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
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.ApiStatus;

import net.fabricmc.zip.impl.ZipViewImpl;

/**
 * A read-only view of a ZIP archive.
 */
@ApiStatus.NonExtendable
public interface ZipView extends Closeable {
	/**
	 * Opens a ZIP archive backed by the supplied path.
	 *
	 * @param path the archive path.
	 * @return a read-only view of the archive.
	 * @throws IOException if the archive cannot be opened.
	 */
	static ZipView open(Path path) throws IOException {
		return open(ZipByteSource.of(path));
	}

	/**
	 * Opens a ZIP archive backed by the supplied byte source.
	 *
	 * @param source the archive byte source.
	 * @return a read-only view of the archive.
	 * @throws IOException if the archive cannot be opened.
	 */
	static ZipView open(ZipByteSource source) throws IOException {
		return open(source, CompressionCodec.javaDefault());
	}

	/**
	 * Opens a ZIP archive backed by the supplied byte source using the given compression codec.
	 *
	 * @param source the archive byte source.
	 * @param compressionCodec the codec used to inflate compressed entry data.
	 * @return a read-only view of the archive.
	 * @throws IOException if the archive cannot be opened.
	 */
	static ZipView open(ZipByteSource source, CompressionCodec compressionCodec) throws IOException {
		return ZipViewImpl.open(source, compressionCodec);
	}

	List<ZipEntryView> entries();

	Optional<ZipEntryView> getEntry(String name);

	default boolean contains(String name) {
		return getEntry(name).isPresent();
	}

	InputStream open(ZipEntryView entry) throws IOException;

	InputStream openRaw(ZipEntryView entry) throws IOException;
}
