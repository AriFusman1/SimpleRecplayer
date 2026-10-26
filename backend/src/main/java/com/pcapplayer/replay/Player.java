package com.pcapplayer.replay;

import com.pcapplayer.pcap.Message;
import com.pcapplayer.pcap.Mode;
import io.vertx.core.Vertx;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sends messages over the network with the original timing. Only one replay can run at a time.
 *
 * Threading:
 *  - start()/stop()/status() are called from Vert.x threads (event loop or worker) and never block.
 *  - Each replay is a {@link ReplayVerticle}: Vert.x gives it its own event-loop context, and all its
 *    sockets and timers run there. No executors, no sleeping threads.
 */
public final class Player {

    private final Vertx vertx;

    /** The current (or last) replay. Holding it in an AtomicReference is what makes "one at a time" safe. */
    private final AtomicReference<Replay> current = new AtomicReference<>();

    public Player(Vertx vertx) {
        this.vertx = vertx;
    }

    /**
     * Starts a replay in the background and returns immediately.
     *
     * @param host ignored in TCP_CLIENT mode
     * @param port target port, or the port to listen on in TCP_CLIENT mode
     * @throws IllegalStateException if another replay is still running
     */
    public void start(String recordId, String recordName, Mode mode, String host, int port,
                      long rangeStartMillis, List<Message> messages) {
        Replay previous = current.get();
        if (previous != null && !previous.state.get().isDone()) {
            throw new IllegalStateException("Another replay is already running: " + previous.recordName);
        }
        Replay replay = new Replay(recordId, recordName, mode, host, port, rangeStartMillis, messages);
        // compareAndSet fails if another request replaced 'previous' in the meantime.
        if (!current.compareAndSet(previous, replay)) {
            throw new IllegalStateException("Another replay was just started");
        }
        vertx.deployVerticle(new ReplayVerticle(replay))
                .onSuccess(id -> {
                    replay.deploymentId.set(id);
                    // Stop was pressed before we knew the id, or the replay already ended inside start().
                    if (replay.stopRequested.get() || replay.state.get().isDone()) {
                        vertx.undeploy(id);
                    }
                })
                .onFailure(e -> replay.finish(ReplayState.FAILED, e.getMessage()));
    }

    /**
     * Stops the running replay. Undeploying the verticle makes Vert.x call ReplayVerticle.stop() on the
     * replay's own context, which cancels the pending timer and closes the sockets.
     */
    public void stop() {
        Replay replay = current.get();
        if (replay == null || replay.state.get().isDone()) {
            return;
        }
        replay.stopRequested.set(true);
        String id = replay.deploymentId.get();
        if (id != null) {
            vertx.undeploy(id); // if it already ended by itself, this just fails quietly
        }
    }

    public ReplayStatus status() {
        Replay replay = current.get();
        return replay == null ? ReplayStatus.idle() : replay.toStatus();
    }
}
