/*
 * Copyright (c) 2026 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.zip.impl;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.fabricmc.zip.api.CompressionMethod;
import net.fabricmc.zip.api.MalformedZipException;
import net.fabricmc.zip.api.UnsupportedZipFeatureException;
import net.fabricmc.zip.api.ZipByteSource;

final class ZipParser {
	private static final Charset LEGACY_ZIP_CHARSET = Charset.forName("IBM437");
	private static final long UINT16_MAX = 0xFFFFL;
	private static final long UINT32_MAX = 0xFFFFFFFFL;

	private ZipParser() {
	}

	static List<EntryData> parse(ZipByteSource source) throws IOException {
		return parseArchive(source).entries();
	}

	static CentralDirectoryData locateCentralDirectory(ZipByteSource source) throws IOException {
		long size = source.size();
		EndOfCentralDirectory endOfCentralDirectory = locateEndOfCentralDirectory(source, size);

		if (endOfCentralDirectory.diskNumber != 0 || endOfCentralDirectory.centralDirectoryDiskNumber != 0) {
			throw new UnsupportedZipFeatureException("Multi-disk ZIP archives are not supported");
		}

		CentralDirectory centralDirectory = resolveCentralDirectory(source, endOfCentralDirectory);

		if (centralDirectory.entryCount > Integer.MAX_VALUE) {
			throw new UnsupportedZipFeatureException("ZIP archives with more than " + Integer.MAX_VALUE + " entries are not supported");
		}

		return new CentralDirectoryData(centralDirectory.entryCount, centralDirectory.size, centralDirectory.offset);
	}

	static ParsedZip parseArchive(ZipByteSource source) throws IOException {
		long size = source.size();
		CentralDirectoryData centralDirectory = locateCentralDirectory(source);
		List<EntryData> entries = new ArrayList<>((int) centralDirectory.entryCount);
		long position = centralDirectory.offset;

		for (long index = 0; index < centralDirectory.entryCount; index++) {
			byte[] fixedHeader = new byte[ZipConstants.CENTRAL_DIRECTORY_HEADER_LENGTH];
			source.readFully(position, fixedHeader);

			if (readInt(fixedHeader, 0) != ZipConstants.CENTRAL_DIRECTORY_HEADER_SIGNATURE) {
				throw new MalformedZipException("Invalid central directory file header signature at offset " + position);
			}

			int flags = readUnsignedShort(fixedHeader, 8);
			int methodCode = readUnsignedShort(fixedHeader, 10);
			int lastModifiedTime = readUnsignedShort(fixedHeader, 12);
			int lastModifiedDate = readUnsignedShort(fixedHeader, 14);
			long crc32 = readUnsignedInt(fixedHeader, 16);
			long compressedSize = readUnsignedInt(fixedHeader, 20);
			long uncompressedSize = readUnsignedInt(fixedHeader, 24);
			int nameLength = readUnsignedShort(fixedHeader, 28);
			int extraLength = readUnsignedShort(fixedHeader, 30);
			int commentLength = readUnsignedShort(fixedHeader, 32);
			int diskNumberStart = readUnsignedShort(fixedHeader, 34);
			long localHeaderOffset = readUnsignedInt(fixedHeader, 42);

			if (diskNumberStart != 0) {
				throw new UnsupportedZipFeatureException("Multi-disk ZIP entries are not supported");
			}

			if ((flags & ZipConstants.GENERAL_PURPOSE_FLAG_ENCRYPTED) != 0) {
				throw new UnsupportedZipFeatureException("Encrypted ZIP entries are not supported");
			}

			CompressionMethod method = CompressionMethod.fromCode(methodCode);

			if (method == null) {
				throw new UnsupportedZipFeatureException("Unsupported compression method: " + methodCode);
			}

			int variableLength = nameLength + extraLength + commentLength;
			byte[] variableData = new byte[variableLength];
			source.readFully(position + ZipConstants.CENTRAL_DIRECTORY_HEADER_LENGTH, variableData);

			byte[] nameBytes = slice(variableData, 0, nameLength);
			byte[] extraBytes = slice(variableData, nameLength, extraLength);
			byte[] commentBytes = slice(variableData, nameLength + extraLength, commentLength);

			Zip64Values zip64Values = parseZip64Extra(extraBytes,
					uncompressedSize == UINT32_MAX,
					compressedSize == UINT32_MAX,
					localHeaderOffset == UINT32_MAX,
					diskNumberStart == 0xFFFF
			);

			if (uncompressedSize == UINT32_MAX) {
				uncompressedSize = zip64Values.uncompressedSize;
			}

			if (compressedSize == UINT32_MAX) {
				compressedSize = zip64Values.compressedSize;
			}

			if (localHeaderOffset == UINT32_MAX) {
				localHeaderOffset = zip64Values.localHeaderOffset;
			}

			Timestamps timestamps = parseTimestamps(extraBytes, lastModifiedDate, lastModifiedTime);
			Charset charset = (flags & ZipConstants.GENERAL_PURPOSE_FLAG_UTF8) != 0 ? StandardCharsets.UTF_8 : LEGACY_ZIP_CHARSET;
			String name = new String(nameBytes, charset);
			String comment = commentLength == 0 ? null : new String(commentBytes, charset);

			entries.add(new EntryData(
					name,
					comment,
					method,
					flags,
					crc32,
					compressedSize,
					uncompressedSize,
					localHeaderOffset,
					position,
					name.endsWith("/"),
					timestamps.lastModifiedTime,
					timestamps.lastAccessTime,
					timestamps.creationTime
			));

			position += ZipConstants.CENTRAL_DIRECTORY_HEADER_LENGTH + variableLength;
		}

		return new ParsedZip(entries, centralDirectory.offset, size - centralDirectory.offset);
	}

	private static CentralDirectory resolveCentralDirectory(ZipByteSource source, EndOfCentralDirectory endOfCentralDirectory) throws IOException {
		boolean needsZip64 = endOfCentralDirectory.entryCount == UINT16_MAX
				|| endOfCentralDirectory.centralDirectoryOffset == UINT32_MAX
				|| endOfCentralDirectory.centralDirectorySize == UINT32_MAX;

		if (!needsZip64) {
			return new CentralDirectory(
					endOfCentralDirectory.entryCount,
					endOfCentralDirectory.centralDirectorySize,
					endOfCentralDirectory.centralDirectoryOffset
			);
		}

		if (endOfCentralDirectory.offset < ZipConstants.ZIP64_LOCATOR_LENGTH) {
			throw new MalformedZipException("Missing ZIP64 end of central directory locator");
		}

		byte[] locator = new byte[ZipConstants.ZIP64_LOCATOR_LENGTH];
		source.readFully(endOfCentralDirectory.offset - ZipConstants.ZIP64_LOCATOR_LENGTH, locator);

		if (readInt(locator, 0) != ZipConstants.ZIP64_LOCATOR_SIGNATURE) {
			throw new MalformedZipException("Missing ZIP64 end of central directory locator");
		}

		if (readInt(locator, 4) != 0 || readInt(locator, 16) != 1) {
			throw new UnsupportedZipFeatureException("Multi-disk ZIP64 archives are not supported");
		}

		long zip64Offset = readLong(locator, 8);
		byte[] zip64Eocd = new byte[ZipConstants.ZIP64_EOCD_MIN_LENGTH];
		source.readFully(zip64Offset, zip64Eocd);

		if (readInt(zip64Eocd, 0) != ZipConstants.ZIP64_EOCD_SIGNATURE) {
			throw new MalformedZipException("Invalid ZIP64 end of central directory signature");
		}

		if (readInt(zip64Eocd, 16) != 0 || readInt(zip64Eocd, 20) != 0) {
			throw new UnsupportedZipFeatureException("Multi-disk ZIP64 archives are not supported");
		}

		return new CentralDirectory(
				readLong(zip64Eocd, 32),
				readLong(zip64Eocd, 40),
				readLong(zip64Eocd, 48)
		);
	}

	private static EndOfCentralDirectory locateEndOfCentralDirectory(ZipByteSource source, long size) throws IOException {
		int tailLength = (int) Math.min(size, ZipConstants.EOCD_LENGTH + UINT16_MAX);
		byte[] tail = new byte[tailLength];
		source.readFully(size - tailLength, tail);

		for (int index = tailLength - ZipConstants.EOCD_LENGTH; index >= 0; index--) {
			if (readInt(tail, index) != ZipConstants.EOCD_SIGNATURE) {
				continue;
			}

			int commentLength = readUnsignedShort(tail, index + 20);

			if (index + ZipConstants.EOCD_LENGTH + commentLength != tailLength) {
				continue;
			}

			return new EndOfCentralDirectory(
					size - tailLength + index,
					readUnsignedShort(tail, index + 4),
					readUnsignedShort(tail, index + 6),
					readUnsignedShort(tail, index + 10),
					readUnsignedInt(tail, index + 12),
					readUnsignedInt(tail, index + 16)
			);
		}

		throw new MalformedZipException("Could not locate end of central directory record");
	}

	static Charset charsetForFlags(int flags) {
		return (flags & ZipConstants.GENERAL_PURPOSE_FLAG_UTF8) != 0 ? StandardCharsets.UTF_8 : LEGACY_ZIP_CHARSET;
	}

	static Zip64Values parseZip64Extra(byte[] extraBytes, boolean needsUncompressedSize, boolean needsCompressedSize, boolean needsLocalHeaderOffset, boolean needsDiskNumber) throws IOException {
		return parseZip64Extra(extraBytes, 0, extraBytes.length, needsUncompressedSize, needsCompressedSize, needsLocalHeaderOffset, needsDiskNumber);
	}

	static Zip64Values parseZip64Extra(byte[] extraBytes, int extraOffset, int extraLength, boolean needsUncompressedSize, boolean needsCompressedSize, boolean needsLocalHeaderOffset, boolean needsDiskNumber) throws IOException {
		Zip64Values values = new Zip64Values(-1L, -1L, -1L);
		int offset = extraOffset;
		int endOffset = extraOffset + extraLength;

		while (offset + 4 <= endOffset) {
			int headerId = readUnsignedShort(extraBytes, offset);
			int dataSize = readUnsignedShort(extraBytes, offset + 2);
			offset += 4;

			if (offset + dataSize > endOffset) {
				throw new MalformedZipException("Truncated ZIP extra field");
			}

			if (headerId == ZipConstants.ZIP64_EXTRA_FIELD_ID) {
				int currentOffset = offset;
				long uncompressedSize = values.uncompressedSize;
				long compressedSize = values.compressedSize;
				long localHeaderOffset = values.localHeaderOffset;

				if (needsUncompressedSize) {
					ensureRemainingExtraData(offset, dataSize, currentOffset, 8);
					uncompressedSize = readLong(extraBytes, currentOffset);
					currentOffset += 8;
				}

				if (needsCompressedSize) {
					ensureRemainingExtraData(offset, dataSize, currentOffset, 8);
					compressedSize = readLong(extraBytes, currentOffset);
					currentOffset += 8;
				}

				if (needsLocalHeaderOffset) {
					ensureRemainingExtraData(offset, dataSize, currentOffset, 8);
					localHeaderOffset = readLong(extraBytes, currentOffset);
					currentOffset += 8;
				}

				if (needsDiskNumber) {
					ensureRemainingExtraData(offset, dataSize, currentOffset, 4);
				}

				values = new Zip64Values(uncompressedSize, compressedSize, localHeaderOffset);
				break;
			}

			offset += dataSize;
		}

		if (needsUncompressedSize && values.uncompressedSize < 0) {
			throw new MalformedZipException("Missing ZIP64 uncompressed size");
		}

		if (needsCompressedSize && values.compressedSize < 0) {
			throw new MalformedZipException("Missing ZIP64 compressed size");
		}

		if (needsLocalHeaderOffset && values.localHeaderOffset < 0) {
			throw new MalformedZipException("Missing ZIP64 local header offset");
		}

		return values;
	}

	private static void ensureRemainingExtraData(int fieldOffset, int fieldLength, int valueOffset, int valueLength) throws MalformedZipException {
		if (valueOffset + valueLength > fieldOffset + fieldLength) {
			throw new MalformedZipException("Truncated ZIP64 extra field");
		}
	}

	static Timestamps parseTimestamps(byte[] extraBytes, int dosDate, int dosTime) throws IOException {
		return parseTimestamps(extraBytes, 0, extraBytes.length, dosDate, dosTime);
	}

	static Timestamps parseTimestamps(byte[] extraBytes, int extraOffset, int extraLength, int dosDate, int dosTime) throws IOException {
		@Nullable FileTime lastModifiedTime = null;
		@Nullable FileTime lastAccessTime = null;
		@Nullable FileTime creationTime = null;

		int offset = extraOffset;
		int endOffset = extraOffset + extraLength;

		while (offset + 4 <= endOffset) {
			int headerId = readUnsignedShort(extraBytes, offset);
			int dataSize = readUnsignedShort(extraBytes, offset + 2);
			offset += 4;

			if (offset + dataSize > endOffset) {
				throw new MalformedZipException("Truncated ZIP extra field");
			}

			if (headerId == ZipConstants.EXTENDED_TIMESTAMP_EXTRA_FIELD_ID && dataSize > 0) {
				int flags = extraBytes[offset] & 0xFF;
				int currentOffset = offset + 1;

				if ((flags & 1) != 0 && currentOffset + 4 <= offset + dataSize) {
					lastModifiedTime = FileTime.from(Instant.ofEpochSecond(readUnsignedInt(extraBytes, currentOffset)));
					currentOffset += 4;
				}

				if ((flags & 2) != 0 && currentOffset + 4 <= offset + dataSize) {
					lastAccessTime = FileTime.from(Instant.ofEpochSecond(readUnsignedInt(extraBytes, currentOffset)));
					currentOffset += 4;
				}

				if ((flags & 4) != 0 && currentOffset + 4 <= offset + dataSize) {
					creationTime = FileTime.from(Instant.ofEpochSecond(readUnsignedInt(extraBytes, currentOffset)));
				}

				break;
			}

			offset += dataSize;
		}

		if (lastModifiedTime == null && dosDate != 0) {
			lastModifiedTime = FileTime.from(dosDateTimeToInstant(dosDate, dosTime));
		}

		return new Timestamps(lastModifiedTime, lastAccessTime, creationTime);
	}

	private static Instant dosDateTimeToInstant(int dosDate, int dosTime) {
		int day = dosDate & 0x1F;
		int month = (dosDate >>> 5) & 0x0F;
		int year = ((dosDate >>> 9) & 0x7F) + 1980;
		int second = (dosTime & 0x1F) * 2;
		int minute = (dosTime >>> 5) & 0x3F;
		int hour = (dosTime >>> 11) & 0x1F;

		LocalDateTime dateTime = LocalDateTime.of(year, month, day, hour, minute, second);
		return dateTime.atZone(ZoneId.systemDefault()).toInstant();
	}

	static int readInt(byte[] data, int offset) {
		return (data[offset] & 0xFF)
				| ((data[offset + 1] & 0xFF) << 8)
				| ((data[offset + 2] & 0xFF) << 16)
				| ((data[offset + 3] & 0xFF) << 24);
	}

	static long readUnsignedInt(byte[] data, int offset) {
		return readInt(data, offset) & 0xFFFFFFFFL;
	}

	static int readUnsignedShort(byte[] data, int offset) {
		return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
	}

	static long readLong(byte[] data, int offset) {
		return (data[offset] & 0xFFL)
				| ((data[offset + 1] & 0xFFL) << 8)
				| ((data[offset + 2] & 0xFFL) << 16)
				| ((data[offset + 3] & 0xFFL) << 24)
				| ((data[offset + 4] & 0xFFL) << 32)
				| ((data[offset + 5] & 0xFFL) << 40)
				| ((data[offset + 6] & 0xFFL) << 48)
				| ((data[offset + 7] & 0xFFL) << 56);
	}

	static byte[] slice(byte[] data, int offset, int length) {
		byte[] slice = new byte[length];
		System.arraycopy(data, offset, slice, 0, length);
		return slice;
	}

	private static final class EndOfCentralDirectory {
		private final long offset;
		private final int diskNumber;
		private final int centralDirectoryDiskNumber;
		private final long entryCount;
		private final long centralDirectorySize;
		private final long centralDirectoryOffset;

		private EndOfCentralDirectory(long offset, int diskNumber, int centralDirectoryDiskNumber, long entryCount, long centralDirectorySize, long centralDirectoryOffset) {
			this.offset = offset;
			this.diskNumber = diskNumber;
			this.centralDirectoryDiskNumber = centralDirectoryDiskNumber;
			this.entryCount = entryCount;
			this.centralDirectorySize = centralDirectorySize;
			this.centralDirectoryOffset = centralDirectoryOffset;
		}
	}

	private static final class CentralDirectory {
		private final long entryCount;
		private final long size;
		private final long offset;

		private CentralDirectory(long entryCount, long size, long offset) {
			this.entryCount = entryCount;
			this.size = size;
			this.offset = offset;
		}
	}

	static final class Zip64Values {
		private final long uncompressedSize;
		private final long compressedSize;
		private final long localHeaderOffset;

		Zip64Values(long uncompressedSize, long compressedSize, long localHeaderOffset) {
			this.uncompressedSize = uncompressedSize;
			this.compressedSize = compressedSize;
			this.localHeaderOffset = localHeaderOffset;
		}

		long uncompressedSize() {
			return uncompressedSize;
		}

		long compressedSize() {
			return compressedSize;
		}

		long localHeaderOffset() {
			return localHeaderOffset;
		}
	}

	static final class Timestamps {
		private final @Nullable FileTime lastModifiedTime;
		private final @Nullable FileTime lastAccessTime;
		private final @Nullable FileTime creationTime;

		Timestamps(@Nullable FileTime lastModifiedTime, @Nullable FileTime lastAccessTime, @Nullable FileTime creationTime) {
			this.lastModifiedTime = lastModifiedTime;
			this.lastAccessTime = lastAccessTime;
			this.creationTime = creationTime;
		}

		@Nullable FileTime lastModifiedTime() {
			return lastModifiedTime;
		}

		@Nullable FileTime lastAccessTime() {
			return lastAccessTime;
		}

		@Nullable FileTime creationTime() {
			return creationTime;
		}
	}

	static final class EntryData {
		private final String name;
		private final @Nullable String comment;
		private final CompressionMethod method;
		private final int flags;
		private final long crc32;
		private final long compressedSize;
		private final long uncompressedSize;
		private final long localHeaderOffset;
		private final long centralDirectoryOffset;
		private final boolean directory;
		private final @Nullable FileTime lastModifiedTime;
		private final @Nullable FileTime lastAccessTime;
		private final @Nullable FileTime creationTime;

		EntryData(
				String name,
				@Nullable String comment,
				CompressionMethod method,
				int flags,
				long crc32,
				long compressedSize,
				long uncompressedSize,
				long localHeaderOffset,
				long centralDirectoryOffset,
				boolean directory,
				@Nullable FileTime lastModifiedTime,
				@Nullable FileTime lastAccessTime,
				@Nullable FileTime creationTime
		) {
			this.name = name;
			this.comment = comment;
			this.method = method;
			this.flags = flags;
			this.crc32 = crc32;
			this.compressedSize = compressedSize;
			this.uncompressedSize = uncompressedSize;
			this.localHeaderOffset = localHeaderOffset;
			this.centralDirectoryOffset = centralDirectoryOffset;
			this.directory = directory;
			this.lastModifiedTime = lastModifiedTime;
			this.lastAccessTime = lastAccessTime;
			this.creationTime = creationTime;
		}

		String name() {
			return name;
		}

		@Nullable String comment() {
			return comment;
		}

		CompressionMethod method() {
			return method;
		}

		int flags() {
			return flags;
		}

		long crc32() {
			return crc32;
		}

		long compressedSize() {
			return compressedSize;
		}

		long uncompressedSize() {
			return uncompressedSize;
		}

		long localHeaderOffset() {
			return localHeaderOffset;
		}

		long centralDirectoryOffset() {
			return centralDirectoryOffset;
		}

		boolean directory() {
			return directory;
		}

		@Nullable FileTime lastModifiedTime() {
			return lastModifiedTime;
		}

		@Nullable FileTime lastAccessTime() {
			return lastAccessTime;
		}

		@Nullable FileTime creationTime() {
			return creationTime;
		}
	}

	static final class ParsedZip {
		private final List<EntryData> entries;
		private final long centralDirectoryOffset;
		private final long trailingMetadataLength;

		ParsedZip(List<EntryData> entries, long centralDirectoryOffset, long trailingMetadataLength) {
			this.entries = entries;
			this.centralDirectoryOffset = centralDirectoryOffset;
			this.trailingMetadataLength = trailingMetadataLength;
		}

		List<EntryData> entries() {
			return entries;
		}

		long centralDirectoryOffset() {
			return centralDirectoryOffset;
		}

		long trailingMetadataLength() {
			return trailingMetadataLength;
		}
	}

	static final class CentralDirectoryData {
		private final long entryCount;
		private final long size;
		private final long offset;

		CentralDirectoryData(long entryCount, long size, long offset) {
			this.entryCount = entryCount;
			this.size = size;
			this.offset = offset;
		}

		long entryCount() {
			return entryCount;
		}

		long size() {
			return size;
		}

		long offset() {
			return offset;
		}
	}
}
