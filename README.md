# PocketMS

A self-hostable fork of [MonoSketch](https://github.com/tuanchauict/MonoSketch)
that adds authentication and server-side storage on top of the original
ASCII diagram editor. Everything ships as a single container: the
Kotlin/JS app and a [PocketBase](https://pocketbase.io) backend live in
one image and one persistent volume.

## What's different from upstream MonoSketch

MonoSketch is a fully client-side editor; it stores everything in
`localStorage` and has no concept of users. PocketMS keeps the editor
intact and wraps it with:

- **PocketBase as the application server.** Serves the static site,
  provides REST and realtime APIs, ships an admin UI, persists to
  SQLite.
- **Email/password auth.** Public signup is disabled; admins create
  accounts from the PocketBase admin UI.
- **Server-side drawings.** Drawings live in PocketBase as the source
  of truth. The local copy is a single open-draft cache for
  offline/network-interruption resilience.
- **Optimistic concurrency.** A version number on each drawing is
  enforced server-side via a JS hook. Concurrent edits surface as a
  409 to the client, which decides whether to overwrite, refetch, or
  open the server copy in a new tab. No automatic merging.
- **Folders.** A `folder_path` string per drawing; nested paths
  rendered as a tree client-side.
- **Templates (planned).** Collection is provisioned in the initial
  migration; the editor UI for it is deferred.

## Why this approach

- **Single binary deploy.** PocketBase is one Go binary. Coolify, a VPS,
  or `docker run` all work the same way.
- **Minimal upstream divergence.** The Kotlin/JS editor is only touched
  at clearly scoped seams: the application entrypoint (to insert an
  auth gate) and the data-access layer (to swap the localStorage
  workspace for a remote-backed one). The rest of MonoSketch remains
  byte-identical to upstream where possible.
- **Self-hosted.** Not a SaaS, no telemetry, no third-party services.
  Your SQLite file is the entire state.

## Architecture sketch

```
+-------------------+         HTTP        +-----------------------+
|  Browser (KT/JS)  |  <----------------> |  PocketBase           |
|  - MonoSketch UI  |   /api/...          |  - Static site (8090) |
|  - AuthGate       |   Authorization:    |  - Auth + collections |
|  - RemoteClient   |     <token>         |  - JS hooks           |
|  - WorkspaceDao   |   X-Expected-Ver:   |  - SQLite (/pb_data)  |
+-------------------+     <n>             +-----------------------+
```

Collections provisioned by migrations under
[server/pb_migrations](server/pb_migrations):

- `users` (built-in, public signup disabled).
- `drawings` (owner-scoped; `app_id` unique-indexed; `version` for
  optimistic concurrency).
- `templates` (read by any authenticated user; write owner-only).

## Quick start (Docker)

```bash
docker build -t pocketms .
docker run --rm -p 8090:8090 -v pocketms_data:/pb/pb_data pocketms
```

Open <http://localhost:8090/_/> to create the first admin and then
provision a user account from the admin UI under the `users` collection.
Open <http://localhost:8090/> to use the editor.

The persistent volume at `/pb/pb_data` holds the SQLite file and any
uploaded assets. Lose it and you lose everything; back it up.

## Coolify

- **Base directory:** repo root
- **Dockerfile location:** `Dockerfile`
- **Container port:** `8090`
- **Persistent volume:** mount at `/pb/pb_data`

## Development

The MonoSketch dev workflow is unchanged for the editor itself.

```bash
# Hot-reload dev server (no PocketBase)
./gradlew browserDevelopmentRun --continuous -Dorg.gradle.parallel=false
```

When running the dev server, the app talks to whatever PocketBase you
point it at via same-origin requests. The simplest setup is to run the
production container alongside and proxy `/api/*` and `/_/*` through
your dev server, or just iterate against the deployed instance.

Server-side changes live under [server/](server):

- [server/pb_migrations](server/pb_migrations) — collection schema as
  versioned JS migrations.
- [server/pb_hooks](server/pb_hooks) — JS hooks (currently optimistic
  concurrency on the drawings collection).

## Status

Active, early. Tracks roughly these milestones:

1. [x] Container builds and serves the upstream app from PocketBase.
2. [x] Collections and version-check hook provisioned automatically.
3. [x] `RemoteClient` module for PocketBase API calls.
4. [ ] Login gate and logout affordance.
5. [ ] Replace localStorage workspace list with PocketBase.
6. [ ] Server-side open/edit/save with conflict banner.
7. [ ] Folder sidebar.
8. [ ] Templates UI.

## License

This repository is licensed under the [Apache License 2.0](LICENSE),
inherited from MonoSketch.

PocketBase (MIT) is bundled into the runtime image as an unmodified
binary. See [NOTICE.md](NOTICE.md) for upstream attributions and
third-party licenses.
