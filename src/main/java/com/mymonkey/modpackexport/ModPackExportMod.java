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
    private boolean loadStarted = false;
    private int ticks = 0;
    private int menuTicks = 0;

    public ModPackExportMod() {
        MinecraftForge.EVENT_BUS.addListener(this::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(this::onRegisterClientCommands);
    }

    private void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();

        // MC < 1.20 has no --quickPlaySingleplayer, so a headless launch sits at a menu.
        // FTB/FancyMenu packs replace the vanilla TitleScreen, so don't match a specific
        // class — once we've sat on ANY non-null menu screen for a few seconds (stable),
        // auto-load the first world so the dump can run. (Reusable for every pre-1.20 branch.)
        if (mc.level == null) {
            if (!loadStarted && mc.screen != null && anyTriggerArmed()) {
                menuTicks++;
                if (menuTicks > 120) { // ~6s settled on a menu
                    loadStarted = true;
                    autoLoadWorld(mc);
                }
            }
            return;
        }
        if (mc.player == null) return;
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

    private boolean anyTriggerArmed() {
        return "true".equalsIgnoreCase(System.getProperty("modpackexport.recipes"))
            || Files.exists(FMLPaths.GAMEDIR.get().resolve("recipes.trigger"));
    }

    /** Open the first single-player world from the title screen. 1.18.2 has the direct
     *  {@code Minecraft.loadLevel(String)} (WorldOpenFlows is 1.19+). */
    private void autoLoadWorld(Minecraft mc) {
        try {
            String world = firstWorld();
            if (world == null) {
                LOGGER.warn("[modpackexport] auto-load: no single-player world in saves/");
                return;
            }
            LOGGER.info("[modpackexport] auto-loading world '{}'", world);
            mc.loadLevel(world);
        } catch (Throwable t) {
            LOGGER.warn("[modpackexport] auto-load failed: {}", t.toString());
        }
    }

    /** First save folder that holds a level.dat — pure java.nio, version-independent. */
    private String firstWorld() {
        java.nio.file.Path saves = FMLPaths.GAMEDIR.get().resolve("saves");
        if (!Files.isDirectory(saves)) return null;
        try (var s = Files.list(saves)) {
            return s.filter(p -> Files.isRegularFile(p.resolve("level.dat")))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .findFirst().orElse(null);
        } catch (Exception e) {
            return null;
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
