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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import net.fabricmc.zip.api.CompressionCodec;
import net.fabricmc.zip.api.ZipByteSource;
import net.fabricmc.zip.api.ZipEntryView;
import net.fabricmc.zip.api.ZipView;

public final class ZipViewImpl implements ZipView {
	private final ZipByteSource source;
	private final CompressionCodec compressionCodec;
	private final List<ZipEntryView> entries;
	private final Map<String, ZipEntryView> entriesByName;
	private final AtomicBoolean closed = new AtomicBoolean();

	private ZipViewImpl(ZipByteSource source, CompressionCodec compressionCodec, List<ZipParser.EntryData> parsedEntries) {
		this.source = source;
		this.compressionCodec = compressionCodec;
		List<ZipEntryView> entries = new ArrayList<>(parsedEntries.size());

		for (ZipParser.EntryData parsedEntry : parsedEntries) {
			entries.add(createEntry(parsedEntry));
		}

		this.entries = Collections.unmodifiableList(entries);
		this.entriesByName = buildEntriesByName(entries);
	}

	public static ZipView open(ZipByteSource source, CompressionCodec compressionCodec) throws IOException {
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(compressionCodec, "compressionCodec");

		try {
			return new ZipViewImpl(source, compressionCodec, ZipParser.parse(source));
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
	public List<ZipEntryView> entries() {
		ensureOpen();
		return entries;
	}

	@Override
	public Optional<ZipEntryView> getEntry(String name) {
		ensureOpen();
		return Optional.ofNullable(entriesByName.get(name));
	}

	@Override
	public InputStream open(ZipEntryView entry) throws IOException {
		ZipEntryViewImpl zipEntry = requireEntry(entry);
		InputStream raw = openRaw(zipEntry);
		return compressionCodec.decompress(zipEntry, raw);
	}

	@Override
	public InputStream openRaw(ZipEntryView entry) throws IOException {
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

	private ZipEntryViewImpl requireEntry(ZipEntryView entry) {
		ensureOpen();
		Objects.requireNonNull(entry, "entry");

		if (!(entry instanceof ZipEntryViewImpl zipEntry) || zipEntry.archive() != this) {
			throw new IllegalArgumentException("Entry does not belong to this archive view");
		}

		return zipEntry;
	}

	private void ensureOpen() {
		if (closed.get()) {
			throw new IllegalStateException("ZIP view is closed");
		}
	}

	private static Map<String, ZipEntryView> buildEntriesByName(List<ZipEntryView> entries) {
		Map<String, ZipEntryView> entriesByName = new LinkedHashMap<>();

		for (ZipEntryView entry : entries) {
			entriesByName.putIfAbsent(entry.getName(), entry);
		}

		return Collections.unmodifiableMap(entriesByName);
	}

	private ZipEntryView createEntry(ZipParser.EntryData entry) {
		return new ZipEntryViewImpl(
				this,
				entry.name(),
				entry.comment(),
				entry.method(),
				entry.flags(),
				entry.crc32(),
				entry.compressedSize(),
				entry.uncompressedSize(),
				entry.localHeaderOffset(),
				entry.centralDirectoryOffset(),
				entry.directory(),
				entry.lastModifiedTime(),
				entry.lastAccessTime(),
				entry.creationTime()
		);
	}
}
