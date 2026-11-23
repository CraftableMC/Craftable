package org.craftablemc.craftable.recipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.crafting.DataComponentIngredient;
import org.craftablemc.craftable.Craftable;

/**
 * 将原版酿造表投影为 Craftable 的单瓶处理步骤。配方 ID 只用于本模组的计划和审阅，不加入原版配方管理器。
 * Project the vanilla brewing table as single-bottle steps. Their IDs belong to Craftable plans and reviews, not the vanilla recipe manager.
 */
public final class VanillaBrewingRecipe implements Recipe<RecipeInput> {
    public static final int TICKS_PER_BATCH = PotionBrewing.BREWING_TIME_SECONDS * 20;
    public static final int FUEL_BATCHES = 20;
    private static final RecipeType<VanillaBrewingRecipe> TYPE = new RecipeType<>() {};

    private final ItemStack input, output, remainder;
    private final Ingredient potion, reagent;

    private VanillaBrewingRecipe(ItemStack input, Item reagent, ItemStack output) {
        this.input = input;
        this.output = output;
        this.potion = DataComponentIngredient.of(true, DataComponents.POTION_CONTENTS,
                input.get(DataComponents.POTION_CONTENTS), input.getItem());
        this.reagent = Ingredient.of(reagent);
        this.remainder = new ItemStack(reagent).getCraftingRemainingItem().copy();
    }

    public static List<RecipeHolder<?>> recipes() { return Catalog.DATA.recipes(); }

    public static @Nullable RecipeHolder<?> byId(ResourceLocation id) { return Catalog.DATA.byId().get(id); }

    public List<CraftingRecipes.Requirement> requirements() {
        return List.of(new CraftingRecipes.Requirement(0, potion), new CraftingRecipes.Requirement(1, reagent));
    }

    public ItemStack reagentRemainder() { return remainder.copy(); }

    /** 只允许没有改名、额外颜色或自定义效果的原版药水；广义物品谓词不能用这个例外扣料。
     * Only plain vanilla potion stacks qualify. An item-only predicate cannot use this exception to debit a potion. */
    public static boolean isPlainVanillaPotion(ItemStack stack) {
        if (!(stack.is(Items.POTION) || stack.is(Items.SPLASH_POTION) || stack.is(Items.LINGERING_POTION))) return false;
        var patch = stack.getComponentsPatch();
        var contents = stack.get(DataComponents.POTION_CONTENTS);
        return patch.size() == 1 && patch.get(DataComponents.POTION_CONTENTS) != null && contents != null
                && contents.customColor().isEmpty() && contents.customEffects().isEmpty()
                && contents.potion().flatMap(Holder::unwrapKey).filter(Catalog.DATA.potions()::contains).isPresent();
    }

    public static boolean acceptsPotion(Ingredient ingredient, ItemStack stack) {
        return ingredient.isCustom() && ingredient.getCustomIngredient() instanceof DataComponentIngredient exact
                && exact.isStrict() && isPlainVanillaPotion(stack) && exact.test(stack);
    }

    /** 注册的自定义酿造有优先权且可能执行任意模组逻辑；本虚拟路径在其存在时不尝试执行。
     * Custom registry recipes take precedence and may execute arbitrary mod logic. Decline this virtual path when they are installed. */
    public ItemStack liveOutput(List<ItemStack> inputs, ServerPlayer player) {
        if (inputs.size() < 2) return ItemStack.EMPTY;
        return verifyLive(inputs.getFirst(), inputs.get(1), player.serverLevel());
    }

    private ItemStack verifyLive(ItemStack bottle, ItemStack ingredient, Level level) {
        if (!matchesInputs(bottle, ingredient) || !ItemStack.matches(ingredient.getCraftingRemainingItem(), remainder))
            return ItemStack.EMPTY;
        var brewing = level.potionBrewing();
        if (!brewing.getRecipes().isEmpty() || !brewing.hasMix(bottle, ingredient)) return ItemStack.EMPTY;
        var actual = brewing.mix(ingredient.copy(), bottle.copy());
        return ItemStack.matches(actual, output) ? actual : ItemStack.EMPTY;
    }

    private boolean matchesInputs(ItemStack bottle, ItemStack ingredient) {
        return bottle.getCount() == 1 && ingredient.getCount() == 1
                && ItemStack.isSameItemSameComponents(bottle, input)
                && !CraftingRecipes.protectedStack(ingredient) && reagent.test(ingredient);
    }

    @Override public boolean matches(RecipeInput slots, Level level) {
        return slots.size() == 2 && !verifyLive(slots.getItem(0), slots.getItem(1), level).isEmpty();
    }

    @Override public ItemStack assemble(RecipeInput slots, HolderLookup.Provider registries) {
        return slots.size() == 2 && matchesInputs(slots.getItem(0), slots.getItem(1)) ? output.copy() : ItemStack.EMPTY;
    }

    @Override public boolean canCraftInDimensions(int width, int height) { return false; }
    @Override public ItemStack getResultItem(HolderLookup.Provider registries) { return output.copy(); }
    @Override public NonNullList<Ingredient> getIngredients() { return NonNullList.of(Ingredient.EMPTY, potion, reagent); }
    @Override public boolean showNotification() { return false; }
    @Override public RecipeType<?> getType() { return TYPE; }
    @Override public RecipeSerializer<?> getSerializer() { throw new UnsupportedOperationException("Virtual brewing is never serialized as a vanilla recipe"); }

    private static final class Catalog {
        private static final CatalogData DATA = build();

        private static CatalogData build() {
            var builder = new RecordingBuilder();
            // 使用原版定义，不触发 NeoForge 的酿造注册事件，也不复制原版配方表。
            // Reuse vanilla definitions without posting NeoForge registration or copying the vanilla recipe table.
            PotionBrewing.addVanillaMixes(builder);
            var potions = builder.vanillaPotions();
            var keys = new LinkedHashSet<ResourceKey<Potion>>();
            for (var potion : potions) keys.add(potion.unwrapKey().orElseThrow());
            var recipes = builder.recipes(potions);
            var byId = new HashMap<ResourceLocation, RecipeHolder<?>>();
            for (var recipe : recipes) {
                if (byId.putIfAbsent(recipe.id(), recipe) != null)
                    throw new IllegalStateException("Duplicate vanilla brewing route: " + recipe.id());
            }
            return new CatalogData(recipes, Map.copyOf(byId), Set.copyOf(keys));
        }
    }

    private record CatalogData(List<RecipeHolder<?>> recipes, Map<ResourceLocation, RecipeHolder<?>> byId,
            Set<ResourceKey<Potion>> potions) {}
    private record PotionMix(Holder<Potion> source, Item reagent, Holder<Potion> result) {}
    private record ContainerMix(Item source, Item reagent, Item result) {}

    private static final class RecordingBuilder extends PotionBrewing.Builder {
        private final List<Item> containers = new ArrayList<>();
        private final List<PotionMix> potions = new ArrayList<>();
        private final List<ContainerMix> bottles = new ArrayList<>();

        private RecordingBuilder() { super(FeatureFlags.DEFAULT_FLAGS); }

        @Override public void addContainer(Item item) { containers.add(item); }
        @Override public void addContainerRecipe(Item input, Item reagent, Item result) { bottles.add(new ContainerMix(input, reagent, result)); }
        @Override public void addMix(Holder<Potion> input, Item reagent, Holder<Potion> result) { potions.add(new PotionMix(input, reagent, result)); }

        List<Holder<Potion>> vanillaPotions() {
            var vanilla = new LinkedHashSet<Holder<Potion>>();
            for (var mix : potions) {
                vanilla.add(mix.source());
                vanilla.add(mix.result());
            }
            // 幸运药水没有生存获得路线，但已取得的原版药水可按原版更换容器。
            // Luck has no survival source, but an existing vanilla potion can still change containers.
            vanilla.add(Potions.LUCK);
            return List.copyOf(vanilla);
        }

        List<RecipeHolder<?>> recipes(List<Holder<Potion>> vanillaPotions) {
            var recipes = new ArrayList<RecipeHolder<?>>();
            for (var mix : potions) for (var container : containers) {
                recipes.add(route(container, mix.source(), mix.reagent(), container, mix.result()));
            }
            for (var mix : bottles) for (var potion : vanillaPotions) {
                recipes.add(route(mix.source(), potion, mix.reagent(), mix.result(), potion));
            }
            return List.copyOf(recipes);
        }

        private static RecipeHolder<?> route(Item fromContainer, Holder<Potion> fromPotion, Item reagent,
                Item toContainer, Holder<Potion> toPotion) {
            String path = "brewing/" + BuiltInRegistries.ITEM.getKey(fromContainer).getPath()
                    + "/" + fromPotion.unwrapKey().orElseThrow().location().getPath()
                    + "/" + BuiltInRegistries.ITEM.getKey(reagent).getPath();
            var input = PotionContents.createItemStack(fromContainer, fromPotion);
            var output = PotionContents.createItemStack(toContainer, toPotion);
            return new RecipeHolder<>(ResourceLocation.fromNamespaceAndPath(Craftable.MOD_ID, path),
                    new VanillaBrewingRecipe(input, reagent, output));
        }
    }
}
