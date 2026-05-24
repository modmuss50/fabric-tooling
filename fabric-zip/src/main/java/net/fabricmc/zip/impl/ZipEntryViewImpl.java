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

import java.nio.file.attribute.FileTime;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import net.fabricmc.zip.api.CompressionMethod;
import net.fabricmc.zip.api.ZipEntryView;

public final class ZipEntryViewImpl implements ZipEntryView {
	private final Object archive;
	private final String name;
	private final CompressionMethod method;
	private final int flags;
	private final long crc32;
	private final long compressedSize;
	private final long uncompressedSize;
	private final long localHeaderOffset;
	private final long centralDirectoryOffset;
	private final boolean directory;
	private final @Nullable LazyMetadataSupplier metadataSupplier;
	private volatile @Nullable LazyMetadata metadata;
	private volatile long dataOffset = -1L;

	ZipEntryViewImpl(
			Object archive,
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
		this.archive = archive;
		this.name = name;
		this.method = method;
		this.flags = flags;
		this.crc32 = crc32;
		this.compressedSize = compressedSize;
		this.uncompressedSize = uncompressedSize;
		this.localHeaderOffset = localHeaderOffset;
		this.centralDirectoryOffset = centralDirectoryOffset;
		this.directory = directory;
		this.metadataSupplier = null;
		this.metadata = new LazyMetadata(comment, lastModifiedTime, lastAccessTime, creationTime);
	}

	ZipEntryViewImpl(
			Object archive,
			String name,
			CompressionMethod method,
			int flags,
			long crc32,
			long compressedSize,
			long uncompressedSize,
			long localHeaderOffset,
			long centralDirectoryOffset,
			boolean directory,
			LazyMetadataSupplier metadataSupplier
	) {
		this.archive = archive;
		this.name = name;
		this.method = method;
		this.flags = flags;
		this.crc32 = crc32;
		this.compressedSize = compressedSize;
		this.uncompressedSize = uncompressedSize;
		this.localHeaderOffset = localHeaderOffset;
		this.centralDirectoryOffset = centralDirectoryOffset;
		this.directory = directory;
		this.metadataSupplier = Objects.requireNonNull(metadataSupplier, "metadataSupplier");
	}

	Object archive() {
		return archive;
	}

	long dataOffset() {
		return dataOffset;
	}

	void dataOffset(long dataOffset) {
		this.dataOffset = dataOffset;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public @Nullable String getComment() {
		return metadata().comment();
	}

	@Override
	public CompressionMethod getMethod() {
		return method;
	}

	@Override
	public int getFlags() {
		return flags;
	}

	@Override
	public long getCrc32() {
		return crc32;
	}

	@Override
	public long getCompressedSize() {
		return compressedSize;
	}

	@Override
	public long getUncompressedSize() {
		return uncompressedSize;
	}

	@Override
	public long getLocalHeaderOffset() {
		return localHeaderOffset;
	}

	@Override
	public long getCentralDirectoryOffset() {
		return centralDirectoryOffset;
	}

	@Override
	public boolean isDirectory() {
		return directory;
	}

	@Override
	public @Nullable FileTime getLastModifiedTime() {
		return metadata().lastModifiedTime();
	}

	@Override
	public @Nullable FileTime getLastAccessTime() {
		return metadata().lastAccessTime();
	}

	@Override
	public @Nullable FileTime getCreationTime() {
		return metadata().creationTime();
	}

	private LazyMetadata metadata() {
		LazyMetadata resolved = metadata;

		if (resolved != null) {
			return resolved;
		}

		synchronized (this) {
			resolved = metadata;

			if (resolved == null) {
				resolved = Objects.requireNonNull(metadataSupplier, "metadataSupplier").load();
				metadata = resolved;
			}
		}

		return resolved;
	}

	@FunctionalInterface
	interface LazyMetadataSupplier {
		LazyMetadata load();
	}

	record LazyMetadata(
			@Nullable String comment,
			@Nullable FileTime lastModifiedTime,
			@Nullable FileTime lastAccessTime,
			@Nullable FileTime creationTime
	) {
	}
}
