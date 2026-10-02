package com.nbh.backend.imagecleanup;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Stamps the shop logo small, dead centre, at 30% opacity — drawn here rather than by the model because image
 * models garble small precise marks. If the picture can't be decoded the original bytes are returned untouched
 * (a clean image without the logo beats a failed one).
 */
@Service
public class WatermarkService {

    static final float OPACITY = 0.30f;
    static final double WIDTH_FRACTION = 0.20;

    private final BufferedImage logo = loadLogo();

    private static BufferedImage loadLogo() {
        try (InputStream in = new ClassPathResource("branding/logo.png").getInputStream()) {
            return ImageIO.read(in);
        } catch (IOException e) {
            return null;
        }
    }

    /** @return PNG bytes of the image with the logo composited on it. */
    public byte[] stamp(byte[] imageBytes) {
        if (logo == null) {
            return imageBytes;
        }
        try {
            BufferedImage base = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (base == null) {
                return imageBytes;
            }
            BufferedImage canvas = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = canvas.createGraphics();
            g.drawImage(base, 0, 0, null);
            int w = (int) Math.round(base.getWidth() * WIDTH_FRACTION);
            int h = (int) Math.round(w * (logo.getHeight() / (double) logo.getWidth()));
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, OPACITY));
            g.drawImage(logo, (base.getWidth() - w) / 2, (base.getHeight() - h) / 2, w, h, null);
            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(canvas, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            return imageBytes;
        }
    }
}
