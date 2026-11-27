package io.descoped.rawdata.provider.gcs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.descoped.rawdata.api.RawdataClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Real Google Cloud Storage for the live tests, which run only with {@code -Drawdata.gcs.live=true}. The bucket is
 * {@code -Drawdata.gcs.live.bucket} (required). Credentials are {@code application-default} by default: point
 * {@code GOOGLE_APPLICATION_CREDENTIALS} at the credentials meant for the tests; {@code -Drawdata.gcs.live.credential-provider}
 * and {@code -Drawdata.gcs.live.key-file} choose others. A run writes under {@code rawdata-client-test/<random>/} and
 * removes it.
 */
final class LiveGcs {

    static final String ENABLED = "rawdata.gcs.live";
    static final String RUN = "rawdata-client-test/" + UUID.randomUUID() + "/";

    private LiveGcs() {
    }

    static String bucket() {
        String bucket = System.getProperty("rawdata.gcs.live.bucket");
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("The live tests need -Drawdata.gcs.live.bucket=<bucket>");
        }
        return bucket;
    }

    static Map<String, String> configuration(String prefix) {
        Map<String, String> configuration = credentialsConfiguration();
        configuration.put(RawdataClient.PROVIDER_KEY, "gcs");
        configuration.put("gcs.bucket-name", bucket());
        configuration.put("gcs.prefix", RUN + prefix);
        configuration.put("gcs.listing.min-interval-seconds", "0");
        configuration.put("local-temp-folder", bufferFolder());
        configuration.put("avro-file.max.seconds", "1");
        return configuration;
    }

    private static Map<String, String> credentialsConfiguration() {
        Map<String, String> configuration = new HashMap<>();
        configuration.put("gcs.credential-provider", System.getProperty("rawdata.gcs.live.credential-provider", "application-default"));
        String keyFile = System.getProperty("rawdata.gcs.live.key-file");
        if (keyFile != null) {
            configuration.put("gcs.service-account.key-file", keyFile);
        }
        return configuration;
    }

    private static String bufferFolder() {
        try {
            return Files.createTempDirectory("rawdata-buffer").toString(); // a folder per client, as per process
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Removes everything under a prefix of this run, in every sub-folder. A failure is reported, not thrown: what is
     * left is removed by the bucket's lifecycle rule.
     */
    static void remove(String prefix) {
        try {
            removeOrFail(prefix);
        } catch (IOException | RuntimeException e) {
            System.err.println("Removing gs://" + bucket() + "/" + RUN + prefix + " failed, left to the bucket's lifecycle: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void removeOrFail(String prefix) throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        GcsCredentials credentials = GcsRawdataClientInitializer.loadCredentials(credentialsConfiguration(), http);
        GcsClient gcs = new GcsClient(http, GcsClient.DEFAULT_ENDPOINT, credentials);
        String bucket = bucket();
        String pageToken = null;
        do {
            String uri = GcsClient.DEFAULT_ENDPOINT + "/storage/v1/b/" + GcsClient.encode(bucket) + "/o?prefix="
                    + GcsClient.encode(RUN + prefix) + "&fields=" + GcsClient.encode("items(name),nextPageToken")
                    + (pageToken == null ? "" : "&pageToken=" + GcsClient.encode(pageToken));
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(uri)).GET();
            credentials.authorization().ifPresent(value -> request.header("Authorization", value));
            HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("Listing " + RUN + prefix + " failed: HTTP " + response.statusCode());
            }
            JsonNode page = new ObjectMapper().readTree(response.body());
            for (JsonNode item : page.path("items")) {
                gcs.delete(bucket, item.path("name").asText());
            }
            JsonNode next = page.path("nextPageToken");
            pageToken = next.isTextual() && !next.asText().isEmpty() ? next.asText() : null;
        } while (pageToken != null);
    }
}
