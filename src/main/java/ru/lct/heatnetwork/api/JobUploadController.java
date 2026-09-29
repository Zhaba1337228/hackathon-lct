package ru.lct.heatnetwork.api;

import java.io.IOException;
import javax.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.lct.heatnetwork.jobs.JobService;
import ru.lct.heatnetwork.input.UploadResult;

@RestController
public final class JobUploadController {
    private final JobService jobService;

    public JobUploadController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping(path = "/api/jobs", consumes = {MediaType.APPLICATION_JSON_VALUE, "application/geo+json"})
    @Operation(summary = "Upload and validate one GeoJSON dataset")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Input accepted and validation completed"),
            @ApiResponse(responseCode = "400", description = "GeoJSON validation failed"),
            @ApiResponse(responseCode = "413", description = "Input exceeds the configured byte limit")
    })
    public ResponseEntity<UploadResult> upload(HttpServletRequest request) throws IOException {
        return ResponseEntity.accepted().body(jobService.upload(request.getInputStream()));
    }
}
