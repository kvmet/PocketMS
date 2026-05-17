# NOTICE

PocketMS  
Copyright 2026 the PocketMS contributors.

This product is licensed under the Apache License, Version 2.0 (the
"License"); see [LICENSE](LICENSE) for the full text.

---

## Upstream project

PocketMS is a fork of **MonoSketch** by Tuan Chau and contributors,
licensed under the Apache License 2.0. Significant portions of this
codebase originate from MonoSketch unchanged.

- Source: <https://github.com/tuanchauict/MonoSketch>
- License: Apache License 2.0

## Bundled third-party software

The Docker image produced by this repository's Dockerfile bundles the
following third-party software:

### PocketBase

[PocketBase](https://pocketbase.io) is included as an unmodified Linux
binary downloaded at image build time from the upstream GitHub release.
It is licensed under the MIT License. A copy of the license is fetched
during the Docker build and placed at `/pb/licenses/POCKETBASE-LICENSE.md`
inside the runtime image. The pinned version is declared by the
`PB_VERSION` build argument in the Dockerfile.

- Source: <https://github.com/pocketbase/pocketbase>
- License: MIT

No PocketBase source code is modified or redistributed by this
repository.
