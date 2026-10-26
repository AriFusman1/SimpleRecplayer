package com.pcapplayer.api;

import com.pcapplayer.Config;
import com.pcapplayer.Json;
import com.pcapplayer.replay.Player;
import com.pcapplayer.storage.MetadataStore;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.StaticHandler;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.Callable;

/**
 * Builds the HTTP router: all API routes, /health, the static frontend, and error handling.
 *
 * Vert.x threading in one paragraph: route handlers run on the Vert.x EVENT LOOP thread, which must
 * never block. Anything slow (S3 calls, parsing a pcap) goes through {@link #blocking}, which runs it on a
 * Vert.x WORKER thread via executeBlocking and then sends the result back on the event loop.
 * executeBlocking is "ordered" by default: blocking jobs run one after another, never at the same time.
 * That keeps metadata changes simple and race-free (e.g. two saves with the same name), at the cost of
 * a long upload delaying other changes for a moment.
 */
public final class ApiServer {

    private static final long MB = 1024L * 1024L;

    private ApiServer() {
    }

    public static Router createRouter(Vertx vertx, Config config, MetadataStore store, Player player) {
        Router router = Router.router(vertx);

        // Body handlers read the request body before our handler runs.
        BodyHandler jsonBody = BodyHandler.create(false).setBodyLimit(MB); // false = no file uploads
        BodyHandler uploadBody = BodyHandler.create(config.tempDir() + "/uploads")
                .setBodyLimit(config.maxUploadMb() * MB)
                .setDeleteUploadedFilesOnEnd(false); // we keep the file until the record is saved

        UploadRoutes upload = new UploadRoutes(vertx, store);
        RecordRoutes records = new RecordRoutes(vertx, config, store);
        CategoryRoutes categories = new CategoryRoutes(vertx, store);
        ReplayRoutes replay = new ReplayRoutes(vertx, store, player, records);

        router.get("/health").handler(ctx -> sendJson(ctx, Map.of("status", "UP")));

        router.post("/api/pcap/analyze").handler(uploadBody).handler(upload::analyze);
        router.post("/api/pcap/validate").handler(jsonBody).handler(upload::validate);
        router.post("/api/records").handler(jsonBody).handler(upload::save);

        router.get("/api/records").handler(records::list);
        router.put("/api/records/:id").handler(jsonBody).handler(records::update);
        router.delete("/api/records/:id").handler(records::delete);
        router.get("/api/records/:id/download").handler(records::download);

        router.get("/api/categories").handler(categories::listCategories);
        router.post("/api/categories").handler(jsonBody).handler(categories::createCategory);
        router.put("/api/categories/:id").handler(jsonBody).handler(categories::renameCategory);
        router.delete("/api/categories/:id").handler(categories::deleteCategory);

        router.get("/api/tags").handler(categories::listTags);
        router.post("/api/tags").handler(jsonBody).handler(categories::createTag);
        router.put("/api/tags/:id").handler(jsonBody).handler(categories::renameTag);
        router.delete("/api/tags/:id").handler(categories::deleteTag);

        router.post("/api/replay/start").handler(jsonBody).handler(replay::start);
        router.post("/api/replay/stop").handler(replay::stop);
        router.get("/api/replay/status").handler(replay::status);

        // The built React app, packaged inside the jar under /webroot.
        router.route("/*").handler(StaticHandler.create("webroot"));

        // Any exception thrown in a handler (or passed to ctx.fail) ends up here.
        router.route().failureHandler(ApiServer::handleFailure);
        return router;
    }

    /**
     * Runs 'work' on a worker thread (it may block), then sends its result as JSON from the event loop.
     * If 'work' throws, the exception goes to handleFailure.
     */
    static <T> void blocking(Vertx vertx, RoutingContext ctx, Callable<T> work) {
        vertx.executeBlocking(work)
                .onSuccess(result -> sendJson(ctx, result))
                .onFailure(ctx::fail);
    }

    static void sendJson(RoutingContext ctx, Object body) {
        ctx.response()
                .putHeader("Content-Type", "application/json")
                .end(body == null ? "{}" : Json.write(body));
    }

    /** Turns exceptions into {"error": "..."} with a matching HTTP status. */
    private static void handleFailure(RoutingContext ctx) {
        Throwable error = ctx.failure();
        int status;
        String message;
        if (error instanceof IllegalArgumentException) {
            status = 400; // bad input from the user
            message = error.getMessage();
        } else if (error instanceof NoSuchElementException) {
            status = 404;
            message = error.getMessage();
        } else if (error instanceof IllegalStateException) {
            status = 409; // e.g. a replay is already running
            message = error.getMessage();
        } else if (ctx.statusCode() == 413) {
            status = 413;
            message = "The file is too large";
        } else if (error == null) {
            status = ctx.statusCode() > 0 ? ctx.statusCode() : 500;
            message = "Request failed with status " + status;
        } else {
            status = 500;
            message = "Server error: " + (error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName());
            error.printStackTrace();
        }
        if (ctx.response().ended()) return;
        ctx.response()
                .setStatusCode(status)
                .putHeader("Content-Type", "application/json")
                .end(Json.write(Map.of("error", message == null ? "Unknown error" : message)));
    }
}
