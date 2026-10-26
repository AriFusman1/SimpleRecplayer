package com.pcapplayer.api;

import com.pcapplayer.Json;
import com.pcapplayer.pcap.Filter;
import com.pcapplayer.pcap.ParsedPcap;
import com.pcapplayer.pcap.PcapParser;
import com.pcapplayer.pcap.PcapSummary;
import com.pcapplayer.storage.Category;
import com.pcapplayer.storage.MetadataStore;
import com.pcapplayer.storage.PcapRecord;
import io.vertx.core.Vertx;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.RoutingContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The 3 steps of the Upload page:
 *   1. analyze  - receive the file, parse it, return a summary and a temporary uploadId
 *   2. validate - check the user's choices (ports, IP, time range) against the file
 *   3. save     - copy the file to S3 and create the record
 */
final class UploadRoutes {

    /** A file that was analyzed but not saved as a record yet. */
    private record PendingUpload(Path file, String fileName, long fileSize, ParsedPcap parsed, long createdAt) {
    }

    record AnalyzeResponse(String uploadId, String fileName, long fileSize, PcapSummary.Summary summary) {
    }

    record ValidateRequest(String uploadId, String categoryId, List<Integer> ports, String ipFilter,
                           long startTime, long endTime) {
    }

    record ValidateResponse(int messageCount, long durationMillis, String protocol, String categoryName,
                            String mode, List<String> warnings) {
    }

    record SaveRequest(String uploadId, String name, String description, String categoryId, List<String> tagIds,
                       List<Integer> ports, String ipFilter, long startTime, long endTime) {
    }

    private static final long PENDING_MAX_AGE_MILLIS = 60 * 60 * 1000; // 1 hour

    private final Vertx vertx;
    private final MetadataStore store;
    private final ConcurrentHashMap<String, PendingUpload> pending = new ConcurrentHashMap<>();

    UploadRoutes(Vertx vertx, MetadataStore store) {
        this.vertx = vertx;
        this.store = store;
        // Every 10 minutes, forget uploads that were never saved, and delete their temp files.
        vertx.setPeriodic(10 * 60 * 1000, timerId -> removeOldPendingUploads());
    }

    /** POST /api/pcap/analyze (multipart form with one field "file"). */
    void analyze(RoutingContext ctx) {
        List<FileUpload> uploads = ctx.fileUploads();
        if (uploads.size() != 1) {
            uploads.forEach(u -> vertx.fileSystem().delete(u.uploadedFileName()));
            ctx.fail(new IllegalArgumentException("Upload exactly one .pcap file"));
            return;
        }
        FileUpload upload = uploads.get(0);
        Path file = Path.of(upload.uploadedFileName());

        ApiServer.blocking(vertx, ctx, () -> {
            ParsedPcap parsed;
            try {
                parsed = PcapParser.parse(file);
            } catch (Exception e) {
                Files.deleteIfExists(file);
                throw e;
            }
            String uploadId = UUID.randomUUID().toString();
            pending.put(uploadId, new PendingUpload(file, upload.fileName(), upload.size(), parsed,
                    System.currentTimeMillis()));
            return new AnalyzeResponse(uploadId, upload.fileName(), upload.size(), PcapSummary.summarize(parsed));
        });
    }

    /** POST /api/pcap/validate */
    void validate(RoutingContext ctx) {
        ValidateRequest req = Json.read(ctx.body().asString(), ValidateRequest.class);
        ApiServer.blocking(vertx, ctx, () -> {
            PendingUpload upload = pendingUpload(req.uploadId());
            Category category = store.category(req.categoryId());
            Filter filter = new Filter(category.mode(), toSet(req.ports()), req.ipFilter(), req.startTime(), req.endTime());
            PcapSummary.Check check = PcapSummary.validate(upload.parsed(), filter);
            return new ValidateResponse(check.messageCount(), check.durationMillis(), check.protocol(),
                    category.name(), category.mode().name(), check.warnings());
        });
    }

    /** POST /api/records - validates everything again, then stores the file and the record in S3. */
    void save(RoutingContext ctx) {
        SaveRequest req = Json.read(ctx.body().asString(), SaveRequest.class);
        ApiServer.blocking(vertx, ctx, () -> {
            PendingUpload upload = pendingUpload(req.uploadId());
            String name = Validation.recordName(store, req.name(), null);
            Category category = store.category(req.categoryId());
            List<String> tagIds = req.tagIds() == null ? List.of() : req.tagIds();
            Validation.tagsAllowed(store, tagIds, category.id());

            String ip = req.ipFilter() == null || req.ipFilter().isBlank() ? null : req.ipFilter().trim();
            Filter filter = new Filter(category.mode(), toSet(req.ports()), ip, req.startTime(), req.endTime());
            PcapSummary.Check check = PcapSummary.validate(upload.parsed(), filter);

            String id = UUID.randomUUID().toString();
            store.savePcapFile(id, upload.file()); // file first, so a record never points to a missing file
            PcapRecord record = new PcapRecord(id, name, req.description() == null ? "" : req.description().trim(),
                    category.id(), tagIds, req.ports(), ip, req.startTime(), req.endTime(),
                    upload.parsed().recordingStartMillis(), upload.parsed().recordingEndMillis(),
                    new PcapRecord.Stats(check.messageCount(), check.durationMillis(), check.protocol(), check.warnings()),
                    upload.fileName(), upload.fileSize(), System.currentTimeMillis());
            store.saveRecord(record);

            pending.remove(req.uploadId());
            Files.deleteIfExists(upload.file());
            return record;
        });
    }

    private PendingUpload pendingUpload(String uploadId) {
        PendingUpload upload = uploadId == null ? null : pending.get(uploadId);
        if (upload == null) {
            throw new NoSuchElementException("The uploaded file has expired. Please upload it again");
        }
        return upload;
    }

    private static HashSet<Integer> toSet(List<Integer> ports) {
        return ports == null ? new HashSet<>() : new HashSet<>(ports);
    }

    private void removeOldPendingUploads() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, PendingUpload> entry : pending.entrySet()) {
            if (now - entry.getValue().createdAt() > PENDING_MAX_AGE_MILLIS) {
                pending.remove(entry.getKey());
                vertx.fileSystem().delete(entry.getValue().file().toString()); // async, doesn't block
            }
        }
    }
}
