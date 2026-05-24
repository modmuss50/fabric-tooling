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

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import net.fabricmc.zip.api.CompressionCodec;
import net.fabricmc.zip.api.CompressionMethod;
import net.fabricmc.zip.api.UnsupportedZipFeatureException;
import net.fabricmc.zip.api.ZipEntryView;

public enum JavaCompressionCodec implements CompressionCodec {
	INSTANCE;

	private static final int INFLATE_BUFFER_SIZE = 16 * 1024;
	private static final int DEFLATE_BUFFER_SIZE = 8 * 1024;

	@Override
	public OutputStream compress(CompressionMethod method, OutputStream compressedData) throws IOException {
		return switch (method) {
		case STORED -> compressedData;
		case DEFLATED -> newDeflaterStream(compressedData);
		default -> throw new UnsupportedZipFeatureException("Unsupported compression method: " + method);
		};
	}

	@Override
	public InputStream decompress(ZipEntryView entry, InputStream compressedData) throws IOException {
		return switch (entry.getMethod()) {
		case STORED -> compressedData;
		case DEFLATED -> newInflaterStream(compressedData);
		default -> throw new UnsupportedZipFeatureException("Unsupported compression method: " + entry.getMethod());
		};
	}

	private static InputStream newInflaterStream(InputStream compressedData) {
		Inflater inflater = new Inflater(true);
		InflaterInputStream inflaterStream = new InflaterInputStream(compressedData, inflater, INFLATE_BUFFER_SIZE);

		return new FilterInputStream(inflaterStream) {
			@Override
			public void close() throws IOException {
				try {
					super.close();
				} finally {
					inflater.end();
				}
			}
		};
	}

	private static OutputStream newDeflaterStream(OutputStream compressedData) {
		Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
		DeflaterOutputStream deflaterStream = new DeflaterOutputStream(compressedData, deflater, DEFLATE_BUFFER_SIZE, true);

		return new FilterOutputStream(deflaterStream) {
			private boolean closed;

			@Override
			public void close() throws IOException {
				if (closed) {
					return;
				}

				closed = true;

				try {
					deflaterStream.finish();
				} finally {
					try {
						super.close();
					} finally {
						deflater.end();
					}
				}
			}
		};
	}
}
