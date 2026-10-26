package com.pcapplayer.pcap;

import org.pcap4j.core.PcapHandle;
import org.pcap4j.core.PcapHandle.TimestampPrecision;
import org.pcap4j.core.PcapNativeException;
import org.pcap4j.core.Pcaps;
import org.pcap4j.packet.IllegalRawDataException;
import org.pcap4j.packet.IpV4Packet;
import org.pcap4j.packet.TcpPacket;
import org.pcap4j.packet.UdpPacket;
import org.pcap4j.packet.factory.PacketFactories;
import org.pcap4j.packet.namednumber.DataLinkType;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reads classic pcap files (not pcapng) with Pcap4J, which uses the native libpcap.
 *
 * libpcap handles the file format (byte order, micro/nanosecond timestamps); Pcap4J decodes
 * Ethernet (+VLAN), Linux SLL, IPv4, TCP and UDP. We only decide which packets to keep.
 */
public final class PcapReader {

    // Magic numbers, read as a big-endian int from the first 4 bytes of the file.
    private static final int MAGIC_MICROS = 0xa1b2c3d4;
    private static final int MAGIC_MICROS_SWAPPED = 0xd4c3b2a1;
    private static final int MAGIC_NANOS = 0xa1b23c4d;
    private static final int MAGIC_NANOS_SWAPPED = 0x4d3cb2a1;
    private static final int MAGIC_PCAPNG = 0x0a0d0d0a;

    private static final int GLOBAL_HEADER_SIZE = 24;

    // Link types we accept (global header). Pcap4J 1.8.2 has no constant for SLL2.
    private static final int LINKTYPE_ETHERNET = 1;
    private static final int LINKTYPE_LINUX_SLL = 113;   // dumpcap -i any (older)
    private static final int LINKTYPE_LINUX_SLL2 = 276;  // dumpcap -i any (newer)

    private static final int SLL2_HEADER_SIZE = 20;
    private static final int ETHERTYPE_IPV4 = 0x0800;

    private PcapReader() {
    }

    /** Reads a pcap file. Throws IllegalArgumentException if the file is not supported. */
    public static ParsedPcap read(Path file) throws IOException {
        checkMagic(file);

        try (PcapHandle handle = open(file)) {
            int linkType = handle.getDlt().value();
            if (linkType != LINKTYPE_ETHERNET && linkType != LINKTYPE_LINUX_SLL && linkType != LINKTYPE_LINUX_SLL2) {
                throw new IllegalArgumentException("Unsupported link type " + linkType
                        + ". Supported: Ethernet (1), Linux cooked SLL (113), SLL2 (276)");
            }
            return readPackets(handle, linkType);
        } catch (PcapNativeException e) {
            throw new IllegalArgumentException("Invalid file: " + e.getMessage());
        }
    }

    /**
     * libpcap would happily open pcapng files too, but we only support classic pcap
     * (same rule as before), so we look at the magic number ourselves first.
     */
    private static void checkMagic(Path file) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(GLOBAL_HEADER_SIZE);
        }
        if (head.length < GLOBAL_HEADER_SIZE) {
            throw new IllegalArgumentException("Invalid file: too small to be a pcap file");
        }
        int magic = ((head[0] & 0xff) << 24) | ((head[1] & 0xff) << 16) | ((head[2] & 0xff) << 8) | (head[3] & 0xff);
        if (magic == MAGIC_PCAPNG) {
            throw new IllegalArgumentException(
                    "pcapng files are not supported. Convert with: editcap -F pcap in.pcapng out.pcap");
        }
        if (magic != MAGIC_MICROS && magic != MAGIC_MICROS_SWAPPED
                && magic != MAGIC_NANOS && magic != MAGIC_NANOS_SWAPPED) {
            throw new IllegalArgumentException("Invalid file: not a pcap file (unknown magic number)");
        }
    }

    /**
     * Asks libpcap for nanosecond timestamps (it converts microsecond files for us).
     * Very old libpcap versions can't do that, so we fall back to the default (microseconds).
     */
    private static PcapHandle open(Path file) throws PcapNativeException {
        String path = file.toAbsolutePath().toString();
        try {
            return Pcaps.openOffline(path, TimestampPrecision.NANO);
        } catch (PcapNativeException | UnsupportedOperationException e) {
            return Pcaps.openOffline(path);
        }
    }

    private static ParsedPcap readPackets(PcapHandle handle, int linkType) {
        DataLinkType dlt = handle.getDlt();
        List<Packet> packets = new ArrayList<>();
        long firstTime = 0;
        long lastTime = 0;
        int total = 0;
        int fragments = 0;
        int truncated = 0;

        while (true) {
            // We take the raw bytes (and decode them below) so we know the exact captured length.
            byte[] raw;
            try {
                raw = handle.getNextRawPacketEx();
            } catch (EOFException e) {
                break; // end of file
            } catch (PcapNativeException e) {
                // The file itself was cut in the middle of a packet (e.g. dumpcap was killed).
                total++;
                truncated++;
                break;
            } catch (Exception e) { // NotOpenException, TimeoutException: can't happen for an open file
                throw new IllegalArgumentException("Invalid file: " + e.getMessage());
            }

            long timeNanos = toNanos(handle.getTimestamp());
            if (total == 0) {
                firstTime = timeNanos;
            }
            lastTime = timeNanos;
            total++;

            // dumpcap stored only the first 'snaplen' bytes of this packet.
            if (raw.length < handle.getOriginalLength()) {
                truncated++;
                continue;
            }

            int ipStart = findIpv4Start(raw, linkType, dlt);
            if (ipStart < 0) {
                continue; // not IPv4 (IPv6, ARP, ...)
            }
            IpV4Packet ip;
            try {
                ip = IpV4Packet.newPacket(raw, ipStart, raw.length - ipStart);
            } catch (IllegalRawDataException e) {
                continue; // broken IPv4 header
            }
            IpV4Packet.IpV4Header header = ip.getHeader();
            if (header.getMoreFragmentFlag() || header.getFragmentOffset() != 0) {
                fragments++; // IP fragment reassembly is not supported in this version
                continue;
            }
            Packet packet = toPacket(raw, ipStart, ip, timeNanos);
            if (packet != null) {
                packets.add(packet);
            }
        }

        return new ParsedPcap(packets, firstTime, lastTime, total, fragments, truncated);
    }

    /**
     * Returns where the IPv4 header starts in the raw bytes, or -1 if the packet is not IPv4.
     * Pcap4J decodes Ethernet (with or without 802.1Q VLAN tags) and Linux SLL; we add up the
     * header lengths of the layers it found in front of the IPv4 packet.
     */
    private static int findIpv4Start(byte[] raw, int linkType, DataLinkType dlt) {
        if (linkType == LINKTYPE_LINUX_SLL2) {
            return sll2Ipv4Start(raw);
        }
        org.pcap4j.packet.Packet layer = PacketFactories.getFactory(org.pcap4j.packet.Packet.class, DataLinkType.class)
                .newInstance(raw, 0, raw.length, dlt);
        int offset = 0;
        while (layer != null && layer.getHeader() != null && !(layer instanceof IpV4Packet)) {
            offset += layer.getHeader().length();
            layer = layer.getPayload();
        }
        return layer instanceof IpV4Packet ? offset : -1;
    }

    /**
     * Pcap4J 1.8.2 doesn't know Linux SLL2 (link type 276), so we read its fixed 20-byte header ourselves:
     *   protocol(2) reserved(2) ifindex(4) arphrd(2) packet_type(1) addr_len(1) addr(8)
     * and the IPv4 packet starts right after it.
     */
    private static int sll2Ipv4Start(byte[] raw) {
        if (raw.length < SLL2_HEADER_SIZE) return -1;
        int protocol = ((raw[0] & 0xff) << 8) | (raw[1] & 0xff);
        return protocol == ETHERTYPE_IPV4 ? SLL2_HEADER_SIZE : -1;
    }

    /** Returns the TCP/UDP packet, or null if it should be skipped (not TCP/UDP, empty payload). */
    private static Packet toPacket(byte[] raw, int ipStart, IpV4Packet ip, long timeNanos) {
        IpV4Packet.IpV4Header ipHeader = ip.getHeader();
        String srcIp = ipHeader.getSrcAddr().getHostAddress();
        String dstIp = ipHeader.getDstAddr().getHostAddress();

        org.pcap4j.packet.Packet l4 = ip.getPayload();
        int srcPort;
        int dstPort;
        int l4HeaderLen;
        String protocolName;
        if (l4 instanceof TcpPacket tcp) {
            srcPort = tcp.getHeader().getSrcPort().valueAsInt();
            dstPort = tcp.getHeader().getDstPort().valueAsInt();
            l4HeaderLen = tcp.getHeader().length();
            protocolName = "TCP";
        } else if (l4 instanceof UdpPacket udp) {
            srcPort = udp.getHeader().getSrcPort().valueAsInt();
            dstPort = udp.getHeader().getDstPort().valueAsInt();
            l4HeaderLen = udp.getHeader().length();
            protocolName = "UDP";
        } else {
            return null; // ICMP etc., or a TCP/UDP header too short to decode
        }

        // We cut the payload out of the original bytes instead of using Pcap4J's decoded payload:
        // Pcap4J decodes some ports as protocols (DNS on 53, GTP) and rebuilds their bytes, which can
        // lose trailing data. Ethernet may pad short frames, so the IP total length marks the real end.
        int ipEnd = Math.min(raw.length, ipStart + ipHeader.getTotalLengthAsInt());
        int payloadStart = ipStart + ipHeader.length() + l4HeaderLen;
        if (payloadStart >= ipEnd) return null; // empty payload, e.g. a pure TCP ACK

        byte[] payload = Arrays.copyOfRange(raw, payloadStart, ipEnd);
        return new Packet(timeNanos, srcIp, dstIp, protocolName, srcPort, dstPort, payload);
    }

    /** java.sql.Timestamp keeps whole seconds in getTime() and the fraction (as nanos) in getNanos(). */
    private static long toNanos(Timestamp ts) {
        long seconds = Math.floorDiv(ts.getTime(), 1000L);
        return seconds * 1_000_000_000L + ts.getNanos();
    }
}
