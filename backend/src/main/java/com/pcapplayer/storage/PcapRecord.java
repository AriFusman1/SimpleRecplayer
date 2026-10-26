package com.pcapplayer.storage;

import java.util.List;

/**
 * A saved recording, ready to play. Stored in records/{id}.json; its file is pcaps/{id}.pcap.
 * (Named PcapRecord, not Record, to avoid confusion with java.lang.Record.)
 * All times are epoch milliseconds.
 *
 * @param ports          selected ports (destination or source side, depending on the category mode)
 * @param ipFilter       selected IP, or null for all IPs
 * @param startTime      start of the selected range
 * @param endTime        end of the selected range
 * @param recordingStart first packet in the file (used to validate edits)
 * @param recordingEnd   last packet in the file
 */
public record PcapRecord(String id, String name, String description, String categoryId, List<String> tagIds,
                         List<Integer> ports, String ipFilter, long startTime, long endTime,
                         long recordingStart, long recordingEnd, Stats stats,
                         String fileName, long fileSize, long uploadDate) {

    /** Numbers computed from the file and the filter, shown on the record card. */
    public record Stats(int messageCount, long durationMillis, String protocol, List<String> warnings) {
    }
}
