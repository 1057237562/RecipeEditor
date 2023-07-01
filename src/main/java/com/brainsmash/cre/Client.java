package com.brainsmash.cre;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class Client implements ClientModInitializer {

    public static final String CATEGORY = "key.category.cre";
    public static KeyBinding openKey = KeyBindingHelper.registerKeyBinding(
            new KeyBinding("key.cre.crawl", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_M, CATEGORY));

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if(openKey.isPressed()){
                ClientPlayerEntity clientPlayerEntity = MinecraftClient.getInstance().player;
                if(clientPlayerEntity.isCreative()){
                    client.setScreen(new CraftingRecipeEditScreen(clientPlayerEntity));
                }
            }
        });
    }
}
