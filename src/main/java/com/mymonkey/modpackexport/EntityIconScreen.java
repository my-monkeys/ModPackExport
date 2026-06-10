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
    // Two-pass auto-fit (ported from forge-1.18). Pass 1 renders each frame small (RENDER_FILL of the
    // frame, by hitbox) so most overhanging models don't clip; pass 2 measures the mob's REAL extent
    // across all 12 frames and crops+scales it to FIT_FILL, so every mob ends fully visible + evenly
    // sized. A few models overhang >2x their hitbox (guardian spikes, ghast tentacles) and still clip
    // at RENDER_FILL → renderSheet shrinks the fill and re-renders until nothing touches an edge.
    private static final float RENDER_FILL = 0.42f;
    private static final float SHRINK = 0.6f;        // fill multiplier per clip retry
    private static final int MAX_FIT_ATTEMPTS = 4;   // 0.42 → 0.25 → 0.15 → 0.09 (covers ~11x overhang)
    private static final float FIT_FILL = 0.86f;
    private static final boolean DEBUG = "true".equalsIgnoreCase(System.getProperty("modpackexport.mobs.debug"));
    private int dbgEntities = 0;

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
        // Optional subset for fast iteration: -Dmodpackexport.mobs.only=minecraft:horse,minecraft:panda
        java.util.Set<String> only = new java.util.HashSet<>();
        for (String s : System.getProperty("modpackexport.mobs.only", "").split(",")) {
            s = s.trim(); if (!s.isEmpty()) only.add(s);
        }
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (id == null) continue;
            if (!only.isEmpty() && !only.contains(id.toString())) continue;
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

    /** Render all FRAMES turntable angles of one entity and compose them into a sprite sheet, using
     *  the two-pass auto-fit: render conservative, measure the union opaque bbox, retry at a smaller
     *  fill while it touches a frame edge, then crop+scale the real extent to FIT_FILL. */
    private void renderSheet(LivingEntity entity, String name) throws Exception {
        boolean dbgThis = DEBUG && dbgEntities < 8;
        NativeImage[] frames = null;
        int minX = 0, minY = 0, maxX = -1, maxY = -1;
        float fill = RENDER_FILL;
        int attempt = 0;
        for (; attempt < MAX_FIT_ATTEMPTS; attempt++) {
            if (frames != null) for (NativeImage im : frames) if (im != null) im.close();
            frames = new NativeImage[FRAMES];
            minX = F; minY = F; maxX = -1; maxY = -1;
            for (int f = 0; f < FRAMES; f++) {
                NativeImage one = renderFrame(entity, (float) (f * 2.0 * Math.PI / FRAMES), fill);
                frames[f] = one;
                for (int y = 0; y < F; y++)
                    for (int x = 0; x < F; x++)
                        if (((one.getPixelRGBA(x, y) >> 24) & 0xFF) > 10) {
                            if (x < minX) minX = x; if (x > maxX) maxX = x;
                            if (y < minY) minY = y; if (y > maxY) maxY = y;
                        }
            }
            boolean clipped = maxX >= 0 && (minX == 0 || maxX == F - 1 || minY == 0 || maxY == F - 1);
            if (!clipped) break;
            fill *= SHRINK;
        }
        if (maxX < 0) {                                  // nothing rendered → skip
            for (NativeImage im : frames) if (im != null) im.close();
            if (dbgThis) { ModPackExportMod.LOGGER.info("[mobdbg] {} EMPTY", name); dbgEntities++; }
            return;
        }
        int bw = maxX - minX + 1, bh = maxY - minY + 1;
        int side = (int) Math.ceil(Math.max(bw, bh) / FIT_FILL);
        int sx0 = (minX + maxX + 1) / 2 - side / 2;
        int sy0 = (minY + maxY + 1) / 2 - side / 2;
        if (dbgThis) {
            ModPackExportMod.LOGGER.info("[mobdbg] {} union x[{}..{}] y[{}..{}] -> side={} attempts={} fill={} clipped={}",
                name, minX, maxX, minY, maxY, side, attempt + 1, String.format("%.3f", fill),
                (minX == 0 || maxX == F - 1 || minY == 0 || maxY == F - 1));
            dbgEntities++;
        }
        NativeImage sheet = new NativeImage(NativeImage.Format.RGBA, F * FRAMES, F, false);
        for (int f = 0; f < FRAMES; f++) {
            for (int oy = 0; oy < F; oy++)
                for (int ox = 0; ox < F; ox++) {
                    int sX = sx0 + (int) ((ox + 0.5f) * side / F);
                    int sY = sy0 + (int) ((oy + 0.5f) * side / F);
                    int px = (sX >= 0 && sX < F && sY >= 0 && sY < F) ? frames[f].getPixelRGBA(sX, sY) : 0;
                    sheet.setPixelRGBA(f * F + ox, oy, px);
                }
            frames[f].close();
        }
        sheet.writeToFile(outDir.resolve(name + ".png"));
        written++;
        sheet.close();
    }

    private NativeImage renderFrame(LivingEntity entity, float angle, float fill) throws Exception {
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
            float scale = (F * fill) / Math.max(bb, 0.5f);
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
