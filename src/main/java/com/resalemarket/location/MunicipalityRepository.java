package com.resalemarket.location;

import com.resalemarket.location.entity.Municipality;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MunicipalityRepository extends JpaRepository<Municipality, Integer> {

    /** Major cities first, then alphabetical. */
    List<Municipality> findByDistrictIdOrderByMajorDescNameAsc(Integer districtId);
}
