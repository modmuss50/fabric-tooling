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
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import net.fabricmc.zip.api.Zip;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
public class ZipModifyBenchmark {
	@State(Scope.Thread)
	public static class ModifyState {
		private Path tempDir;
		private Path archivePath;
		private URI zipUri;

		@Setup(Level.Iteration)
		public void setUp() throws Exception {
			tempDir = Files.createTempDirectory("fabric-zip-jmh-modify-");
			archivePath = tempDir.resolve("modify-source.zip");
			BenchmarkArchive.writeModifyArchive(archivePath);
			zipUri = URI.create("jar:" + archivePath.toUri());
		}

		@TearDown(Level.Iteration)
		public void tearDown() throws Exception {
			BenchmarkArchive.deleteRecursively(tempDir);
		}
	}

	@Benchmark
	public void fabricImmediateModify(ModifyState state, Blackhole blackhole) throws Exception {
		try (Zip zip = Zip.open(state.archivePath)) {
			for (int index = 0; index < BenchmarkArchive.modifyEntryCount(); index++) {
				zip.replace(BenchmarkArchive.modifyEntryNameForIndex(index), BenchmarkArchive.modifyReplacementData(index));
			}
		}

		blackhole.consume(Files.size(state.archivePath));
	}

	@Benchmark
	public void nioZipFsModify(ModifyState state, Blackhole blackhole) throws Exception {
		try (FileSystem fileSystem = FileSystems.newFileSystem(state.zipUri, Map.of())) {
			for (int index = 0; index < BenchmarkArchive.modifyEntryCount(); index++) {
				Files.write(fileSystem.getPath("/" + BenchmarkArchive.modifyEntryNameForIndex(index)), BenchmarkArchive.modifyReplacementData(index));
			}
		}

		blackhole.consume(Files.size(state.archivePath));
	}

	@Benchmark
	public void javaRewriteModify(ModifyState state, Blackhole blackhole) throws Exception {
		Path rewritten = state.tempDir.resolve("rewrite.zip");

		try (ZipFile zipFile = new ZipFile(state.archivePath.toFile());
				OutputStream outputStream = Files.newOutputStream(rewritten);
				ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream)) {
			Enumeration<? extends ZipEntry> entries = zipFile.entries();

			while (entries.hasMoreElements()) {
				ZipEntry sourceEntry = entries.nextElement();
				ZipEntry targetEntry = new ZipEntry(sourceEntry.getName());
				int entryIndex = parseModifyIndex(sourceEntry.getName());
				byte[] replacementData = BenchmarkArchive.modifyReplacementData(entryIndex);
				targetEntry.setMethod(sourceEntry.getMethod());

				if (sourceEntry.getComment() != null) {
					targetEntry.setComment(sourceEntry.getComment());
				}

				if (sourceEntry.getTime() != -1L) {
					targetEntry.setTime(sourceEntry.getTime());
				}

				if (sourceEntry.getMethod() == ZipEntry.STORED) {
					byte[] storedBytes = replacementData;
					targetEntry.setSize(storedBytes.length);
					targetEntry.setCompressedSize(storedBytes.length);
					targetEntry.setCrc(BenchmarkArchive.crc32(storedBytes));
					zipOutputStream.putNextEntry(targetEntry);
					zipOutputStream.write(storedBytes);
				} else {
					zipOutputStream.putNextEntry(targetEntry);
					zipOutputStream.write(replacementData);
				}

				zipOutputStream.closeEntry();
			}
		}

		Files.move(rewritten, state.archivePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		blackhole.consume(Files.size(state.archivePath));
	}
	private static int parseModifyIndex(String entryName) {
		int start = entryName.lastIndexOf('-') + 1;
		int end = entryName.indexOf('.', start);
		return Integer.parseInt(entryName.substring(start, end));
	}
}
