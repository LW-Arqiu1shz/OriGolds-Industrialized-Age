package cn.origolds.ia;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;

/**
 * 金源粉碎机的方块实体。
 *
 * <p>服务器端每个游戏刻尝试一次：按固定顺序检查与自身相邻的 5 个格子
 * （北、东、南、西四个水平侧面 + 底下），格子里优先取容器（箱子、桶等）中的物品，
 * 否则取漂浮在该格的掉落物。找到第一个可粉碎的目标后，从所在无线电网（电池优先）
 * 原子扣除 {@value OGEPulverizerBlock#COST_PER_OPERATION} u OGE 并执行一次粉碎；
 * 能量不足则本刻什么都不做、也不扣费。每刻至多粉碎 1 个物品。</p>
 *
 * <ul>
 *   <li>粗矿（c:raw_materials 标签，如粗铁/粗铜/粗金）→ 变为 2 个“精矿”：
 *       与粗矿是同一物品，但携带 {@code origolds_ia:oge_concentrated} 组件并改名
 *       （“精炼粗铁” / “Concentrated Raw Iron”）。精矿熔炉配方与粗矿相同
 *       （熔炼配方只认物品本身），但不能再次被粉碎；配方数据里已用
 *       {@code neoforge:difference} 把它排除出粗矿块的合成；</li>
 *   <li>矿石物品（c:ores 标签对应方块的物品形态，含青金石/红石/石英等）→
 *       按该矿石方块正常挖掘（无时运、无精准采集）的战利品表产物 ×2 掉落。</li>
 * </ul>
 *
 * <p>粉碎产物统一掉落在粉碎机顶部。若某矿石正常挖掘会掉回自身，则拒绝粉碎，
 * 防止 1 进 2 出无限增殖。</p>
 */
public final class OGEPulverizerBlockEntity extends BlockEntity {

    /** 输入扫描顺序：四个水平侧面 + 底下。 */
    private static final Direction[] INPUT_DIRECTIONS = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.DOWN
    };

    public OGEPulverizerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.OGE_PULVERIZER.get(), pos, state);
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
        // 在四个水平侧面 + 底下里找第一份可粉碎的原料（容器优先，其次掉落物）。
        // find 返回的句柄同时握住“取走这一份”的入口：查与取共用一次扫描。
        OGEFaceItems.Found<List<ItemStack>> found = OGEFaceItems.find(
                serverLevel, worldPosition, INPUT_DIRECTIONS,
                (pos, stack) -> planOutputs(stack, serverLevel, pos));
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
     * 判定一个输入物品能否粉碎，并给出全部输出（不含输入自身的消耗）。
     * 返回 null 表示该物品不是合法原料。纯计算，不扣能量、不改动世界状态。
     */
    @Nullable
    private static List<ItemStack> planOutputs(ItemStack input, ServerLevel serverLevel, BlockPos sourcePos) {
        if (input.isEmpty() || input.has(ModDataComponents.OGE_CONCENTRATED.get())) {
            return null; // 空堆或已是精矿：精矿不可二次粉碎
        }

        // 黑曜石 → 6~10 个闪亮物品（10%/20%/40%/20%/10%），
        // 每个 25% 碎粒、25% 碎片、50% 碎块。
        if (input.is(Blocks.OBSIDIAN.asItem())) {
            return OGEShinyLoot.makeObsidianOutputs(
                    OGEShinyLoot.rollObsidianCount(serverLevel.getRandom()), serverLevel.getRandom());
        }

        // 粗矿 → 2 个精矿
        if (input.is(Tags.Items.RAW_MATERIALS)) {
            return List.of(makeConcentrated(input));
        }

        // 矿石物品 → 正常挖掘产物 ×2
        if (input.getItem() instanceof BlockItem blockItem) {
            Block block = blockItem.getBlock();
            if (block != Blocks.AIR && block.defaultBlockState().is(Tags.Blocks.ORES)) {
                List<ItemStack> drops = Block.getDrops(
                        block.defaultBlockState(), serverLevel, sourcePos, null, null, ItemStack.EMPTY);
                if (drops.isEmpty()) {
                    return null;
                }
                for (ItemStack drop : drops) {
                    if (drop.is(input.getItem())) {
                        return null; // 会掉回自己：粉碎会变成无限增殖，拒绝
                    }
                }
                List<ItemStack> doubled = new ArrayList<>(drops.size() * 2);
                for (ItemStack drop : drops) {
                    int total = drop.getCount() * 2;
                    int max = drop.getMaxStackSize();
                    while (total > 0) {
                        int n = Math.min(total, max);
                        doubled.add(drop.copyWithCount(n));
                        total -= n;
                    }
                }
                return doubled;
            }
        }
        return null;
    }

    /** 用物品组件生成精矿：同一粗矿物品 + {@code oge_concentrated} 标记 + 前缀改名。 */
    private static ItemStack makeConcentrated(ItemStack raw) {
        ItemStack out = raw.copyWithCount(2);
        out.remove(DataComponents.CUSTOM_NAME); // 玩家的铁砧改名不随精矿继承
        out.set(ModDataComponents.OGE_CONCENTRATED.get(), raw.typeHolder());
        out.set(DataComponents.ITEM_NAME,
                Component.translatable("item.origolds_ia.concentrated",
                        Component.translatable(raw.getItem().getDescriptionId())));
        return out;
    }

    /** 从所在无线电网原子扣除单次粉碎费用；余额不足返回 false（不产生任何消耗）。 */
    private boolean payOge(MinecraftServer server) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        int cost = OGEPulverizerBlock.COST_PER_OPERATION;
        return OGEWirelessManager.consumeOGEIfAvailable(
                server, serverLevel.dimension(), worldPosition, cost) >= cost;
    }

    /** 把全部产物掉落在粉碎机顶部（Block.popResource 会自然合并到已有的掉落物堆）。 */
    private void dropOutputs(List<ItemStack> outputs) {
        BlockPos out = worldPosition.above();
        for (ItemStack stack : outputs) {
            if (level != null && !stack.isEmpty()) {
                Block.popResource(level, out, stack);
            }
        }
    }
}
