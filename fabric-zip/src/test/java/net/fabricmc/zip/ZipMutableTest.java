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
	void copySupportsRenamingTargetEntry() throws Exception {
		Path sourcePath = tempDir.resolve("source-renamed.zip");
		TestZipBuilder sourceBuilder = new TestZipBuilder();
		sourceBuilder.addStored("original.txt", "copy me renamed".getBytes());
		Files.write(sourcePath, sourceBuilder.build());
		Path destinationPath = tempDir.resolve("destination-renamed.zip");

		try (ZipView source = ZipView.open(sourcePath);
				Zip destination = Zip.create(destinationPath)) {
			destination.copy(source, "original.txt", "renamed.txt");
			assertFalse(destination.contains("original.txt"));
			assertEquals("copy me renamed", new String(readAllBytes(destination.open(destination.getEntry("renamed.txt").orElseThrow()))));
		}

		assertZipReadableByJava(destinationPath, Map.of("renamed.txt", "copy me renamed".getBytes()));
	}

	@Test
	void copyManySmallFilesFromReadOnlySourceZip() throws Exception {
		Path sourcePath = tempDir.resolve("source-many-small.zip");
		Path destinationPath = tempDir.resolve("destination-many-small.zip");
		TestZipBuilder sourceBuilder = new TestZipBuilder();
		LinkedHashMap<String, byte[]> expectedEntries = new LinkedHashMap<>();

		for (int i = 0; i < 300; i++) {
			String name = "small/group-" + (i % 12) + "/entry-" + i + ".txt";
			byte[] data = ("payload-" + i + "-" + "x".repeat(i % 9)).getBytes();
			expectedEntries.put(name, data);
			sourceBuilder.addDeflated(name, data);
		}

		Files.write(sourcePath, sourceBuilder.build());

		try (ZipView source = ZipView.open(sourcePath);
				Zip destination = Zip.create(destinationPath)) {
			for (String name : expectedEntries.keySet()) {
				destination.copy(source, name);
			}

			assertEquals(expectedEntries.size(), destination.entries().size());

			for (Map.Entry<String, byte[]> entry : expectedEntries.entrySet()) {
				assertArrayEquals(entry.getValue(), readAllBytes(destination.open(destination.getEntry(entry.getKey()).orElseThrow())));
			}
		}

		try (ZipView destinationView = ZipView.open(destinationPath)) {
			assertEquals(expectedEntries.size(), destinationView.entries().size());

			for (Map.Entry<String, byte[]> entry : expectedEntries.entrySet()) {
				assertArrayEquals(entry.getValue(), readAllBytes(destinationView.open(destinationView.getEntry(entry.getKey()).orElseThrow())));
			}
		}

		assertZipReadableByJava(destinationPath, expectedEntries);
	}

	@Test
	void replaceIsAtomicAndPreservesEntryName() throws Exception {
		Path path = tempDir.resolve("replace.zip");

		try (Zip zip = Zip.create(path)) {
			zip.add("value.txt", "old".getBytes());
			zip.replace("value.txt", "new".getBytes());
			assertEquals("new", new String(readAllBytes(zip.open(zip.getEntry("value.txt").orElseThrow()))));
			assertEquals(1, zip.entries().size());
		}

		assertZipReadableByJava(path, Map.of("value.txt", "new".getBytes()));
	}

	@Test
	void modifyUpdatesExistingEntryAtomically() throws Exception {
		Path path = tempDir.resolve("modify.zip");

		try (Zip zip = Zip.create(path)) {
			zip.add("value.txt", "base".getBytes());
			zip.modify("value.txt", bytes -> (new String(bytes) + "-modified").getBytes());
			assertEquals("base-modified", new String(readAllBytes(zip.open(zip.getEntry("value.txt").orElseThrow()))));
		}

		assertZipReadableByJava(path, Map.of("value.txt", "base-modified".getBytes()));
	}

	@Test
	void modifyFailureLeavesOriginalEntryUntouched() throws Exception {
		Path path = tempDir.resolve("modify-failure.zip");

		try (Zip zip = Zip.create(path)) {
			zip.add("value.txt", "stable".getBytes());
			assertThrows(IllegalStateException.class, () -> zip.modify("value.txt", ignored -> {
				throw new IllegalStateException("boom");
			}));
			assertEquals("stable", new String(readAllBytes(zip.open(zip.getEntry("value.txt").orElseThrow()))));
		}

		assertZipReadableByJava(path, Map.of("value.txt", "stable".getBytes()));
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
	void sparseModeSupportsSmallFileMutations() throws Exception {
		Path path = tempDir.resolve("sparse-small.zip");
		LinkedHashMap<String, byte[]> expectedEntries = new LinkedHashMap<>();
		byte[] alpha = "alpha".getBytes();
		byte[] beta = "beta".getBytes();
		byte[] gamma = "gamma".getBytes();

		try (Zip zip = Zip.create(path, options -> options.sparse(true).writeMode(WriteMode.IMMEDIATE))) {
			zip.add("small/alpha.txt", alpha);
			zip.add("small/beta.txt", beta);
			zip.remove("small/alpha.txt");
			zip.add("small/gamma.txt", gamma);

			expectedEntries.put("small/beta.txt", beta);
			expectedEntries.put("small/gamma.txt", gamma);
		}

		try (ZipView view = ZipView.open(path)) {
			assertFalse(view.contains("small/alpha.txt"));
			assertArrayEquals(beta, readAllBytes(view.open(view.getEntry("small/beta.txt").orElseThrow())));
			assertArrayEquals(gamma, readAllBytes(view.open(view.getEntry("small/gamma.txt").orElseThrow())));
		}

		assertZipReadableByJava(path, expectedEntries);
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void sparseModeSupportsLargeFileMutations() throws Exception {
		Path path = tempDir.resolve("sparse-large.zip");
		LinkedHashMap<String, byte[]> expectedEntries = new LinkedHashMap<>();
		byte[] largeA = new byte[256 * 1024];
		byte[] largeB = new byte[384 * 1024];
		byte[] largeC = new byte[512 * 1024];

		fillPattern(largeA, 17);
		fillPattern(largeB, 29);
		fillPattern(largeC, 43);

		try (Zip zip = Zip.create(path, options -> options.sparse(true).writeMode(WriteMode.IMMEDIATE))) {
			zip.add("large/a.bin", largeA);
			zip.add("large/b.bin", largeB);
			zip.remove("large/a.bin");
			zip.add("large/c.bin", largeC);

			expectedEntries.put("large/b.bin", largeB);
			expectedEntries.put("large/c.bin", largeC);
		}

		try (ZipView view = ZipView.open(path)) {
			assertFalse(view.contains("large/a.bin"));
			assertArrayEquals(largeB, readAllBytes(view.open(view.getEntry("large/b.bin").orElseThrow())));
			assertArrayEquals(largeC, readAllBytes(view.open(view.getEntry("large/c.bin").orElseThrow())));
		}

		assertZipReadableByJava(path, expectedEntries);
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void sparseModeReusesFreedSpaceForReplacementEntry() throws Exception {
		Path path = tempDir.resolve("sparse-reuse.zip");
		byte[] original = new byte[512 * 1024];
		byte[] replacement = new byte[160 * 1024];
		fillPattern(original, 73);
		fillPattern(replacement, 91);

		long sizeAfterRemove;
		long sizeAfterReplacement;

		try (Zip zip = Zip.create(path, options -> options.sparse(true).writeMode(WriteMode.IMMEDIATE))) {
			zip.add("payload.bin", original);
			zip.remove("payload.bin");
			sizeAfterRemove = Files.size(path);
			zip.add("replacement.bin", replacement);
			sizeAfterReplacement = Files.size(path);
		}

		assertTrue(sizeAfterReplacement - sizeAfterRemove < 8192, "Replacement entry should reuse freed sparse space");
		assertZipReadableByJava(path, Map.of("replacement.bin", replacement));
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void sparseModeRebuildsAndCoalescesFreeSpaceAfterReopen() throws Exception {
		Path path = tempDir.resolve("sparse-coalesce.zip");
		byte[] first = new byte[192 * 1024];
		byte[] second = new byte[192 * 1024];
		byte[] merged = new byte[320 * 1024];
		fillPattern(first, 7);
		fillPattern(second, 11);
		fillPattern(merged, 13);

		try (Zip zip = Zip.create(path, options -> options.sparse(true).writeMode(WriteMode.IMMEDIATE))) {
			zip.add("first.bin", first);
			zip.add("second.bin", second);
			zip.remove("first.bin");
			zip.remove("second.bin");
		}

		long sizeAfterReopenRemove = Files.size(path);

		try (Zip zip = Zip.open(path, options -> options.sparse(true).writeMode(WriteMode.IMMEDIATE))) {
			zip.add("merged.bin", merged);
		}

		long sizeAfterMerged = Files.size(path);
		assertTrue(sizeAfterMerged - sizeAfterReopenRemove < 8192, "Merged entry should fit within coalesced sparse free space");
		assertZipReadableByJava(path, Map.of("merged.bin", merged));
	}

	private static Callable<byte[]> readEntry(Zip zip, String name) {
		return () -> readAllBytes(zip.open(zip.getEntry(name).orElseThrow()));
	}

	private static void fillPattern(byte[] data, int seed) {
		for (int i = 0; i < data.length; i++) {
			data[i] = (byte) (seed + i * 31);
		}
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
