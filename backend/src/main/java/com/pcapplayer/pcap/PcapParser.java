package com.pcapplayer.pcap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written parser for classic pcap files (not pcapng).
 *
 * File layout:
 *   [24-byte global header] [16-byte packet header][packet bytes] [16-byte packet header][packet bytes] ...
 *
 * The pcap headers are written in the byte order of the machine that captured the file
 * (we detect it from the magic number). The network headers inside each packet
 * (Ethernet, IPv4, TCP, UDP) are always big-endian ("network byte order").
 */
public final class PcapParser {

    // Magic numbers, read as a big-endian int from the first 4 bytes of the file.
    private static final int MAGIC_MICROS = 0xa1b2c3d4;
    private static final int MAGIC_MICROS_SWAPPED = 0xd4c3b2a1;
    private static final int MAGIC_NANOS = 0xa1b23c4d;
    private static final int MAGIC_NANOS_SWAPPED = 0x4d3cb2a1;
    private static final int MAGIC_PCAPNG = 0x0a0d0d0a;

    // Link types (global header, offset 20).
    private static final int LINKTYPE_ETHERNET = 1;
    private static final int LINKTYPE_LINUX_SLL = 113;   // dumpcap -i any (older)
    private static final int LINKTYPE_LINUX_SLL2 = 276;  // dumpcap -i any (newer)

    private static final int ETHERTYPE_IPV4 = 0x0800;
    private static final int ETHERTYPE_VLAN = 0x8100;

    private static final int IP_PROTO_TCP = 6;
    private static final int IP_PROTO_UDP = 17;

    private static final int GLOBAL_HEADER_SIZE = 24;
    private static final int PACKET_HEADER_SIZE = 16;

    private PcapParser() {
    }

    /** Reads and parses a pcap file. Throws IllegalArgumentException if the file is not supported. */
    public static ParsedPcap parse(Path file) throws IOException {
        return parse(Files.readAllBytes(file));
    }

    public static ParsedPcap parse(byte[] data) {
        if (data.length < GLOBAL_HEADER_SIZE) {
            throw new IllegalArgumentException("Invalid file: too small to be a pcap file");
        }

        // 'net' reads big-endian values (network headers). 'pcap' reads values in the file's byte order.
        ByteBuffer net = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        ByteBuffer pcap = ByteBuffer.wrap(data);

        int magic = net.getInt(0);
        boolean nanos;
        switch (magic) {
            case MAGIC_MICROS -> { pcap.order(ByteOrder.BIG_ENDIAN); nanos = false; }
            case MAGIC_MICROS_SWAPPED -> { pcap.order(ByteOrder.LITTLE_ENDIAN); nanos = false; }
            case MAGIC_NANOS -> { pcap.order(ByteOrder.BIG_ENDIAN); nanos = true; }
            case MAGIC_NANOS_SWAPPED -> { pcap.order(ByteOrder.LITTLE_ENDIAN); nanos = true; }
            case MAGIC_PCAPNG -> throw new IllegalArgumentException(
                    "pcapng files are not supported. Convert with: editcap -F pcap in.pcapng out.pcap");
            default -> throw new IllegalArgumentException("Invalid file: not a pcap file (unknown magic number)");
        }

        // Global header: magic(4) version_major(2) version_minor(2) thiszone(4) sigfigs(4) snaplen(4) linktype(4)
        int linkType = pcap.getInt(20);
        if (linkType != LINKTYPE_ETHERNET && linkType != LINKTYPE_LINUX_SLL && linkType != LINKTYPE_LINUX_SLL2) {
            throw new IllegalArgumentException("Unsupported link type " + linkType
                    + ". Supported: Ethernet (1), Linux cooked SLL (113), SLL2 (276)");
        }

        List<Packet> packets = new ArrayList<>();
        long firstTime = 0;
        long lastTime = 0;
        int total = 0;
        int fragments = 0;
        int truncated = 0;

        int pos = GLOBAL_HEADER_SIZE;
        while (pos + PACKET_HEADER_SIZE <= data.length) {
            // Packet header: ts_sec(4) ts_usec_or_nsec(4) captured_len(4) original_len(4)
            long seconds = Integer.toUnsignedLong(pcap.getInt(pos));
            long fraction = Integer.toUnsignedLong(pcap.getInt(pos + 4));
            int capturedLen = pcap.getInt(pos + 8);
            int originalLen = pcap.getInt(pos + 12);
            int start = pos + PACKET_HEADER_SIZE;
            int end = start + capturedLen;
            pos = end;

            long timeNanos = seconds * 1_000_000_000L + (nanos ? fraction : fraction * 1000);
            if (total == 0) {
                firstTime = timeNanos;
            }
            lastTime = timeNanos;
            total++;

            // The file itself was cut in the middle of a packet (e.g. dumpcap was killed).
            if (capturedLen < 0 || end > data.length) {
                truncated++;
                break;
            }
            // dumpcap stored only the first 'snaplen' bytes of this packet.
            if (capturedLen < originalLen) {
                truncated++;
                continue;
            }

            int ipStart = findIpv4Start(net, linkType, start, end);
            if (ipStart < 0) {
                continue; // not IPv4 (IPv6, ARP, ...)
            }
            if (isFragment(net, ipStart, end)) {
                fragments++; // IP fragment reassembly is not supported in this version
                continue;
            }
            Packet packet = parseIpv4(net, data, ipStart, end, timeNanos);
            if (packet != null) {
                packets.add(packet);
            }
        }

        return new ParsedPcap(packets, firstTime, lastTime, total, fragments, truncated);
    }

    /**
     * Skips the link-layer header. Returns the offset where the IPv4 header starts,
     * or -1 if this packet is not IPv4.
     */
    private static int findIpv4Start(ByteBuffer net, int linkType, int start, int end) {
        int etherType;
        int ipStart;
        if (linkType == LINKTYPE_ETHERNET) {
            // Ethernet: dst MAC(6) src MAC(6) ethertype(2)
            if (end - start < 14) return -1;
            etherType = net.getShort(start + 12) & 0xffff;
            ipStart = start + 14;
            if (etherType == ETHERTYPE_VLAN) {
                // 802.1Q adds 4 bytes: tag(2) + the real ethertype(2)
                if (end - start < 18) return -1;
                etherType = net.getShort(start + 16) & 0xffff;
                ipStart = start + 18;
            }
        } else if (linkType == LINKTYPE_LINUX_SLL) {
            // SLL: packet_type(2) arphrd(2) addr_len(2) addr(8) protocol(2) = 16 bytes
            if (end - start < 16) return -1;
            etherType = net.getShort(start + 14) & 0xffff;
            ipStart = start + 16;
        } else {
            // SLL2: protocol(2) reserved(2) ifindex(4) arphrd(2) packet_type(1) addr_len(1) addr(8) = 20 bytes
            if (end - start < 20) return -1;
            etherType = net.getShort(start) & 0xffff;
            ipStart = start + 20;
        }
        return etherType == ETHERTYPE_IPV4 ? ipStart : -1;
    }

    /**
     * IPv4 header bytes 6-7: flags(3 bits) + fragment offset(13 bits).
     * A packet is a fragment if "more fragments" (0x2000) is set or the offset is not 0.
     */
    private static boolean isFragment(ByteBuffer net, int ip, int end) {
        if (end - ip < 20) return false;
        int flagsAndOffset = net.getShort(ip + 6) & 0xffff;
        boolean moreFragments = (flagsAndOffset & 0x2000) != 0;
        int fragmentOffset = flagsAndOffset & 0x1fff;
        return moreFragments || fragmentOffset > 0;
    }

    /** Returns the TCP/UDP packet, or null if it should be skipped (not IPv4, not TCP/UDP, empty payload). */
    private static Packet parseIpv4(ByteBuffer net, byte[] data, int ip, int end, long timeNanos) {
        if (end - ip < 20) return null;

        // IPv4 header: version+IHL(1) tos(1) total_length(2) id(2) flags+fragment_offset(2)
        //              ttl(1) protocol(1) checksum(2) src(4) dst(4) [options]
        int versionAndIhl = data[ip] & 0xff;
        if ((versionAndIhl >> 4) != 4) return null;
        int ipHeaderLen = (versionAndIhl & 0x0f) * 4;
        int totalLen = net.getShort(ip + 2) & 0xffff;

        int protocol = data[ip + 9] & 0xff;
        String srcIp = ipToString(data, ip + 12);
        String dstIp = ipToString(data, ip + 16);

        // Ethernet may pad short frames with zeros; the IP total length tells us where the real data ends.
        int ipEnd = Math.min(end, ip + totalLen);
        int l4 = ip + ipHeaderLen;

        int srcPort;
        int dstPort;
        int payloadStart;
        String protocolName;
        if (protocol == IP_PROTO_TCP) {
            // TCP header: src_port(2) dst_port(2) seq(4) ack(4) data_offset(4 bits) ...
            if (ipEnd - l4 < 20) return null;
            srcPort = net.getShort(l4) & 0xffff;
            dstPort = net.getShort(l4 + 2) & 0xffff;
            int tcpHeaderLen = ((data[l4 + 12] & 0xff) >> 4) * 4;
            payloadStart = l4 + tcpHeaderLen;
            protocolName = "TCP";
        } else if (protocol == IP_PROTO_UDP) {
            // UDP header: src_port(2) dst_port(2) length(2) checksum(2)
            if (ipEnd - l4 < 8) return null;
            srcPort = net.getShort(l4) & 0xffff;
            dstPort = net.getShort(l4 + 2) & 0xffff;
            payloadStart = l4 + 8;
            protocolName = "UDP";
        } else {
            return null; // ICMP etc.
        }

        if (payloadStart >= ipEnd) return null; // empty payload, e.g. a pure TCP ACK

        byte[] payload = new byte[ipEnd - payloadStart];
        System.arraycopy(data, payloadStart, payload, 0, payload.length);
        return new Packet(timeNanos, srcIp, dstIp, protocolName, srcPort, dstPort, payload);
    }

    private static String ipToString(byte[] data, int offset) {
        return (data[offset] & 0xff) + "." + (data[offset + 1] & 0xff) + "."
                + (data[offset + 2] & 0xff) + "." + (data[offset + 3] & 0xff);
    }
}
