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

import net.fabricmc.zip.api.ZipByteSource;

final class BoundedInputStream extends InputStream {
	private final ZipByteSource source;
	private final byte[] singleByte = new byte[1];
	private long position;
	private long remaining;

	BoundedInputStream(ZipByteSource source, long position, long length) {
		this.source = source;
		this.position = position;
		this.remaining = length;
	}

	@Override
	public int read() throws IOException {
		int read = read(singleByte, 0, 1);
		return read < 0 ? -1 : singleByte[0] & 0xFF;
	}

	@Override
	public int read(byte[] buffer, int offset, int length) throws IOException {
		ZipByteSource.checkRange(buffer, offset, length);

		if (length == 0) {
			return 0;
		}

		if (remaining == 0) {
			return -1;
		}

		int toRead = (int) Math.min(remaining, length);
		int read = source.read(position, buffer, offset, toRead);

		if (read < 0) {
			return -1;
		}

		position += read;
		remaining -= read;
		return read;
	}

	@Override
	public long skip(long count) {
		if (count <= 0 || remaining == 0) {
			return 0;
		}

		long skipped = Math.min(count, remaining);
		position += skipped;
		remaining -= skipped;
		return skipped;
	}

	@Override
	public int available() {
		return remaining > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) remaining;
	}
}
