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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Tracks reusable sparse regions in a mutable ZIP file.
 *
 * <p>Sparse mode leaves removed local records and replaced trailing metadata in place on disk, then
 * punches holes in those dead byte ranges when the platform supports it. This free-list mirrors that
 * layout in memory so new local records can be placed back into an existing gap before the archive grows
 * at EOF. The list is rebuilt from the persisted archive layout on open, and merges adjacent free ranges
 * so reuse still works after multiple add/remove cycles and across reopen.
 */
final class SparseFreeList {
	private final ArrayList<Range> ranges;

	private SparseFreeList(ArrayList<Range> ranges) {
		this.ranges = ranges;
	}

	static SparseFreeList empty() {
		return new SparseFreeList(new ArrayList<>());
	}

	static SparseFreeList rebuild(long fileSize, Collection<MutableZipEntry> entries, long trailingMetadataOffset, long trailingMetadataLength) {
		ArrayList<Range> occupied = new ArrayList<>(entries.size() + 1);

		for (MutableZipEntry entry : entries) {
			if (entry.localHeaderOffset >= 0 && entry.localRecordLength > 0) {
				occupied.add(new Range(entry.localHeaderOffset, entry.localRecordLength));
			}
		}

		if (trailingMetadataLength > 0) {
			occupied.add(new Range(trailingMetadataOffset, trailingMetadataLength));
		}

		occupied.sort(Comparator.comparingLong(Range::offset));

		ArrayList<Range> free = new ArrayList<>();
		long cursor = 0L;

		for (Range range : occupied) {
			long offset = Math.max(cursor, range.offset);

			if (offset > cursor) {
				free.add(new Range(cursor, offset - cursor));
			}

			cursor = Math.max(cursor, range.end());
		}

		if (fileSize > cursor) {
			free.add(new Range(cursor, fileSize - cursor));
		}

		return new SparseFreeList(free);
	}

	SparseFreeList copy() {
		return new SparseFreeList(new ArrayList<>(ranges));
	}

	List<Range> ranges() {
		return List.copyOf(ranges);
	}

	void free(long offset, long length) {
		if (length <= 0) {
			return;
		}

		long start = offset;
		long end = offset + length;
		int insertionPoint = 0;

		while (insertionPoint < ranges.size() && ranges.get(insertionPoint).end() < start) {
			insertionPoint++;
		}

		while (insertionPoint < ranges.size() && ranges.get(insertionPoint).offset <= end) {
			Range range = ranges.remove(insertionPoint);
			start = Math.min(start, range.offset);
			end = Math.max(end, range.end());
		}

		ranges.add(insertionPoint, new Range(start, end - start));
	}

	void allocate(long offset, long length) {
		if (length <= 0) {
			return;
		}

		for (int i = 0; i < ranges.size(); i++) {
			Range range = ranges.get(i);

			if (range.offset > offset) {
				break;
			}

			if (range.offset <= offset && range.end() >= offset + length) {
				ranges.remove(i);

				if (offset > range.offset) {
					ranges.add(i++, new Range(range.offset, offset - range.offset));
				}

				long remainderOffset = offset + length;
				long remainderLength = range.end() - remainderOffset;

				if (remainderLength > 0) {
					ranges.add(i, new Range(remainderOffset, remainderLength));
				}

				return;
			}
		}

		throw new IllegalArgumentException("Range is not free: offset=" + offset + ", length=" + length);
	}

	record Range(long offset, long length) {
		Range {
			if (offset < 0) {
				throw new IllegalArgumentException("offset must be non-negative");
			}

			if (length <= 0) {
				throw new IllegalArgumentException("length must be positive");
			}
		}

		long end() {
			return offset + length;
		}
	}
}
