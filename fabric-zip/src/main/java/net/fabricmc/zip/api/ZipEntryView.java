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

package net.fabricmc.zip.api;

import java.nio.file.attribute.FileTime;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Read-only metadata for a ZIP entry.
 */
@ApiStatus.NonExtendable
public interface ZipEntryView {
	/**
	 * Returns the entry name.
	 *
	 * @return the entry name.
	 */
	String getName();

	/**
	 * Returns the optional entry comment.
	 *
	 * @return the optional entry comment, or {@code null} if absent.
	 */
	@Nullable
	String getComment();

	/**
	 * Returns the entry compression method.
	 *
	 * @return the entry compression method.
	 */
	CompressionMethod getMethod();

	/**
	 * Returns the raw ZIP general-purpose bit flags.
	 *
	 * @return the raw ZIP general-purpose bit flags.
	 */
	int getFlags();

	/**
	 * Returns the entry CRC-32 value.
	 *
	 * @return the entry CRC-32 value.
	 */
	long getCrc32();

	/**
	 * Returns the compressed entry size in bytes.
	 *
	 * @return the compressed entry size in bytes.
	 */
	long getCompressedSize();

	/**
	 * Returns the uncompressed entry size in bytes.
	 *
	 * @return the uncompressed entry size in bytes.
	 */
	long getUncompressedSize();

	/**
	 * Returns the absolute local file header offset in the archive.
	 *
	 * @return the absolute local file header offset in the archive.
	 */
	long getLocalHeaderOffset();

	/**
	 * Returns the absolute central directory record offset in the archive.
	 *
	 * @return the absolute central directory record offset in the archive.
	 */
	long getCentralDirectoryOffset();

	/**
	 * Returns whether this entry represents a directory.
	 *
	 * @return whether this entry represents a directory.
	 */
	boolean isDirectory();

	/**
	 * Returns the optional last modified time.
	 *
	 * @return the optional last modified time, or {@code null} if unavailable.
	 */
	@Nullable
	FileTime getLastModifiedTime();

	/**
	 * Returns the optional last access time.
	 *
	 * @return the optional last access time, or {@code null} if unavailable.
	 */
	@Nullable
	FileTime getLastAccessTime();

	/**
	 * Returns the optional creation time.
	 *
	 * @return the optional creation time, or {@code null} if unavailable.
	 */
	@Nullable
	FileTime getCreationTime();
}
