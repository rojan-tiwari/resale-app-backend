package com.resalemarket.category;

import com.resalemarket.category.dto.CategoryResponse;
import com.resalemarket.category.dto.CategorySummary;
import com.resalemarket.category.entity.Category;
import com.resalemarket.common.exception.FieldValidationException;
import com.resalemarket.common.exception.ResourceNotFoundException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The two-level category tree: what the public may browse, and what a listing may be filed under. */
@Service
@Transactional(readOnly = true)
public class CategoryService {

    private final CategoryRepository repository;
    private final CategoryMapper mapper;

    public CategoryService(CategoryRepository repository, CategoryMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    /** The active root categories, each with its active subcategories, in display order. */
    public List<CategoryResponse> getTree() {
        List<Category> active = repository.findByActiveTrueOrderBySortOrderAscIdAsc();
        Map<Integer, List<Category>> childrenByParent = active.stream()
                .filter(category -> !category.isRoot())
                .collect(Collectors.groupingBy(Category::getParentId, LinkedHashMap::new, Collectors.toList()));
        return active.stream()
                .filter(Category::isRoot)
                .map(root -> toNode(root, childrenByParent.getOrDefault(root.getId(), List.of())))
                .toList();
    }

    /**
     * One active category with its active subcategories.
     *
     * @throws ResourceNotFoundException if there is no such category, it is inactive, or its parent is
     */
    public CategoryResponse getCategory(Integer id) {
        Category category = repository.findById(id)
                .filter(Category::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Category", id));
        if (category.isRoot()) {
            List<Category> children = repository.findByActiveTrueOrderBySortOrderAscIdAsc().stream()
                    .filter(candidate -> id.equals(candidate.getParentId()))
                    .toList();
            return toNode(category, children);
        }
        boolean parentActive = repository.findById(category.getParentId()).filter(Category::isActive).isPresent();
        if (!parentActive) {
            throw new ResourceNotFoundException("Category", id);
        }
        return toNode(category, List.of());
    }

    /**
     * Checks that a listing may be filed under this category and subcategory: both active, the subcategory a child of the
     * category, and a subcategory chosen whenever the category has any. The database enforces the parent-child link with a
     * composite foreign key; this check answers with a message the seller can act on.
     *
     * @throws FieldValidationException naming {@code categoryId} or {@code subcategoryId}
     */
    public void requireSelectable(Integer categoryId, Integer subcategoryId) {
        Category category = repository.findById(categoryId)
                .filter(found -> found.isActive() && found.isRoot())
                .orElseThrow(() -> new FieldValidationException("categoryId", "Choose one of the listed categories."));

        if (subcategoryId == null) {
            boolean hasSubcategories = repository.findByActiveTrueOrderBySortOrderAscIdAsc().stream()
                    .anyMatch(candidate -> category.getId().equals(candidate.getParentId()));
            if (hasSubcategories) {
                throw new FieldValidationException("subcategoryId", "Choose a subcategory.");
            }
            return;
        }
        repository.findById(subcategoryId)
                .filter(found -> found.isActive() && category.getId().equals(found.getParentId()))
                .orElseThrow(() -> new FieldValidationException(
                        "subcategoryId", "This subcategory is not in the chosen category."));
    }

    /**
     * Names for the given ids, <b>including inactive categories</b>: a listing keeps showing the category it was filed under even
     * after that category is hidden from the pickers. Ids that do not exist are left out. One query for any number of listings.
     */
    public Map<Integer, CategorySummary> summaries(Collection<Integer> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return repository.findAllById(ids).stream()
                .collect(Collectors.toMap(Category::getId, mapper::toSummary, (first, second) -> first));
    }

    private CategoryResponse toNode(Category category, List<Category> children) {
        return mapper.toResponse(category, children.stream().map(child -> toNode(child, List.of())).toList());
    }
}
