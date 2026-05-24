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
public class ZipOpenBenchmark {
	@State(Scope.Benchmark)
	public static class ArchiveState {
		@Param({"SMALL_ENTRIES", "LARGE_ENTRY", "MANY_ENTRIES"})
		public BenchmarkArchive.ArchiveShape shape;

		private BenchmarkArchive archive;
		private URI zipUri;

		@Setup(Level.Trial)
		public void setUp() throws Exception {
			archive = BenchmarkArchive.create(shape);
			zipUri = URI.create("jar:" + archive.archivePath.toUri());
		}

		@TearDown(Level.Trial)
		public void tearDown() throws Exception {
			archive.delete();
		}
	}

	@Benchmark
	public void fabricOpenAndList(ArchiveState state, Blackhole blackhole) throws Exception {
		try (ZipView zipView = ZipView.open(state.archive.archivePath)) {
			blackhole.consume(BenchmarkArchive.countFabricEntries(zipView.entries()));
		}
	}

	@Benchmark
	public void zipFileOpenAndList(ArchiveState state, Blackhole blackhole) throws Exception {
		try (ZipFile zipFile = new ZipFile(state.archive.archivePath.toFile())) {
			blackhole.consume(BenchmarkArchive.countZipFileEntries(zipFile));
		}
	}

	@Benchmark
	public void nioOpenAndList(ArchiveState state, Blackhole blackhole) throws Exception {
		try (FileSystem fileSystem = FileSystems.newFileSystem(state.zipUri, Map.of())) {
			blackhole.consume(BenchmarkArchive.countZipFsEntries(fileSystem.getPath("/")));
		}
	}

	@Benchmark
	public void fabricOpenAndLookup(ArchiveState state, Blackhole blackhole) throws Exception {
		try (ZipView zipView = ZipView.open(state.archive.archivePath)) {
			blackhole.consume(zipView.getEntry(state.archive.targetName).orElseThrow().getCompressedSize());
		}
	}

	@Benchmark
	public void zipFileOpenAndLookup(ArchiveState state, Blackhole blackhole) throws Exception {
		try (ZipFile zipFile = new ZipFile(state.archive.archivePath.toFile())) {
			blackhole.consume(zipFile.getEntry(state.archive.targetName).getCompressedSize());
		}
	}

	@Benchmark
	public void nioOpenAndLookup(ArchiveState state, Blackhole blackhole) throws Exception {
		try (FileSystem fileSystem = FileSystems.newFileSystem(state.zipUri, Map.of())) {
			Path path = fileSystem.getPath("/" + state.archive.targetName);
			blackhole.consume(Files.size(path));
		}
	}

	@Benchmark
	public void fabricOpenAndRead(ArchiveState state, Blackhole blackhole) throws Exception {
		try (ZipView zipView = ZipView.open(state.archive.archivePath)) {
			blackhole.consume(BenchmarkArchive.readAll(zipView.open(zipView.getEntry(state.archive.targetName).orElseThrow())));
		}
	}

	@Benchmark
	public void zipFileOpenAndRead(ArchiveState state, Blackhole blackhole) throws Exception {
		try (ZipFile zipFile = new ZipFile(state.archive.archivePath.toFile())) {
			blackhole.consume(BenchmarkArchive.readAll(zipFile.getInputStream(zipFile.getEntry(state.archive.targetName))));
		}
	}

	@Benchmark
	public void nioOpenAndRead(ArchiveState state, Blackhole blackhole) throws Exception {
		try (FileSystem fileSystem = FileSystems.newFileSystem(state.zipUri, Map.of())) {
			blackhole.consume(Files.readAllBytes(fileSystem.getPath("/" + state.archive.targetName)));
		}
	}
}
