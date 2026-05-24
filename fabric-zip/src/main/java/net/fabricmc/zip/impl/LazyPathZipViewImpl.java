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
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
	private final NameIndex nameIndex;
	private final ZipEntryViewImpl[] entryCache;
	private final AtomicBoolean closed = new AtomicBoolean();
	private final Object materializationLock = new Object();

	private volatile List<net.fabricmc.zip.api.ZipEntryView> entries;
	private volatile Map<String, net.fabricmc.zip.api.ZipEntryView> entriesByName;

	private LazyPathZipViewImpl(PathZipByteSource source, CompressionCodec compressionCodec, byte[] centralDirectory, EntryIndex[] entryIndexes, NameIndex nameIndex) {
		this.source = source;
		this.compressionCodec = compressionCodec;
		this.centralDirectory = centralDirectory;
		this.entryIndexes = entryIndexes;
		this.nameIndex = nameIndex;
		this.entryCache = new ZipEntryViewImpl[entryIndexes.length];
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
			EntryIndex[] entryIndexes = buildEntryIndexes(centralDirectoryBytes, (int) centralDirectory.entryCount());
			return new LazyPathZipViewImpl(
					source,
					compressionCodec,
					centralDirectoryBytes,
					entryIndexes,
					NameIndex.build(centralDirectoryBytes, entryIndexes)
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

		return Optional.ofNullable(indexedEntry(name));
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

	private ZipEntryViewImpl indexedEntry(String name) {
		EntryIndex entryIndex = nameIndex.find(name);

		if (entryIndex == null) {
			return scanEntry(name);
		}

		return entryAt(entryIndex.arrayIndex());
	}

	private ZipEntryViewImpl scanEntry(String name) {
		for (EntryIndex entryIndex : entryIndexes) {
			ZipEntryViewImpl entry = entryAt(entryIndex.arrayIndex());

			if (entry.getName().equals(name)) {
				return entry;
			}
		}

		return null;
	}

	private MaterializedEntries materializeEntries() {
		List<net.fabricmc.zip.api.ZipEntryView> materializedEntries = new ArrayList<>(entryIndexes.length);
		Map<String, net.fabricmc.zip.api.ZipEntryView> materializedEntriesByName = new LinkedHashMap<>();

		for (EntryIndex entryIndex : entryIndexes) {
			ZipEntryViewImpl entry = entryAt(entryIndex.arrayIndex());
			materializedEntries.add(entry);
			materializedEntriesByName.putIfAbsent(entry.getName(), entry);
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
			entryIndexes[index] = readEntryIndex(centralDirectory, offset, index);
			offset = entryIndexes[index].nextOffset();
		}

		if (offset != centralDirectory.length) {
			throw new MalformedZipException("Trailing data after central directory");
		}

		return entryIndexes;
	}

	private static EntryIndex readEntryIndex(byte[] centralDirectory, int offset, int arrayIndex) throws IOException {
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

		int extraOffset = variableOffset + nameLength;

		if (uncompressedSize == UINT32_MAX || compressedSize == UINT32_MAX || localHeaderOffset == UINT32_MAX) {
			ZipParser.Zip64Values zip64Values = ZipParser.parseZip64Extra(
					centralDirectory,
					extraOffset,
					extraLength,
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
		}

		CompressionMethod method = CompressionMethod.fromCode(methodCode);

		if (method == null) {
			throw new UnsupportedZipFeatureException("Unsupported compression method: " + methodCode);
		}

		return new EntryIndex(
				arrayIndex,
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
				asciiNameHash(centralDirectory, variableOffset, nameLength),
				lastModifiedDate,
				lastModifiedTime
		);
	}

	private ZipEntryViewImpl entryAt(int index) {
		ZipEntryViewImpl entry = entryCache[index];

		if (entry != null) {
			return entry;
		}

		entry = createEntry(entryIndexes[index]);
		entryCache[index] = entry;
		return entry;
	}

	private static int asciiNameHash(byte[] centralDirectory, int offset, int length) {
		int hash = 0;

		for (int index = 0; index < length; index++) {
			int value = centralDirectory[offset + index] & 0xFF;

			if (value > 0x7F) {
				return -1;
			}

			hash = 31 * hash + value;
		}

		return hash;
	}

	private ZipEntryViewImpl createEntry(EntryIndex entryIndex) {
		ZipParser.Timestamps timestamps = parseTimestamps(entryIndex);
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
				timestamps.lastModifiedTime(),
				timestamps.lastAccessTime(),
				timestamps.creationTime()
		);
	}

	private ZipParser.Timestamps parseTimestamps(EntryIndex entryIndex) {
		try {
			return ZipParser.parseTimestamps(
					centralDirectory,
					entryIndex.variableOffset() + entryIndex.nameLength(),
					entryIndex.extraLength(),
					entryIndex.lastModifiedDate(),
					entryIndex.lastModifiedTimeBits()
			);
		} catch (IOException exception) {
			throw new UncheckedIOException(exception);
		}
	}

	private void ensureOpen() {
		if (closed.get()) {
			throw new IllegalStateException("ZIP view is closed");
		}
	}

	private record MaterializedEntries(List<net.fabricmc.zip.api.ZipEntryView> entries, Map<String, net.fabricmc.zip.api.ZipEntryView> entriesByName) {
	}

	private static final class NameIndex {
		private static final int EMPTY = -1;

		private final byte[] centralDirectory;
		private final EntryIndex[] entryIndexes;
		private final int[] asciiTable;

		private NameIndex(byte[] centralDirectory, EntryIndex[] entryIndexes, int[] asciiTable) {
			this.centralDirectory = centralDirectory;
			this.entryIndexes = entryIndexes;
			this.asciiTable = asciiTable;
		}

		static NameIndex build(byte[] centralDirectory, EntryIndex[] entryIndexes) {
			int tableSize = tableSize(entryIndexes.length);
			int[] asciiTable = createEmptyTable(tableSize);

			for (int index = 0; index < entryIndexes.length; index++) {
				EntryIndex entryIndex = entryIndexes[index];

				if (entryIndex.asciiNameHash() >= 0) {
					insert(asciiTable, centralDirectory, entryIndexes, entryIndex, index);
				}
			}

			return new NameIndex(centralDirectory, entryIndexes, asciiTable);
		}

		EntryIndex find(String name) {
			if (asciiTable.length == 0) {
				return null;
			}

			int hash = name.hashCode();

			for (int index = 0; index < name.length(); index++) {
				if (name.charAt(index) > 0x7F) {
					return null;
				}
			}

			int mask = asciiTable.length - 1;
			int slot = hash & mask;

			while (true) {
				int entryArrayIndex = asciiTable[slot];

				if (entryArrayIndex == EMPTY) {
					return null;
				}

				EntryIndex entryIndex = entryIndexes[entryArrayIndex];

				if (nameEquals(entryIndex, name, hash)) {
					return entryIndex;
				}

				slot = (slot + 1) & mask;
			}
		}

		private static void insert(int[] table, byte[] centralDirectory, EntryIndex[] entryIndexes, EntryIndex entryIndex, int entryArrayIndex) {
			int mask = table.length - 1;
			int slot = entryIndex.asciiNameHash() & mask;

			while (table[slot] != EMPTY) {
				if (nameEquals(centralDirectory, entryIndexes[table[slot]], entryIndex)) {
					return;
				}

				slot = (slot + 1) & mask;
			}

			table[slot] = entryArrayIndex;
		}

		private static boolean nameEquals(byte[] centralDirectory, EntryIndex first, EntryIndex second) {
			if (first.nameLength() != second.nameLength() || first.asciiNameHash() != second.asciiNameHash()) {
				return false;
			}

			int firstOffset = first.variableOffset();
			int secondOffset = second.variableOffset();

			for (int index = 0; index < first.nameLength(); index++) {
				if (centralDirectory[firstOffset + index] != centralDirectory[secondOffset + index]) {
					return false;
				}
			}

			return true;
		}

		private boolean nameEquals(EntryIndex entryIndex, String name, int hash) {
			if (entryIndex.asciiNameHash() != hash || entryIndex.nameLength() != name.length()) {
				return false;
			}

			int offset = entryIndex.variableOffset();

			for (int index = 0; index < name.length(); index++) {
				if ((centralDirectory[offset + index] & 0xFF) != name.charAt(index)) {
					return false;
				}
			}

			return true;
		}

		private static int[] createEmptyTable(int tableSize) {
			int[] table = new int[tableSize];
			java.util.Arrays.fill(table, EMPTY);
			return table;
		}

		private static int tableSize(int entryCount) {
			if (entryCount == 0) {
				return 0;
			}

			int requiredSize = Math.max(2, entryCount * 2);
			int tableSize = 1;

			while (tableSize < requiredSize) {
				tableSize <<= 1;
			}

			return tableSize;
		}
	}

	private record EntryIndex(
			int arrayIndex,
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
			int asciiNameHash,
			int lastModifiedDate,
			int lastModifiedTimeBits
	) {
	}
}
