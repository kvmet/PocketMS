/*
 * Copyright (c) 2026, PocketMS contributors
 */

package mono.remote.sync

/**
 * Coarse-grained sync state shown to the user as a non-blocking
 * indicator. Conflicts are a separate concern surfaced via a modal,
 * not via this enum.
 */
enum class SyncStatus {
    /** No pending writes and the last push reached the server. */
    Synced,

    /** At least one drawing has a queued debounce or in-flight push. */
    Pending,

    /** At least one drawing's last push failed with a network error. */
    Offline,
}
