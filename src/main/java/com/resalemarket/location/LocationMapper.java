package com.resalemarket.location;

import com.resalemarket.location.dto.DistrictResponse;
import com.resalemarket.location.dto.MunicipalityResponse;
import com.resalemarket.location.dto.ProvinceResponse;
import com.resalemarket.location.entity.District;
import com.resalemarket.location.entity.Municipality;
import com.resalemarket.location.entity.Province;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * Entity -> DTO mapping (MapStruct generates the implementation at compile time).
 * The build fails on any unmapped DTO property, so adding a field to a response without mapping it cannot slip through.
 * Entities are never exposed by controllers.
 */
@Mapper(componentModel = "spring")
public interface LocationMapper {

    ProvinceResponse toResponse(Province province);

    DistrictResponse toResponse(District district);

    MunicipalityResponse toResponse(Municipality municipality);

    List<ProvinceResponse> toProvinceResponses(List<Province> provinces);

    List<DistrictResponse> toDistrictResponses(List<District> districts);

    List<MunicipalityResponse> toMunicipalityResponses(List<Municipality> municipalities);
}
