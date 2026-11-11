package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 资源发生器（沙砾 / 黏土 / 黑曜石）的通用方块实体。
 *
 * <p>服务器端每秒推进 1 点“生成进度”：进度攒满 1 就尝试生成一次——
 * <ol>
 *   <li>正上方被任何方块占用 → 本轮不生成，也不扣能量；</li>
 *   <li>从所在无线电网（电池优先）原子扣除该机型定价的 OGE，不足则不生成；</li>
 *   <li>扣除成功后在正上方放置 1 个目标方块。</li>
 * </ol>
 * 目标方块与单次消耗从携带它的 {@link OGEResourceGeneratorBlock} 上读取，
 * 因此三种发生器共用这一个类与一个方块实体类型。</p>
 *
 * <p>可被“彩虹标记”点亮（{@link IRainbowMarked}）：上彩虹后每秒推进的进度按档位
 * 速度倍率放大（一秒内可连发多次），但每次生成的 OGE 费用按资源倍率同乘——
 * 高档位是“用更多能量换更快产出”，不是白嫖。</p>
 */
public final class OGEResourceGeneratorBlockEntity extends BlockEntity implements IRainbowMarked {

    /** 彩虹标记档位（0/1/2 = 5x/20x/100x），-1 表示未上标记。 */
    private int rainbowTier = -1;
    /** 上标记时彩虹标记物品的剩余耐久（仅展示与持久化用）。 */
    private int rainbowDurability;
    /** 生成进度（每秒 +1，上彩虹后按倍率加速），每满 1 尝试生成一次。 */
    private double progress;

    public OGEResourceGeneratorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.OGE_RESOURCE_GENERATOR.get(), pos, state);
    }

    public void tick() {
        if (level == null || level.isClientSide()) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!(getBlockState().getBlock() instanceof OGEResourceGeneratorBlock generator)) {
            return;
        }
        MinecraftServer server = serverLevel.getServer();
        if (server == null) {
            return;
        }

        // 每秒基础推进 1 点进度，上彩虹后按速度倍率放大；上限防止长期闲置后爆发性生成。
        double perTick = OGERainbowMarks.speedMultiplier(rainbowTier) / (double) 20;
        progress = Math.min(progress + perTick, Math.max(1.0, perTick));
        while (progress >= 1.0 && generateOne(serverLevel, server, generator)) {
            progress--;
        }
    }

    /** 尝试生成一次：成功返回 true（进度才回退），失败返回 false（进度停在 1 等下刻）。 */
    private boolean generateOne(ServerLevel serverLevel, MinecraftServer server,
                                OGEResourceGeneratorBlock generator) {
        // 1. 顶部被占用则不生成（先看占用，再扣费，避免白白消耗 OGE）
        BlockPos out = worldPosition.above();
        if (!serverLevel.isLoaded(out) || !serverLevel.isEmptyBlock(out)) {
            return false;
        }

        // 2. 从所在无线电网（电池优先）扣除本次费用：彩虹档位按资源倍率上浮
        int cost = generator.costPerOperation() * OGERainbowMarks.resourceMultiplier(rainbowTier);
        int consumed = OGEWirelessManager.consumeOGEIfAvailable(
                server, serverLevel.dimension(), worldPosition, cost);
        if (consumed < cost) {
            return false;
        }

        // 3. 正上方生成 1 个目标方块（下方是本机器，实心承载，沙砾等不会下落）
        serverLevel.setBlock(out, generator.outputBlock().defaultBlockState(), Block.UPDATE_ALL);
        setChanged();
        return true;
    }

    // ------------------------------------------------------------------
    // 彩虹标记（IRainbowMarked）
    // ------------------------------------------------------------------

    @Override
    public int getRainbowTier() {
        return rainbowTier;
    }

    @Override
    public int getRainbowDurability() {
        return rainbowDurability;
    }

    @Override
    public void applyRainbowMark(int tier, int durability) {
        rainbowTier = OGERainbowMarks.clampTier(tier);
        rainbowDurability = Math.max(1, durability);
        setChanged();
        syncClient();
    }

    @Override
    public void clearRainbowMark() {
        rainbowTier = -1;
        rainbowDurability = 0;
        setChanged();
        syncClient();
    }

    /** 把彩虹档位推给客户端，驱动 {@link OGERainbowOverlayRenderer} 的覆层显示。 */
    private void syncClient() {
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    // ------------------------------------------------------------------
    // 持久化与客户端同步
    // ------------------------------------------------------------------

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("RainbowTier", rainbowTier);
        output.putInt("RainbowDurability", rainbowDurability);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        rainbowTier = Math.max(-1, input.getIntOr("RainbowTier", -1));
        rainbowDurability = input.getIntOr("RainbowDurability", 0);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putInt("RainbowTier", rainbowTier);
        tag.putInt("RainbowDurability", rainbowDurability);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
