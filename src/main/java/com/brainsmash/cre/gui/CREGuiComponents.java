package com.brainsmash.cre.gui;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import com.brainsmash.cre.CraftingRecipeEditScreen;
import com.brainsmash.cre.interfaces.CREGuiExtensions;
import net.fabricmc.fabric.impl.client.item.group.CreativeGuiExtensions;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemGroup;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import com.mojang.blaze3d.systems.RenderSystem;

public class CREGuiComponents {
    private static final Identifier BUTTON_TEX = new Identifier("fabric", "textures/gui/creative_buttons.png");
    public static final Set<ItemGroup> COMMON_GROUPS = new HashSet<>();

    static {
        COMMON_GROUPS.add(ItemGroup.SEARCH);
        COMMON_GROUPS.add(ItemGroup.INVENTORY);
        COMMON_GROUPS.add(ItemGroup.HOTBAR);
    }

    public static class ItemGroupButtonWidget extends ButtonWidget {
        CREGuiExtensions extensions;
        CraftingRecipeEditScreen gui;
        Type type;

        public ItemGroupButtonWidget(int x, int y, Type type, CREGuiExtensions extensions) {
            super(x, y, 11, 10, type.text, (bw) -> type.clickConsumer.accept(extensions));
            this.extensions = extensions;
            this.type = type;
            this.gui = (CraftingRecipeEditScreen) extensions;
        }

        @Override
        public void render(MatrixStack matrixStack, int mouseX, int mouseY, float float_1) {
            this.hovered = mouseX >= this.x && mouseY >= this.y && mouseX < this.x + this.width && mouseY < this.y + this.height;
            this.visible = extensions.fabric_isButtonVisible(type);
            this.active = extensions.fabric_isButtonEnabled(type);

            if (this.visible) {
                int u = active && this.isHovered() ? 22 : 0;
                int v = active ? 0 : 10;

                RenderSystem.setShaderTexture(0, BUTTON_TEX);
                RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
                this.drawTexture(matrixStack, this.x, this.y, u + (type == Type.NEXT ? 11 : 0), v, 11, 10);

                if (this.hovered) {
                    int pageCount = (int) Math.ceil((ItemGroup.GROUPS.length - COMMON_GROUPS.size()) / 9D);
                    gui.renderTooltip(matrixStack, Text.translatable("cre.gui.craftingTabPage", extensions.fabric_currentPage() + 1, pageCount), mouseX, mouseY);
                }
            }
        }
    }

    public enum Type {
        NEXT(Text.literal(">"), CREGuiExtensions::fabric_nextPage),
        PREVIOUS(Text.literal("<"), CREGuiExtensions::fabric_previousPage);

        Text text;
        Consumer<CREGuiExtensions> clickConsumer;

        Type(Text text, Consumer<CREGuiExtensions> clickConsumer) {
            this.text = text;
            this.clickConsumer = clickConsumer;
        }
    }
}