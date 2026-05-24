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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.zip.api.Zip;
import net.fabricmc.zip.api.ZipReference;
import net.fabricmc.zip.api.ZipView;

public class ZipReferenceTest {
	@TempDir
	Path tempDir;

	@Test
	void closesSharedViewAfterLastReference() throws Exception {
		Path path = writeZip("shared.zip", builder -> builder.addStored("shared.txt", "shared".getBytes()));
		ZipReference<ZipView> first = ZipReference.openView(path);
		ZipReference<ZipView> second = ZipReference.openView(path);

		first.close();
		assertEquals("shared", new String(readAllBytes(second.get().open(second.get().getEntry("shared.txt").orElseThrow()))));

		second.close();

		try (ZipReference<ZipView> reopened = ZipReference.openView(path)) {
			assertEquals("shared", new String(readAllBytes(reopened.get().open(reopened.get().getEntry("shared.txt").orElseThrow()))));
		}
	}

	@Test
	void repeatedOpenOfSamePathSharesUnderlyingView() throws Exception {
		Path path = writeZip("same-view.zip", builder -> builder.addStored("value.txt", "same".getBytes()));

		try (ZipReference<ZipView> first = ZipReference.openView(path);
				ZipReference<ZipView> second = ZipReference.openView(path)) {
			assertTrue(first.get() == second.get());
		}
	}

	@Test
	void closedReferenceCannotBeUsed() throws Exception {
		Path path = writeZip("closed.zip", builder -> builder.addStored("entry.txt", "value".getBytes()));
		ZipReference<ZipView> reference = ZipReference.openView(path);
		reference.close();

		assertThrows(IllegalStateException.class, reference::get);
	}

	@Test
	void supportsConcurrentReadsFromTheSamePath() throws Exception {
		Path path = writeZip("concurrent.zip", builder -> {
			builder.addDeflated("a.txt", "alpha".getBytes());
			builder.addDeflated("b.txt", "beta".getBytes());
		});

		try (ZipReference<ZipView> first = ZipReference.openView(path);
				ZipReference<ZipView> second = ZipReference.openView(path);
				var executor = Executors.newFixedThreadPool(2)) {
			Future<byte[]> firstFuture = executor.submit(readEntry(first, "a.txt"));
			Future<byte[]> secondFuture = executor.submit(readEntry(second, "b.txt"));

			assertArrayEquals("alpha".getBytes(), firstFuture.get());
			assertArrayEquals("beta".getBytes(), secondFuture.get());
		}
	}

	@Test
	void repeatedOpenOfSamePathSharesUnderlyingZip() throws Exception {
		Path path = writeZip("same-zip.zip", builder -> builder.addStored("value.txt", "same".getBytes()));

		try (ZipReference<Zip> first = ZipReference.open(path);
				ZipReference<Zip> second = ZipReference.open(path)) {
			assertTrue(first.get() == second.get());
		}
	}

	@Test
	void createdZipReferenceSharesMutableZipAndPersistsChanges() throws Exception {
		Path path = tempDir.resolve("created.zip");

		try (ZipReference<Zip> first = ZipReference.create(path);
				ZipReference<Zip> second = ZipReference.open(path)) {
			assertTrue(first.get() == second.get());
			first.get().add("created.txt", "created".getBytes());
			assertEquals("created", new String(readAllBytes(second.get().open(second.get().getEntry("created.txt").orElseThrow()))));
		}

		try (ZipView view = ZipView.open(path)) {
			assertEquals("created", new String(readAllBytes(view.open(view.getEntry("created.txt").orElseThrow()))));
		}
	}

	private Path writeZip(String fileName, ThrowingConsumer<TestZipBuilder> consumer) throws Exception {
		TestZipBuilder builder = new TestZipBuilder();
		consumer.accept(builder);
		Path path = tempDir.resolve(fileName);
		Files.write(path, builder.build());
		return path;
	}

	private static Callable<byte[]> readEntry(ZipReference<ZipView> reference, String name) {
		return () -> readAllBytes(reference.get().open(reference.get().getEntry(name).orElseThrow()));
	}

	private static byte[] readAllBytes(InputStream inputStream) throws IOException {
		try (inputStream) {
			return inputStream.readAllBytes();
		}
	}

	@FunctionalInterface
	private interface ThrowingConsumer<T> {
		void accept(T value) throws Exception;
	}
}
