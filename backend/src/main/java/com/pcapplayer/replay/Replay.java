package com.pcapplayer.replay;

import com.pcapplayer.pcap.Message;
import com.pcapplayer.pcap.Mode;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One replay: what to send, where, and its live progress.
 *
 * The replay's own Vert.x context ({@link ReplayVerticle}) writes the progress fields, while the HTTP
 * event loop reads them for the status endpoint and calls stop(). Every changing field is an Atomic*,
 * so all threads always see up-to-date values without any locking.
 */
final class Replay {

    final String recordId;
    final String recordName;
    final Mode mode;
    final String host;           // target host (TCP_SERVER / UDP_SERVER modes)
    final int port;              // target port, or the listen port in TCP_CLIENT mode
    final long rangeStartMillis; // recording time that matches offset 0
    final List<Message> messages;

    final AtomicReference<ReplayState> state = new AtomicReference<>();
    final AtomicInteger sent = new AtomicInteger();
    final AtomicLong startNanos = new AtomicLong();  // 0 until the timer starts
    final AtomicLong endNanos = new AtomicLong();    // 0 until the replay ends
    final AtomicReference<String> error = new AtomicReference<>();
    final AtomicBoolean stopRequested = new AtomicBoolean();
    /** Vert.x deployment id of the ReplayVerticle, set once it is deployed, so stop() can undeploy it. */
    final AtomicReference<String> deploymentId = new AtomicReference<>();

    Replay(String recordId, String recordName, Mode mode, String host, int port,
           long rangeStartMillis, List<Message> messages) {
        this.recordId = recordId;
        this.recordName = recordName;
        this.mode = mode;
        this.host = host;
        this.port = port;
        this.rangeStartMillis = rangeStartMillis;
        this.messages = messages;
        state.set(mode == Mode.TCP_CLIENT ? ReplayState.WAITING : ReplayState.PLAYING);
    }

    /** Offset of the last message = how long the whole replay takes. */
    long totalMillis() {
        return messages.isEmpty() ? 0 : messages.get(messages.size() - 1).offsetMillis();
    }

    void finish(ReplayState finalState, String errorMessage) {
        endNanos.compareAndSet(0, System.nanoTime());
        error.set(errorMessage);
        state.set(finalState);
    }

    ReplayStatus toStatus() {
        long start = startNanos.get();
        long end = endNanos.get();
        long elapsed = 0;
        if (start != 0) {
            elapsed = ((end != 0 ? end : System.nanoTime()) - start) / 1_000_000;
        }
        long remaining = Math.max(0, totalMillis() - elapsed);
        long clock = start != 0 ? rangeStartMillis + elapsed : 0;
        String target = mode == Mode.TCP_CLIENT ? "listening on port " + port : host + ":" + port;

        return new ReplayStatus(state.get(), recordId, recordName, mode.name(), target, port,
                sent.get(), messages.size(), elapsed, remaining, clock, error.get());
    }
}
