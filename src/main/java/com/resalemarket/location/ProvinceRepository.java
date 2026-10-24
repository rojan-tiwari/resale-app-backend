package com.resalemarket.location;

import com.resalemarket.location.entity.Province;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProvinceRepository extends JpaRepository<Province, Integer> {

    List<Province> findAllByOrderByIdAsc();
}
