package com.mymonkey.modpackexport;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;

@JeiPlugin
public class JeiDumperPlugin implements IModPlugin {
    private static IJeiRuntime runtime;

    public static IJeiRuntime getRuntime() {
        return runtime;
    }

    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath("modpackexport", "dumper");
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
        ModPackExportMod.LOGGER.info("[jeidump] JEI runtime captured");
    }

    @Override
    public void onRuntimeUnavailable() {
        runtime = null;
    }
}
