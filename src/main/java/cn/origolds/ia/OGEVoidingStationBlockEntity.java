package cn.origolds.ia;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * 金源销毁站（Origold Voiding Station）的方块实体。
 *
 * <p>服务端每刻只看自身正上方的那一格，并分别处理两类目标：</p>
 *
 * <ul>
 *   <li><b>容器里的物品</b>（末影箱除外）：按“物品 ID + 名字”归类，
 *       每个种类独立计时。只要这一格容器里该类物品的总数<b>没有减少</b>，
 *       计时就 +1；一旦发现它在减少（总数下降，或在总数上升的同时有格子少了
 *       该类物品，说明正在被抽取），计时清零。计时达到模式时间就销毁容器里
 *       全部该类物品——所以一箱子破损的金剑会被一次清空。</li>
 *   <li><b>掉落物</b>：每一堆掉落物各自独立计时，只要它还停在正上方那格里，
 *       计时就 +1；离开或被捡走则清零。计时达到模式时间就整堆销毁。</li>
 * </ul>
 *
 * <p>计时只保存在内存里，区块卸载后重新计时，不写入 NBT。该方块不耗能、
 * 也没有界面。</p>
 */
public final class OGEVoidingStationBlockEntity extends BlockEntity {

    /** 每个“种类”的计时状态：上次看到的总数与连续未减少的刻数。 */
    private static final class TypeTimer {
        int lastTotal;
        int timer;

        TypeTimer(int lastTotal) {
            this.lastTotal = lastTotal;
        }
    }

    /**
     * 容器物品的归类键：只看物品 ID 与“名字”（自定义名 / 物品名，都没有就是默认名），
     * 不看耐久、附魔等组件，这样“一箱子破损的金剑”会被当作同一类一起销毁。
     * 直接取名字组件而不是 {@code getHoverName()}，是为了避免每刻为无名字的物品
     * 新建可翻译文本组件——放在大容器下面时这点开销很可观。
     */
    private record ItemKey(Item item, Component customName, Component itemName) {
        static ItemKey of(ItemStack stack) {
            return new ItemKey(stack.getItem(),
                    stack.get(DataComponents.CUSTOM_NAME),
                    stack.get(DataComponents.ITEM_NAME));
        }
    }

    /** 容器里各物品种类的计时状态。 */
    private final Map<ItemKey, TypeTimer> trackedTypes = new HashMap<>();

    /** 每个掉落物（按实体 UUID）的连续停留刻数。 */
    private final Map<UUID, Integer> dropTimers = new HashMap<>();

    /** 上一刻容器每格的归类键与数量，用于判断“这一格是不是少了东西”。 */
    private ItemKey[] prevKeys = new ItemKey[0];
    private int[] prevCounts = new int[0];

    public OGEVoidingStationBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.OGE_VOIDING_STATION.get(), pos, state);
    }

    /** 服务端每 tick 调用（方块 ticker 驱动）。 */
    public void tick() {
        if (level == null || level.isClientSide()) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockPos target = worldPosition.above();
        if (!serverLevel.isLoaded(target)) {
            return;
        }
        int ticks = OGEVoidingStationBlock.modeTicks(getBlockState().getValue(OGEVoidingStationBlock.MODE));
        tickContainer(serverLevel, target, ticks);
        tickDrops(serverLevel, target, ticks);
    }

    // ------------------------------------------------------------------
    // 容器物品：按种类计时
    // ------------------------------------------------------------------

    private void tickContainer(ServerLevel serverLevel, BlockPos target, int ticks) {
        // 末影箱内容物归属玩家且随人变动，跳过以保证安全（与各取料机器一致）。
        Container container = OGEFaceItems.containerAt(serverLevel, target);
        if (container == null) {
            return;
        }
        int size = container.getContainerSize();
        if (size <= 0) {
            clearContainerTracking();
            return;
        }

        // 本刻快照：每格的归类键与数量，并顺带汇总每个种类的总数。
        ItemKey[] nowKeys = new ItemKey[size];
        int[] nowCounts = new int[size];
        Map<ItemKey, Integer> totals = new HashMap<>();
        for (int i = 0; i < size; i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            ItemKey key = ItemKey.of(stack);
            nowKeys[i] = key;
            nowCounts[i] = stack.getCount();
            totals.merge(key, stack.getCount(), Integer::sum);
        }

        // 与上一刻逐格对比：某个格子少了某类物品，说明该类正在被抽取。
        Set<ItemKey> drainedKeys = new HashSet<>();
        int prevLength = prevKeys.length;
        for (int i = 0; i < prevLength; i++) {
            ItemKey prevKey = prevKeys[i];
            if (prevKey == null) {
                continue;
            }
            int nowCount = (i < size && prevKey.equals(nowKeys[i])) ? nowCounts[i] : 0;
            if (nowCount < prevCounts[i]) {
                drainedKeys.add(prevKey);
            }
        }

        // 逐个种类推进计时；总数超过上一刻且没有格子变少，才算“稳定”。
        Set<ItemKey> keys = new HashSet<>(trackedTypes.keySet());
        keys.addAll(totals.keySet());
        for (ItemKey key : keys) {
            int totalNow = totals.getOrDefault(key, 0);
            if (totalNow <= 0) {
                trackedTypes.remove(key);
                continue;
            }
            TypeTimer tracker = trackedTypes.get(key);
            if (tracker == null) {
                trackedTypes.put(key, new TypeTimer(totalNow));
                continue;
            }
            boolean drained = totalNow < tracker.lastTotal
                    || (totalNow > tracker.lastTotal && drainedKeys.contains(key));
            if (drained) {
                tracker.timer = 0;
            } else {
                tracker.timer++;
            }
            if (tracker.timer >= ticks) {
                destroyType(container, key);
                trackedTypes.remove(key);
                continue;
            }
            tracker.lastTotal = totalNow;
        }

        prevKeys = nowKeys;
        prevCounts = nowCounts;
    }

    /** 销毁容器里所有属于 {@code key} 的物品（同 ID、同名字的全部格子）。 */
    private void destroyType(Container container, ItemKey key) {
        int size = container.getContainerSize();
        boolean removed = false;
        for (int i = 0; i < size; i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && ItemKey.of(stack).equals(key)) {
                container.setItem(i, ItemStack.EMPTY);
                removed = true;
            }
        }
        if (removed) {
            container.setChanged();
            setChanged();
        }
    }

    /** 正上方不再是容器时，清掉容器相关的计时状态。 */
    private void clearContainerTracking() {
        trackedTypes.clear();
        if (prevKeys.length != 0) {
            prevKeys = new ItemKey[0];
            prevCounts = new int[0];
        }
    }

    // ------------------------------------------------------------------
    // 掉落物：每堆独立计时
    // ------------------------------------------------------------------

    private void tickDrops(ServerLevel serverLevel, BlockPos target, int ticks) {
        AABB area = new AABB(target);
        List<ItemEntity> drops = serverLevel.getEntitiesOfClass(ItemEntity.class, area);
        if (drops.isEmpty() && dropTimers.isEmpty()) {
            return;
        }
        Set<UUID> seen = new HashSet<>();
        for (ItemEntity drop : drops) {
            if (drop.isRemoved() || drop.getItem().isEmpty()) {
                continue;
            }
            UUID id = drop.getUUID();
            seen.add(id);
            int timer = dropTimers.merge(id, 1, Integer::sum);
            if (timer >= ticks) {
                drop.discard();
                dropTimers.remove(id);
            }
        }
        // 只保留本刻仍停在原位的掉落物，离开或被捡走的计时清零。
        dropTimers.keySet().removeIf(id -> !seen.contains(id));
    }
}
