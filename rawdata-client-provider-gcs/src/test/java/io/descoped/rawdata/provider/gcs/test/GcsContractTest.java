package io.descoped.rawdata.provider.gcs.test;

import io.descoped.rawdata.tck.avro.AvroClientContractTest;

import java.util.Map;

class GcsContractTest extends AvroClientContractTest {

    private static final String PREFIX = "contract/";

    @Override
    protected Map<String, String> configuration() {
        return FakeGcs.configuration(PREFIX);
    }

    @Override
    protected Map<String, String> missingStoreConfiguration() {
        Map<String, String> configuration = FakeGcs.configuration(PREFIX);
        configuration.put("gcs.bucket-name", "no-such-bucket");
        return configuration;
    }
}
