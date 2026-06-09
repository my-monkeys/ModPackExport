package com.mymonkey.modpackexport;

// Adapted from CyclopsMC/IconExporter (master-1.18, MIT) — ItemRenderUtil. Cyclops Core
// removed: the only dependency in the item path is binding the block atlas, done here with
// vanilla RenderSystem. See NOTICE.

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Renders an item into the GUI at a scale (scale = target px, item models are 16px). */
public final class ItemRenderUtil {
    private ItemRenderUtil() {}

    public static void renderItem(ItemStack stack, float scale) {
        if (stack.isEmpty()) return;
        var ir = Minecraft.getInstance().getItemRenderer();
        ir.blitOffset += 50.0F;
        try {
            BakedModel model = ir.getModel(stack, (Level) null, Minecraft.getInstance().player, 0);
            renderItemModelIntoGUI(stack, 0, 0, model, scale);
        } finally {
            ir.blitOffset -= 50.0F;
            Lighting.setupFor3DItems();
        }
    }

    private static void renderItemModelIntoGUI(ItemStack stack, int x, int y, BakedModel model, float scale) {
        Minecraft mc = Minecraft.getInstance();
        RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
        mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).setFilter(false, false);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        modelView.scale(scale / 16, scale / 16, 1);
        modelView.translate((float) x, (float) y, 100.0F + mc.getItemRenderer().blitOffset);
        modelView.translate(8.0F, 8.0F, 0.0F);
        modelView.scale(1.0F, -1.0F, 1.0F);
        modelView.scale(16.0F, 16.0F, 16.0F);
        RenderSystem.applyModelViewMatrix();
        PoseStack pose = new PoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        boolean flat = !model.usesBlockLight();
        if (flat) Lighting.setupForFlatItems();
        mc.getItemRenderer().render(stack, ItemTransforms.TransformType.GUI, false, pose, buffers,
            15728880, OverlayTexture.NO_OVERLAY, model);
        buffers.endBatch();
        RenderSystem.enableDepthTest();
        if (flat) Lighting.setupFor3DItems();
        modelView.popPose();
        RenderSystem.applyModelViewMatrix();
    }
}
