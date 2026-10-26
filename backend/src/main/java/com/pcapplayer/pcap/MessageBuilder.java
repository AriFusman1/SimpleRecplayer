package com.pcapplayer.pcap;

import java.util.ArrayList;
import java.util.List;

/** Turns parsed packets into the list of messages a replay will send. */
public final class MessageBuilder {

    private MessageBuilder() {
    }

    /**
     * Keeps only the packets that match the filter and shifts their time so the range start is 0.
     * Packets stay in capture order. offsetMillis = packetTime - filter.startMillis.
     */
    public static List<Message> build(List<Packet> packets, Filter filter) {
        List<Message> messages = new ArrayList<>();
        for (Packet p : packets) {
            if (matches(p, filter)) {
                messages.add(new Message(p.timeMillis() - filter.startMillis(), p.payload()));
            }
        }
        return messages;
    }

    static boolean matches(Packet p, Filter filter) {
        Mode mode = filter.mode();
        if (!p.protocol().equals(mode.protocol())) {
            return false;
        }
        int port = mode.filtersOnSource() ? p.srcPort() : p.dstPort();
        if (!filter.ports().contains(port)) {
            return false;
        }
        boolean allIps = filter.ip() == null || filter.ip().isBlank();
        String ip = mode.filtersOnSource() ? p.srcIp() : p.dstIp();
        if (!allIps && !filter.ip().trim().equals(ip)) {
            return false;
        }
        long t = p.timeMillis();
        return t >= filter.startMillis() && t <= filter.endMillis();
    }
}
