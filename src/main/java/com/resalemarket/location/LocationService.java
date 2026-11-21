package com.resalemarket.location;

import com.resalemarket.common.exception.FieldValidationException;
import com.resalemarket.common.exception.ResourceNotFoundException;
import com.resalemarket.location.dto.DistrictResponse;
import com.resalemarket.location.dto.LocationNames;
import com.resalemarket.location.dto.MunicipalityResponse;
import com.resalemarket.location.dto.ProvinceResponse;
import com.resalemarket.location.entity.District;
import com.resalemarket.location.entity.Municipality;
import com.resalemarket.location.entity.Province;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only access to the structured Nepal location hierarchy (province -> district -> municipality). */
@Service
@Transactional(readOnly = true)
public class LocationService {

    private final ProvinceRepository provinceRepository;
    private final DistrictRepository districtRepository;
    private final MunicipalityRepository municipalityRepository;
    private final LocationMapper mapper;

    public LocationService(
            ProvinceRepository provinceRepository,
            DistrictRepository districtRepository,
            MunicipalityRepository municipalityRepository,
            LocationMapper mapper) {
        this.provinceRepository = provinceRepository;
        this.districtRepository = districtRepository;
        this.municipalityRepository = municipalityRepository;
        this.mapper = mapper;
    }

    public List<ProvinceResponse> getProvinces() {
        return mapper.toProvinceResponses(provinceRepository.findAllByOrderByIdAsc());
    }

    public List<DistrictResponse> getDistricts(Integer provinceId) {
        if (!provinceRepository.existsById(provinceId)) {
            throw new ResourceNotFoundException("Province", provinceId);
        }
        return mapper.toDistrictResponses(districtRepository.findByProvinceIdOrderByNameAsc(provinceId));
    }

    public List<MunicipalityResponse> getMunicipalities(Integer districtId) {
        if (!districtRepository.existsById(districtId)) {
            throw new ResourceNotFoundException("District", districtId);
        }
        return mapper.toMunicipalityResponses(
                municipalityRepository.findByDistrictIdOrderByMajorDescNameAsc(districtId));
    }

    /**
     * Checks that a province / district / municipality choice makes sense and returns the names behind it. Any of the three may
     * be {@code null} (a person who has not said where they are), but a district needs its province and a municipality its
     * district, and each must belong to the one above it. The database enforces the same with composite foreign keys; this
     * check exists to answer with a message the person can act on instead of a constraint violation.
     *
     * @throws FieldValidationException naming the field to fix ({@code provinceId}, {@code districtId} or {@code municipalityId})
     */
    public LocationNames resolve(Integer provinceId, Integer districtId, Integer municipalityId) {
        if (provinceId == null && districtId == null && municipalityId == null) {
            return LocationNames.NONE;
        }
        if (provinceId == null) {
            throw new FieldValidationException("provinceId", "Choose a province first.");
        }
        Province province = provinceRepository.findById(provinceId)
                .orElseThrow(() -> new FieldValidationException("provinceId", "This province does not exist."));

        if (districtId == null) {
            if (municipalityId != null) {
                throw new FieldValidationException("districtId", "Choose a district first.");
            }
            return new LocationNames(province.getName(), null, null);
        }
        District district = districtRepository.findById(districtId)
                .filter(found -> found.getProvinceId().equals(provinceId))
                .orElseThrow(() -> new FieldValidationException("districtId", "This district is not in the chosen province."));

        if (municipalityId == null) {
            return new LocationNames(province.getName(), district.getName(), null);
        }
        Municipality municipality = municipalityRepository.findById(municipalityId)
                .filter(found -> found.getDistrictId().equals(districtId))
                .orElseThrow(() -> new FieldValidationException(
                        "municipalityId", "This municipality is not in the chosen district."));
        return new LocationNames(province.getName(), district.getName(), municipality.getName());
    }
}
