package com.resalemarket.category;

import com.resalemarket.category.entity.Category;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Integer> {

    /** Every active category, roots and children together: the whole tree is about a hundred rows. */
    List<Category> findByActiveTrueOrderBySortOrderAscIdAsc();
}
