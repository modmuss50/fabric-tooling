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

import java.io.IOException;
import java.util.Objects;

import net.fabricmc.zip.api.ZipByteSource;

final class InMemoryZipByteSource implements ZipByteSource {
	private final byte[] data;
	private boolean closed;

	InMemoryZipByteSource(byte[] data) {
		this.data = Objects.requireNonNull(data, "data");
	}

	boolean isClosed() {
		return closed;
	}

	@Override
	public long size() {
		ensureOpen();
		return data.length;
	}

	@Override
	public int read(long position, byte[] buffer, int offset, int length) throws IOException {
		ensureOpen();
		Objects.checkFromIndexSize(offset, length, buffer.length);

		if (position < 0) {
			throw new IllegalArgumentException("Positions must be non-negative");
		}

		if (position >= data.length) {
			return -1;
		}

		int read = (int) Math.min(length, data.length - position);
		System.arraycopy(data, (int) position, buffer, offset, read);
		return read;
	}

	@Override
	public void close() {
		closed = true;
	}

	private void ensureOpen() {
		if (closed) {
			throw new IllegalStateException("Source is closed");
		}
	}
}
