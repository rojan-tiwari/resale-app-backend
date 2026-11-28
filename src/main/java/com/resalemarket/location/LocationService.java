package com.resalemarket.location;

import com.resalemarket.common.exception.FieldValidationException;
import com.resalemarket.common.exception.ResourceNotFoundException;
import com.resalemarket.location.dto.DistrictResponse;
import com.resalemarket.location.dto.LocationNames;
import com.resalemarket.location.dto.LocationRef;
import com.resalemarket.location.dto.MunicipalityResponse;
import com.resalemarket.location.dto.ProvinceResponse;
import com.resalemarket.location.entity.District;
import com.resalemarket.location.entity.Municipality;
import com.resalemarket.location.entity.Province;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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

    /**
     * The names behind many places at once, for lists of ads: three queries however many places there are, instead of up to
     * three per ad. The ids are trusted to be consistent (the database guarantees it with composite foreign keys), so unlike
     * {@link #resolve} this does not check that a district belongs to its province; an id that does not exist gives a {@code null} name.
     */
    public Map<LocationRef, LocationNames> describe(Collection<LocationRef> places) {
        Set<Integer> provinceIds = new HashSet<>();
        Set<Integer> districtIds = new HashSet<>();
        Set<Integer> municipalityIds = new HashSet<>();
        for (LocationRef place : places) {
            if (place.provinceId() != null) {
                provinceIds.add(place.provinceId());
            }
            if (place.districtId() != null) {
                districtIds.add(place.districtId());
            }
            if (place.municipalityId() != null) {
                municipalityIds.add(place.municipalityId());
            }
        }
        Map<Integer, String> provinces = provinceIds.isEmpty() ? Map.of()
                : provinceRepository.findAllById(provinceIds).stream().collect(Collectors.toMap(Province::getId, Province::getName));
        Map<Integer, String> districts = districtIds.isEmpty() ? Map.of()
                : districtRepository.findAllById(districtIds).stream().collect(Collectors.toMap(District::getId, District::getName));
        Map<Integer, String> municipalities = municipalityIds.isEmpty() ? Map.of()
                : municipalityRepository.findAllById(municipalityIds).stream()
                        .collect(Collectors.toMap(Municipality::getId, Municipality::getName));

        Map<LocationRef, LocationNames> names = new HashMap<>();
        for (LocationRef place : places) {
            names.computeIfAbsent(place, ref -> new LocationNames(
                    nameOf(provinces, ref.provinceId()), nameOf(districts, ref.districtId()), nameOf(municipalities, ref.municipalityId())));
        }
        return names;
    }

    private static String nameOf(Map<Integer, String> names, Integer id) {
        return id == null ? null : names.get(id);
    }
}
