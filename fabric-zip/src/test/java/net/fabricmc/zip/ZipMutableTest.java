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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.zip.api.CompressionMethod;
import net.fabricmc.zip.api.WriteMode;
import net.fabricmc.zip.api.Zip;
import net.fabricmc.zip.api.ZipEntryView;
import net.fabricmc.zip.api.ZipView;

public class ZipMutableTest {
	@TempDir
	Path tempDir;

	@Test
	void createRequiresMissingFileAndOpenRequiresExistingFile() throws Exception {
		Path existing = tempDir.resolve("existing.zip");
		Files.write(existing, new TestZipBuilder().build());

		assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> Zip.create(existing));
		assertThrows(NoSuchFileException.class, () -> Zip.open(tempDir.resolve("missing.zip")));
	}

	@Test
	void createWritesValidEmptyArchiveOnClose() throws Exception {
		Path path = tempDir.resolve("empty.zip");

		try (Zip ignored = Zip.create(path)) {
			assertEquals(0, Files.size(path));
		}

		try (ZipView view = ZipView.open(path)) {
			assertTrue(view.entries().isEmpty());
		}
	}

	@Test
	void addSupportsByteArrayAndInputStream() throws Exception {
		Path path = tempDir.resolve("add.zip");

		try (Zip zip = Zip.create(path)) {
			zip.add("bytes.bin", new byte[] {1, 2, 3});
			zip.add("stream.txt", new ByteArrayInputStream("stream".getBytes()));
			assertTrue(zip.contains("bytes.bin"));
			assertEquals("stream", new String(readAllBytes(zip.open(zip.getEntry("stream.txt").orElseThrow()))));
			assertEquals(0, Files.size(path));
		}

		try (ZipView view = ZipView.open(path)) {
			assertArrayEquals(new byte[] {1, 2, 3}, readAllBytes(view.open(view.getEntry("bytes.bin").orElseThrow())));
			assertEquals("stream", new String(readAllBytes(view.open(view.getEntry("stream.txt").orElseThrow()))));
		}

		assertZipReadableByJava(path, Map.of(
				"bytes.bin", new byte[] {1, 2, 3},
				"stream.txt", "stream".getBytes()
		));
	}

	@Test
	void removeAndDuplicateValidationWork() throws Exception {
		Path path = tempDir.resolve("remove.zip");

		try (Zip zip = Zip.create(path)) {
			zip.add("value.txt", "value".getBytes());
			assertThrows(IllegalArgumentException.class, () -> zip.add("value.txt", "again".getBytes()));
			zip.remove("value.txt");
			assertFalse(zip.contains("value.txt"));
			assertThrows(IllegalArgumentException.class, () -> zip.remove("value.txt"));
		}
	}

	@Test
	void copyPreservesMethodAndRawBytes() throws Exception {
		Path sourcePath = tempDir.resolve("source.zip");
		TestZipBuilder sourceBuilder = new TestZipBuilder();
		sourceBuilder.addDeflated("copy.txt", "copy me".getBytes());
		Files.write(sourcePath, sourceBuilder.build());
		Path destinationPath = tempDir.resolve("destination.zip");

		try (ZipView source = ZipView.open(sourcePath);
				Zip destination = Zip.create(destinationPath)) {
			ZipEntryView sourceEntry = source.getEntry("copy.txt").orElseThrow();
			byte[] sourceRaw = readAllBytes(source.openRaw(sourceEntry));
			destination.copy(source, "copy.txt");

			ZipEntryView copied = destination.getEntry("copy.txt").orElseThrow();
			assertEquals(CompressionMethod.DEFLATED, copied.getMethod());
			assertArrayEquals(sourceRaw, readAllBytes(destination.openRaw(copied)));
		}

		try (ZipView view = ZipView.open(destinationPath)) {
			assertEquals("copy me", new String(readAllBytes(view.open(view.getEntry("copy.txt").orElseThrow()))));
		}
	}

	@Test
	void immediateModeWritesArchiveAfterEachMutation() throws Exception {
		Path path = tempDir.resolve("immediate.zip");

		try (Zip zip = Zip.create(path, options -> options.writeMode(WriteMode.IMMEDIATE))) {
			zip.add("now.txt", "now".getBytes());
			assertTrue(Files.size(path) > 0);

			try (ZipView diskView = ZipView.open(path)) {
				assertEquals("now", new String(readAllBytes(diskView.open(diskView.getEntry("now.txt").orElseThrow()))));
			}
		}

		assertZipReadableByJava(path, Map.of("now.txt", "now".getBytes()));
	}

	@Test
	void openExistingArchiveCanMutateContents() throws Exception {
		Path path = tempDir.resolve("open-existing.zip");
		TestZipBuilder builder = new TestZipBuilder();
		builder.addStored("remove.txt", "remove".getBytes());
		builder.addDeflated("keep.txt", "keep".getBytes());
		Files.write(path, builder.build());

		try (Zip zip = Zip.open(path)) {
			zip.remove("remove.txt");
			zip.add("add.txt", "add".getBytes());
			assertFalse(zip.contains("remove.txt"));
			assertTrue(zip.contains("keep.txt"));
		}

		try (ZipView view = ZipView.open(path)) {
			assertFalse(view.contains("remove.txt"));
			assertEquals("keep", new String(readAllBytes(view.open(view.getEntry("keep.txt").orElseThrow()))));
			assertEquals("add", new String(readAllBytes(view.open(view.getEntry("add.txt").orElseThrow()))));
		}
	}

	@Test
	void reproducibleModeWritesStableBytesAndSortedEntries() throws Exception {
		Path first = tempDir.resolve("first.zip");
		Path second = tempDir.resolve("second.zip");

		try (Zip zip = Zip.create(first, options -> options.reproducible(true))) {
			zip.add("b.txt", "beta".getBytes());
			zip.add("a.txt", "alpha".getBytes());
		}

		try (Zip zip = Zip.create(second, options -> options.reproducible(true))) {
			zip.add("a.txt", "alpha".getBytes());
			zip.add("b.txt", "beta".getBytes());
		}

		assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));

		try (ZipView view = ZipView.open(first)) {
			assertEquals("a.txt", view.entries().get(0).getName());
			assertEquals("b.txt", view.entries().get(1).getName());
		}

		assertZipReadableByJava(first, Map.of(
				"a.txt", "alpha".getBytes(),
				"b.txt", "beta".getBytes()
		));
	}

	@Test
	void writesSmallAndLargeFilesThatJavaCanRead() throws Exception {
		Path path = tempDir.resolve("sizes.zip");
		byte[] oneByte = new byte[] {42};
		byte[] medium = "small text payload".getBytes();
		byte[] large = new byte[2 * 1024 * 1024 + 321];

		for (int i = 0; i < large.length; i++) {
			large[i] = (byte) (i * 31);
		}

		try (Zip zip = Zip.create(path)) {
			zip.add("tiny.bin", oneByte);
			zip.add("small.txt", medium);
			zip.add("large.bin", large);

			assertArrayEquals(oneByte, readAllBytes(zip.open(zip.getEntry("tiny.bin").orElseThrow())));
			assertArrayEquals(large, readAllBytes(zip.open(zip.getEntry("large.bin").orElseThrow())));
		}

		assertZipReadableByJava(path, orderedEntries(
				"tiny.bin", oneByte,
				"small.txt", medium,
				"large.bin", large
		));
	}

	@Test
	void writesManyEntriesAndPreservesAllContents() throws Exception {
		Path path = tempDir.resolve("many.zip");
		LinkedHashMap<String, byte[]> expectedEntries = new LinkedHashMap<>();

		try (Zip zip = Zip.create(path)) {
			for (int i = 0; i < 400; i++) {
				String name = "nested/entry-" + i + ".txt";
				byte[] data = ("value-" + i + "-" + "x".repeat(i % 17)).getBytes();
				expectedEntries.put(name, data);
				zip.add(name, data);
			}

			assertEquals(expectedEntries.size(), zip.entries().size());
		}

		try (ZipView view = ZipView.open(path)) {
			assertEquals(expectedEntries.size(), view.entries().size());

			for (Map.Entry<String, byte[]> entry : expectedEntries.entrySet()) {
				assertArrayEquals(entry.getValue(), readAllBytes(view.open(view.getEntry(entry.getKey()).orElseThrow())));
			}
		}

		assertZipReadableByJava(path, expectedEntries);
	}

	@Test
	void openExistingLargeArchiveSupportsMixedMutationsAndJavaInterop() throws Exception {
		Path path = tempDir.resolve("mutated-large.zip");
		LinkedHashMap<String, byte[]> expectedEntries = new LinkedHashMap<>();
		TestZipBuilder builder = new TestZipBuilder();

		for (int i = 0; i < 120; i++) {
			byte[] data = ("seed-" + i).repeat((i % 9) + 1).getBytes();
			expectedEntries.put("seed-" + i + ".txt", data);
			builder.addDeflated("seed-" + i + ".txt", data);
		}

		Files.write(path, builder.build());

		try (Zip zip = Zip.open(path, options -> options.writeMode(WriteMode.IMMEDIATE))) {
			for (int i = 0; i < 20; i++) {
				String name = "seed-" + i + ".txt";
				zip.remove(name);
				expectedEntries.remove(name);
			}

			for (int i = 0; i < 25; i++) {
				String name = "added-" + i + ".bin";
				byte[] data = new byte[1024 + i * 13];

				for (int j = 0; j < data.length; j++) {
					data[j] = (byte) (i + j);
				}

				zip.add(name, data);
				expectedEntries.put(name, data);
			}
		}

		assertZipReadableByJava(path, expectedEntries);
	}

	@Test
	void invalidOptionsAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> Zip.create(tempDir.resolve("invalid.zip"), options -> options.reproducible(true).sparse(true)));
		assertThrows(IllegalArgumentException.class, () -> Zip.create(tempDir.resolve("invalid-write-mode.zip"), options -> options.sparse(true)));
	}

	@Test
	void mutableZipSupportsConcurrentReads() throws Exception {
		Path path = tempDir.resolve("concurrent.zip");

		try (Zip zip = Zip.create(path)) {
			zip.add("a.txt", "alpha".getBytes());
			zip.add("b.txt", "beta".getBytes());

			try (var executor = Executors.newFixedThreadPool(2)) {
				Future<byte[]> first = executor.submit(readEntry(zip, "a.txt"));
				Future<byte[]> second = executor.submit(readEntry(zip, "b.txt"));
				assertArrayEquals("alpha".getBytes(), first.get());
				assertArrayEquals("beta".getBytes(), second.get());
			}
		}
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void sparseModeSupportsAddRemoveAndReopen() throws Exception {
		Path path = tempDir.resolve("sparse.zip");
		TestZipBuilder builder = new TestZipBuilder();
		LinkedHashMap<String, byte[]> expectedEntries = new LinkedHashMap<>();

		for (int i = 0; i < 180; i++) {
			String name = "sparse/entry-" + i + "-" + "x".repeat(24) + ".bin";
			byte[] data = new byte[4096 + i * 17];

			for (int j = 0; j < data.length; j++) {
				data[j] = (byte) (i + j);
			}

			expectedEntries.put(name, data);
			builder.addDeflated(name, data);
		}

		Files.write(path, builder.build());

		String removedName = expectedEntries.keySet().iterator().next();
		expectedEntries.remove(removedName);
		byte[] addedData = new byte[8192];

		for (int i = 0; i < addedData.length; i++) {
			addedData[i] = (byte) (255 - i);
		}

		try (Zip zip = Zip.open(path, options -> options.sparse(true).writeMode(WriteMode.IMMEDIATE))) {
			long sizeBeforeRemove = Files.size(path);
			zip.remove(removedName);
			zip.add("sparse/added-entry.bin", addedData);
			assertTrue(Files.size(path) >= sizeBeforeRemove);
		}

		expectedEntries.put("sparse/added-entry.bin", addedData);

		try (ZipView view = ZipView.open(path)) {
			assertFalse(view.contains(removedName));
			assertArrayEquals(addedData, readAllBytes(view.open(view.getEntry("sparse/added-entry.bin").orElseThrow())));
		}

		assertZipReadableByJava(path, expectedEntries);
	}

	private static Callable<byte[]> readEntry(Zip zip, String name) {
		return () -> readAllBytes(zip.open(zip.getEntry(name).orElseThrow()));
	}

	private static LinkedHashMap<String, byte[]> orderedEntries(Object... keyValuePairs) {
		LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();

		for (int i = 0; i < keyValuePairs.length; i += 2) {
			entries.put((String) keyValuePairs[i], (byte[]) keyValuePairs[i + 1]);
		}

		return entries;
	}

	private static void assertZipReadableByJava(Path path, Map<String, byte[]> expectedEntries) throws Exception {
		try (ZipFile zipFile = new ZipFile(path.toFile())) {
			assertEquals(expectedEntries.size(), zipFile.size());

			for (Map.Entry<String, byte[]> entry : expectedEntries.entrySet()) {
				ZipEntry zipEntry = zipFile.getEntry(entry.getKey());
				assertTrue(zipEntry != null, "Missing ZipFile entry: " + entry.getKey());

				try (InputStream inputStream = zipFile.getInputStream(zipEntry)) {
					assertArrayEquals(entry.getValue(), inputStream.readAllBytes(), "ZipFile data mismatch for " + entry.getKey());
				}
			}
		}

		URI zipUri = URI.create("jar:" + path.toUri());

		try (FileSystem fileSystem = FileSystems.newFileSystem(zipUri, Map.of())) {
			for (Map.Entry<String, byte[]> entry : expectedEntries.entrySet()) {
				Path zipEntryPath = fileSystem.getPath("/" + entry.getKey());
				assertTrue(Files.exists(zipEntryPath), "Missing zipfs entry: " + entry.getKey());
				assertArrayEquals(entry.getValue(), Files.readAllBytes(zipEntryPath), "zipfs data mismatch for " + entry.getKey());
			}
		}
	}

	private static byte[] readAllBytes(InputStream inputStream) throws IOException {
		try (inputStream) {
			return inputStream.readAllBytes();
		}
	}
}
