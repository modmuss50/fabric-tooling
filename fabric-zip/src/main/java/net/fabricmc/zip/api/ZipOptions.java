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

import java.util.Objects;
import java.util.function.Consumer;

import org.jetbrains.annotations.ApiStatus;

/**
 * Options for mutable ZIP creation and opening.
 */
@ApiStatus.NonExtendable
public final class ZipOptions {
	private final boolean reproducible;
	private final CompressionMethod defaultCompressionMethod;
	private final CompressionCodec compressionCodec;

	private ZipOptions(Builder builder) {
		this.reproducible = builder.reproducible;
		this.defaultCompressionMethod = builder.defaultCompressionMethod;
		this.compressionCodec = builder.compressionCodec;
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
	 * Returns the default compression method used for new entries.
	 *
	 * @return the default compression method used for new entries.
	 */
	public CompressionMethod defaultCompressionMethod() {
		return defaultCompressionMethod;
	}

	/**
	 * Returns the codec used to compress and inflate entry data.
	 *
	 * @return the codec used to compress and inflate entry data.
	 */
	public CompressionCodec compressionCodec() {
		return compressionCodec;
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
		private CompressionMethod defaultCompressionMethod = CompressionMethod.DEFLATED;
		private CompressionCodec compressionCodec = CompressionCodec.defaultCodec();

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
		 * Sets the codec used to compress and inflate entry data.
		 *
		 * @param compressionCodec the compression codec.
		 * @return this builder.
		 */
		public Builder compressionCodec(CompressionCodec compressionCodec) {
			this.compressionCodec = Objects.requireNonNull(compressionCodec, "compressionCodec");
			return this;
		}

		/**
		 * Validates and creates the configured ZIP options.
		 *
		 * @return the configured ZIP options.
		 */
		public ZipOptions build() {
			if (defaultCompressionMethod != CompressionMethod.STORED && defaultCompressionMethod != CompressionMethod.DEFLATED) {
				throw new IllegalArgumentException("Unsupported default compression method: " + defaultCompressionMethod);
			}

			Objects.requireNonNull(compressionCodec, "compressionCodec");

			return new ZipOptions(this);
		}
	}
}
