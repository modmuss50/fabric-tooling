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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import net.fabricmc.zip.api.MalformedZipException;
import net.fabricmc.zip.api.UnsupportedZipFeatureException;
import net.fabricmc.zip.api.ZipEntryView;
import net.fabricmc.zip.api.ZipView;

public class ZipViewTest {
	@Test
	void readsEmptyArchive() throws Exception {
		byte[] zip = new TestZipBuilder().build();

		try (ZipView view = ZipView.open(new InMemoryZipByteSource(zip))) {
			assertTrue(view.entries().isEmpty());
			assertFalse(view.contains("missing"));
		}
	}

	@Test
	void readsStoredAndDeflatedEntries() throws Exception {
		TestZipBuilder builder = new TestZipBuilder().comment("archive-comment");
		builder.addStored("folder/", new byte[0]).comment("dir");
		builder.addStored("folder/raw.bin", new byte[] {1, 2, 3, 4}).comment("raw-comment");
		builder.addDeflated("folder/text.txt", "hello zip".getBytes()).comment("text-comment");
		byte[] zip = builder.build();

		try (ZipView view = ZipView.open(new InMemoryZipByteSource(zip))) {
			assertEquals(3, view.entries().size());
			ZipEntryView rawEntry = view.getEntry("folder/raw.bin").orElseThrow();
			ZipEntryView textEntry = view.getEntry("folder/text.txt").orElseThrow();
			ZipEntryView dirEntry = view.getEntry("folder/").orElseThrow();

			assertTrue(dirEntry.isDirectory());
			assertEquals("raw-comment", rawEntry.getComment());
			assertEquals("text-comment", textEntry.getComment());
			assertNotNull(textEntry.getLastModifiedTime());
			assertNotNull(textEntry.getLastAccessTime());
			assertNotNull(textEntry.getCreationTime());

			assertArrayEquals(new byte[] {1, 2, 3, 4}, readAllBytes(view.openRaw(rawEntry)));
			assertArrayEquals(new byte[] {1, 2, 3, 4}, readAllBytes(view.open(rawEntry)));
			assertArrayEquals("hello zip".getBytes(), readAllBytes(view.open(textEntry)));
			assertTrue(view.contains("folder/text.txt"));
			assertFalse(view.getEntry("missing.txt").isPresent());
		}
	}

	@Test
	void readsZip64Archive() throws Exception {
		TestZipBuilder builder = new TestZipBuilder().forceZip64();
		builder.addDeflated("large.txt", "zip64 data".getBytes()).forceZip64();
		byte[] zip = builder.build();

		try (ZipView view = ZipView.open(new InMemoryZipByteSource(zip))) {
			ZipEntryView entry = view.getEntry("large.txt").orElseThrow();
			assertEquals("zip64 data", new String(readAllBytes(view.open(entry))));
			assertTrue(entry.getCentralDirectoryOffset() >= 0);
			assertTrue(entry.getLocalHeaderOffset() >= 0);
		}
	}

	@Test
	void readsEntryWithDataDescriptor() throws Exception {
		TestZipBuilder builder = new TestZipBuilder();
		builder.addDeflated("descriptor.txt", "descriptor".getBytes()).dataDescriptor();
		byte[] zip = builder.build();

		try (ZipView view = ZipView.open(new InMemoryZipByteSource(zip))) {
			ZipEntryView entry = view.getEntry("descriptor.txt").orElseThrow();
			assertEquals("descriptor", new String(readAllBytes(view.open(entry))));
			assertTrue((entry.getFlags() & (1 << 3)) != 0);
		}
	}

	@Test
	void decodesUtf8AndLegacyNames() throws Exception {
		TestZipBuilder builder = new TestZipBuilder();
		builder.addStored("unicodé.txt", "utf8".getBytes());
		builder.addStored("café.txt", "legacy".getBytes()).utf8(false);
		byte[] zip = builder.build();

		try (ZipView view = ZipView.open(new InMemoryZipByteSource(zip))) {
			assertTrue(view.contains("unicodé.txt"));
			assertTrue(view.contains("café.txt"));
			assertEquals("legacy", new String(readAllBytes(view.open(view.getEntry("café.txt").orElseThrow())), Charset.defaultCharset()));
		}
	}

	@Test
	void preservesDuplicateNamesAndReturnsFirstMatch() throws Exception {
		TestZipBuilder builder = new TestZipBuilder();
		builder.addStored("duplicate.txt", "first".getBytes());
		builder.addStored("duplicate.txt", "second".getBytes());
		byte[] zip = builder.build();

		try (ZipView view = ZipView.open(new InMemoryZipByteSource(zip))) {
			assertEquals(2, view.entries().size());
			assertEquals("first", new String(readAllBytes(view.open(view.getEntry("duplicate.txt").orElseThrow()))));
		}
	}

	@Test
	void readsZeroLengthEntries() throws Exception {
		TestZipBuilder builder = new TestZipBuilder();
		builder.addStored("empty.txt", new byte[0]);
		byte[] zip = builder.build();

		try (ZipView view = ZipView.open(new InMemoryZipByteSource(zip))) {
			assertArrayEquals(new byte[0], readAllBytes(view.open(view.getEntry("empty.txt").orElseThrow())));
		}
	}

	@Test
	void closingArchiveClosesUnderlyingSourceAndOpenStreamsStopWorking() throws Exception {
		TestZipBuilder builder = new TestZipBuilder();
		builder.addStored("close.txt", "close".getBytes());
		byte[] zip = builder.build();
		InMemoryZipByteSource source = new InMemoryZipByteSource(zip);
		ZipView view = ZipView.open(source);
		InputStream stream = view.openRaw(view.getEntry("close.txt").orElseThrow());
		view.close();

		assertTrue(source.isClosed());
		assertThrows(IllegalStateException.class, () -> view.entries().size());
		assertThrows(IllegalStateException.class, () -> stream.read());
	}

	@Test
	void rejectsEncryptedEntries() throws Exception {
		TestZipBuilder builder = new TestZipBuilder();
		builder.addStored("secret.txt", "secret".getBytes()).flags(1);
		UnsupportedZipFeatureException exception = assertThrows(UnsupportedZipFeatureException.class, () -> ZipView.open(new InMemoryZipByteSource(builder.build())));
		assertThat(exception).hasMessageContaining("Encrypted");
	}

	@Test
	void rejectsUnsupportedCompressionMethod() throws Exception {
		TestZipBuilder builder = new TestZipBuilder();
		builder.addStored("weird.txt", "weird".getBytes()).flags(0);
		byte[] zip = builder.build();
		int localOffset = TestZipBuilder.findLastSignature(zip, 0x04034b50);
		int centralOffset = TestZipBuilder.findLastSignature(zip, 0x02014b50);
		zip[localOffset + 8] = 99;
		zip[centralOffset + 10] = 99;

		UnsupportedZipFeatureException exception = assertThrows(UnsupportedZipFeatureException.class, () -> ZipView.open(new InMemoryZipByteSource(zip)));
		assertThat(exception).hasMessageContaining("Unsupported compression method");
	}

	@Test
	void rejectsSplitArchives() throws Exception {
		TestZipBuilder builder = new TestZipBuilder().diskNumbers(1, 0);
		builder.addStored("split.txt", "split".getBytes());
		UnsupportedZipFeatureException exception = assertThrows(UnsupportedZipFeatureException.class, () -> ZipView.open(new InMemoryZipByteSource(builder.build())));
		assertThat(exception).hasMessageContaining("Multi-disk");
	}

	@Test
	void rejectsSplitEntries() throws Exception {
		TestZipBuilder builder = new TestZipBuilder();
		builder.addStored("split-entry.txt", "split".getBytes()).diskNumberStart(1);
		UnsupportedZipFeatureException exception = assertThrows(UnsupportedZipFeatureException.class, () -> ZipView.open(new InMemoryZipByteSource(builder.build())));
		assertThat(exception).hasMessageContaining("Multi-disk ZIP entries");
	}

	@Test
	void failsForTruncatedEndOfCentralDirectory() throws Exception {
		TestZipBuilder builder = new TestZipBuilder();
		builder.addStored("truncated.txt", "x".getBytes());
		byte[] zip = builder.build();
		byte[] truncated = java.util.Arrays.copyOf(zip, zip.length - 5);
		assertThrows(MalformedZipException.class, () -> ZipView.open(new InMemoryZipByteSource(truncated)));
	}

	@Test
	void failsForInvalidCentralDirectoryHeader() throws Exception {
		TestZipBuilder builder = new TestZipBuilder();
		builder.addStored("entry.txt", "x".getBytes());
		byte[] zip = builder.build();
		int centralOffset = TestZipBuilder.findLastSignature(zip, 0x02014b50);
		TestZipBuilder.writeInt(zip, centralOffset, 0x11111111);
		assertThrows(MalformedZipException.class, () -> ZipView.open(new InMemoryZipByteSource(zip)));
	}

	@Test
	void failsForInvalidLocalHeaderWhenReadingEntry() throws Exception {
		TestZipBuilder builder = new TestZipBuilder();
		builder.addStored("entry.txt", "x".getBytes());
		byte[] zip = builder.build();
		int localOffset = TestZipBuilder.findLastSignature(zip, 0x04034b50);
		TestZipBuilder.writeInt(zip, localOffset, 0x22222222);

		try (ZipView view = ZipView.open(new InMemoryZipByteSource(zip))) {
			MalformedZipException exception = assertThrows(MalformedZipException.class, () -> view.openRaw(view.getEntry("entry.txt").orElseThrow()));
			assertThat(exception).hasMessageContaining("Invalid local file header");
		}
	}

	@Test
	void canBeOpenedFromPath() throws Exception {
		TestZipBuilder builder = new TestZipBuilder();
		builder.addStored("path.txt", "path".getBytes());
		byte[] zip = builder.build();
		java.nio.file.Path path = java.nio.file.Files.createTempFile("fabric-zip-", ".zip");
		java.nio.file.Files.write(path, zip);

		try (ZipView view = ZipView.open(path)) {
			assertEquals("path", new String(readAllBytes(view.open(view.getEntry("path.txt").orElseThrow()))));
		} finally {
			java.nio.file.Files.deleteIfExists(path);
		}
	}

	@Test
	void pathBackedViewMaterializesEntriesLazilyAndKeepsDuplicateSemantics() throws Exception {
		TestZipBuilder builder = new TestZipBuilder();
		builder.addStored("duplicate.txt", "first".getBytes());
		builder.addStored("other.txt", "other".getBytes());
		builder.addStored("duplicate.txt", "second".getBytes());
		java.nio.file.Path path = java.nio.file.Files.createTempFile("fabric-zip-lazy-", ".zip");
		java.nio.file.Files.write(path, builder.build());

		try (ZipView view = ZipView.open(path)) {
			assertEquals("first", new String(readAllBytes(view.open(view.getEntry("duplicate.txt").orElseThrow()))));
			assertTrue(view.contains("other.txt"));
			assertEquals(3, view.entries().size());
			assertEquals("other", new String(readAllBytes(view.open(view.getEntry("other.txt").orElseThrow()))));
		} finally {
			java.nio.file.Files.deleteIfExists(path);
		}
	}

	@Test
	void rejectsEntriesFromAnotherArchive() throws Exception {
		TestZipBuilder firstBuilder = new TestZipBuilder();
		firstBuilder.addStored("entry.txt", "one".getBytes());
		byte[] firstZip = firstBuilder.build();
		TestZipBuilder secondBuilder = new TestZipBuilder();
		secondBuilder.addStored("entry.txt", "two".getBytes());
		byte[] secondZip = secondBuilder.build();

		try (ZipView first = ZipView.open(new InMemoryZipByteSource(firstZip));
				ZipView second = ZipView.open(new InMemoryZipByteSource(secondZip))) {
			IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> first.open(second.getEntry("entry.txt").orElseThrow()));
			assertInstanceOf(IllegalArgumentException.class, exception);
		}
	}

	@Test
	void exposesConfiguredTimestamps() throws Exception {
		Instant modified = Instant.parse("2023-08-09T10:11:12Z");
		Instant accessed = Instant.parse("2023-08-09T10:12:12Z");
		Instant created = Instant.parse("2023-08-09T10:13:12Z");
		TestZipBuilder builder = new TestZipBuilder();
		builder.addStored("times.txt", "times".getBytes()).timestamps(modified, accessed, created);

		try (ZipView view = ZipView.open(new InMemoryZipByteSource(builder.build()))) {
			ZipEntryView entry = view.getEntry("times.txt").orElseThrow();
			assertEquals(modified, entry.getLastModifiedTime().toInstant());
			assertEquals(accessed, entry.getLastAccessTime().toInstant());
			assertEquals(created, entry.getCreationTime().toInstant());
		}
	}

	private static byte[] readAllBytes(InputStream inputStream) throws IOException {
		try (inputStream) {
			return inputStream.readAllBytes();
		}
	}
}
