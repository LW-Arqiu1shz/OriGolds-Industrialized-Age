package cn.origolds.ia;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * 金源锯片台的方块实体：武器/锯片槽（0）、废件槽（1）、储经验和自动攻击的核心逻辑。
 *
 * <h2>运转</h2>
 * <p>服务端每刻 {@link #tick()}：破损判定与经验修理 → 每 {@value #POWER_CHECK_INTERVAL}
 * 刻向金源无线网查一次 OGE 供给（{@link #isBladePowered()}，钉着的锯片实体靠它决定是否旋转
 * 攻击）→ 每 20 刻重新解析绑定的志愿者实体。</p>
 *
 * <h2>磨损</h2>
 * <p>普通近战武器每 {@value #HITS_PER_WEAR} 次命中消耗 1 点耐久（受不磨损影响）；
 * 圆锯片按 {@value #SAWBLADE_HITS_PER_WEAR} 次/点耐久计。武器被打坏（或只剩 1 点耐久
 * 且带附魔）时整体挪进废件槽；圆锯片爆耐久后原地滞留、连同附魔一起进废件槽。</p>
 *
 * <h2>经验</h2>
 * <p>绑定志愿者的锯片击杀会把经验收进本方块（{@link #collectKillExperience}），
 * 有修理附魔时优先拿去修武器（{@link #repairWeaponWithStoredExperience()}，
 * 规则与原版修补一致：1 点经验修 2 点耐久），剩下的从界面取出（{@link #takeExperienceOut}）。</p>
 *
 * <h2>拆除善后</h2>
 * <p>26.x 没有 {@code Block#onRemove}，方块被破坏/替换时随方块移除的副作用统一放在
 * {@link #preRemoveSideEffects(BlockPos, BlockState)}：掉下钉着的锯片实体、把储经验
 * 吐成经验球、解绑志愿者、掉落两个槽的内容物。</p>
 *
 * <h2>对外能力</h2>
 * <p>物品能力注册由 {@code OGECapabilities} 一侧统一接线（容器走
 * {@code VanillaContainerWrapper.of(be)}）；本类保留 {@link #getItemHandler()} 作为
 * 内部/兼容入口。吸取过滤实现 {@link IOGESuctionFilter}：物流只许从废件槽（1）抽东西。</p>
 */
public final class OGESawbladeMountBlockEntity extends BaseContainerBlockEntity implements IOGESuctionFilter {

    /** 武器/锯片槽。 */
    public static final int SLOT_WEAPON = 0;
    /** 废件槽。 */
    public static final int SLOT_WASTE = 1;
    /** 槽位总数。 */
    public static final int SLOT_COUNT = 2;
    /** 普通近战武器每多少次数值命中掉 1 点耐久。 */
    public static final int HITS_PER_WEAR = 2;
    /** 圆锯片每多少次数值命中掉 1 点耐久。 */
    public static final int SAWBLADE_HITS_PER_WEAR = 1250;
    /** OGE 供给查询间隔（刻）。 */
    private static final int POWER_CHECK_INTERVAL = 10;
    /** 绑定志愿者实体重解析间隔（刻）。 */
    private static final int VOLUNTEER_RESOLVE_INTERVAL = 20;

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
    private boolean powered;
    private int powerCountdown = 1;
    private int storedExperience;
    private int bladeHits;
    private UUID boundVolunteerId;
    private OGEVolunteerWatcherEntity boundVolunteer;
    private int boundResolveCountdown = 1;
    /** 本台锯片台的绑定名（1.0.1+）：集中存储，绑到本台的所有志愿者共用并同步。 */
    private String volunteerBindName = "";
    private final ResourceHandler<ItemResource> itemHandler = new OGESawbladeMountItemHandler(this);

    public OGESawbladeMountBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.OGE_SAWBLADE_MOUNT.get(), pos, state);
    }

    @Override
    public int getContainerSize() {
        return SLOT_COUNT;
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return this.items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {
        this.items = items;
    }

    @Override
    public boolean canPlaceItem(int index, ItemStack stack) {
        return index == SLOT_WEAPON ? OGEMeleeWeapons.isMeleeWeapon(stack) : index == SLOT_WASTE && !stack.isEmpty();
    }

    /** 物流吸取过滤：只有废件槽（1）允许被外部抽走。 */
    @Override
    public boolean canSuckSlot(int slot) {
        return slot == SLOT_WASTE;
    }

    @Override
    public void setItem(int index, ItemStack stack) {
        super.setItem(index, stack);
        if (index == SLOT_WEAPON) {
            this.bladeHits = 0;
            if (this.shouldMoveWeaponToWaste()) {
                this.moveWeaponToWaste();
            }
        }
    }

    /** 服务端每刻：破损/修理 → OGE 供给轮询 → 志愿者重解析。 */
    public void tick() {
        if (this.level != null && !this.level.isClientSide() && this.level instanceof ServerLevel serverLevel) {
            if (this.shouldMoveWeaponToWaste()) {
                this.moveWeaponToWaste();
            } else {
                this.repairWeaponWithStoredExperience();
            }

            if (--this.powerCountdown <= 0) {
                this.powerCountdown = POWER_CHECK_INTERVAL;
                this.powered = OGEWirelessManager.availableOGE(serverLevel.getServer(), serverLevel.dimension(),
                        this.worldPosition) >= 1;
            }

            if (--this.boundResolveCountdown <= 0) {
                this.boundResolveCountdown = VOLUNTEER_RESOLVE_INTERVAL;
                this.boundVolunteer = this.resolveBoundVolunteer(serverLevel);
            }
        }
    }

    public UUID getBoundVolunteerId() {
        return this.boundVolunteerId;
    }

    public void setBoundVolunteer(UUID volunteerId) {
        this.boundVolunteerId = volunteerId;
        if (this.level instanceof ServerLevel serverLevel) {
            this.boundVolunteer = this.resolveBoundVolunteer(serverLevel);
        } else {
            this.boundVolunteer = null;
        }
    }

    public OGEVolunteerWatcherEntity getBoundVolunteer() {
        return this.boundVolunteer != null && this.boundVolunteer.isAlive() && !this.boundVolunteer.isRemoved()
                ? this.boundVolunteer : null;
    }

    private OGEVolunteerWatcherEntity resolveBoundVolunteer(ServerLevel serverLevel) {
        if (this.boundVolunteerId == null) {
            return null;
        }
        // 26.x 的 UUID 实体查找合并成整服一个入口；志愿者与锯片台同维度存活时才可能命中。
        return serverLevel.getEntityInAnyDimension(this.boundVolunteerId) instanceof OGEVolunteerWatcherEntity volunteer
                && !volunteer.isRemoved() ? volunteer : null;
    }

    /** 本台锯片台的绑定显示名（空串＝未命名）。绑到本台的所有志愿者共用这一个名字。 */
    public String getVolunteerBindName() {
        return this.volunteerBindName;
    }

    /** 设置绑定名（去首尾空白、截到 {@link OGEVolunteerBindManager#MAX_BIND_NAME_LENGTH}）；有变化才置脏。 */
    public void setVolunteerBindName(String name) {
        String safe = name == null ? "" : name.strip();
        if (safe.length() > OGEVolunteerBindManager.MAX_BIND_NAME_LENGTH) {
            safe = safe.substring(0, OGEVolunteerBindManager.MAX_BIND_NAME_LENGTH);
        }
        if (!safe.equals(this.volunteerBindName)) {
            this.volunteerBindName = safe;
            this.setChanged();
        }
    }

    /** 锯片是否“通电”（金源无线网里有可消耗的 OGE）。 */
    public boolean isBladePowered() {
        return this.powered;
    }

    /** 每消耗 1 点耐久所需的数值命中次数：圆锯片更耐用。 */
    public int wearThreshold() {
        ItemStack weapon = this.getItem(SLOT_WEAPON);
        return weapon.getItem() instanceof OGECircularSawbladeItem ? SAWBLADE_HITS_PER_WEAR : HITS_PER_WEAR;
    }

    /** 单次攻击的数值伤害：面板攻击 × 面板攻速 ÷ 2；武器不可用（或空槽）时 0。 */
    public double bladeDamagePerHit() {
        ItemStack weapon = this.getItem(SLOT_WEAPON);
        return !weapon.isEmpty() && OGEMeleeWeapons.isMeleeWeapon(weapon) && weaponUsable(weapon)
                ? OGEMeleeWeapons.bladeDamagePerHit(weapon) : 0.0;
    }

    /** 武器是否还能打（未损坏或没打坏）。 */
    public static boolean weaponUsable(ItemStack weapon) {
        return !weapon.isDamageableItem() || weapon.getDamageValue() < weapon.getMaxDamage();
    }

    private static int enchantmentLevel(ItemStack weapon, ResourceKey<Enchantment> key) {
        for (Holder<Enchantment> enchantment : weapon.getEnchantments().keySet()) {
            if (enchantment.is(key)) {
                return weapon.getEnchantments().getLevel(enchantment);
            }
        }
        return 0;
    }

    private static boolean hasEnchantment(ItemStack weapon, ResourceKey<Enchantment> key) {
        return enchantmentLevel(weapon, key) > 0;
    }

    /**
     * 借给刀刃击杀者读取抢夺等级的攻击器副本。
     * 原版战利品表从击杀者主手读取 Looting，因此只在实际附有抢夺时借出副本。
     */
    public ItemStack loanLootingWeapon() {
        ItemStack weapon = this.getItem(SLOT_WEAPON);
        if (weapon.isEmpty() || !hasEnchantment(weapon, Enchantments.LOOTING)) {
            return ItemStack.EMPTY;
        }
        return weapon.copy();
    }

    /** 打一次结算前先付 1 点 OGE；付不出就不打。 */
    public boolean payBladeAttack(ServerLevel serverLevel) {
        return OGEWirelessManager.consumeOGEIfAvailable(serverLevel.getServer(), serverLevel.dimension(),
                this.worldPosition, 1) >= 1;
    }

    /** 一轮命中后计磨损：攒够 {@link #wearThreshold()} 次扣 1 点耐久。 */
    public void onBladeHits() {
        if (this.level != null && !this.level.isClientSide()) {
            if (++this.bladeHits < this.wearThreshold()) {
                this.setChanged();
            } else {
                this.bladeHits = 0;
                this.consumeWeaponDurability();
            }
        }
    }

    private void consumeWeaponDurability() {
        ItemStack weapon = this.getItem(SLOT_WEAPON);
        if (!weapon.isEmpty() && weapon.isDamageableItem()) {
            int unbreaking = enchantmentLevel(weapon, Enchantments.UNBREAKING);
            if (unbreaking <= 0 || this.level.getRandom().nextInt(unbreaking + 1) == 0) {
                weapon.setDamageValue(weapon.getDamageValue() + 1);
            }

            this.setChanged();
            int remaining = weapon.getMaxDamage() - weapon.getDamageValue();
            if (remaining <= 0 && !(weapon.getItem() instanceof OGECircularSawbladeItem)) {
                this.setItem(SLOT_WEAPON, ItemStack.EMPTY);
            } else {
                if (this.shouldMoveWeaponToWaste()) {
                    this.moveWeaponToWaste();
                }
            }
        }
    }

    /** 该把武器挪进废件槽了吗：圆锯片打爆（剩余 ≤0）直接进；带附魔的快打坏（剩余 ≤1）就进，保住附魔。 */
    private boolean shouldMoveWeaponToWaste() {
        ItemStack weapon = this.getItem(SLOT_WEAPON);
        if (!weapon.isEmpty() && weapon.isDamageableItem()) {
            int remaining = weapon.getMaxDamage() - weapon.getDamageValue();
            return weapon.getItem() instanceof OGECircularSawbladeItem && remaining <= 0
                    || !weapon.getEnchantments().isEmpty() && remaining <= 1;
        }
        return false;
    }

    /** 把武器整份复制进废件槽并清空武器槽；废件槽占着就失败。 */
    private boolean moveWeaponToWaste() {
        ItemStack weapon = this.getItem(SLOT_WEAPON);
        if (weapon.isEmpty()) {
            return true;
        }
        if (!this.getItem(SLOT_WASTE).isEmpty()) {
            return false;
        }
        this.setItem(SLOT_WASTE, weapon.copy());
        this.setItem(SLOT_WEAPON, ItemStack.EMPTY);
        this.setChanged();
        return true;
    }

    /** 锯片击杀结算：把应得经验收进储经验（击杀者侧要先 skipDropExperience）。 */
    public void collectKillExperience(ServerLevel serverLevel, LivingEntity victim, Entity attacker) {
        boolean dropsExp = victim instanceof Player
                || serverLevel.getGameRules().get(GameRules.MOB_DROPS) && victim.shouldDropExperience();
        if (dropsExp) {
            int reward = victim.getExperienceReward(serverLevel, attacker);
            if (reward > 0) {
                this.addExperience(reward);
            }
        }
    }

    /** 进储经验：立刻尝试拿去修带修补的武器，封顶 int 上限。 */
    public void addExperience(int amount) {
        if (amount > 0) {
            long total = (long) this.storedExperience + amount;
            this.storedExperience = total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
            this.repairWeaponWithStoredExperience();
            this.setChanged();
        }
    }

    /** 用储经验修武器：1 经验修 2 耐久，与原版修补一致（向上取整扣经验）。 */
    private void repairWeaponWithStoredExperience() {
        ItemStack weapon = this.getItem(SLOT_WEAPON);
        if (this.storedExperience > 0
                && !weapon.isEmpty()
                && weapon.isDamageableItem()
                && weapon.getDamageValue() > 0
                && hasEnchantment(weapon, Enchantments.MENDING)) {
            int repair = Math.min(weapon.getDamageValue(), this.storedExperience * 2);
            int cost = (repair + 1) / 2;
            weapon.setDamageValue(weapon.getDamageValue() - repair);
            this.storedExperience -= cost;
            this.setChanged();
        }
    }

    public int getStoredExperience() {
        return this.storedExperience;
    }

    /** 从储经验里扣走最多 requested 点（负数请求按 0）。 */
    public int extractExperience(int requested) {
        if (requested <= 0) {
            return 0;
        }
        int amount = Math.min(requested, this.storedExperience);
        if (amount <= 0) {
            return 0;
        }
        this.storedExperience -= amount;
        this.setChanged();
        return amount;
    }

    public ResourceHandler<ItemResource> getItemHandler() {
        return this.itemHandler;
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("block.origolds_ia.origold_sawblade_mount");
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory playerInventory) {
        return new OGESawbladeMountMenu(containerId, playerInventory, this.worldPosition);
    }

    /** 界面“取经验”：扣储经验并在玩家脚下生成经验球。 */
    public void takeExperienceOut(Player player, int requested) {
        if (this.level instanceof ServerLevel serverLevel && player != null) {
            int amount = this.extractExperience(requested);
            if (amount > 0) {
                ExperienceOrb.award(serverLevel, Vec3.atBottomCenterOf(player.blockPosition()), amount);
            }
        }
    }

    /**
     * 方块被破坏/替换时的善后（26.x 取代 {@code Block#onRemove} 的钩子，服务端调用）：
     * 掉下钉着的锯片实体、吐出储经验、解绑志愿者、散落槽内物品。
     *
     * <p><b>注意参数语义</b>：26.x 里 {@code LevelChunk#setBlockState} 传给本钩子的是
     * <em>被换掉的旧方块状态</em>（原版 {@code LecternBlockEntity} 就是拿它读 {@code HAS_BOOK}
     * 的），不是新状态。所以这里<b>不能</b>再照 1.21.1 的 {@code onRemove} 写
     * {@code getBlockState().is(新方块)} 的同块判断——那句话在这里恒为真，会把整个善后
     * 直接 return 掉，锯片就会钉在原地浮空、储经验和槽内物品也会一起吞掉。
     * 调用方本身已经保证"只在方块类型真的变了、且没被 {@code UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS}
     * 抑制"时才触发，原版 {@code CampfireBlockEntity} 等实现同样不加二次判断，这里保持一致。</p>
     */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState oldState) {
        super.preRemoveSideEffects(pos, oldState);
        if (!(this.level instanceof ServerLevel serverLevel)) {
            return;
        }
        for (OGESawbladeEntity blade : OGESawbladeEntity.findMountedOn(serverLevel, pos, null)) {
            blade.dropAndDiscard(serverLevel);
        }

        int xp = this.getStoredExperience();
        if (xp > 0) {
            ExperienceOrb.award(serverLevel, Vec3.atCenterOf(pos), xp);
        }

        if (this.boundVolunteerId != null
                && serverLevel.getEntityInAnyDimension(this.boundVolunteerId) instanceof OGEVolunteerWatcherEntity volunteer) {
            volunteer.removeBind(pos);
        }

        Containers.dropContents(serverLevel, pos, this);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("StoredExperience", this.storedExperience);
        output.putInt("BladeHits", this.bladeHits);
        if (this.boundVolunteerId != null) {
            output.putLong("BoundVolunteerMost", this.boundVolunteerId.getMostSignificantBits());
            output.putLong("BoundVolunteerLeast", this.boundVolunteerId.getLeastSignificantBits());
        }
        if (!this.volunteerBindName.isEmpty()) {
            output.putString("VolunteerBindName", this.volunteerBindName);
        }
        ContainerHelper.saveAllItems(output, this.items);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, this.items);
        this.storedExperience = Math.max(0, input.getIntOr("StoredExperience", 0));
        this.bladeHits = Math.max(0, input.getIntOr("BladeHits", 0));
        Optional<Long> most = input.getLong("BoundVolunteerMost");
        Optional<Long> least = input.getLong("BoundVolunteerLeast");
        this.boundVolunteerId = most.isPresent() && least.isPresent()
                ? new UUID(most.get(), least.get())
                // 1.21.1 旧存档用 putUUID("BoundVolunteer") 存的整型数组：升级上来的世界照样认得绑定。
                : input.read("BoundVolunteer", net.minecraft.core.UUIDUtil.CODEC).orElse(null);
        this.volunteerBindName = input.getStringOr("VolunteerBindName", "");
        this.boundResolveCountdown = 1;
        this.boundVolunteer = null;
    }
}
