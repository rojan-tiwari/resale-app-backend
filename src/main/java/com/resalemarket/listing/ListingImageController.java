package com.resalemarket.listing;

import com.resalemarket.common.config.OpenApiConfig;
import com.resalemarket.listing.dto.ListingImageResponse;
import com.resalemarket.listing.dto.ReorderImagesRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** The photos of a listing. Changing them is the seller's; the picture itself is public (see {@link #image}). */
@RestController
@RequestMapping("/api/v1/listings/{id}/images")
@Tag(name = "Listing images", description = "Upload, order, remove and show photos (JPEG, PNG, WebP; at most 8 per listing)")
public class ListingImageController {

    private static final CacheControl CACHE_ONE_DAY = CacheControl.maxAge(Duration.ofDays(1)).cachePublic();

    private final ListingImageService imageService;
    private final ListingRateLimits rateLimits;

    public ListingImageController(ListingImageService imageService, ListingRateLimits rateLimits) {
        this.imageService = imageService;
        this.rateLimits = rateLimits;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Upload photos",
            description = "The seller only. Multipart form with one or more files in the field `images`. What counts is the content: "
                    + "JPEG, PNG or WebP, at most 5 MB each and 50 megapixels, at most 8 photos per listing in all. The request is "
                    + "all or nothing. The first photo of a listing becomes its main photo. Returns the whole gallery.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponse(responseCode = "201", description = "The gallery after the upload")
    @ApiResponse(responseCode = "400", description = "No file, or a file that is empty, too big or not a supported image (VALIDATION_FAILED, field `images`)")
    @ApiResponse(responseCode = "403", description = "The listing is someone else's (FORBIDDEN)")
    @ApiResponse(responseCode = "404", description = "No such listing, or the caller may not see it (NOT_FOUND)")
    @ApiResponse(responseCode = "422", description = "More than 8 photos, or the listing is in a status that cannot be changed (BUSINESS_RULE_VIOLATION)")
    @ApiResponse(responseCode = "429", description = "Too many uploads recently (TOO_MANY_REQUESTS)")
    public ResponseEntity<List<ListingImageResponse>> upload(
            @PathVariable UUID id,
            Authentication authentication,
            @RequestParam(name = "images", required = false) List<MultipartFile> images) {
        Viewer viewer = Viewer.of(authentication);
        rateLimits.imageUpload(viewer.userId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(imageService.upload(id, viewer, images));
    }

    @GetMapping("/{imageId}")
    @Operation(
            summary = "A photo",
            description = "Public. The picture itself, with the content type found when it was uploaded. The address is unguessable "
                    + "(two random ids) and is the only protection: it works in every listing status so that sellers see their own "
                    + "photos in an img tag. Cached for a day.")
    @ApiResponse(responseCode = "404", description = "No such photo of this listing (NOT_FOUND)")
    public ResponseEntity<Resource> image(@PathVariable UUID id, @PathVariable UUID imageId) {
        ListingImageService.ImageContent content = imageService.load(id, imageId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.sizeBytes())
                .cacheControl(CACHE_ONE_DAY)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().build().toString())
                // A stored file is never allowed to run as anything but a picture, even if a browser were fooled about its type.
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .body(content.resource());
    }

    @PutMapping("/order")
    @Operation(
            summary = "Put the photos in order",
            description = "The seller only. List every photo id of the listing exactly once; the first becomes the main photo. "
                    + "To make a photo the main one, move it to the front.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponse(responseCode = "400", description = "The list does not hold every photo exactly once (VALIDATION_FAILED, field `imageIds`)")
    @ApiResponse(responseCode = "403", description = "The listing is someone else's (FORBIDDEN)")
    @ApiResponse(responseCode = "404", description = "No such listing, or the caller may not see it (NOT_FOUND)")
    public ResponseEntity<List<ListingImageResponse>> reorder(
            @PathVariable UUID id, Authentication authentication, @Valid @RequestBody ReorderImagesRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(imageService.reorder(id, Viewer.of(authentication), request));
    }

    @DeleteMapping("/{imageId}")
    @Operation(
            summary = "Remove a photo",
            description = "The seller only. The photos behind it move up; if it was the main photo the next one becomes the main photo. "
                    + "Returns the gallery that is left. A live listing keeps at least one photo.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponse(responseCode = "403", description = "The listing is someone else's (FORBIDDEN)")
    @ApiResponse(responseCode = "404", description = "No such listing or photo, or the caller may not see the listing (NOT_FOUND)")
    @ApiResponse(responseCode = "422", description = "It is the only photo of a live listing (BUSINESS_RULE_VIOLATION)")
    public ResponseEntity<List<ListingImageResponse>> delete(
            @PathVariable UUID id, @PathVariable UUID imageId, Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(imageService.delete(id, imageId, Viewer.of(authentication)));
    }
}
