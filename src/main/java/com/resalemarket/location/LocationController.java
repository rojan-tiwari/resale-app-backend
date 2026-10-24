package com.resalemarket.location;

import com.resalemarket.location.dto.DistrictResponse;
import com.resalemarket.location.dto.MunicipalityResponse;
import com.resalemarket.location.dto.ProvinceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public, cacheable reference data used by location pickers and search filters. */
@RestController
@RequestMapping("/api/v1/locations")
@Tag(name = "Locations", description = "Nepal provinces, districts and municipalities")
public class LocationController {

    private static final CacheControl CACHE_ONE_HOUR = CacheControl.maxAge(Duration.ofHours(1)).cachePublic();

    private final LocationService locationService;

    public LocationController(LocationService locationService) {
        this.locationService = locationService;
    }

    @GetMapping("/provinces")
    @Operation(summary = "List all 7 provinces")
    public ResponseEntity<List<ProvinceResponse>> provinces() {
        return cached(locationService.getProvinces());
    }

    @GetMapping("/provinces/{provinceId}/districts")
    @Operation(summary = "List the districts of a province")
    public ResponseEntity<List<DistrictResponse>> districts(@PathVariable Integer provinceId) {
        return cached(locationService.getDistricts(provinceId));
    }

    @GetMapping("/districts/{districtId}/municipalities")
    @Operation(summary = "List the municipalities of a district (major cities first)")
    public ResponseEntity<List<MunicipalityResponse>> municipalities(@PathVariable Integer districtId) {
        return cached(locationService.getMunicipalities(districtId));
    }

    private static <T> ResponseEntity<T> cached(T body) {
        return ResponseEntity.ok().cacheControl(CACHE_ONE_HOUR).body(body);
    }
}
