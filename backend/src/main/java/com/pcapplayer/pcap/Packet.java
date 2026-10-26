package com.pcapplayer.pcap;

/**
 * One IPv4 TCP/UDP packet from the pcap file, with a non-empty payload.
 *
 * @param timeNanos epoch time in nanoseconds (works for both microsecond and nanosecond pcaps)
 * @param protocol  "TCP" or "UDP"
 * @param payload   the application bytes (after the TCP/UDP header)
 */
public record Packet(long timeNanos, String srcIp, String dstIp, String protocol,
                     int srcPort, int dstPort, byte[] payload) {

    public long timeMillis() {
        return timeNanos / 1_000_000;
    }
}
