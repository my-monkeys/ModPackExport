package com.mymonkey.modpackexport;

import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Renders every registry item to {@code <gameDir>/item-icons/<ns>__<path>.png}
 * (128x128, transparent, item-only). Opens an {@link ItemIconScreen} which does
 * the in-frame batched rendering. Returns the total item count queued.
 */
public final class ItemIconDumper {
    private ItemIconDumper() {}

    public static int dumpAll(Consumer<String> feedback) {
        Path outDir = FMLPaths.GAMEDIR.get().resolve("item-icons");
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            feedback.accept("[itemdump] cannot create output dir: " + e.getMessage());
            return 0;
        }
        Minecraft mc = Minecraft.getInstance();
        ItemIconScreen screen = new ItemIconScreen(outDir, feedback,
            () -> feedback.accept("[itemdump] item bank render complete"));
        int total = screen.total();
        feedback.accept("[itemdump] rendering " + total + " item icons to item-icons/ ...");
        mc.execute(() -> mc.setScreen(screen));
        return total;
    }
}
