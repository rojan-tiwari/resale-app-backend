package com.resalemarket.listing;

import com.resalemarket.common.exception.BusinessRuleException;
import com.resalemarket.common.exception.FieldValidationException;
import com.resalemarket.common.exception.ResourceNotFoundException;
import com.resalemarket.common.storage.ImageInspector;
import com.resalemarket.common.storage.InspectedImage;
import com.resalemarket.common.storage.InvalidImageException;
import com.resalemarket.common.storage.StorageException;
import com.resalemarket.common.storage.StorageObjectNotFoundException;
import com.resalemarket.common.storage.StorageService;
import com.resalemarket.listing.dto.ListingImageResponse;
import com.resalemarket.listing.dto.ReorderImagesRequest;
import com.resalemarket.listing.entity.Listing;
import com.resalemarket.listing.entity.ListingImage;
import com.resalemarket.user.UserService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * The photos of a listing: upload, remove, put in order, and read back.
 *
 * <p>What is checked, and why:
 * <ul>
 *   <li><b>The bytes decide what a file is</b> ({@link ImageInspector}); the name and {@code Content-Type} the client sent are
 *       ignored. Every file of a request is checked before any is stored, so a request is all or nothing.</li>
 *   <li><b>Names are made up by the server</b> (a random UUID plus the extension of the detected format), so an upload cannot
 *       choose where it lands or overwrite anything.</li>
 *   <li><b>Files and rows stay in step.</b> New files are removed again if the transaction does not commit, and the file of a
 *       removed photo is only deleted after the commit, so a failure never leaves a row that points at nothing.</li>
 *   <li><b>One change at a time per listing</b>: the listing row is locked, so two requests cannot both see seven photos and add
 *       two more.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class ListingImageService {

    private static final Logger log = LoggerFactory.getLogger(ListingImageService.class);

    private static final String KEY_PREFIX = "listing-images/";
    private static final int MAX_NAME_LENGTH = 60;

    private final ListingImageRepository imageRepository;
    private final ListingAccess access;
    private final StorageService storage;
    private final UserService userService;
    private final ListingProperties properties;

    public ListingImageService(
            ListingImageRepository imageRepository,
            ListingAccess access,
            StorageService storage,
            UserService userService,
            ListingProperties properties) {
        this.imageRepository = imageRepository;
        this.access = access;
        this.storage = storage;
        this.userService = userService;
        this.properties = properties;
    }

    /** The loaded file of a photo, ready to be sent. */
    public record ImageContent(Resource resource, String contentType, long sizeBytes) {
    }

    /**
     * Adds photos to the end of the gallery. The first photo of a listing becomes its main photo.
     *
     * @return the complete gallery after the upload
     * @throws FieldValidationException if there is no file, or one is empty, too big, not a JPEG/PNG/WebP, or has too many pixels
     * @throws BusinessRuleException    if the listing would hold more than 8 photos, or is in a status that is frozen
     */
    @Transactional
    public List<ListingImageResponse> upload(UUID listingId, Viewer viewer, List<MultipartFile> files) {
        Listing listing = access.ownedForUpdate(listingId, viewer);
        requireEditable(listing);
        userService.requireActive(viewer.userId());

        if (files == null || files.isEmpty()) {
            throw new FieldValidationException("images", "Choose at least one photo.");
        }
        int existing = (int) imageRepository.countByListingId(listingId);
        if (existing + files.size() > ListingImage.MAX_PER_LISTING) {
            throw new BusinessRuleException("A listing can have at most %d photos; this one has %d.".formatted(ListingImage.MAX_PER_LISTING, existing));
        }

        List<Prepared> prepared = files.stream().map(this::prepare).toList();

        List<String> storedKeys = new ArrayList<>();
        removeFilesUnlessCommitted(storedKeys);
        List<ListingImage> rows = new ArrayList<>();
        for (int i = 0; i < prepared.size(); i++) {
            Prepared upload = prepared.get(i);
            String key = KEY_PREFIX + UUID.randomUUID() + "." + upload.inspected().type().extension();
            storage.store(key, upload.bytes());
            storedKeys.add(key);
            InspectedImage found = upload.inspected();
            rows.add(ListingImage.of(
                    listingId, key, found.type().contentType(), upload.bytes().length, found.width(), found.height(), existing + i));
        }
        imageRepository.saveAll(rows);
        imageRepository.flush();
        return gallery(listingId);
    }

    /**
     * Removes one photo; the ones behind it move up, and if it was the main photo the next one becomes the main photo.
     *
     * @return the gallery that is left
     * @throws BusinessRuleException if it is the only photo of a live listing
     */
    @Transactional
    public List<ListingImageResponse> delete(UUID listingId, UUID imageId, Viewer viewer) {
        Listing listing = access.ownedForUpdate(listingId, viewer);
        requireEditable(listing);

        ListingImage image = imageRepository.findByIdAndListingId(imageId, listingId)
                .orElseThrow(() -> new ResourceNotFoundException("Image", imageId));
        List<ListingImage> all = imageRepository.findByListingIdOrderBySortOrderAsc(listingId);
        if (all.size() == 1 && listing.getStatus().isPublic()) {
            throw new BusinessRuleException("A live listing needs at least one photo. Add another photo first, or take the listing down.");
        }

        imageRepository.delete(image);
        imageRepository.flush();        // the old main photo must be gone before another one is marked as main
        List<ListingImage> remaining = all.stream().filter(other -> !other.getId().equals(imageId)).toList();
        for (int position = 0; position < remaining.size(); position++) {
            remaining.get(position).moveTo(position);
        }
        imageRepository.flush();
        removeFileAfterCommit(image.getStorageKey(), image.getId());
        return gallery(listingId);
    }

    /**
     * Puts the photos in the given order; the first one becomes the main photo.
     *
     * @throws FieldValidationException unless the request lists every photo of the listing exactly once
     */
    @Transactional
    public List<ListingImageResponse> reorder(UUID listingId, Viewer viewer, ReorderImagesRequest request) {
        Listing listing = access.ownedForUpdate(listingId, viewer);
        requireEditable(listing);

        List<ListingImage> current = imageRepository.findByListingIdOrderBySortOrderAsc(listingId);
        List<UUID> requested = request.imageIds();
        Map<UUID, ListingImage> byId = current.stream().collect(Collectors.toMap(ListingImage::getId, Function.identity()));
        if (requested.size() != current.size() || !new HashSet<>(requested).equals(byId.keySet())) {
            throw new FieldValidationException("imageIds", "List every photo of the listing exactly once.");
        }

        // At most one image per listing may be primary, and that unique index cannot be deferred to the end of the
        // transaction (unlike the one on the positions). So the old main photo is demoted and flushed first.
        UUID newFirst = requested.get(0);
        current.stream().filter(image -> image.isPrimary() && !image.getId().equals(newFirst)).forEach(image -> {
            image.moveTo(requested.indexOf(image.getId()));
        });
        imageRepository.flush();
        for (int position = 0; position < requested.size(); position++) {
            byId.get(requested.get(position)).moveTo(position);
        }
        imageRepository.flush();
        return gallery(listingId);
    }

    /**
     * The file of a photo, for the public image URL. The URL carries the unguessable ids of the listing and the photo, which is
     * what makes it safe to show in an {@code img} tag (browsers cannot send an access token there); no status check is made,
     * so the seller sees the photos of drafts and sold items in "My ads" too.
     *
     * @throws ResourceNotFoundException if there is no such photo of this listing, or its file is gone from the storage
     */
    public ImageContent load(UUID listingId, UUID imageId) {
        ListingImage image = imageRepository.findByIdAndListingId(imageId, listingId)
                .orElseThrow(() -> new ResourceNotFoundException("Image", imageId));
        try {
            return new ImageContent(storage.load(image.getStorageKey()), image.getContentType(), image.getSizeBytes());
        } catch (StorageObjectNotFoundException ex) {
            // The row says the file exists but the storage disagrees: a data problem for the operator, not for the client.
            log.error("The file of listing image {} is missing from the storage", imageId);
            throw new ResourceNotFoundException("Image", imageId);
        }
    }

    // ------------------------------------------------------------------------------------------------ internals

    private record Prepared(byte[] bytes, InspectedImage inspected) {
    }

    private Prepared prepare(MultipartFile file) {
        String name = displayName(file);
        if (file.isEmpty()) {
            throw new FieldValidationException("images", name + " is empty.");
        }
        long limit = properties.maxImageSize().toBytes();
        if (file.getSize() > limit) {
            throw new FieldValidationException("images", name + " is larger than " + properties.maxImageSize().toMegabytes() + " MB.");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new StorageException("Could not read an uploaded file.", ex);
        }
        try {
            return new Prepared(bytes, ImageInspector.inspect(bytes));
        } catch (InvalidImageException ex) {
            throw new FieldValidationException("images", name + " " + ex.getMessage());
        }
    }

    /** The name the client gave, made safe to show back: no path, no control characters, not too long. Used in messages only. */
    private static String displayName(MultipartFile file) {
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) {
            return "A file";
        }
        String name = original.substring(Math.max(original.lastIndexOf('/'), original.lastIndexOf('\\')) + 1)
                .replaceAll("\\p{Cntrl}", "")
                .strip();
        if (name.isEmpty()) {
            return "A file";
        }
        return name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) + "…" : name;
    }

    private void requireEditable(Listing listing) {
        if (!listing.getStatus().isEditable()) {
            throw new BusinessRuleException(
                    "The photos of a listing that is %s cannot be changed.".formatted(ListingService.label(listing.getStatus())));
        }
    }

    private List<ListingImageResponse> gallery(UUID listingId) {
        return imageRepository.findByListingIdOrderBySortOrderAsc(listingId).stream().map(ListingResponses::image).toList();
    }

    /** Deletes the given (already stored) files again if the surrounding transaction does not commit. The list may still grow. */
    private void removeFilesUnlessCommitted(List<String> keys) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    return;
                }
                for (String key : keys) {
                    try {
                        storage.delete(key);
                    } catch (RuntimeException ex) {
                        log.warn("Could not remove an uploaded file after a failed request", ex);
                    }
                }
            }
        });
    }

    private void removeFileAfterCommit(String key, UUID imageId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    storage.delete(key);
                } catch (RuntimeException ex) {
                    log.warn("Could not delete the file of removed listing image {}", imageId, ex);
                }
            }
        });
    }
}
