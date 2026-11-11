package cn.origolds.ia;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
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
 * 金源能量无线网络数据（SavedData）。
 *
 * <p>保存所有“双向无线电线”（{@link WirelessLink}）。由这些连线构成的
 * 无向连通分量即为一个金源能量网络：同网络内的发电机会自动把产出的 OGE
 * 注入电池，发电机之间不互传能量。</p>
 */
public final class OGEWirelessData extends SavedData {

    /** 维度以 Identifier 存、位置以压缩 long 存。 */
    private static final Codec<WirelessLink> LINK_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(WirelessLink::id),
            Identifier.CODEC.fieldOf("adim").forGetter(link -> link.dimA().identifier()),
            Codec.LONG.fieldOf("apos").forGetter(link -> link.posA().asLong()),
            Identifier.CODEC.fieldOf("bdim").forGetter(link -> link.dimB().identifier()),
            Codec.LONG.fieldOf("bpos").forGetter(link -> link.posB().asLong())
    ).apply(instance, (id, adim, apos, bdim, bpos) -> new WirelessLink(
            id, dimKey(adim), BlockPos.of(apos), dimKey(bdim), BlockPos.of(bpos))));

    private static final Codec<OGEWirelessData> CODEC = LINK_CODEC.listOf().xmap(list -> {
        OGEWirelessData data = new OGEWirelessData();
        data.links.addAll(list);
        return data;
    }, data -> List.copyOf(data.links));

    /** 26.x 的存档数据类型：id + 构造器 + 编解码。 */
    public static final SavedDataType<OGEWirelessData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(OriGoldsIndustrializedAge.MOD_ID, "oge_wireless"),
            OGEWirelessData::new, CODEC);

    private final List<WirelessLink> links = new ArrayList<>();

    /** 连线增删计数：供 {@link OGEWirelessManager} 判断连通分量缓存是否过期（不存档）。 */
    private int revision;

    private OGEWirelessData() {
        super();
    }

    public static OGEWirelessData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public List<WirelessLink> links() {
        return links;
    }

    /** 每次增删连线都会变化；用于连通分量缓存失效判断。 */
    public int revision() {
        return revision;
    }

    public WirelessLink link(UUID id) {
        for (WirelessLink link : links) {
            if (link.id().equals(id)) {
                return link;
            }
        }
        return null;
    }

    public void addLink(WirelessLink link) {
        links.add(link);
        revision++;
        setDirty();
    }

    public boolean removeLink(UUID id) {
        boolean removed = links.removeIf(link -> link.id().equals(id));
        if (removed) {
            revision++;
            setDirty();
        }
        return removed;
    }

    /** 判断两个方向是否已有连线，避免重复。 */
    public boolean hasLink(ResourceKey<Level> dimA, BlockPos posA, ResourceKey<Level> dimB, BlockPos posB) {
        for (WirelessLink link : links) {
            if (link.dimA().equals(dimA) && link.posA().equals(posA)
                    && link.dimB().equals(dimB) && link.posB().equals(posB)) {
                return true;
            }
            if (link.dimA().equals(dimB) && link.posA().equals(posB)
                    && link.dimB().equals(dimA) && link.posB().equals(posA)) {
                return true;
            }
        }
        return false;
    }

    public static ResourceKey<Level> dimKey(Identifier location) {
        return ResourceKey.create(Registries.DIMENSION, location);
    }

    /** 一条双向无线电线：连接 A、B 两处的方块。 */
    public record WirelessLink(UUID id, ResourceKey<Level> dimA, BlockPos posA,
                               ResourceKey<Level> dimB, BlockPos posB) {
    }
}
