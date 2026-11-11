package cn.origolds.ia;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 金源放置器（Origold Placer）的方块实体。
 *
 * <p>数据分三块：</p>
 * <ul>
 *   <li><b>绑定方块</b>：玩家 Alt+中键指定的那一种方块（只存 1 个作样本），
 *       放置器只认它、也只放它；</li>
 *   <li><b>投影列表</b>：玩家在投影放置模式里摆好、再由 Alt+右键保存下来的
 *       目标位置与方块状态（含朝向），一个投影位置一个条目；</li>
 *   <li><b>放置节拍</b>：服务端每 {@code interval} 刻触发一次放置事件，把当前所有能放的
 *       投影<b>一次性全部放完</b>（不再是每个节拍只放一格），间隔由方块状态的
 *       {@link OGEPlacerBlock#MODE} 决定（1/5/10/20 刻）。</li>
 * </ul>
 *
 * <p>工作流程（服务端）：接到放置事件后，按投影列表顺序逐条检查——目标格空着或是可替换
 * 方块、投影状态通过 {@code canSurvive} 合法性校验、且目标格里没有会把方块冲走的流体
 * （见 {@link OGEFluidGuard}，防止放置器往水里反复放作物而拦住水流）——能放的当场就放，
 * 一整个节拍内放完整份蓝图。因为是一条接一条放进世界的，排在前面的方块会立刻成为后面
 * 方块的支撑（火把排在支撑方块前面也能同批落地）。</p>
 *
 * <p>每放一个方块都从 <b>四个水平侧面 + 底下</b>这 5 个相邻格子取 1 份绑定方块
 * （优先容器内的物品，其次该格空间里的掉落物），并原子扣除 1u OGE
 * （见 {@link OGEWirelessManager}）；材料或电量不够时这一批就停在哪算哪，剩下的
 * 等下一个节拍再放。放置成功播放方块自己的放置音效。</p>
 *
 * <p>绑定方块与投影列表都要给客户端用：物流显示模式要按已保存投影画出
 * 50% 透明的虚影和 #c99951 外框线，投影放置模式还要拿绑定方块当预览样本。
 * 数据量不大、变化也不频繁，因此沿用原版方块实体同步（{@link #getUpdateTag}
 * / {@link #getUpdatePacket}），每次改动调 {@link #syncToClients()} 推一次。</p>
 */
public final class OGEPlacerBlockEntity extends BlockEntity {

    /** 输入扫描顺序：四个水平侧面 + 底下（与粉碎机一致）。 */
    private static final Direction[] SOURCE_DIRECTIONS = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.DOWN
    };

    /** 每成功放置 1 个方块消耗的 OGE。 */
    private static final int OGE_PER_PLACEMENT = 1;

    /** 绑定的方块样本（1 个）；未绑定时为空。 */
    private ItemStack bound = ItemStack.EMPTY;

    /** 已保存的投影：目标位置 + 要放的方块状态（含朝向）。 */
    private final List<Projection> projections = new ArrayList<>();

    /** 距离下一次尝试放置还剩几刻。 */
    private int cooldown = 0;

    public OGEPlacerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.OGE_PLACER.get(), pos, state);
    }

    /** 一条投影：放在哪一格、放成什么状态。 */
    public record Projection(BlockPos pos, BlockState state) {
    }

    // ------------------------------------------------------------------
    // 对外读写（方块交互与客户端渲染用）
    // ------------------------------------------------------------------

    public ItemStack getBound() {
        return bound;
    }

    /** 写入绑定方块；数量强制为 1（只当样本用）。调用方负责同步。 */
    public void setBound(ItemStack stack) {
        this.bound = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
        syncToClients();
    }

    public List<Projection> getProjections() {
        return projections;
    }

    /** 整体替换投影列表（已由方块侧校验过范围与方块种类）。 */
    public void setProjections(List<Projection> newProjections) {
        projections.clear();
        projections.addAll(newProjections);
        cooldown = 0;
        syncToClients();
    }

    /** 数据改变后落盘并推给周围客户端。 */
    private void syncToClients() {
        setChanged();
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_ALL);
        }
    }

    /** 当前放置间隔（刻）：由方块状态的档位换算。 */
    private int intervalTicks() {
        BlockState state = getBlockState();
        if (state.getBlock() instanceof OGEPlacerBlock) {
            return OGEPlacerBlock.intervalTicks(state.getValue(OGEPlacerBlock.MODE));
        }
        return OGEPlacerBlock.INTERVAL_TICKS[OGEPlacerBlock.DEFAULT_MODE];
    }

    // ------------------------------------------------------------------
    // 服务端每刻：取料 + 放置
    // ------------------------------------------------------------------

    /** 服务端每 tick 调用（方块 ticker 驱动）。 */
    public void tick() {
        if (level == null || level.isClientSide) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        MinecraftServer server = serverLevel.getServer();
        if (server == null || bound.isEmpty() || projections.isEmpty()) {
            return;
        }
        if (cooldown > 0) {
            --cooldown;
            return;
        }
        // 放置事件触发：把当前能放的投影一次性全部放完；一个都没放成就不进入节拍，
        // 下一 tick 接着看（条件一满足就能放）。
        if (placeAll(serverLevel, server) > 0) {
            cooldown = Math.max(0, intervalTicks() - 1);
        }
    }

    /**
     * 把所有「现在能放」的投影一次性放完，返回实际放下的方块数。
     *
     * <p>按投影列表顺序逐条处理，每条都在放下前用当前世界状态重新校验：目标格空着或可
     * 替换、{@code canSurvive} 通过、目标格里没有会把方块冲走的流体（见 {@link OGEFluidGuard}）。
     * 同批内先放下的方块会立刻出现在世界里，所以后面的投影如果依赖前面的方块当支撑，
     * 同一批就能落地。</p>
     *
     * <p>遇到不合法/被占的投影是<b>跳过而不是删除</b>：后放的投影可能给前面的当支撑，
     * 等条件满足时自然会轮到它。材料或电量耗尽时停止这一批（已经放下的不回滚），
     * 剩下的等下一个节拍。</p>
     */
    private int placeAll(ServerLevel serverLevel, MinecraftServer server) {
        int placed = 0;
        for (Projection projection : projections) {
            BlockPos pos = projection.pos();
            if (!canPlaceNow(serverLevel, pos, projection.state())) {
                continue;
            }
            // 先确认 5 个相邻格子里确实有绑定方块，再扣电——
            // 反过来的话“有电但没料”会白扣 1u。
            OGEFaceItems.Found<ItemStack> material = OGEFaceItems.find(
                    serverLevel, worldPosition, SOURCE_DIRECTIONS,
                    (sourcePos, stack) -> ItemStack.isSameItemSameComponents(stack, bound) ? stack : null);
            if (material == null) {
                break; // 这一批没料了，剩下的等下一个节拍
            }
            if (!payOge(serverLevel, server)) {
                break; // 这一批没电了，剩下的等下一个节拍
            }
            if (material.take(1).isEmpty()) {
                continue; // 单线程内不会发生，保守起见跳过这一条
            }
            setChanged();
            // 放置：投影里存的是完整方块状态，朝向原样还原。
            // 用带邻居更新的 setBlock：放在耕地上方时，耕地会像原版放置一样被踩成泥土。
            if (serverLevel.setBlock(pos, projection.state(), Block.UPDATE_ALL)) {
                SoundType sound = projection.state().getSoundType();
                serverLevel.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS,
                        (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
                ++placed;
                setChanged();
            }
        }
        return placed;
    }

    /**
     * 目标位置现在能不能放下这个投影状态：目标格空着或可替换（草、藤蔓等可替换方块会被
     * 直接顶掉）、投影状态通过 {@code canSurvive} 合法性校验（比如小麦必须有耕地），
     * 并且目标格里的流体不会把方块冲走（见 {@link OGEFluidGuard}）。
     */
    private static boolean canPlaceNow(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        if (!serverLevel.isLoaded(pos)) {
            return false;
        }
        BlockState current = serverLevel.getBlockState(pos);
        if (!current.isAir() && !current.canBeReplaced()) {
            return false; // 被真实方块占住了：既不是空气也不能替换
        }
        if (!state.canSurvive(serverLevel, pos)) {
            return false; // 位置不合法（放置规则与真实放置同一套），暂时不放置
        }
        // 目标格是水（或别种流体），放下去也会被冲走：跳过而不是反复放——
        // 否则水流被反复放置的作物拦住，以水流为基础的自动农场就失效了。
        return !OGEFluidGuard.washedAwayByFluid(serverLevel, pos, state);
    }

    /** 从所在无线电网原子扣除一次放置费用；余额不足返回 false（不产生任何消耗）。 */
    private boolean payOge(ServerLevel serverLevel, MinecraftServer server) {
        return OGEWirelessManager.consumeOGEIfAvailable(
                server, serverLevel.dimension(), worldPosition, OGE_PER_PLACEMENT) >= OGE_PER_PLACEMENT;
    }

    // ------------------------------------------------------------------
    // 持久化
    // ------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        savePlacerData(tag, registries);
        tag.putInt("Cooldown", cooldown);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.bound = ItemStack.parseOptional(registries, tag.getCompound("Bound"));
        this.projections.clear();
        ListTag list = tag.getList("Projections", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            projections.add(new Projection(
                    BlockPos.of(entry.getLong("Pos")),
                    NbtUtils.readBlockState(registries.lookupOrThrow(Registries.BLOCK), entry.getCompound("State"))));
        }
        this.cooldown = tag.getInt("Cooldown");
    }

    /** 绑定方块与投影列表（发往客户端的同步标签也复用这一份）。 */
    private void savePlacerData(CompoundTag tag, HolderLookup.Provider registries) {
        if (!bound.isEmpty()) {
            tag.put("Bound", bound.save(registries));
        }
        ListTag list = new ListTag();
        for (Projection projection : projections) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("Pos", projection.pos().asLong());
            entry.put("State", NbtUtils.writeBlockState(projection.state()));
            list.add(entry);
        }
        tag.put("Projections", list);
    }

    /**
     * 发往客户端的方块实体数据：绑定方块 + 全部投影（客户端渲染只读这两样，
     * 节拍不需要同步）。基类默认返回空标签，不显式写就没有内容可同步。
     */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        savePlacerData(tag, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
