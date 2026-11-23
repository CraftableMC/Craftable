package org.craftablemc.craftable.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.SmithingTrimRecipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.api.CraftingResultCode;
import org.craftablemc.craftable.client.mixin.RecipeBookComponentAccessor;
import org.craftablemc.craftable.client.mixin.RecipeBookPageAccessor;
import org.craftablemc.craftable.client.recipebook.ClientBrowsePlanner;
import org.craftablemc.craftable.client.recipebook.ClientRecipeStatusStore;
import org.craftablemc.craftable.client.recipebook.RecipeBookInputHandler;
import org.craftablemc.craftable.client.recipebook.RecipeBookProjection;
import org.craftablemc.craftable.client.recipebook.RecipeButtonTargetResolver;
import org.craftablemc.craftable.config.ConfigSettings;
import org.craftablemc.craftable.config.CraftableServerConfig;
import org.craftablemc.craftable.workstation.WorkstationCapability;

/** 检查实际客户端的工作站搜索和能力同步，并通过 C 请求原版药水处理。
 * Exercise live station search and capability sync, then brew a vanilla potion through the usual C binding. */
final class ProcessingStationScenario {
    private enum Phase { SETUP, OPEN_BOOK, STONECUTTING, SMITHING, BREWING, DELIVERY }
    private static final BlockPos CUTTER = new BlockPos(1, -60, 0);
    private static final BlockPos SMITH = new BlockPos(2, -60, 0);
    private static final BlockPos BREW = new BlockPos(0, -60, 1);
    private static final int STATIONS = WorkstationCapability.STONECUTTER.bit()
            | WorkstationCapability.SMITHING_TABLE.bit() | WorkstationCapability.BREWING_STAND.bit();
    private static final ResourceLocation SLAB = ResourceLocation.withDefaultNamespace("cobblestone_slab_from_cobblestone_stonecutting");
    private static final ResourceLocation SWORD = ResourceLocation.withDefaultNamespace("netherite_sword_smithing");
    private static final ResourceLocation AWKWARD = ResourceLocation.fromNamespaceAndPath(Craftable.MOD_ID, "brewing/potion/water/nether_wart");

    private Phase phase = Phase.SETUP;
    private ModConfigSpec.ConfigValue<Boolean> compression;
    private Boolean originalCompression;

    @SuppressWarnings("unchecked")
    boolean tick(Minecraft mc) throws ReflectiveOperationException {
        switch (phase) {
            case SETUP -> {
                mc.player.closeContainer();
                compression = (ModConfigSpec.ConfigValue<Boolean>) ConfigSettings.values(CraftableServerConfig.SPEC).stream()
                        .filter(value -> value.getPath().getLast().equals("timeCompression")).findFirst().orElseThrow();
                var id = mc.player.getUUID();
                mc.getSingleplayerServer().submit(() -> {
                    originalCompression = compression.getRaw();
                    compression.set(true);
                    var player = mc.getSingleplayerServer().getPlayerList().getPlayer(id);
                    var level = player.serverLevel();
                    level.setBlockAndUpdate(CUTTER, Blocks.STONECUTTER.defaultBlockState());
                    level.setBlockAndUpdate(SMITH, Blocks.SMITHING_TABLE.defaultBlockState());
                    level.setBlockAndUpdate(BREW, Blocks.BREWING_STAND.defaultBlockState());
                    player.getInventory().clearContent();
                    player.getInventory().setItem(0, PotionContents.createItemStack(Items.POTION, Potions.WATER));
                    player.getInventory().setItem(1, new ItemStack(Items.NETHER_WART));
                    player.getInventory().setItem(2, new ItemStack(Items.BLAZE_POWDER));
                    player.containerMenu.broadcastChanges();
                }).join();
                phase = Phase.OPEN_BOOK;
            }
            case OPEN_BOOK -> {
                if (mc.player.getInventory().countItem(Items.NETHER_WART) != 1) return false;
                mc.setScreen(new InventoryScreen(mc.player));
                var book = RecipeBookProjection.component(mc.screen);
                if (!book.isVisible()) book.toggleVisibility();
                mc.player.getRecipeBook().setFiltering(RecipeBookType.CRAFTING, false);
                search(mc, Items.COBBLESTONE_SLAB.getDescription().getString());
                phase = Phase.STONECUTTING;
            }
            case STONECUTTING -> {
                if (!ClientBrowsePlanner.ready()) return false;
                require((ClientBrowsePlanner.availableWorkstations() & STATIONS) == STATIONS,
                        "Nearby processing stations missing from the client snapshot: " + ClientBrowsePlanner.availableWorkstations());
                require(button(mc, SLAB) != null, "Stonecutting route missing from slab search");
                search(mc, Items.NETHERITE_SWORD.getDescription().getString());
                phase = Phase.SMITHING;
            }
            case SMITHING -> {
                require(button(mc, SWORD) != null, "Smithing upgrade missing from sword search");
                search(mc, "");
                require(RecipeBookProjection.scope(RecipeBookProjection.component(mc.screen)).stream().noneMatch(collection ->
                        collection.getRecipes().stream().anyMatch(holder -> holder.value() instanceof SmithingTrimRecipe)),
                        "Unmodeled armor trim entered the processing catalog");
                search(mc, PotionContents.createItemStack(Items.POTION, Potions.AWKWARD).getHoverName().getString());
                phase = Phase.BREWING;
            }
            case BREWING -> {
                var button = button(mc, AWKWARD);
                require(button != null, "Awkward Potion name did not find its brewing route");
                if (ClientRecipeStatusStore.reason(AWKWARD) != CraftingResultCode.CREATED) return false;
                require(RecipeButtonTargetResolver.preferredRecipe(button).id().equals(AWKWARD),
                        "Recipe book selected the wrong route for the displayed potion");
                pressCreate(mc, button);
                phase = Phase.DELIVERY;
            }
            case DELIVERY -> {
                var expected = PotionContents.createItemStack(Items.POTION, Potions.AWKWARD);
                if (mc.player.getInventory().items.stream().noneMatch(stack -> ItemStack.matches(stack, expected))) return false;
                require(mc.player.getInventory().countItem(Items.POTION) == 1
                                && mc.player.getInventory().countItem(Items.NETHER_WART) == 0
                                && mc.player.getInventory().countItem(Items.BLAZE_POWDER) == 0,
                        "Brewing did not consume exactly the bottle, reagent and fuel for the delivered potion");
                require(mc.getSingleplayerServer().submit(() -> mc.getSingleplayerServer().overworld()
                        .getBlockEntity(BREW) instanceof BrewingStandBlockEntity stand && stand.isEmpty()).join(),
                        "Virtual brewing occupied the native stand slots");
                close(mc);
                Craftable.LOGGER.warn("PROCESSING_STATIONS PASS: synced stonecutting/smithing/brewing, localized search, no trims, real brewing C delivered exact potion");
                return true;
            }
        }
        return false;
    }

    void close(Minecraft mc) {
        if (originalCompression == null) return;
        mc.getSingleplayerServer().submit(() -> compression.set(originalCompression)).join();
        originalCompression = null;
    }

    private static void search(Minecraft mc, String text) {
        var book = RecipeBookProjection.component(mc.screen);
        ((RecipeBookComponentAccessor) book).craftable$getSearchBox().setValue(text);
        book.recipesUpdated();
    }

    private static RecipeButton button(Minecraft mc, ResourceLocation recipe) {
        var book = RecipeBookProjection.component(mc.screen);
        var page = ((RecipeBookComponentAccessor) book).craftable$getRecipeBookPage();
        return ((RecipeBookPageAccessor) page).craftable$getButtons().stream().filter(button -> button.visible && button.getCollection()
                .getRecipes().stream().anyMatch(holder -> holder.id().equals(recipe))).findFirst().orElse(null);
    }

    private static void pressCreate(Minecraft mc, RecipeButton button) throws ReflectiveOperationException {
        var book = (RecipeBookComponentAccessor) RecipeBookProjection.component(mc.screen);
        book.craftable$getSearchBox().setFocused(false);
        // 只设置原版悬停观察点，制作目标仍由正常按键处理器和服务端决定。
        // Set only the vanilla hover observation. The normal key handler and server still choose and validate the request.
        var hover = RecipeBookPage.class.getDeclaredField("hoveredButton");
        hover.setAccessible(true);
        hover.set(book.craftable$getRecipeBookPage(), button);
        int key = CraftableKeyMappings.CREATE_ONE.getKey().getValue();
        var press = new ScreenEvent.KeyPressed.Pre(mc.screen, key, 0, 0);
        RecipeBookInputHandler.onKeyPressed(press);
        RecipeBookInputHandler.onKeyReleased(new ScreenEvent.KeyReleased.Pre(mc.screen, key, 0, 0));
        require(press.isCanceled(), "C was not routed through the brewing recipe button");
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
