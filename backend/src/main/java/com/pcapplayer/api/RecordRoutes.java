package com.pcapplayer.api;

import com.pcapplayer.Config;
import com.pcapplayer.Json;
import com.pcapplayer.pcap.Filter;
import com.pcapplayer.pcap.ParsedPcap;
import com.pcapplayer.pcap.PcapParser;
import com.pcapplayer.pcap.PcapSummary;
import com.pcapplayer.storage.Category;
import com.pcapplayer.storage.MetadataStore;
import com.pcapplayer.storage.PcapRecord;
import io.vertx.core.Vertx;
import io.vertx.ext.web.RoutingContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;

/** List, edit, delete and download saved records. */
final class RecordRoutes {

    record UpdateRequest(String name, String description, long startTime, long endTime) {
    }

    private final Vertx vertx;
    private final Config config;
    private final MetadataStore store;

    RecordRoutes(Vertx vertx, Config config, MetadataStore store) {
        this.vertx = vertx;
        this.config = config;
        this.store = store;
    }

    /** GET /api/records - from memory, so no executeBlocking needed. */
    void list(RoutingContext ctx) {
        ApiServer.sendJson(ctx, store.records());
    }

    /**
     * PUT /api/records/:id - edits name, description and time range.
     * A new time range changes the message count, so we download and parse the file again.
     */
    void update(RoutingContext ctx) {
        String id = ctx.pathParam("id");
        UpdateRequest req = Json.read(ctx.body().asString(), UpdateRequest.class);
        ApiServer.blocking(vertx, ctx, () -> {
            PcapRecord r = store.record(id);
            String name = Validation.recordName(store, req.name(), id);
            String description = req.description() == null ? "" : req.description().trim();

            PcapRecord.Stats stats = r.stats();
            if (req.startTime() != r.startTime() || req.endTime() != r.endTime()) {
                Category category = store.category(r.categoryId());
                ParsedPcap parsed = loadParsed(r.id());
                PcapSummary.Check check = PcapSummary.validate(parsed,
                        filterFor(r, category, req.startTime(), req.endTime()));
                stats = new PcapRecord.Stats(check.messageCount(), check.durationMillis(), check.protocol(), check.warnings());
            }

            PcapRecord updated = new PcapRecord(r.id(), name, description, r.categoryId(), r.tagIds(), r.ports(),
                    r.ipFilter(), req.startTime(), req.endTime(), r.recordingStart(), r.recordingEnd(), stats,
                    r.fileName(), r.fileSize(), r.uploadDate());
            store.saveRecord(updated);
            return updated;
        });
    }

    /** DELETE /api/records/:id - deletes the record and its pcap file. */
    void delete(RoutingContext ctx) {
        String id = ctx.pathParam("id");
        ApiServer.blocking(vertx, ctx, () -> {
            store.record(id); // 404 if missing
            store.deleteRecord(id);
            return Map.of("deleted", id);
        });
    }

    /** GET /api/records/:id/download - copies the file from S3 to a temp file, then streams it to the browser. */
    void download(RoutingContext ctx) {
        String id = ctx.pathParam("id");
        vertx.executeBlocking(() -> {
            PcapRecord r = store.record(id);
            Path temp = tempFile();
            store.downloadPcapFile(id, temp);
            return Map.entry(r, temp);
        }).onSuccess(result -> {
            // Back on the event loop: sendFile streams the file without blocking.
            Path temp = result.getValue();
            String fileName = result.getKey().fileName().replace("\"", "");
            ctx.response()
                    .putHeader("Content-Type", "application/vnd.tcpdump.pcap")
                    .putHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                    .sendFile(temp.toString())
                    .onComplete(done -> vertx.fileSystem().delete(temp.toString()));
        }).onFailure(ctx::fail);
    }

    // ------------------------------------------------------------------ shared with ReplayRoutes

    /** The filter that a record stands for, with a given time range. */
    static Filter filterFor(PcapRecord r, Category category, long startTime, long endTime) {
        return new Filter(category.mode(), new HashSet<>(r.ports()), r.ipFilter(), startTime, endTime);
    }

    /** Downloads a record's pcap from S3 to a temp file and parses it. BLOCKING. */
    ParsedPcap loadParsed(String recordId) throws IOException {
        Path temp = tempFile();
        try {
            store.downloadPcapFile(recordId, temp);
            return PcapParser.parse(temp);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private Path tempFile() throws IOException {
        Path dir = Path.of(config.tempDir(), "downloads");
        Files.createDirectories(dir);
        return Files.createTempFile(dir, "record-", ".pcap");
    }
}
