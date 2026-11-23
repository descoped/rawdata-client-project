package io.descoped.rawdata.api;

import java.util.UUID;

/**
 * Thrown by a publish that gives an id already taken by a different message: in the topic, or earlier in the same
 * call. An id is unique within a topic. Publishing the same message again (the same id, position, ordering group,
 * sequence number, and content keys and bytes in the same order) is a no-op, so a writer that recovers can repeat its
 * last publish; a different message under a taken id is refused, and nothing of the call is written.
 */
public class RawdataDuplicateIdException extends RawdataException {

    private static final long serialVersionUID = 1L;

    /**
     * The topic.
     */
    private final String topic;

    /**
     * The id that is taken.
     */
    private final UUID id;

    /**
     * Creates the exception.
     *
     * @param topic the topic
     * @param id    the id that is taken
     */
    public RawdataDuplicateIdException(String topic, UUID id) {
        super("Id " + id + " is taken in topic '" + topic + "' by another message");
        this.topic = topic;
        this.id = id;
    }

    /**
     * Returns the topic.
     *
     * @return the topic
     */
    public String topic() {
        return topic;
    }

    /**
     * Returns the id that is taken.
     *
     * @return the id
     */
    public UUID id() {
        return id;
    }
}
