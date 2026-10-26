package com.pcapplayer;

import com.pcapplayer.api.ApiServer;
import com.pcapplayer.replay.Player;
import com.pcapplayer.storage.MetadataStore;
import com.pcapplayer.storage.S3Storage;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.file.FileSystemOptions;
import io.vertx.ext.web.Router;

import java.nio.file.Files;
import java.nio.file.Path;

/** Starts the application: config -> S3 metadata -> replay player -> HTTP server. */
public final class Main {

    public static void main(String[] args) throws Exception {
        Config config = Config.load();
        System.out.println("Starting PCAP Player Simple: " + config.describe());
        Files.createDirectories(Path.of(config.tempDir()));

        // Vert.x unpacks static files from the jar into a cache directory. On OpenShift the working
        // directory may not be writable (random user id), so we put the cache in our temp directory.
        Vertx vertx = Vertx.vertx(new VertxOptions().setFileSystemOptions(
                new FileSystemOptions().setFileCacheDir(config.tempDir() + "/vertx-cache")));

        // Loading from S3 blocks, which is fine here: the server isn't started yet.
        MetadataStore store = new MetadataStore(new S3Storage(config));
        store.load();

        Player player = new Player();
        Router router = ApiServer.createRouter(vertx, config, store, player);

        vertx.createHttpServer()
                .requestHandler(router)
                .listen(config.httpPort())
                .onSuccess(server -> System.out.println("Listening on port " + server.actualPort()))
                .onFailure(error -> {
                    System.err.println("Could not start HTTP server: " + error.getMessage());
                    System.exit(1);
                });
    }
}
