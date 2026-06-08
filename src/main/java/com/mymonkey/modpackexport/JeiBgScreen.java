package com.mymonkey.modpackexport;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

import java.nio.file.Path;
import java.util.List;

/**
 * Renders each category's real colored background INSIDE a live GUI render frame
 * (the headless client still runs the render loop). Doing this in-frame is what
 * makes JEI's drawables actually paint: DrawableResource / DrawableSprite /
 * DrawableNineSliceTexture bind their own texture+shader and use gg.pose(); a
 * render OUTSIDE a frame produced transparent output.
 *
 * Two render strategies, one category per frame:
 *   1. {@code category.getBackground().draw(...)} — clean empty background. Works
 *      for the many mods that supply a DrawableResource background.
 *   2. If (1) comes out blank/flat (Create, Mekanism, ... draw their machine GUI
 *      in the category's own draw() override, not in getBackground()), fall back
 *      to {@code layout.drawRecipe(...)} which invokes that draw() and yields the
 *      full colored machine GUI (with the sample recipe's ingredients).
 *
 * Each capture renders into a fresh per-job offscreen TextureTarget (true
 * transparency), reads it back into a NativeImage, and writes it only if it has
 * opaque pixels with color variance (skips blank/flat).
 */
public class JeiBgScreen extends Screen {
    public record Job(String safeName, IDrawable bg, IRecipeLayoutDrawable<?> layout, int w, int h) {}

    private final List<Job> jobs;
    private final Path outDir;
    private final Runnable onDone;
    private int index = 0;
    private int settleFrames = 0;
    private int renderedBg = 0;
    private int renderedFull = 0;
    private int blank = 0;

    public JeiBgScreen(List<Job> jobs, Path outDir, Runnable onDone) {
        super(Component.literal("JEI BG Dump"));
        this.jobs = jobs;
        this.outDir = outDir;
        this.onDone = onDone;
    }

    @Override
    public boolean isPauseScreen() {
        return false; // keep the render loop ticking
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        gg.fill(0, 0, this.width, this.height, 0xFF101010);

        if (settleFrames < 3) {
            settleFrames++;
            return;
        }
        if (index >= jobs.size()) {
            finish();
            return;
        }
        Job job = jobs.get(index);
        index++;
        try {
            captureOne(job);
        } catch (Throwable t) {
            ModPackExportMod.LOGGER.warn("[jeidump] bg capture failed for {}: {}", job.safeName(), t.toString());
        }
    }

    private enum Kind { BG, FRAME }

    private void captureOne(Job job) {
        int w = job.w();
        int h = job.h();
        if (w <= 0 || h <= 0 || w > 1024 || h > 1024) {
            return;
        }

        // Strategy 1: clean background only.
        if (job.bg() != null) {
            Result bgResult = renderToImage(job, Kind.BG, w, h);
            if (bgResult != null && bgResult.good) {
                writePng(job, bgResult.image);
                bgResult.image.close();
                renderedBg++;
                return;
            }
            if (bgResult != null) bgResult.image.close();
        }

        // Strategy 2: item-free machine frame = category.draw() (machine GUI, arrows,
        // gauges, labels) PLUS each slot's own background drawable (the empty slot
        // squares) — but NOT the ingredient stacks. Recovers procedural machine GUIs
        // (Create/Mekanism) and slot-only categories (vanilla crafting/smelting) while
        // staying item-free.
        if (job.layout() != null) {
            Result fullResult = renderToImage(job, Kind.FRAME, w, h);
            if (fullResult != null && fullResult.good) {
                writePng(job, fullResult.image);
                fullResult.image.close();
                renderedFull++;
                return;
            }
            if (fullResult != null) fullResult.image.close();
        }

        blank++;
        ModPackExportMod.LOGGER.info("[jeidump] bg blank/flat for {}", job.safeName());
    }

    private record Result(NativeImage image, boolean good) {}

    private Result renderToImage(Job job, Kind kind, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        RenderTarget mainTarget = mc.getMainRenderTarget();
        RenderTarget target = new TextureTarget(w, h, true, Minecraft.ON_OSX);
        try {
            target.setClearColor(0f, 0f, 0f, 0f);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            RenderSystem.viewport(0, 0, w, h);

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableDepthTest();
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

            Matrix4f projection = new Matrix4f().setOrtho(0.0f, w, h, 0.0f, 1000.0f, 21000.0f);
            RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);
            var modelView = RenderSystem.getModelViewStack();
            modelView.pushMatrix();
            modelView.identity();
            modelView.translate(0.0f, 0.0f, -11000.0f);
            RenderSystem.applyModelViewMatrix();

            MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
            GuiGraphics local = new GuiGraphics(mc, buffers);
            if (kind == Kind.BG) {
                job.bg().draw(local, 0, 0);
            } else {
                // Item-free machine frame: category.draw() (frame/arrows/gauges/labels)
                // + each slot's own background drawable (empty slot squares). We skip
                // JEI's drawRecipe(), which is what bakes the sample ingredient stacks
                // in — so our overlaid items won't collide.
                drawCategoryFrame(job.layout(), local);
            }
            local.flush();
            buffers.endBatch();

            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();

            NativeImage out = new NativeImage(NativeImage.Format.RGBA, w, h, false);
            target.bindRead();
            out.downloadTexture(0, false);
            target.unbindRead();
            out.flipY();
            return new Result(out, isGood(out, w, h));
        } catch (Throwable e) {
            ModPackExportMod.LOGGER.warn("[jeidump] render ({}) failed for {}: {}", kind, job.safeName(), e.toString());
            return null;
        } finally {
            target.destroyBuffers();
            if (mainTarget != null) {
                mainTarget.bindWrite(true);
                RenderSystem.viewport(0, 0, mainTarget.width, mainTarget.height);
            }
        }
    }

    /**
     * Draw the item-free machine frame: the category's own GUI graphics (no ingredient
     * stacks) PLUS each slot's background drawable (empty slot squares). The generic
     * helper captures R from the layout so category.draw(recipe, ...) typechecks.
     */
    private static <R> void drawCategoryFrame(IRecipeLayoutDrawable<R> layout, GuiGraphics gg) {
        layout.setPosition(0, 0);
        layout.getRecipeCategory().draw(
            layout.getRecipe(),
            layout.getRecipeSlotsView(),
            gg,
            -9999.0, -9999.0); // mouse off-screen: no hover tooltips/highlights

        // Draw each slot's own background (the empty slot square) without the ingredient.
        // JEI's RecipeSlot has a private @Nullable OffsetDrawable 'background'; reflect
        // and draw it at the slot rect. Categories that draw their own slot frames in
        // draw() have null backgrounds here (no-op); slot-only categories (vanilla
        // crafting/smelting) carry the standard slot texture here.
        for (var view : layout.getRecipeSlotsView().getSlotViews()) {
            if (!(view instanceof IRecipeSlotDrawable slot)) continue;
            try {
                Object bgObj = readSlotBackground(slot);
                if (bgObj instanceof IDrawable bgDraw) {
                    var rect = slot.getRect();
                    bgDraw.draw(gg, rect.getX(), rect.getY());
                }
            } catch (Throwable ignored) {
                // best-effort; field name/type may differ across versions
            }
        }
    }

    private static Object readSlotBackground(Object slot) throws Exception {
        Class<?> c = slot.getClass();
        while (c != null) {
            try {
                var f = c.getDeclaredField("background");
                f.setAccessible(true);
                return f.get(slot);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    /** Non-blank = has opaque pixels AND more than one distinct opaque color. */
    private static boolean isGood(NativeImage img, int w, int h) {
        boolean anyOpaque = false;
        int firstColor = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getPixelRGBA(x, y);
                int a = (argb >> 24) & 0xFF;
                if (a > 10) {
                    anyOpaque = true;
                    int rgb = argb & 0x00FFFFFF;
                    if (firstColor == -1) firstColor = rgb;
                    else if (rgb != firstColor) return true;
                }
            }
        }
        return anyOpaque && false; // single flat color => not useful
    }

    private void writePng(Job job, NativeImage img) {
        try {
            img.writeToFile(outDir.resolve(job.safeName() + "_bg.png"));
        } catch (Exception e) {
            ModPackExportMod.LOGGER.warn("[jeidump] writePng failed for {}: {}", job.safeName(), e.toString());
        }
    }

    private void finish() {
        Minecraft mc = Minecraft.getInstance();
        ModPackExportMod.LOGGER.info("[jeidump] bg render pass done: bg-only={} full-layout={} blank={} of {}",
            renderedBg, renderedFull, blank, jobs.size());
        mc.setScreen(null);
        if (onDone != null) onDone.run();
    }
}
