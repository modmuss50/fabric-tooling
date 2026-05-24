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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.zip.CRC32;

import org.jetbrains.annotations.Nullable;

import net.fabricmc.zip.api.CompressionCodec;
import net.fabricmc.zip.api.CompressionMethod;
import net.fabricmc.zip.api.MalformedZipException;
import net.fabricmc.zip.api.UnsupportedZipFeatureException;
import net.fabricmc.zip.api.Zip;
import net.fabricmc.zip.api.ZipEntryView;
import net.fabricmc.zip.api.ZipOptions;
import net.fabricmc.zip.api.ZipView;

public final class ZipImpl implements Zip {
	private static final long UINT16_MAX = 0xFFFFL;
	private static final long UINT32_MAX = 0xFFFFFFFFL;
	private static final Instant REPRODUCIBLE_TIME = Instant.parse("1980-01-01T00:00:00Z");

	private final Path path;
	private final FileChannel channel;
	private final FileLock fileLock;
	private final ZipOptions options;
	private final CompressionCodec compressionCodec;
	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
	private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
	private final List<Payload> retiredPayloads = new ArrayList<>();

	private volatile MutableZipSnapshot snapshot;
	private LinkedHashMap<String, MutableZipEntry> entriesByName;
	private boolean dirty;

	private ZipImpl(
			Path path,
			FileChannel channel,
			FileLock fileLock,
			ZipOptions options,
			LinkedHashMap<String, MutableZipEntry> entriesByName,
			boolean dirty
	) {
		this.path = path;
		this.channel = channel;
		this.fileLock = fileLock;
		this.options = options;
		this.entriesByName = entriesByName;
		this.dirty = dirty;
		this.compressionCodec = options.compressionCodec();
		this.snapshot = MutableZipSnapshot.create(this, entriesByName.values());
	}

	public static Zip create(Path path, ZipOptions options) throws IOException {
		Path normalizedPath = normalizePath(path);
		Objects.requireNonNull(options, "options");

		FileChannel channel = FileChannel.open(normalizedPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE);
		FileLock fileLock = null;
		boolean success = false;

		try {
			fileLock = tryLock(channel, normalizedPath);
			ZipImpl zip = new ZipImpl(normalizedPath, channel, fileLock, options, new LinkedHashMap<>(), true);
			success = true;
			return zip;
		} finally {
			if (!success) {
				closeResources(channel, fileLock);
				Files.deleteIfExists(normalizedPath);
			}
		}
	}

	public static Zip open(Path path, ZipOptions options) throws IOException {
		Path normalizedPath = normalizePath(path);
		Objects.requireNonNull(options, "options");

		if (!Files.exists(normalizedPath)) {
			throw new NoSuchFileException(normalizedPath.toString());
		}

		FileChannel channel = FileChannel.open(normalizedPath, StandardOpenOption.READ, StandardOpenOption.WRITE);
		FileLock fileLock = null;
		boolean success = false;

		try {
			fileLock = tryLock(channel, normalizedPath);
			LoadedArchive loadedArchive = loadExistingArchive(channel);
			ZipImpl zip = new ZipImpl(
					normalizedPath,
					channel,
					fileLock,
					options,
					loadedArchive.entriesByName(),
					false
			);
			success = true;
			return zip;
		} finally {
			if (!success) {
				closeResources(channel, fileLock);
			}
		}
	}

	@Override
	public void add(String name, byte[] data) throws IOException {
		Objects.requireNonNull(data, "data");
		add(name, new ByteArrayInputStream(data));
	}

	@Override
	public void add(String name, InputStream data) throws IOException {
		Zip.requireName(name);
		Objects.requireNonNull(data, "data");

		MutableZipEntry newEntry = stageAddedEntry(name, data);
		boolean success = false;

		try {
			lock.writeLock().lock();

			try {
				ensureOpen();

				if (entriesByName.containsKey(name)) {
					throw new IllegalArgumentException("ZIP entry already exists: " + name);
				}

				LinkedHashMap<String, MutableZipEntry> updatedEntries = new LinkedHashMap<>(entriesByName);
				updatedEntries.put(name, newEntry);
				commitUpdatedEntries(updatedEntries, Collections.emptyList());
				success = true;
			} finally {
				lock.writeLock().unlock();
			}
		} finally {
			if (!success) {
				newEntry.payload.close();
			}
		}
	}

	@Override
	public void remove(String name) throws IOException {
		Zip.requireName(name);
		List<Payload> retired = new ArrayList<>(1);

		lock.writeLock().lock();

		try {
			ensureOpen();
			MutableZipEntry removedEntry = entriesByName.get(name);

			if (removedEntry == null) {
				throw new IllegalArgumentException("ZIP entry does not exist: " + name);
			}

			LinkedHashMap<String, MutableZipEntry> updatedEntries = new LinkedHashMap<>(entriesByName);
			updatedEntries.remove(name);
			retired.add(removedEntry.payload);
			commitUpdatedEntries(updatedEntries, retired);
		} finally {
			lock.writeLock().unlock();
		}
	}

	@Override
	public void copy(ZipView source, String name) throws IOException {
		copy(source, name, name);
	}

	@Override
	public void copy(ZipView source, String sourceName, String targetName) throws IOException {
		Objects.requireNonNull(source, "source");
		Zip.requireName(sourceName);
		Zip.requireName(targetName);
		ZipEntryView sourceEntry = source.getEntry(sourceName).orElseThrow(() -> new IllegalArgumentException("ZIP entry does not exist: " + sourceName));
		MutableZipEntry copiedEntry = stageCopiedEntry(targetName, sourceEntry, source.openRaw(sourceEntry));
		boolean success = false;

		try {
			lock.writeLock().lock();

			try {
				ensureOpen();

				if (entriesByName.containsKey(targetName)) {
					throw new IllegalArgumentException("ZIP entry already exists: " + targetName);
				}

				LinkedHashMap<String, MutableZipEntry> updatedEntries = new LinkedHashMap<>(entriesByName);
				updatedEntries.put(targetName, copiedEntry);
				commitUpdatedEntries(updatedEntries, Collections.emptyList());
				success = true;
			} finally {
				lock.writeLock().unlock();
			}
		} finally {
			if (!success) {
				copiedEntry.payload.close();
			}
		}
	}

	@Override
	public void replace(String name, byte[] data) throws IOException {
		Objects.requireNonNull(data, "data");
		replace(name, new ByteArrayInputStream(data));
	}

	@Override
	public void replace(String name, InputStream data) throws IOException {
		Zip.requireName(name);
		Objects.requireNonNull(data, "data");

		lock.writeLock().lock();

		try {
			ensureOpen();
			MutableZipEntry existingEntry = entriesByName.get(name);

			if (existingEntry == null) {
				throw new IllegalArgumentException("ZIP entry does not exist: " + name);
			}

			MutableZipEntry replacementEntry = stageAddedEntry(name, data, existingEntry.method);
			boolean success = false;

			try {
				LinkedHashMap<String, MutableZipEntry> updatedEntries = new LinkedHashMap<>(entriesByName);
				updatedEntries.put(name, replacementEntry);
				commitUpdatedEntries(updatedEntries, List.of(existingEntry.payload));
				success = true;
			} finally {
				if (!success) {
					replacementEntry.payload.close();
				}
			}
		} finally {
			lock.writeLock().unlock();
		}
	}

	@Override
	public void modify(String name, Function<byte[], byte[]> modifier) throws IOException {
		Zip.requireName(name);
		Objects.requireNonNull(modifier, "modifier");

		lock.writeLock().lock();

		try {
			ensureOpen();
			MutableZipEntry existingEntry = entriesByName.get(name);

			if (existingEntry == null) {
				throw new IllegalArgumentException("ZIP entry does not exist: " + name);
			}

			byte[] inputBytes;

			try (InputStream inputStream = open(snapshot.entriesByName().get(name))) {
				inputBytes = inputStream.readAllBytes();
			}

			byte[] updatedBytes = Objects.requireNonNull(modifier.apply(inputBytes), "modifier result");
			MutableZipEntry replacementEntry = stageAddedEntry(name, new ByteArrayInputStream(updatedBytes), existingEntry.method);
			boolean success = false;

			try {
				LinkedHashMap<String, MutableZipEntry> updatedEntries = new LinkedHashMap<>(entriesByName);
				updatedEntries.put(name, replacementEntry);
				commitUpdatedEntries(updatedEntries, List.of(existingEntry.payload));
				success = true;
			} finally {
				if (!success) {
					replacementEntry.payload.close();
				}
			}
		} finally {
			lock.writeLock().unlock();
		}
	}

	@Override
	public List<ZipEntryView> entries() {
		ensureOpen();
		return snapshot.entries();
	}

	@Override
	public Optional<ZipEntryView> getEntry(String name) {
		ensureOpen();
		return Optional.ofNullable(snapshot.entriesByName().get(name));
	}

	@Override
	public InputStream open(ZipEntryView entry) throws IOException {
		MutableZipEntry entryState = requireEntry(entry);
		InputStream raw = entryState.payload.openStream();
		boolean success = false;

		try {
			InputStream decompressed = compressionCodec.decompress(entry, raw);
			success = true;
			return decompressed;
		} finally {
			if (!success) {
				raw.close();
			}
		}
	}

	@Override
	public InputStream openRaw(ZipEntryView entry) throws IOException {
		return requireEntry(entry).payload.openStream();
	}

	@Override
	public void close() throws IOException {
		if (!closed.compareAndSet(false, true)) {
			return;
		}

		Path replacementArchive = null;

		lock.writeLock().lock();

		try {
			if (dirty) {
				replacementArchive = persistFull(entriesByName);
				dirty = false;
			}
		} finally {
			lock.writeLock().unlock();
		}

		IOException exception = null;
		exception = closeSuppressing(exception, fileLock);
		exception = closeSuppressing(exception, channel);

		if (replacementArchive != null) {
			exception = replaceArchive(exception, replacementArchive);
		}

		for (MutableZipEntry entry : entriesByName.values()) {
			exception = closeSuppressing(exception, entry.payload);
		}

		for (Payload payload : retiredPayloads) {
			exception = closeSuppressing(exception, payload);
		}

		if (exception != null) {
			throw exception;
		}
	}

	private void commitUpdatedEntries(LinkedHashMap<String, MutableZipEntry> updatedEntries, List<Payload> retired) {
		MutableZipSnapshot updatedSnapshot = MutableZipSnapshot.create(this, updatedEntries.values());
		dirty = true;
		entriesByName = updatedEntries;
		retiredPayloads.addAll(retired);
		snapshot = updatedSnapshot;
	}

	private MutableZipEntry requireEntry(ZipEntryView entry) {
		ensureOpen();
		Objects.requireNonNull(entry, "entry");

		if (!(entry instanceof MutableZipSnapshot.MutableZipSnapshotEntry snapshotEntry) || snapshotEntry.archive() != this) {
			throw new IllegalArgumentException("Entry does not belong to this archive");
		}

		return snapshotEntry.state();
	}

	private void ensureOpen() {
		if (closed.get()) {
			throw new IllegalStateException("ZIP archive is closed");
		}
	}

	private MutableZipEntry stageAddedEntry(String name, InputStream data) throws IOException {
		return stageAddedEntry(name, data, options.defaultCompressionMethod());
	}

	private MutableZipEntry stageAddedEntry(String name, InputStream data, CompressionMethod method) throws IOException {
		FileTime modifiedTime = defaultTimestampForNewEntry();
		FileTime accessTime = modifiedTime;
		FileTime creationTime = modifiedTime;
		int flags = ZipConstants.GENERAL_PURPOSE_FLAG_UTF8;

		Payload payload;
		long crc32;
		long compressedSize;
		long uncompressedSize;

		if (method == CompressionMethod.STORED) {
			PayloadWithMetadata stored = copyStoredPayload(data);
			payload = stored.payload();
			crc32 = stored.crc32();
			compressedSize = stored.size();
			uncompressedSize = stored.size();
		} else if (method == CompressionMethod.DEFLATED) {
			PayloadWithMetadata deflated = copyDeflatedPayload(data);
			payload = deflated.payload();
			crc32 = deflated.crc32();
			compressedSize = deflated.size();
			uncompressedSize = deflated.uncompressedSize();
		} else {
			throw new UnsupportedZipFeatureException("Unsupported compression method: " + method);
		}

		return new MutableZipEntry(
				name,
				null,
				method,
				flags,
				crc32,
				compressedSize,
				uncompressedSize,
				name.endsWith("/"),
				modifiedTime,
				accessTime,
				creationTime,
				payload,
				-1L,
				-1L
		);
	}

	private MutableZipEntry stageCopiedEntry(String targetName, ZipEntryView sourceEntry, InputStream rawData) throws IOException {
		try (rawData) {
			Payload payload = Payload.copyOf(rawData);
			FileTime modifiedTime = normalizedTimestamp(sourceEntry.getLastModifiedTime());
			FileTime accessTime = normalizedTimestamp(sourceEntry.getLastAccessTime());
			FileTime creationTime = normalizedTimestamp(sourceEntry.getCreationTime());
			int flags = ZipConstants.GENERAL_PURPOSE_FLAG_UTF8;

			return new MutableZipEntry(
					targetName,
					sourceEntry.getComment(),
					sourceEntry.getMethod(),
					flags,
					sourceEntry.getCrc32(),
					sourceEntry.getCompressedSize(),
					sourceEntry.getUncompressedSize(),
					targetName.endsWith("/"),
					modifiedTime,
					accessTime,
					creationTime,
					payload,
					-1L,
					-1L
			);
		}
	}

	private Path persistFull(LinkedHashMap<String, MutableZipEntry> entries) throws IOException {
		Path temporaryArchive = createReplacementArchivePath();
		boolean success = false;

		try (FileChannel outputChannel = FileChannel.open(temporaryArchive, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
			persistFull(outputChannel, entries);
			success = true;
			return temporaryArchive;
		} finally {
			if (!success) {
				Files.deleteIfExists(temporaryArchive);
			}
		}
	}

	private void persistFull(FileChannel outputChannel, LinkedHashMap<String, MutableZipEntry> entries) throws IOException {
		List<MutableZipEntry> orderedEntries = orderedEntries(entries.values());
		long position = 0L;

		for (MutableZipEntry entry : orderedEntries) {
			long localHeaderOffset = position;
			LocalHeaderData localHeaderData = buildLocalHeader(entry, localHeaderOffset);
			position = writeBytes(outputChannel, position, localHeaderData.header());

			try (InputStream inputStream = entry.payload.openStream()) {
				position = transferToChannel(inputStream, outputChannel, position);
			}

			entry.localHeaderOffset = localHeaderOffset;
			entry.localRecordLength = position - localHeaderOffset;
		}

		long centralDirectoryOffset = position;
		position = writeCentralDirectory(outputChannel, orderedEntries, position);
		outputChannel.truncate(position);
		outputChannel.force(true);
	}

	private long writeCentralDirectory(FileChannel channel, List<MutableZipEntry> entries, long position) throws IOException {
		long centralDirectoryOffset = position;

		for (MutableZipEntry entry : entries) {
			position = writeBytes(channel, position, buildCentralDirectoryHeader(entry));
		}

		long centralDirectorySize = position - centralDirectoryOffset;
		boolean zip64 = entries.size() > UINT16_MAX || centralDirectoryOffset > UINT32_MAX || centralDirectorySize > UINT32_MAX
				|| entries.stream().anyMatch(entry -> entry.localHeaderOffset > UINT32_MAX || entry.compressedSize > UINT32_MAX || entry.uncompressedSize > UINT32_MAX);

		if (zip64) {
			long zip64EocdOffset = position;
			position = writeBytes(channel, position, buildZip64EndOfCentralDirectory(entries.size(), centralDirectorySize, centralDirectoryOffset));
			position = writeBytes(channel, position, buildZip64Locator(zip64EocdOffset));
		}

		return writeBytes(channel, position, buildEndOfCentralDirectory(entries.size(), centralDirectorySize, centralDirectoryOffset, zip64));
	}

	private static long transferToChannel(InputStream inputStream, FileChannel channel, long position) throws IOException {
		byte[] buffer = new byte[8192];

		while (true) {
			int read = inputStream.read(buffer);

			if (read < 0) {
				return position;
			}

			position = writeBuffer(channel, position, ByteBuffer.wrap(buffer, 0, read));
		}
	}

	private static long writeBytes(FileChannel channel, long position, byte[] bytes) throws IOException {
		return writeBuffer(channel, position, ByteBuffer.wrap(bytes));
	}

	private static long writeBuffer(FileChannel channel, long position, ByteBuffer buffer) throws IOException {
		while (buffer.hasRemaining()) {
			position += channel.write(buffer, position);
		}

		return position;
	}

	private static byte[] buildZip64EndOfCentralDirectory(long entryCount, long centralDirectorySize, long centralDirectoryOffset) {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		writeInt(output, ZipConstants.ZIP64_EOCD_SIGNATURE);
		writeLong(output, 44);
		writeShort(output, 45);
		writeShort(output, 45);
		writeInt(output, 0);
		writeInt(output, 0);
		writeLong(output, entryCount);
		writeLong(output, entryCount);
		writeLong(output, centralDirectorySize);
		writeLong(output, centralDirectoryOffset);
		return output.toByteArray();
	}

	private static byte[] buildZip64Locator(long zip64EocdOffset) {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		writeInt(output, ZipConstants.ZIP64_LOCATOR_SIGNATURE);
		writeInt(output, 0);
		writeLong(output, zip64EocdOffset);
		writeInt(output, 1);
		return output.toByteArray();
	}

	private static byte[] buildEndOfCentralDirectory(long entryCount, long centralDirectorySize, long centralDirectoryOffset, boolean zip64) {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		writeInt(output, ZipConstants.EOCD_SIGNATURE);
		writeShort(output, 0);
		writeShort(output, 0);
		writeShort(output, zip64 ? 0xFFFF : entryCount);
		writeShort(output, zip64 ? 0xFFFF : entryCount);
		writeInt(output, zip64 ? UINT32_MAX : centralDirectorySize);
		writeInt(output, zip64 ? UINT32_MAX : centralDirectoryOffset);
		writeShort(output, 0);
		return output.toByteArray();
	}

	private static byte[] buildCentralDirectoryHeader(MutableZipEntry entry) {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		byte[] nameBytes = entry.name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		byte[] commentBytes = entry.comment == null ? new byte[0] : entry.comment.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		int dosTime = toDosTime(entry.lastModifiedTime.toInstant());
		int dosDate = toDosDate(entry.lastModifiedTime.toInstant());
		boolean zip64 = entry.localHeaderOffset > UINT32_MAX || entry.compressedSize > UINT32_MAX || entry.uncompressedSize > UINT32_MAX;
		byte[] timestampExtra = buildTimestampExtra(entry);
		byte[] zip64Extra = zip64 ? buildZip64Extra(true, entry.uncompressedSize, true, entry.compressedSize, true, entry.localHeaderOffset) : new byte[0];
		byte[] extra = concat(timestampExtra, zip64Extra);

		writeInt(output, ZipConstants.CENTRAL_DIRECTORY_HEADER_SIGNATURE);
		writeShort(output, 45);
		writeShort(output, zip64 ? 45 : 20);
		writeShort(output, entry.flags);
		writeShort(output, entry.method.getCode());
		writeShort(output, dosTime);
		writeShort(output, dosDate);
		writeInt(output, entry.crc32);
		writeInt(output, zip64 ? UINT32_MAX : entry.compressedSize);
		writeInt(output, zip64 ? UINT32_MAX : entry.uncompressedSize);
		writeShort(output, nameBytes.length);
		writeShort(output, extra.length);
		writeShort(output, commentBytes.length);
		writeShort(output, 0);
		writeShort(output, 0);
		writeInt(output, entry.directory ? 0x10 : 0);
		writeInt(output, zip64 ? UINT32_MAX : entry.localHeaderOffset);
		output.writeBytes(nameBytes);
		output.writeBytes(extra);
		output.writeBytes(commentBytes);
		return output.toByteArray();
	}

	private static LocalHeaderData buildLocalHeader(MutableZipEntry entry, long localHeaderOffset) {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		byte[] nameBytes = entry.name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		int dosTime = toDosTime(entry.lastModifiedTime.toInstant());
		int dosDate = toDosDate(entry.lastModifiedTime.toInstant());
		boolean zip64 = localHeaderOffset > UINT32_MAX || entry.compressedSize > UINT32_MAX || entry.uncompressedSize > UINT32_MAX;
		byte[] timestampExtra = buildTimestampExtra(entry);
		byte[] zip64Extra = zip64 ? buildZip64Extra(true, entry.uncompressedSize, true, entry.compressedSize, false, 0L) : new byte[0];
		byte[] extra = concat(timestampExtra, zip64Extra);

		writeInt(output, ZipConstants.LOCAL_FILE_HEADER_SIGNATURE);
		writeShort(output, zip64 ? 45 : 20);
		writeShort(output, entry.flags);
		writeShort(output, entry.method.getCode());
		writeShort(output, dosTime);
		writeShort(output, dosDate);
		writeInt(output, entry.crc32);
		writeInt(output, zip64 ? UINT32_MAX : entry.compressedSize);
		writeInt(output, zip64 ? UINT32_MAX : entry.uncompressedSize);
		writeShort(output, nameBytes.length);
		writeShort(output, extra.length);
		output.writeBytes(nameBytes);
		output.writeBytes(extra);
		return new LocalHeaderData(output.toByteArray());
	}

	private static byte[] buildTimestampExtra(MutableZipEntry entry) {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		writeShort(output, ZipConstants.EXTENDED_TIMESTAMP_EXTRA_FIELD_ID);
		writeShort(output, 13);
		output.write(0b0000_0111);
		writeInt(output, entry.lastModifiedTime.toInstant().getEpochSecond());
		writeInt(output, entry.lastAccessTime.toInstant().getEpochSecond());
		writeInt(output, entry.creationTime.toInstant().getEpochSecond());
		return output.toByteArray();
	}

	private static byte[] buildZip64Extra(boolean includeUncompressedSize, long uncompressedSize, boolean includeCompressedSize, long compressedSize, boolean includeLocalOffset, long localOffset) {
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
		writeShort(output, ZipConstants.ZIP64_EXTRA_FIELD_ID);
		writeShort(output, data.size());
		output.writeBytes(data.toByteArray());
		return output.toByteArray();
	}

	private List<MutableZipEntry> orderedEntries(Collection<MutableZipEntry> entries) {
		List<MutableZipEntry> orderedEntries = new ArrayList<>(entries);

		if (options.reproducible()) {
			orderedEntries.sort((first, second) -> first.name.compareTo(second.name));
		}

		return orderedEntries;
	}

	private static FileTime defaultTimestamp() {
		return FileTime.from(REPRODUCIBLE_TIME);
	}

	private FileTime normalizedTimestamp(@Nullable FileTime timestamp) {
		if (options.reproducible()) {
			return defaultTimestamp();
		}

		return timestamp != null ? timestamp : FileTime.from(Instant.now());
	}

	private FileTime defaultTimestampForNewEntry() {
		return options.reproducible() ? defaultTimestamp() : FileTime.from(Instant.now());
	}

	private static PayloadWithMetadata copyStoredPayload(InputStream inputStream) throws IOException {
		Path tempFile = Files.createTempFile("fabric-zip-entry-", ".bin");
		CRC32 crc32 = new CRC32();
		long size = 0L;

		try (inputStream; OutputStream outputStream = Files.newOutputStream(tempFile)) {
			byte[] buffer = new byte[8192];

			while (true) {
				int read = inputStream.read(buffer);

				if (read < 0) {
					break;
				}

				outputStream.write(buffer, 0, read);
				crc32.update(buffer, 0, read);
				size += read;
			}
		} catch (IOException | RuntimeException error) {
			Files.deleteIfExists(tempFile);
			throw error;
		}

		return new PayloadWithMetadata(new TempFilePayload(tempFile), crc32.getValue(), size, size);
	}

	private PayloadWithMetadata copyDeflatedPayload(InputStream inputStream) throws IOException {
		Path tempFile = Files.createTempFile("fabric-zip-entry-", ".bin");
		CRC32 crc32 = new CRC32();
		long uncompressedSize = 0L;

		try (inputStream;
				OutputStream fileOutput = Files.newOutputStream(tempFile);
				OutputStream compressedOutput = compressionCodec.compress(CompressionMethod.DEFLATED, fileOutput)) {
			byte[] buffer = new byte[8192];

			while (true) {
				int read = inputStream.read(buffer);

				if (read < 0) {
					break;
				}

				compressedOutput.write(buffer, 0, read);
				crc32.update(buffer, 0, read);
				uncompressedSize += read;
			}
		} catch (IOException | RuntimeException error) {
			Files.deleteIfExists(tempFile);
			throw error;
		}

		long compressedSize = Files.size(tempFile);
		return new PayloadWithMetadata(new TempFilePayload(tempFile), crc32.getValue(), compressedSize, uncompressedSize);
	}

	private static LoadedArchive loadExistingArchive(FileChannel channel) throws IOException {
		FileChannelZipByteSource source = new FileChannelZipByteSource(channel);
		ZipParser.ParsedZip parsedZip = ZipParser.parseArchive(source);
		LinkedHashMap<String, MutableZipEntry> entriesByName = new LinkedHashMap<>();

		for (ZipParser.EntryData parsedEntry : parsedZip.entries()) {
			if (entriesByName.containsKey(parsedEntry.name())) {
				throw new UnsupportedZipFeatureException("Mutable ZIP archives do not support duplicate entry names: " + parsedEntry.name());
			}

			EntryLocation location = locateEntry(source, parsedEntry);
			Payload payload = Payload.slice(source, location.dataOffset(), parsedEntry.compressedSize());
			MutableZipEntry entry = new MutableZipEntry(
					parsedEntry.name(),
					parsedEntry.comment(),
					parsedEntry.method(),
					ZipConstants.GENERAL_PURPOSE_FLAG_UTF8,
					parsedEntry.crc32(),
					parsedEntry.compressedSize(),
					parsedEntry.uncompressedSize(),
					parsedEntry.directory(),
					normalizeLoadedTimestamp(parsedEntry.lastModifiedTime()),
					normalizeLoadedTimestamp(parsedEntry.lastAccessTime(), parsedEntry.lastModifiedTime()),
					normalizeLoadedTimestamp(parsedEntry.creationTime(), parsedEntry.lastModifiedTime()),
					payload,
					parsedEntry.localHeaderOffset(),
					location.recordLength()
			);
			entriesByName.put(entry.name, entry);
		}

		return new LoadedArchive(entriesByName);
	}

	private static FileTime normalizeLoadedTimestamp(@Nullable FileTime timestamp) {
		return timestamp != null ? timestamp : FileTime.from(Instant.now());
	}

	private static FileTime normalizeLoadedTimestamp(@Nullable FileTime timestamp, @Nullable FileTime fallback) {
		return timestamp != null ? timestamp : normalizeLoadedTimestamp(fallback);
	}

	private static EntryLocation locateEntry(FileChannelZipByteSource source, ZipParser.EntryData entry) throws IOException {
		byte[] localHeader = new byte[ZipConstants.LOCAL_FILE_HEADER_LENGTH];
		source.readFully(entry.localHeaderOffset(), localHeader);

		if (ZipParser.readInt(localHeader, 0) != ZipConstants.LOCAL_FILE_HEADER_SIGNATURE) {
			throw new MalformedZipException("Invalid local file header signature for entry: " + entry.name());
		}

		int nameLength = ZipParser.readUnsignedShort(localHeader, 26);
		int extraLength = ZipParser.readUnsignedShort(localHeader, 28);
		long dataOffset = entry.localHeaderOffset() + ZipConstants.LOCAL_FILE_HEADER_LENGTH + nameLength + extraLength;
		long descriptorLength = dataDescriptorLength(source, entry, dataOffset + entry.compressedSize());
		long recordLength = dataOffset + entry.compressedSize() + descriptorLength - entry.localHeaderOffset();
		return new EntryLocation(dataOffset, recordLength);
	}

	private static long dataDescriptorLength(FileChannelZipByteSource source, ZipParser.EntryData entry, long descriptorOffset) throws IOException {
		if ((entry.flags() & ZipConstants.GENERAL_PURPOSE_FLAG_DATA_DESCRIPTOR) == 0) {
			return 0L;
		}

		byte[] signature = new byte[4];
		source.readFully(descriptorOffset, signature);
		boolean hasSignature = ZipParser.readInt(signature, 0) == 0x08074b50;
		boolean zip64 = entry.compressedSize() > UINT32_MAX || entry.uncompressedSize() > UINT32_MAX;

		if (hasSignature) {
			return zip64 ? 24L : 16L;
		}

		return zip64 ? 20L : 12L;
	}

	private static FileLock tryLock(FileChannel channel, Path path) throws IOException {
		try {
			FileLock fileLock = channel.tryLock();

			if (fileLock == null) {
				throw new IOException("ZIP archive is already open for mutation: " + path);
			}

			return fileLock;
		} catch (java.nio.channels.OverlappingFileLockException exception) {
			throw new IOException("ZIP archive is already open for mutation: " + path, exception);
		}
	}

	private static Path normalizePath(Path path) {
		return Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
	}

	private Path createReplacementArchivePath() throws IOException {
		String prefix = path.getFileName() != null ? path.getFileName().toString() + "-" : "fabric-zip-";
		return Files.createTempFile(path.getParent(), prefix, ".tmp");
	}

	private IOException replaceArchive(@Nullable IOException existing, Path replacementArchive) {
		try {
			try {
				Files.move(replacementArchive, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
				Files.move(replacementArchive, path, StandardCopyOption.REPLACE_EXISTING);
			}

			return existing;
		} catch (IOException moveException) {
			IOException result = existing == null ? moveException : existing;

			if (existing != null) {
				existing.addSuppressed(moveException);
			}

			try {
				Files.deleteIfExists(replacementArchive);
			} catch (IOException deleteException) {
				result.addSuppressed(deleteException);
			}

			return result;
		}
	}

	private static void closeResources(FileChannel channel, @Nullable FileLock fileLock) throws IOException {
		IOException exception = null;
		exception = closeSuppressing(exception, fileLock);
		exception = closeSuppressing(exception, channel);

		if (exception != null) {
			throw exception;
		}
	}

	private static IOException closeSuppressing(@Nullable IOException existing, @Nullable AutoCloseable closeable) {
		if (closeable == null) {
			return existing;
		}

		try {
			closeable.close();
			return existing;
		} catch (Exception exception) {
			IOException ioException = exception instanceof IOException io ? io : new IOException(exception);

			if (existing == null) {
				return ioException;
			}

			existing.addSuppressed(ioException);
			return existing;
		}
	}

	private static int toDosTime(Instant instant) {
		ZonedDateTime dateTime = instant.atZone(ZoneOffset.UTC);
		return (dateTime.getHour() << 11) | (dateTime.getMinute() << 5) | (dateTime.getSecond() / 2);
	}

	private static int toDosDate(Instant instant) {
		ZonedDateTime dateTime = instant.atZone(ZoneOffset.UTC);
		return ((dateTime.getYear() - 1980) << 9) | (dateTime.getMonthValue() << 5) | dateTime.getDayOfMonth();
	}

	private static byte[] concat(byte[] first, byte[] second) {
		byte[] bytes = Arrays.copyOf(first, first.length + second.length);
		System.arraycopy(second, 0, bytes, first.length, second.length);
		return bytes;
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

	private record LocalHeaderData(byte[] header) {
	}

	private record PayloadWithMetadata(Payload payload, long crc32, long size, long uncompressedSize) {
	}

	private record EntryLocation(long dataOffset, long recordLength) {
	}

	private record LoadedArchive(LinkedHashMap<String, MutableZipEntry> entriesByName) {
	}
}
