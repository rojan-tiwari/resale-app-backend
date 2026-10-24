package com.resalemarket.location;

import com.resalemarket.common.exception.ResourceNotFoundException;
import com.resalemarket.location.dto.DistrictResponse;
import com.resalemarket.location.dto.MunicipalityResponse;
import com.resalemarket.location.dto.ProvinceResponse;
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
}
