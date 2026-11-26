package com.resalemarket.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.resalemarket.support.TestImages;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ImageInspectorTest {

    @Test
    void recognisesARealJpegAndReadsItsSize() throws Exception {
        InspectedImage image = ImageInspector.inspect(TestImages.jpeg(120, 80));

        assertThat(image.type()).isEqualTo(ImageType.JPEG);
        assertThat(image.width()).isEqualTo(120);
        assertThat(image.height()).isEqualTo(80);
        assertThat(image.type().contentType()).isEqualTo("image/jpeg");
        assertThat(image.type().extension()).isEqualTo("jpg");
    }

    @Test
    void recognisesARealPngAndReadsItsSize() throws Exception {
        InspectedImage image = ImageInspector.inspect(TestImages.png(64, 200));

        assertThat(image.type()).isEqualTo(ImageType.PNG);
        assertThat(image.width()).isEqualTo(64);
        assertThat(image.height()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 300, 4000, 12000})
    void readsTheSizeOfALosslessWebp(int side) throws Exception {
        InspectedImage image = ImageInspector.inspect(TestImages.webp(side, 17));

        assertThat(image.type()).isEqualTo(ImageType.WEBP);
        assertThat(image.width()).isEqualTo(side);
        assertThat(image.height()).isEqualTo(17);
    }

    @Test
    void readsTheSizeOfALossyWebp() throws Exception {
        byte[] riff = webpChunk("VP8 ", new byte[] {0, 0, 0, (byte) 0x9D, 0x01, 0x2A, (byte) 0x90, 0x01, (byte) 0xC8, 0x00});

        InspectedImage image = ImageInspector.inspect(riff);

        assertThat(image.width()).isEqualTo(400);
        assertThat(image.height()).isEqualTo(200);
    }

    @Test
    void readsTheSizeOfAnExtendedWebp() throws Exception {
        // VP8X: flags, 3 reserved bytes, then canvas width - 1 and height - 1 as 24-bit little-endian: 1000 x 500.
        byte[] riff = webpChunk("VP8X", new byte[] {0, 0, 0, 0, (byte) 0xE7, 0x03, 0x00, (byte) 0xF3, 0x01, 0x00});

        InspectedImage image = ImageInspector.inspect(riff);

        assertThat(image.width()).isEqualTo(1000);
        assertThat(image.height()).isEqualTo(500);
    }

    @Test
    void theContentDecidesNotTheName() {
        // An HTML page and a script are refused whatever they are called; there is no file name in the signature at all.
        assertInvalid("<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8));
        assertInvalid("<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8));
        assertInvalid("%PDF-1.7 ...".getBytes(StandardCharsets.US_ASCII));
        assertInvalid("GIF89a......".getBytes(StandardCharsets.US_ASCII));
        assertInvalid(new byte[] {0x4D, 0x5A, (byte) 0x90, 0x00, 0x03});        // a Windows executable
    }

    @Test
    void refusesEmptyInput() {
        assertThatThrownBy(() -> ImageInspector.inspect(new byte[0])).isInstanceOf(InvalidImageException.class).hasMessageContaining("empty");
        assertThatThrownBy(() -> ImageInspector.inspect(null)).isInstanceOf(InvalidImageException.class);
    }

    @Test
    void refusesAJpegThatIsCutOffBeforeItsHeaderEnds() {
        byte[] jpeg = TestImages.jpeg(50, 50);

        assertInvalid(Arrays.copyOf(jpeg, 8));
    }

    @Test
    void refusesAPngWithoutItsHeaderChunk() {
        byte[] png = TestImages.png(50, 50);
        png[12] = 'X';                                     // IHDR -> XHDR

        assertInvalid(png);
        assertInvalid(Arrays.copyOf(png, 20));
    }

    @Test
    void refusesAWebpShorterThanItClaims() {
        byte[] webp = TestImages.webp(100, 100);

        assertThatThrownBy(() -> ImageInspector.inspect(Arrays.copyOf(webp, webp.length - 4)))
                .isInstanceOf(InvalidImageException.class)
                .hasMessageContaining("damaged");
    }

    @Test
    void refusesARiffContainerThatIsNotAWebp() {
        byte[] wave = TestImages.webp(10, 10);
        wave[8] = 'A';                                     // WEBP -> AEBP

        assertInvalid(wave);
    }

    @Test
    void refusesImagesWithTooManyPixelsWithoutDecodingThem() {
        // A 30 KB file whose header claims 12 000 x 12 000 would need hundreds of MB to decode: it is refused from the header alone.
        assertThatThrownBy(() -> ImageInspector.inspect(TestImages.webp(12_000, 12_000)))
                .isInstanceOf(InvalidImageException.class)
                .hasMessageContaining("too many pixels");
        assertThatThrownBy(() -> ImageInspector.inspect(TestImages.webp(16_000, 10)))
                .isInstanceOf(InvalidImageException.class)
                .hasMessageContaining("too many pixels");
    }

    @Test
    void acceptsTheLargestImageAllowed() throws Exception {
        InspectedImage image = ImageInspector.inspect(TestImages.webp(7_000, 7_000));   // 49 megapixels

        assertThat(image.width()).isEqualTo(7_000);
    }

    @Test
    void refusesAZeroSizedPng() {
        byte[] png = TestImages.png(10, 10);
        for (int i = 16; i < 20; i++) {
            png[i] = 0;                                    // width = 0
        }

        assertInvalid(png);
    }

    private static void assertInvalid(byte[] bytes) {
        assertThatThrownBy(() -> ImageInspector.inspect(bytes)).isInstanceOf(InvalidImageException.class);
    }

    private static byte[] webpChunk(String fourCc, byte[] payload) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(le(4 + 8 + payload.length));
        out.writeBytes("WEBP".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(fourCc.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(le(payload.length));
        out.writeBytes(payload);
        return out.toByteArray();
    }

    private static byte[] le(int value) {
        return new byte[] {(byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24)};
    }
}
