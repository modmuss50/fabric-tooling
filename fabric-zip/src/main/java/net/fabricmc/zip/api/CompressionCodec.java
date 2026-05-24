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

import org.jetbrains.annotations.ApiStatus;

import net.fabricmc.zip.impl.JavaCompressionCodec;

/**
 * Inflates ZIP entry payloads.
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
	 * Wraps the raw compressed payload for the supplied entry.
	 *
	 * @param entry the entry being read.
	 * @param compressedData the raw compressed payload stream.
	 * @return an inflated stream for the entry contents.
	 * @throws IOException if decompression fails.
	 */
	InputStream decompress(ZipEntryView entry, InputStream compressedData) throws IOException;
}
