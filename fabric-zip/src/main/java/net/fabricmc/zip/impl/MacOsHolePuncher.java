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
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

enum MacOsHolePuncher implements HolePuncher {
	INSTANCE;

	private static final long MINIMUM_HOLE_LENGTH = 4096L;
	private static final int OPEN_READ_WRITE = 2;
	private static final int F_PUNCHHOLE = 99;
	private static final MemoryLayout FPUNCHHOLE_LAYOUT = MemoryLayout.structLayout(
			java.lang.foreign.ValueLayout.JAVA_INT.withName("fp_flags"),
			java.lang.foreign.ValueLayout.JAVA_INT.withName("reserved"),
			java.lang.foreign.ValueLayout.JAVA_LONG.withName("fp_offset"),
			java.lang.foreign.ValueLayout.JAVA_LONG.withName("fp_length")
	);
	private static final MethodHandle OPEN = downcall("open", FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT, java.lang.foreign.ValueLayout.ADDRESS, java.lang.foreign.ValueLayout.JAVA_INT));
	private static final MethodHandle FCNTL = downcall("fcntl", FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT, java.lang.foreign.ValueLayout.JAVA_INT, java.lang.foreign.ValueLayout.JAVA_INT, java.lang.foreign.ValueLayout.ADDRESS));
	private static final MethodHandle CLOSE = downcall("close", FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT, java.lang.foreign.ValueLayout.JAVA_INT));

	@Override
	public long minimumHoleLength() {
		return MINIMUM_HOLE_LENGTH;
	}

	@Override
	public void punch(Path path, long offset, long length) throws IOException {
		if (length <= 0) {
			return;
		}

		try (Arena arena = Arena.ofConfined()) {
			int fd = (int) OPEN.invokeExact(arena.allocateFrom(path.toString()), OPEN_READ_WRITE);

			if (fd < 0) {
				throw new IOException("Failed to open ZIP file for sparse hole punching: " + path);
			}

			try {
				MemorySegment hole = arena.allocate(FPUNCHHOLE_LAYOUT);
				hole.set(java.lang.foreign.ValueLayout.JAVA_INT, 0, 0);
				hole.set(java.lang.foreign.ValueLayout.JAVA_INT, Integer.BYTES, 0);
				hole.set(java.lang.foreign.ValueLayout.JAVA_LONG, 8, offset);
				hole.set(java.lang.foreign.ValueLayout.JAVA_LONG, 16, length);
				int result = (int) FCNTL.invokeExact(fd, F_PUNCHHOLE, hole);

				if (result != 0) {
					throw new IOException("Failed to punch sparse hole for ZIP file: " + path);
				}
			} catch (Throwable throwable) {
				if (throwable instanceof IOException ioException) {
					throw ioException;
				}

				throw new IOException("Failed to punch sparse hole for ZIP file: " + path, throwable);
			} finally {
				try {
					@SuppressWarnings("unused")
					int ignored = (int) CLOSE.invokeExact(fd);
				} catch (Throwable throwable) {
					throw new IOException("Failed to close sparse ZIP descriptor for: " + path, throwable);
				}
			}
		} catch (Throwable throwable) {
			if (throwable instanceof IOException ioException) {
				throw ioException;
			}

			throw new IOException("Failed to punch sparse hole for ZIP file: " + path, throwable);
		}
	}

	private static MethodHandle downcall(String symbolName, FunctionDescriptor functionDescriptor) {
		SymbolLookup lookup = Linker.nativeLinker().defaultLookup();
		MemorySegment symbol = lookup.find(symbolName).orElseThrow(() -> new IllegalStateException("Missing native symbol: " + symbolName));
		return Linker.nativeLinker().downcallHandle(symbol, functionDescriptor);
	}
}
