package io.descoped.rawdata.provider.gcs;

import io.descoped.rawdata.tck.avro.AvroClientContractTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Map;
import java.util.UUID;

/**
 * The contract against real Google Cloud Storage; see {@link LiveGcs}.
 */
@EnabledIfSystemProperty(named = LiveGcs.ENABLED, matches = "true")
class GcsLiveContractTest extends AvroClientContractTest {

    private static final String PREFIX = "contract/";

    @Override
    protected Map<String, String> configuration() {
        return LiveGcs.configuration(PREFIX);
    }

    /**
     * A bucket name nobody has; GCS answers 404 (or 403, should someone own it), both failures.
     */
    @Override
    protected Map<String, String> missingStoreConfiguration() {
        Map<String, String> configuration = LiveGcs.configuration(PREFIX);
        configuration.put("gcs.bucket-name", "descoped-no-such-bucket-" + UUID.randomUUID());
        return configuration;
    }

    @AfterAll
    static void removeTheTestObjects() {
        LiveGcs.remove(PREFIX);
    }
}
