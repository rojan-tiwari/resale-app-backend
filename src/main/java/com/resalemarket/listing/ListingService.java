package com.resalemarket.listing;

import com.resalemarket.category.CategoryService;
import com.resalemarket.common.exception.BusinessRuleException;
import com.resalemarket.common.exception.FieldValidationException;
import com.resalemarket.common.exception.ForbiddenException;
import com.resalemarket.common.exception.ResourceNotFoundException;
import com.resalemarket.common.response.PageResponse;
import com.resalemarket.listing.dto.ListingRequest;
import com.resalemarket.listing.dto.ListingResponse;
import com.resalemarket.listing.dto.ListingStatusRequest;
import com.resalemarket.listing.dto.ListingSummaryResponse;
import com.resalemarket.listing.entity.Listing;
import com.resalemarket.listing.entity.ListingDetails;
import com.resalemarket.listing.entity.ListingStatus;
import com.resalemarket.location.LocationService;
import com.resalemarket.user.UserService;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creating, editing, publishing and reading listings. Photos are in {@link ListingImageService}.
 *
 * <p>Every change starts from {@link ListingAccess}, which answers 404 for a listing the caller may not see and 403 for one they
 * may see but not change, and ends with the move being checked against {@link ListingStatusPolicy}. Nothing here trusts the web
 * app: the buttons it shows come from {@code allowedStatuses}, but the server checks again on every call.
 */
@Service
@Transactional(readOnly = true)
public class ListingService {

    /** Own listings and a seller's page come newest first. The client's {@code sort} is ignored: only fixed orders are safe to index. */
    private static final Sort NEWEST_CREATED = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"));
    private static final Sort NEWEST_PUBLISHED = Sort.by(Sort.Order.desc("publishedAt"), Sort.Order.asc("id"));

    private final ListingRepository repository;
    private final ListingImageRepository imageRepository;
    private final ListingAccess access;
    private final ListingResponses responses;
    private final CategoryService categoryService;
    private final LocationService locationService;
    private final UserService userService;
    private final ListingProperties properties;
    private final Clock clock;

    public ListingService(
            ListingRepository repository,
            ListingImageRepository imageRepository,
            ListingAccess access,
            ListingResponses responses,
            CategoryService categoryService,
            LocationService locationService,
            UserService userService,
            ListingProperties properties,
            Clock clock) {
        this.repository = repository;
        this.imageRepository = imageRepository;
        this.access = access;
        this.responses = responses;
        this.categoryService = categoryService;
        this.locationService = locationService;
        this.userService = userService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Creates a <b>draft</b>. Publishing is a separate step because a listing needs a photo first, and photos are uploaded to a
     * listing that already exists.
     *
     * @throws FieldValidationException if the category or the place does not fit together
     */
    @Transactional
    public ListingResponse create(Viewer viewer, ListingRequest request) {
        userService.requireActive(viewer.userId());
        ListingDetails details = validated(request);
        Listing listing = repository.saveAndFlush(Listing.draft(viewer.userId(), details));
        return responses.detail(listing, viewer);
    }

    /**
     * Replaces the content of one of the caller's listings.
     *
     * @throws BusinessRuleException if the listing is in a status that is frozen (under review, sold, rejected, removed)
     */
    @Transactional
    public ListingResponse update(UUID id, Viewer viewer, ListingRequest request) {
        Listing listing = access.owned(id, viewer);
        if (!listing.getStatus().isEditable()) {
            throw new BusinessRuleException(frozenMessage(listing, viewer, "edited"));
        }
        userService.requireActive(viewer.userId());
        listing.edit(validated(request));
        // Flushed here so a concurrent edit (optimistic lock) fails inside this call and the database timestamps are read back.
        repository.saveAndFlush(listing);
        return responses.detail(listing, viewer);
    }

    /**
     * Moves a listing to another status after checking the move against the state machine for this caller.
     *
     * @throws BusinessRuleException    if the move is not allowed to this caller from the current status, or a photo is missing
     * @throws FieldValidationException if a rejection comes without a reason
     * @throws ForbiddenException       if the caller can see the listing but is neither its seller nor an administrator
     */
    @Transactional
    public ListingResponse changeStatus(UUID id, Viewer viewer, ListingStatusRequest request) {
        Listing listing = access.visible(id, viewer);
        applyStatus(listing, viewer, request.status(), request.reason());
        return responses.detail(listing, viewer);
    }

    /** "Delete" is archiving: the listing disappears from the public pages and stays in the database. Archiving twice is fine. */
    @Transactional
    public void archive(UUID id, Viewer viewer) {
        Listing listing = access.visible(id, viewer);
        if (listing.getStatus() == ListingStatus.ARCHIVED) {
            return;   // only its seller and administrators can see an archived listing, so this is theirs to repeat
        }
        applyStatus(listing, viewer, ListingStatus.ARCHIVED, null);
    }

    /**
     * One listing. The seller and administrators see it in every status; everyone else only while it is live. A visit by anyone
     * but the seller or an administrator counts as a view.
     */
    @Transactional
    public ListingResponse get(UUID id, Viewer viewer) {
        Listing listing = access.visible(id, viewer);
        long views = listing.getViewCount();
        if (listing.getStatus().isPublic() && !viewer.owns(listing) && !viewer.admin()) {
            repository.incrementViewCount(id);
            views++;
        }
        return responses.detail(listing, viewer, views);
    }

    /** The caller's own listings, newest first. Archived ones are left out unless {@code status} asks for them. */
    public PageResponse<ListingSummaryResponse> mine(Viewer viewer, ListingStatus status, Pageable pageable) {
        Pageable page = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), NEWEST_CREATED);
        Page<Listing> found = status == null
                ? repository.findBySellerIdAndStatusNot(viewer.userId(), ListingStatus.ARCHIVED, page)
                : repository.findBySellerIdAndStatus(viewer.userId(), status, page);
        return summaries(found, page);
    }

    /**
     * A member's live listings, newest first.
     *
     * @throws com.resalemarket.common.exception.ResourceNotFoundException if the account is unknown, suspended or deleted
     */
    public PageResponse<ListingSummaryResponse> sellerListings(UUID sellerId, Pageable pageable) {
        userService.getPublicProfile(sellerId);
        Pageable page = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), NEWEST_PUBLISHED);
        return summaries(repository.findBySellerIdAndStatusIn(sellerId, ListingStatus.PUBLIC, page), page);
    }

    /** Live listings like this one (same category; the same subcategory and place first), at most {@code limit}. */
    public List<ListingSummaryResponse> similar(UUID id, Viewer viewer, int limit) {
        Listing base = access.visible(id, viewer);
        List<Listing> found = repository.findSimilar(
                ListingStatus.PUBLIC,
                base.getId(),
                base.getCategoryId(),
                base.getSubcategoryId() == null ? 0 : base.getSubcategoryId(),
                base.getDistrictId(),
                base.getProvinceId(),
                PageRequest.of(0, limit));
        return responses.summaries(found);
    }

    // ------------------------------------------------------------------------------------------------ internals

    private void applyStatus(Listing listing, Viewer viewer, ListingStatus target, String reason) {
        boolean owner = viewer.owns(listing);
        if (!owner && !viewer.admin()) {
            throw new ForbiddenException("You can only change your own listings.");
        }
        ListingStatus current = listing.getStatus();
        Set<ListingStatus> allowed = ListingStatusPolicy.allowedTargets(current, owner, viewer.admin(), properties.requireReview());
        if (!allowed.contains(target)) {
            throw new BusinessRuleException("A listing that is %s cannot be moved to %s.".formatted(label(current), label(target)));
        }
        if (target == ListingStatus.REJECTED && reason == null) {
            throw new FieldValidationException("reason", "Say why the listing is rejected.");
        }
        boolean publishing = current == ListingStatus.DRAFT && (target == ListingStatus.ACTIVE || target == ListingStatus.PENDING_REVIEW);
        if (publishing) {
            userService.requireActive(listing.getSellerId());
            if (imageRepository.countByListingId(listing.getId()) < 1) {
                throw new BusinessRuleException("Add at least one photo before publishing the listing.");
            }
        }
        listing.changeStatus(target, clock.instant(), reason);
        repository.saveAndFlush(listing);
    }

    private ListingDetails validated(ListingRequest request) {
        categoryService.requireSelectable(request.categoryId(), request.subcategoryId());
        locationService.resolve(request.provinceId(), request.districtId(), request.municipalityId());
        return new ListingDetails(
                request.title(),
                request.description(),
                request.price(),
                request.priceType(),
                request.condition(),
                request.categoryId(),
                request.subcategoryId(),
                request.provinceId(),
                request.districtId(),
                request.municipalityId(),
                request.locality());
    }

    private PageResponse<ListingSummaryResponse> summaries(Page<Listing> found, Pageable page) {
        return PageResponse.from(new PageImpl<>(responses.summaries(found.getContent()), page, found.getTotalElements()));
    }

    private String frozenMessage(Listing listing, Viewer viewer, String what) {
        String message = "A listing that is %s cannot be %s.".formatted(label(listing.getStatus()), what);
        boolean canGoBackToDraft = ListingStatusPolicy
                .allowedTargets(listing.getStatus(), viewer.owns(listing), false, properties.requireReview())
                .contains(ListingStatus.DRAFT);
        return canGoBackToDraft ? message + " Move it back to draft first." : message;
    }

    static String label(ListingStatus status) {
        return status.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
