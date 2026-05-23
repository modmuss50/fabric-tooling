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

import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

import org.jetbrains.annotations.ApiStatus;

/**
 * Options for mutable ZIP creation and opening.
 */
@ApiStatus.NonExtendable
public final class ZipOptions {
	private final boolean reproducible;
	private final boolean sparse;
	private final WriteMode writeMode;
	private final CompressionMethod defaultCompressionMethod;

	private ZipOptions(Builder builder) {
		this.reproducible = builder.reproducible;
		this.sparse = builder.sparse;
		this.writeMode = builder.writeMode;
		this.defaultCompressionMethod = builder.defaultCompressionMethod;
	}

	public boolean reproducible() {
		return reproducible;
	}

	public boolean sparse() {
		return sparse;
	}

	public WriteMode writeMode() {
		return writeMode;
	}

	public CompressionMethod defaultCompressionMethod() {
		return defaultCompressionMethod;
	}

	public static ZipOptions configure(Consumer<Builder> consumer) {
		Builder builder = new Builder();
		Objects.requireNonNull(consumer, "consumer").accept(builder);
		return builder.build();
	}

	public static final class Builder {
		private boolean reproducible;
		private boolean sparse;
		private WriteMode writeMode = WriteMode.ON_CLOSE;
		private CompressionMethod defaultCompressionMethod = CompressionMethod.DEFLATED;

		public Builder reproducible(boolean reproducible) {
			this.reproducible = reproducible;
			return this;
		}

		public Builder sparse(boolean sparse) {
			this.sparse = sparse;
			return this;
		}

		public Builder writeMode(WriteMode writeMode) {
			this.writeMode = Objects.requireNonNull(writeMode, "writeMode");
			return this;
		}

		public Builder defaultCompressionMethod(CompressionMethod defaultCompressionMethod) {
			this.defaultCompressionMethod = Objects.requireNonNull(defaultCompressionMethod, "defaultCompressionMethod");
			return this;
		}

		public ZipOptions build() {
			if (reproducible && sparse) {
				throw new IllegalArgumentException("Reproducible ZIPs are not compatible with sparse mode");
			}

			if (sparse && writeMode != WriteMode.IMMEDIATE) {
				throw new IllegalArgumentException("Sparse mode requires writeMode(IMMEDIATE)");
			}

			if (defaultCompressionMethod != CompressionMethod.STORED && defaultCompressionMethod != CompressionMethod.DEFLATED) {
				throw new IllegalArgumentException("Unsupported default compression method: " + defaultCompressionMethod);
			}

			if (sparse && !isMacOs()) {
				throw new UnsupportedOperationException("Sparse ZIP mode is currently supported on macOS only");
			}

			return new ZipOptions(this);
		}

		private static boolean isMacOs() {
			return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
		}
	}
}
