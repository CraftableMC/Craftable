package org.craftablemc.craftable.network;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.api.CraftingResultCode;
import org.craftablemc.craftable.execution.CraftingService;
import org.craftablemc.craftable.planner.CraftRequest;
import org.craftablemc.craftable.planner.PlanView;

/**
 * 显式详情消息与浏览传输信封；被动配方书包不携带规划图，MAX也不因此触发服务端搜索。
 * Explicit detail messages and browsing envelopes; passive recipe-book packets carry no plan graph and do not trigger server MAX searches.
 * 编解码只校验数据形状/大小，菜单归属、规则、新鲜度、单次令牌与提交由主线程服务层核对。
 * Codecs validate shape/size only; main-thread services check menu ownership, rules, freshness, one-use tokens and commit.
 */
public final class CraftingDetailPayloads {
    private CraftingDetailPayloads() {}
    public static final UUID NO_TOKEN = new UUID(0, 0);

    /**
     * 单个视图订阅替代逐配方被动查询；客户端确认的session/resources只是传输信息，不是资源授权。
     * One view subscription replaces per-recipe passive queries; acknowledged session/resources are transport state, never resource authority.
     * 非空 session 配合 resources=-1 请求补传该会话内容；同一内容仅准许一次，不重置接收总期限。
     * A nonempty session with resources=-1 requests that session's missing bytes once, without resetting the receive deadline.
     */
    public record BrowseRequest(int menuId, long revision, boolean open, UUID session, long resources)
            implements CustomPacketPayload {
        public BrowseRequest { identity(menuId, revision); if (resources < -1) throw new IllegalArgumentException("resource version"); }
        public static final Type<BrowseRequest> TYPE = new Type<>(Craftable.id("browse"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BrowseRequest> CODEC = CustomPacketPayload.codec(
                (p,b) -> { b.writeVarInt(p.menuId); b.writeVarLong(p.revision); b.writeBoolean(p.open); b.writeUUID(p.session); b.writeVarLong(p.resources); },
                b -> new BrowseRequest(b.readVarInt(), b.readVarLong(), b.readBoolean(), b.readUUID(), b.readVarLong()));
        @Override public Type<BrowseRequest> type() { return TYPE; }
    }

    /**
     * 服务端有界浏览视图的有效期与规则副本；header用于内容变化或一次补传，不能代替执行前刷新。
     * Expiry/rule copy for a bounded server browsing view; headers carry changed content or one re-sync, never replace pre-execution refresh.
     */
    public record BrowseLease(int menuId, long revision, UUID session, long recipes, long resources,
            long expires, String fingerprint, CraftingWire.SnapshotHeader header,
            org.craftablemc.craftable.config.EnvironmentScanSettings settings, boolean workbench,
            org.craftablemc.craftable.config.CraftableServerConfig.CraftingRules rules, int furnaceMask) implements CustomPacketPayload {
        public BrowseLease {
            identity(menuId, revision);
            java.util.Objects.requireNonNull(settings);
            java.util.Objects.requireNonNull(rules);
            if ((furnaceMask & ~org.craftablemc.craftable.workstation.WorkstationCapability.PROCESSING_MASK) != 0)
                throw new IllegalArgumentException("Invalid browse furnace capabilities");
            if (recipes < 0 || resources < 0 || fingerprint.length() != 64
                    || header != null && (!header.session().equals(session) || header.recipes() != recipes
                    || header.resources() != resources)) throw new IllegalArgumentException("Invalid browse grant");
        }
        public BrowseLease(int menuId, long revision, UUID session, long recipes, long resources,
                long expires, String fingerprint, CraftingWire.SnapshotHeader header,
                org.craftablemc.craftable.config.EnvironmentScanSettings settings, boolean workbench,
                org.craftablemc.craftable.config.CraftableServerConfig.CraftingRules rules) {
            this(menuId, revision, session, recipes, resources, expires, fingerprint, header, settings, workbench, rules, 0);
        }
        public static final Type<BrowseLease> TYPE = new Type<>(Craftable.id("browse_lease"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BrowseLease> CODEC = CustomPacketPayload.codec(
                (p,b) -> {
                    b.writeVarInt(p.menuId); b.writeVarLong(p.revision); b.writeUUID(p.session);
                    b.writeVarLong(p.recipes); b.writeVarLong(p.resources); b.writeLong(p.expires); b.writeUtf(p.fingerprint,64);
                    SettingsPayloads.writeScan(b, p.settings); b.writeBoolean(p.workbench); b.writeVarInt(p.furnaceMask);
                    SettingsPayloads.writeCrafting(b, p.rules);
                    b.writeBoolean(p.header != null);
                    if (p.header != null) { b.writeUUID(p.header.transfer()); b.writeVarInt(p.header.bytes()); b.writeVarInt(p.header.chunks()); b.writeBytes(p.header.digest()); }
                }, b -> {
                    int menu = b.readVarInt(); long revision = b.readVarLong(); UUID session = b.readUUID();
                    long recipes = b.readVarLong(), resources = b.readVarLong(), expires = b.readLong(); String fingerprint = b.readUtf(64);
                    var settings = SettingsPayloads.readScan(b);
                    boolean workbench = b.readBoolean();
                    int furnaceMask = b.readVarInt();
                    var rules = SettingsPayloads.readCrafting(b);
                    CraftingWire.SnapshotHeader header = null;
                    if (b.readBoolean()) { UUID transfer = b.readUUID(); int bytes = b.readVarInt(), chunks = b.readVarInt(); byte[] digest = new byte[32]; b.readBytes(digest);
                        header = new CraftingWire.SnapshotHeader(session, transfer, recipes, resources, bytes, chunks, digest); }
                    return new BrowseLease(menu, revision, session, recipes, resources, expires, fingerprint, header, settings, workbench, rules, furnaceMask);
                });
        @Override public Type<BrowseLease> type() { return TYPE; }
    }

    /** 防御性复制分块；接收者仍须检查传输身份、顺序、总长、摘要与独立租约。 / Defensively copy chunks; the receiver still checks identity, order, length, digest and independent lease. */
    public record BrowseChunk(int menuId, long revision, UUID session, UUID transfer, int index, byte[] data)
            implements CustomPacketPayload {
        public BrowseChunk {
            identity(menuId, revision);
            if (index < 0 || index >= CraftingWire.SNAPSHOT_CHUNKS || data.length > CraftingWire.SNAPSHOT_CHUNK_BYTES)
                throw new IllegalArgumentException("Invalid browse chunk");
            data = data.clone();
        }
        @Override public byte[] data() { return data.clone(); }
        public static final Type<BrowseChunk> TYPE = new Type<>(Craftable.id("browse_chunk"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BrowseChunk> CODEC = CustomPacketPayload.codec(
                (p,b) -> { b.writeVarInt(p.menuId); b.writeVarLong(p.revision); b.writeUUID(p.session); b.writeUUID(p.transfer); b.writeVarInt(p.index); b.writeByteArray(p.data); },
                b -> new BrowseChunk(b.readVarInt(), b.readVarLong(), b.readUUID(), b.readUUID(), b.readVarInt(), b.readByteArray(CraftingWire.SNAPSHOT_CHUNK_BYTES)));
        @Override public Type<BrowseChunk> type() { return TYPE; }
    }

    /** 请求审阅具体意图及可选见证，不是请求直接执行；choicePath采用有界树路径。 / Request review of intent/optional witness, not direct execution; choicePath is a bounded tree path. */
    public record PreviewRequest(int menuId, long revision, CraftRequest request, String choicePath,
            org.craftablemc.craftable.planner.CraftPlan.Witness witness) implements CustomPacketPayload {
        public PreviewRequest {
            identity(menuId, revision);
            if (!choicePath.isEmpty() && !choicePath.matches("0(?:\\.[0-8]){0,12}"))
                throw new IllegalArgumentException("Invalid choice path");
        }
        public static final Type<PreviewRequest> TYPE = new Type<>(Craftable.id("plan_preview"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PreviewRequest> CODEC = CustomPacketPayload.codec(
                (p, outer) -> CraftingWire.writeFrame(outer, b -> { b.writeVarInt(p.menuId); b.writeVarLong(p.revision); CraftingWire.request(b, p.request); b.writeUtf(p.choicePath, 32); CraftingWire.witness(b, p.witness); }),
                outer -> CraftingWire.readFrame(outer, b -> new PreviewRequest(b.readVarInt(), b.readVarLong(), CraftingWire.request(b), b.readUtf(32), CraftingWire.witness(b))));
        @Override public Type<PreviewRequest> type() { return TYPE; }
    }

    /** 可展示且有界的草稿；只有完整披露的可执行计划才可能带非零令牌。 / Bounded display draft; only fully disclosed executable plans may carry a nonzero token. */
    public record PreviewResponse(int menuId, long revision, CraftingService.Draft draft) implements CustomPacketPayload {
        public PreviewResponse { identity(menuId, revision); }
        public static final Type<PreviewResponse> TYPE = new Type<>(Craftable.id("plan_preview_result"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PreviewResponse> CODEC = CustomPacketPayload.codec(
                (p, b) -> {
                    b.writeVarInt(p.menuId); b.writeVarLong(p.revision);
                    // 出服务端前强制字节上限；超大显示无令牌，不能用截断图授权隐藏操作。
                    // Enforce the byte cap before sending; oversized displays carry no token, never permission for work hidden by graph truncation.
                    // OTHER 保留既有内层字节格式，不随外层协商连接类型改变。
                    // OTHER preserves the existing inner byte format, independently of the outer negotiated connection type.
                    var body = new RegistryFriendlyByteBuf(Unpooled.buffer(1024, CraftingWire.MAX_DETAIL_BYTES), b.registryAccess(), ConnectionType.OTHER);
                    try {
                        try { writeDraft(body, p.draft); }
                        catch (IndexOutOfBoundsException | io.netty.handler.codec.EncoderException oversized) {
                            body.clear();
                            writeDraft(body, failed(CraftingResultCode.SEARCH_BUDGET_EXCEEDED));
                        }
                        b.writeByteArray(ByteBufUtil.getBytes(body));
                    } finally { body.release(); }
                }, b -> {
                    int menu = b.readVarInt(); long revision = b.readVarLong();
                    byte[] bytes = b.readByteArray(CraftingWire.MAX_DETAIL_BYTES);
                    var body = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), b.registryAccess(), ConnectionType.OTHER);
                    try {
                        var draft = new CraftingService.Draft(body.readUUID(), CraftingWire.view(body), CraftingWire.choices(body));
                        if (body.isReadable()) throw new IllegalArgumentException("Trailing plan bytes");
                        return new PreviewResponse(menu, revision, draft);
                    } finally { body.release(); }
                });
        @Override public Type<PreviewResponse> type() { return TYPE; }
    }

    /** 令牌只索引服务端一次性确认记录；recipe用于回应关联，不能替换令牌中绑定的请求。 / Token indexes a one-use server offer; recipe correlates replies and cannot replace its bound request. */
    public record ConfirmRequest(int menuId, long revision, ResourceLocation recipe, UUID token,
            org.craftablemc.craftable.planner.CraftPlan.Witness witness) implements CustomPacketPayload {
        public ConfirmRequest { identity(menuId, revision); }
        public static final Type<ConfirmRequest> TYPE = new Type<>(Craftable.id("plan_confirm"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ConfirmRequest> CODEC = CustomPacketPayload.codec(
                (p, outer) -> CraftingWire.writeFrame(outer, b -> { b.writeVarInt(p.menuId); b.writeVarLong(p.revision); b.writeUtf(p.recipe.toString(),512); b.writeUUID(p.token); CraftingWire.witness(b, p.witness); }),
                outer -> CraftingWire.readFrame(outer, b -> new ConfirmRequest(b.readVarInt(), b.readVarLong(), ResourceLocation.parse(b.readUtf(512)), b.readUUID(), CraftingWire.witness(b))));
        @Override public Type<ConfirmRequest> type() { return TYPE; }
    }

    static CraftingService.Draft failed(CraftingResultCode code) {
        return new CraftingService.Draft(NO_TOKEN, PlanView.failed(code), new PlanView.Choices(List.of(), false));
    }

    /** 使用真实编解码路径试编码并释放临时缓冲区；失败只表示无法安全发送，不授予执行回退。 / Probe the real codec and release its buffer; failure means unsafe to send, never authority for an execution fallback. */
    public static boolean fitsWitness(CraftRequest request, org.craftablemc.craftable.planner.CraftPlan.Witness witness,
            net.minecraft.core.RegistryAccess registries) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(1024, CraftingWire.MAX_DETAIL_BYTES), registries, ConnectionType.OTHER);
        try { PreviewRequest.CODEC.encode(buffer, new PreviewRequest(100, Long.MAX_VALUE, request, "", witness)); return true; }
        catch (RuntimeException oversized) { return false; }
        finally { buffer.release(); }
    }

    /** 本地显示使用服务端草稿的同一字节上限，但不会获得服务端执行令牌。 / Local displays share the server-draft byte cap without acquiring server execution tokens. */
    public static CraftingService.Draft boundedLocal(CraftingService.Draft draft, net.minecraft.core.RegistryAccess registries) {
        var body = new RegistryFriendlyByteBuf(Unpooled.buffer(1024, CraftingWire.MAX_DETAIL_BYTES), registries, ConnectionType.OTHER);
        try {
            writeDraft(body, draft);
            return draft;
        } catch (IndexOutOfBoundsException | io.netty.handler.codec.EncoderException oversized) {
            return failed(CraftingResultCode.SEARCH_BUDGET_EXCEEDED);
        } finally { body.release(); }
    }

    private static void writeDraft(RegistryFriendlyByteBuf buffer, CraftingService.Draft draft) {
        buffer.writeUUID(draft.token());
        CraftingWire.view(buffer, draft.view());
        CraftingWire.choices(buffer, draft.choices());
    }

    private static void identity(int menu, long revision) {
        if (menu < 0 || menu > 100 || revision < 0) throw new IllegalArgumentException("Invalid detail identity");
    }
}
