package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * 服务端“Alt+中键绑过滤物品”的统一入口（客户端由
 * {@link OGEItemBindClient} 发起 {@link OGEItemBindPayload}）。
 *
 * <p>手持物品 = 绑定；空手 = 解绑；不兼容则拒绝并提示。</p>
 */
public final class OGEItemBind {

    /** 与 {@link OGEVolunteerBindManager} 一致的交互距离上限（8 格）。 */
    static final double MAX_INTERACT_DISTANCE_SQ = 64.0;

    private OGEItemBind() {
    }

    public static void handle(ServerPlayer player, long packedPos) {
        BlockPos pos = BlockPos.of(packedPos);
        Level level = player.level();
        if (level.isLoaded(pos) && !(player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_INTERACT_DISTANCE_SQ)) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof OGEItemBindable target) {
                bind(player, be, target, player.getMainHandItem());
            }
        }
    }

    private static void bind(ServerPlayer player, BlockEntity be, OGEItemBindable target, ItemStack held) {
        Component name = blockNameOf(be);
        if (held.isEmpty()) {
            target.setBound(ItemStack.EMPTY);
            player.sendOverlayMessage(
                    Component.translatable("message.origolds_ia.item_bind_unbound", name));
        } else if (!target.isBindingCompatible(held)) {
            player.sendOverlayMessage(target.bindRejectedMessage());
        } else {
            target.setBound(held);
            player.sendOverlayMessage(
                    Component.translatable("message.origolds_ia.item_bind_bound", name, held.getHoverName()));
        }
    }

    private static Component blockNameOf(BlockEntity be) {
        return Component.translatable(be.getBlockState().getBlock().getDescriptionId());
    }
}
