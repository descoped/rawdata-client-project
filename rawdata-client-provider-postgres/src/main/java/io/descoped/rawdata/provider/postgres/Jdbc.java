package io.descoped.rawdata.provider.postgres;

import io.descoped.rawdata.api.RawdataClosedException;
import io.descoped.rawdata.api.RawdataException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.BooleanSupplier;

/**
 * Runs work in a transaction on a pooled connection (auto-commit off, READ COMMITTED): commits when the work returns,
 * rolls back when it throws. A rollback failure is added to the original exception, never hides it. SQL exceptions
 * leave as {@link RawdataException} whose message names the operation and the SQLState, with the SQL exception as the
 * cause; as {@link RawdataClosedException} when the client closed meanwhile (closing it aborts the pool's
 * connections). (On an {@link Error} the pool rolls back when the connection is returned.)
 */
final class Jdbc {

    /**
     * Work on a connection.
     *
     * @param <T> the result type
     */
    @FunctionalInterface
    interface Work<T> {
        T apply(Connection connection) throws SQLException;
    }

    private final DataSource dataSource;
    private final BooleanSupplier closed;

    Jdbc(DataSource dataSource, BooleanSupplier closed) {
        this.dataSource = dataSource;
        this.closed = closed;
    }

    /**
     * Runs work in a transaction.
     *
     * @param operation what the work does, for the message of a failure; names the topic, never a message's values
     * @param work      the work
     */
    <T> T transaction(String operation, Work<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            return commitOrRollback(connection, work);
        } catch (SQLException e) {
            if (closed.getAsBoolean()) {
                throw new RawdataClosedException("client closed during: " + operation, e);
            }
            throw new RawdataException("PostgreSQL failed to " + operation + " (SQLState " + e.getSQLState() + "): "
                    + firstLine(e.getMessage()), e);
        }
    }

    /**
     * The database's message without its detail lines, which may show a row's values; the cause keeps them.
     */
    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int end = message.indexOf('\n');
        return end < 0 ? message : message.substring(0, end);
    }

    private static <T> T commitOrRollback(Connection connection, Work<T> work) throws SQLException {
        try {
            T result = work.apply(connection);
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            rollback(connection, e);
            throw e;
        }
    }

    private static void rollback(Connection connection, Exception cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }
}
