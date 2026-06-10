package com.mymonkey.modpackexport;

// JEI recipe-graph dump — the viewer-driven equivalent of the forge-1.18 ReiDisplayDumper, for
// neoforge packs that ship JEI (SB4). Walks every JEI recipe category + recipe, lays the recipe
// out, and records each INPUT/OUTPUT slot's ingredients (item/fluid/chemical id + amount) to
// jei_recipes.json — the SAME schema as rei_recipes.json, so build_crafts_rei.py ingests it as-is.

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public final class JeiRecipeDumper {
    private JeiRecipeDumper() {}

    public static int dumpAll(Consumer<String> feedback) {
        IJeiRuntime rt = JeiDumperPlugin.getRuntime();
        if (rt == null) {
            feedback.accept("[jeirecipedump] JEI runtime not ready");
            return 0;
        }
        IRecipeManager rm = rt.getRecipeManager();
        IIngredientManager mgr = rt.getIngredientManager();
        IFocusGroup empty = rt.getJeiHelpers().getFocusFactory().getEmptyFocusGroup();

        JsonArray displays = new JsonArray();
        int recipes = 0, cats = 0, skipped = 0;
        List<IRecipeCategory<?>> categories = rm.createRecipeCategoryLookup().get().toList();
        for (IRecipeCategory<?> category : categories) {
            cats++;
            try {
                recipes += dumpCategory(rm, mgr, empty, category, displays);
            } catch (Throwable t) {
                skipped++;
                ModPackExportMod.LOGGER.warn("[jeirecipedump] category {} failed: {}",
                    category.getRecipeType().getUid(), t.toString());
            }
            if (cats % 25 == 0) {
                feedback.accept("[jeirecipedump] " + cats + "/" + categories.size() + " categories ("
                    + recipes + " recipes)");
            }
        }
        JsonObject root = new JsonObject();
        root.addProperty("count", recipes);
        root.addProperty("categories", cats);
        root.add("displays", displays);
        try {
            Files.write(FMLPaths.GAMEDIR.get().resolve("jei_recipes.json"),
                new Gson().toJson(root).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            ModPackExportMod.LOGGER.warn("[jeirecipedump] write failed: {}", e.toString());
        }
        String summary = "[jeirecipedump] " + recipes + " recipes in " + cats + " categories ("
            + skipped + " cat skipped) -> jei_recipes.json";
        ModPackExportMod.LOGGER.info(summary);
        feedback.accept(summary);
        return recipes;
    }

    private static <T> int dumpCategory(IRecipeManager rm, IIngredientManager mgr, IFocusGroup empty,
                                        IRecipeCategory<T> category, JsonArray displays) {
        RecipeType<T> type = category.getRecipeType();
        String uid = type.getUid().toString();
        int n = 0;
        List<T> recipes = rm.createRecipeLookup(type).includeHidden().get().toList();
        for (T recipe : recipes) {
            try {
                Optional<IRecipeLayoutDrawable<T>> opt = rm.createRecipeLayoutDrawable(category, recipe, empty);
                if (opt.isEmpty()) continue;
                IRecipeLayoutDrawable<T> layout = opt.get();
                JsonArray inputs = new JsonArray(), outputs = new JsonArray();
                for (IRecipeSlotView view : layout.getRecipeSlotsView().getSlotViews()) {
                    RecipeIngredientRole role = view.getRole();
                    boolean isOut = role == RecipeIngredientRole.OUTPUT;
                    boolean isIn = role == RecipeIngredientRole.INPUT;
                    if (!isIn && !isOut) continue;                 // skip catalysts/render-only
                    JsonArray options = slotOptions(view, mgr);
                    if (options.isEmpty()) continue;
                    (isOut ? outputs : inputs).add(options);
                }
                if (outputs.isEmpty()) continue;                   // no output → not a craftable recipe
                JsonObject d = new JsonObject();
                d.addProperty("category", uid);
                d.add("inputs", inputs);
                d.add("outputs", outputs);
                displays.add(d);
                n++;
            } catch (Throwable t) {
                // a recipe that won't lay out — skip it, keep the rest of the category
            }
        }
        return n;
    }

    /** A slot's interchangeable ingredients (a tag's members) → [ {id,type,amount} ]. */
    private static JsonArray slotOptions(IRecipeSlotView view, IIngredientManager mgr) {
        JsonArray options = new JsonArray();
        for (ITypedIngredient<?> ti : view.getAllIngredientsList()) {
            try {
                ResourceLocation rl = idOf(ti, mgr);
                if (rl == null) continue;
                Object ing = ti.getIngredient();
                boolean isItem = ti.getItemStack().isPresent();
                String kind = isItem ? "minecraft:item"
                    : (ing.getClass().getSimpleName().toLowerCase().contains("fluid") ? "minecraft:fluid" : "chemical");
                long amount = isItem ? ti.getItemStack().get().getCount() : amountOf(ing);
                JsonObject o = new JsonObject();
                o.addProperty("id", rl.toString());
                o.addProperty("type", kind);
                o.addProperty("amount", amount);
                options.add(o);
            } catch (Throwable ignored) {
                // un-resolvable ingredient — skip
            }
        }
        return options;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ResourceLocation idOf(ITypedIngredient<?> ti, IIngredientManager mgr) {
        IIngredientType type = ti.getType();
        return mgr.getIngredientHelper(type).getResourceLocation(ti.getIngredient());
    }

    /** Fluids/chemicals expose getAmount() on their stack (FluidStack / Mekanism ChemicalStack). */
    private static long amountOf(Object ing) {
        try {
            Object a = ing.getClass().getMethod("getAmount").invoke(ing);
            if (a instanceof Number num) return num.longValue();
        } catch (Throwable ignored) {
        }
        return 1;
    }
}
