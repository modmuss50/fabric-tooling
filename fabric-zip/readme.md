# fabric-zip

`fabric-zip` is a low-level ZIP library intended for Fabric tooling use cases where the JDK ZIP APIs are too high level.
The current implementation is read-only, supports ZIP64, and is structured so write and modification support can be added later without reshaping the whole API.

## API design

- `ZipFileView` is the read-only ZIP contract.
- `ZipView` currently defines the read-only operations and static factory methods for opening ZIPs.
- `Zip` extends `ZipFileView` and is reserved for future mutable ZIP support.
- `ZipEntryView` exposes entry metadata such as name, sizes, offsets, CRC, flags, timestamps, and `CompressionMethod`.
- `ZipByteSource` is the random-access storage abstraction used by the implementation.
  The default implementation is path-backed, but the design leaves room for other backends later.
- `CompressionCodec` abstracts decompression so the implementation is not tied permanently to the JDK inflater.
- `ZipReference.openView(Path)` provides shared path-based access to an already-open ZIP view and reference-counts its lifetime.

## Thread safety

All methods on `ZipView` and `Zip` must be thread safe.
Callers should be able to use a single opened ZIP from multiple threads at the same time, including when the ZIP is accessed through `ZipReference`.

## Current scope

- Read-only archive access
- ZIP64 support
- Stored and deflated entry data
- Raw compressed entry reads and inflated entry reads
- Shared path-based ZIP references

## Internal structure

Public API types live in `net.fabricmc.zip.api`.
Implementation details, parsing logic, and backend code live in `net.fabricmc.zip.impl`.
