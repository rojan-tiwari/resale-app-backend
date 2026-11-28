package com.resalemarket.common.storage;

/** What {@link ImageInspector} found out about a file by reading its bytes: the real format and the pixel size from its header. */
public record InspectedImage(ImageType type, int width, int height) {
}
