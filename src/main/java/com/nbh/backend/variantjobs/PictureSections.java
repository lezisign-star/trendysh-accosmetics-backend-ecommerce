package com.nbh.backend.variantjobs;

import com.nbh.backend.imagecleanup.GeminiImageClient;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A big picture of a display cut into overlapping sections, so the AI looks at each part closely (owner, 2026-10-14:
 * one look at a counter of about 200 gel polish bottles found 19 colours). Each section "owns" one cell of a grid and
 * reaches a little past it on every side, so an item on a cut line is seen whole by both neighbours; it is then kept
 * only by the section whose cell holds its centre, so it is counted once.
 */
final class PictureSections {

    /** Aimed-for cell edge in pixels: close enough for the AI to tell neighbouring shades apart. */
    static final int TARGET = 800;
    static final int MAX_SECTIONS = 6;
    /** How far past its cell a section reaches, as a share of the cell. */
    static final double OVERLAP = 0.12;

    /**
     * One part of the picture. {@code x, y, w, h} is the crop within the whole picture; {@code own*} is the cell it is
     * responsible for, also in whole-picture pixels (end exclusive, except on the picture's last row and column).
     */
    record Section(GeminiImageClient.Image image, int x, int y, int w, int h,
                   int ownX0, int ownY0, int ownX1, int ownY1, boolean lastCol, boolean lastRow) {

        /**
         * Whether the item centred at ({@code px}, {@code py}), each 0–1000 across this section, is this section's to
         * keep. An item the AI gave no position for is kept (it may be counted twice, never lost).
         */
        boolean owns(Integer px, Integer py) {
            if (px == null || py == null) {
                return true;
            }
            double gx = x + Math.max(0, Math.min(1000, px)) / 1000.0 * w;
            double gy = y + Math.max(0, Math.min(1000, py)) / 1000.0 * h;
            boolean inX = gx >= ownX0 && (gx < ownX1 || (lastCol && gx <= ownX1));
            boolean inY = gy >= ownY0 && (gy < ownY1 || (lastRow && gy <= ownY1));
            return inX && inY;
        }

        /** The item's centre in whole-picture pixels, for putting the list in reading order. */
        double[] centre(Integer px, Integer py) {
            double gx = x + (px == null ? 500 : Math.max(0, Math.min(1000, px))) / 1000.0 * w;
            double gy = y + (py == null ? 500 : Math.max(0, Math.min(1000, py))) / 1000.0 * h;
            return new double[]{gx, gy};
        }
    }

    private PictureSections() {}

    /** Columns and rows for a picture of this size: cells near {@link #TARGET}, at most {@link #MAX_SECTIONS}. */
    static int[] grid(int width, int height) {
        int cols = Math.max(1, (int) Math.round(width / (double) TARGET));
        int rows = Math.max(1, (int) Math.round(height / (double) TARGET));
        while (cols * rows > MAX_SECTIONS) {
            // Give up a cut where the cells are already the smallest.
            if (width / (double) cols < height / (double) rows) {
                cols--;
            } else {
                rows--;
            }
        }
        return new int[]{cols, rows};
    }

    /**
     * The picture's sections. A small picture, or one Java can't open (WebP), is one section: the picture as sent.
     */
    static List<Section> cut(GeminiImageClient.Image picture) {
        BufferedImage src;
        try {
            src = ImageIO.read(new ByteArrayInputStream(picture.bytes()));
        } catch (IOException | RuntimeException e) {
            src = null;
        }
        if (src == null) {
            return List.of(whole(picture, 1, 1));
        }
        int w = src.getWidth();
        int h = src.getHeight();
        int[] g = grid(w, h);
        int cols = g[0];
        int rows = g[1];
        if (cols * rows == 1) {
            return List.of(whole(picture, w, h));
        }
        List<Section> out = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int ox0 = c * w / cols;
                int ox1 = (c + 1) * w / cols;
                int oy0 = r * h / rows;
                int oy1 = (r + 1) * h / rows;
                int padX = (int) Math.round((ox1 - ox0) * OVERLAP);
                int padY = (int) Math.round((oy1 - oy0) * OVERLAP);
                int x0 = Math.max(0, ox0 - padX);
                int y0 = Math.max(0, oy0 - padY);
                int x1 = Math.min(w, ox1 + padX);
                int y1 = Math.min(h, oy1 + padY);
                byte[] jpeg = jpeg(src, x0, y0, x1 - x0, y1 - y0);
                out.add(new Section(new GeminiImageClient.Image(jpeg, "image/jpeg"), x0, y0, x1 - x0, y1 - y0,
                        ox0, oy0, ox1, oy1, c == cols - 1, r == rows - 1));
            }
        }
        return out;
    }

    private static Section whole(GeminiImageClient.Image picture, int w, int h) {
        return new Section(picture, 0, 0, w, h, 0, 0, w, h, true, true);
    }

    private static byte[] jpeg(BufferedImage src, int x, int y, int w, int h) {
        BufferedImage part = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = part.createGraphics();
        try {
            g.drawImage(src, 0, 0, w, h, x, y, x + w, y + h, java.awt.Color.WHITE, null);
        } finally {
            g.dispose();
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.9f);
            writer.write(null, new IIOImage(part, null, null), param);
        } catch (IOException e) {
            throw new IllegalStateException("A part of the picture couldn't be prepared.", e);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
