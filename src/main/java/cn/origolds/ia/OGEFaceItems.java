package cn.origolds.ia;

import java.util.function.BiFunction;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * “从方块的某个面取物品”的公共实现。
 *
 * <p>本模组里大量机器都要从相邻格取料（粉碎机、筛洗机、坷垃机、放置器……），
 * 取料规则完全一致：<b>优先取该格容器里的物品，容器不存在或这里不是容器时，
 * 才取漂浮在该格空间里的掉落物</b>；末影箱的库存归属玩家、内容随人变动，
 * 一律跳过以保证安全。</p>
 *
 * <p>把这段规则集中到这里有两个好处：</p>
 * <ul>
 *   <li><b>省一次扫描</b>：{@link Found} 既是“查到了什么”的结果，也是“取走它”的
 *       句柄。此前放置器、坷垃机都要先 has 扫一遍、再 take 扫一遍；现在一次扫描
 *       同时给出结果与取料句柄，取料目标是同一份物料，不会因为两次扫描之间
 *       世界被改动而取错东西。</li>
 *   <li><b>规则只改一处</b>：扫描顺序、末影箱例外、容器与掉落物的优先级，
 *       以后调整都只动这一个文件。</li>
 * </ul>
 *
 * <p>约定：{@code matcher} 返回 {@code null} 表示“这份物料不合要求，继续找下一份”，
 * 返回非 null 即命中，该返回值原样放进 {@link Found#value()}。查找是纯查询，
 * 不取走任何东西；只有显式调用 {@link Found#take(int)} 才会改动世界。</p>
 */
public final class OGEFaceItems {

    /** 默认取料面顺序：北、东、南、西四个水平侧面 + 底下。 */
    public static final Direction[] SIDES_AND_BELOW = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.DOWN
    };

    private OGEFaceItems() {
    }

    /**
     * 一次命中：{@code value} 是 {@code matcher} 给出的判定结果，
     * 内部同时握住取料句柄，{@link #take(int)} 取走的正是这次命中的那一份。
     *
     * @param <T> matcher 的返回值类型
     */
    public static final class Found<T> {

        private final T value;
        private final Container container; // 非 null：来自容器槽位
        private final int slot;
        private final ItemEntity entity;   // 非 null：来自地面掉落物

        private Found(T value, Container container, int slot, ItemEntity entity) {
            this.value = value;
            this.container = container;
            this.slot = slot;
            this.entity = entity;
        }

        /** matcher 在这次命中的物料上算出的结果。 */
        public T value() {
            return value;
        }

        /**
         * 取走 {@code amount} 个并返回实际取走的堆；取不到返回空堆。
         * 容器槽位走 {@link Container#removeItem}（内部会 setChanged 并同步观察者），
         * 掉落物则缩减堆叠、变空时移除实体。
         */
        public ItemStack take(int amount) {
            if (container != null) {
                return container.removeItem(slot, amount);
            }
            ItemStack stack = entity.getItem();
            ItemStack taken = stack.split(amount);
            if (stack.isEmpty()) {
                entity.discard();
            } else {
                entity.setItem(stack);
            }
            return taken;
        }
    }

    /**
     * 在单个格子 {@code pos} 里查找第一份满足 {@code matcher} 的物料。
     * 该格是容器则只在容器槽位里找；否则找该格空间里的掉落物。
     * 位置未加载直接返回 {@code null}。
     */
    @Nullable
    public static <T> Found<T> findAt(Level level, BlockPos pos, BiFunction<BlockPos, ItemStack, T> matcher) {
        if (!level.isLoaded(pos)) {
            return null;
        }
        Container container = containerAt(level, pos);
        if (container != null) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                if (!suckable(container, slot)) {
                    continue;
                }
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                T value = matcher.apply(pos, stack);
                if (value != null) {
                    return new Found<>(value, container, slot, null);
                }
            }
            return null;
        }
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, new AABB(pos))) {
            if (entity.isRemoved()) {
                continue;
            }
            ItemStack stack = entity.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            T value = matcher.apply(pos, stack);
            if (value != null) {
                return new Found<>(value, null, -1, entity);
            }
        }
        return null;
    }

    /**
     * 只在该格空间里查找掉落物（{@link ItemEntity}），<b>完全不看容器槽位</b>。
     *
     * <p>规则与 {@link #findAt} 的“容器优先”不同：需要把同一格里的容器与掉落物分开处理时
     * 用这个入口（金源超级漏斗就是如此——容器按间隔取、掉落物一次全部吸走）。
     * 位置未加载直接返回 {@code null}。</p>
     */
    @Nullable
    public static <T> Found<T> findDroppedAt(Level level, BlockPos pos, BiFunction<BlockPos, ItemStack, T> matcher) {
        if (!level.isLoaded(pos)) {
            return null;
        }
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, new AABB(pos))) {
            if (entity.isRemoved()) {
                continue;
            }
            ItemStack stack = entity.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            T value = matcher.apply(pos, stack);
            if (value != null) {
                return new Found<>(value, null, -1, entity);
            }
        }
        return null;
    }

    /**
     * 统计单个格子里符合条件的物品总数（只查不改），规则与 {@link #findAt} 完全相同：
     * 容器优先、末影箱跳过、没有容器时才看掉落物。用于“先确认够不够、
     * 再决定要不要扣”的两段式取料（如坷垃机一次要取好几个骨粉）。
     */
    public static int countAt(Level level, BlockPos pos, Predicate<ItemStack> filter) {
        if (!level.isLoaded(pos)) {
            return 0;
        }
        int total = 0;
        Container container = containerAt(level, pos);
        if (container != null) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                if (!suckable(container, slot)) {
                    continue;
                }
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty() && filter.test(stack)) {
                    total += stack.getCount();
                }
            }
            return total;
        }
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, new AABB(pos))) {
            if (entity.isRemoved()) {
                continue;
            }
            ItemStack stack = entity.getItem();
            if (!stack.isEmpty() && filter.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * 从单个格子里取走至多 {@code amount} 个符合条件的物品，返回实际取走的数量。
     * 可以跨多个容器槽位或掉落物累加，取不满就返回实际数量。调用前应先用
     * {@link #countAt} 确认总量，避免扣了电却拿不到料。
     */
    public static int takeUpToAt(Level level, BlockPos pos, Predicate<ItemStack> filter, int amount) {
        if (amount <= 0 || !level.isLoaded(pos)) {
            return 0;
        }
        int taken = 0;
        Container container = containerAt(level, pos);
        if (container != null) {
            for (int slot = 0; slot < container.getContainerSize() && taken < amount; slot++) {
                if (!suckable(container, slot)) {
                    continue;
                }
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty() || !filter.test(stack)) {
                    continue;
                }
                taken += container.removeItem(slot, amount - taken).getCount();
            }
            return taken;
        }
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, new AABB(pos))) {
            if (entity.isRemoved() || taken >= amount) {
                continue;
            }
            ItemStack stack = entity.getItem();
            if (stack.isEmpty() || !filter.test(stack)) {
                continue;
            }
            ItemStack split = stack.split(amount - taken);
            taken += split.getCount();
            if (stack.isEmpty()) {
                entity.discard();
            } else {
                entity.setItem(stack);
            }
        }
        return taken;
    }

    /**
     * 依次扫描 {@code center} 相邻的 {@code dirs} 各面，返回第一个命中的物料。
     * 未加载的面直接跳过；全部扫完没有命中则返回 {@code null}。
     */
    @Nullable
    public static <T> Found<T> find(Level level, BlockPos center, Direction[] dirs,
                                    BiFunction<BlockPos, ItemStack, T> matcher) {
        for (Direction dir : dirs) {
            Found<T> found = findAt(level, center.relative(dir), matcher);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * 取该位置的容器，末影箱一律当作“没有容器”返回 {@code null}。
     * 其它机器需要自己遍历容器（如销毁站按种类计时）时可复用这个安全检查。
     */
    @Nullable
    public static Container containerAt(Level level, BlockPos pos) {
        if (level.getBlockState(pos).is(Blocks.ENDER_CHEST)) {
            return null;
        }
        return level.getBlockEntity(pos) instanceof Container container ? container : null;
    }

    /**
     * 槽位 {@code slot} 里的东西能不能被吸走。
     *
     * <p>不实现 {@link IOGESuctionFilter} 的容器一律放行（绝大多数箱子、木桶都是如此）；
     * 金源圆锯刃槽用它锁住武器槽——正在用的武器不会被超级漏斗、销毁站这类
     * 按 {@link Container} 逐格遍历的吸取方摸走。</p>
     */
    private static boolean suckable(Container container, int slot) {
        return !(container instanceof IOGESuctionFilter filter) || filter.canSuckSlot(slot);
    }
}
