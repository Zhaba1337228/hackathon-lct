package ru.lct.heatnetwork.jobs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import ru.lct.heatnetwork.domain.Job;
import ru.lct.heatnetwork.input.UploadResult;

@Repository
public class PostgresJobRepository implements JobRepository {
    private static final String COLUMNS = "id,status,failure_stage,created_at,updated_at,received_bytes,"
            + "artifacts_purged_at,artifact_cleanup_attempted_at,feature_count,counts_by_type::text,"
            + "error_code,error_message,error_feature_id,error_field_path";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PostgresJobRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void create(Job job) {
        jdbc.update("INSERT INTO job(id,status,created_at,updated_at) VALUES (?,?,?,?)",
                job.getId(), job.getStatus().name(), timestamp(job.getCreatedAt()), timestamp(job.getUpdatedAt()));
    }

    @Override
    public boolean markReady(UUID id, UploadResult upload, Instant updatedAt) {
        int changed = jdbc.update("UPDATE job SET status='READY',updated_at=?,received_bytes=?,feature_count=?,counts_by_type=?::jsonb "
                        + "WHERE id=? AND status='VALIDATING'",
                timestamp(updatedAt), upload.getReceivedBytes(), upload.getFeatureCount(),
                writeCounts(upload.getCountsByType()), id);
        return changed == 1;
    }

    @Override
    public boolean markValidationFailed(UUID id, String code, String message,
            String featureId, String fieldPath, Instant updatedAt) {
        int changed = jdbc.update("UPDATE job SET status='FAILED',failure_stage='VALIDATION',updated_at=?,"
                        + "error_code=?,error_message=?,error_feature_id=?,error_field_path=? "
                        + "WHERE id=? AND status='VALIDATING'",
                timestamp(updatedAt), code, message, featureId, fieldPath, id);
        return changed == 1;
    }

    @Override
    public boolean markCalculating(UUID id, String bboxJson, Instant updatedAt) {
        int changed = jdbc.update("UPDATE job SET status='CALCULATING',failure_stage=NULL,updated_at=?,"
                        + "calculation_bbox=?::jsonb,calculation_result=NULL,error_code=NULL,error_message=NULL,"
                        + "error_feature_id=NULL,error_field_path=NULL "
                        + "WHERE id=? AND status='READY' AND artifacts_purged_at IS NULL",
                timestamp(updatedAt), bboxJson, id);
        return changed == 1;
    }

    @Override
    public boolean markCalculationSucceeded(UUID id, String resultJson, Instant updatedAt) {
        int changed = jdbc.update("UPDATE job SET status='SUCCEEDED',failure_stage=NULL,updated_at=?,"
                        + "calculation_result=?::jsonb,error_code=NULL,error_message=NULL "
                        + "WHERE id=? AND status='CALCULATING'",
                timestamp(updatedAt), resultJson, id);
        return changed == 1;
    }

    @Override
    public boolean markCalculationFailed(UUID id, String code, String message, Instant updatedAt) {
        int changed = jdbc.update("UPDATE job SET status='FAILED',failure_stage='CALCULATION',updated_at=?,"
                        + "calculation_result=NULL,error_code=?,error_message=?,error_feature_id=NULL,error_field_path=NULL "
                        + "WHERE id=? AND status='CALCULATING'",
                timestamp(updatedAt), code, message, id);
        return changed == 1;
    }

    @Override
    public int failInterruptedCalculations(String code, String message, Instant updatedAt) {
        return jdbc.update("UPDATE job SET status='FAILED',failure_stage='CALCULATION',updated_at=?,"
                        + "calculation_result=NULL,error_code=?,error_message=?,error_feature_id=NULL,error_field_path=NULL "
                        + "WHERE status='CALCULATING'",
                timestamp(updatedAt), code, message);
    }

    @Override
    public Optional<String> findCalculationResult(UUID id) {
        return jdbc.query("SELECT calculation_result::text FROM job WHERE id=? AND calculation_result IS NOT NULL",
                (row, index) -> row.getString(1), id).stream().findFirst();
    }

    @Override
    public Optional<Job> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM job WHERE id=?", JOB_ROW, id).stream().findFirst();
    }

    @Override
    public List<UUID> findExpiredArtifacts(Instant completedBefore, Instant retryBefore, int limit) {
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
        return jdbc.query("SELECT id FROM job WHERE status IN ('SUCCEEDED','FAILED','CANCELLED') "
                        + "AND updated_at<=? AND artifacts_purged_at IS NULL "
                        + "AND (artifact_cleanup_attempted_at IS NULL OR artifact_cleanup_attempted_at<=?) "
                        + "ORDER BY updated_at,id LIMIT ?",
                (row, index) -> row.getObject("id", UUID.class),
                timestamp(completedBefore), timestamp(retryBefore), limit);
    }

    @Override
    public boolean claimArtifactCleanup(UUID id, Instant completedBefore, Instant retryBefore, Instant attemptedAt) {
        int changed = jdbc.update("UPDATE job SET artifact_cleanup_attempted_at=? "
                        + "WHERE id=? AND status IN ('SUCCEEDED','FAILED','CANCELLED') AND updated_at<=? "
                        + "AND artifacts_purged_at IS NULL "
                        + "AND (artifact_cleanup_attempted_at IS NULL OR artifact_cleanup_attempted_at<=?)",
                timestamp(attemptedAt), id, timestamp(completedBefore), timestamp(retryBefore));
        return changed == 1;
    }

    @Override
    public boolean markArtifactsPurged(UUID id, Instant completedBefore, Instant purgedAt) {
        int changed = jdbc.update("UPDATE job SET artifacts_purged_at=?,calculation_result=NULL,calculation_bbox=NULL "
                        + "WHERE id=? AND status IN ('SUCCEEDED','FAILED','CANCELLED') AND updated_at<=? "
                        + "AND artifacts_purged_at IS NULL",
                timestamp(purgedAt), id, timestamp(completedBefore));
        return changed == 1;
    }

    private String writeCounts(Map<String, Long> counts) {
        try {
            return mapper.writeValueAsString(counts);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot serialize job feature counts", exception);
        }
    }

    private static Timestamp timestamp(Instant instant) { return Timestamp.from(instant); }

    private static final RowMapper<Job> JOB_ROW = new RowMapper<Job>() {
        @Override
        public Job mapRow(ResultSet row, int rowNumber) throws SQLException {
            try {
                String failureStage = row.getString("failure_stage");
                Map<String, Long> counts = MAPPER.readValue(row.getString("counts_by_type"),
                        new TypeReference<LinkedHashMap<String, Long>>() { });
                return new Job(row.getObject("id", UUID.class),
                        Job.Status.valueOf(row.getString("status")),
                        failureStage == null ? null : Job.FailureStage.valueOf(failureStage),
                        row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant(),
                        row.getTimestamp("artifacts_purged_at") == null ? null
                                : row.getTimestamp("artifacts_purged_at").toInstant(),
                        row.getTimestamp("artifact_cleanup_attempted_at") == null ? null
                                : row.getTimestamp("artifact_cleanup_attempted_at").toInstant(),
                        row.getLong("received_bytes"), row.getLong("feature_count"), counts,
                        row.getString("error_code"), row.getString("error_message"),
                        row.getString("error_feature_id"), row.getString("error_field_path"));
            } catch (Exception exception) {
                throw new SQLException("Cannot map persisted job state", exception);
            }
        }
    };

    private static final ObjectMapper MAPPER = new ObjectMapper();
}
