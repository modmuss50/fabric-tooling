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

package net.fabricmc.zip.api;

/**
 * ZIP compression methods supported by this library.
 */
public enum CompressionMethod {
	/**
	 * Stores entry data without compression.
	 */
	STORED(0),
	/**
	 * Stores entry data using the DEFLATE algorithm.
	 */
	DEFLATED(8);

	private final int code;

	CompressionMethod(int code) {
		this.code = code;
	}

	/**
	 * Returns the ZIP specification numeric code for this compression method.
	 *
	 * @return the ZIP specification numeric code for this compression method.
	 */
	public int getCode() {
		return code;
	}

	/**
	 * Resolves a compression method from its ZIP numeric code.
	 *
	 * @param code the ZIP compression method code.
	 * @return the matching compression method, or {@code null} if unsupported.
	 */
	public static CompressionMethod fromCode(int code) {
		switch (code) {
		case 0:
			return STORED;
		case 8:
			return DEFLATED;
		default:
			return null;
		}
	}
}
