package io.descoped.rawdata.provider.gcs;

import io.descoped.rawdata.provider.avro.AvroChecksums;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lost-response check of a segment upload, and timeouts as throttling, against a local server playing GCS.
 */
class GcsIntegrityTest {

    /**
     * A create that finds its segment taken looks at the object's size and CRC32C before it downloads anything:
     * another writer's segment costs one metadata request; a match is confirmed byte for byte.
     *
     * @throws Exception if the test fails unexpectedly
     */
    @Test
    void aTakenSegmentIsComparedByChecksumBeforeItIsDownloaded() throws Exception {
        byte[] mine = {1, 2, 3, 4};
        AtomicReference<byte[]> stored = new AtomicReference<>(new byte[]{9, 9, 9, 9});
        AtomicInteger downloads = new AtomicInteger();
        try (LocalHttp gcs = new LocalHttp(request -> {
            if (request.method().equals("POST")) {
                return LocalHttp.Response.json(412, "{}"); // taken
            }
            if (request.query() != null && request.query().contains("alt=media")) {
                downloads.incrementAndGet();
                return new LocalHttp.Response(200, new String(stored.get(), java.nio.charset.StandardCharsets.ISO_8859_1),
                        java.util.Map.of("Content-Type", "application/octet-stream"));
            }
            return LocalHttp.Response.json(200, "{\"generation\":\"5\",\"size\":\"" + stored.get().length
                    + "\",\"crc32c\":\"" + AvroChecksums.crc32c(stored.get()) + "\"}");
        })) {
            GcsAvroStore store = new GcsAvroStore(new GcsClient(gcs.root(), GcsCredentials.NONE), "b", "p/");
            assertFalse(store.create("t", 0, mine), "another writer's segment of the same size");
            assertEquals(0, downloads.get(), "told apart by its CRC32C, not downloaded");
            stored.set(mine.clone()); // our own upload, its response lost
            assertTrue(store.create("t", 0, mine));
            assertEquals(1, downloads.get(), "a match is confirmed byte for byte");
        }
    }

    /**
     * A segment upload that times out is reported as throttling, on each attempt, and fails once the attempts are
     * used up; another request that times out is not reported.
     */
    @Test
    void aTimedOutSegmentUploadIsReportedAsThrottling() {
        AtomicInteger throttled = new AtomicInteger();
        AtomicInteger waits = new AtomicInteger();
        GcsClient client = new GcsClient(new TimingOut(), "http://localhost", GcsCredentials.NONE, millis -> waits.incrementAndGet());
        client.onThrottled(throttled::incrementAndGet);
        assertThrows(HttpTimeoutException.class, () -> client.upload("b", "s", "avro/binary", new byte[]{1}, 0, true));
        assertEquals(6, throttled.get(), "every attempt");
        assertEquals(5, waits.get(), "a back-off between attempts, none after the last");
        assertThrows(HttpTimeoutException.class, () -> client.generation("b", "o"));
        assertEquals(6, throttled.get(), "not a read");
    }

    /**
     * A metadata write whose entry changes under every write gives up after a bounded number of rounds, naming the
     * object, instead of trying forever; one that succeeds after a few rounds writes once.
     *
     * @throws Exception if the test fails unexpectedly
     */
    @Test
    void aMetadataWriteThatKeepsLosingGivesUp() throws Exception {
        AtomicInteger uploads = new AtomicInteger();
        AtomicInteger succeedAt = new AtomicInteger(Integer.MAX_VALUE);
        try (LocalHttp gcs = new LocalHttp(request -> {
            if (request.method().equals("POST")) {
                return LocalHttp.Response.json(uploads.incrementAndGet() >= succeedAt.get() ? 200 : 412, "{}");
            }
            return LocalHttp.Response.json(200, "{\"generation\":\"" + uploads.get() + "\"}");
        })) {
            GcsAvroStore store = new GcsAvroStore(new GcsClient(gcs.root(), GcsCredentials.NONE), "b", "p/");
            byte[] value = {1};
            IOException lost = assertThrows(IOException.class, () -> store.writeMetadata("t", "k", value));
            assertTrue(lost.getMessage().contains("p/t/metadata/k"), lost.getMessage());
            assertEquals(10, uploads.get());
            uploads.set(0);
            succeedAt.set(3);
            store.writeMetadata("t", "k", value);
            assertEquals(3, uploads.get(), "written at the third round");
        }
    }

    /**
     * An HTTP client whose every request times out.
     */
    private static final class TimingOut extends HttpClient {

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
            throw new HttpTimeoutException("request timed out");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            return CompletableFuture.failedFuture(new HttpTimeoutException("request timed out"));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                                                HttpResponse.PushPromiseHandler<T> push) {
            return sendAsync(request, handler);
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return null;
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }
    }
}
