package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 金源无线物流管道的服务端管理器（“物流显示模式”，基础按键 Alt）。
 *
 * <p>交互：Alt+左键选起点、Alt+右键选终点，创建一条<b>单向</b>无线物流管道；
 * 物流显示模式下悬停选中管道，Delete 删除、Backspace 反转方向；
 * Ctrl+Z / Ctrl+Y 与金源能量线路共用一条操作历史（撤销最近一次装置操作）。</p>
 *
 * <p>每刻传输：起点是容器（箱子、木桶及其他暴露物品能力的方块）时，
 * 按槽位顺序每刻取 1 个物品送往终点；起点不是容器时，像放在它下面的漏斗一样，
 * 每刻从该位置吸取 1 个掉落物送往终点。终点是容器则塞入（塞不进的留在原处/原容器），
 * 不是容器则把物品丢在终点方块的正中心。</p>
 *
 * <p>终点是虚空抽屉时，起点只挑抽屉收得下的那一格物品 / 那一叠掉落物输送，
 * 不合规的直接跳过。否则它会永久占住管口，把后面合规的物品一起堵死。</p>
 */
public final class OGELogisticsManager {

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    /** 吸取掉落物用的判定盒膨胀量，与漏斗一致。 */
    private static final double PICKUP_INFLATE = 1.0 / 8.0;

    private OGELogisticsManager() {
    }

    // ------------------------------------------------------------------
    // 交互：选点建线 / 删除 / 反转
    // ------------------------------------------------------------------

    public static void onSelect(ServerPlayer player, long packedPos, boolean start) {
        if (!OGEWirelessConnectorItemRef.isHeld(player)) {
            return;
        }
        BlockPos pos = BlockPos.of(packedPos);
        Level level = player.level();
        if (!level.isLoaded(pos) || !player.isWithinBlockInteractionRange(pos, OGEWirelessManager.INTERACT_RANGE)) {
            OGEWirelessManager.tell(player, "message.origolds_ia.wc_out_of_range");
            return;
        }
        UUID id = player.getUUID();
        Pending pending = PENDING.getOrDefault(id, Pending.EMPTY);
        boolean onlyA = pending.posA() != null && pending.posB() == null;
        boolean onlyB = pending.posA() == null && pending.posB() != null;

        // 再次点击同一端点则撤销选择
        if ((start && onlyA && level.dimension().equals(pending.dimA()) && pending.posA().equals(pos))
                || (!start && onlyB && level.dimension().equals(pending.dimB()) && pending.posB().equals(pos))) {
            PENDING.remove(id);
            OGEWirelessManager.tell(player, "message.origolds_ia.wc_canceled");
            syncSelection(player);
            player.swing(OGEWirelessManager.hand(player), true);
            return;
        }

        Pending next = pending.with(start, level.dimension(), pos);
        if (next.posA() != null && next.posB() != null) {
            if (next.dimA().equals(next.dimB()) && next.posA().equals(next.posB())) {
                OGEWirelessManager.tell(player, "message.origolds_ia.wc_same_block");
                PENDING.put(id, next);
                syncSelection(player);
                return;
            }
            OGELogisticsData data = OGELogisticsData.get(player.level().getServer());
            if (data.hasPipe(next.dimA(), next.posA(), next.dimB(), next.posB())) {
                OGEWirelessManager.tell(player, "message.origolds_ia.wc_pipe_exists");
                PENDING.remove(id);
                syncSelection(player);
                return;
            }
            OGELogisticsData.PipeLink pipe = new OGELogisticsData.PipeLink(
                    UUID.randomUUID(), next.dimA(), next.posA(), next.dimB(), next.posB());
            data.addPipe(pipe);
            OGEWirelessHistory.record(id, OGEWirelessHistory.Op.pipeAdded(pipe));
            PENDING.remove(id);
            OGEWirelessManager.tell(player, "message.origolds_ia.wc_pipe_created");
            syncAll(player.level().getServer());
            syncSelection(player);
        } else {
            PENDING.put(id, next);
            player.sendOverlayMessage(Component.translatable(
                    start ? "message.origolds_ia.wc_pipe_selected_a" : "message.origolds_ia.wc_pipe_selected_b",
                    pos.getX(), pos.getY(), pos.getZ()));
            syncSelection(player);
        }
        player.swing(OGEWirelessManager.hand(player), true);
    }

    public static void onDelete(ServerPlayer player, UUID pipeId) {
        if (!OGEWirelessConnectorItemRef.isHeld(player)) {
            return;
        }
        OGELogisticsData data = OGELogisticsData.get(player.level().getServer());
        OGELogisticsData.PipeLink pipe = data.pipe(pipeId);
        if (pipe != null && !OGEWirelessManager.withinReach(player,
                pipe.dimFrom(), pipe.posFrom(), pipe.dimTo(), pipe.posTo())) {
            OGEWirelessManager.tell(player, "message.origolds_ia.wc_out_of_range");
            return;
        }
        if (data.removePipe(pipeId)) {
            if (pipe != null) {
                OGEWirelessHistory.record(player.getUUID(), OGEWirelessHistory.Op.pipeRemoved(pipe));
            }
            OGEWirelessManager.tell(player, "message.origolds_ia.wc_pipe_removed");
            syncAll(player.level().getServer());
        }
    }

    /** Backspace：把悬停选中的管道起点与终点对调（方向反转）。 */
    public static void onReverse(ServerPlayer player, UUID pipeId) {
        if (!OGEWirelessConnectorItemRef.isHeld(player)) {
            return;
        }
        OGELogisticsData data = OGELogisticsData.get(player.level().getServer());
        OGELogisticsData.PipeLink pipe = data.pipe(pipeId);
        if (pipe == null) {
            return;
        }
        if (!OGEWirelessManager.withinReach(player,
                pipe.dimFrom(), pipe.posFrom(), pipe.dimTo(), pipe.posTo())) {
            OGEWirelessManager.tell(player, "message.origolds_ia.wc_out_of_range");
            return;
        }
        OGELogisticsData.PipeLink flipped = pipe.reversed();
        if (data.hasPipe(flipped.dimFrom(), flipped.posFrom(), flipped.dimTo(), flipped.posTo())) {
            OGEWirelessManager.tell(player, "message.origolds_ia.wc_pipe_exists");
            return;
        }
        data.replacePipe(flipped);
        OGEWirelessHistory.record(player.getUUID(), OGEWirelessHistory.Op.pipeReversed(pipe, flipped));
        OGEWirelessManager.tell(player, "message.origolds_ia.wc_pipe_reversed");
        syncAll(player.level().getServer());
    }

    // ------------------------------------------------------------------
    // 每刻传输
    // ------------------------------------------------------------------

    public static void tick(MinecraftServer server) {
        OGELogisticsData data = OGELogisticsData.get(server);
        List<OGELogisticsData.PipeLink> pipes = data.pipes();
        if (pipes.isEmpty()) {
            return;
        }
        // 快照遍历：传输过程中玩家操作可能增删管道（同线程，顺序安全，快照只为语义清晰）
        for (OGELogisticsData.PipeLink pipe : List.copyOf(pipes)) {
            transferOne(server, pipe);
        }
    }

    private static void transferOne(MinecraftServer server, OGELogisticsData.PipeLink pipe) {
        ServerLevel from = server.getLevel(pipe.dimFrom());
        if (from == null || !from.isLoaded(pipe.posFrom())) {
            return;
        }
        ServerLevel to = server.getLevel(pipe.dimTo());
        if (to == null || !to.isLoaded(pipe.posTo())) {
            return;
        }

        // 起点是锯片台：这一跳只搬它存的经验，不动任何物品。
        if (from.getBlockEntity(pipe.posFrom()) instanceof OGESawbladeMountBlockEntity sourceMount) {
            transferExperience(sourceMount, to, pipe);
            return;
        }
        // 终点是锯片台：先把起点格里的经验球吸进去存好，再照常搬物品。
        if (to.getBlockEntity(pipe.posTo()) instanceof OGESawbladeMountBlockEntity destMount) {
            suckExperienceIntoMount(from, destMount, pipe);
        }

        IItemHandler source = OGEItemHandlerView.of(from.getCapability(Capabilities.Item.BLOCK, pipe.posFrom(), null));
        if (source != null) {
            transferFromContainer(from, to, pipe, source);
        } else {
            suckFromGround(from, to, pipe);
        }
    }

    /** 锯片台 → 管道：一次抽干它存着的全部经验；终点没有锯片台就在终点炸成经验球。 */
    private static void transferExperience(OGESawbladeMountBlockEntity source,
                                           ServerLevel to, OGELogisticsData.PipeLink pipe) {
        OGESawbladeMountBlockEntity dest = to.getBlockEntity(pipe.posTo())
                instanceof OGESawbladeMountBlockEntity mount ? mount : null;
        if (dest == source) {
            return;
        }
        int moved = source.extractExperience(source.getStoredExperience());
        if (moved <= 0) {
            return;
        }
        if (dest != null) {
            dest.addExperience(moved);
        } else {
            ExperienceOrb.award(to, Vec3.atCenterOf(pipe.posTo()), moved);
        }
    }

    /** 管道 → 锯片台：把起点格范围内漂浮的经验球整份收进存料仓。 */
    private static void suckExperienceIntoMount(ServerLevel from, OGESawbladeMountBlockEntity destMount,
                                                OGELogisticsData.PipeLink pipe) {
        AABB box = new AABB(pipe.posFrom()).inflate(0.125);
        List<ExperienceOrb> orbs = from.getEntitiesOfClass(ExperienceOrb.class, box, orb -> !orb.isRemoved());
        if (orbs.isEmpty()) {
            return;
        }
        int total = 0;
        for (ExperienceOrb orb : orbs) {
            total += orb.getValue();
            orb.discard();
        }
        if (total > 0) {
            destMount.addExperience(total);
        }
    }

    /**
     * 起点是容器：按槽位顺序取第一个有货的槽位，每刻抽 1 个物品。
     *
     * <p>终点是虚空抽屉（只收一种物品）时，跳过抽屉收不下的槽位，改取第一个
     * 合规格的物品；否则不合规的物品会永久占住管口，把后面合规的也堵死。
     * 其它终点维持“第一个非空格”。</p>
     */
    private static void transferFromContainer(ServerLevel from, ServerLevel to,
                                              OGELogisticsData.PipeLink pipe, IItemHandler source) {
        OGEVoidDrawerBlockEntity voidDrawer = voidDrawerAt(to, pipe.posTo());
        int slot = -1;
        ItemStack probe = null;
        for (int i = 0; i < source.getSlots(); ++i) {
            ItemStack stack = source.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (voidDrawer != null && !voidDrawer.canInsert(stack)) {
                continue;
            }
            slot = i;
            probe = stack.copyWithCount(1);
            break;
        }
        if (probe == null) {
            return;
        }
        // 先模拟终点接收，塞不进就整刻跳过，避免物品凭空消失
        if (!deliver(to, pipe.posTo(), probe, true).isEmpty()) {
            return;
        }
        ItemStack moving = source.extractItem(slot, 1, false);
        if (moving.isEmpty()) {
            return;
        }
        markDirty(from, pipe.posFrom());
        ItemStack rest = deliver(to, pipe.posTo(), moving, false);
        if (!rest.isEmpty()) {
            // 极端情况下（模拟后容器被同时改动）塞不进的原样退回起点
            source.insertItem(slot, rest, false);
        } else {
            markDirty(to, pipe.posTo());
        }
    }

    /** 起点不是容器：像放在它下面的漏斗，每刻吸取该位置上的一枚掉落物（整叠）。 */
    private static void suckFromGround(ServerLevel from, ServerLevel to, OGELogisticsData.PipeLink pipe) {
        AABB box = new AABB(pipe.posFrom()).inflate(PICKUP_INFLATE);
        List<ItemEntity> items = from.getEntitiesOfClass(ItemEntity.class, box,
                e -> !e.isRemoved() && !e.getItem().isEmpty());
        if (items.isEmpty()) {
            return;
        }
        // 终点是虚空抽屉时，从这一格里挑出抽屉收得下的那一叠，跳过不合规的掉落物。
        OGEVoidDrawerBlockEntity voidDrawer = voidDrawerAt(to, pipe.posTo());
        ItemEntity entity = null;
        ItemStack stack = ItemStack.EMPTY;
        int moved = 0;
        for (ItemEntity candidate : items) {
            ItemStack candidateStack = candidate.getItem();
            if (candidateStack.isEmpty()
                    || (voidDrawer != null && !voidDrawer.canInsert(candidateStack))) {
                continue;
            }
            // 先模拟终点接收，算出这叠能移走多少个；一个都塞不下就看下一叠
            ItemStack rejected = deliver(to, pipe.posTo(), candidateStack, true);
            int canMove = candidateStack.getCount() - rejected.getCount();
            if (canMove > 0) {
                entity = candidate;
                stack = candidateStack;
                moved = canMove;
                break;
            }
        }
        if (entity == null) {
            return;
        }
        ItemStack leftover = deliver(to, pipe.posTo(), stack.copyWithCount(moved), false);
        int remaining = stack.getCount() - moved + leftover.getCount();
        if (remaining <= 0) {
            entity.discard();
        } else {
            entity.setItem(stack.copyWithCount(remaining));
        }
        markDirty(to, pipe.posTo());
    }

    /** 终点方块是虚空抽屉时返回其方块实体，否则返回 null。 */
    private static OGEVoidDrawerBlockEntity voidDrawerAt(ServerLevel to, BlockPos pos) {
        return to.getBlockEntity(pos) instanceof OGEVoidDrawerBlockEntity drawer ? drawer : null;
    }

    /**
     * 把物品送往终点：终点是容器则按槽位塞入，返回塞不进的剩余（模拟时不真正改动）。
     * 终点不是容器则丢在方块正中心，返回空。
     */
    private static ItemStack deliver(ServerLevel to, BlockPos pos, ItemStack stack, boolean simulate) {
        IItemHandler dest = OGEItemHandlerView.of(to.getCapability(Capabilities.Item.BLOCK, pos, null));
        if (dest != null) {
            ItemStack rest = stack;
            for (int i = 0; i < dest.getSlots() && !rest.isEmpty(); ++i) {
                if (!dest.isItemValid(i, rest)) {
                    continue;
                }
                rest = dest.insertItem(i, rest, simulate);
            }
            return rest;
        }
        if (!simulate) {
            Vec3 center = Vec3.atCenterOf(pos);
            ItemEntity dropped = new ItemEntity(to, center.x, center.y, center.z, stack);
            dropped.setPickUpDelay(10);
            to.addFreshEntity(dropped);
        }
        return ItemStack.EMPTY;
    }

    private static void markDirty(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null) {
            be.setChanged();
        }
    }

    // ------------------------------------------------------------------
    // 同步
    // ------------------------------------------------------------------

    public static void syncTo(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, buildSyncPayload(player.level().getServer()));
    }

    public static void syncAll(MinecraftServer server) {
        PacketDistributor.sendToAllPlayers(buildSyncPayload(server));
    }

    private static OGELogisticsPipeSyncPayload buildSyncPayload(MinecraftServer server) {
        List<OGELogisticsPipeSyncPayload.PipeView> views = new ArrayList<>();
        for (OGELogisticsData.PipeLink pipe : OGELogisticsData.get(server).pipes()) {
            views.add(new OGELogisticsPipeSyncPayload.PipeView(
                    pipe.id(), pipe.dimFrom(), pipe.posFrom().asLong(), pipe.dimTo(), pipe.posTo().asLong()));
        }
        return new OGELogisticsPipeSyncPayload(views);
    }

    public static void syncSelection(ServerPlayer player) {
        Pending pending = PENDING.get(player.getUUID());
        if (pending == null) {
            PacketDistributor.sendToPlayer(player, new OGELogisticsSelectSyncPayload(false, null, 0L, false));
            return;
        }
        if (pending.posA() != null) {
            PacketDistributor.sendToPlayer(player,
                    new OGELogisticsSelectSyncPayload(true, pending.dimA(), pending.posA().asLong(), true));
        } else if (pending.posB() != null) {
            PacketDistributor.sendToPlayer(player,
                    new OGELogisticsSelectSyncPayload(true, pending.dimB(), pending.posB().asLong(), false));
        } else {
            PacketDistributor.sendToPlayer(player, new OGELogisticsSelectSyncPayload(false, null, 0L, false));
        }
    }

    public static void clearPending(UUID playerId) {
        PENDING.remove(playerId);
    }

    public static void clearPendingAll() {
        PENDING.clear();
    }

    /** 单个玩家的管道选点状态（起点=A，终点=B）。 */
    private record Pending(ResourceKey<Level> dimA, BlockPos posA,
                           ResourceKey<Level> dimB, BlockPos posB) {
        static final Pending EMPTY = new Pending(null, null, null, null);

        Pending with(boolean start, ResourceKey<Level> dim, BlockPos pos) {
            return start ? new Pending(dim, pos, this.dimB, this.posB)
                         : new Pending(this.dimA, this.posA, dim, pos);
        }
    }
}
