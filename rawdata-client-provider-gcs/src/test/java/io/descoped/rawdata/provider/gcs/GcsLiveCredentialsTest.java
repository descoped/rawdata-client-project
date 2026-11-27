package io.descoped.rawdata.provider.gcs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Token refresh against Google with the credentials of the live tests ({@link LiveGcs}): a token that expires is
 * fetched again, and a token GCS refuses (401) is fetched again and the call goes on. No token is logged or shown in
 * an assertion message.
 */
@EnabledIfSystemProperty(named = LiveGcs.ENABLED, matches = "true")
class GcsLiveCredentialsTest {

    private static final String PREFIX = "credentials/";
    private static final byte[] CONTENT = "synthetic".getBytes(StandardCharsets.UTF_8);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /**
     * A clock that can be moved forward.
     */
    private static final class MovableClock extends Clock {
        private volatile Duration offset = Duration.ZERO;

        void advance(Duration duration) {
            offset = offset.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.now().plus(offset);
        }
    }

    /**
     * A token past its time is fetched again from Google, and the new one works.
     *
     * @throws IOException if GCS fails
     */
    @Test
    void anExpiredTokenIsFetchedAgain() throws IOException {
        MovableClock clock = new MovableClock();
        GcsCredentials credentials = authorizedUser(clock);
        GcsClient gcs = new GcsClient(http, GcsClient.DEFAULT_ENDPOINT, credentials);
        String object = LiveGcs.RUN + PREFIX + "expired";
        assertTrue(gcs.upload(LiveGcs.bucket(), object, "application/octet-stream", CONTENT, 0, false));
        String first = credentials.authorization().orElseThrow();
        clock.advance(Duration.ofHours(2));
        String second = credentials.authorization().orElseThrow();
        assertFalse(first.equals(second), "a new token after the old one's time"); // never shows the tokens
        assertArrayEquals(CONTENT, gcs.download(LiveGcs.bucket(), object).orElseThrow());
    }

    /**
     * A token GCS refuses (401: revoked, or the clock is off) is dropped, a new one is fetched, and the call goes on.
     *
     * @throws IOException if GCS fails
     */
    @Test
    void aRefusedTokenIsFetchedAgainAndTheCallGoesOn() throws IOException {
        GcsCredentials real = authorizedUser(Clock.systemUTC());
        AtomicInteger invalidated = new AtomicInteger();
        GcsCredentials refusedFirst = new GcsCredentials() {
            @Override
            Optional<String> authorization() throws IOException {
                return invalidated.get() == 0 ? Optional.of("Bearer refused-by-gcs") : real.authorization();
            }

            @Override
            void invalidate() {
                invalidated.incrementAndGet();
                real.invalidate();
            }
        };
        GcsClient gcs = new GcsClient(http, GcsClient.DEFAULT_ENDPOINT, refusedFirst);
        String object = LiveGcs.RUN + PREFIX + "refused";
        assertTrue(gcs.upload(LiveGcs.bucket(), object, "application/octet-stream", CONTENT, 0, false));
        assertEquals(1, invalidated.get());
        assertArrayEquals(CONTENT, gcs.download(LiveGcs.bucket(), object).orElseThrow());
    }

    /**
     * The gcloud user's credentials of the live tests, with the given clock.
     */
    private GcsCredentials authorizedUser(Clock clock) throws IOException {
        String file = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
        if (file == null) {
            throw new IllegalStateException("The credentials tests need GOOGLE_APPLICATION_CREDENTIALS");
        }
        JsonNode json = new ObjectMapper().readTree(Files.readAllBytes(Path.of(file)));
        if (!"authorized_user".equals(json.path("type").asText())) {
            throw new IllegalStateException("The credentials tests need a gcloud user's credentials (authorized_user)");
        }
        return new GcsCredentials.AuthorizedUser(json.path("client_id").asText(), json.path("client_secret").asText(),
                json.path("refresh_token").asText(), json.path("token_uri").asText(GcsCredentials.GOOGLE_TOKEN_URI), http, clock);
    }

    @AfterAll
    static void removeTheTestObjects() throws IOException, InterruptedException {
        LiveGcs.remove(PREFIX);
    }
}
