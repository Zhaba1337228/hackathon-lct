package ru.lct.heatnetwork.api;

import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.lct.heatnetwork.jobs.MapViewService;

@RestController
public final class MapViewController {
    private final MapViewService service;

    public MapViewController(MapViewService service) {
        this.service = service;
    }

    @GetMapping(path = "/api/jobs/{jobId}/view/extent", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get the WGS84 extent for opening an uploaded dataset on the map")
    public ResponseEntity<MapExtentResponse> extent(@PathVariable UUID jobId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.extent(jobId));
    }

    @GetMapping(path = "/api/jobs/{jobId}/view", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Read input and selected result features intersecting a WGS84 viewport")
    public ResponseEntity<MapViewResponse> view(
            @PathVariable UUID jobId,
            @Parameter(description = "WGS84 bounds [west,south,east,north]") @RequestParam double[] bbox,
            @RequestParam(required = false) String variantId,
            @RequestParam(defaultValue = "500") int limit,
            @RequestParam(required = false) String inputCursor,
            @RequestParam(required = false) String variantCursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.view(jobId, bbox, variantId, limit, inputCursor, variantCursor));
    }
}
