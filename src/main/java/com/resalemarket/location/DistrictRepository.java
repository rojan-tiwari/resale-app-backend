package com.resalemarket.location;

import com.resalemarket.location.entity.District;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DistrictRepository extends JpaRepository<District, Integer> {

    List<District> findByProvinceIdOrderByNameAsc(Integer provinceId);
}
