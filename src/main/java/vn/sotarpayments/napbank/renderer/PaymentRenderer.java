package vn.sotarpayments.napbank.renderer;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import vn.sotarpayments.SotarPayments;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.EnumMap;

public class PaymentRenderer extends MapRenderer {
    private static final int MAP_SIZE = 128;
    private static final int BLACK = Color.BLACK.getRGB();
    private static final int WHITE = Color.WHITE.getRGB();

    private static final int QR_MARGIN = 4;
    private static final int QUIET_ZONE = 1;
    private static final int REMOTE_SIZE = 512;
    private static final int REMOTE_TIMEOUT_MS = 5000;
    private static final int BLACK_WHITE_THRESHOLD = 180;

    private volatile BufferedImage finalImage;
    private boolean done = false;

    public PaymentRenderer(String qrData) {
        SotarPayments plugin = SotarPayments.getInstance();
        if (plugin == null) {
            this.finalImage = buildErrorImage();
            return;
        }
        plugin.getPlatformScheduler().runAsync(() -> {
            try {
                this.finalImage = buildQrOnlyImage(qrData);
            } catch (Exception e) {
                plugin.logWarning("Cannot render QR map: " + e.getMessage(), e);
                this.finalImage = buildErrorImage();
            }
        });
    }

    private static BufferedImage buildQrOnlyImage(String qrData) throws Exception {
        String raw = qrData == null ? "" : qrData.trim();
        boolean isUrl = raw.startsWith("http://") || raw.startsWith("https://");

        if (!isUrl) {
            return buildLocalQrMap(raw);
        }

        BufferedImage source = readImageUrl(forceVietQrOnlyUrl(raw));
        return fitQrToMap(source);
    }

    private static BufferedImage buildLocalQrMap(String qrContent) throws Exception {
        int qrSize = Math.max(64, MAP_SIZE - (QR_MARGIN * 2));

        java.util.Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, QUIET_ZONE);

        BitMatrix matrix = new MultiFormatWriter().encode(
                qrContent == null ? "" : qrContent,
                BarcodeFormat.QR_CODE,
                qrSize,
                qrSize,
                hints
        );

        BufferedImage qr = bitMatrixToImage(matrix);
        return drawCenteredOnWhite(qr, MAP_SIZE, MAP_SIZE, QR_MARGIN);
    }

    @SuppressWarnings("unused")
    private static BufferedImage readQrFromRemoteGenerator(String qrContent) throws Exception {
        String encoded = java.net.URLEncoder.encode(qrContent == null ? "" : qrContent, java.nio.charset.StandardCharsets.UTF_8);
        String apiUrl = "https://api.qrserver.com/v1/create-qr-code/?size=" + REMOTE_SIZE + "x" + REMOTE_SIZE + "&margin=1&data=" + encoded;
        return readImageUrl(apiUrl);
    }

    private static BufferedImage readImageUrl(String imageUrl) throws Exception {
        URLConnection connection = new URL(imageUrl).openConnection();
        connection.setConnectTimeout(REMOTE_TIMEOUT_MS);
        connection.setReadTimeout(REMOTE_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 SotarPayments QR Renderer");
        connection.setRequestProperty("Accept", "image/png,image/*,*/*");

        String contentType = connection.getContentType();

        try (InputStream input = connection.getInputStream()) {
            BufferedImage image = ImageIO.read(input);

            if (image == null) {
                SotarPayments plugin = SotarPayments.getInstance();
                if (plugin != null) {
                    plugin.logDebug("QR image URL: " + imageUrl);
                    plugin.logDebug("QR image content-type: " + contentType);
                }
                throw new IllegalStateException("ImageIO cannot read QR image from URL");
            }

            return image;
        }
    }

    private static BufferedImage fitQrToMap(BufferedImage source) {
        BufferedImage prepared = forceBlackWhite(source);
        return drawCenteredOnWhite(prepared, MAP_SIZE, MAP_SIZE, QR_MARGIN);
    }

    private static BufferedImage bitMatrixToImage(BitMatrix matrix) {
        int width = matrix.getWidth();
        int height = matrix.getHeight();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, matrix.get(x, y) ? BLACK : WHITE);
            }
        }
        return image;
    }

    private static BufferedImage forceBlackWhite(BufferedImage source) {
        BufferedImage output = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);

        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int argb = source.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xff;
                if (alpha < 32) {
                    output.setRGB(x, y, WHITE);
                    continue;
                }

                int r = (argb >>> 16) & 0xff;
                int g = (argb >>> 8) & 0xff;
                int b = argb & 0xff;
                int gray = (r * 299 + g * 587 + b * 114) / 1000;
                output.setRGB(x, y, gray < BLACK_WHITE_THRESHOLD ? BLACK : WHITE);
            }
        }
        return output;
    }

    private static BufferedImage drawCenteredOnWhite(BufferedImage source, int canvasWidth, int canvasHeight, int margin) {
        BufferedImage output = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = output.createGraphics();
        applySharpHints(g);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, canvasWidth, canvasHeight);

        int maxWidth = Math.max(1, canvasWidth - margin * 2);
        int maxHeight = Math.max(1, canvasHeight - margin * 2);
        int x = (canvasWidth - maxWidth) / 2;
        int y = (canvasHeight - maxHeight) / 2;
        g.drawImage(source, x, y, maxWidth, maxHeight, null);
        g.dispose();
        return output;
    }

    private static void applySharpHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_DISABLE);
        g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_SPEED);
    }

    private static String forceVietQrOnlyUrl(String imageUrl) {
        String lower = imageUrl.toLowerCase(java.util.Locale.ROOT);
        if (!lower.contains("img.vietqr.io/image/") || !lower.contains(".png")) {
            return imageUrl;
        }

        int pngIndex = lower.indexOf(".png");
        int lastDashBeforePng = imageUrl.lastIndexOf('-', pngIndex);
        if (lastDashBeforePng < 0) {
            return imageUrl;
        }

        return imageUrl.substring(0, lastDashBeforePng + 1) + "qr_only" + imageUrl.substring(pngIndex);
    }

    private static BufferedImage buildErrorImage() {
        BufferedImage image = new BufferedImage(MAP_SIZE, MAP_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        applySharpHints(g);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, MAP_SIZE, MAP_SIZE);
        g.setColor(Color.BLACK);
        g.drawRect(3, 3, MAP_SIZE - 7, MAP_SIZE - 7);
        g.drawString("QR ERROR", 34, 58);
        g.drawString("/bank again", 31, 74);
        g.dispose();
        return image;
    }

    @Override
    public void render(MapView map, MapCanvas canvas, Player player) {
        if (done || finalImage == null) return;
        canvas.drawImage(0, 0, finalImage);
        done = true;
    }
}
