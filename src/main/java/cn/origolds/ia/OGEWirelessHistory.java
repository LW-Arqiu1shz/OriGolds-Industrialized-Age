package cn.origolds.ia;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 金源无线连接装置的操作历史（服务端，按玩家，撤销/重做共用一条时间线）。
 *
 * <p>与父模组（origolds_ar）“线路显示模式”的同款交互：Ctrl+Z 撤销上一步、
 * Ctrl+Y 重做。金源能量线路（建线/删线）与无线物流管道（建线/删线/反转方向）
 * 记录在同一条 undo 栈上，各自维护 redo 栈；任何新操作都会清空 redo 栈，
 * 栈深上限 {@value #LIMIT}。</p>
 *
 * <p>撤销时可按类别过滤：只开能量显示模式时仅撤销能量线路操作，
 * 只开物流显示模式时仅撤销管道操作，两者都开时撤销最近一次操作。</p>
 */
public final class OGEWirelessHistory {

    public static final int LIMIT = 16;

    /** 撤销范围：金源能量线路。 */
    public static final int SCOPE_ENERGY = 0;
    /** 撤销范围：无线物流管道。 */
    public static final int SCOPE_LOGISTICS = 1;
    /** 撤销范围：不分类别，取最近一次操作。 */
    public static final int SCOPE_ANY = 2;

    /** 操作类型。 */
    public enum Kind {
        /** 新增金源能量无线线路 */
        ADDED,
        /** 删除金源能量无线线路 */
        REMOVED,
        /** 新增单向无线物流管道 */
        PIPE_ADDED,
        /** 删除无线物流管道 */
        PIPE_REMOVED,
        /** 反转无线物流管道方向 */
        PIPE_REVERSED
    }

    /**
     * 一次操作。不同类别使用不同的快照字段：
     * 能量操作用 {@code link}；管道新增/删除用 {@code pipe}；
     * 管道反转用 {@code pipe}（反转后的样子）与 {@code pipeBefore}（反转前的样子）。
     */
    public record Op(Kind kind, UUID linkId,
                     OGEWirelessData.WirelessLink link,
                     OGELogisticsData.PipeLink pipe,
                     OGELogisticsData.PipeLink pipeBefore) {

        public static Op energyAdded(OGEWirelessData.WirelessLink link) {
            return new Op(Kind.ADDED, link.id(), link, null, null);
        }

        public static Op energyRemoved(OGEWirelessData.WirelessLink link) {
            return new Op(Kind.REMOVED, link.id(), link, null, null);
        }

        public static Op pipeAdded(OGELogisticsData.PipeLink pipe) {
            return new Op(Kind.PIPE_ADDED, pipe.id(), null, pipe, null);
        }

        public static Op pipeRemoved(OGELogisticsData.PipeLink pipe) {
            return new Op(Kind.PIPE_REMOVED, pipe.id(), null, pipe, null);
        }

        public static Op pipeReversed(OGELogisticsData.PipeLink before, OGELogisticsData.PipeLink after) {
            return new Op(Kind.PIPE_REVERSED, after.id(), null, after, before);
        }

        /** 该操作属于无线物流管道（否则属于金源能量线路）。 */
        public boolean isPipe() {
            return kind == Kind.PIPE_ADDED || kind == Kind.PIPE_REMOVED || kind == Kind.PIPE_REVERSED;
        }

        /** 撤销/重做完成后的操作描述语言键。 */
        String descriptionKey() {
            return switch (kind) {
                case ADDED -> "message.origolds_ia.op_add_link";
                case REMOVED -> "message.origolds_ia.op_remove_link";
                case PIPE_ADDED -> "message.origolds_ia.op_add_pipe";
                case PIPE_REMOVED -> "message.origolds_ia.op_remove_pipe";
                case PIPE_REVERSED -> "message.origolds_ia.op_reverse_pipe";
            };
        }
    }

    private static final Map<UUID, Deque<Op>> UNDO = new ConcurrentHashMap<>();
    private static final Map<UUID, Deque<Op>> REDO = new ConcurrentHashMap<>();

    private OGEWirelessHistory() {
    }

    /** 记录一次新操作：压入 undo 栈并清空该玩家的 redo 栈。 */
    public static void record(UUID player, Op op) {
        Deque<Op> stack = UNDO.computeIfAbsent(player, k -> new ArrayDeque<>());
        while (stack.size() >= LIMIT) {
            stack.removeFirst();
        }
        stack.addLast(op);
        REDO.remove(player);
    }

    /**
     * 应用一次撤销/重做（服务端）。
     *
     * @param undo  {@code true} 撤销，{@code false} 重做
     * @param scope {@link #SCOPE_ENERGY}／{@link #SCOPE_LOGISTICS}／{@link #SCOPE_ANY}
     */
    public static void apply(ServerPlayer player, boolean undo, int scope) {
        UUID id = player.getUUID();
        Op op = undo ? move(UNDO, REDO, id, scope) : move(REDO, UNDO, id, scope);
        if (op == null && scope != SCOPE_ANY) {
            // 能量与物流共用同一条操作时间线：当前类别没有可撤销/重做的操作时，
            // 回退到时间线上最近一条（避免“电线模式里撤销不了刚建的管道”这类假失败）。
            op = undo ? move(UNDO, REDO, id, SCOPE_ANY) : move(REDO, UNDO, id, SCOPE_ANY);
        }
        if (op == null) {
            tell(player, undo ? "message.origolds_ia.undo_nothing" : "message.origolds_ia.redo_nothing");
            return;
        }
        boolean energy = !op.isPipe();
        OGEWirelessData wire = energy ? OGEWirelessData.get(player.getServer()) : null;
        OGELogisticsData logistics = energy ? null : OGELogisticsData.get(player.getServer());
        // 撤销=反向执行、重做=正向执行
        switch (op.kind()) {
            case ADDED -> {
                if (undo) {
                    wire.removeLink(op.linkId());
                } else if (wire.link(op.linkId()) == null
                        && !wire.hasLink(op.link().dimA(), op.link().posA(), op.link().dimB(), op.link().posB())) {
                    wire.addLink(op.link());
                }
            }
            case REMOVED -> {
                if (!undo) {
                    wire.removeLink(op.linkId());
                } else if (wire.link(op.linkId()) == null
                        && !wire.hasLink(op.link().dimA(), op.link().posA(), op.link().dimB(), op.link().posB())) {
                    wire.addLink(op.link());
                }
            }
            case PIPE_ADDED -> {
                if (undo) {
                    logistics.removePipe(op.linkId());
                } else if (logistics.pipe(op.linkId()) == null) {
                    logistics.addPipe(op.pipe());
                }
            }
            case PIPE_REMOVED -> {
                if (!undo) {
                    logistics.removePipe(op.linkId());
                } else if (logistics.pipe(op.linkId()) == null) {
                    logistics.addPipe(op.pipe());
                }
            }
            case PIPE_REVERSED -> {
                OGELogisticsData.PipeLink target = undo ? op.pipeBefore() : op.pipe();
                if (logistics.pipe(op.linkId()) != null) {
                    logistics.replacePipe(target);
                }
            }
        }
        tell(player, undo ? "message.origolds_ia.undo_done" : "message.origolds_ia.redo_done",
                op.descriptionKey());
        if (energy) {
            OGEWirelessManager.syncAll(player.getServer());
        } else {
            OGELogisticsManager.syncAll(player.getServer());
        }
    }

    /** 从一侧栈里弹出最近一条符合 scope 的操作，压入另一侧栈。 */
    private static Op move(Map<UUID, Deque<Op>> from, Map<UUID, Deque<Op>> to, UUID player, int scope) {
        Deque<Op> src = from.get(player);
        if (src == null || src.isEmpty()) {
            return null;
        }
        Op picked = null;
        for (Iterator<Op> it = src.descendingIterator(); it.hasNext(); ) {
            Op candidate = it.next();
            // scope 分类匹配：SCOPE_LOGISTICS 只要管道操作，SCOPE_ENERGY 只要能量操作，SCOPE_ANY 取最近一条
            if (scopeMatch(scope, candidate)) {
                picked = candidate;
                it.remove();
                break;
            }
        }
        if (picked == null) {
            return null;
        }
        Deque<Op> dst = to.computeIfAbsent(player, k -> new ArrayDeque<>());
        while (dst.size() >= LIMIT) {
            dst.removeFirst();
        }
        dst.addLast(picked);
        return picked;
    }

    private static boolean scopeMatch(int scope, Op op) {
        return scope == SCOPE_ANY || (scope == SCOPE_LOGISTICS) == op.isPipe();
    }

    /** 玩家退出时清掉其历史。 */
    public static void clearPlayer(UUID player) {
        UNDO.remove(player);
        REDO.remove(player);
    }

    public static void clearAll() {
        UNDO.clear();
        REDO.clear();
        MESSAGES.clear();
    }

    // ------------------------------------------------------------------
    // 延迟播报：父模组（origolds_ar）对 Ctrl+Z/Ctrl+Y 的失败提示文案
    // 与本模组历史版本完全相同（“当前手持没有可撤销的操作”），且它无条件
    // 发送自己的撤销包、消息与本模组的落在同一条动作栏上。把本模组的撤销
    // 结果延后一个游戏刻播报，父模组的消息先到、我们的后到并覆盖，
    // 动作栏最终显示的一定是本模组的真实结果。
    // ------------------------------------------------------------------

    /** 一条待播报消息：uuid + 组件 + 最早发送刻。 */
    private record DeferredMessage(UUID player, net.minecraft.network.chat.Component message, long fireTick) {
    }

    private static final java.util.Deque<DeferredMessage> MESSAGES = new java.util.ArrayDeque<>();

    /** 每个服务器刻（{@code ServerTickEvent.Post}）调用：发出到期消息。 */
    public static void drainMessages(MinecraftServer server) {
        if (MESSAGES.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        java.util.Iterator<DeferredMessage> it = MESSAGES.iterator();
        while (it.hasNext()) {
            DeferredMessage m = it.next();
            if (m.fireTick() <= now) {
                ServerPlayer player = server.getPlayerList().getPlayer(m.player());
                if (player != null) {
                    player.displayClientMessage(m.message(), true);
                }
                it.remove();
            }
        }
    }

    private static void tell(ServerPlayer player, String key, String... argKeys) {
        net.minecraft.network.chat.Component message;
        if (argKeys.length == 0) {
            message = net.minecraft.network.chat.Component.translatable(key);
        } else {
            message = net.minecraft.network.chat.Component.translatable(key,
                    net.minecraft.network.chat.Component.translatable(argKeys[0]));
        }
        MinecraftServer server = player.getServer();
        if (server != null) {
            MESSAGES.add(new DeferredMessage(player.getUUID(), message, server.getTickCount() + 1L));
        } else {
            player.displayClientMessage(message, true);
        }
    }
}
