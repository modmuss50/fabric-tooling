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

package net.fabricmc.zip;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.attribute.FileTime;

import org.junit.jupiter.api.Test;

import net.fabricmc.zip.api.CompressionCodec;
import net.fabricmc.zip.api.CompressionMethod;
import net.fabricmc.zip.api.ZipEntryView;

public class CompressionCodecTest {
	@Test
	void storedDataPassesThroughUnchanged() throws IOException {
		byte[] data = "stored".getBytes();
		byte[] inflated = readAllBytes(CompressionCodec.javaDefault().decompress(new TestEntry("stored.txt", CompressionMethod.STORED), new ByteArrayInputStream(data)));
		assertArrayEquals(data, inflated);
	}

	@Test
	void deflatedDataInflatesCorrectly() throws IOException {
		byte[] expected = "hello deflate".getBytes();
		TestZipBuilder builder = new TestZipBuilder().forceZip64();
		builder.addDeflated("hello.txt", expected).forceZip64();
		byte[] zip = builder.build();

		try (var view = net.fabricmc.zip.api.ZipView.open(new InMemoryZipByteSource(zip));
				InputStream raw = view.openRaw(view.getEntry("hello.txt").orElseThrow());
				InputStream inflated = CompressionCodec.javaDefault().decompress(view.getEntry("hello.txt").orElseThrow(), raw)) {
			assertArrayEquals(expected, readAllBytes(inflated));
		}
	}

	@Test
	void deflatedDataRoundTripsThroughCodec() throws IOException {
		byte[] expected = "hello codec".getBytes();
		ByteArrayOutputStream compressed = new ByteArrayOutputStream();

		try (OutputStream compressedOutput = CompressionCodec.javaDefault().compress(CompressionMethod.DEFLATED, compressed)) {
			compressedOutput.write(expected);
		}

		byte[] inflated = readAllBytes(CompressionCodec.javaDefault().decompress(new TestEntry("hello.txt", CompressionMethod.DEFLATED), new ByteArrayInputStream(compressed.toByteArray())));
		assertArrayEquals(expected, inflated);
	}

	private static byte[] readAllBytes(InputStream stream) throws IOException {
		return stream.readAllBytes();
	}

	private record TestEntry(String name, CompressionMethod method) implements ZipEntryView {
		@Override
		public String getName() {
			return name;
		}

		@Override
		public String getComment() {
			return null;
		}

		@Override
		public CompressionMethod getMethod() {
			return method;
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
			return 0;
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
