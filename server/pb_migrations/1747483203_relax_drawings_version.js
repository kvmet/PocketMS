/// <reference path="../pb_data/types.d.ts" />

// PocketBase's "required" validator on number fields rejects 0 as
// "blank", which is unsatisfiable for a version field whose semantic
// zero IS 0. The drawings_version_check hook is the authoritative
// writer for this field anyway (forces 0 on create, current+1 on
// update), so the schema-side required flag adds no safety and breaks
// every create. Drop required and rely on the hook.

migrate((app) => {
    const collection = app.findCollectionByNameOrId("drawings");
    const field = collection.fields.getByName("version");
    field.required = false;
    app.save(collection);
}, (app) => {
    const collection = app.findCollectionByNameOrId("drawings");
    const field = collection.fields.getByName("version");
    field.required = true;
    app.save(collection);
});
