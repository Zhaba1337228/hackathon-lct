package ru.lct.heatnetwork.input;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;
import ru.lct.heatnetwork.domain.TypedId;

/** Uses a job-scoped PostgreSQL temp table so ID tracking does not grow with input size in heap. */
@Component
public final class PostgresFeatureIdRegistryFactory implements FeatureIdRegistryFactory {
    private final DataSource dataSource;

    public PostgresFeatureIdRegistryFactory(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public FeatureIdRegistry create() {
        try {
            return new Registry(dataSource.getConnection());
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot allocate the per-job feature ID index", exception);
        }
    }

    private static final class Registry implements FeatureIdRegistry {
        private final Connection connection;
        private final PreparedStatement insert;
        private int sinceCommit;
        private boolean completed;

        private Registry(Connection connection) throws SQLException {
            this.connection = connection;
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TEMP TABLE heatnet_input_ids ("
                        + "id_kind CHAR(1) NOT NULL, id_value TEXT NOT NULL, "
                        + "PRIMARY KEY (id_kind, id_value)) ON COMMIT PRESERVE ROWS");
            }
            this.insert = connection.prepareStatement(
                    "INSERT INTO heatnet_input_ids (id_kind, id_value) VALUES (?, ?)");
        }

        @Override
        public void register(TypedId id) {
            try {
                insert.setString(1, id.isNumeric() ? "N" : "S");
                insert.setString(2, id.isNumeric() ? id.asNumber().toPlainString() : id.asString());
                insert.executeUpdate();
                if (++sinceCommit >= 5000) {
                    connection.commit();
                    sinceCommit = 0;
                }
            } catch (SQLException exception) {
                if ("23505".equals(exception.getSQLState())) throw new DuplicateInputIdException();
                throw new IllegalStateException("Cannot index a feature ID", exception);
            }
        }

        @Override
        public void complete() {
            try {
                connection.commit();
                try (Statement statement = connection.createStatement()) {
                    statement.execute("DROP TABLE heatnet_input_ids");
                }
                connection.commit();
                completed = true;
            } catch (SQLException exception) {
                throw new IllegalStateException("Cannot finalize the feature ID index", exception);
            }
        }

        @Override
        public void close() {
            try {
                insert.close();
                if (!completed) {
                    connection.rollback();
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("DROP TABLE IF EXISTS heatnet_input_ids");
                    }
                    connection.commit();
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Cannot clean up the feature ID index", exception);
            } finally {
                try {
                    connection.close();
                } catch (SQLException exception) {
                    throw new IllegalStateException("Cannot close the feature ID index", exception);
                }
            }
        }
    }
}
