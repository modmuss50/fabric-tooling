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

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A reference-counted ZIP view shared by path.
 *
 * @param <T> the ZIP view type being shared.
 */
public final class ZipReference<T extends ZipView> implements AutoCloseable {
	private static final Object OPEN_VIEWS_LOCK = new Object();
	private static final Map<Path, SharedValue<ZipView>> OPEN_VIEWS = new HashMap<>();
	private static final Object OPEN_ZIPS_LOCK = new Object();
	private static final Map<Path, SharedValue<Zip>> OPEN_ZIPS = new HashMap<>();

	private final Path path;
	private final Object lock;
	private final Map<Path, SharedValue<T>> openValues;
	private final SharedValue<T> sharedValue;
	private final AtomicBoolean closed = new AtomicBoolean();

	private ZipReference(Path path, Object lock, Map<Path, SharedValue<T>> openValues, SharedValue<T> sharedValue) {
		this.path = path;
		this.lock = lock;
		this.openValues = openValues;
		this.sharedValue = sharedValue;
	}

	/**
	 * Opens a shared ZIP view for the supplied path.
	 * Repeated calls for the same normalized absolute path reuse the same underlying ZIP view until
	 * the last reference is closed.
	 *
	 * @param path the archive path.
	 * @return a reference-counted ZIP view.
	 * @throws IOException if the archive cannot be opened.
	 */
	public static ZipReference<ZipView> openView(Path path) throws IOException {
		return openShared(path, OPEN_VIEWS_LOCK, OPEN_VIEWS, ZipView::open);
	}

	/**
	 * Opens a shared mutable ZIP for the supplied path.
	 * Repeated calls for the same normalized absolute path reuse the same underlying ZIP until
	 * the last reference is closed.
	 *
	 * @param path the archive path.
	 * @return a reference-counted mutable ZIP.
	 * @throws IOException if the archive cannot be opened.
	 */
	public static ZipReference<Zip> open(Path path) throws IOException {
		return openShared(path, OPEN_ZIPS_LOCK, OPEN_ZIPS, Zip::open);
	}

	/**
	 * Creates a shared mutable ZIP for the supplied path.
	 * Repeated calls for the same normalized absolute path reuse the same underlying ZIP until
	 * the last reference is closed.
	 *
	 * @param path the archive path.
	 * @return a reference-counted mutable ZIP.
	 * @throws IOException if the archive cannot be created.
	 */
	public static ZipReference<Zip> create(Path path) throws IOException {
		return openShared(path, OPEN_ZIPS_LOCK, OPEN_ZIPS, Zip::create);
	}

	/**
	 * Returns the shared ZIP view referenced by this handle.
	 *
	 * @return the shared ZIP view referenced by this handle.
	 */
	public T get() {
		ensureOpen();
		return sharedValue.value;
	}

	/**
	 * Releases this reference and closes the shared ZIP view when the last reference is closed.
	 *
	 * @throws IOException if closing the shared ZIP view fails.
	 */
	@Override
	public void close() throws IOException {
		if (!closed.compareAndSet(false, true)) {
			return;
		}

		T valueToClose = null;

		synchronized (lock) {
			int remaining = sharedValue.release();

			if (remaining == 0) {
				openValues.remove(path, sharedValue);
				valueToClose = sharedValue.value;
			}
		}

		if (valueToClose != null) {
			valueToClose.close();
		}
	}

	private void ensureOpen() {
		if (closed.get()) {
			throw new IllegalStateException("ZIP reference is closed");
		}
	}

	private static Path normalizePath(Path path) {
		return Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
	}

	private static <T extends ZipView> ZipReference<T> openShared(Path path, Object lock, Map<Path, SharedValue<T>> openValues, ZipOpener<T> opener) throws IOException {
		Path normalizedPath = normalizePath(path);

		synchronized (lock) {
			SharedValue<T> sharedValue = openValues.get(normalizedPath);

			if (sharedValue != null) {
				sharedValue.retain();
				return new ZipReference<>(normalizedPath, lock, openValues, sharedValue);
			}

			T value = opener.open(normalizedPath);
			SharedValue<T> newSharedValue = new SharedValue<>(value);
			openValues.put(normalizedPath, newSharedValue);
			return new ZipReference<>(normalizedPath, lock, openValues, newSharedValue);
		}
	}

	private static class SharedValue<T extends ZipView> {
		private final T value;
		private int referenceCount = 1;

		private SharedValue(T value) {
			this.value = Objects.requireNonNull(value, "value");
		}

		void retain() {
			if (referenceCount == 0) {
				throw new IllegalStateException("ZIP reference is closed");
			}

			referenceCount++;
		}

		int release() {
			referenceCount--;

			if (referenceCount < 0) {
				referenceCount++;
				throw new IllegalStateException("ZIP reference is closed");
			}

			return referenceCount;
		}
	}

	@FunctionalInterface
	private interface ZipOpener<T extends ZipView> {
		T open(Path path) throws IOException;
	}
}
