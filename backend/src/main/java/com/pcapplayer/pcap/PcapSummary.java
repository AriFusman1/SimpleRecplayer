package com.pcapplayer.pcap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Builds the information shown on the Upload page, and validates the user's choices. */
public final class PcapSummary {

    public record PortCount(String protocol, int port, int packets) {
    }

    public record IpCount(String ip, int packets) {
    }

    /** Everything found in the file. Times are epoch millis. */
    public record Summary(long recordingStart, long recordingEnd, int totalPackets, int usablePackets,
                          List<String> protocols, List<PortCount> dstPorts, List<PortCount> srcPorts,
                          List<IpCount> dstIps, List<IpCount> srcIps, List<String> warnings) {
    }

    /** Result of validating a filter: what a replay with this filter would do. */
    public record Check(int messageCount, long durationMillis, String protocol, List<String> warnings) {
    }

    private PcapSummary() {
    }

    public static Summary summarize(ParsedPcap parsed) {
        TreeSet<String> protocols = new TreeSet<>();
        // TreeMap keeps the lists sorted, which is nicer in the UI.
        Map<String, Integer> dstPorts = new TreeMap<>();
        Map<String, Integer> srcPorts = new TreeMap<>();
        Map<String, Integer> dstIps = new TreeMap<>();
        Map<String, Integer> srcIps = new TreeMap<>();

        for (Packet p : parsed.packets()) {
            protocols.add(p.protocol());
            dstPorts.merge(p.protocol() + ":" + p.dstPort(), 1, Integer::sum);
            srcPorts.merge(p.protocol() + ":" + p.srcPort(), 1, Integer::sum);
            dstIps.merge(p.dstIp(), 1, Integer::sum);
            srcIps.merge(p.srcIp(), 1, Integer::sum);
        }

        return new Summary(
                parsed.recordingStartMillis(),
                parsed.recordingEndMillis(),
                parsed.totalPackets(),
                parsed.packets().size(),
                new ArrayList<>(protocols),
                toPortList(dstPorts),
                toPortList(srcPorts),
                toIpList(dstIps),
                toIpList(srcIps),
                warnings(parsed));
    }

    /**
     * Checks a filter against the file. Throws IllegalArgumentException with a user-friendly
     * message if the choice can't produce a replay.
     */
    public static Check validate(ParsedPcap parsed, Filter filter) {
        validateTimeRange(parsed, filter.startMillis(), filter.endMillis());
        if (filter.ports() == null || filter.ports().isEmpty()) {
            throw new IllegalArgumentException("Select at least one port");
        }

        String protocol = filter.mode().protocol();
        boolean hasProtocol = parsed.packets().stream().anyMatch(p -> p.protocol().equals(protocol));
        if (!hasProtocol) {
            throw new IllegalArgumentException("The file contains no " + protocol
                    + " packets with data, but the category mode " + filter.mode() + " needs " + protocol);
        }

        List<Message> messages = MessageBuilder.build(parsed.packets(), filter);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("No messages left after filtering. Check the ports, IP and time range");
        }

        return new Check(messages.size(), filter.endMillis() - filter.startMillis(), protocol, warnings(parsed));
    }

    /**
     * The UI works in whole seconds, so we allow the range from the second the recording started
     * up to the second after it ended (otherwise the default end time would cut the last packets).
     */
    public static void validateTimeRange(ParsedPcap parsed, long start, long end) {
        long min = floorToSecond(parsed.recordingStartMillis());
        long max = ceilToSecond(parsed.recordingEndMillis());
        if (start < min || end > max) {
            throw new IllegalArgumentException("The time range is outside the recording");
        }
        if (start >= end) {
            throw new IllegalArgumentException("The start time must be before the end time");
        }
    }

    public static long floorToSecond(long millis) {
        return millis - Math.floorMod(millis, 1000L);
    }

    public static long ceilToSecond(long millis) {
        long floor = floorToSecond(millis);
        return floor == millis ? millis : floor + 1000;
    }

    private static List<String> warnings(ParsedPcap parsed) {
        List<String> warnings = new ArrayList<>();
        if (parsed.fragmentsSkipped() > 0) {
            warnings.add(parsed.fragmentsSkipped() + " fragmented packets skipped (not supported in this version)");
        }
        if (parsed.truncatedSkipped() > 0) {
            warnings.add(parsed.truncatedSkipped() + " packets truncated by capture size were skipped");
        }
        return warnings;
    }

    private static List<PortCount> toPortList(Map<String, Integer> counts) {
        List<PortCount> list = new ArrayList<>();
        counts.forEach((key, count) -> {
            String[] parts = key.split(":"); // key is "TCP:5000"
            list.add(new PortCount(parts[0], Integer.parseInt(parts[1]), count));
        });
        list.sort((a, b) -> a.protocol().equals(b.protocol())
                ? Integer.compare(a.port(), b.port())
                : a.protocol().compareTo(b.protocol()));
        return list;
    }

    private static List<IpCount> toIpList(Map<String, Integer> counts) {
        List<IpCount> list = new ArrayList<>();
        counts.forEach((ip, count) -> list.add(new IpCount(ip, count)));
        return list;
    }
}
