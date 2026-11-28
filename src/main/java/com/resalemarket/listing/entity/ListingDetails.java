package com.resalemarket.listing.entity;

import java.math.BigDecimal;

/**
 * Everything a seller writes about a listing. The service has already validated it (category and place fit together); the
 * entity just stores it.
 */
public record ListingDetails(
        String title,
        String description,
        BigDecimal price,
        PriceType priceType,
        ListingCondition condition,
        Integer categoryId,
        Integer subcategoryId,
        Integer provinceId,
        Integer districtId,
        Integer municipalityId,
        String locality) {
}
