package com.resalemarket.system;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
@Tag(name = "System", description = "Health and build information")
public class SystemController {

    private final SystemInfoService systemInfoService;

    public SystemController(SystemInfoService systemInfoService) {
        this.systemInfoService = systemInfoService;
    }

    @GetMapping("/ping")
    @Operation(
            summary = "Check that the API and database are reachable",
            description = "Public. Always answers 200 while the API is up; a database failure is reported "
                    + "in the payload (database.status = DOWN). Use /actuator/health for load balancers.")
    public ResponseEntity<SystemInfoResponse> ping() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(systemInfoService.getSystemInfo());
    }
}
