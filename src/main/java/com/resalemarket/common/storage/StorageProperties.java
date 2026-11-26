package com.resalemarket.common.storage;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where files are kept, bound from {@code app.storage.*}.
 *
 * @param type which implementation to use; only {@code local} (a directory on the server's disk) exists so far
 * @param root the directory of the local implementation. Relative paths are resolved against the working directory of the
 *             process. Keep it outside the web root and back it up with the database: the rows say which files should exist.
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        @DefaultValue("local") String type,
        @DefaultValue("./data/uploads") Path root) {
}
