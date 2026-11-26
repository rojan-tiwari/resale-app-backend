package com.resalemarket.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LocalDiskStorageServiceTest {

    @TempDir
    Path root;

    private LocalDiskStorageService storage;

    @BeforeEach
    void setUp() {
        storage = new LocalDiskStorageService(new StorageProperties("local", root));
    }

    @Test
    void storesAndLoadsAFile() throws IOException {
        storage.store("listing-images/abc-123.jpg", new byte[] {1, 2, 3});

        assertThat(storage.load("listing-images/abc-123.jpg").getContentAsByteArray()).containsExactly(1, 2, 3);
        assertThat(root.resolve("listing-images/abc-123.jpg")).exists();
    }

    @Test
    void leavesNoTemporaryFileBehind() throws IOException {
        storage.store("a/b/c.png", new byte[] {9});

        try (Stream<Path> files = Files.walk(root)) {
            assertThat(files.filter(Files::isRegularFile).map(path -> path.getFileName().toString())).containsExactly("c.png");
        }
    }

    @Test
    void deletingIsIdempotent() {
        storage.store("x/y.webp", new byte[] {1});

        storage.delete("x/y.webp");
        storage.delete("x/y.webp");

        assertThat(root.resolve("x/y.webp")).doesNotExist();
    }

    @Test
    void loadingAMissingFileIsNotFound() {
        assertThatThrownBy(() -> storage.load("nothing/here.jpg")).isInstanceOf(StorageObjectNotFoundException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "../escape.jpg", "a/../../escape.jpg", "/etc/passwd", "a//b.jpg", "a\\b.jpg", "UPPER.jpg", "with space.jpg",
        ".hidden", "a/.hidden", "", "a/", "..", "a/..", "%2e%2e/x", "café.jpg", "a\u0000b.jpg"})
    void refusesKeysThatCouldLeaveTheStorage(String key) {
        assertThatThrownBy(() -> storage.store(key, new byte[] {1})).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> storage.load(key)).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> storage.delete(key)).isInstanceOf(StorageException.class);
        assertThat(root.getParent().resolve("escape.jpg")).doesNotExist();
    }

    @Test
    void refusesAMissingOrOverlongKey() {
        assertThatThrownBy(() -> storage.store(null, new byte[] {1})).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> storage.store("a".repeat(256), new byte[] {1})).isInstanceOf(StorageException.class);
        storage.store("a".repeat(255), new byte[] {1});
    }

    @Test
    void aDirectoryIsNotAFile() throws IOException {
        Files.createDirectories(root.resolve("folder"));

        assertThatThrownBy(() -> storage.load("folder")).isInstanceOf(StorageObjectNotFoundException.class);
    }
}
