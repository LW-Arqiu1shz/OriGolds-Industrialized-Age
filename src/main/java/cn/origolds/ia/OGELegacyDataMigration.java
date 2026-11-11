package cn.origolds.ia;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 1.21.1 旧存档 → 26.x 的无线数据迁移。
 *
 * <p>1.21.1 把无线电线 / 物流管道存在 {@code data/origolds_ia_oge_wireless.dat} /
 * {@code data/origolds_ia_oge_logistics.dat}（根 NBT 是 {@code links} / {@code pipes} 列表）；
 * 26.x 改用 {@code SavedDataType}（{@code origolds_ia:oge_wireless} / {@code origolds_ia:oge_logistics}），
 * 文件路径和根格式都变了。把 1.21.1 的世界升级到 26.x 后，所有连线和管道会悄无声息地消失。</p>
 *
 * <p>这里在服务端启动完成时检查一次：新数据为空、且能找到旧文件时把旧数据导入，
 * 然后把旧文件改名为 {@code *.migrated}，以后不会重复导入。新数据已经有内容时不动旧文件。</p>
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID)
public final class OGELegacyDataMigration {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String LEGACY_WIRELESS = "origolds_ia_oge_wireless.dat";
    private static final String LEGACY_LOGISTICS = "origolds_ia_oge_logistics.dat";

    private OGELegacyDataMigration() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        try {
            migrateWireless(server);
        } catch (Exception e) {
            LOGGER.warn("[origolds_ia] 旧版无线电线数据迁移失败，已跳过", e);
        }
        try {
            migrateLogistics(server);
        } catch (Exception e) {
            LOGGER.warn("[origolds_ia] 旧版物流管道数据迁移失败，已跳过", e);
        }
    }

    private static void migrateWireless(MinecraftServer server) throws Exception {
        Path file = findLegacy(server, LEGACY_WIRELESS);
        if (file == null) {
            return;
        }
        OGEWirelessData data = OGEWirelessData.get(server);
        if (!data.links().isEmpty()) {
            return;
        }
        int count = 0;
        for (CompoundTag t : readList(file, "links")) {
            Optional<UUID> id = t.read("id", UUIDUtil.CODEC);
            ResourceKey<Level> dimA = dim(t, "adim");
            ResourceKey<Level> dimB = dim(t, "bdim");
            Optional<Long> posA = t.getLong("apos");
            Optional<Long> posB = t.getLong("bpos");
            if (id.isEmpty() || dimA == null || dimB == null || posA.isEmpty() || posB.isEmpty()) {
                continue;
            }
            data.addLink(new OGEWirelessData.WirelessLink(
                    id.get(), dimA, BlockPos.of(posA.get()), dimB, BlockPos.of(posB.get())));
            count++;
        }
        finish(file, count, "无线电线");
    }

    private static void migrateLogistics(MinecraftServer server) throws Exception {
        Path file = findLegacy(server, LEGACY_LOGISTICS);
        if (file == null) {
            return;
        }
        OGELogisticsData data = OGELogisticsData.get(server);
        if (!data.pipes().isEmpty()) {
            return;
        }
        int count = 0;
        for (CompoundTag t : readList(file, "pipes")) {
            Optional<UUID> id = t.read("id", UUIDUtil.CODEC);
            ResourceKey<Level> dimFrom = dim(t, "fdim");
            ResourceKey<Level> dimTo = dim(t, "tdim");
            Optional<Long> posFrom = t.getLong("fpos");
            Optional<Long> posTo = t.getLong("tpos");
            if (id.isEmpty() || dimFrom == null || dimTo == null || posFrom.isEmpty() || posTo.isEmpty()) {
                continue;
            }
            data.addPipe(new OGELogisticsData.PipeLink(
                    id.get(), dimFrom, BlockPos.of(posFrom.get()), dimTo, BlockPos.of(posTo.get())));
            count++;
        }
        finish(file, count, "物流管道");
    }

    /** 旧文件可能还在世界根的 data/ 里，也可能被原版的存档升级挪进了主世界的维度目录。 */
    private static Path findLegacy(MinecraftServer server, String name) {
        Path root = server.getWorldPath(LevelResource.ROOT);
        for (Path candidate : List.of(
                root.resolve("data").resolve(name),
                root.resolve("dimensions").resolve("minecraft").resolve("overworld").resolve("data").resolve(name))) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static List<CompoundTag> readList(Path file, String key) throws Exception {
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        CompoundTag body = root.getCompound("data").orElse(root);
        ListTag list = body.getListOrEmpty(key);
        java.util.ArrayList<CompoundTag> out = new java.util.ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            list.getCompound(i).ifPresent(out::add);
        }
        return out;
    }

    private static ResourceKey<Level> dim(CompoundTag tag, String key) {
        Identifier id = tag.getString(key).map(Identifier::tryParse).orElse(null);
        return id == null ? null : OGEWirelessData.dimKey(id);
    }

    private static void finish(Path file, int count, String what) throws Exception {
        Files.move(file, file.resolveSibling(file.getFileName() + ".migrated"));
        LOGGER.info("[origolds_ia] 已从 1.21.1 旧存档导入 {} 条{}（{}）", count, what, file);
    }
}
