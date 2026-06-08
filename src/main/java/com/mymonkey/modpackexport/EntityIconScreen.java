package com.mymonkey.modpackexport;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Renders EVERY living entity to a turntable SPRITE SHEET inside a live GUI frame —
 * universal mob previews for any modpack (vanilla code-models, GeckoLib, whatever the
 * game can render). One row of {@link #FRAMES} frames, each {@link #F}px, the entity
 * spun around Y; written to {@code mob-icons/<ns>__<path>.png} ({@code F*FRAMES} × F,
 * transparent). The frontend scrubs the row for a rotating preview.
 *
 * Entities are created (not spawned) in the client level; ones that fail to create as a
 * LivingEntity, or throw while rendering, are skipped. A handful per render() call so the
 * whole registry finishes in a few seconds without freezing the (headless) client.
 */
public class EntityIconScreen extends Screen {
    private static final int F = 128;         // one frame, px (bigger source for crisp large previews)
    private static final int FRAMES = 12;     // turntable steps (30° each)
    private static final int PER_FRAME = 2;   // entities rendered per render() call (heavier than items)
    private static final float TILT = 0.32f;  // downward camera tilt for a 3/4 look
    private static final float FILL = 0.60f;  // fraction of the frame the hitbox fills — leaves
                                              // margin for models that overhang their hitbox (no clipping)

    private final List<LivingEntity> entities = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private final Path outDir;
    private final Consumer<String> feedback;
    private final Runnable onDone;

    private int index = 0;
    private int settleFrames = 0;
    private int written = 0;
    private int errored = 0;
    private RenderTarget target;

    public EntityIconScreen(Path outDir, Consumer<String> feedback, Runnable onDone) {
        super(Component.literal("Entity Icon Dump"));
        this.outDir = outDir;
        this.feedback = feedback;
        this.onDone = onDone;
        Minecraft mc = Minecraft.getInstance();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (id == null) continue;
            try {
                Entity e = type.create(mc.level);
                if (e instanceof LivingEntity le) {
                    entities.add(le);
                    names.add(id.getNamespace() + "__" + safePath(id.getPath()));
                }
            } catch (Throwable t) {
                // some entities can't be created client-side without context — skip
            }
        }
    }

    public int total() {
        return entities.size();
    }

    private static String safePath(String path) {
        StringBuilder sb = new StringBuilder(path.length());
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            sb.append(Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' ? c : '_');
        }
        return sb.toString();
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
            target = new TextureTarget(F, F, true, Minecraft.ON_OSX);
        }
        if (index >= entities.size()) {
            finish();
            return;
        }
        int end = Math.min(index + PER_FRAME, entities.size());
        for (; index < end; index++) {
            try {
                renderSheet(entities.get(index), names.get(index));
            } catch (Throwable t) {
                errored++;
                ModPackExportMod.LOGGER.warn("[mobdump] render failed for {}: {}", names.get(index), t.toString());
            }
        }
        if (index % 40 == 0) {
            feedback.accept("[mobdump] " + index + "/" + entities.size() + " (written=" + written + ")");
        }
    }

    /** Render all FRAMES turntable angles of one entity and compose them into a sprite sheet. */
    private void renderSheet(LivingEntity entity, String name) throws Exception {
        NativeImage sheet = new NativeImage(NativeImage.Format.RGBA, F * FRAMES, F, false);
        boolean any = false;
        for (int frame = 0; frame < FRAMES; frame++) {
            float angle = (float) (frame * 2.0 * Math.PI / FRAMES);
            try (NativeImage one = renderFrame(entity, angle)) {
                for (int y = 0; y < F; y++) {
                    for (int x = 0; x < F; x++) {
                        sheet.setPixelRGBA(frame * F + x, y, one.getPixelRGBA(x, y));
                    }
                }
                if (!any && hasContent(one)) any = true;
            }
        }
        if (any) {
            sheet.writeToFile(outDir.resolve(name + ".png"));
            written++;
        }
        sheet.close();
    }

    private NativeImage renderFrame(LivingEntity entity, float angle) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        RenderTarget mainTarget = mc.getMainRenderTarget();
        try {
            target.setClearColor(0f, 0f, 0f, 0f);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            RenderSystem.viewport(0, 0, F, F);

            Matrix4f projection = new Matrix4f().setOrtho(0.0f, F, F, 0.0f, 1000.0f, 21000.0f);
            RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);
            var modelView = RenderSystem.getModelViewStack();
            modelView.pushMatrix();
            modelView.identity();
            modelView.translate(0.0f, 0.0f, -11000.0f);
            RenderSystem.applyModelViewMatrix();

            MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
            GuiGraphics local = new GuiGraphics(mc, buffers);

            // Scale so the WHOLE hitbox (height AND width) fits FILL of the frame — true max,
            // no weighting, so wide mobs don't overscale and clip.
            float bb = Math.max(entity.getBbHeight(), entity.getBbWidth());
            float scale = (F * FILL) / Math.max(bb, 0.5f);
            // Centre the mob vertically: feet sit below centre by half the rendered height, so
            // the body straddles the frame centre (no headroom, no bottom clip).
            float feetY = F / 2.0f + (entity.getBbHeight() * scale) / 2.0f;

            // GUI entities render upside-down → flip Z; tilt the camera down a touch; spin Y.
            Quaternionf pose = new Quaternionf().rotateZ((float) Math.PI);
            Quaternionf cameraOrbit = new Quaternionf().rotateX(TILT);
            pose.mul(cameraOrbit);
            pose.rotateY(angle);

            InventoryScreen.renderEntityInInventory(
                local, F / 2.0f, feetY, scale, new Vector3f(0, 0, 0), pose, cameraOrbit, entity);
            local.flush();
            buffers.endBatch();

            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();

            NativeImage out = new NativeImage(NativeImage.Format.RGBA, F, F, false);
            target.bindRead();
            out.downloadTexture(0, false);
            target.unbindRead();
            out.flipY();
            return out;
        } finally {
            if (mainTarget != null) {
                mainTarget.bindWrite(true);
                RenderSystem.viewport(0, 0, mainTarget.width, mainTarget.height);
            }
        }
    }

    private static boolean hasContent(NativeImage img) {
        int opaque = 0;
        for (int y = 0; y < F; y++) {
            for (int x = 0; x < F; x++) {
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
        String summary = "[mobdump] done: written=" + written + " errored=" + errored
            + " of " + entities.size() + " entities -> " + outDir;
        ModPackExportMod.LOGGER.info(summary);
        feedback.accept(summary);
        mc.setScreen(null);
        if (onDone != null) onDone.run();
    }
}
