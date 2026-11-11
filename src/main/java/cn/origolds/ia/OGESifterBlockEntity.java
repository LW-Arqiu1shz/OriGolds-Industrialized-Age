package cn.origolds.ia;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 金源筛洗机的方块实体。
 *
 * <p>服务器端每个游戏刻尝试一次：按固定顺序检查与自身相邻的 5 个格子
 * （北、东、南、西四个水平侧面 + 底下），格子里优先取容器中的物品，
 * 否则取漂浮在该格的掉落物。找到第一个可筛洗的目标后，从所在无线电网（电池优先）
 * 原子扣除 {@value OGESifterBlock#COST_PER_OPERATION} u OGE 并执行一次筛洗；
 * 能量不足则本刻什么都不做、也不扣费。每刻至多筛洗 1 个物品。</p>
 *
 * <ul>
 *   <li>沙砾 → 25%/50%/25% 概率产出 3/4/5 个闪亮碎粒；</li>
 *   <li>黏土（黏土块或黏土球）→ 25%/50%/25% 概率产出 3/4/5 个物品，
 *       每个 50% 闪亮碎粒、50% 闪亮碎片；</li>
 *   <li>闪亮碎粒 / 闪亮碎片 / 闪亮碎块 → 按各自权重表产出 1 个随机矿物或粗矿。</li>
 * </ul>
 *
 * <p>筛洗产物统一掉落在筛洗机顶部。</p>
 */
public final class OGESifterBlockEntity extends BlockEntity {

    /** 输入扫描顺序：四个水平侧面 + 底下。 */
    private static final Direction[] INPUT_DIRECTIONS = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.DOWN
    };

    public OGESifterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.OGE_SIFTER.get(), pos, state);
    }

    public void tick() {
        if (level == null || level.isClientSide()) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        MinecraftServer server = serverLevel.getServer();
        if (server == null) {
            return;
        }
        // 在四个水平侧面 + 底下里找第一份可筛洗的原料（容器优先，其次掉落物）。
        // find 返回的句柄同时握住“取走这一份”的入口：查与取共用一次扫描。
        RandomSource rand = serverLevel.getRandom();
        OGEFaceItems.Found<List<ItemStack>> found = OGEFaceItems.find(
                serverLevel, worldPosition, INPUT_DIRECTIONS,
                (pos, stack) -> {
                    List<ItemStack> outputs = planOutputs(stack, rand);
                    return outputs == null || outputs.isEmpty() ? null : outputs;
                });
        if (found == null) {
            return;
        }
        // 有料但没电：本刻什么都不做、不扣费，保持停机。
        if (!payOge(server)) {
            return;
        }
        found.take(1);
        dropOutputs(found.value());
        setChanged();
    }

    /**
     * 判定一个输入物品能否筛洗，并给出全部输出（不含输入自身的消耗）。
     * 返回 null 表示该物品不是合法原料；空列表同样视为不合法（不扣能量）。纯计算。
     */
    @Nullable
    private static List<ItemStack> planOutputs(ItemStack input, RandomSource rand) {
        if (input.isEmpty()) {
            return null;
        }

        // 闪亮物品再筛洗 → 1 个随机矿物（按该物品权重表）
        if (OGEShinyLoot.isShiny(input)) {
            return OGEShinyLoot.siftShiny(input, rand);
        }

        // 沙砾 → 3/4/5 个闪亮碎粒
        if (input.is(Blocks.GRAVEL.asItem())) {
            return OGEShinyLoot.makeGravelOutputs(OGEShinyLoot.rollSiftCount(rand));
        }

        // 黏土（黏土块 / 黏土球）→ 3/4/5 个物品，每个 50% 碎粒、50% 碎片
        if (input.is(Blocks.CLAY.asItem()) || input.is(Items.CLAY_BALL)) {
            return OGEShinyLoot.makeClayOutputs(OGEShinyLoot.rollSiftCount(rand), rand);
        }

        return null;
    }

    /** 从所在无线电网原子扣除单次筛洗费用；余额不足返回 false（不产生任何消耗）。 */
    private boolean payOge(MinecraftServer server) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        int cost = OGESifterBlock.COST_PER_OPERATION;
        return OGEWirelessManager.consumeOGEIfAvailable(
                server, serverLevel.dimension(), worldPosition, cost) >= cost;
    }

    /** 把全部产物掉落在筛洗机顶部（Block.popResource 会自然合并到已有的掉落物堆）。 */
    private void dropOutputs(List<ItemStack> outputs) {
        BlockPos out = worldPosition.above();
        for (ItemStack stack : outputs) {
            if (level != null && !stack.isEmpty()) {
                Block.popResource(level, out, stack);
            }
        }
    }
}
