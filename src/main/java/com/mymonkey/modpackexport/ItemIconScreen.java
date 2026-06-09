package com.mymonkey.modpackexport;

// Adapted from CyclopsMC/IconExporter (master-1.18, MIT) — ScreenIconExporter, item path
// only (fluids/NBT-variants dropped), output renamed to the ModPackExport contract dir
// item-icons/. Cyclops Wrapper/Helpers/GeneralConfig removed. See NOTICE.

import com.google.common.collect.Queues;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.File;
import java.io.IOException;
import java.util.Queue;
import java.util.function.Consumer;

/** A temporary screen: one item rendered + screenshotted + written per render tick. */
public class ItemIconScreen extends Screen {

    private static final int BACKGROUND_COLOR = 0xFFFEFFFF;          // ARGB fill (a=255,r=254,g=255,b=255)
    private static final int BACKGROUND_COLOR_SHIFTED = 0xFFFFFFFE;  // MC NativeImage ABGR match

    private final int scaleImage;
    private final double scaleGui;
    private final Consumer<String> feedback;
    private final Queue<Runnable> tasks;
    private final int total;
    private int done = 0;

    public ItemIconScreen(int scaleImage, double scaleGui, Consumer<String> feedback) {
        super(new TextComponent("ModPackExport item icons"));
        this.scaleImage = scaleImage;
        this.scaleGui = scaleGui;
        this.feedback = feedback;
        this.tasks = buildTasks();
        this.total = tasks.size();
        feedback.accept("[itemdump] rendering " + total + " item icons at " + scaleImage + "px");
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void render(PoseStack pose, int mouseX, int mouseY, float partialTicks) {
        if (tasks.isEmpty()) {
            Minecraft.getInstance().setScreen(null);
            ModPackExportMod.LOGGER.info("[itemdump] wrote {} item icons", done);
            feedback.accept("[itemdump] done: " + done + " icons -> item-icons/");
            return;
        }
        Runnable t = tasks.poll();
        try {
            t.run();
            done++;
        } catch (Throwable e) {
            ModPackExportMod.LOGGER.warn("[itemdump] task failed: {}", e.toString());
        }
    }

    private Queue<Runnable> buildTasks() {
        float scale = (float) (this.scaleImage / this.scaleGui);
        int box = (int) Math.ceil(scale);
        File dir = new File(Minecraft.getInstance().gameDirectory, "item-icons");
        dir.mkdirs();
        Queue<Runnable> q = Queues.newArrayDeque();
        for (ResourceLocation key : ForgeRegistries.ITEMS.getKeys()) {
            Item item = ForgeRegistries.ITEMS.getValue(key);
            if (item == null) continue;
            ItemStack stack = new ItemStack(item);
            q.add(() -> {
                PoseStack pose = new PoseStack();
                fill(pose, 0, 0, box, box, BACKGROUND_COLOR);
                ItemRenderUtil.renderItem(stack, scale);
                try {
                    ImageExportUtil.exportIcon(dir, key.toString(), scaleImage, BACKGROUND_COLOR_SHIFTED);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        return q;
    }
}
