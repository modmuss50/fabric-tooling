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

import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

final class Payload implements Closeable {
	private final Path path;
	private final AtomicBoolean closed = new AtomicBoolean();

	Payload(Path path) {
		this.path = path;
	}

	static Payload copyOf(InputStream inputStream) throws IOException {
		Path tempFile = Files.createTempFile("fabric-zip-entry-", ".bin");

		try (inputStream; OutputStream outputStream = Files.newOutputStream(tempFile)) {
			inputStream.transferTo(outputStream);
		} catch (IOException | RuntimeException error) {
			Files.deleteIfExists(tempFile);
			throw error;
		}

		return new Payload(tempFile);
	}

	InputStream openStream() throws IOException {
		if (closed.get()) {
			throw new IllegalStateException("ZIP entry payload is closed");
		}

		return new FilterInputStream(Files.newInputStream(path)) {
			@Override
			public int available() throws IOException {
				long size = Files.size(path);
				return size > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) size;
			}
		};
	}

	@Override
	public void close() throws IOException {
		if (!closed.compareAndSet(false, true)) {
			return;
		}

		Files.deleteIfExists(path);
	}
}
