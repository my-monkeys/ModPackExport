package com.mymonkey.modpackexport;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;

@Mod("modpackexport")
public class ModPackExportMod {
    public static final Logger LOGGER = LoggerFactory.getLogger("modpackexport");

    // Headless auto-triggers:
    //  - jeidump (layouts + backgrounds): -Dmodpackexport.layouts=true OR <gameDir>/jeidump.trigger
    //  - itemdump (item-icon bank):       -Dmodpackexport.items=true OR <gameDir>/itemdump.trigger
    // Each fires once, a few seconds in-world, without a typed/KubeJS command.
    private boolean autoFired = false;
    private boolean itemsFired = false;
    private boolean mobsFired = false;
    private boolean fcFired = false;
    private boolean recipesFired = false;
    private int autoTicks = 0;

    public ModPackExportMod() {
        NeoForge.EVENT_BUS.addListener(this::onRegisterClientCommands);
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
    }

    private void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        autoTicks++;
        if (autoTicks < 100) { // ~5s in-world settle
            return;
        }

        if (!autoFired) {
            boolean enabled = "true".equalsIgnoreCase(System.getProperty("modpackexport.layouts"))
                || Files.exists(FMLPaths.GAMEDIR.get().resolve("jeidump.trigger"));
            if (enabled && JeiDumperPlugin.getRuntime() != null) {
                autoFired = true;
                LOGGER.info("[jeidump] auto-trigger firing");
                int n = JeiLayoutDumper.dumpAll(LOGGER::info);
                LOGGER.info("[jeidump] auto-trigger done: {} categories", n);
            }
        }

        if (!itemsFired) {
            boolean enabled = "true".equalsIgnoreCase(System.getProperty("modpackexport.items"))
                || Files.exists(FMLPaths.GAMEDIR.get().resolve("itemdump.trigger"));
            if (enabled) {
                itemsFired = true;
                LOGGER.info("[itemdump] auto-trigger firing");
                int n = ItemIconDumper.dumpAll(LOGGER::info);
                LOGGER.info("[itemdump] auto-trigger queued {} items", n);
            }
        }

        if (!mobsFired) {
            boolean enabled = "true".equalsIgnoreCase(System.getProperty("modpackexport.mobs"))
                || Files.exists(FMLPaths.GAMEDIR.get().resolve("mobdump.trigger"));
            if (enabled) {
                mobsFired = true;
                LOGGER.info("[mobdump] auto-trigger firing");
                int n = EntityIconDumper.dumpAll(LOGGER::info);
                LOGGER.info("[mobdump] auto-trigger queued {} entities", n);
            }
        }

        if (!fcFired) {
            boolean enabled = "true".equalsIgnoreCase(System.getProperty("modpackexport.fc"))
                || Files.exists(FMLPaths.GAMEDIR.get().resolve("fcdump.trigger"));
            if (enabled && JeiDumperPlugin.getRuntime() != null) {
                fcFired = true;
                LOGGER.info("[fcdump] auto-trigger firing");
                int n = IngredientIconDumper.dumpAll(LOGGER::info);
                LOGGER.info("[fcdump] auto-trigger queued {} ingredients", n);
            }
        }

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
        LiteralArgumentBuilder<CommandSourceStack> jeidump = Commands.literal("jeidump")
            .executes(ctx -> {
                CommandSourceStack src = ctx.getSource();
                src.sendSuccess(() -> Component.literal("[jeidump] starting layout dump..."), false);
                int n = JeiLayoutDumper.dumpAll(msg -> src.sendSuccess(() -> Component.literal(msg), false));
                src.sendSuccess(() -> Component.literal("[jeidump] done: " + n + " categories dumped to jei-layouts/"), false);
                return Command.SINGLE_SUCCESS;
            });
        event.getDispatcher().register(jeidump);

        LiteralArgumentBuilder<CommandSourceStack> itemdump = Commands.literal("itemdump")
            .executes(ctx -> {
                CommandSourceStack src = ctx.getSource();
                src.sendSuccess(() -> Component.literal("[itemdump] starting item-icon bank dump..."), false);
                int n = ItemIconDumper.dumpAll(msg -> src.sendSuccess(() -> Component.literal(msg), false));
                src.sendSuccess(() -> Component.literal("[itemdump] rendering " + n + " items to item-icons/"), false);
                return Command.SINGLE_SUCCESS;
            });
        event.getDispatcher().register(itemdump);

        LiteralArgumentBuilder<CommandSourceStack> mobdump = Commands.literal("mobdump")
            .executes(ctx -> {
                CommandSourceStack src = ctx.getSource();
                src.sendSuccess(() -> Component.literal("[mobdump] starting entity turntable dump..."), false);
                int n = EntityIconDumper.dumpAll(msg -> src.sendSuccess(() -> Component.literal(msg), false));
                src.sendSuccess(() -> Component.literal("[mobdump] rendering " + n + " entities to mob-icons/"), false);
                return Command.SINGLE_SUCCESS;
            });
        event.getDispatcher().register(mobdump);

        LiteralArgumentBuilder<CommandSourceStack> fcdump = Commands.literal("fcdump")
            .executes(ctx -> {
                CommandSourceStack src = ctx.getSource();
                src.sendSuccess(() -> Component.literal("[fcdump] starting fluid/chemical icon dump..."), false);
                int n = IngredientIconDumper.dumpAll(msg -> src.sendSuccess(() -> Component.literal(msg), false));
                src.sendSuccess(() -> Component.literal("[fcdump] rendering " + n + " ingredients to fluid-icons/"), false);
                return Command.SINGLE_SUCCESS;
            });
        event.getDispatcher().register(fcdump);

        LiteralArgumentBuilder<CommandSourceStack> recipedump = Commands.literal("recipedump")
            .executes(ctx -> {
                CommandSourceStack src = ctx.getSource();
                src.sendSuccess(() -> Component.literal("[recipedump] dumping recipes..."), false);
                int n = RecipeDumper.dumpAll(msg -> src.sendSuccess(() -> Component.literal(msg), false));
                src.sendSuccess(() -> Component.literal("[recipedump] wrote " + n + " recipes to recipes.json"), false);
                return Command.SINGLE_SUCCESS;
            });
        event.getDispatcher().register(recipedump);

        LOGGER.info("[jeidump] /jeidump + /itemdump + /mobdump + /fcdump + /recipedump client commands registered");
    }
}
