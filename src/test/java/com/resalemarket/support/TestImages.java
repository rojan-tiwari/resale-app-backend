package com.resalemarket.support;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;

/** Small, valid images for upload tests. JPEG and PNG are real files made by the JDK; the WebP has a real header (that is all the server reads). */
public final class TestImages {

    private TestImages() {
    }

    public static byte[] jpeg(int width, int height) {
        return encode(width, height, "jpg", BufferedImage.TYPE_INT_RGB);
    }

    public static byte[] png(int width, int height) {
        return encode(width, height, "png", BufferedImage.TYPE_INT_ARGB);
    }

    /** A lossless WebP ("VP8L") header for the given size followed by padding: recognisable by content, not decodable. */
    public static byte[] webp(int width, int height) {
        int w = width - 1;
        int h = height - 1;
        byte[] payload = new byte[10];
        payload[0] = 0x2F;
        payload[1] = (byte) (w & 0xFF);
        payload[2] = (byte) (((w >> 8) & 0x3F) | ((h & 0x03) << 6));
        payload[3] = (byte) ((h >> 2) & 0xFF);
        payload[4] = (byte) ((h >> 10) & 0x0F);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(littleEndian(4 + 8 + payload.length));
        out.writeBytes("WEBP".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes("VP8L".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(littleEndian(payload.length));
        out.writeBytes(payload);
        return out.toByteArray();
    }

    private static byte[] encode(int width, int height, String format, int type) {
        BufferedImage image = new BufferedImage(width, height, type);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalStateException("No writer for " + format);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return out.toByteArray();
    }

    private static byte[] littleEndian(int value) {
        return new byte[] {(byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24)};
    }
}
