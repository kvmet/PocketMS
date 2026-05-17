/// <reference path="../pb_data/types.d.ts" />

// Admin-only account provisioning. Setting createRule to null disables the
// public signup endpoint; accounts are created from the admin UI.
migrate((app) => {
    const users = app.findCollectionByNameOrId("users");
    users.createRule = null;
    app.save(users);
}, (app) => {
    const users = app.findCollectionByNameOrId("users");
    users.createRule = "";
    app.save(users);
});
