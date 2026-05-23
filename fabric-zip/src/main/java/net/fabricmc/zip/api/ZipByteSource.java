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

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

import org.jetbrains.annotations.ApiStatus;

import net.fabricmc.zip.impl.PathZipByteSource;

/**
 * A seekable byte source for ZIP data.
 */
@ApiStatus.NonExtendable
public interface ZipByteSource extends Closeable {
	/**
	 * Opens a ZIP byte source backed by a file path.
	 *
	 * @param path the file to read.
	 * @return a byte source backed by the supplied path.
	 * @throws IOException if the file cannot be opened.
	 */
	static ZipByteSource of(Path path) throws IOException {
		return new PathZipByteSource(path);
	}

	/**
	 * @return the total size of the byte source in bytes.
	 * @throws IOException if the size cannot be determined.
	 */
	long size() throws IOException;

	/**
	 * Reads up to {@code length} bytes from the supplied absolute position.
	 *
	 * @param position the absolute byte position to read from.
	 * @param buffer the destination buffer.
	 * @param offset the destination offset.
	 * @param length the maximum number of bytes to read.
	 * @return the number of bytes read, or {@code -1} if the position is at end of source.
	 * @throws IOException if the read fails.
	 */
	int read(long position, byte[] buffer, int offset, int length) throws IOException;

	/**
	 * Reads exactly {@code length} bytes from the supplied absolute position.
	 *
	 * @param position the absolute byte position to read from.
	 * @param buffer the destination buffer.
	 * @param offset the destination offset.
	 * @param length the number of bytes to read.
	 * @throws IOException if the read fails or does not have enough data.
	 */
	default void readFully(long position, byte[] buffer, int offset, int length) throws IOException {
		Objects.checkFromIndexSize(offset, length, buffer.length);

		long currentPosition = position;
		int currentOffset = offset;
		int remaining = length;

		while (remaining > 0) {
			int read = read(currentPosition, buffer, currentOffset, remaining);

			if (read < 0) {
				throw new EOFException("Unexpected end of ZIP source");
			}

			currentPosition += read;
			currentOffset += read;
			remaining -= read;
		}
	}

	/**
	 * Reads exactly {@code buffer.length} bytes from the supplied absolute position.
	 *
	 * @param position the absolute byte position to read from.
	 * @param buffer the destination buffer.
	 * @throws IOException if the read fails or does not have enough data.
	 */
	default void readFully(long position, byte[] buffer) throws IOException {
		readFully(position, buffer, 0, buffer.length);
	}
}
