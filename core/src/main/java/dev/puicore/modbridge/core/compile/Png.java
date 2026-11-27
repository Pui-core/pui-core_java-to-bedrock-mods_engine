package dev.puicore.modbridge.core.compile;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;

/**
 * テクスチャ PNG の検査。展開前にヘッダーで縦横を確かめ、大きすぎる画像は展開しない（展開爆弾対策）。
 *
 * <p>透過の種類は、Bedrock 側の描画方法（不透明・切り抜き・半透明）を決めるために使う。
 */
public final class Png {
    /** 縦横の上限。Minecraft のテクスチャは通常 16〜512 px。 */
    public static final int MAX_SIDE = 4096;
    /** 展開して検査する画素数の上限（2048x2048）。これを超える PNG は検査できないので使わない。 */
    static final long MAX_DECODE_PIXELS = 4L << 20;

    public enum Alpha { OPAQUE, CUTOUT, TRANSLUCENT }

    public record Info(int width, int height, Alpha alpha) {}

    /** 不正な PNG・大きすぎる PNG。メッセージは固定の文言。 */
    public static final class PngException extends Exception {
        private static final long serialVersionUID = 1L;

        PngException(String message) {
            super(message);
        }
    }

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    private Png() {}

    public static Info inspect(byte[] png) throws PngException {
        if (png.length < 33) {
            throw new PngException("PNG として短すぎる");
        }
        for (int i = 0; i < SIGNATURE.length; i++) {
            if (png[i] != SIGNATURE[i]) {
                throw new PngException("PNG の署名ではない");
            }
        }
        ByteBuffer b = ByteBuffer.wrap(png);
        if (b.getInt(8) != 13 || !"IHDR".equals(new String(png, 12, 4, StandardCharsets.US_ASCII))) {
            throw new PngException("IHDR が先頭に無い");
        }
        long width = Integer.toUnsignedLong(b.getInt(16));
        long height = Integer.toUnsignedLong(b.getInt(20));
        int colorType = png[25] & 0xff;
        if (width == 0 || height == 0 || width > MAX_SIDE || height > MAX_SIDE) {
            throw new PngException("縦横が 0 か、上限（" + MAX_SIDE + " px）を超えている: " + width + "x" + height);
        }
        if (width * height > MAX_DECODE_PIXELS) {
            throw new PngException("画素数が上限（" + MAX_DECODE_PIXELS + "）を超えていて検査できない: " + width + "x" + height);
        }
        boolean mayHaveAlpha = colorType == 4 || colorType == 6 || hasChunk(png, "tRNS");
        // 壊れた PNG をパックに入れないため、透過の有無にかかわらず必ず展開して確かめる
        BufferedImage img;
        try {
            ImageIO.setUseCache(false);
            img = ImageIO.read(new ByteArrayInputStream(png));
        } catch (IOException | RuntimeException e) {
            throw new PngException("PNG を展開できない（" + e.getClass().getSimpleName() + "）");
        }
        if (img == null || img.getWidth() != width || img.getHeight() != height) {
            throw new PngException("PNG を展開できない");
        }
        if (!mayHaveAlpha) {
            return new Info((int) width, (int) height, Alpha.OPAQUE);
        }
        boolean cut = false;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int a = img.getRGB(x, y) >>> 24;
                if (a != 0 && a != 255) {
                    return new Info((int) width, (int) height, Alpha.TRANSLUCENT);
                }
                cut |= a == 0;
            }
        }
        return new Info((int) width, (int) height, cut ? Alpha.CUTOUT : Alpha.OPAQUE);
    }

    /** IDAT より前に指定のチャンクがあるか（tRNS は IDAT より前に置く決まり）。 */
    private static boolean hasChunk(byte[] png, String type) {
        int pos = 8;
        while (pos + 8 <= png.length) {
            long len = Integer.toUnsignedLong(ByteBuffer.wrap(png, pos, 4).getInt());
            String t = new String(png, pos + 4, 4, StandardCharsets.US_ASCII);
            if (t.equals(type)) {
                return true;
            }
            if (t.equals("IDAT") || t.equals("IEND")) {
                return false;
            }
            long next = pos + 12L + len;
            if (next > png.length) {
                return false;
            }
            pos = (int) next;
        }
        return false;
    }
}
