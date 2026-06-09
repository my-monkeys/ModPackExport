package com.mymonkey.modpackexport;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.Consumer;

/**
 * Forge 1.18.2 recipe dump. 1.18.2 has no recipe codecs, so the raw recipe JSON is read
 * verbatim from the loaded datapacks (the exact authored shape the host normalizer reads),
 * via the integrated server's resource manager. Item names + tags come from the 1.18.2
 * {@code Registry.ITEM} (BuiltInRegistries is 1.19.3+). Same {@code {recipes,names,tags}}
 * output contract as every branch.
 */
public final class RecipeDumper {
    private RecipeDumper() {}

    public static int dumpAll(Consumer<String> feedback) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            feedback.accept("[recipedump] no integrated server (not in a world yet)");
            return 0;
        }
        ResourceManager res = server.getResourceManager();

        JsonArray recipes = new JsonArray();
        int ok = 0, errored = 0;
        for (ResourceLocation rl : res.listResources("recipes", s -> s.endsWith(".json"))) {
            try {
                String p = rl.getPath();                       // "recipes/<inner>.json"
                if (!p.startsWith("recipes/")) continue;
                String inner = p.substring("recipes/".length(), p.length() - 5);
                String id = rl.getNamespace() + ":" + inner;
                Resource resource = res.getResource(rl);
                String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                JsonElement parsed = JsonParser.parseString(content);
                if (!parsed.isJsonObject()) continue;
                JsonObject obj = parsed.getAsJsonObject();
                JsonObject entry = new JsonObject();
                entry.addProperty("id", id);
                entry.addProperty("type", obj.has("type") ? obj.get("type").getAsString() : "");
                entry.add("json", obj);
                recipes.add(entry);
                ok++;
            } catch (Throwable t) {
                errored++;
                ModPackExportMod.LOGGER.warn("[recipedump] {} failed: {}", rl, t.toString());
            }
        }

        JsonObject names = new JsonObject();
        for (Item item : Registry.ITEM) {
            try {
                names.addProperty(Registry.ITEM.getKey(item).toString(),
                    new ItemStack(item).getHoverName().getString());
            } catch (Throwable ignored) {}
        }

        JsonObject tags = new JsonObject();
        Registry.ITEM.getTags().forEach(pair -> {
            JsonArray arr = new JsonArray();
            pair.getSecond().forEach(h -> {
                try { arr.add(Registry.ITEM.getKey(h.value()).toString()); }
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
}
