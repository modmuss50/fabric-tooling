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

	/**
	 * Returns whether reproducible ZIP output is enabled.
	 *
	 * @return whether reproducible ZIP output is enabled.
	 */
	public boolean reproducible() {
		return reproducible;
	}

	/**
	 * Returns whether sparse mode is enabled.
	 *
	 * @return whether sparse mode is enabled.
	 */
	public boolean sparse() {
		return sparse;
	}

	/**
	 * Returns when successful mutations are written to disk.
	 *
	 * @return when successful mutations are written to disk.
	 */
	public WriteMode writeMode() {
		return writeMode;
	}

	/**
	 * Returns the default compression method used for new entries.
	 *
	 * @return the default compression method used for new entries.
	 */
	public CompressionMethod defaultCompressionMethod() {
		return defaultCompressionMethod;
	}

	/**
	 * Builds ZIP options from the supplied builder callback.
	 *
	 * @param consumer configures the builder.
	 * @return the configured options.
	 */
	public static ZipOptions configure(Consumer<Builder> consumer) {
		Builder builder = new Builder();
		Objects.requireNonNull(consumer, "consumer").accept(builder);
		return builder.build();
	}

	/**
	 * A builder for {@link ZipOptions}.
	 */
	public static final class Builder {
		private boolean reproducible;
		private boolean sparse;
		private WriteMode writeMode = WriteMode.ON_CLOSE;
		private CompressionMethod defaultCompressionMethod = CompressionMethod.DEFLATED;

		/**
		 * Creates a builder with default ZIP options.
		 */
		public Builder() {
		}

		/**
		 * Enables or disables reproducible ZIP output.
		 *
		 * @param reproducible whether reproducible output is enabled.
		 * @return this builder.
		 */
		public Builder reproducible(boolean reproducible) {
			this.reproducible = reproducible;
			return this;
		}

		/**
		 * Enables or disables sparse mode.
		 *
		 * @param sparse whether sparse mode is enabled.
		 * @return this builder.
		 */
		public Builder sparse(boolean sparse) {
			this.sparse = sparse;
			return this;
		}

		/**
		 * Sets when successful mutations are written to disk.
		 *
		 * @param writeMode the write mode to use.
		 * @return this builder.
		 */
		public Builder writeMode(WriteMode writeMode) {
			this.writeMode = Objects.requireNonNull(writeMode, "writeMode");
			return this;
		}

		/**
		 * Sets the default compression method used for newly added entries.
		 *
		 * @param defaultCompressionMethod the default compression method.
		 * @return this builder.
		 */
		public Builder defaultCompressionMethod(CompressionMethod defaultCompressionMethod) {
			this.defaultCompressionMethod = Objects.requireNonNull(defaultCompressionMethod, "defaultCompressionMethod");
			return this;
		}

		/**
		 * Validates and creates the configured ZIP options.
		 *
		 * @return the configured ZIP options.
		 */
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
