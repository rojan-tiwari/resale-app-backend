package com.resalemarket.listing;

import static org.assertj.core.api.Assertions.assertThat;

import com.resalemarket.listing.dto.ListingRequest;
import com.resalemarket.listing.dto.ListingStatusRequest;
import com.resalemarket.listing.dto.ReorderImagesRequest;
import com.resalemarket.listing.entity.ListingCondition;
import com.resalemarket.listing.entity.ListingStatus;
import com.resalemarket.listing.entity.PriceType;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ListingRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    private static Set<String> invalidFields(Object request) {
        return validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    private static ListingRequest request(String title, String description, String price, String locality) {
        return new ListingRequest(
                title, description, price == null ? null : new BigDecimal(price), null, ListingCondition.GOOD, 1, 2, 3, 25, 1, locality);
    }

    @Test
    void aCompleteListingIsValid() {
        assertThat(validator.validate(request("Old guitar", "Plays well, new strings.", "12000", "Baneshwor"))).isEmpty();
    }

    @Test
    void titleAndDescriptionAreTrimmedBeforeTheyAreChecked() {
        ListingRequest padded = request("   ab   ", "     short     ", "1", null);

        assertThat(padded.title()).isEqualTo("ab");
        assertThat(invalidFields(padded)).containsExactlyInAnyOrder("title", "description");
    }

    @Test
    void titleAndDescriptionHaveLengthLimits() {
        assertThat(invalidFields(request("a".repeat(151), "Plays well, new strings.", "1", null))).containsExactly("title");
        assertThat(validator.validate(request("a".repeat(150), "d".repeat(5000), "1", null))).isEmpty();
        assertThat(invalidFields(request("Old guitar", "d".repeat(5001), "1", null))).containsExactly("description");
        assertThat(invalidFields(request("   ", "Plays well, new strings.", "1", null))).containsExactly("title");
    }

    @Test
    void thePriceMustBePresentNonNegativeAndHaveAtMostTwoDecimals() {
        assertThat(invalidFields(request("Old guitar", "Plays well, new strings.", null, null))).containsExactly("price");
        assertThat(invalidFields(request("Old guitar", "Plays well, new strings.", "-0.01", null))).containsExactly("price");
        assertThat(invalidFields(request("Old guitar", "Plays well, new strings.", "10.123", null))).containsExactly("price");
        assertThat(invalidFields(request("Old guitar", "Plays well, new strings.", "12345678901", null))).containsExactly("price");
        assertThat(validator.validate(request("Old guitar", "Plays well, new strings.", "0", null))).isEmpty();
        assertThat(validator.validate(request("Old guitar", "Plays well, new strings.", "9999999999.99", null))).isEmpty();
    }

    @Test
    void anOmittedPriceTypeMeansFixed() {
        assertThat(request("Old guitar", "Plays well, new strings.", "1", null).priceType()).isEqualTo(PriceType.FIXED);
    }

    @Test
    void anEmptyLocalityMeansNoneAndALongOneIsInvalid() {
        assertThat(request("Old guitar", "Plays well, new strings.", "1", "   ").locality()).isNull();
        assertThat(invalidFields(request("Old guitar", "Plays well, new strings.", "1", "x".repeat(121)))).containsExactly("locality");
    }

    @Test
    void categoryConditionAndPlaceAreRequiredIds() {
        ListingRequest request = new ListingRequest("Old guitar", "Plays well, new strings.", BigDecimal.ONE, null, null, null, null, null, null, null, null);

        assertThat(invalidFields(request)).containsExactlyInAnyOrder("condition", "categoryId", "provinceId", "districtId");
        ListingRequest negative = new ListingRequest("Old guitar", "Plays well, new strings.", BigDecimal.ONE, null, ListingCondition.NEW, -1, 0, 3, 25, -5, null);
        assertThat(invalidFields(negative)).containsExactlyInAnyOrder("categoryId", "subcategoryId", "municipalityId");
    }

    @Test
    void aStatusRequestNeedsAStatusAndAShortReason() {
        assertThat(invalidFields(new ListingStatusRequest(null, null))).containsExactly("status");
        assertThat(invalidFields(new ListingStatusRequest(ListingStatus.REJECTED, "x".repeat(501)))).containsExactly("reason");
        assertThat(new ListingStatusRequest(ListingStatus.REJECTED, "   ").reason()).isNull();
        assertThat(new ListingStatusRequest(ListingStatus.REJECTED, "  why  ").reason()).isEqualTo("why");
    }

    @Test
    void anOrderRequestNeedsBetweenOneAndEightIds() {
        assertThat(invalidFields(new ReorderImagesRequest(null))).containsExactly("imageIds");
        assertThat(invalidFields(new ReorderImagesRequest(List.of()))).containsExactly("imageIds");
        List<UUID> nine = java.util.stream.Stream.generate(UUID::randomUUID).limit(9).toList();
        assertThat(invalidFields(new ReorderImagesRequest(nine))).containsExactly("imageIds");
        assertThat(validator.validate(new ReorderImagesRequest(List.of(UUID.randomUUID())))).isEmpty();
    }
}
