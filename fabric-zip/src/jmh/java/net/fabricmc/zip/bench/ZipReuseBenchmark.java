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

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import net.fabricmc.zip.api.ZipView;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 4, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 6, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
public class ZipReuseBenchmark {
	@State(Scope.Thread)
	public static class OpenArchiveState {
		@Param({"SMALL_ENTRIES", "LARGE_ENTRY", "MANY_ENTRIES"})
		public BenchmarkArchive.ArchiveShape shape;

		private BenchmarkArchive archive;
		private ZipView zipView;
		private ZipFile zipFile;
		private FileSystem fileSystem;
		private Path zipFsTargetPath;

		@Setup(Level.Trial)
		public void setUp() throws Exception {
			archive = BenchmarkArchive.create(shape);
			zipView = ZipView.open(archive.archivePath);
			zipFile = new ZipFile(archive.archivePath.toFile());
			fileSystem = FileSystems.newFileSystem(URI.create("jar:" + archive.archivePath.toUri()), Map.of());
			zipFsTargetPath = fileSystem.getPath("/" + archive.targetName);
		}

		@TearDown(Level.Trial)
		public void tearDown() throws Exception {
			fileSystem.close();
			zipFile.close();
			zipView.close();
			archive.delete();
		}
	}

	@Benchmark
	public void fabricList(OpenArchiveState state, Blackhole blackhole) {
		blackhole.consume(BenchmarkArchive.countFabricEntries(state.zipView.entries()));
	}

	@Benchmark
	public void zipFileList(OpenArchiveState state, Blackhole blackhole) {
		blackhole.consume(BenchmarkArchive.countZipFileEntries(state.zipFile));
	}

	@Benchmark
	public void nioList(OpenArchiveState state, Blackhole blackhole) throws Exception {
		blackhole.consume(BenchmarkArchive.countZipFsEntries(state.fileSystem.getPath("/")));
	}

	@Benchmark
	public void fabricLookup(OpenArchiveState state, Blackhole blackhole) {
		blackhole.consume(state.zipView.getEntry(state.archive.targetName).orElseThrow().getCompressedSize());
	}

	@Benchmark
	public void zipFileLookup(OpenArchiveState state, Blackhole blackhole) {
		blackhole.consume(state.zipFile.getEntry(state.archive.targetName).getCompressedSize());
	}

	@Benchmark
	public void nioLookup(OpenArchiveState state, Blackhole blackhole) throws Exception {
		blackhole.consume(Files.size(state.zipFsTargetPath));
	}

	@Benchmark
	public void fabricRead(OpenArchiveState state, Blackhole blackhole) throws Exception {
		blackhole.consume(BenchmarkArchive.readAll(state.zipView.open(state.zipView.getEntry(state.archive.targetName).orElseThrow())));
	}

	@Benchmark
	public void zipFileRead(OpenArchiveState state, Blackhole blackhole) throws Exception {
		blackhole.consume(BenchmarkArchive.readAll(state.zipFile.getInputStream(state.zipFile.getEntry(state.archive.targetName))));
	}

	@Benchmark
	public void nioRead(OpenArchiveState state, Blackhole blackhole) throws Exception {
		blackhole.consume(Files.readAllBytes(state.zipFsTargetPath));
	}
}
