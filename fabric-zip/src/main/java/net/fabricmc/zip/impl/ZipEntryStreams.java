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

import java.io.IOException;
import java.io.InputStream;

import net.fabricmc.zip.api.MalformedZipException;
import net.fabricmc.zip.api.ZipByteSource;

final class ZipEntryStreams {
	private ZipEntryStreams() {
	}

	static InputStream openRaw(ZipByteSource source, ZipEntryViewImpl entry) throws IOException {
		long dataOffset = entry.dataOffset();

		if (dataOffset < 0) {
			dataOffset = readDataOffset(source, entry);
			entry.dataOffset(dataOffset);
		}

		return new BoundedInputStream(source, dataOffset, entry.getCompressedSize());
	}

	private static long readDataOffset(ZipByteSource source, ZipEntryViewImpl entry) throws IOException {
		byte[] localHeader = new byte[ZipConstants.LOCAL_FILE_HEADER_LENGTH];
		source.readFully(entry.getLocalHeaderOffset(), localHeader);

		if (ZipParser.readInt(localHeader, 0) != ZipConstants.LOCAL_FILE_HEADER_SIGNATURE) {
			throw new MalformedZipException("Invalid local file header signature for entry: " + entry.getName());
		}

		int nameLength = ZipParser.readUnsignedShort(localHeader, 26);
		int extraLength = ZipParser.readUnsignedShort(localHeader, 28);
		return entry.getLocalHeaderOffset() + ZipConstants.LOCAL_FILE_HEADER_LENGTH + nameLength + extraLength;
	}
}
