package com.pcapplayer.storage;

/**
 * A label for records. Stored in tags.json.
 *
 * @param categoryId the category this tag belongs to, or null for a global tag
 */
public record Tag(String id, String name, String categoryId) {
}
