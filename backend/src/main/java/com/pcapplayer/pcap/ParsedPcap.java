package com.pcapplayer.pcap;

import java.util.List;

/**
 * Result of parsing a whole pcap file.
 *
 * @param packets            usable packets, in capture order
 * @param recordingStartNanos time of the first packet in the file (any type), epoch nanos
 * @param recordingEndNanos   time of the last packet in the file (any type), epoch nanos
 * @param fragmentsSkipped   IPv4 fragments we skipped (reassembly is not supported)
 * @param truncatedSkipped   packets cut short by the capture snap length, skipped
 */
public record ParsedPcap(List<Packet> packets, long recordingStartNanos, long recordingEndNanos,
                         int totalPackets, int fragmentsSkipped, int truncatedSkipped) {

    public long recordingStartMillis() {
        return recordingStartNanos / 1_000_000;
    }

    public long recordingEndMillis() {
        return recordingEndNanos / 1_000_000;
    }
}
