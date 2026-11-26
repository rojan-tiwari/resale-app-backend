package com.resalemarket.listing;

import com.resalemarket.category.CategoryService;
import com.resalemarket.category.dto.CategorySummary;
import com.resalemarket.common.exception.ResourceNotFoundException;
import com.resalemarket.listing.dto.ListingImageResponse;
import com.resalemarket.listing.dto.ListingResponse;
import com.resalemarket.listing.dto.ListingSummaryResponse;
import com.resalemarket.listing.entity.Listing;
import com.resalemarket.listing.entity.ListingImage;
import com.resalemarket.listing.entity.ListingStatus;
import com.resalemarket.location.LocationService;
import com.resalemarket.location.dto.LocationNames;
import com.resalemarket.location.dto.LocationRef;
import com.resalemarket.user.UserService;
import com.resalemarket.user.dto.PublicUserResponse;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Builds the response objects from entities and the data of other modules (category names, place names, the seller).
 *
 * <p>Lists are assembled in bulk: a page of cards costs one query for the photos, one for the categories and three for the places,
 * however many cards it holds, never one set of queries per card.
 */
@Component
class ListingResponses {

    private static final String UNKNOWN_SELLER = "Former member";

    private final ListingImageRepository imageRepository;
    private final CategoryService categoryService;
    private final LocationService locationService;
    private final UserService userService;
    private final ListingProperties properties;
    private final Clock clock;

    ListingResponses(
            ListingImageRepository imageRepository,
            CategoryService categoryService,
            LocationService locationService,
            UserService userService,
            ListingProperties properties,
            Clock clock) {
        this.imageRepository = imageRepository;
        this.categoryService = categoryService;
        this.locationService = locationService;
        this.userService = userService;
        this.properties = properties;
        this.clock = clock;
    }

    /** Cards for a page of listings, in the same order. */
    List<ListingSummaryResponse> summaries(List<Listing> listings) {
        if (listings.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = listings.stream().map(Listing::getId).toList();
        Map<UUID, ListingImageResponse> primaryImages = imageRepository.findByListingIdInAndPrimaryTrue(ids).stream()
                .collect(Collectors.toMap(ListingImage::getListingId, ListingResponses::image));
        Map<Integer, CategorySummary> categories = categoryService.summaries(categoryIds(listings));
        Map<LocationRef, LocationNames> places = locationService.describe(
                listings.stream().map(ListingResponses::placeOf).collect(Collectors.toSet()));

        return listings.stream()
                .map(listing -> new ListingSummaryResponse(
                        listing.getId(),
                        listing.getTitle(),
                        listing.getPrice(),
                        listing.getCurrency(),
                        listing.getPriceType(),
                        listing.getCondition(),
                        listing.getStatus(),
                        categories.get(listing.getCategoryId()),
                        listing.getSubcategoryId() == null ? null : categories.get(listing.getSubcategoryId()),
                        places.get(placeOf(listing)),
                        listing.getLocality(),
                        primaryImages.get(listing.getId()),
                        listing.isFeaturedAt(clock.instant()),
                        listing.getCreatedAt(),
                        listing.getPublishedAt()))
                .toList();
    }

    ListingResponse detail(Listing listing, Viewer viewer) {
        return detail(listing, viewer, listing.getViewCount());
    }

    /**
     * The full listing as {@code viewer} may see it. {@code viewCount} is passed in because a view that was just counted is not in
     * the loaded entity.
     *
     * @throws ResourceNotFoundException if the seller's account is no longer active and the viewer is neither the seller nor an
     *     administrator: a suspended member's ads are not shown to the public
     */
    ListingResponse detail(Listing listing, Viewer viewer, long viewCount) {
        boolean owner = viewer.owns(listing);
        boolean privileged = owner || viewer.admin();

        PublicUserResponse seller = userService.findPublicProfile(listing.getSellerId()).orElse(null);
        if (seller == null) {
            if (!privileged) {
                throw new ResourceNotFoundException("Listing", listing.getId());
            }
            seller = new PublicUserResponse(listing.getSellerId(), UNKNOWN_SELLER, null, null, LocationNames.NONE, null);
        }
        Map<Integer, CategorySummary> categories = categoryService.summaries(categoryIds(List.of(listing)));
        LocationNames location = locationService.describe(Set.of(placeOf(listing))).get(placeOf(listing));
        List<ListingImageResponse> images = images(listing.getId());
        List<ListingStatus> allowed = allowedStatuses(listing, viewer);

        return new ListingResponse(
                listing.getId(),
                listing.getTitle(),
                listing.getDescription(),
                listing.getPrice(),
                listing.getCurrency(),
                listing.getPriceType(),
                listing.getCondition(),
                listing.getStatus(),
                privileged ? listing.getRejectionReason() : null,
                categories.get(listing.getCategoryId()),
                listing.getSubcategoryId() == null ? null : categories.get(listing.getSubcategoryId()),
                listing.getProvinceId(),
                listing.getDistrictId(),
                listing.getMunicipalityId(),
                location,
                listing.getLocality(),
                listing.isFeaturedAt(clock.instant()),
                viewCount,
                images,
                seller,
                allowed,
                listing.getCreatedAt(),
                listing.getUpdatedAt(),
                listing.getPublishedAt(),
                listing.getSoldAt());
    }

    /** The moves this viewer may make now, in the order of the status enum; the web app shows exactly these buttons. */
    List<ListingStatus> allowedStatuses(Listing listing, Viewer viewer) {
        return ListingStatusPolicy
                .allowedTargets(listing.getStatus(), viewer.owns(listing), viewer.admin(), properties.requireReview())
                .stream()
                .toList();
    }

    List<ListingImageResponse> images(UUID listingId) {
        return imageRepository.findByListingIdOrderBySortOrderAsc(listingId).stream().map(ListingResponses::image).toList();
    }

    static ListingImageResponse image(ListingImage image) {
        return new ListingImageResponse(
                image.getId(),
                imageUrl(image.getListingId(), image.getId()),
                image.getWidth(),
                image.getHeight(),
                image.getSortOrder(),
                image.isPrimary());
    }

    /** Root-relative, so it works behind the dev proxy and behind a reverse proxy alike. */
    static String imageUrl(UUID listingId, UUID imageId) {
        return "/api/v1/listings/%s/images/%s".formatted(listingId, imageId);
    }

    private static LocationRef placeOf(Listing listing) {
        return new LocationRef(listing.getProvinceId(), listing.getDistrictId(), listing.getMunicipalityId());
    }

    private static Collection<Integer> categoryIds(List<Listing> listings) {
        Set<Integer> ids = new HashSet<>();
        for (Listing listing : listings) {
            ids.add(listing.getCategoryId());
            if (listing.getSubcategoryId() != null) {
                ids.add(listing.getSubcategoryId());
            }
        }
        return new ArrayList<>(ids);
    }
}
