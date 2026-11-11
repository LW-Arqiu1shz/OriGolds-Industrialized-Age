package cn.origolds.ia;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 挂机志愿者 ↔ 金源圆锯刃槽 的绑定流程（服务端）。
 *
 * <p>交互方式（客户端判定见 {@link OGEVolunteerBindClient}，这里只复核）：</p>
 * <ul>
 *   <li>普通右键志愿者：下发整张绑定表并打开配置界面（改名、拖序都回这里校验）；</li>
 *   <li>Ctrl+左键志愿者：进入"快捷绑定"模式——把该志愿者已绑定的刃槽列表发给客户端，
 *       由客户端在 32 格半径内画白色线框；</li>
 *   <li>快捷绑定模式里左键点中某个刃槽线框：切换这个刃槽与该志愿者的绑定状态；
 *       右键退出模式。</li>
 * </ul>
 *
 * <p>绑定的<b>有哪些</b>（坐标与顺序）权威存在志愿者身上，刃槽方块实体只留一份反向引用；
 * 绑定的<b>显示名</b>则相反——唯一一份存在刃槽方块实体上（{@code volunteer_bind_name}），
 * 下发时现取，所以在任一志愿者的界面里改名，绑定了同一刃槽的其它志愿者会一起变。
 * 绑定数据清除：志愿者被收回（实体移除）、刃槽被破坏（见
 * {@link OGESawbladeMountBlock#onRemove}）或志愿者每 20 刻的对账（刃槽没了 /
 * 被别的志愿者抢绑）。</p>
 */
public final class OGEVolunteerBindManager {

    /** 与 {@link OGEItemBind#MAX_INTERACT_DISTANCE_SQ} 同规格的 8 格交互距离。 */
    private static final double MAX_INTERACT_DISTANCE_SQ = 64.0;

    /** 快捷绑定模式允许的 32 格操作半径（留一点余量，避免贴边被拒）。 */
    private static final double MAX_QUICK_BIND_DISTANCE_SQ = 33.0 * 33.0;

    /** 刃槽显示名长度上限（客户端输入框与服务端口径一致）。 */
    public static final int MAX_BIND_NAME_LENGTH = 64;

    private OGEVolunteerBindManager() {
    }

    /** Ctrl+左键志愿者：把该志愿者已绑定的刃槽列表发给客户端，客户端据此进入快捷绑定模式。 */
    public static void beginQuickBind(ServerPlayer player, int entityId) {
        if (!(player.level().getEntity(entityId) instanceof OGEVolunteerWatcherEntity volunteer)
                || volunteer.isRemoved()
                || tooFar(player, volunteer.position())) {
            return;
        }
        sendQuickBind(player, volunteer);
    }

    /** 快捷绑定模式里左键点中线框：把该刃槽与志愿者的绑定状态取反。 */
    public static void toggleBind(ServerPlayer player, int entityId, long packedPos) {
        if (!(player.level().getEntity(entityId) instanceof OGEVolunteerWatcherEntity volunteer)
                || volunteer.isRemoved()) {
            return;
        }
        BlockPos pos = BlockPos.of(packedPos);
        if (player.distanceToSqr(volunteer.position()) > MAX_QUICK_BIND_DISTANCE_SQ
                || player.distanceToSqr(pos.getCenter()) > MAX_QUICK_BIND_DISTANCE_SQ
                || !player.serverLevel().isLoaded(pos)
                || !(player.serverLevel().getBlockState(pos).getBlock() instanceof OGESawbladeMountBlock)
                || !(player.serverLevel().getBlockEntity(pos) instanceof OGESawbladeMountBlockEntity mount)) {
            return;
        }

        if (volunteer.hasBind(pos)) {
            volunteer.removeBind(pos);
            player.displayClientMessage(Component.translatable(
                    "message.origolds_ia.volunteer_unbind_ok", pos.getX(), pos.getY(), pos.getZ()), true);
        } else {
            // 这个刃槽原先绑了别的志愿者：先从旧主那边把这条解掉（后绑者优先）。
            UUID previous = mount.getBoundVolunteerId();
            if (previous != null && !previous.equals(volunteer.getUUID())
                    && player.serverLevel().getEntity(previous) instanceof OGEVolunteerWatcherEntity oldOwner) {
                oldOwner.removeBind(pos);
            }
            volunteer.addBind(pos);
            mount.setBoundVolunteer(volunteer.getUUID());
            mount.setChanged();
            player.displayClientMessage(Component.translatable(
                    "message.origolds_ia.volunteer_bind_ok", pos.getX(), pos.getY(), pos.getZ()), true);
        }
        // 回发最新绑定表，客户端线框的高亮状态以此为准。
        sendQuickBind(player, volunteer);
    }

    /** 普通右键志愿者：把整张绑定表下发给客户端并让它打开配置界面。 */
    public static void openConfig(ServerPlayer player, int entityId) {
        if (!(player.level().getEntity(entityId) instanceof OGEVolunteerWatcherEntity volunteer)
                || volunteer.isRemoved()
                || tooFar(player, volunteer.position())) {
            return;
        }
        sendList(player, volunteer);
    }

    /**
     * 改名（配置界面输入框失焦时发来）。名字唯一存在刃槽那边，改一次全体同步；
     * 改完朝同一维度广播 {@link OGEVolunteerBindNamePayload}，让别的志愿者已经开着的
     * 配置界面也当场把这一行换掉（没开界面的客户端直接忽略）。
     */
    public static void rename(ServerPlayer player, int entityId, long packedPos, String name) {
        OGEVolunteerWatcherEntity volunteer = resolve(player, entityId);
        if (volunteer == null) {
            return;
        }
        String trimmed = name.length() > MAX_BIND_NAME_LENGTH
                ? name.substring(0, MAX_BIND_NAME_LENGTH) : name;
        BlockPos pos = BlockPos.of(packedPos);
        if (!volunteer.renameBind(pos, trimmed.strip())) {
            return;
        }
        String stored = volunteer.bindDisplayName(pos);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersInDimension(
                player.serverLevel(), new OGEVolunteerBindNamePayload(packedPos, stored));
    }

    /** 拖拽排序（松开鼠标时发来完整顺序）。 */
    public static void reorder(ServerPlayer player, int entityId, List<BlockPos> order) {
        OGEVolunteerWatcherEntity volunteer = resolve(player, entityId);
        if (volunteer != null) {
            volunteer.reorderBinds(order);
        }
    }

    /** 配置界面里点垃圾桶：删掉这条绑定（顺带清掉刃槽那边的反向引用）。 */
    public static void delete(ServerPlayer player, int entityId, long packedPos) {
        OGEVolunteerWatcherEntity volunteer = resolve(player, entityId);
        if (volunteer != null) {
            volunteer.removeBind(BlockPos.of(packedPos));
        }
    }

    private static OGEVolunteerWatcherEntity resolve(ServerPlayer player, int entityId) {
        if (player.level().getEntity(entityId) instanceof OGEVolunteerWatcherEntity volunteer
                && !volunteer.isRemoved()
                && !tooFar(player, volunteer.position())) {
            return volunteer;
        }
        return null;
    }

    private static void sendList(ServerPlayer player, OGEVolunteerWatcherEntity volunteer) {
        List<OGEVolunteerBindEntry> entries = volunteer.bladeBinds();
        List<Long> positions = new ArrayList<>(entries.size());
        List<String> names = new ArrayList<>(entries.size());
        for (OGEVolunteerBindEntry entry : entries) {
            positions.add(entry.pos().asLong());
            // 名字现取于刃槽：绑同一个刃槽的志愿者看到的永远是同一个名字。
            names.add(volunteer.bindDisplayName(entry.pos()));
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                new OGEVolunteerBindSyncPayload(volunteer.getId(), positions, names));
    }

    private static void sendQuickBind(ServerPlayer player, OGEVolunteerWatcherEntity volunteer) {
        List<Long> positions = new ArrayList<>();
        for (OGEVolunteerBindEntry entry : volunteer.bladeBinds()) {
            positions.add(entry.pos().asLong());
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                new OGEVolunteerQuickBindPayload(volunteer.getId(), positions));
    }

    private static boolean tooFar(ServerPlayer player, net.minecraft.world.phys.Vec3 target) {
        return player.distanceToSqr(target) > MAX_INTERACT_DISTANCE_SQ;
    }
}
