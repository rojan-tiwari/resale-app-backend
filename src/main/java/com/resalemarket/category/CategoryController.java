package com.resalemarket.category;

import com.resalemarket.category.dto.CategoryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public, cacheable category tree used by the pickers, the filters and the category pages. */
@RestController
@RequestMapping("/api/v1/categories")
@Tag(name = "Categories", description = "Two-level category tree")
public class CategoryController {

    private static final CacheControl CACHE_ONE_HOUR = CacheControl.maxAge(Duration.ofHours(1)).cachePublic();

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    @Operation(summary = "The category tree", description = "Public. Active top-level categories, each with its active subcategories, in display order.")
    public ResponseEntity<List<CategoryResponse>> tree() {
        return ResponseEntity.ok().cacheControl(CACHE_ONE_HOUR).body(categoryService.getTree());
    }

    @GetMapping("/{id}")
    @Operation(summary = "One category with its subcategories")
    @ApiResponse(responseCode = "200", description = "The category")
    @ApiResponse(responseCode = "404", description = "No active category with this id (NOT_FOUND)")
    public ResponseEntity<CategoryResponse> one(@PathVariable Integer id) {
        return ResponseEntity.ok().cacheControl(CACHE_ONE_HOUR).body(categoryService.getCategory(id));
    }
}
