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
import java.io.OutputStream;

import net.fabricmc.zip.api.CompressionCodec;
import net.fabricmc.zip.api.CompressionMethod;
import net.fabricmc.zip.api.ZipEntryView;

public enum LibDeflateCompressionCodec implements CompressionCodec {
	INSTANCE;

	@Override
	public OutputStream compress(CompressionMethod method, OutputStream compressedData) throws IOException {
		throw unavailable();
	}

	@Override
	public InputStream decompress(ZipEntryView entry, InputStream compressedData) throws IOException {
		throw unavailable();
	}

	public boolean isAvailable() {
		return false;
	}

	public Throwable unavailableCause() {
		return null;
	}

	private IllegalStateException unavailable() {
		return new IllegalStateException("libdeflate is not available on this runtime");
	}
}
