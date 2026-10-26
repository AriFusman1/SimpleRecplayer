package com.pcapplayer.storage;

import com.pcapplayer.pcap.Mode;

/** A group of records that share a connection mode. Stored in categories.json. */
public record Category(String id, String name, Mode mode) {
}
