package ru.lct.heatnetwork.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ru.lct.heatnetwork.api.CalculationResultResponse;
import ru.lct.heatnetwork.jobs.CalculationProgressSnapshot;
import ru.lct.heatnetwork.jobs.CalculationProgressStore;
import ru.lct.heatnetwork.jobs.JobCalculationService;
import ru.lct.heatnetwork.jobs.JobService;
import ru.lct.heatnetwork.output.GeoJsonJobExportService;

@RestController
public final class JobController {
    private final JobService jobService;
    private final JobCalculationService calculationService;
    private final GeoJsonJobExportService exportService;
    private final CalculationProgressStore progressStore;

    public JobController(JobService jobService, JobCalculationService calculationService,
            GeoJsonJobExportService exportService, CalculationProgressStore progressStore) {
        this.jobService = jobService;
        this.calculationService = calculationService;
        this.exportService = exportService;
        this.progressStore = progressStore;
    }

    @GetMapping("/api/jobs/{jobId}")
    @Operation(summary = "Get validation and calculation state for one job")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Job status and validation summary"),
            @ApiResponse(responseCode = "404", description = "Job does not exist")
    })
    public ResponseEntity<JobStatusResponse> get(@PathVariable UUID jobId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(JobStatusResponse.from(jobService.get(jobId)));
    }

    @GetMapping(path = "/api/jobs/{jobId}/progress", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get transient live route-search progress for one job")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current progress snapshot; it may be unavailable outside calculation"),
            @ApiResponse(responseCode = "404", description = "Job does not exist")
    })
    public ResponseEntity<CalculationProgressSnapshot> progress(@PathVariable UUID jobId) {
        jobService.get(jobId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(progressStore.snapshot(jobId));
    }

    @PostMapping(path = "/api/jobs/{jobId}/calculate", consumes = "application/json")
    @Operation(summary = "Start bounded heat-network routing for a validated job")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Calculation accepted"),
            @ApiResponse(responseCode = "400", description = "Invalid WGS84 bbox",
                    content = @Content(schema = @Schema(type = "object"))),
            @ApiResponse(responseCode = "404", description = "Job does not exist",
                    content = @Content(schema = @Schema(type = "object"))),
            @ApiResponse(responseCode = "409", description = "Job is not READY or calculation result is not ready",
                    content = @Content(schema = @Schema(type = "object"))),
            @ApiResponse(responseCode = "503", description = "Calculation queue is full",
                    content = @Content(schema = @Schema(type = "object")))
    })
    public ResponseEntity<JobStatusResponse> calculate(@PathVariable UUID jobId,
            @RequestBody CalculateRequest request) {
        JobStatusResponse status = JobStatusResponse.from(calculationService.start(jobId, request));
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .location(URI.create("/api/jobs/" + jobId)).body(status);
    }

    @GetMapping(path = "/api/jobs/{jobId}/variants", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get ranked summaries for a completed calculation")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Completed calculation result",
                    content = @Content(schema = @Schema(implementation = CalculationResultResponse.class))),
            @ApiResponse(responseCode = "404", description = "Job does not exist",
                    content = @Content(schema = @Schema(type = "object"))),
            @ApiResponse(responseCode = "409", description = "Calculation result is not ready",
                    content = @Content(schema = @Schema(type = "object"))),
            @ApiResponse(responseCode = "410", description = "Calculation result has expired",
                    content = @Content(schema = @Schema(type = "object")))
    })
    public ResponseEntity<CalculationResultResponse> variants(@PathVariable UUID jobId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(calculationService.result(jobId));
    }

    @GetMapping(path = "/api/jobs/{jobId}/export", produces = "application/geo+json")
    @Operation(summary = "Download the completed GeoJSON FeatureCollection for all ranked variants")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Completed GeoJSON export",
                    content = @Content(schema = @Schema(type = "string", format = "binary"))),
            @ApiResponse(responseCode = "404", description = "Job does not exist",
                    content = @Content(schema = @Schema(type = "object"))),
            @ApiResponse(responseCode = "409", description = "Export is not ready",
                    content = @Content(schema = @Schema(type = "object"))),
            @ApiResponse(responseCode = "410", description = "Export has expired",
                    content = @Content(schema = @Schema(type = "object")))
    })
    public ResponseEntity<StreamingResponseBody> export(@PathVariable UUID jobId) {
        GeoJsonJobExportService.Download download = exportService.openDownload(jobId);
        StreamingResponseBody body = output -> copy(download, output);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType("application/geo+json"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("heat-network-" + jobId + ".geojson").build().toString())
                .contentLength(download.getSize())
                .body(body);
    }

    private static void copy(GeoJsonJobExportService.Download download, OutputStream output) throws IOException {
        try (GeoJsonJobExportService.Download closeable = download) {
            InputStream input = closeable.getInput();
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
    }
}
