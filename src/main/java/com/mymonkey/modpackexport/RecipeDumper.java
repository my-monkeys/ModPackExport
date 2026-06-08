package com.mymonkey.modpackexport;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.Consumer;

/**
 * Dumps every recipe (raw JSON via codec), every item's display name, and every item
 * tag to {@code <gameDir>/recipes.json} — the shape build_crafts.py consumes. Replaces
 * the KubeJS recipe_dump.js: a real mod has no class-filter, so we write the file
 * directly (no log-chunk-and-reassemble). Reached via the integrated server, so it
 * must run in a loaded single-player world (the headless client launches one).
 */
public final class RecipeDumper {
    private RecipeDumper() {}

    public static int dumpAll(Consumer<String> feedback) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            feedback.accept("[recipedump] no integrated server (not in a world yet)");
            return 0;
        }
        RecipeManager rm = server.getRecipeManager();

        JsonArray recipes = new JsonArray();
        int ok = 0, errored = 0;
        for (RecipeHolder<?> holder : rm.getRecipes()) {
            try {
                JsonObject entry = new JsonObject();
                entry.addProperty("id", holder.id().toString());
                Recipe<?> recipe = holder.value();
                entry.addProperty("type",
                    BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer()).toString());
                entry.add("json", encode(recipe));
                recipes.add(entry);
                ok++;
            } catch (Throwable t) {
                errored++;
                ModPackExportMod.LOGGER.warn("[recipedump] recipe {} failed: {}",
                    holder.id(), t.toString());
            }
        }

        JsonObject names = new JsonObject();
        for (Item item : BuiltInRegistries.ITEM) {
            try {
                names.addProperty(BuiltInRegistries.ITEM.getKey(item).toString(),
                    new ItemStack(item).getHoverName().getString());
            } catch (Throwable ignored) {}
        }

        JsonObject tags = new JsonObject();
        BuiltInRegistries.ITEM.getTags().forEach(pair -> {
            JsonArray arr = new JsonArray();
            pair.getSecond().forEach(h -> {
                try { arr.add(BuiltInRegistries.ITEM.getKey(h.value()).toString()); }
                catch (Throwable ignored) {}
            });
            tags.add(pair.getFirst().location().toString(), arr);
        });

        JsonObject root = new JsonObject();
        root.add("recipes", recipes);
        root.add("names", names);
        root.add("tags", tags);

        try {
            var path = FMLPaths.GAMEDIR.get().resolve("recipes.json");
            Files.write(path, new Gson().toJson(root).getBytes(StandardCharsets.UTF_8));
            String summary = String.format(
                "[recipedump] wrote %s — recipes=%d (errored=%d) names=%d tags=%d",
                path, ok, errored, names.size(), tags.size());
            ModPackExportMod.LOGGER.info(summary);
            feedback.accept(summary);
        } catch (Exception e) {
            feedback.accept("[recipedump] write failed: " + e.getMessage());
            return 0;
        }
        return ok;
    }

    /** Re-encode a recipe to its raw JSON via the serializer codec. Raw types capture
     *  the wildcard so the codec accepts the recipe instance. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static JsonElement encode(Recipe<?> recipe) {
        RecipeSerializer ser = recipe.getSerializer();
        return (JsonElement) ser.codec().codec().encodeStart(JsonOps.INSTANCE, recipe).getOrThrow();
    }
}
