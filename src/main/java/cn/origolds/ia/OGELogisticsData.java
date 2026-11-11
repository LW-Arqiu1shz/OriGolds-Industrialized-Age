package cn.origolds.ia;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

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

    /** 维度以 Identifier 存、位置以压缩 long 存。 */
    private static final Codec<PipeLink> PIPE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(PipeLink::id),
            Identifier.CODEC.fieldOf("fdim").forGetter(pipe -> pipe.dimFrom().identifier()),
            Codec.LONG.fieldOf("fpos").forGetter(pipe -> pipe.posFrom().asLong()),
            Identifier.CODEC.fieldOf("tdim").forGetter(pipe -> pipe.dimTo().identifier()),
            Codec.LONG.fieldOf("tpos").forGetter(pipe -> pipe.posTo().asLong())
    ).apply(instance, (id, fdim, fpos, tdim, tpos) -> new PipeLink(
            id, OGEWirelessData.dimKey(fdim), BlockPos.of(fpos),
            OGEWirelessData.dimKey(tdim), BlockPos.of(tpos))));

    private static final Codec<OGELogisticsData> CODEC = PIPE_CODEC.listOf().xmap(list -> {
        OGELogisticsData data = new OGELogisticsData();
        data.pipes.addAll(list);
        return data;
    }, data -> List.copyOf(data.pipes));

    /** 26.x 的存档数据类型：id + 构造器 + 编解码。 */
    public static final SavedDataType<OGELogisticsData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(OriGoldsIndustrializedAge.MOD_ID, "oge_logistics"),
            OGELogisticsData::new, CODEC);

    private final List<PipeLink> pipes = new ArrayList<>();

    private OGELogisticsData() {
        super();
    }

    public static OGELogisticsData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
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
