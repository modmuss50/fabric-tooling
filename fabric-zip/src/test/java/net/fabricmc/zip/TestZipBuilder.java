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

package net.fabricmc.zip;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

final class TestZipBuilder {
	private static final Charset CP437 = Charset.forName("IBM437");
	private static final int METHOD_STORED = 0;
	private static final int METHOD_DEFLATED = 8;
	private static final int FLAG_DATA_DESCRIPTOR = 1 << 3;
	private static final int FLAG_UTF8 = 1 << 11;
	private static final long UINT32_MAX = 0xFFFFFFFFL;
	private static final Instant DEFAULT_TIME = Instant.parse("2024-01-02T03:04:06Z");

	private final List<EntrySpec> entries = new ArrayList<>();
	private String comment = "";
	private boolean forceZip64;
	private int diskNumber;
	private int centralDirectoryDiskNumber;

	TestZipBuilder comment(String comment) {
		this.comment = Objects.requireNonNull(comment, "comment");
		return this;
	}

	TestZipBuilder forceZip64() {
		this.forceZip64 = true;
		return this;
	}

	TestZipBuilder diskNumbers(int diskNumber, int centralDirectoryDiskNumber) {
		this.diskNumber = diskNumber;
		this.centralDirectoryDiskNumber = centralDirectoryDiskNumber;
		return this;
	}

	EntrySpec addStored(String name, byte[] data) {
		EntrySpec entry = new EntrySpec(name, data, METHOD_STORED);
		entries.add(entry);
		return entry;
	}

	EntrySpec addDeflated(String name, byte[] data) {
		EntrySpec entry = new EntrySpec(name, data, METHOD_DEFLATED);
		entries.add(entry);
		return entry;
	}

	byte[] build() throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		List<EncodedEntry> encodedEntries = new ArrayList<>();

		for (EntrySpec entry : entries) {
			encodedEntries.add(entry.encode());
		}

		for (EncodedEntry entry : encodedEntries) {
			entry.localHeaderOffset = output.size();
			writeLocalFileHeader(output, entry);
			output.write(entry.compressedData);

			if (entry.usesDataDescriptor()) {
				writeDataDescriptor(output, entry);
			}
		}

		long centralDirectoryOffset = output.size();

		for (EncodedEntry entry : encodedEntries) {
			entry.centralDirectoryOffset = output.size();
			writeCentralDirectoryHeader(output, entry);
		}

		long centralDirectorySize = output.size() - centralDirectoryOffset;
		boolean useZip64 = forceZip64 || encodedEntries.stream().anyMatch(EncodedEntry::requiresZip64);

		if (useZip64) {
			long zip64EocdOffset = output.size();
			writeZip64EndOfCentralDirectory(output, encodedEntries.size(), centralDirectorySize, centralDirectoryOffset);
			writeZip64Locator(output, zip64EocdOffset);
			writeEndOfCentralDirectory(output, encodedEntries.size(), centralDirectorySize, centralDirectoryOffset, true);
		} else {
			writeEndOfCentralDirectory(output, encodedEntries.size(), centralDirectorySize, centralDirectoryOffset, false);
		}

		output.write(comment.getBytes(StandardCharsets.UTF_8));
		return output.toByteArray();
	}

	private void writeLocalFileHeader(ByteArrayOutputStream output, EncodedEntry entry) {
		int versionNeeded = entry.requiresZip64() ? 45 : 20;
		int dosTime = toDosTime(entry.lastModifiedTime);
		int dosDate = toDosDate(entry.lastModifiedTime);
		boolean zip64 = entry.requiresZip64();

		writeInt(output, 0x04034b50);
		writeShort(output, versionNeeded);
		writeShort(output, entry.flags);
		writeShort(output, entry.method);
		writeShort(output, dosTime);
		writeShort(output, dosDate);
		writeInt(output, entry.usesDataDescriptor() ? 0 : entry.crc32);
		writeInt(output, zip64 ? UINT32_MAX : (entry.usesDataDescriptor() ? 0 : entry.compressedData.length));
		writeInt(output, zip64 ? UINT32_MAX : (entry.usesDataDescriptor() ? 0 : entry.uncompressedData.length));
		writeShort(output, entry.nameBytes.length);
		writeShort(output, entry.localExtra.length);
		output.writeBytes(entry.nameBytes);
		output.writeBytes(entry.localExtra);
	}

	private void writeDataDescriptor(ByteArrayOutputStream output, EncodedEntry entry) {
		writeInt(output, 0x08074b50);
		writeInt(output, entry.crc32);

		if (entry.requiresZip64()) {
			writeLong(output, entry.compressedData.length);
			writeLong(output, entry.uncompressedData.length);
		} else {
			writeInt(output, entry.compressedData.length);
			writeInt(output, entry.uncompressedData.length);
		}
	}

	private void writeCentralDirectoryHeader(ByteArrayOutputStream output, EncodedEntry entry) {
		int versionNeeded = entry.requiresZip64() ? 45 : 20;
		int dosTime = toDosTime(entry.lastModifiedTime);
		int dosDate = toDosDate(entry.lastModifiedTime);
		boolean zip64 = entry.requiresZip64();
		byte[] centralExtra = zip64 ? concat(entry.timeExtra, zip64Extra(true, entry.uncompressedData.length, true, entry.compressedData.length, true, entry.localHeaderOffset)) : entry.timeExtra;

		writeInt(output, 0x02014b50);
		writeShort(output, 45);
		writeShort(output, versionNeeded);
		writeShort(output, entry.flags);
		writeShort(output, entry.method);
		writeShort(output, dosTime);
		writeShort(output, dosDate);
		writeInt(output, entry.crc32);
		writeInt(output, zip64 ? UINT32_MAX : entry.compressedData.length);
		writeInt(output, zip64 ? UINT32_MAX : entry.uncompressedData.length);
		writeShort(output, entry.nameBytes.length);
		writeShort(output, centralExtra.length);
		writeShort(output, entry.commentBytes.length);
		writeShort(output, entry.diskNumberStart);
		writeShort(output, 0);
		writeInt(output, 0);
		writeInt(output, zip64 ? UINT32_MAX : entry.localHeaderOffset);
		output.writeBytes(entry.nameBytes);
		output.writeBytes(centralExtra);
		output.writeBytes(entry.commentBytes);
	}

	private void writeZip64EndOfCentralDirectory(ByteArrayOutputStream output, int entryCount, long centralDirectorySize, long centralDirectoryOffset) {
		writeInt(output, 0x06064b50);
		writeLong(output, 44);
		writeShort(output, 45);
		writeShort(output, 45);
		writeInt(output, 0);
		writeInt(output, 0);
		writeLong(output, entryCount);
		writeLong(output, entryCount);
		writeLong(output, centralDirectorySize);
		writeLong(output, centralDirectoryOffset);
	}

	private void writeZip64Locator(ByteArrayOutputStream output, long zip64EocdOffset) {
		writeInt(output, 0x07064b50);
		writeInt(output, 0);
		writeLong(output, zip64EocdOffset);
		writeInt(output, 1);
	}

	private void writeEndOfCentralDirectory(ByteArrayOutputStream output, int entryCount, long centralDirectorySize, long centralDirectoryOffset, boolean zip64) {
		writeInt(output, 0x06054b50);
		writeShort(output, diskNumber);
		writeShort(output, centralDirectoryDiskNumber);
		writeShort(output, zip64 ? 0xFFFF : entryCount);
		writeShort(output, zip64 ? 0xFFFF : entryCount);
		writeInt(output, zip64 ? UINT32_MAX : centralDirectorySize);
		writeInt(output, zip64 ? UINT32_MAX : centralDirectoryOffset);
		writeShort(output, comment.getBytes(StandardCharsets.UTF_8).length);
	}

	static int findLastSignature(byte[] data, int signature) {
		for (int i = data.length - 4; i >= 0; i--) {
			if (readInt(data, i) == signature) {
				return i;
			}
		}

		return -1;
	}

	static int readInt(byte[] data, int offset) {
		return (data[offset] & 0xFF)
				| ((data[offset + 1] & 0xFF) << 8)
				| ((data[offset + 2] & 0xFF) << 16)
				| ((data[offset + 3] & 0xFF) << 24);
	}

	static void writeInt(byte[] data, int offset, long value) {
		data[offset] = (byte) value;
		data[offset + 1] = (byte) (value >>> 8);
		data[offset + 2] = (byte) (value >>> 16);
		data[offset + 3] = (byte) (value >>> 24);
	}

	private static byte[] deflate(byte[] data) throws IOException {
		Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
		deflater.setInput(data);
		deflater.finish();
		byte[] buffer = new byte[256];
		ByteArrayOutputStream output = new ByteArrayOutputStream();

		try {
			while (!deflater.finished()) {
				int written = deflater.deflate(buffer);
				output.write(buffer, 0, written);
			}
		} finally {
			deflater.end();
		}

		return output.toByteArray();
	}

	private static int toDosTime(Instant instant) {
		ZonedDateTime dateTime = instant.atZone(ZoneOffset.UTC);
		return (dateTime.getHour() << 11) | (dateTime.getMinute() << 5) | (dateTime.getSecond() / 2);
	}

	private static int toDosDate(Instant instant) {
		ZonedDateTime dateTime = instant.atZone(ZoneOffset.UTC);
		return ((dateTime.getYear() - 1980) << 9) | (dateTime.getMonthValue() << 5) | dateTime.getDayOfMonth();
	}

	private static byte[] timestampExtra(Instant modifiedTime, Instant accessTime, Instant creationTime) {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		writeShort(output, 0x5455);
		writeShort(output, 13);
		output.write(0b0000_0111);
		writeInt(output, modifiedTime.getEpochSecond());
		writeInt(output, accessTime.getEpochSecond());
		writeInt(output, creationTime.getEpochSecond());
		return output.toByteArray();
	}

	private static byte[] zip64Extra(boolean includeUncompressedSize, long uncompressedSize, boolean includeCompressedSize, long compressedSize, boolean includeLocalOffset, long localOffset) {
		ByteArrayOutputStream data = new ByteArrayOutputStream();

		if (includeUncompressedSize) {
			writeLong(data, uncompressedSize);
		}

		if (includeCompressedSize) {
			writeLong(data, compressedSize);
		}

		if (includeLocalOffset) {
			writeLong(data, localOffset);
		}

		ByteArrayOutputStream output = new ByteArrayOutputStream();
		writeShort(output, 0x0001);
		writeShort(output, data.size());
		output.writeBytes(data.toByteArray());
		return output.toByteArray();
	}

	private static void writeShort(ByteArrayOutputStream output, long value) {
		output.write((int) (value & 0xFF));
		output.write((int) ((value >>> 8) & 0xFF));
	}

	private static void writeInt(ByteArrayOutputStream output, long value) {
		output.write((int) (value & 0xFF));
		output.write((int) ((value >>> 8) & 0xFF));
		output.write((int) ((value >>> 16) & 0xFF));
		output.write((int) ((value >>> 24) & 0xFF));
	}

	private static void writeLong(ByteArrayOutputStream output, long value) {
		output.write((int) (value & 0xFF));
		output.write((int) ((value >>> 8) & 0xFF));
		output.write((int) ((value >>> 16) & 0xFF));
		output.write((int) ((value >>> 24) & 0xFF));
		output.write((int) ((value >>> 32) & 0xFF));
		output.write((int) ((value >>> 40) & 0xFF));
		output.write((int) ((value >>> 48) & 0xFF));
		output.write((int) ((value >>> 56) & 0xFF));
	}

	final class EntrySpec {
		private final String name;
		private final byte[] data;
		private final int method;
		private String comment = "";
		private boolean utf8 = true;
		private boolean dataDescriptor;
		private boolean forceZip64;
		private Instant lastModifiedTime = DEFAULT_TIME;
		private Instant lastAccessTime = DEFAULT_TIME.plusSeconds(60);
		private Instant creationTime = DEFAULT_TIME.plusSeconds(120);
		private int extraFlags;
		private int diskNumberStart;

		private EntrySpec(String name, byte[] data, int method) {
			this.name = Objects.requireNonNull(name, "name");
			this.data = Objects.requireNonNull(data, "data");
			this.method = method;
		}

		EntrySpec comment(String comment) {
			this.comment = Objects.requireNonNull(comment, "comment");
			return this;
		}

		EntrySpec utf8(boolean utf8) {
			this.utf8 = utf8;
			return this;
		}

		EntrySpec dataDescriptor() {
			this.dataDescriptor = true;
			return this;
		}

		EntrySpec forceZip64() {
			this.forceZip64 = true;
			return this;
		}

		EntrySpec flags(int extraFlags) {
			this.extraFlags = extraFlags;
			return this;
		}

		EntrySpec diskNumberStart(int diskNumberStart) {
			this.diskNumberStart = diskNumberStart;
			return this;
		}

		EntrySpec timestamps(Instant modifiedTime, Instant accessTime, Instant creationTime) {
			this.lastModifiedTime = Objects.requireNonNull(modifiedTime, "modifiedTime");
			this.lastAccessTime = Objects.requireNonNull(accessTime, "accessTime");
			this.creationTime = Objects.requireNonNull(creationTime, "creationTime");
			return this;
		}

		private EncodedEntry encode() throws IOException {
			Charset charset = utf8 ? StandardCharsets.UTF_8 : CP437;
			byte[] nameBytes = name.getBytes(charset);
			byte[] commentBytes = comment.getBytes(charset);
			byte[] compressedData = method == METHOD_STORED ? data : deflate(data);
			CRC32 crc32 = new CRC32();
			crc32.update(data);
			boolean zip64 = forceZip64;
			byte[] timeExtra = timestampExtra(lastModifiedTime, lastAccessTime, creationTime);
			byte[] localExtra = zip64 ? concat(timeExtra, zip64Extra(true, data.length, true, compressedData.length, false, 0)) : timeExtra;
			int flags = extraFlags | (utf8 ? FLAG_UTF8 : 0) | (dataDescriptor ? FLAG_DATA_DESCRIPTOR : 0);
			return new EncodedEntry(nameBytes, commentBytes, timeExtra, localExtra, data, compressedData, (int) crc32.getValue(), method, flags, diskNumberStart, zip64, dataDescriptor, lastModifiedTime);
		}
	}

	private static final class EncodedEntry {
		private final byte[] nameBytes;
		private final byte[] commentBytes;
		private final byte[] timeExtra;
		private final byte[] localExtra;
		private final byte[] uncompressedData;
		private final byte[] compressedData;
		private final int crc32;
		private final int method;
		private final int flags;
		private final int diskNumberStart;
		private final boolean requiresZip64;
		private final boolean usesDataDescriptor;
		private final Instant lastModifiedTime;
		private long localHeaderOffset;
		private long centralDirectoryOffset;

		private EncodedEntry(byte[] nameBytes, byte[] commentBytes, byte[] timeExtra, byte[] localExtra, byte[] uncompressedData, byte[] compressedData, int crc32, int method, int flags, int diskNumberStart, boolean requiresZip64, boolean usesDataDescriptor, Instant lastModifiedTime) {
			this.nameBytes = nameBytes;
			this.commentBytes = commentBytes;
			this.timeExtra = timeExtra;
			this.localExtra = localExtra;
			this.uncompressedData = uncompressedData;
			this.compressedData = compressedData;
			this.crc32 = crc32;
			this.method = method;
			this.flags = flags;
			this.diskNumberStart = diskNumberStart;
			this.requiresZip64 = requiresZip64;
			this.usesDataDescriptor = usesDataDescriptor;
			this.lastModifiedTime = lastModifiedTime;
		}

		private boolean requiresZip64() {
			return requiresZip64;
		}

		private boolean usesDataDescriptor() {
			return usesDataDescriptor;
		}
	}

	private static byte[] concat(byte[] first, byte[] second) {
		byte[] combined = new byte[first.length + second.length];
		System.arraycopy(first, 0, combined, 0, first.length);
		System.arraycopy(second, 0, combined, first.length, second.length);
		return combined;
	}
}
