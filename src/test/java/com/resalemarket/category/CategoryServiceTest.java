package com.resalemarket.category;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.resalemarket.category.dto.CategoryResponse;
import com.resalemarket.category.dto.CategorySummary;
import com.resalemarket.category.entity.Category;
import com.resalemarket.common.exception.FieldValidationException;
import com.resalemarket.common.exception.ResourceNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    private CategoryRepository repository;

    private CategoryService service;

    private final Category mobiles = category(1, null, "Mobiles", true);
    private final Category phones = category(2, 1, "Phones", true);
    private final Category tablets = category(3, 1, "Tablets", true);
    private final Category books = category(4, null, "Books", true);
    private final Category oldPhones = category(5, 1, "Old phones", false);
    private final Category other = category(6, null, "Other", true);          // a root without subcategories

    @BeforeEach
    void setUp() {
        service = new CategoryService(repository, new CategoryMapperImpl());
    }

    private static Category category(int id, Integer parentId, String name, boolean active) {
        Category category = new CategoryStub();
        ReflectionTestUtils.setField(category, "id", id);
        ReflectionTestUtils.setField(category, "parentId", parentId);
        ReflectionTestUtils.setField(category, "name", name);
        ReflectionTestUtils.setField(category, "slug", name.toLowerCase().replace(' ', '-'));
        ReflectionTestUtils.setField(category, "active", active);
        return category;
    }

    private void treeIsStored() {
        when(repository.findByActiveTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of(mobiles, books, other, phones, tablets));
    }

    @Test
    void theTreeNestsActiveChildrenUnderTheirRootsInOrder() {
        treeIsStored();

        List<CategoryResponse> tree = service.getTree();

        assertThat(tree).extracting(CategoryResponse::name).containsExactly("Mobiles", "Books", "Other");
        assertThat(tree.get(0).children()).extracting(CategoryResponse::name).containsExactly("Phones", "Tablets");
        assertThat(tree.get(0).children().get(0).parentId()).isEqualTo(1);
        assertThat(tree.get(0).children().get(0).children()).isEmpty();
        assertThat(tree.get(1).children()).isEmpty();
    }

    @Test
    void anEmptyDatabaseGivesAnEmptyTree() {
        when(repository.findByActiveTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of());

        assertThat(service.getTree()).isEmpty();
    }

    @Test
    void aRootComesWithItsActiveChildrenOnly() {
        when(repository.findById(1)).thenReturn(Optional.of(mobiles));
        treeIsStored();

        CategoryResponse response = service.getCategory(1);

        assertThat(response.children()).extracting(CategoryResponse::name).containsExactly("Phones", "Tablets");
    }

    @Test
    void aSubcategoryIsFoundWhenItsParentIsActive() {
        when(repository.findById(2)).thenReturn(Optional.of(phones));
        when(repository.findById(1)).thenReturn(Optional.of(mobiles));

        assertThat(service.getCategory(2).name()).isEqualTo("Phones");
    }

    @Test
    void aSubcategoryOfAnInactiveParentIsNotFound() {
        Category hidden = category(1, null, "Mobiles", false);
        when(repository.findById(2)).thenReturn(Optional.of(phones));
        when(repository.findById(1)).thenReturn(Optional.of(hidden));

        assertThatThrownBy(() -> service.getCategory(2)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void unknownAndInactiveCategoriesAreNotFound() {
        when(repository.findById(99)).thenReturn(Optional.empty());
        when(repository.findById(5)).thenReturn(Optional.of(oldPhones));

        assertThatThrownBy(() -> service.getCategory(99)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.getCategory(5)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aListingMayBeFiledUnderACategoryAndOneOfItsSubcategories() {
        when(repository.findById(1)).thenReturn(Optional.of(mobiles));
        when(repository.findById(2)).thenReturn(Optional.of(phones));

        assertThatCode(() -> service.requireSelectable(1, 2)).doesNotThrowAnyException();
    }

    @Test
    void aCategoryWithoutSubcategoriesNeedsNone() {
        when(repository.findById(6)).thenReturn(Optional.of(other));
        when(repository.findByActiveTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of(mobiles, other, phones));

        assertThatCode(() -> service.requireSelectable(6, null)).doesNotThrowAnyException();
    }

    @Test
    void aCategoryWithSubcategoriesNeedsOne() {
        when(repository.findById(1)).thenReturn(Optional.of(mobiles));
        treeIsStored();

        assertThatThrownBy(() -> service.requireSelectable(1, null))
                .isInstanceOfSatisfying(FieldValidationException.class, ex -> {
                    assertThat(ex.getViolations()).hasSize(1);
                    assertThat(ex.getViolations().get(0).field()).isEqualTo("subcategoryId");
                });
    }

    @Test
    void aSubcategoryOfAnotherCategoryIsRefused() {
        when(repository.findById(4)).thenReturn(Optional.of(books));
        when(repository.findById(2)).thenReturn(Optional.of(phones));

        assertThatThrownBy(() -> service.requireSelectable(4, 2))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        ex -> assertThat(ex.getViolations().get(0).field()).isEqualTo("subcategoryId"));
    }

    @Test
    void anInactiveSubcategoryIsRefused() {
        when(repository.findById(1)).thenReturn(Optional.of(mobiles));
        when(repository.findById(5)).thenReturn(Optional.of(oldPhones));

        assertThatThrownBy(() -> service.requireSelectable(1, 5)).isInstanceOf(FieldValidationException.class);
    }

    @Test
    void unknownInactiveAndNonRootCategoriesCannotBeTheCategory() {
        when(repository.findById(99)).thenReturn(Optional.empty());
        when(repository.findById(2)).thenReturn(Optional.of(phones));

        for (int id : new int[] {99, 2}) {
            assertThatThrownBy(() -> service.requireSelectable(id, null))
                    .isInstanceOfSatisfying(FieldValidationException.class,
                            ex -> assertThat(ex.getViolations().get(0).field()).isEqualTo("categoryId"));
        }
    }

    @Test
    void summariesIncludeInactiveCategoriesAndSkipUnknownIds() {
        when(repository.findAllById(List.of(1, 5, 99))).thenReturn(List.of(mobiles, oldPhones));

        Map<Integer, CategorySummary> summaries = service.summaries(List.of(1, 5, 99));

        assertThat(summaries).containsOnlyKeys(1, 5);
        assertThat(summaries.get(5).name()).isEqualTo("Old phones");
    }

    @Test
    void summariesOfNothingCostNoQuery() {
        assertThat(service.summaries(List.of())).isEmpty();
    }

    /** {@link Category}'s constructor is protected; a subclass in the test tree is the simplest way to get an instance. */
    private static final class CategoryStub extends Category {
    }
}
