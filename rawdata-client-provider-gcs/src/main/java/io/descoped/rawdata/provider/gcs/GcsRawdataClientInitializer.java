package io.descoped.rawdata.provider.gcs;

import io.descoped.rawdata.api.RawdataClient;
import io.descoped.rawdata.api.RawdataClientInitializer;
import io.descoped.rawdata.api.spi.RawdataLimits;
import io.descoped.rawdata.provider.avro.AvroConfiguration;
import io.descoped.rawdata.provider.avro.AvroRawdataClient;
import io.descoped.rawdata.provider.avro.AvroUploadWindow;
import io.descoped.service.provider.api.ProviderName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * The {@code gcs} provider: topics as Avro files in a Google Cloud Storage bucket, through the GCS JSON API on the
 * JDK's HTTP client.
 * <p>
 * Configuration:
 * <ul>
 *     <li>{@code gcs.bucket-name}: the bucket (required).</li>
 *     <li>{@code gcs.prefix}: a folder in the bucket for the topics, for example {@code rawdata/} (default none), at most
 *     {@link AvroConfiguration#MAX_PREFIX_BYTES} bytes.</li>
 *     <li>{@code gcs.credential-provider}, as in 2.x: {@code service-account} (default: a key file,
 *     {@code gcs.service-account.key-file}), {@code compute-engine} (the metadata server of a Google Cloud runtime:
 *     GCE, GKE, Cloud Run), {@code application-default} (Application Default Credentials: the key file in
 *     {@code GOOGLE_APPLICATION_CREDENTIALS}, gcloud's application default credentials, or the metadata server) or
 *     {@code none} (for an emulator). Read as dynamic-configuration reads enums: case-insensitive, {@code -} as
 *     {@code _}.</li>
 *     <li>{@code gcs.endpoint}: another endpoint than Google's, for example an emulator's
 *     {@code http://localhost:4443}.</li>
 *     <li>{@code gcs.listing.min-interval-seconds}: how often a waiting consumer looks for new messages, in seconds
 *     (default 1; 0 looks as often as a receive asks).</li>
 * </ul>
 * <p>
 * Producers buffer on a local disk and upload one Avro file per window, configured with the keys of
 * {@link AvroUploadWindow}: {@code local-temp-folder} (required), {@code avro-file.max.seconds},
 * {@code avro-file.max.bytes}, {@code avro-file.sync.interval}, {@code avro-file.max.pending-uploads},
 * {@code avro-file.close-timeout-seconds} and {@code avro-file.upload.max-concurrency}. A message is visible to
 * consumers once its window is uploaded.
 * <p>
 * A metadata key is at most {@link io.descoped.rawdata.api.RawdataMetadataClient#MAX_KEY_BYTES} bytes in UTF-8, as in
 * every provider; the prefix is limited so that such a key always fits in the 1024-byte object name.
 */
@ProviderName("gcs")
public final class GcsRawdataClientInitializer implements RawdataClientInitializer {

    static final String BUCKET = "gcs.bucket-name";
    static final String PREFIX = "gcs.prefix";
    static final String CREDENTIALS = "gcs.credential-provider";
    static final String KEY_FILE = "gcs.service-account.key-file";
    static final String ENDPOINT = "gcs.endpoint";
    static final String POLL_INTERVAL = "gcs.listing.min-interval-seconds";

    /**
     * The values of {@code gcs.credential-provider}.
     */
    enum CredentialProvider {
        SERVICE_ACCOUNT, COMPUTE_ENGINE, APPLICATION_DEFAULT, NONE
    }

    /**
     * Creates the initializer; called by the service loader.
     */
    public GcsRawdataClientInitializer() {
        // nothing to set up; the configuration comes with initialize
    }

    /**
     * The required configuration keys.
     *
     * @return the bucket and local temp folder keys
     */
    @Override
    public Set<String> configurationKeys() {
        return Set.of(BUCKET, AvroUploadWindow.LOCAL_TEMP_FOLDER);
    }

    @Override
    public RawdataClient initialize(Map<String, String> configuration) {
        RawdataLimits limits = RawdataLimits.of(configuration);
        String bucket = AvroConfiguration.required(configuration, BUCKET);
        String prefix = AvroConfiguration.prefix(configuration, PREFIX);
        long pollInterval = AvroConfiguration.pollIntervalMillis(configuration, POLL_INTERVAL, 1);
        AvroUploadWindow window = AvroUploadWindow.of(configuration);
        String endpoint = AvroConfiguration.optional(configuration, ENDPOINT, GcsClient.DEFAULT_ENDPOINT);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        GcsClient client = new GcsClient(http, endpoint, loadCredentials(configuration, http));
        return new AvroRawdataClient(new GcsAvroStore(client, bucket, prefix), window, limits, pollInterval);
    }

    static GcsCredentials loadCredentials(Map<String, String> configuration, HttpClient http) {
        CredentialProvider kind = AvroConfiguration.enumValue(configuration, CREDENTIALS, CredentialProvider.class,
                CredentialProvider.SERVICE_ACCOUNT);
        try {
            return switch (kind) {
                case SERVICE_ACCOUNT -> GcsCredentials.fromFile(Path.of(AvroConfiguration.required(configuration, KEY_FILE)), http);
                case COMPUTE_ENGINE -> GcsCredentials.computeEngine(System.getenv(), http);
                case APPLICATION_DEFAULT -> GcsCredentials.applicationDefault(System.getenv(), Path.of(System.getProperty("user.home")), http);
                case NONE -> GcsCredentials.NONE;
            };
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load the " + kind.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-')
                    + " credentials", e);
        }
    }
}
