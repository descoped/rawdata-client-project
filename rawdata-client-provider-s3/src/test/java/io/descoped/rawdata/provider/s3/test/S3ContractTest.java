package io.descoped.rawdata.provider.s3.test;

import io.descoped.rawdata.tck.avro.AvroClientContractTest;

import java.util.Map;

class S3ContractTest extends AvroClientContractTest {

    private static final String PREFIX = "contract/";

    @Override
    protected Map<String, String> configuration() {
        return S3Mock.configuration(PREFIX);
    }

    @Override
    protected Map<String, String> missingStoreConfiguration() {
        Map<String, String> configuration = S3Mock.configuration(PREFIX);
        configuration.put("s3.bucket-name", "no-such-bucket");
        return configuration;
    }
}
