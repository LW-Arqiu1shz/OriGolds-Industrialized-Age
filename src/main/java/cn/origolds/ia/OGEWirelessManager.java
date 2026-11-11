package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 金源能量无线连接装置的服务端管理器。
 *
 * <p>负责两件事：
 * <ul>
 *   <li>无线连线的“选点—建线—删除”交互（Ctrl+左键选 A、Ctrl+右键选 B）；</li>
 *   <li>由连线构成的网络能量传输：每个游戏刻把同网络内发电机储存的 OGE
 *       注入该网络的电池，发电机之间不互传。</li>
 * </ul>
 */
public final class OGEWirelessManager {

    public static final double INTERACT_RANGE = 32.0;

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private OGEWirelessManager() {
    }

    // ------------------------------------------------------------------
    // 交互：选点建线 / 删除
    // ------------------------------------------------------------------

    public static void onSelect(ServerPlayer player, long packedPos, boolean start) {
        if (!OGEWirelessConnectorItemRef.isHeld(player)) {
            return;
        }
        BlockPos pos = BlockPos.of(packedPos);
        Level level = player.level();
        if (!level.isLoaded(pos) || !player.isWithinBlockInteractionRange(pos, INTERACT_RANGE)) {
            tell(player, "message.origolds_ia.wc_out_of_range");
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
            tell(player, "message.origolds_ia.wc_canceled");
            syncSelection(player);
            player.swing(hand(player), true);
            return;
        }

        Pending next = pending.with(start, level.dimension(), pos);
        if (next.posA() != null && next.posB() != null) {
            if (next.dimA().equals(next.dimB()) && next.posA().equals(next.posB())) {
                tell(player, "message.origolds_ia.wc_same_block");
                PENDING.put(id, next);
                syncSelection(player);
                return;
            }
            OGEWirelessData data = OGEWirelessData.get(player.level().getServer());
            if (data.hasLink(next.dimA(), next.posA(), next.dimB(), next.posB())) {
                tell(player, "message.origolds_ia.wc_link_exists");
                PENDING.remove(id);
                syncSelection(player);
                return;
            }
            OGEWirelessData.WirelessLink link = new OGEWirelessData.WirelessLink(
                    UUID.randomUUID(), next.dimA(), next.posA(), next.dimB(), next.posB());
            data.addLink(link);
            OGEWirelessHistory.record(id, OGEWirelessHistory.Op.energyAdded(link));
            PENDING.remove(id);
            tell(player, "message.origolds_ia.wc_link_created");
            syncAll(player.level().getServer());
            syncSelection(player);
        } else {
            PENDING.put(id, next);
            player.sendOverlayMessage(Component.translatable(
                    start ? "message.origolds_ia.wc_selected_a" : "message.origolds_ia.wc_selected_b",
                    pos.getX(), pos.getY(), pos.getZ()));
            syncSelection(player);
        }
        player.swing(hand(player), true);
    }

    public static void onDelete(ServerPlayer player, UUID id) {
        if (!OGEWirelessConnectorItemRef.isHeld(player)) {
            return;
        }
        OGEWirelessData data = OGEWirelessData.get(player.level().getServer());
        OGEWirelessData.WirelessLink link = data.link(id);
        if (link != null && !withinReach(player, link.dimA(), link.posA(), link.dimB(), link.posB())) {
            tell(player, "message.origolds_ia.wc_out_of_range");
            return;
        }
        if (data.removeLink(id)) {
            if (link != null) {
                OGEWirelessHistory.record(player.getUUID(), OGEWirelessHistory.Op.energyRemoved(link));
            }
            tell(player, "message.origolds_ia.wc_link_removed");
            syncAll(player.level().getServer());
        }
    }

    /**
     * Ctrl+Z / Ctrl+Y：撤销或重做最近一次装置操作。
     * 与父模组“线路显示模式”的同款交互；能量线路与物流管道共用一条操作时间线，
     * {@code scope} 决定可撤销的类别（见 {@link OGEWirelessHistory}）。
     */
    public static void onUndoRedo(ServerPlayer player, boolean undo, int scope) {
        if (!OGEWirelessConnectorItemRef.isHeld(player)) {
            return;
        }
        OGEWirelessHistory.apply(player, undo, scope);
    }

    // ------------------------------------------------------------------
    // 能量网络传输
    // ------------------------------------------------------------------

    public static void tick(MinecraftServer server) {
        OGEWirelessData data = OGEWirelessData.get(server);
        if (data.links().isEmpty()) {
            return;
        }
        // 连通分量只在连线增删时重建（见 ensureGraph），不再每刻重新建图
        ensureGraph(data);
        for (Set<Node> component : cachedComponents) {
            transfer(server, component);
        }
    }

    // ------------------------------------------------------------------
    // 连通分量缓存
    // ------------------------------------------------------------------

    /*
     * 性能：电网查询（availableOGE / consumeOGEIfAvailable）被每台机器每刻高频调用，
     * 旧实现每次调用都把全部连线重新建成邻接表再做一次深搜，机器数 × 连线数的开销，
     * 大型基地会明显拖慢服务端刻。现在按 OGEWirelessData 的 revision 缓存，
     * 只有连线增删（含撤销/重做）后才重建一次。仅在服务端主线程访问。
     */
    private static OGEWirelessData cachedData;
    private static int cachedRevision = -1;
    private static Map<Node, Set<Node>> cachedComponentOf = Map.of();
    private static List<Set<Node>> cachedComponents = List.of();

    private static void ensureGraph(OGEWirelessData data) {
        if (data == cachedData && data.revision() == cachedRevision) {
            return;
        }
        Map<Node, Set<Node>> adj = new HashMap<>();
        for (OGEWirelessData.WirelessLink link : data.links()) {
            Node a = new Node(link.dimA(), link.posA());
            Node b = new Node(link.dimB(), link.posB());
            adj.computeIfAbsent(a, k -> new HashSet<>()).add(b);
            adj.computeIfAbsent(b, k -> new HashSet<>()).add(a);
        }
        Map<Node, Set<Node>> componentOf = new HashMap<>();
        List<Set<Node>> components = new ArrayList<>();
        for (Node start : adj.keySet()) {
            if (componentOf.containsKey(start)) {
                continue;
            }
            Set<Node> component = new LinkedHashSet<>();
            Set<Node> seen = new HashSet<>();
            Deque<Node> stack = new ArrayDeque<>();
            stack.push(start);
            seen.add(start);
            while (!stack.isEmpty()) {
                Node cur = stack.pop();
                component.add(cur);
                for (Node nb : adj.getOrDefault(cur, Set.of())) {
                    if (seen.add(nb)) {
                        stack.push(nb);
                    }
                }
            }
            Set<Node> frozen = Collections.unmodifiableSet(component);
            for (Node node : frozen) {
                componentOf.put(node, frozen);
            }
            components.add(frozen);
        }
        cachedData = data;
        cachedRevision = data.revision();
        cachedComponentOf = componentOf;
        cachedComponents = List.copyOf(components);
    }

    /**
     * 把一个连通分量内的能量从发电机注入电池；发电机之间不互传。
     */
    private static void transfer(MinecraftServer server, Collection<Node> component) {
        // 先收集可接收能量且未装满的电池
        List<BatteryRef> batteries = new ArrayList<>();
        long remainingCap = 0;
        for (Node node : component) {
            ServerLevel level = server.getLevel(node.dim());
            if (level == null || !level.isLoaded(node.pos())) {
                continue;
            }
            IGoldSourceEnergyStorage storage = (IGoldSourceEnergyStorage)
                    level.getCapability(OGECapabilities.OGE_STORAGE, node.pos(), null);
            if (storage == null || storage.getOGEProduceRate() != 0 || storage.getOGECapacity() <= 0) {
                continue;
            }
            int cap = storage.getOGECapacity() - storage.getOGEStored();
            if (cap > 0) {
                batteries.add(new BatteryRef(node, storage));
                remainingCap += cap;
            }
        }
        if (batteries.isEmpty()) {
            return;
        }
        Set<Node> dirty = new HashSet<>();
        // 再让每个发电机把存储的 OGE 注入电池
        for (Node node : component) {
            if (remainingCap <= 0) {
                break;
            }
            ServerLevel level = server.getLevel(node.dim());
            if (level == null || !level.isLoaded(node.pos())) {
                continue;
            }
            IGoldSourceEnergyStorage storage = (IGoldSourceEnergyStorage)
                    level.getCapability(OGECapabilities.OGE_STORAGE, node.pos(), null);
            if (storage == null || storage.getOGEProduceRate() <= 0) {
                continue;
            }
            int stored = storage.getOGEStored();
            if (stored <= 0) {
                continue;
            }
            int take = (int) Math.min(stored, remainingCap);
            int extracted = storage.extractOGE(take, false);
            int left = extracted;
            for (BatteryRef battery : batteries) {
                if (left <= 0) {
                    break;
                }
                int put = battery.storage().insertOGE(left, false);
                left -= put;
                remainingCap -= put;
                if (put > 0) {
                    dirty.add(battery.node());
                }
            }
            // 电池容量不足以全部吸收时，把剩余的 OGE 退回发电机缓存，防止凭空丢失。
            if (left > 0) {
                storage.insertOGE(left, false);
            }
            if (extracted > 0) {
                dirty.add(node);
            }
        }
        for (Node node : dirty) {
            ServerLevel level = server.getLevel(node.dim());
            if (level == null) {
                continue;
            }
            BlockEntity be = level.getBlockEntity(node.pos());
            if (be != null) {
                be.setChanged();
            }
        }
    }

    // ------------------------------------------------------------------
    // 电网能量查询 / 消耗（供金源伐木模块等节点使用）
    // ------------------------------------------------------------------

    /**
     * 统计 {@code (dim, pos)} 所在无线电网连通分量内，所有储能节点当前可提取的
     * OGE 总和（即“电网中可以被使用的 OGE”）。节点本身无储能能力时贡献为 0。
     */
    public static int availableOGE(MinecraftServer server, ResourceKey<Level> dim, BlockPos pos) {
        int total = 0;
        for (Node node : componentOf(server, dim, pos)) {
            ServerLevel level = server.getLevel(node.dim());
            if (level == null || !level.isLoaded(node.pos())) {
                continue;
            }
            IGoldSourceEnergyStorage storage = (IGoldSourceEnergyStorage)
                    level.getCapability(OGECapabilities.OGE_STORAGE, node.pos(), null);
            if (storage != null) {
                total += storage.getOGEStored();
            }
        }
        return total;
    }

    /**
     * 从 {@code (dim, pos)} 所在无线电网连通分量内的储能节点中提取 {@code amount} 单位 OGE，
     * 返回实际提取量。提取后标记相关方块实体变化以便客户端同步。
     */
    public static int consumeOGE(MinecraftServer server, ResourceKey<Level> dim, BlockPos pos, int amount) {
        if (amount <= 0) {
            return 0;
        }
        int left = amount;
        for (Node node : componentOf(server, dim, pos)) {
            if (left <= 0) {
                break;
            }
            ServerLevel level = server.getLevel(node.dim());
            if (level == null || !level.isLoaded(node.pos())) {
                continue;
            }
            IGoldSourceEnergyStorage storage = (IGoldSourceEnergyStorage)
                    level.getCapability(OGECapabilities.OGE_STORAGE, node.pos(), null);
            if (storage == null) {
                continue;
            }
            int stored = storage.getOGEStored();
            if (stored <= 0) {
                continue;
            }
            int extracted = storage.extractOGE(Math.min(left, stored), false);
            left -= extracted;
            if (extracted > 0) {
                BlockEntity be = level.getBlockEntity(node.pos());
                if (be != null) {
                    be.setChanged();
                }
            }
        }
        return amount - left;
    }

    /**
     * 从 {@code (dim, pos)} 所在无线电网连通分量内的储能节点（含电池）中原子地
     * 提取 {@code amount} 单位 OGE：先逐个模拟统计可提取总量，足够才真正扣除，
     * 不足则分文不动，避免“扣了电却没干活”。一次构图完成查询与扣除，
     * 供采掘/伐木模块每 tick 高频调用。扣除后把相关方块的最新数值推送客户端。
     *
     * @return 实际提取量（等于 {@code amount} 表示成功，0 表示电网能量不足）。
     */
    public static int consumeOGEIfAvailable(MinecraftServer server, ResourceKey<Level> dim, BlockPos pos, int amount) {
        if (amount <= 0) {
            return 0;
        }
        // 1. 收集分量内可模拟提取的储能节点（电池优先：优先动用已储存到电池的电）。
        //    能力只查一次并复用，旧实现同一节点要查三遍能力。
        List<BatteryRef> batteries = new ArrayList<>();
        List<BatteryRef> generators = new ArrayList<>();
        for (Node node : componentOf(server, dim, pos)) {
            IGoldSourceEnergyStorage storage = storageOf(server, node);
            if (storage == null || storage.getOGEStored() <= 0) {
                continue;
            }
            if (storage.getOGEProduceRate() > 0) {
                generators.add(new BatteryRef(node, storage));
            } else {
                batteries.add(new BatteryRef(node, storage));
            }
        }
        if (batteries.isEmpty() && generators.isEmpty()) {
            return 0;
        }
        List<BatteryRef> ordered = new ArrayList<>(batteries.size() + generators.size());
        ordered.addAll(batteries);
        ordered.addAll(generators);

        // 2. 先模拟：总量不足直接放弃，保证“要么全扣要么不扣”
        int extractable = 0;
        for (BatteryRef ref : ordered) {
            extractable += ref.storage().extractOGE(amount, true);
            if (extractable >= amount) {
                break;
            }
        }
        if (extractable < amount) {
            return 0;
        }

        // 3. 真正扣除并同步客户端数值
        int left = amount;
        for (BatteryRef ref : ordered) {
            if (left <= 0) {
                break;
            }
            int extracted = ref.storage().extractOGE(left, false);
            if (extracted > 0) {
                left -= extracted;
                ServerLevel level = server.getLevel(ref.node().dim());
                BlockEntity be = level == null ? null : level.getBlockEntity(ref.node().pos());
                if (be != null) {
                    be.setChanged();
                    level.sendBlockUpdated(ref.node().pos(), be.getBlockState(), be.getBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        return amount - left;
    }

    /** 取某节点上的 OGE 存储能力；不在本模组机器上或区块未加载时返回 null。 */
    private static IGoldSourceEnergyStorage storageOf(MinecraftServer server, Node node) {
        ServerLevel level = server.getLevel(node.dim());
        if (level == null || !level.isLoaded(node.pos())) {
            return null;
        }
        return (IGoldSourceEnergyStorage) level.getCapability(OGECapabilities.OGE_STORAGE, node.pos(), null);
    }

    /**
     * 计算 {@code (dim, pos)} 在无线连线图中所在的连通分量。若没有任何连线，
     * 则分量只包含自身。
     */
    private static Set<Node> componentOf(MinecraftServer server, ResourceKey<Level> dim, BlockPos pos) {
        OGEWirelessData data = OGEWirelessData.get(server);
        Node start = new Node(dim, pos);
        if (data.links().isEmpty()) {
            return Set.of(start);
        }
        ensureGraph(data);
        Set<Node> component = cachedComponentOf.get(start);
        return component != null ? component : Set.of(start);
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

    public static void syncSelection(ServerPlayer player) {
        Pending pending = PENDING.get(player.getUUID());
        if (pending == null) {
            PacketDistributor.sendToPlayer(player, new OGEWirelessSelectSyncPayload(false, null, 0L, false));
            return;
        }
        if (pending.posA() != null) {
            PacketDistributor.sendToPlayer(player,
                    new OGEWirelessSelectSyncPayload(true, pending.dimA(), pending.posA().asLong(), true));
        } else if (pending.posB() != null) {
            PacketDistributor.sendToPlayer(player,
                    new OGEWirelessSelectSyncPayload(true, pending.dimB(), pending.posB().asLong(), false));
        } else {
            PacketDistributor.sendToPlayer(player, new OGEWirelessSelectSyncPayload(false, null, 0L, false));
        }
    }

    public static void clearPending(UUID playerId) {
        PENDING.remove(playerId);
    }

    public static void clearPendingAll() {
        PENDING.clear();
    }

    private static OGEWirelessLinkSyncPayload buildSyncPayload(MinecraftServer server) {
        List<OGEWirelessLinkSyncPayload.LinkView> views = new ArrayList<>();
        for (OGEWirelessData.WirelessLink link : OGEWirelessData.get(server).links()) {
            views.add(new OGEWirelessLinkSyncPayload.LinkView(
                    link.id(), link.dimA(), link.posA().asLong(), link.dimB(), link.posB().asLong()));
        }
        return new OGEWirelessLinkSyncPayload(views);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 玩家眼睛到连线（或管道）线段的距离是否在允许范围内（两端均需与玩家同维度）。 */
    static boolean withinReach(ServerPlayer player, ResourceKey<Level> dimA, BlockPos posA,
                               ResourceKey<Level> dimB, BlockPos posB) {
        if (!player.level().dimension().equals(dimA) || !player.level().dimension().equals(dimB)) {
            return false;
        }
        net.minecraft.world.phys.Vec3 eye = player.getEyePosition();
        net.minecraft.world.phys.Vec3 a = net.minecraft.world.phys.Vec3.atCenterOf(posA);
        net.minecraft.world.phys.Vec3 b = net.minecraft.world.phys.Vec3.atCenterOf(posB);
        return distSqToSegment(eye, a, b) <= 1024.0;
    }

    private static double distSqToSegment(net.minecraft.world.phys.Vec3 p, net.minecraft.world.phys.Vec3 a, net.minecraft.world.phys.Vec3 b) {
        net.minecraft.world.phys.Vec3 ab = b.subtract(a);
        double lenSq = ab.lengthSqr();
        if (lenSq < 1.0E-6) {
            return p.distanceToSqr(a);
        }
        double t = p.subtract(a).dot(ab) / lenSq;
        t = Math.max(0.0, Math.min(1.0, t));
        net.minecraft.world.phys.Vec3 c = a.add(ab.x * t, ab.y * t, ab.z * t);
        return p.distanceToSqr(c);
    }

    static InteractionHand hand(ServerPlayer player) {
        return player.getMainHandItem().is(OGEWirelessConnectorItemRef.get())
                ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
    }

    static void tell(ServerPlayer player, String key) {
        player.sendOverlayMessage(Component.translatable(key));
    }

    /** 连通分量中的节点键。 */
    private record Node(ResourceKey<Level> dim, BlockPos pos) {
    }

    /** 电池节点及其存储引用。 */
    private record BatteryRef(Node node, IGoldSourceEnergyStorage storage) {
    }

    /** 单个玩家的选点状态。 */
    private record Pending(ResourceKey<Level> dimA, BlockPos posA,
                           ResourceKey<Level> dimB, BlockPos posB) {
        static final Pending EMPTY = new Pending(null, null, null, null);

        Pending with(boolean start, ResourceKey<Level> dim, BlockPos pos) {
            return start ? new Pending(dim, pos, this.dimB, this.posB)
                         : new Pending(this.dimA, this.posA, dim, pos);
        }
    }
}
