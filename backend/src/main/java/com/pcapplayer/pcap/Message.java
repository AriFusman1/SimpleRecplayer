package com.pcapplayer.pcap;

/**
 * One payload to send during a replay.
 *
 * @param offsetMillis when to send it, in milliseconds after the replay starts
 * @param payload      bytes to send
 */
public record Message(long offsetMillis, byte[] payload) {
}
