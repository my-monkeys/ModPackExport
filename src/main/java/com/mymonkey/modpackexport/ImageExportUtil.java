package com.mymonkey.modpackexport;

// Adapted from CyclopsMC/IconExporter (master-1.18, MIT) — ImageExportUtil. Cyclops logging
// swapped for our LOGGER. See NOTICE.

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.io.File;
import java.io.IOException;

public final class ImageExportUtil {
    private ImageExportUtil() {}

    /** Screenshot the main render target, crop the top-left scale×scale, turn the flat
     *  background colour transparent, and write {@code <dir>/<ns>__<path>.png}. */
    public static void exportIcon(File dir, String key, int scale, int backgroundColorShifted) throws IOException {
        NativeImage full = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget());
        NativeImage image = topLeft(full, scale, scale);
        full.close();
        for (int cx = 0; cx < image.getWidth(); cx++) {
            for (int cy = 0; cy < image.getHeight(); cy++) {
                if (image.getPixelRGBA(cx, cy) == backgroundColorShifted) {
                    image.setPixelRGBA(cx, cy, 0); // fully transparent
                }
            }
        }
        String safe = key.replaceAll(":", "__").replaceAll("\"", "'");
        File file = new File(dir, safe + ".png").getCanonicalFile();
        try {
            image.writeToFile(file);
        } catch (Exception e) {
            ModPackExportMod.LOGGER.warn("[itemdump] PNG write failed for {}: {}", safe, e.toString());
            throw new IOException(e);
        } finally {
            image.close();
        }
    }

    /** Crop the top-left width×height region via the public pixel API (no private field). */
    private static NativeImage topLeft(NativeImage src, int width, int height) {
        NativeImage out = new NativeImage(width, height, false);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                out.setPixelRGBA(x, y, src.getPixelRGBA(x, y));
            }
        }
        return out;
    }
}
