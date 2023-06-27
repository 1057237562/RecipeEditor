package com.brainsmash.cre;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerListener;

@Environment(EnvType.CLIENT)
public class CraftingRecipeEditListener implements ScreenHandlerListener {
    private final MinecraftClient client;

    public CraftingRecipeEditListener(MinecraftClient client) {
        this.client = client;
    }

    public void onSlotUpdate(ScreenHandler handler, int slotId, ItemStack stack) {
        this.client.interactionManager.clickCreativeStack(stack, slotId);
    }

    public void onPropertyUpdate(ScreenHandler handler, int property, int value) {
    }
}