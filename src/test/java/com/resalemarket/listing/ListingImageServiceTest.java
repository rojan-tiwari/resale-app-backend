package com.resalemarket.listing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.resalemarket.common.exception.BusinessRuleException;
import com.resalemarket.common.exception.FieldValidationException;
import com.resalemarket.common.storage.StorageException;
import com.resalemarket.common.storage.StorageObjectNotFoundException;
import com.resalemarket.common.storage.StorageService;
import com.resalemarket.listing.entity.Listing;
import com.resalemarket.listing.entity.ListingImage;
import com.resalemarket.listing.entity.ListingStatus;
import com.resalemarket.support.TestImages;
import com.resalemarket.user.UserService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.unit.DataSize;

/**
 * Files and database rows must never drift apart. The service ties the files to the transaction: new files vanish again when the
 * transaction does not commit, and the file of a removed photo is only deleted once the removal is safely committed.
 */
@ExtendWith(MockitoExtension.class)
class ListingImageServiceTest {

    @Mock
    private ListingImageRepository imageRepository;

    @Mock
    private ListingAccess access;

    @Mock
    private StorageService storage;

    @Mock
    private UserService userService;

    private final UUID sellerId = UUID.randomUUID();
    private final Viewer seller = new Viewer(sellerId, false);
    private final ListingProperties properties = new ListingProperties(false, DataSize.ofMegabytes(5), new ListingProperties.RateLimits(null, null));

    private ListingImageService service;
    private Listing listing;

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();     // what @Transactional does around a real call
        service = new ListingImageService(imageRepository, access, storage, userService, properties);
        listing = ListingFixtures.listing(sellerId, ListingStatus.DRAFT);
        // Not every test goes through the ownership check (reading a picture is public), so this stub is not required to be used.
        org.mockito.Mockito.lenient().when(access.ownedForUpdate(listing.getId(), seller)).thenReturn(listing);
    }

    @AfterEach
    void endTransaction() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    private static MockMultipartFile jpeg(String name) {
        return new MockMultipartFile("images", name, "image/jpeg", TestImages.jpeg(20, 20));
    }

    private void finishTransaction(int status) {
        for (TransactionSynchronization synchronization : List.copyOf(TransactionSynchronizationManager.getSynchronizations())) {
            if (status == TransactionSynchronization.STATUS_COMMITTED) {
                synchronization.afterCommit();
            }
            synchronization.afterCompletion(status);
        }
    }

    // --- upload ------------------------------------------------------------------------------------------------------

    @Test
    void filesStoredBeforeAFailureAreRemovedWhenTheTransactionRollsBack() {
        doNothing().doThrow(new StorageException("disk full")).when(storage).store(anyString(), any());

        assertThatThrownBy(() -> service.upload(listing.getId(), seller, List.of(jpeg("a.jpg"), jpeg("b.jpg"))))
                .isInstanceOf(StorageException.class);
        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(storage, org.mockito.Mockito.times(2)).store(stored.capture(), any());
        String firstKey = stored.getAllValues().get(0);

        finishTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(storage).delete(firstKey);
        verify(imageRepository, never()).saveAll(any());
    }

    @Test
    void aCommittedUploadKeepsItsFiles() {
        service.upload(listing.getId(), seller, List.of(jpeg("a.jpg")));

        finishTransaction(TransactionSynchronization.STATUS_COMMITTED);

        verify(storage).store(anyString(), any());
        verify(storage, never()).delete(anyString());
    }

    @Test
    void aFailureWhileCleaningUpDoesNotHideTheRealProblemOrStopTheOtherDeletes() {
        doNothing().doNothing().doThrow(new StorageException("disk full")).when(storage).store(anyString(), any());
        doThrow(new StorageException("cannot delete")).when(storage).delete(anyString());

        assertThatThrownBy(() -> service.upload(listing.getId(), seller, List.of(jpeg("a.jpg"), jpeg("b.jpg"), jpeg("c.jpg"))))
                .isInstanceOf(StorageException.class)
                .hasMessage("disk full");

        finishTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(storage, org.mockito.Mockito.times(2)).delete(anyString());
    }

    @Test
    void nothingIsStoredWhenOneFileOfTheRequestIsNotAnImage() {
        MockMultipartFile bad = new MockMultipartFile("images", "bad.jpg", "image/jpeg", "<html>".getBytes());

        assertThatThrownBy(() -> service.upload(listing.getId(), seller, List.of(jpeg("good.jpg"), bad)))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        ex -> assertThat(ex.getViolations().get(0).message()).isEqualTo("bad.jpg is not a JPEG, PNG or WebP image."));

        verify(storage, never()).store(anyString(), any());
    }

    @Test
    void aFileOverTheConfiguredLimitIsRefusedBeforeItsContentIsRead() {
        ListingProperties tiny = new ListingProperties(false, DataSize.ofBytes(100), new ListingProperties.RateLimits(null, null));
        ListingImageService strict = new ListingImageService(imageRepository, access, storage, userService, tiny);

        assertThatThrownBy(() -> strict.upload(listing.getId(), seller, List.of(jpeg("big.jpg"))))
                .isInstanceOf(FieldValidationException.class)
                .hasMessage("One or more fields are invalid.");
        verify(storage, never()).store(anyString(), any());
    }

    @Test
    void theNinthPhotoIsRefusedWithoutReadingAnyFile() {
        when(imageRepository.countByListingId(listing.getId())).thenReturn(8L);

        assertThatThrownBy(() -> service.upload(listing.getId(), seller, List.of(jpeg("ninth.jpg"))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("A listing can have at most 8 photos; this one has 8.");
        verify(storage, never()).store(anyString(), any());
    }

    @Test
    void nothingToUploadIsAFieldProblem() {
        assertThatThrownBy(() -> service.upload(listing.getId(), seller, null)).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.upload(listing.getId(), seller, List.of())).isInstanceOf(FieldValidationException.class);
    }

    @Test
    void aSuspendedAccountCannotUpload() {
        org.mockito.Mockito.doThrow(new com.resalemarket.common.exception.UnauthorizedException("ended")).when(userService).requireActive(sellerId);

        assertThatThrownBy(() -> service.upload(listing.getId(), seller, List.of(jpeg("a.jpg"))))
                .isInstanceOf(com.resalemarket.common.exception.UnauthorizedException.class);
        verify(storage, never()).store(anyString(), any());
    }

    // --- delete ------------------------------------------------------------------------------------------------------

    @Test
    void theFileOfARemovedPhotoGoesOnlyAfterTheCommit() {
        ListingImage photo = photo(0);
        ListingImage other = photo(1);
        when(imageRepository.findByIdAndListingId(photo.getId(), listing.getId())).thenReturn(java.util.Optional.of(photo));
        when(imageRepository.findByListingIdOrderBySortOrderAsc(listing.getId())).thenReturn(List.of(photo, other));

        service.delete(listing.getId(), photo.getId(), seller);

        verify(storage, never()).delete(anyString());                         // nothing yet: the removal is not committed
        assertThat(other.getSortOrder()).isZero();
        assertThat(other.isPrimary()).isTrue();

        finishTransaction(TransactionSynchronization.STATUS_COMMITTED);

        verify(storage).delete(photo.getStorageKey());
    }

    @Test
    void aRolledBackRemovalKeepsTheFile() {
        ListingImage photo = photo(0);
        ListingImage other = photo(1);
        when(imageRepository.findByIdAndListingId(photo.getId(), listing.getId())).thenReturn(java.util.Optional.of(photo));
        when(imageRepository.findByListingIdOrderBySortOrderAsc(listing.getId())).thenReturn(List.of(photo, other));

        service.delete(listing.getId(), photo.getId(), seller);
        finishTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(storage, never()).delete(anyString());
    }

    @Test
    void aFailingFileDeleteAfterTheCommitIsLoggedNotThrown() {
        ListingImage photo = photo(0);
        when(imageRepository.findByIdAndListingId(photo.getId(), listing.getId())).thenReturn(java.util.Optional.of(photo));
        when(imageRepository.findByListingIdOrderBySortOrderAsc(listing.getId())).thenReturn(List.of(photo));
        doThrow(new StorageException("busy")).when(storage).delete(anyString());

        service.delete(listing.getId(), photo.getId(), seller);

        org.assertj.core.api.Assertions.assertThatCode(() -> finishTransaction(TransactionSynchronization.STATUS_COMMITTED))
                .doesNotThrowAnyException();
    }

    // --- load --------------------------------------------------------------------------------------------------------

    @Test
    void aMissingFileIsNotFoundForTheClient() {
        ListingImage photo = photo(0);
        when(imageRepository.findByIdAndListingId(photo.getId(), listing.getId())).thenReturn(java.util.Optional.of(photo));
        when(storage.load(photo.getStorageKey())).thenThrow(new StorageObjectNotFoundException(photo.getStorageKey()));

        assertThatThrownBy(() -> service.load(listing.getId(), photo.getId()))
                .isInstanceOf(com.resalemarket.common.exception.ResourceNotFoundException.class);
    }

    private ListingImage photo(int position) {
        ListingImage image = ListingImage.of(listing.getId(), "listing-images/" + UUID.randomUUID() + ".jpg", "image/jpeg", 100, 20, 20, position);
        ReflectionTestUtils.setField(image, "id", UUID.randomUUID());
        return image;
    }
}
