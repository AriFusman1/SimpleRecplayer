package com.pcapplayer.api;

import com.pcapplayer.Json;
import com.pcapplayer.pcap.Mode;
import com.pcapplayer.storage.Category;
import com.pcapplayer.storage.MetadataStore;
import com.pcapplayer.storage.Tag;
import io.vertx.core.Vertx;
import io.vertx.ext.web.RoutingContext;

import java.util.Map;
import java.util.UUID;

/** Create / rename / delete categories and tags. */
final class CategoryRoutes {

    record CategoryRequest(String name, Mode mode) {
    }

    /** categoryId null = global tag. */
    record TagRequest(String name, String categoryId) {
    }

    private final Vertx vertx;
    private final MetadataStore store;

    CategoryRoutes(Vertx vertx, MetadataStore store) {
        this.vertx = vertx;
        this.store = store;
    }

    // ------------------------------------------------------------------ categories

    void listCategories(RoutingContext ctx) {
        ApiServer.sendJson(ctx, store.categories());
    }

    void createCategory(RoutingContext ctx) {
        CategoryRequest req = Json.read(ctx.body().asString(), CategoryRequest.class);
        ApiServer.blocking(vertx, ctx, () -> {
            String name = uniqueCategoryName(req.name(), null);
            if (req.mode() == null) throw new IllegalArgumentException("Mode is required");
            Category category = new Category(UUID.randomUUID().toString(), name, req.mode());
            store.saveCategory(category);
            return category;
        });
    }

    /** Only the name can change. The mode is fixed because existing records were filtered for it. */
    void renameCategory(RoutingContext ctx) {
        String id = ctx.pathParam("id");
        CategoryRequest req = Json.read(ctx.body().asString(), CategoryRequest.class);
        ApiServer.blocking(vertx, ctx, () -> {
            Category old = store.category(id);
            Category renamed = new Category(id, uniqueCategoryName(req.name(), id), old.mode());
            store.saveCategory(renamed);
            return renamed;
        });
    }

    /** Also deletes all records (and files) and tags of this category. */
    void deleteCategory(RoutingContext ctx) {
        String id = ctx.pathParam("id");
        ApiServer.blocking(vertx, ctx, () -> {
            store.deleteCategory(id);
            return Map.of("deleted", id);
        });
    }

    private String uniqueCategoryName(String name, String ownId) {
        String trimmed = Validation.requireText(name, "Name");
        for (Category c : store.categories()) {
            if (c.name().equalsIgnoreCase(trimmed) && !c.id().equals(ownId)) {
                throw new IllegalArgumentException("A category named \"" + trimmed + "\" already exists");
            }
        }
        return trimmed;
    }

    // ------------------------------------------------------------------ tags

    void listTags(RoutingContext ctx) {
        ApiServer.sendJson(ctx, store.tags());
    }

    void createTag(RoutingContext ctx) {
        TagRequest req = Json.read(ctx.body().asString(), TagRequest.class);
        ApiServer.blocking(vertx, ctx, () -> {
            String categoryId = req.categoryId() == null || req.categoryId().isBlank() ? null : req.categoryId();
            if (categoryId != null) store.category(categoryId); // 404 if missing
            Tag tag = new Tag(UUID.randomUUID().toString(), uniqueTagName(req.name(), categoryId, null), categoryId);
            store.saveTag(tag);
            return tag;
        });
    }

    /** Only the name can change; a tag stays global or in its category. */
    void renameTag(RoutingContext ctx) {
        String id = ctx.pathParam("id");
        TagRequest req = Json.read(ctx.body().asString(), TagRequest.class);
        ApiServer.blocking(vertx, ctx, () -> {
            Tag old = store.tag(id);
            Tag renamed = new Tag(id, uniqueTagName(req.name(), old.categoryId(), id), old.categoryId());
            store.saveTag(renamed);
            return renamed;
        });
    }

    /** Also removes the tag from every record that has it. */
    void deleteTag(RoutingContext ctx) {
        String id = ctx.pathParam("id");
        ApiServer.blocking(vertx, ctx, () -> {
            store.deleteTag(id);
            return Map.of("deleted", id);
        });
    }

    /** Tag names must be unique within the same scope (global, or one category). */
    private String uniqueTagName(String name, String categoryId, String ownId) {
        String trimmed = Validation.requireText(name, "Name");
        for (Tag t : store.tags()) {
            boolean sameScope = categoryId == null ? t.categoryId() == null : categoryId.equals(t.categoryId());
            if (sameScope && t.name().equalsIgnoreCase(trimmed) && !t.id().equals(ownId)) {
                throw new IllegalArgumentException("A tag named \"" + trimmed + "\" already exists here");
            }
        }
        return trimmed;
    }
}
