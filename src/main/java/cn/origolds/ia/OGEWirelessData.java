package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
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
 * 金源能量无线网络数据（SavedData）。
 *
 * <p>保存所有“双向无线电线”（{@link WirelessLink}）。由这些连线构成的
 * 无向连通分量即为一个金源能量网络：同网络内的发电机会自动把产出的 OGE
 * 注入电池，发电机之间不互传能量。</p>
 */
public final class OGEWirelessData extends SavedData {

    private static final String DATA_NAME = "origolds_ia_oge_wireless";

    private final List<WirelessLink> links = new ArrayList<>();

    /** 连线增删计数：供 {@link OGEWirelessManager} 判断连通分量缓存是否过期（不存档）。 */
    private int revision;

    private OGEWirelessData() {
        super();
    }

    public static OGEWirelessData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(OGEWirelessData::new, OGEWirelessData::load), DATA_NAME);
    }

    private static OGEWirelessData load(CompoundTag tag, HolderLookup.Provider registries) {
        OGEWirelessData data = new OGEWirelessData();
        ListTag linkList = tag.getList("links", Tag.TAG_COMPOUND);
        for (int i = 0; i < linkList.size(); ++i) {
            CompoundTag t = linkList.getCompound(i);
            data.links.add(new WirelessLink(
                    t.getUUID("id"),
                    dimKey(ResourceLocation.parse(t.getString("adim"))),
                    BlockPos.of(t.getLong("apos")),
                    dimKey(ResourceLocation.parse(t.getString("bdim"))),
                    BlockPos.of(t.getLong("bpos"))));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag linkList = new ListTag();
        for (WirelessLink link : links) {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", link.id());
            t.putString("adim", link.dimA().location().toString());
            t.putLong("apos", link.posA().asLong());
            t.putString("bdim", link.dimB().location().toString());
            t.putLong("bpos", link.posB().asLong());
            linkList.add(t);
        }
        tag.put("links", linkList);
        return tag;
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

    public static ResourceKey<Level> dimKey(ResourceLocation location) {
        return ResourceKey.create(Registries.DIMENSION, location);
    }

    /** 一条双向无线电线：连接 A、B 两处的方块。 */
    public record WirelessLink(UUID id, ResourceKey<Level> dimA, BlockPos posA,
                               ResourceKey<Level> dimB, BlockPos posB) {
    }
}
