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

package net.fabricmc.zip.bench;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import net.fabricmc.zip.api.CompressionCodec;
import net.fabricmc.zip.api.CompressionMethod;
import net.fabricmc.zip.api.ZipEntryView;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
public class CompressionCodecBenchmark {
	@State(Scope.Benchmark)
	public static class CodecState {
		@Param({"SMALL_TEXT", "MEDIUM_MIXED", "LARGE_REPETITIVE"})
		public PayloadShape payloadShape;

		byte[] uncompressed;
		byte[] javaCompressed;
		byte[] libdeflateCompressed;
		ZipEntryView entry;
		CompressionCodec javaCodec;
		CompressionCodec libdeflateCodec;

		@Setup(Level.Trial)
		public void setUp() throws Exception {
			javaCodec = CompressionCodec.javaDefault();
			libdeflateCodec = CompressionCodec.libdeflate();
			uncompressed = payloadShape.createData();
			entry = new BenchmarkEntry(uncompressed.length);
			javaCompressed = compress(javaCodec, uncompressed);
			libdeflateCompressed = compress(libdeflateCodec, uncompressed);
		}
	}

	@Benchmark
	public void javaCompress(CodecState state, Blackhole blackhole) throws Exception {
		blackhole.consume(compress(state.javaCodec, state.uncompressed));
	}

	@Benchmark
	public void libdeflateCompress(CodecState state, Blackhole blackhole) throws Exception {
		blackhole.consume(compress(state.libdeflateCodec, state.uncompressed));
	}

	@Benchmark
	public void javaDecompressJavaData(CodecState state, Blackhole blackhole) throws Exception {
		blackhole.consume(decompress(state.javaCodec, state.entry, state.javaCompressed));
	}

	@Benchmark
	public void libdeflateDecompressJavaData(CodecState state, Blackhole blackhole) throws Exception {
		blackhole.consume(decompress(state.libdeflateCodec, state.entry, state.javaCompressed));
	}

	@Benchmark
	public void javaDecompressLibdeflateData(CodecState state, Blackhole blackhole) throws Exception {
		blackhole.consume(decompress(state.javaCodec, state.entry, state.libdeflateCompressed));
	}

	@Benchmark
	public void libdeflateDecompressLibdeflateData(CodecState state, Blackhole blackhole) throws Exception {
		blackhole.consume(decompress(state.libdeflateCodec, state.entry, state.libdeflateCompressed));
	}

	private static byte[] compress(CompressionCodec codec, byte[] data) throws Exception {
		ByteArrayOutputStream output = new ByteArrayOutputStream();

		try (OutputStream compressed = codec.compress(CompressionMethod.DEFLATED, output)) {
			compressed.write(data);
		}

		return output.toByteArray();
	}

	private static byte[] decompress(CompressionCodec codec, ZipEntryView entry, byte[] data) throws Exception {
		try (InputStream inflated = codec.decompress(entry, new ByteArrayInputStream(data))) {
			return inflated.readAllBytes();
		}
	}

	public enum PayloadShape {
		SMALL_TEXT(512) {
			@Override
			byte[] createData() {
				return ("The quick brown fox jumps over the lazy dog. ").repeat(12).getBytes();
			}
		},
		MEDIUM_MIXED(64 * 1024) {
			@Override
			byte[] createData() {
				byte[] data = new byte[size];

				for (int i = 0; i < data.length; i++) {
					data[i] = (byte) ((i * 31) ^ (i >>> 3) ^ (i % 11 == 0 ? 0x55 : 0x0F));
				}

				return data;
			}
		},
		LARGE_REPETITIVE(2 * 1024 * 1024) {
			@Override
			byte[] createData() {
				byte[] pattern = "fabric-zip-libdeflate-benchmark-".repeat(8).getBytes();
				byte[] data = new byte[size];

				for (int offset = 0; offset < data.length; offset += pattern.length) {
					System.arraycopy(pattern, 0, data, offset, Math.min(pattern.length, data.length - offset));
				}

				return data;
			}
		};

		final int size;

		PayloadShape(int size) {
			this.size = size;
		}

		abstract byte[] createData();
	}

	private record BenchmarkEntry(long uncompressedSize) implements ZipEntryView {
		@Override
		public String getName() {
			return "benchmark.bin";
		}

		@Override
		public String getComment() {
			return null;
		}

		@Override
		public CompressionMethod getMethod() {
			return CompressionMethod.DEFLATED;
		}

		@Override
		public int getFlags() {
			return 0;
		}

		@Override
		public long getCrc32() {
			return 0;
		}

		@Override
		public long getCompressedSize() {
			return 0;
		}

		@Override
		public long getUncompressedSize() {
			return uncompressedSize;
		}

		@Override
		public long getLocalHeaderOffset() {
			return 0;
		}

		@Override
		public long getCentralDirectoryOffset() {
			return 0;
		}

		@Override
		public boolean isDirectory() {
			return false;
		}

		@Override
		public FileTime getLastModifiedTime() {
			return null;
		}

		@Override
		public FileTime getLastAccessTime() {
			return null;
		}

		@Override
		public FileTime getCreationTime() {
			return null;
		}
	}
}
