package cn.origolds.ia;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 志愿者绑定的服务端入口：网络载荷处理器全部转发到这里。
 *
 * <p>操作（快速绑定切换 / 改名 / 排序 / 删除 / 打开列表）都做双重距离校验——
 * 玩家离志愿者、玩家离目标锯片台均不得超过 33 格（33²），并且目标必须是
 * 已加载的锯片台；改名长度截到 64。列表/快速绑定结果回发给该玩家本人。</p>
 */
public final class OGEVolunteerBindManager {

    /** 打开配置界面的最大距离平方（8 格）。 */
    private static final double MAX_INTERACT_DISTANCE_SQ = 64.0;
    /** 快速绑定允许的 33 格半径平方。 */
    private static final double MAX_QUICK_BIND_DISTANCE_SQ = 1089.0;
    /** 自定义名长度上限（服务端截断；改名写进锯片台共享）。 */
    public static final int MAX_BIND_NAME_LENGTH = 64;

    private OGEVolunteerBindManager() {
    }

    /** 客户端请求进入"快速绑定"瞄准态：回发当前绑定坐标列表。 */
    public static void beginQuickBind(ServerPlayer player, int entityId) {
        if (player.level().getEntity(entityId) instanceof OGEVolunteerWatcherEntity volunteer
                && !volunteer.isRemoved() && !tooFar(player, volunteer.position(), MAX_INTERACT_DISTANCE_SQ)) {
            sendQuickBind(player, volunteer);
        }
    }

    /** 快速绑定态中对准锯片台点击：未绑则绑（顶掉旧主），已绑则解。 */
    public static void toggleBind(ServerPlayer player, int entityId, long packedPos) {
        if (!(player.level().getEntity(entityId) instanceof OGEVolunteerWatcherEntity volunteer)
                || volunteer.isRemoved()) {
            return;
        }
        BlockPos pos = BlockPos.of(packedPos);
        if (player.distanceToSqr(volunteer.position()) > MAX_QUICK_BIND_DISTANCE_SQ
                || player.distanceToSqr(pos.getCenter()) > MAX_QUICK_BIND_DISTANCE_SQ
                || !player.level().isLoaded(pos)
                || !(player.level().getBlockState(pos).getBlock() instanceof OGESawbladeMountBlock)
                || !(player.level().getBlockEntity(pos) instanceof OGESawbladeMountBlockEntity mount)) {
            return;
        }
        if (volunteer.hasBind(pos)) {
            volunteer.removeBind(pos);
            player.sendOverlayMessage(Component.translatable(
                    "message.origolds_ia.volunteer_unbind_ok", pos.getX(), pos.getY(), pos.getZ()));
        } else {
            UUID previous = mount.getBoundVolunteerId();
            if (previous != null && !previous.equals(volunteer.getUUID())
                    && player.level().getEntity(previous) instanceof OGEVolunteerWatcherEntity oldOwner) {
                oldOwner.removeBind(pos);
            }
            volunteer.addBind(pos);
            mount.setBoundVolunteer(volunteer.getUUID());
            mount.setChanged();
            player.sendOverlayMessage(Component.translatable(
                    "message.origolds_ia.volunteer_bind_ok", pos.getX(), pos.getY(), pos.getZ()));
        }
        sendQuickBind(player, volunteer);
    }

    /** 右键志愿者：校验距离后回发绑定全量列表（客户端据此打开界面）。 */
    public static void openConfig(ServerPlayer player, int entityId) {
        if (player.level().getEntity(entityId) instanceof OGEVolunteerWatcherEntity volunteer
                && !volunteer.isRemoved() && !tooFar(player, volunteer.position(), MAX_INTERACT_DISTANCE_SQ)) {
            sendList(player, volunteer);
        }
    }

    /**
     * 界面改名（截 64 字符、去首尾空白）。名字写在锯片台方块实体上，绑定同一台的所有
     * 志愿者即时共享；改名成功后向整个维度广播 {@link OGEVolunteerBindNamePayload}，
     * 任何客户端开着的绑定管理界面原位刷新。
     */
    public static void rename(ServerPlayer player, int entityId, long packedPos, String name) {
        OGEVolunteerWatcherEntity volunteer = resolve(player, entityId);
        if (volunteer != null) {
            String trimmed = name.length() > MAX_BIND_NAME_LENGTH
                    ? name.substring(0, MAX_BIND_NAME_LENGTH) : name;
            BlockPos pos = BlockPos.of(packedPos);
            if (volunteer.renameBind(pos, trimmed.strip())) {
                PacketDistributor.sendToPlayersInDimension(player.level(),
                        new OGEVolunteerBindNamePayload(packedPos, volunteer.bindDisplayName(pos)));
            }
        }
    }

    /** 界面拖拽排序。 */
    public static void reorder(ServerPlayer player, int entityId, List<BlockPos> order) {
        OGEVolunteerWatcherEntity volunteer = resolve(player, entityId);
        if (volunteer != null) {
            volunteer.reorderBinds(order);
        }
    }

    /** 界面删除一条绑定。 */
    public static void delete(ServerPlayer player, int entityId, long packedPos) {
        OGEVolunteerWatcherEntity volunteer = resolve(player, entityId);
        if (volunteer != null) {
            volunteer.removeBind(BlockPos.of(packedPos));
        }
    }

    private static OGEVolunteerWatcherEntity resolve(ServerPlayer player, int entityId) {
        return player.level().getEntity(entityId) instanceof OGEVolunteerWatcherEntity volunteer
                && !volunteer.isRemoved()
                && !tooFar(player, volunteer.position(), MAX_INTERACT_DISTANCE_SQ)
                ? volunteer : null;
    }

    private static void sendList(ServerPlayer player, OGEVolunteerWatcherEntity volunteer) {
        List<OGEVolunteerBindEntry> entries = volunteer.bladeBinds();
        List<Long> positions = new ArrayList<>(entries.size());
        List<String> names = new ArrayList<>(entries.size());
        for (OGEVolunteerBindEntry entry : entries) {
            positions.add(entry.pos().asLong());
            // 名字实时取自锯片台方块实体——绑同一台的所有志愿者看到的永远是同一个名字。
            names.add(volunteer.bindDisplayName(entry.pos()));
        }
        PacketDistributor.sendToPlayer(player, new OGEVolunteerBindSyncPayload(volunteer.getId(), positions, names));
    }

    private static void sendQuickBind(ServerPlayer player, OGEVolunteerWatcherEntity volunteer) {
        List<Long> positions = new ArrayList<>();
        for (OGEVolunteerBindEntry entry : volunteer.bladeBinds()) {
            positions.add(entry.pos().asLong());
        }
        PacketDistributor.sendToPlayer(player, new OGEVolunteerQuickBindPayload(volunteer.getId(), positions));
    }

    private static boolean tooFar(ServerPlayer player, Vec3 target, double maxDistanceSq) {
        return player.distanceToSqr(target) > maxDistanceSq;
    }
}
