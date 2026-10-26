package com.pcapplayer.pcap;

import java.util.Set;

/**
 * What to take from the pcap file.
 *
 * @param mode        decides the protocol and whether ports/IP are matched on the source or destination side
 * @param ports       selected ports (must not be empty)
 * @param ip          optional IP to match; null or empty means "all IPs"
 * @param startMillis start of the time range, epoch millis (inclusive)
 * @param endMillis   end of the time range, epoch millis (inclusive)
 */
public record Filter(Mode mode, Set<Integer> ports, String ip, long startMillis, long endMillis) {
}
