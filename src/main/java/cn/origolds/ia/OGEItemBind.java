package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * “绑定过滤物品”的服务端统一入口。
 *
 * <p>玩家手持物品 Alt+中键 → 把主手物品记成这台机器唯一接受的物品；空手 Alt+中键 → 清空绑定。
 * 修饰键 Alt 的判定在客户端（见 {@link OGEItemBindClient}），本类作为服务端复核：
 * 方块实体必须实现 {@link OGEItemBindable}，且在 8 格交互距离内，客户端送来的坐标才被采纳。</p>
 *
 * <p>绑定／清空的流程与播报只写这一份：拿到 {@link OGEItemBindable} 后统一走 {@link #bind}。
 * 以后新增机器，只要方块实体实现该接口、方块实现 {@link OGEItemBindableBlock}，
 * 就自动接上绑定功能，这里一行都不用改。</p>
 *
 * <p>三台机器的绑定语义差别集中在 {@link OGEItemBindable#isBindingCompatible(ItemStack)}：
 * 虚空抽屉限死“只存一种物品”，新绑定必须与现有内容相容；引力匣与超级漏斗是普通容器，
 * 绑定只约束以后进来的物品，随时可绑可清。</p>
 */
public final class OGEItemBind {

    /**
     * 允许远程操作的最大距离平方（8 格，与放置器/引力匣改档一致）。
     * 客户端同款判定见 {@link OGEItemBindClient}，两处共用这一个常量。
     */
    static final double MAX_INTERACT_DISTANCE_SQ = 64.0;

    private OGEItemBind() {
    }

    /** 服务端入口：坐标处是可绑定机器时，按玩家主手物品设置或清空绑定。 */
    public static void handle(ServerPlayer player, long packedPos) {
        BlockPos pos = BlockPos.of(packedPos);
        Level level = player.level();
        if (!level.isLoaded(pos)
                || player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_INTERACT_DISTANCE_SQ) {
            return;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof OGEItemBindable target) {
            bind(player, be, target, player.getMainHandItem());
        }
    }

    /**
     * 绑定／清空的唯一实现：空手清空；有物品则先过机器的相容性校验，再记成样本。
     *
     * <p>拿在手上的物品不往背包里扣——它只是被当作样本记下来（只存 1 个），
     * 玩家自己的物品一件不少。</p>
     */
    private static void bind(ServerPlayer player, BlockEntity be, OGEItemBindable target, ItemStack held) {
        Component name = blockNameOf(be);
        if (held.isEmpty()) {
            target.setBound(ItemStack.EMPTY);
            player.displayClientMessage(
                    Component.translatable("message.origolds_ia.item_bind_unbound", name), true);
            return;
        }
        if (!target.isBindingCompatible(held)) {
            player.displayClientMessage(target.bindRejectedMessage(), true);
            return;
        }
        target.setBound(held);
        player.displayClientMessage(
                Component.translatable("message.origolds_ia.item_bind_bound", name, held.getHoverName()), true);
    }

    /** 方块显示名（与方块实体 getDefaultName 同一套翻译键）。 */
    private static Component blockNameOf(BlockEntity be) {
        return Component.translatable(be.getBlockState().getBlock().getDescriptionId());
    }
}
