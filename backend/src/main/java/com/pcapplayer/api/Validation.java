package com.pcapplayer.api;

import com.pcapplayer.storage.MetadataStore;
import com.pcapplayer.storage.PcapRecord;
import com.pcapplayer.storage.Tag;

import java.util.List;

/** Input checks shared by several routes. Each throws IllegalArgumentException (HTTP 400) on bad input. */
final class Validation {

    private Validation() {
    }

    /** Returns the trimmed name. Fails if it is empty or another record (not 'ownId') already uses it. */
    static String recordName(MetadataStore store, String name, String ownId) {
        String trimmed = requireText(name, "Name");
        for (PcapRecord r : store.records()) {
            if (r.name().equalsIgnoreCase(trimmed) && !r.id().equals(ownId)) {
                throw new IllegalArgumentException("A record named \"" + trimmed + "\" already exists. Please choose a different name");
            }
        }
        return trimmed;
    }

    /** A record may only use global tags and tags of its own category. */
    static void tagsAllowed(MetadataStore store, List<String> tagIds, String categoryId) {
        for (String tagId : tagIds) {
            Tag tag = store.tag(tagId);
            if (tag.categoryId() != null && !tag.categoryId().equals(categoryId)) {
                throw new IllegalArgumentException("Tag \"" + tag.name() + "\" belongs to another category");
            }
        }
    }

    static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    static int port(Integer port) {
        if (port == null || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
        return port;
    }
}
