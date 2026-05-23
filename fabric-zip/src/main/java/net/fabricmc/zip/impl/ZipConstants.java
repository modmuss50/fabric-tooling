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

final class ZipConstants {
	static final int EOCD_SIGNATURE = 0x06054b50;
	static final int ZIP64_EOCD_SIGNATURE = 0x06064b50;
	static final int ZIP64_LOCATOR_SIGNATURE = 0x07064b50;
	static final int CENTRAL_DIRECTORY_HEADER_SIGNATURE = 0x02014b50;
	static final int LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50;

	static final int EOCD_LENGTH = 22;
	static final int ZIP64_LOCATOR_LENGTH = 20;
	static final int ZIP64_EOCD_MIN_LENGTH = 56;
	static final int CENTRAL_DIRECTORY_HEADER_LENGTH = 46;
	static final int LOCAL_FILE_HEADER_LENGTH = 30;

	static final int GENERAL_PURPOSE_FLAG_ENCRYPTED = 1;
	static final int GENERAL_PURPOSE_FLAG_DATA_DESCRIPTOR = 1 << 3;
	static final int GENERAL_PURPOSE_FLAG_UTF8 = 1 << 11;

	static final int METHOD_STORED = 0;
	static final int METHOD_DEFLATED = 8;

	static final int ZIP64_EXTRA_FIELD_ID = 0x0001;
	static final int EXTENDED_TIMESTAMP_EXTRA_FIELD_ID = 0x5455;

	private ZipConstants() {
	}
}
