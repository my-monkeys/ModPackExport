package com.mymonkey.modpackexport;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.TextComponent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;

/**
 * ModPackExport — Forge 1.18.2 branch. M1: the recipe/name/tag dump only (no JEI/icon/mob
 * dumps yet). Mirrors the neoforge-1.21 trigger model: fire once a few seconds in-world,
 * via {@code recipes.trigger} or {@code -Dmodpackexport.recipes=true}, plus a /recipedump
 * command.
 */
@Mod("modpackexport")
public class ModPackExportMod {
    public static final Logger LOGGER = LoggerFactory.getLogger("modpackexport");

    private boolean recipesFired = false;
    private int ticks = 0;

    public ModPackExportMod() {
        MinecraftForge.EVENT_BUS.addListener(this::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(this::onRegisterClientCommands);
    }

    private void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        ticks++;
        if (ticks < 100) return; // ~5s in-world settle

        if (!recipesFired) {
            boolean enabled = "true".equalsIgnoreCase(System.getProperty("modpackexport.recipes"))
                || Files.exists(FMLPaths.GAMEDIR.get().resolve("recipes.trigger"));
            if (enabled) {
                recipesFired = true;
                LOGGER.info("[recipedump] auto-trigger firing");
                int n = RecipeDumper.dumpAll(LOGGER::info);
                LOGGER.info("[recipedump] auto-trigger wrote {} recipes", n);
            }
        }
    }

    private void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> recipedump = Commands.literal("recipedump")
            .executes(ctx -> {
                CommandSourceStack src = ctx.getSource();
                src.sendSuccess(new TextComponent("[recipedump] dumping recipes..."), false);
                int n = RecipeDumper.dumpAll(msg -> src.sendSuccess(new TextComponent(msg), false));
                src.sendSuccess(new TextComponent("[recipedump] wrote " + n + " recipes to recipes.json"), false);
                return Command.SINGLE_SUCCESS;
            });
        event.getDispatcher().register(recipedump);
        LOGGER.info("[modpackexport] /recipedump registered");
    }
}
