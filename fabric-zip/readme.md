# fabric-zip

`fabric-zip` is a low-level ZIP library intended for Fabric tooling use cases where the JDK ZIP APIs are too high level.
The current implementation supports both read-only access and mutable local-file ZIP editing, including ZIP64 archives.

## API design

- `ZipView` is the read-only ZIP contract and includes static factory methods for opening ZIPs from a `Path` or `ZipByteSource`.
- `Zip` extends `ZipView` and is the mutable ZIP contract for local filesystem archives.
  It provides `create(Path)` and `open(Path)` factory methods, plus overloads that accept a `ZipOptions.Builder` customizer.
- `Zip` currently supports:
  - `add(String, byte[])`
  - `add(String, InputStream)`
  - `remove(String)`
  - `copy(ZipView, String)` to copy raw compressed entry data without recompressing
- `ZipOptions` controls mutable ZIP behavior:
  - `reproducible`
  - `sparse`
  - `writeMode`
  - `defaultCompressionMethod`
- `WriteMode` controls when mutations are persisted:
  - `ON_CLOSE` buffers changes until the ZIP is closed
  - `IMMEDIATE` writes changes back to disk after each mutation
- `ZipEntryView` exposes entry metadata such as name, sizes, offsets, CRC, flags, timestamps, and `CompressionMethod`.
- `ZipByteSource` is the random-access storage abstraction used by the implementation.
  The default implementation is path-backed, but the design leaves room for other backends later.
- `CompressionCodec` abstracts decompression so the implementation is not tied permanently to the JDK inflater.
- `ZipReference.openView(Path)` provides shared path-based access to an already-open ZIP view and reference-counts its lifetime.

## Thread safety

All methods on `ZipView` and `Zip` must be thread safe.
Callers should be able to use a single opened ZIP from multiple threads at the same time, including when the ZIP is accessed through `ZipReference`.
For mutable ZIPs, reads must continue to work correctly while mutations are happening on the same `Zip` instance.

## Current scope

- Read-only archive access
- Mutable local-file ZIP archives
- ZIP64 support
- Stored and deflated entry data
- Raw compressed entry reads and inflated entry reads
- Add, remove, and raw-copy entry mutation operations
- Reproducible output mode with fixed timestamps and stable entry ordering
- Immediate-write and on-close-write persistence modes
- Shared path-based ZIP references
- macOS sparse-file support for mutable ZIPs

## Internal structure

Public API types live in `net.fabricmc.zip.api`.
Implementation details, parsing logic, writer logic, backend code, snapshot state, and platform-specific sparse helpers live in `net.fabricmc.zip.impl`.

## Notes

- Mutable ZIPs currently assume local filesystem storage and take an exclusive file lock while open for mutation.
- Sparse mode is currently macOS-only and routed through an OS-specific hole-punch implementation.
- `ZipReference` remains read-only; it is not used for mutable ZIP sharing.
