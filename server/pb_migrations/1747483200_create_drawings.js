/// <reference path="../pb_data/types.d.ts" />

migrate((app) => {
    const usersCollection = app.findCollectionByNameOrId("users");

    const collection = new Collection({
        type: "base",
        name: "drawings",
        listRule: "@request.auth.id != \"\" && owner = @request.auth.id",
        viewRule: "@request.auth.id != \"\" && owner = @request.auth.id",
        createRule: "@request.auth.id != \"\" && owner = @request.auth.id",
        updateRule: "@request.auth.id != \"\" && owner = @request.auth.id",
        deleteRule: "@request.auth.id != \"\" && owner = @request.auth.id",
        fields: [
            {
                name: "app_id",
                type: "text",
                required: true,
                min: 1,
                max: 64,
            },
            {
                name: "owner",
                type: "relation",
                required: true,
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
                name: "folder_path",
                type: "text",
                max: 512,
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
                name: "offset",
                type: "json",
                maxSize: 256,
            },
            {
                name: "version",
                type: "number",
                required: true,
                onlyInt: true,
                min: 0,
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
            "CREATE UNIQUE INDEX idx_drawings_app_id ON drawings (app_id)",
            "CREATE INDEX idx_drawings_owner ON drawings (owner)",
            "CREATE INDEX idx_drawings_owner_folder ON drawings (owner, folder_path)",
        ],
    });

    app.save(collection);
}, (app) => {
    const collection = app.findCollectionByNameOrId("drawings");
    app.delete(collection);
});
