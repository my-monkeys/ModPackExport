package com.mymonkey.modpackexport;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Renders EVERY registry item to a 128x128 transparent PNG inside a live GUI
 * render frame, where GuiGraphics.renderItem() — the game's authoritative icon
 * renderer (handles components/colours/tint/BEWLR) — paints correctly (atlas
 * bound, GUI shader set). One shared offscreen target, several items per frame
 * via a state machine so ~30k items finish in minutes.
 *
 * Item-only: renderItem() with NO renderItemDecorations (no count/durability).
 * The 16px item is scaled x8 so it fills 128px (pixelated), matching the bank.
 */
public class ItemIconScreen extends Screen {
    private static final int ICON = 128;
    private static final int SCALE = 8;       // 16px item -> 128px
    private static final int PER_FRAME = 48;  // items rendered per render() call

    private final List<ItemStack> stacks;
    private final List<String> names; // parallel: "<ns>__<path>"
    private final Path outDir;
    private final Consumer<String> feedback;
    private final Runnable onDone;

    private int index = 0;
    private int settleFrames = 0;
    private int written = 0;
    private int blank = 0;
    private int errored = 0;

    private RenderTarget target;

    public ItemIconScreen(Path outDir, Consumer<String> feedback, Runnable onDone) {
        super(Component.literal("Item Icon Dump"));
        this.outDir = outDir;
        this.feedback = feedback;
        this.onDone = onDone;
        this.stacks = new ArrayList<>();
        this.names = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            ItemStack stack = new ItemStack(item);
            if (stack.isEmpty()) {
                continue;
            }
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null) {
                continue;
            }
            stacks.add(stack);
            // Flat filename: "<ns>__<path>" with any path separators / unsafe chars in
            // the path flattened to '_' (some ids have '/', e.g.
            // sophisticatedstorage:chipped/botanist_workbench_upgrade) so the file lands
            // directly in item-icons/ without a missing subdirectory.
            names.add(id.getNamespace() + "__" + safePath(id.getPath()));
        }
    }

    public int total() {
        return stacks.size();
    }

    private static String safePath(String path) {
        StringBuilder sb = new StringBuilder(path.length());
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.') {
                sb.append(c);
            } else {
                sb.append('_');
            }
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
            target = new TextureTarget(ICON, ICON, true, Minecraft.ON_OSX);
        }
        if (index >= stacks.size()) {
            finish();
            return;
        }

        int end = Math.min(index + PER_FRAME, stacks.size());
        for (; index < end; index++) {
            try {
                renderOne(stacks.get(index), names.get(index));
            } catch (Throwable t) {
                errored++;
                ModPackExportMod.LOGGER.warn("[itemdump] render failed for {}: {}", names.get(index), t.toString());
            }
        }
        if (index % 4800 == 0) {
            feedback.accept("[itemdump] progress " + index + "/" + stacks.size()
                + " (written=" + written + " blank=" + blank + ")");
        }
    }

    private void renderOne(ItemStack stack, String name) {
        Minecraft mc = Minecraft.getInstance();
        RenderTarget mainTarget = mc.getMainRenderTarget();
        try {
            target.setClearColor(0f, 0f, 0f, 0f);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            RenderSystem.viewport(0, 0, ICON, ICON);

            // GUI-style ortho. Items are 3D block models => need depth test + a depth
            // range deep enough for the model. renderItem() handles its own z/scale.
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
            Lighting.setupFor3DItems();

            MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
            GuiGraphics local = new GuiGraphics(mc, buffers);
            // Scale the 16px item up to fill 128px (pixelated). Item only — no decorations.
            local.pose().pushPose();
            local.pose().scale(SCALE, SCALE, 1f);
            local.renderItem(stack, 0, 0);
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
                    out.writeToFile(outDir.resolve(name + ".png"));
                    written++;
                } else {
                    blank++;
                }
            }
        } catch (Exception e) {
            errored++;
            ModPackExportMod.LOGGER.warn("[itemdump] readback failed for {}: {}", name, e.toString());
        } finally {
            if (mainTarget != null) {
                mainTarget.bindWrite(true);
                RenderSystem.viewport(0, 0, mainTarget.width, mainTarget.height);
            }
        }
    }

    /** Non-blank = at least a handful of opaque pixels (item drew something). */
    private static boolean hasContent(NativeImage img) {
        int opaque = 0;
        for (int y = 0; y < ICON; y++) {
            for (int x = 0; x < ICON; x++) {
                if (((img.getPixelRGBA(x, y) >> 24) & 0xFF) > 10) {
                    if (++opaque >= 8) {
                        return true;
                    }
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
        String summary = "[itemdump] done: written=" + written + " blank=" + blank
            + " errored=" + errored + " of " + stacks.size() + " items -> " + outDir;
        ModPackExportMod.LOGGER.info(summary);
        feedback.accept(summary);
        mc.setScreen(null);
        if (onDone != null) onDone.run();
    }
}
