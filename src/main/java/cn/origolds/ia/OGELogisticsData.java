package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 金源无线物流管道数据（SavedData）。
 *
 * <p>保存所有“单向无线物流管道”（{@link PipeLink}）：从起点向终点每刻输送一个物品。
 * 起点为容器时按槽位顺序取出 1 个物品；起点非容器时像漏斗一样从该位置吸取 1 个掉落物。
 * 终点为容器则塞入，否则丢在方块正中心。管道有方向，A→B 与 B→A 是两条不同管道。</p>
 */
public final class OGELogisticsData extends SavedData {

    private static final String DATA_NAME = "origolds_ia_oge_logistics";

    private final List<PipeLink> pipes = new ArrayList<>();

    private OGELogisticsData() {
        super();
    }

    public static OGELogisticsData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(OGELogisticsData::new, OGELogisticsData::load), DATA_NAME);
    }

    private static OGELogisticsData load(CompoundTag tag, HolderLookup.Provider registries) {
        OGELogisticsData data = new OGELogisticsData();
        ListTag list = tag.getList("pipes", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); ++i) {
            CompoundTag t = list.getCompound(i);
            data.pipes.add(new PipeLink(
                    t.getUUID("id"),
                    OGEWirelessData.dimKey(ResourceLocation.parse(t.getString("fdim"))),
                    BlockPos.of(t.getLong("fpos")),
                    OGEWirelessData.dimKey(ResourceLocation.parse(t.getString("tdim"))),
                    BlockPos.of(t.getLong("tpos"))));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (PipeLink pipe : pipes) {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", pipe.id());
            t.putString("fdim", pipe.dimFrom().location().toString());
            t.putLong("fpos", pipe.posFrom().asLong());
            t.putString("tdim", pipe.dimTo().location().toString());
            t.putLong("tpos", pipe.posTo().asLong());
            list.add(t);
        }
        tag.put("pipes", list);
        return tag;
    }

    public List<PipeLink> pipes() {
        return pipes;
    }

    public PipeLink pipe(UUID id) {
        for (PipeLink pipe : pipes) {
            if (pipe.id().equals(id)) {
                return pipe;
            }
        }
        return null;
    }

    public void addPipe(PipeLink pipe) {
        pipes.add(pipe);
        setDirty();
    }

    public boolean removePipe(UUID id) {
        boolean removed = pipes.removeIf(pipe -> pipe.id().equals(id));
        if (removed) {
            setDirty();
        }
        return removed;
    }

    /** 按 id 原位替换一条管道（用于反转方向，保持原有顺序）。 */
    public boolean replacePipe(PipeLink pipe) {
        for (int i = 0; i < pipes.size(); ++i) {
            if (pipes.get(i).id().equals(pipe.id())) {
                pipes.set(i, pipe);
                setDirty();
                return true;
            }
        }
        return false;
    }

    /** 同方向（起点→终点）是否已有管道；单向管道允许反向的另一条存在。 */
    public boolean hasPipe(ResourceKey<Level> dimFrom, BlockPos posFrom,
                           ResourceKey<Level> dimTo, BlockPos posTo) {
        for (PipeLink pipe : pipes) {
            if (pipe.dimFrom().equals(dimFrom) && pipe.posFrom().equals(posFrom)
                    && pipe.dimTo().equals(dimTo) && pipe.posTo().equals(posTo)) {
                return true;
            }
        }
        return false;
    }

    /** 一条单向无线物流管道：从 (dimFrom, posFrom) 输送到 (dimTo, posTo)。 */
    public record PipeLink(UUID id, ResourceKey<Level> dimFrom, BlockPos posFrom,
                           ResourceKey<Level> dimTo, BlockPos posTo) {

        /** 返回方向相反的同一管道。 */
        public PipeLink reversed() {
            return new PipeLink(id, dimTo, posTo, dimFrom, posFrom);
        }
    }
}
