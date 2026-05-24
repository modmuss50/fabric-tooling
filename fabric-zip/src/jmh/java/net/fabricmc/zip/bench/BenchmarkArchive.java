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

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import net.fabricmc.zip.api.ZipEntryView;

public final class BenchmarkArchive {
	final Path tempDir;
	final Path archivePath;
	final String targetName;
	final int entryCount;

	private BenchmarkArchive(Path tempDir, Path archivePath, String targetName, int entryCount) {
		this.tempDir = tempDir;
		this.archivePath = archivePath;
		this.targetName = targetName;
		this.entryCount = entryCount;
	}

	static BenchmarkArchive create(ArchiveShape shape) throws IOException {
		Path tempDir = Files.createTempDirectory("fabric-zip-jmh-");
		Path archivePath = tempDir.resolve(shape.fileName);

		try (ZipOutputStream outputStream = new ZipOutputStream(Files.newOutputStream(archivePath))) {
			for (int i = 0; i < shape.entryCount; i++) {
				String entryName = shape.entryName(i);
				outputStream.putNextEntry(new ZipEntry(entryName));
				outputStream.write(shape.entryData(i));
				outputStream.closeEntry();
			}
		}

		return new BenchmarkArchive(tempDir, archivePath, shape.targetName(), shape.entryCount);
	}

	void delete() throws IOException {
		Files.walkFileTree(tempDir, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.deleteIfExists(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
				if (exc != null) {
					throw exc;
				}

				Files.deleteIfExists(dir);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	public enum ArchiveShape {
		SMALL_ENTRIES("small-entries.zip", 64, 256, 31, 32),
		LARGE_ENTRY("large-entry.zip", 8, 2 * 1024 * 1024, 97, 3),
		MANY_ENTRIES("many-entries.zip", 2_000, 96, 53, 1_500);

		private final String fileName;
		private final int entryCount;
		private final int defaultSize;
		private final int seed;
		private final int targetIndex;

		ArchiveShape(String fileName, int entryCount, int defaultSize, int seed, int targetIndex) {
			this.fileName = fileName;
			this.entryCount = entryCount;
			this.defaultSize = defaultSize;
			this.seed = seed;
			this.targetIndex = targetIndex;
		}

		String entryName(int index) {
			return "bench/entry-%04d.bin".formatted(index);
		}

		String targetName() {
			return entryName(targetIndex);
		}

		byte[] entryData(int index) {
			int size = switch (this) {
			case SMALL_ENTRIES -> defaultSize + (index % 5) * 32;
			case LARGE_ENTRY -> index == targetIndex ? defaultSize : 2_048 + index * 97;
			case MANY_ENTRIES -> defaultSize + (index % 7) * 8;
			};

			byte[] data = new byte[size];

			for (int i = 0; i < size; i++) {
				data[i] = (byte) (seed + index * 17 + i * 31);
			}

			return data;
		}
	}

	static int countZipFsEntries(Path root) throws IOException {
		try (var paths = Files.walk(root)) {
			return paths.filter(Files::isRegularFile)
					.mapToInt(path -> path.toString().hashCode())
					.reduce(1, (left, right) -> 31 * left + right);
		}
	}

	static int countFabricEntries(List<ZipEntryView> entries) {
		int hash = 1;

		for (ZipEntryView entry : entries) {
			hash = 31 * hash + entry.getName().hashCode();
		}

		return hash;
	}

	static int countZipFileEntries(ZipFile zipFile) {
		int hash = 1;
		Enumeration<? extends ZipEntry> entries = zipFile.entries();

		while (entries.hasMoreElements()) {
			hash = 31 * hash + entries.nextElement().getName().hashCode();
		}

		return hash;
	}

	static byte[] readAll(java.io.InputStream inputStream) throws IOException {
		try (inputStream) {
			return inputStream.readAllBytes();
		}
	}
}
