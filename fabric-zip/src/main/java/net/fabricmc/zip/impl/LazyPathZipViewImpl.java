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
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import net.fabricmc.zip.api.CompressionCodec;
import net.fabricmc.zip.api.CompressionMethod;
import net.fabricmc.zip.api.MalformedZipException;
import net.fabricmc.zip.api.UnsupportedZipFeatureException;
import net.fabricmc.zip.api.ZipView;

public final class LazyPathZipViewImpl implements ZipView {
	private static final long UINT32_MAX = 0xFFFFFFFFL;

	private final PathZipByteSource source;
	private final CompressionCodec compressionCodec;
	private final byte[] centralDirectory;
	private final EntryIndex[] entryIndexes;
	private final ConcurrentHashMap<String, ZipEntryViewImpl> partialEntriesByName = new ConcurrentHashMap<>();
	private final AtomicBoolean closed = new AtomicBoolean();
	private final Object materializationLock = new Object();

	private volatile List<net.fabricmc.zip.api.ZipEntryView> entries;
	private volatile Map<String, net.fabricmc.zip.api.ZipEntryView> entriesByName;

	private LazyPathZipViewImpl(PathZipByteSource source, CompressionCodec compressionCodec, byte[] centralDirectory, EntryIndex[] entryIndexes) {
		this.source = source;
		this.compressionCodec = compressionCodec;
		this.centralDirectory = centralDirectory;
		this.entryIndexes = entryIndexes;
	}

	public static ZipView open(Path path, CompressionCodec compressionCodec) throws IOException {
		Objects.requireNonNull(path, "path");
		Objects.requireNonNull(compressionCodec, "compressionCodec");

		PathZipByteSource source = new PathZipByteSource(path);

		try {
			ZipParser.CentralDirectoryData centralDirectory = ZipParser.locateCentralDirectory(source);

			if (centralDirectory.size() > Integer.MAX_VALUE) {
				return ZipViewImpl.open(source, compressionCodec);
			}

			byte[] centralDirectoryBytes = new byte[(int) centralDirectory.size()];
			source.readFully(centralDirectory.offset(), centralDirectoryBytes);
			return new LazyPathZipViewImpl(
					source,
					compressionCodec,
					centralDirectoryBytes,
					buildEntryIndexes(centralDirectoryBytes, (int) centralDirectory.entryCount())
			);
		} catch (IOException | RuntimeException | Error throwable) {
			try {
				source.close();
			} catch (IOException closeException) {
				throwable.addSuppressed(closeException);
			}

			throw throwable;
		}
	}

	@Override
	public List<net.fabricmc.zip.api.ZipEntryView> entries() {
		ensureOpen();

		List<net.fabricmc.zip.api.ZipEntryView> materializedEntries = entries;

		if (materializedEntries != null) {
			return materializedEntries;
		}

		synchronized (materializationLock) {
			materializedEntries = entries;

			if (materializedEntries == null) {
				MaterializedEntries materialized = materializeEntries();
				entries = materialized.entries();
				entriesByName = materialized.entriesByName();
				materializedEntries = materialized.entries();
			}
		}

		return materializedEntries;
	}

	@Override
	public Optional<net.fabricmc.zip.api.ZipEntryView> getEntry(String name) {
		ensureOpen();
		Objects.requireNonNull(name, "name");

		Map<String, net.fabricmc.zip.api.ZipEntryView> materializedEntriesByName = entriesByName;

		if (materializedEntriesByName != null) {
			return Optional.ofNullable(materializedEntriesByName.get(name));
		}

		ZipEntryViewImpl cachedEntry = partialEntriesByName.get(name);

		if (cachedEntry != null) {
			return Optional.of(cachedEntry);
		}

		return Optional.ofNullable(scanEntry(name));
	}

	@Override
	public InputStream open(net.fabricmc.zip.api.ZipEntryView entry) throws IOException {
		ZipEntryViewImpl zipEntry = requireEntry(entry);
		InputStream raw = openRaw(zipEntry);
		return compressionCodec.decompress(zipEntry, raw);
	}

	@Override
	public InputStream openRaw(net.fabricmc.zip.api.ZipEntryView entry) throws IOException {
		return openRaw(requireEntry(entry));
	}

	@Override
	public void close() throws IOException {
		if (!closed.compareAndSet(false, true)) {
			return;
		}

		source.close();
	}

	private InputStream openRaw(ZipEntryViewImpl entry) throws IOException {
		ensureOpen();
		return ZipEntryStreams.openRaw(source, entry);
	}

	private ZipEntryViewImpl requireEntry(net.fabricmc.zip.api.ZipEntryView entry) {
		ensureOpen();
		Objects.requireNonNull(entry, "entry");

		if (!(entry instanceof ZipEntryViewImpl zipEntry) || zipEntry.archive() != this) {
			throw new IllegalArgumentException("Entry does not belong to this archive view");
		}

		return zipEntry;
	}

	private ZipEntryViewImpl scanEntry(String name) {
		for (EntryIndex entryIndex : entryIndexes) {
			ZipEntryViewImpl entry = createEntry(entryIndex);

			if (entry.getName().equals(name)) {
				partialEntriesByName.putIfAbsent(name, entry);
				return entry;
			}
		}

		return null;
	}

	private MaterializedEntries materializeEntries() {
		List<net.fabricmc.zip.api.ZipEntryView> materializedEntries = new ArrayList<>(entryIndexes.length);
		Map<String, net.fabricmc.zip.api.ZipEntryView> materializedEntriesByName = new LinkedHashMap<>();

		for (EntryIndex entryIndex : entryIndexes) {
			ZipEntryViewImpl entry = createEntry(entryIndex);
			materializedEntries.add(entry);
			materializedEntriesByName.putIfAbsent(entry.getName(), entry);
			partialEntriesByName.putIfAbsent(entry.getName(), entry);
		}

		return new MaterializedEntries(
				Collections.unmodifiableList(materializedEntries),
				Collections.unmodifiableMap(materializedEntriesByName)
		);
	}

	private static EntryIndex[] buildEntryIndexes(byte[] centralDirectory, int entryCount) throws IOException {
		EntryIndex[] entryIndexes = new EntryIndex[entryCount];
		int offset = 0;

		for (int index = 0; index < entryCount; index++) {
			entryIndexes[index] = readEntryIndex(centralDirectory, offset);
			offset = entryIndexes[index].nextOffset();
		}

		if (offset != centralDirectory.length) {
			throw new MalformedZipException("Trailing data after central directory");
		}

		return entryIndexes;
	}

	private static EntryIndex readEntryIndex(byte[] centralDirectory, int offset) throws IOException {
		if (offset + ZipConstants.CENTRAL_DIRECTORY_HEADER_LENGTH > centralDirectory.length) {
			throw new MalformedZipException("Truncated central directory entry");
		}

		if (ZipParser.readInt(centralDirectory, offset) != ZipConstants.CENTRAL_DIRECTORY_HEADER_SIGNATURE) {
			throw new MalformedZipException("Invalid central directory file header signature at offset " + offset);
		}

		int flags = ZipParser.readUnsignedShort(centralDirectory, offset + 8);
		int methodCode = ZipParser.readUnsignedShort(centralDirectory, offset + 10);
		int lastModifiedTime = ZipParser.readUnsignedShort(centralDirectory, offset + 12);
		int lastModifiedDate = ZipParser.readUnsignedShort(centralDirectory, offset + 14);
		long crc32 = ZipParser.readUnsignedInt(centralDirectory, offset + 16);
		long compressedSize = ZipParser.readUnsignedInt(centralDirectory, offset + 20);
		long uncompressedSize = ZipParser.readUnsignedInt(centralDirectory, offset + 24);
		int nameLength = ZipParser.readUnsignedShort(centralDirectory, offset + 28);
		int extraLength = ZipParser.readUnsignedShort(centralDirectory, offset + 30);
		int commentLength = ZipParser.readUnsignedShort(centralDirectory, offset + 32);
		int diskNumberStart = ZipParser.readUnsignedShort(centralDirectory, offset + 34);
		long localHeaderOffset = ZipParser.readUnsignedInt(centralDirectory, offset + 42);

		if (diskNumberStart != 0) {
			throw new UnsupportedZipFeatureException("Multi-disk ZIP entries are not supported");
		}

		if ((flags & ZipConstants.GENERAL_PURPOSE_FLAG_ENCRYPTED) != 0) {
			throw new UnsupportedZipFeatureException("Encrypted ZIP entries are not supported");
		}

		int variableOffset = offset + ZipConstants.CENTRAL_DIRECTORY_HEADER_LENGTH;
		int variableLength = nameLength + extraLength + commentLength;
		int nextOffset = variableOffset + variableLength;

		if (nextOffset > centralDirectory.length) {
			throw new MalformedZipException("Truncated central directory entry");
		}

		byte[] extraBytes = ZipParser.slice(centralDirectory, variableOffset + nameLength, extraLength);
		ZipParser.Zip64Values zip64Values = ZipParser.parseZip64Extra(
				extraBytes,
				uncompressedSize == UINT32_MAX,
				compressedSize == UINT32_MAX,
				localHeaderOffset == UINT32_MAX,
				false
		);

		if (uncompressedSize == UINT32_MAX) {
			uncompressedSize = zip64Values.uncompressedSize();
		}

		if (compressedSize == UINT32_MAX) {
			compressedSize = zip64Values.compressedSize();
		}

		if (localHeaderOffset == UINT32_MAX) {
			localHeaderOffset = zip64Values.localHeaderOffset();
		}

		ZipParser.Timestamps timestamps = ZipParser.parseTimestamps(
				extraBytes,
				lastModifiedDate,
				lastModifiedTime
		);
		CompressionMethod method = CompressionMethod.fromCode(methodCode);

		if (method == null) {
			throw new UnsupportedZipFeatureException("Unsupported compression method: " + methodCode);
		}

		return new EntryIndex(
				offset,
				nextOffset,
				flags,
				method,
				crc32,
				compressedSize,
				uncompressedSize,
				localHeaderOffset,
				nameLength,
				extraLength,
				commentLength,
				variableOffset,
				timestamps.lastModifiedTime(),
				timestamps.lastAccessTime(),
				timestamps.creationTime()
		);
	}

	private ZipEntryViewImpl createEntry(EntryIndex entryIndex) {
		String name = new String(
				centralDirectory,
				entryIndex.variableOffset(),
				entryIndex.nameLength(),
				ZipParser.charsetForFlags(entryIndex.flags())
		);
		String comment = entryIndex.commentLength() == 0 ? null : new String(
				centralDirectory,
				entryIndex.variableOffset() + entryIndex.nameLength() + entryIndex.extraLength(),
				entryIndex.commentLength(),
				ZipParser.charsetForFlags(entryIndex.flags())
		);

		return new ZipEntryViewImpl(
				this,
				name,
				comment,
				entryIndex.method(),
				entryIndex.flags(),
				entryIndex.crc32(),
				entryIndex.compressedSize(),
				entryIndex.uncompressedSize(),
				entryIndex.localHeaderOffset(),
				entryIndex.centralDirectoryOffset(),
				name.endsWith("/"),
				entryIndex.lastModifiedTime(),
				entryIndex.lastAccessTime(),
				entryIndex.creationTime()
		);
	}

	private void ensureOpen() {
		if (closed.get()) {
			throw new IllegalStateException("ZIP view is closed");
		}
	}

	private record MaterializedEntries(List<net.fabricmc.zip.api.ZipEntryView> entries, Map<String, net.fabricmc.zip.api.ZipEntryView> entriesByName) {
	}

	private record EntryIndex(
			int centralDirectoryOffset,
			int nextOffset,
			int flags,
			CompressionMethod method,
			long crc32,
			long compressedSize,
			long uncompressedSize,
			long localHeaderOffset,
			int nameLength,
			int extraLength,
			int commentLength,
			int variableOffset,
			java.nio.file.attribute.FileTime lastModifiedTime,
			java.nio.file.attribute.FileTime lastAccessTime,
			java.nio.file.attribute.FileTime creationTime
	) {
	}
}
