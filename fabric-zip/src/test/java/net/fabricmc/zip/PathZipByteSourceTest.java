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

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.zip.api.ZipByteSource;

public class PathZipByteSourceTest {
	@TempDir
	Path tempDir;

	@Test
	void readsRangesFromPath() throws Exception {
		Path path = tempDir.resolve("range.bin");
		byte[] data = "0123456789".getBytes();
		Files.write(path, data);

		try (ZipByteSource source = ZipByteSource.of(path)) {
			assertEquals(data.length, source.size());

			byte[] buffer = new byte[4];
			source.readFully(3, buffer);
			assertArrayEquals("3456".getBytes(), buffer);
		}
	}
}
