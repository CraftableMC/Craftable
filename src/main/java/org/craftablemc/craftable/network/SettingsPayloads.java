package org.craftablemc.craftable.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.config.CraftableServerConfig;
import org.craftablemc.craftable.config.DirectPreparationPolicy;
import org.craftablemc.craftable.config.EnvironmentScanSettings;
import org.craftablemc.craftable.planner.CraftRequest;

/**
 * 只读配置发现，不携带背包、浏览租约、执行令牌或远程可写设置。
 * Read-only configuration discovery carrying no inventory, browsing lease, execution token or remotely writable setting.
 * revision仅关联客户端当前页面；所有值经共享配置值对象验证，展示规则不能授权资源访问。
 * Revision only correlates the client's current page; shared config value objects validate values, and displayed rules grant no resource access.
 */
public final class SettingsPayloads {
    private SettingsPayloads() {}

    /** 单调请求序号而非世界/资源版本。 / A request correlation sequence, not a world/resource version. */
    public record RulesRequest(long revision) implements CustomPacketPayload {
        public RulesRequest { requireRevision(revision); }
        public static final Type<RulesRequest> TYPE = new Type<>(Craftable.id("settings_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RulesRequest> CODEC = CustomPacketPayload.codec(
                (p, b) -> b.writeVarLong(p.revision), b -> new RulesRequest(b.readVarLong()));
        @Override public Type<RulesRequest> type() { return TYPE; }
    }

    /** 不可变规则副本；接收页面须另查连接/世界身份与待处理序号。 / Immutable rule copy; the receiving view separately checks connection/world identity and pending revision. */
    public record RulesResponse(long revision, EnvironmentScanSettings scan, CraftableServerConfig.CraftingRules crafting)
            implements CustomPacketPayload {
        public RulesResponse {
            requireRevision(revision);
            java.util.Objects.requireNonNull(scan);
            java.util.Objects.requireNonNull(crafting);
        }
        public static final Type<RulesResponse> TYPE = new Type<>(Craftable.id("settings_response"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RulesResponse> CODEC = CustomPacketPayload.codec(
                (p, b) -> { b.writeVarLong(p.revision); writeScan(b, p.scan); writeCrafting(b, p.crafting); },
                b -> new RulesResponse(b.readVarLong(), readScan(b), readCrafting(b)));
        @Override public Type<RulesResponse> type() { return TYPE; }
    }

    static void writeScan(RegistryFriendlyByteBuf buffer, EnvironmentScanSettings scan) {
        buffer.writeVarInt(scan.horizontalRadius()); buffer.writeVarInt(scan.verticalRadius());
        buffer.writeVarInt(scan.diagnosticCacheTicks()); buffer.writeBoolean(scan.includeEnderChest());
    }

    static EnvironmentScanSettings readScan(RegistryFriendlyByteBuf buffer) {
        return new EnvironmentScanSettings(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean());
    }

    static void writeCrafting(RegistryFriendlyByteBuf buffer, CraftableServerConfig.CraftingRules rules) {
        buffer.writeEnum(rules.surplusDelivery());
        buffer.writeVarInt(rules.maxBatches()); buffer.writeVarInt(rules.maxPlanSteps());
        writeDirectPreparation(buffer, rules.directPreparation());
        buffer.writeBoolean(rules.timeCompression()); buffer.writeVarInt(rules.maxFurnaceBatches());
    }

    static CraftableServerConfig.CraftingRules readCrafting(RegistryFriendlyByteBuf buffer) {
        var delivery = buffer.readEnum(CraftableServerConfig.SurplusDelivery.class);
        int batches = CraftingWire.count(buffer, CraftRequest.MAX_BATCHES);
        if (batches == 0) throw new IllegalArgumentException("Zero batch limit");
        int steps = org.craftablemc.craftable.planner.SearchBudget.requireStepLimit(buffer.readVarInt());
        var preparation = readDirectPreparation(buffer);
        boolean compression = buffer.readBoolean();
        int furnaceBatches = CraftingWire.count(buffer, CraftableServerConfig.MAX_MAX_FURNACE_BATCHES);
        return new CraftableServerConfig.CraftingRules(delivery, batches, steps, preparation, compression, furnaceBatches);
    }

    /** 有界确认偏好，不增加配方知识或执行权限。 / Bounded consent values, never new recipe knowledge or execution authority. */
    static void writeDirectPreparation(RegistryFriendlyByteBuf buffer, DirectPreparationPolicy policy) {
        buffer.writeEnum(policy.mode());
        writeRecipeIds(buffer, policy.allowedRecipes());
    }

    static DirectPreparationPolicy readDirectPreparation(RegistryFriendlyByteBuf buffer) {
        var mode = buffer.readEnum(DirectPreparationPolicy.Mode.class);
        return DirectPreparationPolicy.of(mode, readRecipeIds(buffer));
    }

    private static void writeRecipeIds(RegistryFriendlyByteBuf buffer, java.util.List<String> recipes) {
        buffer.writeVarInt(recipes.size());
        for (var recipe : recipes) buffer.writeUtf(recipe, DirectPreparationPolicy.MAX_RECIPE_ID_LENGTH);
    }

    /** 在分配名单前限量，逐ID限长并校验完整命名空间；规范化由规则对象统一负责。 / Bound size before allocation and validate bounded full IDs; policy construction owns normalization. */
    private static java.util.List<String> readRecipeIds(RegistryFriendlyByteBuf buffer) {
        int count = CraftingWire.count(buffer, DirectPreparationPolicy.MAX_RECIPES);
        var recipes = new java.util.ArrayList<String>(count);
        for (int i = 0; i < count; i++) {
            String recipe = buffer.readUtf(DirectPreparationPolicy.MAX_RECIPE_ID_LENGTH);
            if (!DirectPreparationPolicy.validRecipeId(recipe)) throw new IllegalArgumentException("Invalid preparation recipe ID");
            recipes.add(recipe);
        }
        return recipes;
    }

    private static void requireRevision(long revision) {
        if (revision < 0) throw new IllegalArgumentException("Negative settings revision");
    }
}
