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

import org.jetbrains.annotations.Nullable;

import net.fabricmc.zip.api.CompressionMethod;

final class MutableZipEntry {
	final String name;
	final @Nullable String comment;
	final CompressionMethod method;
	final int flags;
	final long crc32;
	final long compressedSize;
	final long uncompressedSize;
	final boolean directory;
	final FileTime lastModifiedTime;
	final FileTime lastAccessTime;
	final FileTime creationTime;
	final Payload payload;
	long localHeaderOffset;
	long localRecordLength;

	MutableZipEntry(
			String name,
			@Nullable String comment,
			CompressionMethod method,
			int flags,
			long crc32,
			long compressedSize,
			long uncompressedSize,
			boolean directory,
			FileTime lastModifiedTime,
			FileTime lastAccessTime,
			FileTime creationTime,
			Payload payload,
			long localHeaderOffset,
			long localRecordLength
	) {
		this.name = name;
		this.comment = comment;
		this.method = method;
		this.flags = flags;
		this.crc32 = crc32;
		this.compressedSize = compressedSize;
		this.uncompressedSize = uncompressedSize;
		this.directory = directory;
		this.lastModifiedTime = lastModifiedTime;
		this.lastAccessTime = lastAccessTime;
		this.creationTime = creationTime;
		this.payload = payload;
		this.localHeaderOffset = localHeaderOffset;
		this.localRecordLength = localRecordLength;
	}
}
