package com.pcapplayer.pcap;

/**
 * Describes the ORIGINAL service that was recorded. The player plays the opposite role.
 *
 *   TCP_SERVER: service was a TCP server -> player connects to it as a TCP client.
 *   UDP_SERVER: service received UDP     -> player sends UDP datagrams to it.
 *   TCP_CLIENT: service was a TCP client -> player listens and waits for it to connect.
 */
public enum Mode {
    TCP_SERVER("TCP"),
    UDP_SERVER("UDP"),
    TCP_CLIENT("TCP");

    private final String protocol;

    Mode(String protocol) {
        this.protocol = protocol;
    }

    /** "TCP" or "UDP". */
    public String protocol() {
        return protocol;
    }

    /**
     * In TCP_CLIENT mode the source sent its data FROM the selected port (it was the server side),
     * so we filter on the source port/IP. In the other modes we filter on the destination.
     */
    public boolean filtersOnSource() {
        return this == TCP_CLIENT;
    }
}
