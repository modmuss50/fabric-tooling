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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import net.fabricmc.zip.api.CompressionCodec;
import net.fabricmc.zip.api.CompressionMethod;
import net.fabricmc.zip.api.UnsupportedZipFeatureException;
import net.fabricmc.zip.api.ZipEntryView;

public enum LibDeflateCompressionCodec implements CompressionCodec {
	INSTANCE;

	private static final int DEFAULT_COMPRESSION_LEVEL = 6;
	private static final int LIBDEFLATE_SUCCESS = 0;
	private static final NativeLibrary NATIVE_LIBRARY = NativeLibrary.load();

	@Override
	public OutputStream compress(CompressionMethod method, OutputStream compressedData) throws IOException {
		ensureAvailable();

		return switch (method) {
		case STORED -> compressedData;
		case DEFLATED -> new FilterOutputStream(compressedData) {
			private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			private boolean closed;

			@Override
			public void write(int b) {
				buffer.write(b);
			}

			@Override
			public void write(byte[] b, int off, int len) {
				buffer.write(b, off, len);
			}

			@Override
			public void close() throws IOException {
				if (closed) {
					return;
				}

				closed = true;

				try {
					out.write(deflate(buffer.toByteArray()));
				} finally {
					super.close();
				}
			}
		};
		default -> throw new UnsupportedZipFeatureException("Unsupported compression method: " + method);
		};
	}

	@Override
	public InputStream decompress(ZipEntryView entry, InputStream compressedData) throws IOException {
		ensureAvailable();

		return switch (entry.getMethod()) {
		case STORED -> compressedData;
		case DEFLATED -> new ByteArrayInputStream(inflate(compressedData.readAllBytes(), entry.getUncompressedSize()));
		default -> throw new UnsupportedZipFeatureException("Unsupported compression method: " + entry.getMethod());
		};
	}

	public boolean isAvailable() {
		return NATIVE_LIBRARY.available();
	}

	public Throwable unavailableCause() {
		return NATIVE_LIBRARY.failure();
	}

	private void ensureAvailable() {
		if (!isAvailable()) {
			throw new IllegalStateException("libdeflate is not available", unavailableCause());
		}
	}

	private static byte[] deflate(byte[] input) throws IOException {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment compressor = NATIVE_LIBRARY.allocateCompressor(DEFAULT_COMPRESSION_LEVEL);

			if (compressor.equals(MemorySegment.NULL)) {
				throw new IOException("Failed to allocate libdeflate compressor");
			}

			try {
				long bound = NATIVE_LIBRARY.computeDeflateCompressBound(compressor, input.length);
				MemorySegment inputSegment = arena.allocateFrom(ValueLayout.JAVA_BYTE, input);
				MemorySegment outputSegment = arena.allocate(bound);
				long compressedSize = NATIVE_LIBRARY.compressDeflate(compressor, inputSegment, input.length, outputSegment, bound);

				if (compressedSize == 0) {
					throw new IOException("libdeflate failed to compress ZIP entry data");
				}

				return outputSegment.asSlice(0, compressedSize).toArray(ValueLayout.JAVA_BYTE);
			} finally {
				NATIVE_LIBRARY.releaseCompressor(compressor);
			}
		}
	}

	private static byte[] inflate(byte[] input, long expectedSize) throws IOException {
		if (expectedSize == 0) {
			return new byte[0];
		}

		if (expectedSize < 0 || expectedSize > Integer.MAX_VALUE) {
			throw new IOException("Unsupported uncompressed size for libdeflate codec: " + expectedSize);
		}

		try (Arena arena = Arena.ofConfined()) {
			MemorySegment decompressor = NATIVE_LIBRARY.allocateDecompressor();

			if (decompressor.equals(MemorySegment.NULL)) {
				throw new IOException("Failed to allocate libdeflate decompressor");
			}

			try {
				MemorySegment inputSegment = arena.allocateFrom(ValueLayout.JAVA_BYTE, input);
				MemorySegment outputSegment = arena.allocate(expectedSize);
				int result = NATIVE_LIBRARY.decompressDeflate(decompressor, inputSegment, input.length, outputSegment, expectedSize);

				if (result != LIBDEFLATE_SUCCESS) {
					throw new IOException("libdeflate failed to decompress ZIP entry data: result=" + result);
				}

				return outputSegment.toArray(ValueLayout.JAVA_BYTE);
			} finally {
				NATIVE_LIBRARY.releaseDecompressor(decompressor);
			}
		}
	}

	private record NativeLibrary(
			SymbolLookup lookup,
			Throwable failure,
			MethodHandle allocCompressor,
			MethodHandle freeCompressor,
			MethodHandle deflateCompressBound,
			MethodHandle deflateCompress,
			MethodHandle allocDecompressor,
			MethodHandle freeDecompressor,
			MethodHandle deflateDecompress
	) {
		private static final Arena ARENA = Arena.ofShared();

		static NativeLibrary load() {
			try {
				SymbolLookup lookup = SymbolLookup.libraryLookup(libraryPath(), ARENA);
				return new NativeLibrary(
						lookup,
						null,
						downcall(lookup, "libdeflate_alloc_compressor", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT)),
						downcall(lookup, "libdeflate_free_compressor", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)),
						downcall(lookup, "libdeflate_deflate_compress_bound", FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)),
						downcall(lookup, "libdeflate_deflate_compress", FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)),
						downcall(lookup, "libdeflate_alloc_decompressor", FunctionDescriptor.of(ValueLayout.ADDRESS)),
						downcall(lookup, "libdeflate_free_decompressor", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)),
						downcall(lookup, "libdeflate_deflate_decompress", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS))
				);
			} catch (Throwable throwable) {
				return new NativeLibrary(SymbolLookup.loaderLookup(), throwable, null, null, null, null, null, null, null);
			}
		}

		boolean available() {
			return failure == null;
		}

		public Throwable failure() {
			return failure;
		}

		MemorySegment allocateCompressor(int compressionLevel) throws IOException {
			return invoke(allocCompressor, compressionLevel);
		}

		void releaseCompressor(MemorySegment compressor) throws IOException {
			invoke(freeCompressor, compressor);
		}

		long computeDeflateCompressBound(MemorySegment compressor, long inputLength) throws IOException {
			return invoke(deflateCompressBound, compressor, inputLength);
		}

		long compressDeflate(MemorySegment compressor, MemorySegment input, long inputLength, MemorySegment output, long outputLength) throws IOException {
			return invoke(deflateCompress, compressor, input, inputLength, output, outputLength);
		}

		MemorySegment allocateDecompressor() throws IOException {
			return invoke(allocDecompressor);
		}

		void releaseDecompressor(MemorySegment decompressor) throws IOException {
			invoke(freeDecompressor, decompressor);
		}

		int decompressDeflate(MemorySegment decompressor, MemorySegment input, long inputLength, MemorySegment output, long outputLength) throws IOException {
			return invoke(deflateDecompress, decompressor, input, inputLength, output, outputLength, MemorySegment.NULL);
		}

		private static Path libraryPath() {
			String configuredPath = System.getProperty("fabric.zip.libdeflate.path");

			if (configuredPath != null && !configuredPath.isBlank()) {
				return Path.of(configuredPath);
			}

			for (Path candidate : List.of(
					Path.of("/opt/homebrew/opt/libdeflate/lib/libdeflate.dylib"),
					Path.of("/usr/local/opt/libdeflate/lib/libdeflate.dylib")
			)) {
				if (Files.isRegularFile(candidate)) {
					return candidate;
				}
			}

			throw new IllegalStateException("Unable to locate libdeflate.dylib");
		}

		private static MethodHandle downcall(SymbolLookup lookup, String symbolName, FunctionDescriptor functionDescriptor) {
			MemorySegment symbol = lookup.find(symbolName).orElseThrow(() -> new IllegalStateException("Missing native symbol: " + symbolName));
			return Linker.nativeLinker().downcallHandle(symbol, functionDescriptor);
		}

		@SuppressWarnings("unchecked")
		private static <T> T invoke(MethodHandle methodHandle, Object... args) throws IOException {
			try {
				return (T) methodHandle.invokeWithArguments(args);
			} catch (Throwable throwable) {
				throw new IOException("libdeflate FFM call failed", throwable);
			}
		}
	}
}
