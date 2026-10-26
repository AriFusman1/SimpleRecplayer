package com.pcapplayer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** One shared Jackson ObjectMapper for converting between JSON and Java objects. */
public final class Json {

    /** ObjectMapper is thread-safe once configured, so one instance is shared by everyone. */
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Json() {
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write JSON", e);
        }
    }

    /** Parses a request body. Bad JSON becomes an IllegalArgumentException, which the API turns into a 400. */
    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getOriginalMessage());
        }
    }
}
