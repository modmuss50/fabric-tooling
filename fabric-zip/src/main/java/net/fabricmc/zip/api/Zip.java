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

import org.jetbrains.annotations.ApiStatus;

import net.fabricmc.zip.impl.ZipImpl;

/**
 * A mutable ZIP archive.
 */
@ApiStatus.NonExtendable
public interface Zip extends ZipView {
	static Zip create(Path path) throws IOException {
		return create(path, ignored -> {
		});
	}

	static Zip create(Path path, Consumer<ZipOptions.Builder> options) throws IOException {
		return ZipImpl.create(path, ZipOptions.configure(options));
	}

	static Zip open(Path path) throws IOException {
		return open(path, ignored -> {
		});
	}

	static Zip open(Path path, Consumer<ZipOptions.Builder> options) throws IOException {
		return ZipImpl.open(path, ZipOptions.configure(options));
	}

	void add(String name, byte[] data) throws IOException;

	void add(String name, InputStream data) throws IOException;

	void remove(String name) throws IOException;

	void copy(ZipView source, String name) throws IOException;

	static void requireName(String name) {
		Objects.requireNonNull(name, "name");

		if (name.isEmpty()) {
			throw new IllegalArgumentException("ZIP entry names must not be empty");
		}
	}
}
