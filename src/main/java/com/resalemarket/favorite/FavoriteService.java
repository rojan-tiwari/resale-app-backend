package com.resalemarket.favorite;

import com.resalemarket.common.exception.BusinessRuleException;
import com.resalemarket.common.exception.ConflictException;
import com.resalemarket.common.exception.ResourceNotFoundException;
import com.resalemarket.common.response.PageResponse;
import com.resalemarket.favorite.dto.FavoriteResponse;
import com.resalemarket.favorite.entity.Favorite;
import com.resalemarket.listing.ListingService;
import com.resalemarket.listing.dto.ListingSummaryResponse;
import com.resalemarket.user.UserService;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A member's saved listings. Everything is about the member in the access token: nobody can read or change another member's list.
 * Listings are looked at only through {@link ListingService}.
 */
@Service
@Transactional(readOnly = true)
public class FavoriteService {

    private final FavoriteRepository repository;
    private final ListingService listingService;
    private final UserService userService;
    private final FavoriteProperties properties;

    public FavoriteService(
            FavoriteRepository repository, ListingService listingService, UserService userService, FavoriteProperties properties) {
        this.repository = repository;
        this.listingService = listingService;
        this.userService = userService;
        this.properties = properties;
    }

    /**
     * Saves a listing that is live.
     *
     * @throws ResourceNotFoundException if the listing does not exist or is not live (the same answer for both)
     * @throws ConflictException         if it is already saved (also when two requests race: the database constraint decides)
     * @throws BusinessRuleException     if the member already saved the most listings they may
     */
    @Transactional
    public FavoriteResponse add(UUID userId, UUID listingId) {
        userService.requireActive(userId);
        listingService.requireLive(listingId);
        if (repository.existsByUserIdAndListingId(userId, listingId)) {
            throw new ConflictException("This ad is already in your favourites.");
        }
        if (repository.countByUserId(userId) >= properties.maxPerUser()) {
            throw new BusinessRuleException(
                    "You can save at most %d ads. Remove some you no longer need first.".formatted(properties.maxPerUser()));
        }
        // Flushed here so that two simultaneous saves fail on uq_favorites_user_listing inside this call.
        Favorite saved = repository.saveAndFlush(Favorite.of(userId, listingId));
        return toResponses(List.of(saved)).get(0);
    }

    /** Removes a saved listing. Removing one that is not saved is fine (nothing to do), so a double click does no harm. */
    @Transactional
    public void remove(UUID userId, UUID listingId) {
        repository.deleteByUserIdAndListingId(userId, listingId);
    }

    /** The member's saved listings, newest saved first. Ads that are no longer for sale are flagged, not left out. */
    public PageResponse<FavoriteResponse> list(UUID userId, Pageable pageable) {
        Pageable page = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        Page<Favorite> found = repository.findByUserIdOrderByCreatedAtDescIdAsc(userId, page);
        return PageResponse.from(new PageImpl<>(toResponses(found.getContent()), page, found.getTotalElements()));
    }

    /** The ids of everything the member saved: what the web app needs to draw a filled heart on a card. */
    public List<UUID> listingIds(UUID userId) {
        return repository.findListingIdsByUserId(userId);
    }

    /**
     * The categories the member saves the most from, most saved first (ties: the lower id), at most {@code limit}. The
     * recommendations of the home page start from these.
     */
    public List<Integer> favouriteCategoryIds(UUID userId, int limit) {
        List<UUID> saved = repository.findListingIdsByUserId(userId);
        Map<Integer, Long> perCategory = listingService.categoryIds(saved).values().stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        return perCategory.entrySet().stream()
                .sorted(Map.Entry.<Integer, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey(Comparator.naturalOrder())))
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    private List<FavoriteResponse> toResponses(List<Favorite> favorites) {
        Map<UUID, ListingSummaryResponse> cards = listingService.savedCards(favorites.stream().map(Favorite::getListingId).toList());
        return favorites.stream()
                .map(favorite -> {
                    ListingSummaryResponse card = cards.get(favorite.getListingId());
                    boolean available = card != null && card.status().isPublic();
                    return new FavoriteResponse(favorite.getListingId(), favorite.getCreatedAt(), available, card);
                })
                .toList();
    }
}
