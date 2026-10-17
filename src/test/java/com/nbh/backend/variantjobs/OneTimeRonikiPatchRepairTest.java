package com.nbh.backend.variantjobs;

import com.nbh.backend.imagecleanup.GeminiImageClient;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/** The one-time repair of photos with a pale patch (deleted with the repair once it has run). */
class OneTimeRonikiPatchRepairTest {

    private static final Color FOAM = new Color(222, 222, 226);
    private static final Color PINK_CAP = new Color(236, 170, 182);
    private static final Color CREAM = new Color(0xF3, 0xEB, 0xDD);

    /** Foam, a grey barrel and a cap (x 80-119, y 40-79); with a cream patch over the cap and around it when patched. */
    private static GeminiImageClient.Image photo(Color cap, boolean patched) throws IOException {
        BufferedImage im = new BufferedImage(200, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        g.setColor(FOAM);
        g.fillRect(0, 0, 200, 300);
        g.setColor(Color.GRAY);
        g.fillRect(80, 80, 40, 150);
        g.setColor(cap);
        g.fillRect(80, 40, 40, 40);
        if (patched) {
            g.setColor(CREAM);
            g.fillOval(70, 25, 60, 50);
        }
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(im, "png", out);
        return new GeminiImageClient.Image(out.toByteArray(), "image/png");
    }

    private static BufferedImage decode(GeminiImageClient.Image image) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(image.bytes()));
    }

    @Test
    void aPatchedPhotoGetsItsBackgroundBackAndTheCapInTheVariantsColour() throws IOException {
        GeminiImageClient.Image fixed = OneTimeRonikiPatchRepair.repair(photo(PINK_CAP, false),
                photo(new Color(46, 139, 87), true), "#2E8B57", "#F3EBDD");

        assertThat(fixed).isNotNull();
        BufferedImage out = decode(fixed);
        Color above = new Color(out.getRGB(100, 30)); // was cream, background in the product's photo
        assertThat(Math.abs(above.getRed() - FOAM.getRed()) + Math.abs(above.getGreen() - FOAM.getGreen())
                + Math.abs(above.getBlue() - FOAM.getBlue())).isLessThan(15);
        Color cap = new Color(out.getRGB(100, 60));
        assertThat(cap.getGreen()).isGreaterThan(cap.getRed() + 40);
        assertThat(new Color(out.getRGB(100, 200))).isEqualTo(Color.GRAY); // untouched elsewhere
    }

    @Test
    void aPhotoWithoutAPatchIsLeftAlone() throws IOException {
        assertThat(OneTimeRonikiPatchRepair.repair(photo(PINK_CAP, false), photo(new Color(46, 139, 87), false),
                "#2E8B57", "#F3EBDD")).isNull();
    }
}
