package io.descoped.rawdata.provider.postgres;

import io.descoped.rawdata.api.RawdataMetadataClient;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A topic's metadata as rows of its metadata table. A key is at most {@link RawdataMetadataClient#MAX_KEY_BYTES}
 * bytes, as in every provider, well below what a btree index entry holds. Reading or removing the metadata of a topic
 * not written yet finds nothing and creates nothing; the first {@link #put} creates the topic.
 */
final class PostgresRawdataMetadataClient implements RawdataMetadataClient {

    private final PostgresRawdataClient client;
    private final String topic;

    PostgresRawdataMetadataClient(PostgresRawdataClient client, String topic) {
        this.client = client;
        this.topic = topic;
    }

    @Override
    public String topic() {
        return topic;
    }

    @Override
    public Set<String> keys() {
        client.checkOpen();
        PostgresTopics.Topic tables = client.topics().find(topic);
        if (tables == null) {
            return Set.of();
        }
        return client.jdbc().transaction("read the metadata keys of topic " + topic, connection -> {
            try (PreparedStatement select = connection.prepareStatement("SELECT name FROM " + tables.metadata() + " ORDER BY name");
                 ResultSet rows = select.executeQuery()) {
                Set<String> keys = new LinkedHashSet<>();
                while (rows.next()) {
                    keys.add(rows.getString(1));
                }
                return Collections.unmodifiableSet(keys); // sorted by name
            }
        });
    }

    @Override
    public byte[] get(String key) {
        RawdataMetadataClient.requireValidKey(key);
        client.checkOpen();
        PostgresTopics.Topic tables = client.topics().find(topic);
        if (tables == null) {
            return null;
        }
        return client.jdbc().transaction("read metadata of topic " + topic, connection -> {
            try (PreparedStatement select = connection.prepareStatement("SELECT data FROM " + tables.metadata() + " WHERE name = ?")) {
                select.setString(1, key);
                try (ResultSet rows = select.executeQuery()) {
                    return rows.next() ? rows.getBytes(1) : null;
                }
            }
        });
    }

    @Override
    public RawdataMetadataClient put(String key, byte[] value) {
        RawdataMetadataClient.requireValidKey(key);
        Objects.requireNonNull(value, "value");
        client.checkOpen();
        byte[] copy = value.clone();
        PostgresTopics.Topic tables = client.topics().create(topic);
        client.jdbc().transaction("write metadata of topic " + topic, connection -> {
            try (PreparedStatement upsert = connection.prepareStatement("INSERT INTO " + tables.metadata()
                    + " (name, data) VALUES (?, ?) ON CONFLICT (name) DO UPDATE SET data = EXCLUDED.data")) {
                upsert.setString(1, key);
                upsert.setBytes(2, copy);
                upsert.executeUpdate();
            }
            return null;
        });
        return this;
    }

    @Override
    public RawdataMetadataClient remove(String key) {
        RawdataMetadataClient.requireValidKey(key);
        client.checkOpen();
        PostgresTopics.Topic tables = client.topics().find(topic);
        if (tables == null) {
            return this;
        }
        client.jdbc().transaction("remove metadata of topic " + topic, connection -> {
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM " + tables.metadata() + " WHERE name = ?")) {
                delete.setString(1, key);
                delete.executeUpdate();
            }
            return null;
        });
        return this;
    }
}
