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
import java.io.OutputStream;

import org.jetbrains.annotations.ApiStatus;

import net.fabricmc.zip.impl.JavaCompressionCodec;
import net.fabricmc.zip.impl.LibDeflateCompressionCodec;

/**
 * Compresses and inflates ZIP entry payloads.
 */
@ApiStatus.NonExtendable
public interface CompressionCodec {
	/**
	 * Returns the default codec backed by the JDK compression classes.
	 *
	 * @return the default codec backed by the JDK compression classes.
	 */
	static CompressionCodec javaDefault() {
		return JavaCompressionCodec.INSTANCE;
	}

	/**
	 * Returns the preferred default codec for the current runtime.
	 *
	 * @return the preferred default codec for the current runtime.
	 */
	static CompressionCodec defaultCodec() {
		return LibDeflateCompressionCodec.INSTANCE.isAvailable() ? LibDeflateCompressionCodec.INSTANCE : javaDefault();
	}

	/**
	 * Returns a codec backed by the native {@code libdeflate} library.
	 *
	 * @return a codec backed by the native {@code libdeflate} library.
	 */
	static CompressionCodec libdeflate() {
		return LibDeflateCompressionCodec.INSTANCE;
	}

	/**
	 * Wraps an output stream so entry data written to it is encoded with the supplied method.
	 *
	 * @param method the compression method to apply.
	 * @param compressedData the target stream for the encoded payload.
	 * @return a stream that accepts uncompressed entry data.
	 * @throws IOException if compression setup fails.
	 */
	OutputStream compress(CompressionMethod method, OutputStream compressedData) throws IOException;

	/**
	 * Wraps the raw compressed payload for the supplied entry.
	 *
	 * @param entry the entry being read.
	 * @param compressedData the raw compressed payload stream.
	 * @return an inflated stream for the entry contents.
	 * @throws IOException if decompression fails.
	 */
	InputStream decompress(ZipEntryView entry, InputStream compressedData) throws IOException;
}
