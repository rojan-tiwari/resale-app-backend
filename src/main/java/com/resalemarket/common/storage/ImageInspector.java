package com.resalemarket.common.storage;

/**
 * Decides what a file really is by reading its bytes, and ignores what the client claims (file name, {@code Content-Type}
 * header), which an attacker controls. Accepts JPEG, PNG and WebP only.
 *
 * <p>It reads the magic number and the size from the header of each format and <b>never decodes the pixels</b>, so a small file
 * that would expand to gigabytes (a decompression bomb) costs nothing here. The size limits below protect the people who
 * later open the picture in a browser. A file that passes is stored under a name the server makes up and is always served with
 * the {@code Content-Type} of the detected format, so a file that is "an image and something else" is never run as the other thing.
 */
public final class ImageInspector {

    /** No side may be longer than this many pixels. */
    public static final int MAX_SIDE = 12_000;

    /** Width times height may not exceed this (50 megapixels: more than any phone photo that fits the upload limit). */
    public static final long MAX_PIXELS = 50_000_000L;

    private static final String NOT_AN_IMAGE = "is not a JPEG, PNG or WebP image.";
    private static final String DAMAGED = "looks damaged and could not be read.";

    private ImageInspector() {
    }

    /**
     * @throws InvalidImageException if the bytes are not a readable JPEG, PNG or WebP of acceptable size
     */
    public static InspectedImage inspect(byte[] data) throws InvalidImageException {
        if (data == null || data.length == 0) {
            throw new InvalidImageException("is empty.");
        }
        InspectedImage image;
        if (isJpeg(data)) {
            image = jpeg(data);
        } else if (isPng(data)) {
            image = png(data);
        } else if (isWebp(data)) {
            image = webp(data);
        } else {
            throw new InvalidImageException(NOT_AN_IMAGE);
        }
        checkSize(image.width(), image.height());
        return image;
    }

    private static void checkSize(int width, int height) throws InvalidImageException {
        if (width < 1 || height < 1) {
            throw new InvalidImageException(DAMAGED);
        }
        if (width > MAX_SIDE || height > MAX_SIDE || (long) width * height > MAX_PIXELS) {
            throw new InvalidImageException("has too many pixels (at most " + MAX_SIDE + " on a side and 50 megapixels in all).");
        }
    }

    // ---------------------------------------------------------------------------------------------------- JPEG

    private static boolean isJpeg(byte[] d) {
        return d.length >= 4 && u8(d, 0) == 0xFF && u8(d, 1) == 0xD8 && u8(d, 2) == 0xFF;
    }

    /** Walks the marker segments until the "start of frame", which holds the size. */
    private static InspectedImage jpeg(byte[] d) throws InvalidImageException {
        int i = 2;
        while (i < d.length) {
            if (u8(d, i) != 0xFF) {
                throw new InvalidImageException(DAMAGED);
            }
            while (i < d.length && u8(d, i) == 0xFF) {
                i++;                                           // fill bytes may repeat the 0xFF
            }
            if (i >= d.length) {
                break;
            }
            int marker = u8(d, i++);
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) {
                continue;                                      // markers without a length
            }
            if (marker == 0xD9 || marker == 0xDA) {
                break;                                         // end of image / start of scan before any frame header
            }
            if (i + 2 > d.length) {
                break;
            }
            int length = u16be(d, i);
            if (length < 2) {
                throw new InvalidImageException(DAMAGED);
            }
            boolean startOfFrame = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (startOfFrame) {
                if (i + 7 > d.length) {
                    break;
                }
                return new InspectedImage(ImageType.JPEG, u16be(d, i + 5), u16be(d, i + 3));
            }
            i += length;
        }
        throw new InvalidImageException(DAMAGED);
    }

    // ----------------------------------------------------------------------------------------------------- PNG

    private static boolean isPng(byte[] d) {
        return d.length >= 8 && u8(d, 0) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G'
                && u8(d, 4) == 0x0D && u8(d, 5) == 0x0A && u8(d, 6) == 0x1A && u8(d, 7) == 0x0A;
    }

    /** The first chunk of a PNG is always IHDR: 4 bytes length (13), the name, then width and height. */
    private static InspectedImage png(byte[] d) throws InvalidImageException {
        boolean headerChunk = d.length >= 24 && u32be(d, 8) == 13 && d[12] == 'I' && d[13] == 'H' && d[14] == 'D' && d[15] == 'R';
        if (!headerChunk) {
            throw new InvalidImageException(DAMAGED);
        }
        long width = u32be(d, 16);
        long height = u32be(d, 20);
        if (width > Integer.MAX_VALUE || height > Integer.MAX_VALUE) {
            throw new InvalidImageException(DAMAGED);
        }
        return new InspectedImage(ImageType.PNG, (int) width, (int) height);
    }

    // ---------------------------------------------------------------------------------------------------- WebP

    private static boolean isWebp(byte[] d) {
        return d.length >= 16 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P';
    }

    /** A RIFF container whose first chunk is lossy ({@code VP8 }), lossless ({@code VP8L}) or extended ({@code VP8X}). */
    private static InspectedImage webp(byte[] d) throws InvalidImageException {
        long declared = u32le(d, 4) + 8;
        if (declared > d.length) {
            throw new InvalidImageException("looks damaged: the file is shorter than it says.");
        }
        String chunk = new String(d, 12, 4, java.nio.charset.StandardCharsets.US_ASCII);
        int data = 20;                                          // the chunk's payload starts after its 8-byte header
        switch (chunk) {
            case "VP8 " -> {
                // 3-byte frame tag, the start code 9D 01 2A, then 14-bit width and height (the top 2 bits are scaling).
                if (d.length < data + 10 || u8(d, data + 3) != 0x9D || u8(d, data + 4) != 0x01 || u8(d, data + 5) != 0x2A) {
                    throw new InvalidImageException(DAMAGED);
                }
                return new InspectedImage(ImageType.WEBP, u16le(d, data + 6) & 0x3FFF, u16le(d, data + 8) & 0x3FFF);
            }
            case "VP8L" -> {
                // Signature 0x2F, then 14 bits of (width - 1) and 14 bits of (height - 1), little-endian bit order.
                if (d.length < data + 5 || u8(d, data) != 0x2F) {
                    throw new InvalidImageException(DAMAGED);
                }
                int b1 = u8(d, data + 1);
                int b2 = u8(d, data + 2);
                int b3 = u8(d, data + 3);
                int b4 = u8(d, data + 4);
                int width = 1 + (((b2 & 0x3F) << 8) | b1);
                int height = 1 + (((b4 & 0x0F) << 10) | (b3 << 2) | ((b2 & 0xC0) >> 6));
                return new InspectedImage(ImageType.WEBP, width, height);
            }
            case "VP8X" -> {
                // 1 byte of flags, 3 reserved, then the canvas size minus one as 24-bit little-endian numbers.
                if (d.length < data + 10) {
                    throw new InvalidImageException(DAMAGED);
                }
                return new InspectedImage(ImageType.WEBP, 1 + u24le(d, data + 4), 1 + u24le(d, data + 7));
            }
            default -> throw new InvalidImageException(NOT_AN_IMAGE);
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static int u8(byte[] d, int i) {
        return d[i] & 0xFF;
    }

    private static int u16be(byte[] d, int i) {
        return (u8(d, i) << 8) | u8(d, i + 1);
    }

    private static int u16le(byte[] d, int i) {
        return u8(d, i) | (u8(d, i + 1) << 8);
    }

    private static int u24le(byte[] d, int i) {
        return u8(d, i) | (u8(d, i + 1) << 8) | (u8(d, i + 2) << 16);
    }

    private static long u32be(byte[] d, int i) {
        return ((long) u8(d, i) << 24) | (u8(d, i + 1) << 16) | (u8(d, i + 2) << 8) | u8(d, i + 3);
    }

    private static long u32le(byte[] d, int i) {
        return u8(d, i) | (u8(d, i + 1) << 8) | (u8(d, i + 2) << 16) | ((long) u8(d, i + 3) << 24);
    }
}
