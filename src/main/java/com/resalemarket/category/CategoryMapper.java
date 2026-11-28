package com.resalemarket.category;

import com.resalemarket.category.dto.CategoryResponse;
import com.resalemarket.category.dto.CategorySummary;
import com.resalemarket.category.entity.Category;
import java.util.List;
import org.mapstruct.Mapper;

/** Entity -> DTO mapping; the build fails on a DTO property that is not mapped. */
@Mapper(componentModel = "spring")
public interface CategoryMapper {

    CategoryResponse toResponse(Category category, List<CategoryResponse> children);

    CategorySummary toSummary(Category category);
}
