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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Objects;

import net.fabricmc.zip.api.ZipByteSource;

class ChannelZipByteSource implements ZipByteSource {
	private final FileChannel channel;
	private final boolean closeChannel;

	ChannelZipByteSource(FileChannel channel, boolean closeChannel) {
		this.channel = Objects.requireNonNull(channel, "channel");
		this.closeChannel = closeChannel;
	}

	protected final FileChannel channel() {
		return channel;
	}

	@Override
	public long size() throws IOException {
		return channel.size();
	}

	@Override
	public int read(long position, byte[] buffer, int offset, int length) throws IOException {
		Objects.checkFromIndexSize(offset, length, buffer.length);

		if (length == 0) {
			return 0;
		}

		if (position < 0) {
			throw new IllegalArgumentException("ZIP positions must be non-negative");
		}

		return channel.read(ByteBuffer.wrap(buffer, offset, length), position);
	}

	@Override
	public void close() throws IOException {
		if (closeChannel) {
			channel.close();
		}
	}
}
