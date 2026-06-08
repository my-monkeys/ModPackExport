package com.mymonkey.modpackexport;

import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Iterates every JEI recipe category, builds a layout from one sample recipe,
 * and writes {@code <safeCategoryId>.json} (+ best-effort {@code _bg.png}) to
 * {@code <gameDir>/jei-layouts/}.
 */
public final class JeiLayoutDumper {
    private JeiLayoutDumper() {}

    private static final int SLOT = 18;

    public static int dumpAll(Consumer<String> feedback) {
        IJeiRuntime runtime = JeiDumperPlugin.getRuntime();
        if (runtime == null) {
            feedback.accept("[jeidump] JEI runtime not available yet");
            return 0;
        }

        Path outDir = FMLPaths.GAMEDIR.get().resolve("jei-layouts");
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            feedback.accept("[jeidump] cannot create output dir: " + e.getMessage());
            return 0;
        }

        IRecipeManager rm = runtime.getRecipeManager();
        IFocusGroup empty = runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup();

        List<IRecipeCategory<?>> categories = rm.createRecipeCategoryLookup()
            .includeHidden()
            .get()
            .toList();

        int ok = 0;
        int noRecipe = 0;
        int errored = 0;
        List<JeiBgScreen.Job> bgJobs = new ArrayList<>();
        for (IRecipeCategory<?> category : categories) {
            try {
                Result r = dumpCategory(rm, empty, category, outDir, bgJobs);
                switch (r) {
                    case OK -> ok++;
                    case NO_RECIPE -> noRecipe++;
                    case ERROR -> errored++;
                }
            } catch (Throwable t) {
                errored++;
                ModPackExportMod.LOGGER.warn("[jeidump] category {} failed: {}",
                    category.getRecipeType().getUid(), t.toString());
            }
        }

        String summary = String.format("[jeidump] categories=%d written=%d no-recipe=%d errored=%d bg-jobs=%d -> %s",
            categories.size(), ok, noRecipe, errored, bgJobs.size(), outDir);
        ModPackExportMod.LOGGER.info(summary);
        feedback.accept(summary);

        // Phase 2: render the real colored backgrounds inside a live GUI frame.
        if (!bgJobs.isEmpty()) {
            Minecraft mc = Minecraft.getInstance();
            feedback.accept("[jeidump] opening bg render screen for " + bgJobs.size() + " backgrounds...");
            mc.execute(() -> mc.setScreen(new JeiBgScreen(bgJobs, outDir,
                () -> feedback.accept("[jeidump] bg render pass complete"))));
        }
        return ok;
    }

    private enum Result { OK, NO_RECIPE, ERROR }

    // Helper carrying the inferred generic type so createRecipeLayoutDrawableOrShowError typechecks.
    private static <T> Result dumpCategory(IRecipeManager rm, IFocusGroup empty,
                                           IRecipeCategory<T> category, Path outDir,
                                           List<JeiBgScreen.Job> bgJobs) throws IOException {
        RecipeType<T> type = category.getRecipeType();
        String uid = type.getUid().toString();
        String safe = safeName(uid);

        // Background dims: prefer category width/height, fall back to the IDrawable.
        int bgW = category.getWidth();
        int bgH = category.getHeight();
        IDrawable bg = category.getBackground();
        if ((bgW <= 0 || bgH <= 0) && bg != null) {
            bgW = bg.getWidth();
            bgH = bg.getHeight();
        }

        BgTexture bgTex = extractTexture(bg);

        // One sample recipe for this category.
        Optional<T> sample = rm.createRecipeLookup(type).includeHidden().get().findFirst();
        if (sample.isEmpty()) {
            writeJson(outDir, safe, uid, category.getTitle().getString(), bgW, bgH, List.of(), List.of(), bgTex);
            if (bg != null && bgW > 0 && bgH > 0) {
                bgJobs.add(new JeiBgScreen.Job(safe, bg, null, bgW, bgH));
            }
            return Result.NO_RECIPE;
        }

        IRecipeLayoutDrawable<T> layout =
            rm.createRecipeLayoutDrawableOrShowError(category, sample.get(), empty);
        // Origin at (0,0) so slot rects are layout-local (relative to the bg).
        layout.setPosition(0, 0);

        List<SlotInfo> slots = new ArrayList<>();
        for (IRecipeSlotView view : layout.getRecipeSlotsView().getSlotViews()) {
            Rect2i rect;
            if (view instanceof IRecipeSlotDrawable drawable) {
                rect = drawable.getRect();
            } else {
                continue; // no geometry available
            }
            RecipeIngredientRole role = view.getRole();
            slots.add(new SlotInfo(role.name(), rect.getX(), rect.getY(), rect.getWidth(), rect.getHeight()));
        }

        // Catalysts (the recipe's machine block) aren't part of the layout's slots.
        // JEI shows them in ONE slot just left of the recipe background, cycling through
        // the catalyst items. We record a single catalyst marker slot at that position
        // when the category has any catalysts.
        List<SlotInfo> catalysts = new ArrayList<>();
        try {
            boolean hasCatalyst = rm.createRecipeCatalystLookup(type).get().findAny().isPresent();
            if (hasCatalyst) {
                catalysts.add(new SlotInfo("CATALYST", -SLOT, 0, SLOT, SLOT));
            }
        } catch (Throwable ignored) {
            // catalyst lookup is best-effort
        }

        writeJson(outDir, safe, uid, category.getTitle().getString(), bgW, bgH, slots, catalysts, bgTex);

        // Queue the live-frame background render (phase 2). Carry the layout so the
        // screen can fall back to a full drawRecipe() for procedural machine GUIs
        // (Create/Mekanism) whose getBackground() paints nothing.
        if (bgW > 0 && bgH > 0) {
            bgJobs.add(new JeiBgScreen.Job(safe, bg, layout, bgW, bgH));
        }
        return Result.OK;
    }

    private record SlotInfo(String role, int x, int y, int w, int h) {}

    /** Reflected single-texture region for backgrounds that wrap a DrawableResource. */
    private record BgTexture(String texture, int u, int v, int w, int h, int texW, int texH) {}

    /**
     * Reflect a JEI background IDrawable to recover its source texture + uv region,
     * so the colored region can be cropped from the mod jar host-side. Only works for
     * DrawableResource (a single texture region). DrawableSprite / DrawableNineSlice use
     * JEI's runtime-stitched sprite atlas and can't be cropped from a jar — those rely on
     * the in-frame render. DrawableCombined is unwrapped to its first DrawableResource.
     */
    private static BgTexture extractTexture(IDrawable bg) {
        if (bg == null) return null;
        try {
            String cn = bg.getClass().getName();
            if (cn.endsWith("DrawableResource")) {
                ResourceLocation rl = (ResourceLocation) readField(bg, "resourceLocation");
                int u = (int) readField(bg, "u");
                int v = (int) readField(bg, "v");
                int w = (int) readField(bg, "width");
                int h = (int) readField(bg, "height");
                int tw = (int) readField(bg, "textureWidth");
                int th = (int) readField(bg, "textureHeight");
                if (rl != null) {
                    return new BgTexture(rl.toString(), u, v, w, h, tw, th);
                }
            } else if (cn.endsWith("DrawableCombined")) {
                Object list = readField(bg, "drawables");
                if (list instanceof List<?> ds) {
                    for (Object d : ds) {
                        if (d instanceof IDrawable inner) {
                            BgTexture t = extractTexture(inner);
                            if (t != null) return t;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // reflection best-effort; field names differ across versions
        }
        return null;
    }

    private static Object readField(Object obj, String name) throws Exception {
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void writeJson(Path outDir, String safe, String uid, String title,
                                  int bgW, int bgH, List<SlotInfo> slots, List<SlotInfo> catalysts,
                                  BgTexture bgTex) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"category\": ").append(quote(uid)).append(",\n");
        sb.append("  \"title\": ").append(quote(title)).append(",\n");
        sb.append("  \"bg\": {\"w\": ").append(bgW).append(", \"h\": ").append(bgH);
        if (bgTex != null) {
            sb.append(", \"texture\": ").append(quote(bgTex.texture()))
              .append(", \"u\": ").append(bgTex.u())
              .append(", \"v\": ").append(bgTex.v())
              .append(", \"texW\": ").append(bgTex.texW())
              .append(", \"texH\": ").append(bgTex.texH());
        }
        sb.append("},\n");
        sb.append("  \"slots\": [");
        List<SlotInfo> merged = new ArrayList<>(slots);
        merged.addAll(catalysts);
        for (int i = 0; i < merged.size(); i++) {
            SlotInfo s = merged.get(i);
            if (i > 0) sb.append(",");
            sb.append("\n    {\"role\": ").append(quote(s.role()))
              .append(", \"x\": ").append(s.x())
              .append(", \"y\": ").append(s.y())
              .append(", \"w\": ").append(s.w())
              .append(", \"h\": ").append(s.h()).append("}");
        }
        if (!merged.isEmpty()) sb.append("\n  ");
        sb.append("]\n}\n");
        Files.write(outDir.resolve(safe + ".json"), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String safeName(String uid) {
        StringBuilder sb = new StringBuilder(uid.length());
        for (int i = 0; i < uid.length(); i++) {
            char c = uid.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
