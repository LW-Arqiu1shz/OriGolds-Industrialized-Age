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

/**
 * 资源发生器（沙砾 / 黏土 / 黑曜石）的通用方块实体。
 *
 * <p>服务器端每刻把“本档位每秒该生成几块”累进一个小数进度，攒满 1 就尝试生成一块：
 * 无印记时是每秒 1 块；贴上 5x / 20x / 100x 虹彩印记后变成每秒 5 / 20 / 100 块，
 * 但每块的 OGE 消耗也按资源倍率 ×1 / ×2 / ×4。</p>
 *
 * <p>每次都先看正上方是否被占、再看无线电网（电池优先）的电够不够：任一条不满足就
 * 原地等着、既不生成也不扣费。方块实体保存了档位与印记耐久，档位会同步给客户端，
 * 用来画 25% 透明度的虹彩叠加层。</p>
 */
public final class OGEResourceGeneratorBlockEntity extends BlockEntity implements IRainbowMarked {

    /** 无印记时的基准节奏：每秒 1 块。 */
    private static final int TICKS_PER_SECOND = 20;

    /** 虹彩印记档位：-1 = 没贴，0/1/2 = 5x / 20x / 100x。 */
    private int rainbowTier = -1;
    /** 贴上来时印记剩余的耐久（挖掉方块时原样归还）。 */
    private int rainbowDurability;
    /** 攒到 1 就该生成一块的进度。 */
    private double progress;

    public OGEResourceGeneratorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.OGE_RESOURCE_GENERATOR.get(), pos, state);
    }

    public void tick() {
        if (level == null || level.isClientSide) {
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

        double perTick = OGERainbowMarks.speedMultiplier(rainbowTier) / (double) TICKS_PER_SECOND;
        // 上限就是“一刻的量”：顶部被占或断电时进度不会越攒越多，等恢复后立刻接着干。
        progress = Math.min(progress + perTick, Math.max(1.0D, perTick));
        while (progress >= 1.0D) {
            if (!generateOne(serverLevel, server, generator)) {
                break;
            }
            progress -= 1.0D;
        }
    }

    /**
     * 尝试生成一块。顶部被占或电不够就返回 {@code false}（不生成、不扣费）。
     *
     * @return 是否成功生成了一块
     */
    private boolean generateOne(ServerLevel serverLevel, MinecraftServer server,
                                OGEResourceGeneratorBlock generator) {
        BlockPos out = worldPosition.above();
        if (!serverLevel.isLoaded(out) || !serverLevel.isEmptyBlock(out)) {
            return false;
        }
        int cost = generator.costPerOperation() * OGERainbowMarks.resourceMultiplier(rainbowTier);
        int consumed = OGEWirelessManager.consumeOGEIfAvailable(
                server, serverLevel.dimension(), worldPosition, cost);
        if (consumed < cost) {
            return false;
        }
        serverLevel.setBlock(out, generator.outputBlock().defaultBlockState(), Block.UPDATE_ALL);
        setChanged();
        return true;
    }

    // ------------------------------------------------------------------
    // 虹彩印记
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
        this.rainbowTier = OGERainbowMarks.clampTier(tier);
        this.rainbowDurability = Math.max(1, durability);
        setChanged();
        syncClient();
    }

    @Override
    public void clearRainbowMark() {
        this.rainbowTier = -1;
        this.rainbowDurability = 0;
        setChanged();
        syncClient();
    }

    /** 把档位立刻发给跟踪该区块的客户端（叠加层贴图要跟着换）。 */
    private void syncClient() {
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    // ------------------------------------------------------------------
    // 持久化与客户端同步
    // ------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("RainbowTier", rainbowTier);
        tag.putInt("RainbowDurability", rainbowDurability);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        rainbowTier = tag.contains("RainbowTier") ? Math.max(-1, tag.getInt("RainbowTier")) : -1;
        rainbowDurability = tag.getInt("RainbowDurability");
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
