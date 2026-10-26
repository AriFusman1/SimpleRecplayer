package com.pcapplayer.replay;

import com.pcapplayer.pcap.Message;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;

import java.util.function.Consumer;

/**
 * Runs ONE replay with Vert.x networking and timers.
 *
 * Each deployment gets its own event-loop context, and every method here runs on it (Vert.x calls
 * start(), stop(), socket handlers and timer handlers on that context only). So the fields below are
 * plain fields: they are only ever touched by one thread. Nothing here blocks, nothing sleeps.
 * Progress is published through the Atomic* fields of {@link Replay} for the status endpoint.
 *
 * The replay ends in one of two ways:
 *  - by itself (all sent, or a network error): {@link #end} records the result and undeploys us;
 *  - the user pressed Stop: Player undeploys us, Vert.x calls {@link #stop()}.
 * Either way {@link #finish} cancels the pending timer and closes the sockets.
 */
final class ReplayVerticle extends AbstractVerticle {

    private static final int CONNECT_TIMEOUT_MILLIS = 5000;

    private final Replay replay;

    private NetClient client;          // TCP_SERVER mode
    private NetServer server;          // TCP_CLIENT mode, open until the replay ends
    private NetSocket socket;          // the TCP connection, in both TCP modes
    private DatagramSocket udp;        // UDP_SERVER mode
    private Consumer<Buffer> sender;   // sends one payload over whichever socket we have

    private long startNanos;           // when message offset 0 is due
    private int next;                  // index of the next message to send
    private long timerId = -1;         // the one pending timer, or -1
    private boolean finished;

    ReplayVerticle(Replay replay) {
        this.replay = replay;
    }

    @Override
    public void start() {
        switch (replay.mode) {
            case TCP_SERVER -> connectToService();
            case UDP_SERVER -> sendUdp();
            case TCP_CLIENT -> waitForService();
        }
    }

    /** Called by Vert.x when we are undeployed: after Stop, or after we undeployed ourselves in end(). */
    @Override
    public void stop() {
        finish(ReplayState.STOPPED, null); // does nothing if the replay already ended by itself
    }

    // ---------------------------------------------------------------- the three modes

    /** TCP_SERVER mode: the service is a TCP server, so we connect to it. */
    private void connectToService() {
        client = vertx.createNetClient(new NetClientOptions()
                .setTcpNoDelay(true) // send each payload right away, don't batch small writes
                .setConnectTimeout(CONNECT_TIMEOUT_MILLIS));
        client.connect(replay.port, replay.host).onComplete(this::onConnected);
    }

    /**
     * TCP_CLIENT mode: the service connects to us. We accept exactly one connection.
     * Closing a Vert.x NetServer also closes the connections it accepted, so the server stays open
     * until the replay ends and closes any further connection right away.
     */
    private void waitForService() {
        server = vertx.createNetServer(new NetServerOptions().setTcpNoDelay(true).setReuseAddress(true));
        server.connectHandler(incoming -> {
            if (socket != null || finished) {
                incoming.close(); // a second connection: only one is allowed
                return;
            }
            onConnected(Future.succeededFuture(incoming));
        });
        // State stays WAITING until the service connects.
        server.listen(replay.port).onFailure(e -> end(ReplayState.FAILED, messageOf(e)));
    }

    /** UDP_SERVER mode: each payload becomes one datagram. */
    private void sendUdp() {
        udp = vertx.createDatagramSocket();
        sender = payload -> udp.send(payload, replay.port, replay.host)
                .onFailure(e -> end(ReplayState.FAILED, messageOf(e)));
        startTiming();
    }

    /** Both TCP modes end up here with a connected socket. */
    private void onConnected(AsyncResult<NetSocket> result) {
        if (result.failed()) {
            end(ReplayState.FAILED, messageOf(result.cause())); // e.g. "Connection refused"
            return;
        }
        socket = result.result();
        if (finished) { // Stop was pressed while we were connecting
            socket.close();
            return;
        }
        socket.handler(ignored -> {
            // Replies from the service are read and thrown away, so they can't fill the buffers.
        });
        socket.exceptionHandler(e -> end(ReplayState.FAILED, messageOf(e))); // e.g. "Connection reset"
        socket.closeHandler(v -> end(ReplayState.FAILED, "Connection closed by the service"));
        sender = payload -> socket.write(payload).onFailure(e -> end(ReplayState.FAILED, messageOf(e)));
        replay.state.set(ReplayState.PLAYING);
        startTiming();
    }

    // ---------------------------------------------------------------- timing

    private void startTiming() {
        startNanos = System.nanoTime();
        replay.startNanos.set(startNanos);
        sendDueMessages();
    }

    /**
     * Sends every message that is due, then sets ONE timer for the next one.
     * Each message is due at (start + offset), always measured from the same start,
     * so small delays (timer lateness, a slow send) never add up over a long replay: no drift.
     */
    private void sendDueMessages() {
        timerId = -1;
        while (!finished && next < replay.messages.size()) {
            Message message = replay.messages.get(next);
            long delayMillis = (startNanos + message.offsetMillis() * 1_000_000 - System.nanoTime()) / 1_000_000;
            if (delayMillis > 0) {
                timerId = vertx.setTimer(delayMillis, id -> sendDueMessages());
                return;
            }
            // Like a blocking write that waits while the socket buffer is full: pause until it drains.
            if (socket != null && socket.writeQueueFull()) {
                socket.drainHandler(v -> sendDueMessages());
                return;
            }
            sender.accept(Buffer.buffer(message.payload())); // one write / datagram per captured packet
            replay.sent.incrementAndGet();
            next++;
        }
        if (!finished) {
            end(ReplayState.FINISHED, null);
        }
    }

    // ---------------------------------------------------------------- ending

    /** The replay ended by itself: record the result, then undeploy (Vert.x then calls stop()). */
    private void end(ReplayState state, String error) {
        if (finished) return;
        finish(state, error);
        vertx.undeploy(deploymentID());
    }

    private void finish(ReplayState state, String error) {
        if (finished) return;
        finished = true;
        if (timerId != -1) {
            vertx.cancelTimer(timerId);
        }
        if (socket != null) {
            socket.closeHandler(null); // our own close is not "closed by the service"
            socket.close();
        }
        if (server != null) server.close();
        if (client != null) client.close();
        if (udp != null) udp.close();
        replay.finish(state, error);
    }

    private static String messageOf(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
