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

package net.fabricmc.zip.impl;

import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.fabricmc.zip.api.CompressionMethod;
import net.fabricmc.zip.api.ZipEntryView;

final class MutableZipSnapshot {
	private final List<ZipEntryView> entries;
	private final Map<String, ZipEntryView> entriesByName;

	private MutableZipSnapshot(List<ZipEntryView> entries, Map<String, ZipEntryView> entriesByName) {
		this.entries = entries;
		this.entriesByName = entriesByName;
	}

	static MutableZipSnapshot create(ZipImpl archive, Collection<MutableZipEntry> entryStates) {
		List<ZipEntryView> entries = new ArrayList<ZipEntryView>(entryStates.size());
		Map<String, ZipEntryView> entriesByName = new LinkedHashMap<String, ZipEntryView>();

		for (MutableZipEntry entryState : entryStates) {
			MutableZipSnapshotEntry entryView = new MutableZipSnapshotEntry(archive, entryState);
			entries.add(entryView);
			entriesByName.put(entryState.name, entryView);
		}

		return new MutableZipSnapshot(Collections.unmodifiableList(entries), Collections.unmodifiableMap(entriesByName));
	}

	List<ZipEntryView> entries() {
		return entries;
	}

	Map<String, ZipEntryView> entriesByName() {
		return entriesByName;
	}

	static final class MutableZipSnapshotEntry implements ZipEntryView {
		private final ZipImpl archive;
		private final MutableZipEntry state;

		MutableZipSnapshotEntry(ZipImpl archive, MutableZipEntry state) {
			this.archive = archive;
			this.state = state;
		}

		ZipImpl archive() {
			return archive;
		}

		MutableZipEntry state() {
			return state;
		}

		@Override
		public String getName() {
			return state.name;
		}

		@Override
		public @Nullable String getComment() {
			return state.comment;
		}

		@Override
		public CompressionMethod getMethod() {
			return state.method;
		}

		@Override
		public int getFlags() {
			return state.flags;
		}

		@Override
		public long getCrc32() {
			return state.crc32;
		}

		@Override
		public long getCompressedSize() {
			return state.compressedSize;
		}

		@Override
		public long getUncompressedSize() {
			return state.uncompressedSize;
		}

		@Override
		public long getLocalHeaderOffset() {
			return state.localHeaderOffset;
		}

		@Override
		public long getCentralDirectoryOffset() {
			return -1L;
		}

		@Override
		public boolean isDirectory() {
			return state.directory;
		}

		@Override
		public @Nullable FileTime getLastModifiedTime() {
			return state.lastModifiedTime;
		}

		@Override
		public @Nullable FileTime getLastAccessTime() {
			return state.lastAccessTime;
		}

		@Override
		public @Nullable FileTime getCreationTime() {
			return state.creationTime;
		}
	}
}
