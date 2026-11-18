package org.craftablemc.craftable.network;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.environment.BrowsingSnapshot;
import org.craftablemc.craftable.config.DirectPreparationPolicy;

/**
 * 当前订阅只可补传同一份内容一次，未知请求、已知内容心跳与外来会话均不重发。
 * A subscription can re-sync identical content once; unknown requests, acknowledgements and foreign sessions never restart it.
 * 快照含真实 ItemStack，沿已有 GameTest 注册表环境检查实际发送状态；客户端场景另验证包/轮询/解码接线。
 * Snapshots contain real ItemStacks, so the existing GameTest registry environment tests sender state; the client scenario separately covers packet/poll/decode wiring.
 */
@GameTestHolder(Craftable.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BrowseTransferGameTests {
    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void onlyMissingBytesForCurrentSnapshotCanRequestOneResync(GameTestHelper helper) {
        var snapshot = new BrowsingSnapshot(UUID.randomUUID(), 1, 2, true, false, Set.of(), false,
                64, 128, DirectPreparationPolicy.of(DirectPreparationPolicy.Mode.REQUIRE_CONFIRMATION, List.of()), 64,
                Collections.nCopies(36, ItemStack.EMPTY), List.of());
        var transfer = new BrowseSnapshotTransfers.Transfer(request(CraftingDetailPayloads.NO_TOKEN, -1));
        transfer.snapshot = snapshot;
        helper.assertTrue(!transfer.resyncRequested(snapshot), "initial unknown request restarted transfer");
        transfer.request = request(UUID.randomUUID(), -1);
        helper.assertTrue(!transfer.resyncRequested(snapshot), "foreign session restarted transfer");
        transfer.request = request(snapshot.session(), snapshot.resources());
        helper.assertTrue(!transfer.resyncRequested(snapshot), "acknowledgement restarted transfer");
        transfer.request = request(snapshot.session(), -1);
        helper.assertTrue(transfer.resyncRequested(snapshot), "missing bytes could not request re-sync");
        transfer.resynced = true;
        helper.assertTrue(!transfer.resyncRequested(snapshot), "same content was retried twice");
        transfer.resynced = false;
        transfer.snapshot = null;
        helper.assertTrue(!transfer.resyncRequested(snapshot), "unowned content restarted transfer");
        helper.succeed();
    }

    private static CraftingDetailPayloads.BrowseRequest request(UUID session, long resources) {
        return new CraftingDetailPayloads.BrowseRequest(1, 9, true, session, resources);
    }
}
