package com.mymonkey.modpackexport;

import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Renders every non-item JEI ingredient (fluids + chemicals) to
 * {@code <gameDir>/fluid-icons/<ns>_<path>.png} via {@link IngredientIconScreen}.
 * Needs the JEI runtime (ingredient manager), so it only fires once JEI is ready.
 */
public final class IngredientIconDumper {
    private IngredientIconDumper() {}

    public static int dumpAll(Consumer<String> feedback) {
        IJeiRuntime rt = JeiDumperPlugin.getRuntime();
        if (rt == null) {
            feedback.accept("[fcdump] JEI runtime not ready yet");
            return 0;
        }
        Path outDir = FMLPaths.GAMEDIR.get().resolve("fluid-icons");
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            feedback.accept("[fcdump] cannot create output dir: " + e.getMessage());
            return 0;
        }
        IIngredientManager mgr = rt.getIngredientManager();
        Minecraft mc = Minecraft.getInstance();
        IngredientIconScreen screen = new IngredientIconScreen(mgr, outDir, feedback,
            () -> feedback.accept("[fcdump] fluid/chemical icons complete"));
        int total = screen.total();
        feedback.accept("[fcdump] rendering " + total + " fluid/chemical icons to fluid-icons/ ...");
        mc.execute(() -> mc.setScreen(screen));
        return total;
    }
}
