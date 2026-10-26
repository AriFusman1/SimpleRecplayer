package com.pcapplayer;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Application settings.
 *
 * Loaded in 3 steps, each one overriding the previous:
 *   1. config.json inside the jar (defaults)
 *   2. the file named by the CONFIG_FILE environment variable, if set
 *   3. single environment variables (HTTP_PORT, S3_BUCKET, ...)
 */
public record Config(int httpPort, int maxUploadMb, String tempDir,
                     String s3Endpoint, String s3Region, String s3Bucket,
                     String s3AccessKey, String s3SecretKey, boolean s3PathStyle) {

    public static Config load() throws IOException {
        JsonNode defaults;
        try (InputStream in = Config.class.getResourceAsStream("/config.json")) {
            defaults = Json.MAPPER.readTree(in);
        }
        String configFile = System.getenv("CONFIG_FILE");
        JsonNode file = configFile != null && !configFile.isBlank()
                ? Json.MAPPER.readTree(Files.readString(Path.of(configFile)))
                : Json.MAPPER.createObjectNode();

        return new Config(
                Integer.parseInt(value("httpPort", "HTTP_PORT", defaults, file)),
                Integer.parseInt(value("maxUploadMb", "MAX_UPLOAD_MB", defaults, file)),
                value("tempDir", "TEMP_DIR", defaults, file),
                value("s3Endpoint", "S3_ENDPOINT", defaults, file),
                value("s3Region", "S3_REGION", defaults, file),
                value("s3Bucket", "S3_BUCKET", defaults, file),
                value("s3AccessKey", "S3_ACCESS_KEY", defaults, file),
                value("s3SecretKey", "S3_SECRET_KEY", defaults, file),
                Boolean.parseBoolean(value("s3PathStyle", "S3_PATH_STYLE", defaults, file)));
    }

    private static String value(String jsonKey, String envName, JsonNode defaults, JsonNode file) {
        String env = System.getenv(envName);
        if (env != null) return env;
        if (file.hasNonNull(jsonKey)) return file.get(jsonKey).asText();
        return defaults.path(jsonKey).asText("");
    }

    /** Same as toString() but hides the secret key, for the startup log. */
    public String describe() {
        return "httpPort=" + httpPort + " maxUploadMb=" + maxUploadMb + " tempDir=" + tempDir
                + " s3Endpoint=" + s3Endpoint + " s3Region=" + s3Region + " s3Bucket=" + s3Bucket
                + " s3PathStyle=" + s3PathStyle + " s3AccessKey=" + (s3AccessKey.isEmpty() ? "(default AWS chain)" : "***");
    }
}
