package com.pcapplayer.api;

import com.pcapplayer.Json;
import com.pcapplayer.pcap.Message;
import com.pcapplayer.pcap.MessageBuilder;
import com.pcapplayer.pcap.Mode;
import com.pcapplayer.pcap.ParsedPcap;
import com.pcapplayer.replay.Player;
import com.pcapplayer.storage.Category;
import com.pcapplayer.storage.MetadataStore;
import com.pcapplayer.storage.PcapRecord;
import io.vertx.core.Vertx;
import io.vertx.ext.web.RoutingContext;

import java.util.List;

/** Start / stop / status of the (single) replay. */
final class ReplayRoutes {

    /** host is ignored in TCP_CLIENT mode, where port is the port to listen on. */
    record StartRequest(String recordId, String host, Integer port) {
    }

    private final Vertx vertx;
    private final MetadataStore store;
    private final Player player;
    private final RecordRoutes records;

    /** 'records' is used for loadParsed(): downloading and parsing a record's pcap. */
    ReplayRoutes(Vertx vertx, MetadataStore store, Player player, RecordRoutes records) {
        this.vertx = vertx;
        this.store = store;
        this.player = player;
        this.records = records;
    }

    /**
     * POST /api/replay/start
     * On a worker thread: download + parse the pcap and build the messages. Then Player.start() deploys the
     * replay on its own Vert.x context and returns at once, so the HTTP response doesn't wait for the replay.
     */
    void start(RoutingContext ctx) {
        StartRequest req = Json.read(ctx.body().asString(), StartRequest.class);
        ApiServer.blocking(vertx, ctx, () -> {
            if (!player.status().state().isDone()) {
                throw new IllegalStateException("Another replay is already running"); // fail before downloading
            }
            PcapRecord r = store.record(req.recordId());
            Category category = store.category(r.categoryId());
            int port = Validation.port(req.port());
            String host = category.mode() == Mode.TCP_CLIENT ? null : Validation.requireText(req.host(), "Target IP");

            ParsedPcap parsed = records.loadParsed(r.id());
            List<Message> messages = MessageBuilder.build(parsed.packets(),
                    RecordRoutes.filterFor(r, category, r.startTime(), r.endTime()));
            if (messages.isEmpty()) {
                throw new IllegalArgumentException("This record has no messages to send");
            }

            player.start(r.id(), r.name(), category.mode(), host, port, r.startTime(), messages);
            return player.status();
        });
    }

    /** POST /api/replay/stop - only sets a flag and asks Vert.x to undeploy the replay, so it is safe on the event loop. */
    void stop(RoutingContext ctx) {
        player.stop();
        ApiServer.sendJson(ctx, player.status());
    }

    /** GET /api/replay/status - reads atomic fields only, safe on the event loop. */
    void status(RoutingContext ctx) {
        ApiServer.sendJson(ctx, player.status());
    }
}
