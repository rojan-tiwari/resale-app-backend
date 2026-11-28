package com.resalemarket.listing;

import static org.assertj.core.api.Assertions.assertThat;

import com.resalemarket.common.exception.FieldValidationException;
import com.resalemarket.listing.entity.ListingCondition;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ListingSearchParserTest {

    private static ListingSearchCriteria parse(String q, List<String> conditions, String sort) {
        return ListingSearchParser.parse(q, null, null, null, null, conditions, null, null, null, null, null, sort);
    }

    private static String problemField(Runnable parse) {
        try {
            parse.run();
        } catch (FieldValidationException ex) {
            return ex.getViolations().get(0).field();
        }
        return null;
    }

    @Test
    void nothingGivenMeansNoFilterAndNewestFirst() {
        ListingSearchCriteria criteria = parse(null, null, null);

        assertThat(criteria.hasKeyword()).isFalse();
        assertThat(criteria.conditions()).isEmpty();
        assertThat(criteria.negotiableOnly()).isFalse();
        assertThat(criteria.sort()).isEqualTo(ListingSort.NEWEST);
    }

    @Test
    void theKeywordIsTrimmedAndItsWhitespaceCollapsed() {
        assertThat(parse("  iPhone \t  13\n", null, null).keyword()).isEqualTo("iPhone 13");
    }

    @Test
    void controlCharactersAreSpacesNotAnError() {
        assertThat(parse("a\u0000b", null, null).keyword()).isEqualTo("a b");
        assertThat(parse("\u0000\u0007", null, null).hasKeyword()).isFalse();
    }

    @Test
    void aBlankKeywordIsNoKeyword() {
        assertThat(parse("", null, null).hasKeyword()).isFalse();
        assertThat(parse("   ", null, null).hasKeyword()).isFalse();
    }

    @Test
    void theKeywordNeedsTwoToOneHundredCharactersAfterTidying() {
        assertThat(problemField(() -> parse("a", null, null))).isEqualTo("q");
        assertThat(problemField(() -> parse(" a ", null, null))).isEqualTo("q");
        assertThat(problemField(() -> parse("x".repeat(101), null, null))).isEqualTo("q");
        assertThat(parse("ab", null, null).keyword()).isEqualTo("ab");
        assertThat(parse("x".repeat(100), null, null).keyword()).hasSize(100);
    }

    @Test
    void conditionsAcceptRepeatedParametersCommaListsAndAnyCase() {
        assertThat(parse(null, List.of("NEW", "good"), null).conditions()).containsExactlyInAnyOrder(ListingCondition.NEW, ListingCondition.GOOD);
        assertThat(parse(null, List.of("like_new, for_parts"), null).conditions())
                .containsExactlyInAnyOrder(ListingCondition.LIKE_NEW, ListingCondition.FOR_PARTS);
        assertThat(parse(null, List.of("", " ,"), null).conditions()).isEmpty();
    }

    @Test
    void anUnknownConditionNamesTheField() {
        assertThat(problemField(() -> parse(null, List.of("NEW", "BROKEN"), null))).isEqualTo("condition");
    }

    @Test
    void sortDefaultsToRelevanceWithAKeywordAndNewestWithout() {
        assertThat(parse("laptop", null, null).sort()).isEqualTo(ListingSort.RELEVANCE);
        assertThat(parse(null, null, null).sort()).isEqualTo(ListingSort.NEWEST);
        assertThat(parse("laptop", null, "  ").sort()).isEqualTo(ListingSort.RELEVANCE);
    }

    @Test
    void relevanceWithoutAKeywordFallsBackToNewest() {
        assertThat(parse(null, null, "relevance").sort()).isEqualTo(ListingSort.NEWEST);
        assertThat(parse("laptop", null, "relevance").sort()).isEqualTo(ListingSort.RELEVANCE);
    }

    @Test
    void everySortTokenIsUnderstoodAndAnythingElseIsRefused() {
        assertThat(parse(null, null, "newest").sort()).isEqualTo(ListingSort.NEWEST);
        assertThat(parse(null, null, "priceAsc").sort()).isEqualTo(ListingSort.PRICE_ASC);
        assertThat(parse(null, null, "priceDesc").sort()).isEqualTo(ListingSort.PRICE_DESC);
        for (String bad : List.of("price", "PRICEASC", "price_asc", "password_hash", "newest,desc", "1; DROP TABLE listings")) {
            assertThat(problemField(() -> parse(null, null, bad))).as(bad).isEqualTo("sort");
        }
    }

    @Test
    void pricesMustBeSensibleAndInOrder() {
        assertThat(problemField(() -> ListingSearchParser.parse(null, null, null, new BigDecimal("-1"), null, null, null, null, null, null, null, null)))
                .isEqualTo("minPrice");
        assertThat(problemField(() -> ListingSearchParser.parse(null, null, null, null, new BigDecimal("1e12"), null, null, null, null, null, null, null)))
                .isEqualTo("maxPrice");
        assertThat(problemField(() -> ListingSearchParser.parse(null, null, null, new BigDecimal("10"), new BigDecimal("9"), null, null, null, null, null, null, null)))
                .isEqualTo("minPrice");
        ListingSearchCriteria equal = ListingSearchParser.parse(null, null, null, new BigDecimal("10"), new BigDecimal("10"), null, null, null, null, null, null, null);
        assertThat(equal.minPrice()).isEqualByComparingTo("10");
    }

    @Test
    void idsMustBePositiveAndDaysBetweenOneAndAYear() {
        assertThat(problemField(() -> ListingSearchParser.parse(null, 0, null, null, null, null, null, null, null, null, null, null))).isEqualTo("categoryId");
        assertThat(problemField(() -> ListingSearchParser.parse(null, null, -1, null, null, null, null, null, null, null, null, null))).isEqualTo("subcategoryId");
        assertThat(problemField(() -> ListingSearchParser.parse(null, null, null, null, null, null, 0, null, null, null, null, null))).isEqualTo("provinceId");
        assertThat(problemField(() -> ListingSearchParser.parse(null, null, null, null, null, null, null, 0, null, null, null, null))).isEqualTo("districtId");
        assertThat(problemField(() -> ListingSearchParser.parse(null, null, null, null, null, null, null, null, 0, null, null, null))).isEqualTo("municipalityId");
        assertThat(problemField(() -> ListingSearchParser.parse(null, null, null, null, null, null, null, null, null, null, 0, null))).isEqualTo("postedWithinDays");
        assertThat(problemField(() -> ListingSearchParser.parse(null, null, null, null, null, null, null, null, null, null, 366, null))).isEqualTo("postedWithinDays");
        assertThat(ListingSearchParser.parse(null, 1, 2, null, null, null, 3, 4, 5, true, 365, null).negotiableOnly()).isTrue();
    }

    @Test
    void negotiableIsOnlyOnForTrue() {
        assertThat(ListingSearchParser.parse(null, null, null, null, null, null, null, null, null, false, null, null).negotiableOnly()).isFalse();
        assertThat(ListingSearchParser.parse(null, null, null, null, null, null, null, null, null, null, null, null).negotiableOnly()).isFalse();
    }

    @Test
    void likePatternsEscapeTheirSpecialCharacters() {
        assertThat(ListingSearchRepository.escapeLike("50%_off\\now")).isEqualTo("50\\%\\_off\\\\now");
        assertThat(ListingSearchRepository.escapeLike("plain")).isEqualTo("plain");
    }
}
