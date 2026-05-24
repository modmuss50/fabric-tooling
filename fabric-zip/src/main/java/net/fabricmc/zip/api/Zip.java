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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

import org.jetbrains.annotations.ApiStatus;

import net.fabricmc.zip.impl.ZipImpl;

/**
 * A mutable ZIP archive.
 */
@ApiStatus.NonExtendable
public interface Zip extends ZipView {
	/**
	 * Creates a new ZIP archive with default options.
	 *
	 * @param path the archive path to create.
	 * @return the newly created mutable ZIP.
	 * @throws IOException if the archive cannot be created.
	 */
	static Zip create(Path path) throws IOException {
		return create(path, ignored -> {
		});
	}

	/**
	 * Creates a new ZIP archive using the supplied options.
	 *
	 * @param path the archive path to create.
	 * @param options configures mutable ZIP options.
	 * @return the newly created mutable ZIP.
	 * @throws IOException if the archive cannot be created.
	 */
	static Zip create(Path path, Consumer<ZipOptions.Builder> options) throws IOException {
		return ZipImpl.create(path, ZipOptions.configure(options));
	}

	/**
	 * Opens an existing ZIP archive with default options.
	 *
	 * @param path the archive path to open.
	 * @return the opened mutable ZIP.
	 * @throws IOException if the archive cannot be opened.
	 */
	static Zip open(Path path) throws IOException {
		return open(path, ignored -> {
		});
	}

	/**
	 * Opens an existing ZIP archive using the supplied options.
	 *
	 * @param path the archive path to open.
	 * @param options configures mutable ZIP options.
	 * @return the opened mutable ZIP.
	 * @throws IOException if the archive cannot be opened.
	 */
	static Zip open(Path path, Consumer<ZipOptions.Builder> options) throws IOException {
		return ZipImpl.open(path, ZipOptions.configure(options));
	}

	/**
	 * Adds a new entry from the supplied bytes using the archive's default compression method.
	 *
	 * @param name the entry name.
	 * @param data the uncompressed entry data.
	 * @throws IOException if the entry cannot be added.
	 */
	void add(String name, byte[] data) throws IOException;

	/**
	 * Adds a new entry from the supplied stream using the archive's default compression method.
	 *
	 * @param name the entry name.
	 * @param data the uncompressed entry data stream.
	 * @throws IOException if the entry cannot be added.
	 */
	void add(String name, InputStream data) throws IOException;

	/**
	 * Removes an existing entry.
	 *
	 * @param name the entry name.
	 * @throws IOException if the entry cannot be removed.
	 */
	void remove(String name) throws IOException;

	/**
	 * Copies an entry from another ZIP view without recompressing it.
	 *
	 * @param source the source ZIP view.
	 * @param name the source and target entry name.
	 * @throws IOException if the entry cannot be copied.
	 */
	void copy(ZipView source, String name) throws IOException;

	/**
	 * Copies an entry from another ZIP view under a new name without recompressing it.
	 *
	 * @param source the source ZIP view.
	 * @param sourceName the source entry name.
	 * @param targetName the target entry name.
	 * @throws IOException if the entry cannot be copied.
	 */
	void copy(ZipView source, String sourceName, String targetName) throws IOException;

	/**
	 * Atomically replaces an existing entry with the supplied bytes.
	 *
	 * @param name the entry name.
	 * @param data the new uncompressed entry data.
	 * @throws IOException if the entry cannot be replaced.
	 */
	void replace(String name, byte[] data) throws IOException;

	/**
	 * Atomically replaces an existing entry with data from the supplied stream.
	 *
	 * @param name the entry name.
	 * @param data the new uncompressed entry data stream.
	 * @throws IOException if the entry cannot be replaced.
	 */
	void replace(String name, InputStream data) throws IOException;

	/**
	 * Atomically replaces an existing entry with the result of applying a modifier to its current data.
	 *
	 * @param name the entry name.
	 * @param modifier transforms the current uncompressed entry data into the replacement data.
	 * @throws IOException if the entry cannot be modified.
	 */
	void modify(String name, Function<byte[], byte[]> modifier) throws IOException;

	/**
	 * Validates that a ZIP entry name is non-null and non-empty.
	 *
	 * @param name the entry name to validate.
	 */
	static void requireName(String name) {
		Objects.requireNonNull(name, "name");

		if (name.isEmpty()) {
			throw new IllegalArgumentException("ZIP entry names must not be empty");
		}
	}
}
