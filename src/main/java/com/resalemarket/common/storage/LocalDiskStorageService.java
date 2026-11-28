package com.resalemarket.common.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Keeps files in a directory tree on the server's disk (development and single-server deployments).
 *
 * <p>Safety rules, in the order they matter:
 * <ol>
 *   <li>A key must match {@link #KEY}: segments of lower-case letters, digits, {@code . _ -}, each starting with a letter or
 *       digit, joined by {@code /}. That rules out {@code ..}, absolute paths, backslashes and empty segments.</li>
 *   <li>As a second line of defence the resolved path must still be inside the root directory.</li>
 *   <li>A file is written to a temporary name and moved into place, so a concurrent reader (or a crash) never sees a partial file.</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(prefix = "app.storage", name = "type", havingValue = "local", matchIfMissing = true)
class LocalDiskStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalDiskStorageService.class);

    /** The shape of every key this storage accepts; longer than the {@code storage_key} column (255) is refused. */
    static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9._-]*(/[a-z0-9][a-z0-9._-]*)*");
    static final int MAX_KEY_LENGTH = 255;

    private final Path root;

    LocalDiskStorageService(StorageProperties properties) {
        this.root = properties.root().toAbsolutePath().normalize();
        log.info("Uploaded files are stored on local disk under {}", root);
    }

    @Override
    public void store(String key, byte[] content) {
        Path target = resolve(key);
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            Files.write(temporary, content);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            if (temporary != null) {
                deleteQuietly(temporary);
            }
            throw new StorageException("Could not store the file under key '" + key + "'.", ex);
        }
    }

    @Override
    public Resource load(String key) {
        Path path = resolve(key);
        if (!Files.isRegularFile(path)) {
            throw new StorageObjectNotFoundException(key);
        }
        return new FileSystemResource(path);
    }

    @Override
    public void delete(String key) {
        Path path = resolve(key);
        try {
            Files.deleteIfExists(path);
        } catch (NoSuchFileException ignored) {
            // already gone: removing is idempotent
        } catch (IOException ex) {
            throw new StorageException("Could not delete the file under key '" + key + "'.", ex);
        }
    }

    private Path resolve(String key) {
        if (key == null || key.length() > MAX_KEY_LENGTH || !KEY.matcher(key).matches()) {
            throw new StorageException("Not a valid storage key.");
        }
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root) || path.equals(root)) {
            throw new StorageException("Not a valid storage key.");
        }
        return path;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            log.warn("Could not remove temporary file {}", path.getFileName());
        }
    }
}
