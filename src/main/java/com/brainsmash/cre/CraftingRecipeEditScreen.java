package com.brainsmash.cre;

import com.brainsmash.cre.gui.CREGuiComponents;
import com.brainsmash.cre.interfaces.CREGuiExtensions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.datafixers.util.Pair;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.impl.client.item.group.CreativeGuiExtensions;
import net.fabricmc.fabric.impl.client.item.group.FabricCreativeGuiComponents;
import net.fabricmc.fabric.mixin.item.group.client.CreativeInventoryScreenMixin;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.ingame.AbstractInventoryScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.HotbarStorage;
import net.minecraft.client.option.HotbarStorageEntry;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.search.SearchManager;
import net.minecraft.client.search.SearchProvider;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.data.server.RecipeProvider;
import net.minecraft.data.server.recipe.RecipeJsonProvider;
import net.minecraft.data.server.recipe.ShapedRecipeJsonBuilder;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.CraftingInventory;
import net.minecraft.inventory.CraftingResultInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.screen.slot.CraftingResultSlot;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.tag.ItemTags;
import net.minecraft.tag.TagKey;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.registry.Registry;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static com.brainsmash.cre.Main.MODID;

@Environment(EnvType.CLIENT)
public class CraftingRecipeEditScreen extends AbstractInventoryScreen<CraftingRecipeEditScreen.CraftingRecipeEditScreenHandler> implements CREGuiExtensions {
    private static final Identifier TEXTURE = new Identifier(MODID,"textures/gui/container/creative_inventory/tabs.png");
    private static final String TAB_TEXTURE_PREFIX = "textures/gui/container/creative_inventory/tab_";
    private static final String CUSTOM_CREATIVE_LOCK_KEY = "CustomCreativeLock";
    private static final int ROWS_COUNT = 5;
    private static final int COLUMNS_COUNT = 9;
    private static final int TAB_WIDTH = 28;
    private static final int TAB_HEIGHT = 32;
    private static final int SCROLLBAR_WIDTH = 12;
    private static final int SCROLLBAR_HEIGHT = 15;
    static final SimpleInventory INVENTORY = new SimpleInventory(45);
    private static final Text DELETE_ITEM_SLOT_TEXT = Text.translatable("inventory.binSlot");
    private static final int WHITE = 16777215;
    private static int selectedTab;
    private float scrollPosition;
    private boolean scrolling;
    private TextFieldWidget searchBox;
    private TextFieldWidget pathBox;
    @Nullable
    private List<Slot> slots;
    @Nullable
    private Slot deleteItemSlot;
    private CraftingRecipeEditListener listener;
    private boolean ignoreTypedCharacter;
    private boolean lastClickOutsideBounds;
    private final Set<TagKey<Item>> searchResultTags = new HashSet();

    public CraftingRecipeEditScreen(PlayerEntity player) {
        super(new CraftingRecipeEditScreenHandler(player), player.getInventory(), ScreenTexts.EMPTY);
        player.currentScreenHandler = this.handler;
        this.passEvents = true;
        this.backgroundHeight = 191;
        this.backgroundWidth = 195;
    }

    public void handledScreenTick() {
        super.handledScreenTick();
        if (!this.client.interactionManager.hasCreativeInventory()) {
            this.client.setScreen(new InventoryScreen(this.client.player));
        } else {
            if(this.pathBox.isFocused()){
                this.pathBox.tick();
            }
            if(this.searchBox != null && this.searchBox.isFocused()) {
                this.searchBox.tick();
            }
        }
    }

    protected void onMouseClick(@Nullable Slot slot, int slotId, int button, SlotActionType actionType) {
        Main.LOGGER.info("Slot clicked: " + slotId);
        if(slot!=null){
            Main.LOGGER.info("Inventory selected: " + slot.inventory.toString());
            Main.LOGGER.info("Stack:" + slot.getStack().toString());
        }
        if (this.isCreativeInventorySlot(slot)) {
            this.searchBox.setCursorToEnd();
            this.searchBox.setSelectionEnd(0);
        }

        boolean bl = actionType == SlotActionType.QUICK_MOVE;
        actionType = slotId == -999 && actionType == SlotActionType.PICKUP ? SlotActionType.THROW : actionType;
        ItemStack itemStack;
        if (slot == null && selectedTab != ItemGroup.INVENTORY.getIndex() && actionType != SlotActionType.QUICK_CRAFT) {
            if (!(this.handler).getCursorStack().isEmpty() && this.lastClickOutsideBounds) {
                if (button == 0) {
                    this.client.player.dropItem((this.handler).getCursorStack(), true);
                    this.client.interactionManager.dropCreativeStack((this.handler).getCursorStack());
                    (this.handler).setCursorStack(ItemStack.EMPTY);
                }

                if (button == 1) {
                    itemStack = (this.handler).getCursorStack().split(1);
                    this.client.player.dropItem(itemStack, true);
                    this.client.interactionManager.dropCreativeStack(itemStack);
                }
            }
        } else {
            if (slot != null && !slot.canTakeItems(this.client.player)) {
                return;
            }

            if (slot == this.deleteItemSlot && bl) {
                for(int i = 0; i < this.client.player.playerScreenHandler.getStacks().size(); ++i) {
                    this.client.interactionManager.clickCreativeStack(ItemStack.EMPTY, i);
                }
            } else {
                ItemStack itemStack2;
                if (selectedTab == ItemGroup.INVENTORY.getIndex()) {
                    if (slot == this.deleteItemSlot) {
                        (this.handler).setCursorStack(ItemStack.EMPTY);
                    } else if (actionType == SlotActionType.THROW && slot != null && slot.hasStack()) {
                        itemStack = slot.takeStack(button == 0 ? 1 : slot.getStack().getMaxCount());
                        itemStack2 = slot.getStack();
                        this.client.player.dropItem(itemStack, true);
                        this.client.interactionManager.dropCreativeStack(itemStack);
                        this.client.interactionManager.clickCreativeStack(itemStack2, ((CreativeSlot)slot).slot.id);
                    } else if (actionType == SlotActionType.THROW && !(this.handler).getCursorStack().isEmpty()) {
                        this.client.player.dropItem((this.handler).getCursorStack(), true);
                        this.client.interactionManager.dropCreativeStack((this.handler).getCursorStack());
                        (this.handler).setCursorStack(ItemStack.EMPTY);
                    } else {
                        this.client.player.playerScreenHandler.onSlotClick(slot == null ? slotId : ((CreativeSlot)slot).slot.id, button, actionType, this.client.player);
                        this.client.player.playerScreenHandler.sendContentUpdates();
                    }
                } else if (actionType != SlotActionType.QUICK_CRAFT && slot.inventory == INVENTORY) {
                    itemStack = (this.handler).getCursorStack();
                    itemStack2 = slot.getStack();
                    ItemStack itemStack3;
                    if (actionType == SlotActionType.SWAP) {
                        if (!itemStack2.isEmpty()) {
                            itemStack3 = itemStack2.copy();
                            itemStack3.setCount(itemStack3.getMaxCount());
                            this.client.player.getInventory().setStack(button, itemStack3);
                            this.client.player.playerScreenHandler.sendContentUpdates();
                        }

                        return;
                    }

                    if (actionType == SlotActionType.CLONE) {
                        if ((this.handler).getCursorStack().isEmpty() && slot.hasStack()) {
                            itemStack3 = slot.getStack().copy();
                            itemStack3.setCount(itemStack3.getMaxCount());
                            (this.handler).setCursorStack(itemStack3);
                        }

                        return;
                    }

                    if (actionType == SlotActionType.THROW) {
                        if (!itemStack2.isEmpty()) {
                            itemStack3 = itemStack2.copy();
                            itemStack3.setCount(button == 0 ? 1 : itemStack3.getMaxCount());
                            this.client.player.dropItem(itemStack3, true);
                            this.client.interactionManager.dropCreativeStack(itemStack3);
                        }

                        return;
                    }

                    if (!itemStack.isEmpty() && !itemStack2.isEmpty() && itemStack.isItemEqualIgnoreDamage(itemStack2) && ItemStack.areNbtEqual(itemStack, itemStack2)) {
                        if (button == 0) {
                            if (bl) {
                                itemStack.setCount(itemStack.getMaxCount());
                            } else if (itemStack.getCount() < itemStack.getMaxCount()) {
                                itemStack.increment(1);
                            }
                        } else {
                            itemStack.decrement(1);
                        }
                    } else if (!itemStack2.isEmpty() && itemStack.isEmpty()) {
                        (this.handler).setCursorStack(itemStack2.copy());
                        itemStack = (this.handler).getCursorStack();
                        if (bl) {
                            itemStack.setCount(itemStack.getMaxCount());
                        }
                    } else if (button == 0) {
                        (this.handler).setCursorStack(ItemStack.EMPTY);
                    } else {
                        (this.handler).getCursorStack().decrement(1);
                    }
                } else if (this.handler != null) {
                    itemStack = slot == null ? ItemStack.EMPTY : (this.handler).getSlot(slot.id).getStack();
                    Main.LOGGER.info(itemStack.toString());
                    (this.handler).onSlotClick(slot == null ? slotId : slot.id, button, actionType, this.client.player);

                    // TODO: check out what the hell is this below
                    /*
                    if (ScreenHandler.unpackQuickCraftStage(button) == 2) {
                        for(int j = 0; j < 9; ++j) {
                            this.client.interactionManager.clickCreativeStack((this.handler).getSlot(45 + j).getStack(), 36 + j);
                        }
                    } else if (slot != null) {
                        itemStack2 = (this.handler).getSlot(slot.id).getStack();
                        this.client.interactionManager.clickCreativeStack(itemStack2, slot.id - (this.handler).slots.size() + 9 + 36);
                        int k = 45 + button;
                        if (actionType == SlotActionType.SWAP) {
                            this.client.interactionManager.clickCreativeStack(itemStack, k - (this.handler).slots.size() + 9 + 36);
                        } else if (actionType == SlotActionType.THROW && !itemStack.isEmpty()) {
                            ItemStack itemStack4 = itemStack.copy();
                            itemStack4.setCount(button == 0 ? 1 : itemStack4.getMaxCount());
                            this.client.player.dropItem(itemStack4, true);
                            this.client.interactionManager.dropCreativeStack(itemStack4);
                        }

                        this.client.player.playerScreenHandler.sendContentUpdates();
                    }
                    */
                }
            }
        }

    }

    private boolean isCreativeInventorySlot(@Nullable Slot slot) {
        return slot != null && slot.inventory == INVENTORY;
    }

    protected void init() {
        if (this.client.interactionManager.hasCreativeInventory()) {
            super.init();
            this.client.keyboard.setRepeatEvents(true);
            TextRenderer var10003 = this.textRenderer;
            Objects.requireNonNull(this.textRenderer);
            this.searchBox = new TextFieldWidget(var10003, x+82, y+6, 80, 9, Text.translatable("itemGroup.search"));
            this.searchBox.setMaxLength(50);
            this.searchBox.setDrawsBackground(false);
            this.searchBox.setVisible(false);
            this.searchBox.setEditableColor(16777215);
            this.addSelectableChild(this.searchBox);
            this.pathBox = new TextFieldWidget(var10003, x+101, y+132, 80, 9, Text.translatable("crafting_recipe.path"));
            this.pathBox.setMaxLength(50);
            this.pathBox.setDrawsBackground(false);
            this.pathBox.setVisible(true);
            this.pathBox.setEditableColor(16777215);
            this.addSelectableChild(this.pathBox);
            this.pathBox.setText("item.json");

            this.addDrawableChild(new ButtonWidget(x + 6, y + 147, 35, 20, Text.translatable("crafting_recipe.done"), new ButtonWidget.PressAction() {
                @Override
                public void onPress(ButtonWidget button) {
                    generateRecipeJson();
                    // Main.LOGGER.info("generateRecipe button clicked");
                }
            }));
            int i = selectedTab;
            selectedTab = -1;
            this.setSelectedTab(ItemGroup.GROUPS[i]);
            this.client.player.playerScreenHandler.removeListener(this.listener);
            this.listener = new CraftingRecipeEditListener(this.client);
            this.client.player.playerScreenHandler.addListener(this.listener);
        } else {
            this.client.setScreen(new InventoryScreen(this.client.player));
        }
        fabric_updateSelection();
        int xpos = x + 116;
        int ypos = y - 10;
        addDrawableChild(new CREGuiComponents.ItemGroupButtonWidget(xpos + 11, ypos, CREGuiComponents.Type.NEXT, this));
        addDrawableChild(new CREGuiComponents.ItemGroupButtonWidget(xpos, ypos, CREGuiComponents.Type.PREVIOUS, this));
    }

    private void generateRecipeJson(){
        try{
            Map<Text, Character> patternMap = new HashMap<>();
            char patternIndex = 'A';

            ShapedRecipeJsonBuilder builder = ShapedRecipeJsonBuilder.create(this.handler.OUTPUT.getStack(0).getItem(), this.handler.OUTPUT.getStack(0).getCount());
            for(int i=0;i<3;i++){
                StringBuilder pattern = new StringBuilder();
                for(int j=0;j<3;j++){
                    ItemStack nowItemStack = this.handler.RECIPE.getStack(j+ i * 3);
                    if(!nowItemStack.isEmpty()){
                        Item nowItem = nowItemStack.getItem();
                        if(!patternMap.containsKey(nowItem.getName())){
                            builder.input(patternIndex, nowItem);
                            patternMap.put(nowItem.getName(), patternIndex++);
                        }
                        pattern.append(patternMap.get(nowItem.getName()));
                    }
                    else pattern.append(' ');
                }
                // Main.LOGGER.info(pattern.toString());
                builder.pattern(pattern.toString());
            }
            builder.criterion("has_planks", RecipeProvider.conditionsFromTag(ItemTags.PLANKS));
            builder.offerTo(recipeJsonProvider -> {
                Gson builder1 = new GsonBuilder().setPrettyPrinting().create();
                String ans = builder1.toJson(recipeJsonProvider.toJson());

                Main.LOGGER.info(ans);
                File file = new File("../src/main/resources/data/minecraft/recipes/");
                System.out.println(file.getAbsolutePath());
                file.mkdirs();
                FileWriter writer;
                try {
                    writer = new FileWriter("../src/main/resources/data/minecraft/recipes/" + pathBox.getText());
                    writer.write(ans);
                    writer.flush();
                    writer.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        }
        catch(Exception e){
            Main.LOGGER.info(e.toString());
        }
    }
    public void resize(MinecraftClient client, int width, int height) {
        String string = this.searchBox.getText();
        String pathString = this.pathBox.getText();
        this.init(client, width, height);
        this.searchBox.setText(string);
        this.pathBox.setText(pathString);
        if (!this.searchBox.getText().isEmpty()) {
            this.search();
        }

    }

    public void removed() {
        super.removed();
        if (this.client.player != null && this.client.player.getInventory() != null) {
            this.client.player.playerScreenHandler.removeListener(this.listener);
        }

        this.client.keyboard.setRepeatEvents(false);
    }

    public boolean charTyped(char chr, int modifiers) {
        if (this.ignoreTypedCharacter) {
            return false;
        } else if (selectedTab != ItemGroup.SEARCH.getIndex()) {
            if(this.pathBox.isFocused()){
                this.pathBox.charTyped(chr, modifiers);
                return true;
            }
            else return false;
        } else { // in search tab
            if(this.pathBox.isFocused()){
                // Main.LOGGER.info("charTyped: "+ chr + " " + " in pathBox");
                this.pathBox.charTyped(chr, modifiers);
                return true;
            }
            else {
                // Main.LOGGER.info("charTyped: "+ chr + " " + " in searchBox");
                String string = this.searchBox.getText();
                if (this.searchBox.charTyped(chr, modifiers)) {
                    if (!Objects.equals(string, this.searchBox.getText())) {
                        this.search();
                    }

                    return true;
                } else {
                    return false;
                }
            }
        }
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        this.ignoreTypedCharacter = false;
        if (selectedTab != ItemGroup.SEARCH.getIndex()) {
            if (this.client.options.chatKey.matchesKey(keyCode, scanCode)) {
                this.ignoreTypedCharacter = true;
                this.setSelectedTab(ItemGroup.SEARCH);
                return true;
            } else {
                if(this.pathBox.isFocused()){
                    if (this.pathBox.keyPressed(keyCode, scanCode, modifiers)) {
                        return true;
                    } else {
                        return this.pathBox.isFocused() && this.pathBox.isVisible() && keyCode != GLFW.GLFW_KEY_ESCAPE || super.keyPressed(
                                keyCode, scanCode, modifiers);
                    }
                }
                else return super.keyPressed(keyCode, scanCode, modifiers);
            }
        } else {
            boolean bl = !this.isCreativeInventorySlot(this.focusedSlot) || this.focusedSlot.hasStack();
            boolean bl2 = InputUtil.fromKeyCode(keyCode, scanCode).toInt().isPresent();
            if (bl && bl2 && this.handleHotbarKeyPressed(keyCode, scanCode)) {
                this.ignoreTypedCharacter = true;
                return true;
            } else {
                if(this.searchBox.isFocused()){
                    String string = this.searchBox.getText();
                    if (this.searchBox.keyPressed(keyCode, scanCode, modifiers)) {
                        if (!Objects.equals(string, this.searchBox.getText())) {
                            this.search();
                        }

                        return true;
                    } else {
                        return this.searchBox.isFocused() && this.searchBox.isVisible() && keyCode != GLFW.GLFW_KEY_ESCAPE || super.keyPressed(
                                keyCode, scanCode, modifiers);
                    }
                }
                else if(this.pathBox.isFocused()){
                    if (this.pathBox.keyPressed(keyCode, scanCode, modifiers)) {
                        return true;
                    } else {
                        return this.pathBox.isFocused() && this.pathBox.isVisible() && keyCode != GLFW.GLFW_KEY_ESCAPE || super.keyPressed(
                                keyCode, scanCode, modifiers);
                    }
                }
                else {
                    // Main.LOGGER.error("Failed to handle: key pressed");
                    return keyCode != GLFW.GLFW_KEY_ESCAPE || super.keyPressed(keyCode, scanCode, modifiers);
                }
            }
        }
    }

    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        this.ignoreTypedCharacter = false;
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    private void search() {
        (this.handler).itemList.clear();
        this.searchResultTags.clear();
        String string = this.searchBox.getText();
        if (string.isEmpty()) {

            for (Item item : Registry.ITEM) {
                item.appendStacks(ItemGroup.SEARCH, (this.handler).itemList);
            }
        } else {
            SearchProvider searchProvider;
            if (string.startsWith("#")) {
                string = string.substring(1);
                searchProvider = this.client.getSearchProvider(SearchManager.ITEM_TAG);
                this.searchForTags(string);
            } else {
                searchProvider = this.client.getSearchProvider(SearchManager.ITEM_TOOLTIP);
            }

            (this.handler).itemList.addAll(searchProvider.findAll(string.toLowerCase(Locale.ROOT)));
        }

        this.scrollPosition = 0.0F;
        (this.handler).scrollItems(0.0F);
    }

    private void searchForTags(String id) {
        int i = id.indexOf(58);
        Predicate<Identifier> predicate;
        if (i == -1) {
            predicate = (idx) -> idx.getPath().contains(id);
        } else {
            String string = id.substring(0, i).trim();
            String string2 = id.substring(i + 1).trim();
            predicate = (idx) -> idx.getNamespace().contains(string) && idx.getPath().contains(string2);
        }

        Stream<TagKey<Item>> var10000 = Registry.ITEM.streamTags().filter((tagKey) -> predicate.test(tagKey.id()));
        Set<TagKey<Item>> var10001 = this.searchResultTags;
        Objects.requireNonNull(var10001);
        var10000.forEach(var10001::add);
    }

    protected void drawForeground(MatrixStack matrices, int mouseX, int mouseY) {
        ItemGroup itemGroup = ItemGroup.GROUPS[selectedTab];
        if (itemGroup.shouldRenderName()) {
            RenderSystem.disableBlend();
            this.textRenderer.draw(matrices, itemGroup.getDisplayName(), 8.0F, 6.0F, 4210752);
        }

    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        Main.LOGGER.info("clicked: " + String.format("%.2f", mouseX) + " " + String.format("%.2f", mouseY) + " " + button);
        // Main.LOGGER.info("pathBox focused:" + this.pathBox.isFocused());
        // Main.LOGGER.info("searchBox focused:" + this.searchBox.isFocused());

        if (button == 0) {
            double d = mouseX - (double)this.x;
            double e = mouseY - (double)this.y;
            ItemGroup[] var10 = ItemGroup.GROUPS;
            int var11 = var10.length;

            for(int var12 = 0; var12 < var11; ++var12) {
                ItemGroup itemGroup = var10[var12];
                if (this.isClickInTab(itemGroup, d, e)) {
                    return true;
                }
            }

            if (selectedTab != ItemGroup.INVENTORY.getIndex() && this.isClickInScrollbar(mouseX, mouseY)) {
                this.scrolling = this.hasScrollbar();
                return true;
            }
        }
        if(this.pathBox.mouseClicked(mouseX, mouseY, button)){
            this.searchBox.setTextFieldFocused(false);
            return true;
        }
        else if(this.searchBox.mouseClicked(mouseX, mouseY, button)){
            this.pathBox.setTextFieldFocused(false);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            double d = mouseX - (double)this.x;
            double e = mouseY - (double)this.y;
            this.scrolling = false;
            ItemGroup[] var10 = ItemGroup.GROUPS;
            int var11 = var10.length;

            for (ItemGroup itemGroup : var10) {
                if (this.isClickInTab(itemGroup, d, e)) {
                    this.setSelectedTab(itemGroup);
                    return true;
                }
            }
        }

        return super.mouseReleased(mouseX, mouseY, button);
    }

    private boolean hasScrollbar() {
        return selectedTab != ItemGroup.INVENTORY.getIndex() && ItemGroup.GROUPS[selectedTab].hasScrollbar() && (this.handler).shouldShowScrollbar();
    }

    private void setSelectedTab(ItemGroup group) {
        if (!fabric_isGroupVisible(group)) {
            return;
        }
        // Main.LOGGER.info("SelectedTab "+ group.getName());
        int i = selectedTab;
        selectedTab = group.getIndex();
        this.cursorDragSlots.clear();
        (this.handler).itemList.clear();
        this.endTouchDrag();
        int j;
        int k;
        if (group == ItemGroup.HOTBAR) {
            HotbarStorage hotbarStorage = this.client.getCreativeHotbarStorage();

            for(j = 0; j < 9; ++j) {
                HotbarStorageEntry hotbarStorageEntry = hotbarStorage.getSavedHotbar(j);
                if (hotbarStorageEntry.isEmpty()) {
                    for(k = 0; k < 9; ++k) {
                        if (k == j) {
                            ItemStack itemStack = new ItemStack(Items.PAPER);
                            itemStack.getOrCreateSubNbt("CustomCreativeLock");
                            Text text = this.client.options.hotbarKeys[j].getBoundKeyLocalizedText();
                            Text text2 = this.client.options.saveToolbarActivatorKey.getBoundKeyLocalizedText();
                            itemStack.setCustomName(Text.translatable("inventory.hotbarInfo", text2, text));
                            (this.handler).itemList.add(itemStack);
                        } else {
                            (this.handler).itemList.add(ItemStack.EMPTY);
                        }
                    }
                } else {
                    (this.handler).itemList.addAll(hotbarStorageEntry);
                }
            }
        } else if (group != ItemGroup.SEARCH) {
            group.appendStacks((this.handler).itemList);
        }

        if (group == ItemGroup.INVENTORY) {
            ScreenHandler screenHandler = this.client.player.playerScreenHandler;
            if (this.slots == null) {
                this.slots = ImmutableList.copyOf((Collection)(this.handler).slots);
            }

            (this.handler).slots.clear();

            for(j = 0; j < screenHandler.slots.size(); ++j) {
                int o;
                int l;
                int m;
                int n;
                if (j >= 5 && j < 9) {
                    l = j - 5;
                    m = l / 2;
                    n = l % 2;
                    o = 54 + m * 54;
                    k = 6 + n * 27;
                } else if (j >= 0 && j < 5) {
                    o = -2000;
                    k = -2000;
                } else if (j == 45) {
                    o = 35;
                    k = 20;
                } else {
                    l = j - 9;
                    m = l % 9;
                    n = l / 9;
                    o = 9 + m * 18;
                    if (j >= 36) {
                        k = 112;
                    } else {
                        k = 54 + n * 18;
                    }
                }

                Slot slot = new CreativeSlot(screenHandler.slots.get(j), j, o, k);
                (this.handler).slots.add(slot);
            }

            this.deleteItemSlot = new Slot(INVENTORY, 0, 173, 112);
            (this.handler).slots.add(this.deleteItemSlot);
        } else if (i == ItemGroup.INVENTORY.getIndex()) {
            (this.handler).slots.clear();
            (this.handler).slots.addAll(this.slots);
            this.slots = null;
        }

        if (this.searchBox != null) {
            if (group == ItemGroup.SEARCH) {
                this.searchBox.setVisible(true);
                // this.searchBox.setFocusUnlocked(false);
                this.searchBox.setTextFieldFocused(true);
                if (i != group.getIndex()) {
                    this.searchBox.setText("");
                }

                this.search();
            } else {
                this.searchBox.setVisible(false);
                // this.searchBox.setFocusUnlocked(true);
                this.searchBox.setTextFieldFocused(false);
                this.searchBox.setText("");
            }
        }

        this.scrollPosition = 0.0F;
        (this.handler).scrollItems(0.0F);
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!this.hasScrollbar()) {
            return false;
        } else {
            int i = ((this.handler).itemList.size() + 9 - 1) / 9 - 5;
            float f = (float)(amount / (double)i);
            this.scrollPosition = MathHelper.clamp(this.scrollPosition - f, 0.0F, 1.0F);
            (this.handler).scrollItems(this.scrollPosition);
            return true;
        }
    }

    protected boolean isClickOutsideBounds(double mouseX, double mouseY, int left, int top, int button) {
        boolean bl = mouseX < (double)left || mouseY < (double)top || mouseX >= (double)(left + this.backgroundWidth) || mouseY >= (double)(top + this.backgroundHeight);
        this.lastClickOutsideBounds = bl && !this.isClickInTab(ItemGroup.GROUPS[selectedTab], mouseX, mouseY);
        return this.lastClickOutsideBounds;
    }

    protected boolean isClickInScrollbar(double mouseX, double mouseY) {
        int i = this.x;
        int j = this.y;
        int k = i + 175;
        int l = j + 18;
        int m = k + 14;
        int n = l + 112;
        return mouseX >= (double)k && mouseY >= (double)l && mouseX < (double)m && mouseY < (double)n;
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (this.scrolling) {
            int i = this.y + 18;
            int j = i + 112;
            this.scrollPosition = ((float)mouseY - (float)i - 7.5F) / ((float)(j - i) - 15.0F);
            this.scrollPosition = MathHelper.clamp(this.scrollPosition, 0.0F, 1.0F);
            this.handler.scrollItems(this.scrollPosition);
            return true;
        } else {
            return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
        }
    }

    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);
        super.render(matrices, mouseX, mouseY, delta);
        ItemGroup[] var5 = ItemGroup.GROUPS;

        for (ItemGroup itemGroup : var5) {
            if (this.renderTabTooltipIfHovered(matrices, itemGroup, mouseX, mouseY)) {
                break;
            }
        }

        if (this.deleteItemSlot != null && selectedTab == ItemGroup.INVENTORY.getIndex() && this.isPointWithinBounds(this.deleteItemSlot.x, this.deleteItemSlot.y, 16, 16, (double)mouseX, (double)mouseY)) {
            this.renderTooltip(matrices, DELETE_ITEM_SLOT_TEXT, mouseX, mouseY);
        }

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        this.drawMouseoverTooltip(matrices, mouseX, mouseY);
    }

    protected void renderTooltip(MatrixStack matrices, ItemStack stack, int x, int y) {
        if (selectedTab == ItemGroup.SEARCH.getIndex()) {
            List<Text> list = stack.getTooltip(this.client.player, this.client.options.advancedItemTooltips ? TooltipContext.Default.ADVANCED : TooltipContext.Default.NORMAL);
            List<Text> list2 = Lists.newArrayList((Iterable)list);
            Item item = stack.getItem();
            ItemGroup itemGroup = item.getGroup();
            if (itemGroup == null && stack.isOf(Items.ENCHANTED_BOOK)) {
                Map<Enchantment, Integer> map = EnchantmentHelper.get(stack);
                if (map.size() == 1) {
                    Enchantment enchantment = map.keySet().iterator().next();
                    ItemGroup[] var11 = ItemGroup.GROUPS;
                    int var12 = var11.length;

                    for (ItemGroup itemGroup2 : var11) {
                        if (itemGroup2.containsEnchantments(enchantment.type)) {
                            itemGroup = itemGroup2;
                            break;
                        }
                    }
                }
            }

            this.searchResultTags.forEach((tagKey) -> {
                if (stack.isIn(tagKey)) {
                    list2.add(1, Text.literal("#" + tagKey.id()).formatted(Formatting.DARK_PURPLE));
                }

            });
            if (itemGroup != null) {
                list2.add(1, itemGroup.getDisplayName().copy().formatted(Formatting.BLUE));
            }

            this.renderTooltip(matrices, list2, stack.getTooltipData(), x, y);
        } else {
            super.renderTooltip(matrices, stack, x, y);
        }

    }

    protected void drawBackground(MatrixStack matrices, float delta, int mouseX, int mouseY) {
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        ItemGroup itemGroup = ItemGroup.GROUPS[selectedTab];
        ItemGroup[] var6 = ItemGroup.GROUPS;
        int j = var6.length;

        int k;
        for(k = 0; k < j; ++k) {
            ItemGroup itemGroup2 = var6[k];
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.setShaderTexture(0, TEXTURE);
            if (itemGroup2.getIndex() != selectedTab) {
                this.renderTabIcon(matrices, itemGroup2);
            }
        }

        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, new Identifier(MODID,"textures/gui/container/creative_inventory/tab_" + itemGroup.getTexture()));
        this.drawTexture(matrices, this.x, this.y, 0, 0, this.backgroundWidth, this.backgroundHeight);
        this.searchBox.render(matrices, mouseX, mouseY, delta);
        this.pathBox.render(matrices, mouseX, mouseY, delta);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        int i = this.x + 175;
        j = this.y + 18;
        k = j + 112;
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, TEXTURE);
        if (itemGroup.hasScrollbar()) {
            this.drawTexture(matrices, i, j + (int)((float)(k - j - 17) * this.scrollPosition), 232 + (this.hasScrollbar() ? 0 : 12), 0, 12, 15);
        }

        this.renderTabIcon(matrices, itemGroup);
        if (itemGroup == ItemGroup.INVENTORY) {
            InventoryScreen.drawEntity(this.x + 88, this.y + 45, 20, (float)(this.x + 88 - mouseX), (float)(this.y + 45 - 30 - mouseY), this.client.player);
        }

    }

    protected boolean isClickInTab(ItemGroup group, double mouseX, double mouseY) {
        if (!fabric_isGroupVisible(group)) {
            return false;
        }
        int i = group.getColumn();
        int j = 28 * i;
        int k = 0;
        if (group.isSpecial()) {
            j = this.backgroundWidth - 28 * (6 - i) + 2;
        } else if (i > 0) {
            j += i;
        }

        if (group.isTopRow()) {
            k -= 32;
        } else {
            k += this.backgroundHeight;
        }

        return mouseX >= (double)j && mouseX <= (double)(j + 28) && mouseY >= (double)k && mouseY <= (double)(k + 32);
    }

    protected boolean renderTabTooltipIfHovered(MatrixStack matrices, ItemGroup group, int mouseX, int mouseY) {
        if (!fabric_isGroupVisible(group)) {
            return false;
        }
        int i = group.getColumn();
        int j = 28 * i;
        int k = 0;
        if (group.isSpecial()) {
            j = this.backgroundWidth - 28 * (6 - i) + 2;
        } else if (i > 0) {
            j += i;
        }

        if (group.isTopRow()) {
            k -= 32;
        } else {
            k += this.backgroundHeight;
        }

        if (this.isPointWithinBounds(j + 3, k + 3, 23, 27, (double)mouseX, (double)mouseY)) {
            this.renderTooltip(matrices, group.getDisplayName(), mouseX, mouseY);
            return true;
        } else {
            return false;
        }
    }

    protected void renderTabIcon(MatrixStack matrices, ItemGroup group) {
        if (!fabric_isGroupVisible(group)) {
            return;
        }
        boolean bl = group.getIndex() == selectedTab;
        boolean bl2 = group.isTopRow();
        int i = group.getColumn();
        int j = i * 28;
        int k = 0;
        int l = this.x + 28 * i;
        int m = this.y;
        boolean n = true;
        if (bl) {
            k += 32;
        }

        if (group.isSpecial()) {
            l = this.x + this.backgroundWidth - 28 * (6 - i);
        } else if (i > 0) {
            l += i;
        }

        if (bl2) {
            m -= 28;
        } else {
            k += 64;
            m += this.backgroundHeight - 4;
        }

        this.drawTexture(matrices, l, m, j, k, 28, 32);
        this.itemRenderer.zOffset = 100.0F;
        l += 6;
        m += 8 + (bl2 ? 1 : -1);
        ItemStack itemStack = group.getIcon();
        this.itemRenderer.renderInGuiWithOverrides(itemStack, l, m);
        this.itemRenderer.renderGuiItemOverlay(this.textRenderer, itemStack, l, m);
        this.itemRenderer.zOffset = 0.0F;
    }

    public int getSelectedTab() {
        return selectedTab;
    }

    public static void onHotbarKeyPress(MinecraftClient client, int index, boolean restore, boolean save) {
        ClientPlayerEntity clientPlayerEntity = client.player;
        HotbarStorage hotbarStorage = client.getCreativeHotbarStorage();
        HotbarStorageEntry hotbarStorageEntry = hotbarStorage.getSavedHotbar(index);
        int i;
        if (restore) {
            for(i = 0; i < PlayerInventory.getHotbarSize(); ++i) {
                ItemStack itemStack = ((ItemStack)hotbarStorageEntry.get(i)).copy();
                clientPlayerEntity.getInventory().setStack(i, itemStack);
                client.interactionManager.clickCreativeStack(itemStack, 36 + i);
            }

            clientPlayerEntity.playerScreenHandler.sendContentUpdates();
        } else if (save) {
            for(i = 0; i < PlayerInventory.getHotbarSize(); ++i) {
                hotbarStorageEntry.set(i, clientPlayerEntity.getInventory().getStack(i).copy());
            }

            Text text = client.options.hotbarKeys[index].getBoundKeyLocalizedText();
            Text text2 = client.options.loadToolbarActivatorKey.getBoundKeyLocalizedText();
            Text text3 = Text.translatable("inventory.hotbarSaved", text2, text);
            client.inGameHud.setOverlayMessage(text3, false);
            client.getNarratorManager().narrate((Text)text3);
            hotbarStorage.save();
        }

    }

    static {
        selectedTab = ItemGroup.BUILDING_BLOCKS.getIndex();
    }


    private static int fabric_currentPage = 0;

    private int fabric_getPageOffset(int page) {
        switch (page) {
            case 0:
                return 0;
            case 1:
                return 12;
            default:
                return 12 + ((12 - FabricCreativeGuiComponents.COMMON_GROUPS.size()) * (page - 1));
        }
    }

    private int fabric_getOffsetPage(int offset) {
        if (offset < 12) {
            return 0;
        } else {
            return 1 + ((offset - 12) / (12 - FabricCreativeGuiComponents.COMMON_GROUPS.size()));
        }
    }

    private boolean fabric_isGroupVisible(ItemGroup itemGroup) {
        if (FabricCreativeGuiComponents.COMMON_GROUPS.contains(itemGroup)) {
            return true;
        }

        return fabric_currentPage == fabric_getOffsetPage(itemGroup.getIndex());
    }

    @Override
    public int fabric_currentPage() {
        return fabric_currentPage;
    }

    @Override
    public void fabric_nextPage() {
        if (fabric_getPageOffset(fabric_currentPage + 1) >= ItemGroup.GROUPS.length) {
            return;
        }

        fabric_currentPage++;
        fabric_updateSelection();
    }

    public void fabric_previousPage() {
        if (fabric_currentPage == 0) {
            return;
        }

        fabric_currentPage--;
        fabric_updateSelection();
    }

    @Override
    public boolean fabric_isButtonVisible(CREGuiComponents.Type type) {
        return ItemGroup.GROUPS.length > 12;
    }

    @Override
    public boolean fabric_isButtonEnabled(CREGuiComponents.Type type) {
        if (type == CREGuiComponents.Type.NEXT) {
            return !(fabric_getPageOffset(fabric_currentPage + 1) >= ItemGroup.GROUPS.length);
        }

        if (type == CREGuiComponents.Type.PREVIOUS) {
            return fabric_currentPage != 0;
        }

        return false;
    }

    private void fabric_updateSelection() {
        int minPos = fabric_getPageOffset(fabric_currentPage);
        int maxPos = fabric_getPageOffset(fabric_currentPage + 1) - 1;
        int curPos = getSelectedTab();

        if (curPos < minPos || curPos > maxPos) {
            setSelectedTab(ItemGroup.GROUPS[fabric_getPageOffset(fabric_currentPage)]);
        }
    }

    @Environment(EnvType.CLIENT)
    public static class CraftingRecipeEditScreenHandler extends ScreenHandler {
        public final DefaultedList<ItemStack> itemList = DefaultedList.of();
        private final ScreenHandler parent;
        private final SimpleInventory RECIPE = new SimpleInventory(9);
        private final SimpleInventory OUTPUT = new SimpleInventory(1);

        public CraftingRecipeEditScreenHandler(PlayerEntity player) {
            super(null, 0);
            this.parent = player.playerScreenHandler;
            PlayerInventory playerInventory = player.getInventory();

            int i;
            for(i = 0; i < 5; ++i) {
                for(int j = 0; j < 9; ++j) {
                    this.addSlot(new LockableSlot(
                            INVENTORY, i * 9 + j, 9 + j * 18, 18 + i * 18));
                }
            }

            for(i = 0; i < 9; ++i) {
                this.addSlot(new Slot(playerInventory, i, 9 + i * 18, 112));
            }
            for(int x = 0; x < 3 ;x++) {
                for (int y = 0; y < 3; y++) {
                    addSlot(new Slot(RECIPE, 3 * y + x, 45 + x * 18, 131 + y * 18));
                }
            }
            addSlot(new Slot(OUTPUT,0,139,149));

            this.scrollItems(0.0F);
        }

        public boolean canUse(PlayerEntity player) {
            return true;
        }

        public void scrollItems(float position) {
            int i = (this.itemList.size() + 9 - 1) / 9 - 5;
            int j = (int)((double)(position * (float)i) + 0.5);
            if (j < 0) {
                j = 0;
            }

            for(int k = 0; k < 5; ++k) {
                for(int l = 0; l < 9; ++l) {
                    int m = l + (k + j) * 9;
                    if (m >= 0 && m < this.itemList.size()) {
                        INVENTORY.setStack(l + k * 9, this.itemList.get(m));
                    } else {
                        INVENTORY.setStack(l + k * 9, ItemStack.EMPTY);
                    }
                }
            }

        }

        public boolean shouldShowScrollbar() {
            return this.itemList.size() > 45;
        }

        public ItemStack transferSlot(PlayerEntity player, int index) {
            if (index >= this.slots.size() - 9 && index < this.slots.size()) {
                Slot slot = this.slots.get(index);
                if (slot.hasStack()) {
                    slot.setStack(ItemStack.EMPTY);
                }
            }

            return ItemStack.EMPTY;
        }

        public boolean canInsertIntoSlot(ItemStack stack, Slot slot) {
            return slot.inventory != INVENTORY;
        }

        public boolean canInsertIntoSlot(Slot slot) {
            return slot.inventory != INVENTORY;
        }

        public ItemStack getCursorStack() {
            return this.parent.getCursorStack();
        }

        public void setCursorStack(ItemStack stack) {
            this.parent.setCursorStack(stack);
        }
    }

    @Environment(EnvType.CLIENT)
    private static class CreativeSlot extends Slot {
        final Slot slot;

        public CreativeSlot(Slot slot, int invSlot, int x, int y) {
            super(slot.inventory, invSlot, x, y);
            this.slot = slot;
        }

        public void onTakeItem(PlayerEntity player, ItemStack stack) {
            this.slot.onTakeItem(player, stack);
        }

        public boolean canInsert(ItemStack stack) {
            return this.slot.canInsert(stack);
        }

        public ItemStack getStack() {
            return this.slot.getStack();
        }

        public boolean hasStack() {
            return this.slot.hasStack();
        }

        public void setStack(ItemStack stack) {
            this.slot.setStack(stack);
        }

        public void markDirty() {
            this.slot.markDirty();
        }

        public int getMaxItemCount() {
            return this.slot.getMaxItemCount();
        }

        public int getMaxItemCount(ItemStack stack) {
            return this.slot.getMaxItemCount(stack);
        }

        @Nullable
        public Pair<Identifier, Identifier> getBackgroundSprite() {
            return this.slot.getBackgroundSprite();
        }

        public ItemStack takeStack(int amount) {
            return this.slot.takeStack(amount);
        }

        public boolean isEnabled() {
            return this.slot.isEnabled();
        }

        public boolean canTakeItems(PlayerEntity playerEntity) {
            return this.slot.canTakeItems(playerEntity);
        }
    }

    @Environment(EnvType.CLIENT)
    private static class LockableSlot extends Slot {
        public LockableSlot(Inventory inventory, int i, int j, int k) {
            super(inventory, i, j, k);
        }

        public boolean canTakeItems(PlayerEntity playerEntity) {
            if (super.canTakeItems(playerEntity) && this.hasStack()) {
                return this.getStack().getSubNbt("CustomCreativeLock") == null;
            } else {
                return !this.hasStack();
            }
        }
    }
}
