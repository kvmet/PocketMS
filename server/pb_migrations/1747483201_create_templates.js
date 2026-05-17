/// <reference path="../pb_data/types.d.ts" />

migrate((app) => {
    const usersCollection = app.findCollectionByNameOrId("users");

    // Templates are readable by any authenticated user. Write access is
    // restricted to the owner. Global templates (owner = null) are managed
    // from the admin UI, which bypasses collection rules.
    const collection = new Collection({
        type: "base",
        name: "templates",
        listRule: "@request.auth.id != \"\"",
        viewRule: "@request.auth.id != \"\"",
        createRule: "@request.auth.id != \"\" && owner = @request.auth.id",
        updateRule: "@request.auth.id != \"\" && owner = @request.auth.id",
        deleteRule: "@request.auth.id != \"\" && owner = @request.auth.id",
        fields: [
            {
                name: "owner",
                type: "relation",
                required: false,
                maxSelect: 1,
                cascadeDelete: true,
                collectionId: usersCollection.id,
            },
            {
                name: "name",
                type: "text",
                required: true,
                max: 256,
            },
            {
                name: "category",
                type: "text",
                max: 128,
            },
            {
                name: "content",
                type: "json",
                maxSize: 5242880,
            },
            {
                name: "connectors",
                type: "json",
                maxSize: 1048576,
            },
            {
                name: "created",
                type: "autodate",
                onCreate: true,
            },
            {
                name: "updated",
                type: "autodate",
                onCreate: true,
                onUpdate: true,
            },
        ],
        indexes: [
            "CREATE INDEX idx_templates_owner ON templates (owner)",
            "CREATE INDEX idx_templates_category ON templates (category)",
        ],
    });

    app.save(collection);
}, (app) => {
    const collection = app.findCollectionByNameOrId("templates");
    app.delete(collection);
});
