package com.mymonkey.modpackexport;

import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Renders every living entity to a turntable sprite sheet under
 * {@code <gameDir>/mob-icons/<ns>__<path>.png} via {@link EntityIconScreen}.
 */
public final class EntityIconDumper {
    private EntityIconDumper() {}

    public static int dumpAll(Consumer<String> feedback) {
        Path outDir = FMLPaths.GAMEDIR.get().resolve("mob-icons");
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            feedback.accept("[mobdump] cannot create output dir: " + e.getMessage());
            return 0;
        }
        Minecraft mc = Minecraft.getInstance();
        EntityIconScreen screen = new EntityIconScreen(outDir, feedback,
            () -> feedback.accept("[mobdump] mob render complete"));
        int total = screen.total();
        feedback.accept("[mobdump] rendering " + total + " entity turntables to mob-icons/ ...");
        mc.execute(() -> mc.setScreen(screen));
        return total;
    }
}
