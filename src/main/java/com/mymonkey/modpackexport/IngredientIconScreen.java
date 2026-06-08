package com.mymonkey.modpackexport;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.runtime.IIngredientManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Renders every NON-item JEI ingredient (fluids + Mekanism chemicals/gases/slurries +
 * any modded ingredient type) to {@code <gameDir>/fluid-icons/<ns>_<path>.png} (128px,
 * transparent), keyed by the bank's asset key so iconUrl() resolves them in the app.
 *
 * Universal: JEI already knows how to draw each ingredient (its registered renderer) and
 * gives its registry id via the helper — no Mekanism/fluid-specific code or compile dep.
 */
public class IngredientIconScreen extends Screen {
    private static final int ICON = 128;
    private static final int SCALE = 8;       // 16px ingredient -> 128px
    private static final int PER_FRAME = 32;

    @SuppressWarnings("rawtypes")
    private final List<IIngredientRenderer> renderers = new ArrayList<>();
    private final List<Object> ingredients = new ArrayList<>();
    private final List<String> keys = new ArrayList<>();
    private final Path outDir;
    private final Consumer<String> feedback;
    private final Runnable onDone;

    private int index = 0;
    private int settleFrames = 0;
    private int written = 0;
    private int blank = 0;
    private int errored = 0;
    private RenderTarget target;

    public IngredientIconScreen(IIngredientManager mgr, Path outDir, Consumer<String> feedback, Runnable onDone) {
        super(Component.literal("Ingredient Icon Dump"));
        this.outDir = outDir;
        this.feedback = feedback;
        this.onDone = onDone;
        String itemUid = VanillaTypes.ITEM_STACK.getUid();
        for (IIngredientType<?> type : mgr.getRegisteredIngredientTypes()) {
            if (type.getUid().equals(itemUid)) continue; // items dumped separately
            collect(mgr, type);
        }
    }

    private <V> void collect(IIngredientManager mgr, IIngredientType<V> type) {
        IIngredientRenderer<V> renderer = mgr.getIngredientRenderer(type);
        IIngredientHelper<V> helper = mgr.getIngredientHelper(type);
        int n = 0;
        for (V ing : mgr.getAllIngredients(type)) {
            try {
                ResourceLocation id = helper.getResourceLocation(ing);
                if (id == null) continue;
                renderers.add(renderer);
                ingredients.add(ing);
                keys.add((id.getNamespace() + "_" + id.getPath()).replace('/', '_').replace(':', '_'));
                n++;
            } catch (Throwable t) {
                // some ingredients can't surface a resource location — skip
            }
        }
        ModPackExportMod.LOGGER.info("[fcdump] type {} -> {} ingredients", type.getUid(), n);
    }

    public int total() {
        return ingredients.size();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
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
        if (target == null) {
            target = new TextureTarget(ICON, ICON, true, Minecraft.ON_OSX);
        }
        if (index >= ingredients.size()) {
            finish();
            return;
        }
        int end = Math.min(index + PER_FRAME, ingredients.size());
        for (; index < end; index++) {
            try {
                renderOne(renderers.get(index), ingredients.get(index), keys.get(index));
            } catch (Throwable t) {
                errored++;
                ModPackExportMod.LOGGER.warn("[fcdump] render failed for {}: {}", keys.get(index), t.toString());
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void renderOne(IIngredientRenderer renderer, Object ing, String key) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        RenderTarget mainTarget = mc.getMainRenderTarget();
        try {
            target.setClearColor(0f, 0f, 0f, 0f);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            RenderSystem.viewport(0, 0, ICON, ICON);

            Matrix4f projection = new Matrix4f().setOrtho(0.0f, ICON, ICON, 0.0f, 1000.0f, 21000.0f);
            RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);
            var modelView = RenderSystem.getModelViewStack();
            modelView.pushMatrix();
            modelView.identity();
            modelView.translate(0.0f, 0.0f, -11000.0f);
            RenderSystem.applyModelViewMatrix();

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableDepthTest();
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            Lighting.setupForFlatItems();

            MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
            GuiGraphics local = new GuiGraphics(mc, buffers);
            local.pose().pushPose();
            local.pose().scale(SCALE, SCALE, 1f);
            renderer.render(local, ing);
            local.pose().popPose();
            local.flush();
            buffers.endBatch();

            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();

            try (NativeImage out = new NativeImage(NativeImage.Format.RGBA, ICON, ICON, false)) {
                target.bindRead();
                out.downloadTexture(0, false);
                target.unbindRead();
                out.flipY();
                if (hasContent(out)) {
                    out.writeToFile(outDir.resolve(key + ".png"));
                    written++;
                } else {
                    blank++;
                }
            }
        } finally {
            if (mainTarget != null) {
                mainTarget.bindWrite(true);
                RenderSystem.viewport(0, 0, mainTarget.width, mainTarget.height);
            }
        }
    }

    private static boolean hasContent(NativeImage img) {
        int opaque = 0;
        for (int y = 0; y < ICON; y++) {
            for (int x = 0; x < ICON; x++) {
                if (((img.getPixelRGBA(x, y) >> 24) & 0xFF) > 10 && ++opaque >= 8) {
                    return true;
                }
            }
        }
        return false;
    }

    private void finish() {
        Minecraft mc = Minecraft.getInstance();
        if (target != null) {
            target.destroyBuffers();
            target = null;
        }
        String summary = "[fcdump] done: written=" + written + " blank=" + blank
            + " errored=" + errored + " of " + ingredients.size() + " ingredients -> " + outDir;
        ModPackExportMod.LOGGER.info(summary);
        feedback.accept(summary);
        mc.setScreen(null);
        if (onDone != null) onDone.run();
    }
}
