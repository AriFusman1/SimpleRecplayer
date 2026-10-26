package com.pcapplayer.replay;

import com.pcapplayer.pcap.Message;
import com.pcapplayer.pcap.Mode;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sends messages over the network with the original timing. Only one replay can run at a time.
 *
 * Threading:
 *  - start()/stop()/status() are called from Vert.x threads and never block.
 *  - The send loop runs on 'executor', never on the Vert.x event loop (it sleeps and does blocking I/O).
 *  - The executor has 2 threads: one for the send loop, and (TCP only) one that reads and throws away
 *    whatever the service sends back, so unread replies can't fill the socket buffers.
 */
public final class Player {

    private static final int CONNECT_TIMEOUT_MILLIS = 5000;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    /** The current (or last) replay. Holding it in an AtomicReference is what makes "one at a time" safe. */
    private final AtomicReference<Replay> current = new AtomicReference<>();

    /** Something that sends one payload. Lets the timing loop be shared by TCP and UDP. */
    private interface Sender {
        void send(byte[] payload) throws IOException;
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
        executor.submit(() -> run(replay));
    }

    /** Asks the running replay to stop. Closing the socket wakes up a blocked accept() or write(). */
    public void stop() {
        Replay replay = current.get();
        if (replay == null || replay.state.get().isDone()) {
            return;
        }
        replay.stopRequested.set(true);
        closeQuietly(replay.openSocket.get());
    }

    public ReplayStatus status() {
        Replay replay = current.get();
        return replay == null ? ReplayStatus.idle() : replay.toStatus();
    }

    // ---------------------------------------------------------------- runs on the replay executor

    private void run(Replay replay) {
        try {
            switch (replay.mode) {
                case TCP_SERVER -> playAsTcpClient(replay);
                case UDP_SERVER -> playAsUdpSender(replay);
                case TCP_CLIENT -> playAsTcpServer(replay);
            }
            replay.finish(replay.stopRequested.get() ? ReplayState.STOPPED : ReplayState.FINISHED, null);
        } catch (Exception e) {
            // After Stop we close the socket ourselves, so the exception that follows is expected.
            if (replay.stopRequested.get()) {
                replay.finish(ReplayState.STOPPED, null);
            } else {
                String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                replay.finish(ReplayState.FAILED, message);
            }
        } finally {
            closeQuietly(replay.openSocket.get());
        }
    }

    /** TCP_SERVER mode: the service is a TCP server, so we connect to it. */
    private void playAsTcpClient(Replay replay) throws IOException, InterruptedException {
        try (Socket socket = new Socket()) {
            replay.openSocket.set(socket);
            if (replay.stopRequested.get()) return; // Stop was pressed before we registered the socket
            socket.setTcpNoDelay(true); // send each payload right away, don't batch small writes
            socket.connect(new InetSocketAddress(replay.host, replay.port), CONNECT_TIMEOUT_MILLIS);
            sendOverTcp(replay, socket);
        }
    }

    /** TCP_CLIENT mode: the service connects to us. We accept exactly one connection. */
    private void playAsTcpServer(Replay replay) throws IOException, InterruptedException {
        Socket socket;
        try (ServerSocket server = new ServerSocket()) {
            replay.openSocket.set(server);
            if (replay.stopRequested.get()) return;
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(replay.port));
            socket = server.accept(); // blocks in state WAITING until the service connects
        } // the listening socket is closed here, so no second connection is accepted

        try (socket) {
            replay.openSocket.set(socket);
            if (replay.stopRequested.get()) return;
            socket.setTcpNoDelay(true);
            replay.state.set(ReplayState.PLAYING);
            sendOverTcp(replay, socket);
        }
    }

    private void sendOverTcp(Replay replay, Socket socket) throws IOException, InterruptedException {
        InputStream in = socket.getInputStream();
        executor.submit(() -> discardReplies(in));
        OutputStream out = socket.getOutputStream();
        sendWithTiming(replay, payload -> {
            out.write(payload); // one write per captured packet
            out.flush();
        });
    }

    /** UDP_SERVER mode: each payload becomes one datagram. */
    private void playAsUdpSender(Replay replay) throws IOException, InterruptedException {
        try (DatagramSocket socket = new DatagramSocket()) {
            replay.openSocket.set(socket);
            if (replay.stopRequested.get()) return;
            InetAddress address = InetAddress.getByName(replay.host);
            sendWithTiming(replay, payload ->
                    socket.send(new DatagramPacket(payload, payload.length, address, replay.port)));
        }
    }

    /**
     * The timing loop. Every message is due at (start + offset). We always compare with the same
     * 'start', so small delays in one send never add up over a long replay (no drift).
     */
    private void sendWithTiming(Replay replay, Sender sender) throws IOException, InterruptedException {
        long start = System.nanoTime();
        replay.startNanos.set(start);

        for (Message message : replay.messages) {
            long dueNanos = start + message.offsetMillis() * 1_000_000;
            // Sleep in short steps (max 100 ms) so Stop is noticed quickly even during long gaps.
            while (!replay.stopRequested.get()) {
                long leftMillis = (dueNanos - System.nanoTime()) / 1_000_000;
                if (leftMillis <= 0) break;
                Thread.sleep(Math.min(leftMillis, 100));
            }
            if (replay.stopRequested.get()) return;

            sender.send(message.payload());
            replay.sent.incrementAndGet();
        }
    }

    /** Reads until the connection closes. Runs as the second task on the executor. */
    private static void discardReplies(InputStream in) {
        byte[] buffer = new byte[8192];
        try {
            while (in.read(buffer) != -1) {
                // ignore what the service sends back
            }
        } catch (IOException ignored) {
            // the socket was closed: the replay is over
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }
}
