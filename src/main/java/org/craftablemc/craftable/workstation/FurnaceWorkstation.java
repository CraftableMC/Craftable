package org.craftablemc.craftable.workstation;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.craftablemc.craftable.menu.mixin.BrewingStandStateAccessor;

/** 原版加工端点的只读判据，不加载区块、不打开菜单，也不授予玩家访问权。
 * Read-only vanilla processing predicates without chunk loading, menu opening or player access grants. */
public final class FurnaceWorkstation {
    private FurnaceWorkstation() {}

    /** 只接受已存在、与原版方块匹配的原生实体，不借类似类型启用第三方处理。
     * Accept only existing native entities matching their vanilla blocks, not similar third-party processors. */
    @Nullable
    public static WorkstationCapability capability(ServerLevel level, BlockPos pos) {
        var chunk = chunk(level, pos);
        if (chunk == null) return null;
        var entity = chunk.getBlockEntities().get(pos);
        if (entity == null || entity.isRemoved() || entity.getLevel() != level || !entity.getBlockPos().equals(pos)) return null;
        var state = chunk.getBlockState(pos);
        if (!entity.getType().isValid(state)) return null;
        WorkstationCapability capability;
        if (entity.getClass() == FurnaceBlockEntity.class && entity.getType() == BlockEntityType.FURNACE)
            capability = WorkstationCapability.FURNACE;
        else if (entity.getClass() == BlastFurnaceBlockEntity.class && entity.getType() == BlockEntityType.BLAST_FURNACE)
            capability = WorkstationCapability.BLAST_FURNACE;
        else if (entity.getClass() == SmokerBlockEntity.class && entity.getType() == BlockEntityType.SMOKER)
            capability = WorkstationCapability.SMOKER;
        else if (entity.getClass() == CampfireBlockEntity.class && entity.getType() == BlockEntityType.CAMPFIRE)
            capability = WorkstationCapability.CAMPFIRE;
        else if (entity.getClass() == BrewingStandBlockEntity.class && entity.getType() == BlockEntityType.BREWING_STAND)
            capability = WorkstationCapability.BREWING_STAND;
        else return null;
        return matchesBlock(state, capability) ? capability : null;
    }

    public static boolean matchesBlock(BlockState state, WorkstationCapability capability) {
        return switch (capability) {
            case CRAFTING_3X3 -> state.is(Blocks.CRAFTING_TABLE);
            case FURNACE -> state.is(Blocks.FURNACE);
            case BLAST_FURNACE -> state.is(Blocks.BLAST_FURNACE);
            case SMOKER -> state.is(Blocks.SMOKER);
            case CAMPFIRE -> state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE);
            case STONECUTTER -> state.is(Blocks.STONECUTTER);
            case SMITHING_TABLE -> state.is(Blocks.SMITHING_TABLE);
            case BREWING_STAND -> state.is(Blocks.BREWING_STAND);
            case CRAFTING_2X2 -> false;
        };
    }

    /** 点火状态单独影响进度，熄灭时仍可识别同一个订单端点。
     * Lighting gates progress independently, so an extinguished campfire still retains its order identity. */
    public static boolean litCampfire(ServerLevel level, BlockPos pos) {
        var chunk = chunk(level, pos);
        if (chunk == null) return false;
        var state = chunk.getBlockState(pos);
        return matchesBlock(state, WorkstationCapability.CAMPFIRE) && state.getValue(CampfireBlock.LIT);
    }

    /** 与原版方块实体 tick 的外层及 LevelChunk 判据一致；已加载不代表当前能运行。
     * Match vanilla's outer block-entity tick and LevelChunk gates: loaded alone does not mean runnable. */
    public static boolean ticking(ServerLevel level, BlockPos pos) {
        if (capability(level, pos) == null || !level.tickRateManager().runsNormally()
                || !level.getWorldBorder().isWithinBounds(pos) || !level.shouldTickBlocksAt(pos)
                || !level.areEntitiesLoaded(ChunkPos.asLong(pos))) return false;
        var chunk = chunk(level, pos);
        return chunk != null && chunk.getFullStatus().isOrAfter(FullChunkStatus.BLOCK_TICKING);
    }

    /** 检查工作站是否正在被原版加工占用。酿造台的剩余燃料由原版保留。
     * Check for native processing activity. A brewing stand retains its separate native fuel reserve. */
    public static boolean idle(ServerLevel level, BlockPos pos) {
        var capability = capability(level, pos);
        if (capability == null) return false;
        var chunk = chunk(level, pos);
        if (chunk == null) return false;
        if (capability == WorkstationCapability.CAMPFIRE) {
            var campfire = (CampfireBlockEntity) chunk.getBlockEntities().get(pos);
            return chunk.getBlockState(pos).getValue(CampfireBlock.LIT)
                    && campfire.getItems().stream().allMatch(net.minecraft.world.item.ItemStack::isEmpty);
        }
        if (capability == WorkstationCapability.BREWING_STAND) {
            var stand = (BrewingStandBlockEntity) chunk.getBlockEntities().get(pos);
            return stand.isEmpty() && ((BrewingStandStateAccessor) stand).craftable$brewTime() == 0;
        }
        if (chunk.getBlockState(pos).getValue(AbstractFurnaceBlock.LIT)) return false;
        var furnace = (AbstractFurnaceBlockEntity) chunk.getBlockEntities().get(pos);
        for (int slot = 0; slot < furnace.getContainerSize(); slot++)
            if (!furnace.getItem(slot).isEmpty()) return false;
        return furnace.saveCustomOnly(level.registryAccess()).getInt("BurnTime") <= 0;
    }

    @Nullable
    static LevelChunk chunk(ServerLevel level, BlockPos pos) {
        // getChunkNow 只读当前服务端线程已有的区块；直接查实体表也不提升待加载的 NBT。
        // getChunkNow reads only an already-present chunk on the server thread; map lookup does not promote pending NBT.
        return level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ()));
    }
}
