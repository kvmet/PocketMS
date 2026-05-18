/// <reference path="../pb_data/types.d.ts" />

// Optimistic concurrency for drawings.
//
// The client sends X-Expected-Version with the version it last loaded.
// If it matches the stored version, we increment and proceed. Otherwise we
// reject with 409 so the client can prompt the user to overwrite, refetch,
// or open the server copy in a new tab.
//
// New records start at version 0; the create hook enforces that.

onRecordCreateRequest((e) => {
    e.record.set("version", 0);
    e.next();
}, "drawings");

onRecordUpdateRequest((e) => {
    const headers = e.requestInfo().headers || {};
    const raw = headers["x_expected_version"];
    if (raw === undefined || raw === null || raw === "") {
        throw new BadRequestError("missing X-Expected-Version header");
    }
    const expected = parseInt(raw, 10);
    if (Number.isNaN(expected)) {
        throw new BadRequestError("invalid X-Expected-Version header");
    }

    const current = e.record.getInt("version");
    if (current !== expected) {
        // ApiError's data argument expects a per-field validation-errors
        // map; passing plain ints there makes PB rewrite them as
        // "validation_invalid_value" stubs and the client cannot recover
        // the actual current version. Encode it in the message string
        // instead and have the client parse it out.
        throw new ApiError(409, "version_conflict:current=" + current);
    }

    e.record.set("version", current + 1);
    e.next();
}, "drawings");
