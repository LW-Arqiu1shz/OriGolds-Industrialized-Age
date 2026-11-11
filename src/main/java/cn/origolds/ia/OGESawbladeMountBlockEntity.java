package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 金源圆锯刃槽的方块实体：武器槽 + 废物槽 + 经验库，也是所有刀刃的“主控”。
 *
 * <h2>刀刃与它的关系</h2>
 * <p>刀刃（{@link OGESawbladeEntity}）是独立实体，但伤害、耗电、耐久、经验全部由这里统一给：
 * 每片刀刃每 10 刻向它所在的那<b>一整格</b>出手一次（目标按整格 AABB 取，
 * 不看刀刃自己那张薄片的形状——土径这种矮方块上站着的生物也会被锯到）。
 * 出手先扣 1u OGE（从所在无线电网扣，扣不到就不出手）；所有附着刀刃共享刃槽的命中次数：
 * 普通武器总计每命中 2 次消耗 1 点耐久，圆锯刃片总计每命中 1250 次才消耗 1 点。</p>
 *
 * <p><b>绑定</b>：Ctrl+左键选中挂机志愿者、再 Ctrl+左键本刃槽后，本刃槽（连同它身上
 * 所有刀刃）的伤害都记在该志愿者名下，见 {@code OGEVolunteerBindManager}；
 * 权威绑定表存在志愿者身上，这里只留一份反查用的实体 id。</p>
 *
 * <h2>武器</h2>
 * <p>武器槽（UI 顶部居中）只收 {@link OGEMeleeWeapons 近战武器}；刀刃单次伤害 =
 * 面板伤害 × 面板攻速 ÷ 2（刀片 DPS 与武器的面板 DPS 一致）。
 * 放入的武器生效的附魔有三条：<b>耐久</b>（扣耐久时按原版概率豁免）、
 * <b>经验修补</b>（进库的经验先修武器）与<b>抢夺</b>（本刃槽的刀刃击杀的目标按等级多掉常规掉落）。
 * 抢夺是原版战利品表按“击杀者实体主手上的抢夺等级”算的，刀刃击杀的击杀者是绑定的挂机志愿者
 * （玩家模式下的致命一击记在它的隐藏代理玩家头上，让掉落走原版“被玩家击杀”的判定），
 * 所以伤害结算那一小段由击杀者代拿攻击器，见 {@link #loanLootingWeapon()}；
 * <b>没绑定志愿者的刃槽没有“击杀者”实体，抢夺无从谈起</b>（与经验只进绑定刃槽是同一套归属规则）。
 * 耐久耗尽：普通武器破碎消失；刃片例外——归 0 时装进废物槽；
 * 另外任何带附魔的武器耐久只剩 1 点时也装进废物槽（免得碎裂毁了附魔）。</p>
 *
 * <h2>经验</h2>
 * <p>被<b>绑定了挂机志愿者的</b>这里的刀刃击杀的目标（玩家也在内）经验不落地、直接存进刃槽
 * （见 {@link OGESawbladeMountEvents#onLivingDeath}）；武器带经验修补则先拿这份经验修武器。
 * 没绑定志愿者的刀刃击杀<b>不拦截</b>，按原版规则结算——等同摔死、烧死这类非玩家伤害，
 * 死者 100 刻内没被玩家碰过就不掉经验。
 * UI 可按数量取出，取出的经验以经验球掉在操作者脚下；
 * 作为无线物流管道的起点时输出的是存储的经验而不是物品
 * （终点是刃槽则入库，是别的方块则在终点落地成经验球），见 {@link OGELogisticsManager}。</p>
 *
 * <h2>对外接口</h2>
 * <p>漏斗与物流管道走 {@link #getItemHandler()}：输入只进武器槽（且必须是近战武器），
 * 吸取只出废物槽；金源超级漏斗按 {@code Container} 遍历槽位，由
 * {@link IOGESuctionFilter} 挡住武器槽。</p>
 */
public final class OGESawbladeMountBlockEntity extends BaseContainerBlockEntity implements IOGESuctionFilter {

    /** 槽位：顶部武器槽。 */
    public static final int SLOT_WEAPON = 0;
    /** 槽位：底部废物槽。 */
    public static final int SLOT_WASTE = 1;
    /** 容器尺寸。 */
    public static final int SLOT_COUNT = 2;

    /** 普通武器：每命中 2 次消耗 1 点耐久。 */
    public static final int HITS_PER_WEAR = 2;
    /** 圆锯刃片：每命中 1250 次才消耗 1 点耐久。 */
    public static final int SAWBLADE_HITS_PER_WEAR = 1250;

    /** 电网可用性的重估周期（刻）。 */
    private static final int POWER_CHECK_INTERVAL = 10;

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);

    /** 电网里是否还有至少 1u OGE 可用（服务端缓存，每 {@value #POWER_CHECK_INTERVAL} 刻重估）。 */
    private boolean powered;
    /** 距下一次电网重估的刻数。 */
    private int powerCountdown = 1;
    /** 存储的经验值。 */
    private int storedExperience;
    /** 所有附着刀刃共享的命中计数。 */
    private int bladeHits;
    /**
     * 绑定了哪个挂机志愿者（权威数据在志愿者身上，这里只是反查用的引用；
     * 由志愿者侧的绑定流程与每 20 刻的对账维护）。
     */
    private java.util.UUID boundVolunteerId;
    /** 上者的服务端解析缓存：每 20 刻按 id 查一次实体，刀刃出手直接读这份。 */
    private OGEVolunteerWatcherEntity boundVolunteer;
    private int boundResolveCountdown = 1;
    /**
     * 挂机志愿者绑定管理界面里给本刃槽起的显示名（空串＝显示默认的"圆锯刃槽[x,y,z]"）。
     *
     * <p>这份名字是<b>唯一权威</b>：所有绑定了本刃槽的志愿者、以及带着这些绑定的物品，
     * 共用它并互相同步，志愿者自己不再各存一份。理由见 {@link OGEVolunteerBindEntry}。</p>
     */
    private String volunteerBindName = "";

    /** 对外暴露的物品能力：输入只进武器槽、吸取只出废物槽。 */
    private final IItemHandler itemHandler = new OGESawbladeMountItemHandler(this);

    public OGESawbladeMountBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SAWBLADE_MOUNT.get(), pos, state);
    }

    // ------------------------------------------------------------------
    // Container 骨架
    // ------------------------------------------------------------------

    @Override
    public int getContainerSize() {
        return SLOT_COUNT;
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {
        this.items = items;
    }

    /** 武器槽只收近战武器；废物槽什么都收（玩家手动塞垃圾也行）。 */
    @Override
    public boolean canPlaceItem(int index, ItemStack stack) {
        if (index == SLOT_WEAPON) {
            return OGEMeleeWeapons.isMeleeWeapon(stack);
        }
        return index == SLOT_WASTE && !stack.isEmpty();
    }

    /** 超级漏斗按容器遍历：只让它看见废物槽，武器槽不许吸。 */
    @Override
    public boolean canSuckSlot(int slot) {
        return slot == SLOT_WASTE;
    }

    /** 武器刚进入槽位时立即重置共享计数，并执行一次报废判定。 */
    @Override
    public void setItem(int index, ItemStack stack) {
        super.setItem(index, stack);
        if (index == SLOT_WEAPON) {
            bladeHits = 0;
            if (shouldMoveWeaponToWaste()) {
                moveWeaponToWaste();
            }
        }
    }

    // ------------------------------------------------------------------
    // 主循环（服务端）
    // ------------------------------------------------------------------

    public void tick() {
        if (level == null || level.isClientSide || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (shouldMoveWeaponToWaste()) {
            moveWeaponToWaste();
        } else {
            repairWeaponWithStoredExperience();
        }
        if (--powerCountdown <= 0) {
            powerCountdown = POWER_CHECK_INTERVAL;
            powered = OGEWirelessManager.availableOGE(
                    serverLevel.getServer(), serverLevel.dimension(), worldPosition) >= 1;
        }
        if (--boundResolveCountdown <= 0) {
            boundResolveCountdown = 20;
            boundVolunteer = resolveBoundVolunteer(serverLevel);
        }
    }

    // ------------------------------------------------------------------
    // 挂机志愿者绑定（反查引用；权威列表存在志愿者身上）
    // ------------------------------------------------------------------

    /** 绑定到的志愿者实体 id；没绑定时为 {@code null}。 */
    public java.util.UUID getBoundVolunteerId() {
        return boundVolunteerId;
    }

    /** 设置（或清空）绑定的志愿者，并立即刷新解析缓存。 */
    public void setBoundVolunteer(java.util.UUID volunteerId) {
        this.boundVolunteerId = volunteerId;
        if (level instanceof ServerLevel serverLevel) {
            this.boundVolunteer = resolveBoundVolunteer(serverLevel);
        } else {
            this.boundVolunteer = null;
        }
    }

    /** 当前生效的绑定志愿者实体；没绑定 / 实体不在（区块卸载、已被收回）时为 {@code null}。 */
    public OGEVolunteerWatcherEntity getBoundVolunteer() {
        return boundVolunteer != null && boundVolunteer.isAlive() && !boundVolunteer.isRemoved()
                ? boundVolunteer : null;
    }

    private OGEVolunteerWatcherEntity resolveBoundVolunteer(ServerLevel serverLevel) {
        if (boundVolunteerId == null) {
            return null;
        }
        return serverLevel.getEntity(boundVolunteerId) instanceof OGEVolunteerWatcherEntity volunteer
                && !volunteer.isRemoved() ? volunteer : null;
    }

    /** 绑定管理界面里本刃槽的显示名；空串表示没改名（界面按坐标显示默认名）。 */
    public String getVolunteerBindName() {
        return volunteerBindName;
    }

    /**
     * 设置本刃槽在绑定管理界面里的显示名（改名唯一的落盘处，空串回到默认名）。
     *
     * <p>名字记在刃槽这边而不是志愿者那边，所以在任一志愿者的界面里改名，
     * 其它绑定了本刃槽的志愿者看到的名字会一起变；调用方改完后自行广播
     * {@link OGEVolunteerBindNamePayload} 刷新已经打开的界面。</p>
     */
    public void setVolunteerBindName(String name) {
        String safe = name == null ? "" : name.strip();
        if (safe.length() > OGEVolunteerBindManager.MAX_BIND_NAME_LENGTH) {
            safe = safe.substring(0, OGEVolunteerBindManager.MAX_BIND_NAME_LENGTH);
        }
        if (safe.equals(volunteerBindName)) {
            return;
        }
        volunteerBindName = safe;
        setChanged();
    }

    /** 刀刃此刻该不该转（也决定该不该出手）：电网里至少还有 1u。 */
    public boolean isBladePowered() {
        return powered;
    }

    /** 当前武器对应的耐久阈值：刃片 1250，其它近战武器 2。 */
    public int wearThreshold() {
        ItemStack weapon = getItem(SLOT_WEAPON);
        return weapon.getItem() instanceof OGECircularSawbladeItem ? SAWBLADE_HITS_PER_WEAR : HITS_PER_WEAR;
    }

    /** 当前武器的单次刀刃伤害；没放武器 / 武器已耗尽时返回 0（不出手）。 */
    public double bladeDamagePerHit() {
        ItemStack weapon = getItem(SLOT_WEAPON);
        if (weapon.isEmpty() || !OGEMeleeWeapons.isMeleeWeapon(weapon) || !weaponUsable(weapon)) {
            return 0.0D;
        }
        return OGEMeleeWeapons.bladeDamagePerHit(weapon);
    }

    /** 武器还有耐久可烧（不可损耗的物品视为无限）。 */
    public static boolean weaponUsable(ItemStack weapon) {
        return !weapon.isDamageableItem() || weapon.getDamageValue() < weapon.getMaxDamage();
    }

    /** 武器上 {@code key} 这个附魔的等级；没附就是 0。 */
    private static int enchantmentLevel(ItemStack weapon, ResourceKey<Enchantment> key) {
        for (Holder<Enchantment> enchantment : weapon.getEnchantments().keySet()) {
            if (enchantment.is(key)) {
                return weapon.getEnchantments().getLevel(enchantment);
            }
        }
        return 0;
    }

    /** 武器是否附了 {@code key} 这个附魔。 */
    private static boolean hasEnchantment(ItemStack weapon, ResourceKey<Enchantment> key) {
        return enchantmentLevel(weapon, key) > 0;
    }

    /**
     * 借给“击杀者”临时握着的攻击器<b>副本</b>；攻击器没附抢夺时返回空（不值得换手）。
     *
     * <p>原版的抢夺加成（战利品函数 {@code enchanted_count_increase}、条件
     * {@code random_chance_with_enchanted_bonus}）一律按<b>击杀者实体主手上</b>的抢夺等级读取。
     * 刀刃击杀的击杀者是绑定志愿者那个实体——玩家模式下致命一击改记在它的隐藏代理玩家
     * （{@link OGEVolunteerWatcherEntity#playerModeKillProxy()}，让掉落按原版"被玩家击杀"结算）——
     * 它们手上本来什么都没有，所以抢夺读不到——{@link OGESawbladeEntity#tick()} 在伤害
     * （含死亡掉落）结算的这一小段时间里把这份副本塞进击杀者主手，结算完立刻换回原样。</p>
     *
     * <p>给出去的是副本：它只会被读附魔，不参与扣耐久、也不参与经验修补，
     * 更不会被写回武器槽，所以不存在复制或损耗攻击器的问题。</p>
     */
    public ItemStack loanLootingWeapon() {
        ItemStack weapon = getItem(SLOT_WEAPON);
        if (weapon.isEmpty() || !hasEnchantment(weapon, Enchantments.LOOTING)) {
            return ItemStack.EMPTY;
        }
        return weapon.copy();
    }

    /** 出手前扣 1u OGE；扣不到返回 false（本次不出手）。 */
    public boolean payBladeAttack(ServerLevel serverLevel) {
        return OGEWirelessManager.consumeOGEIfAvailable(
                serverLevel.getServer(), serverLevel.dimension(), worldPosition, 1) >= 1;
    }

    /**
     * 任意一片刀刃打完一次（命中过至少一个目标）之后统一给刃槽记账。
     * 所有附着刀刃共享此计数；到阈值就“尝试”消耗 1 点武器耐久——
     * 带耐久附魔的武器按原版概率可能豁免消耗，但次数一定清零。
     */
    public void onBladeHits() {
        if (level == null || level.isClientSide) {
            return;
        }
        if (++bladeHits < wearThreshold()) {
            setChanged();
            return;
        }
        bladeHits = 0;
        consumeWeaponDurability();
    }

    /** 尝试消耗 1 点武器耐久，并处理破碎 / 进废物槽 / 附魔保底规则。 */
    private void consumeWeaponDurability() {
        ItemStack weapon = getItem(SLOT_WEAPON);
        if (weapon.isEmpty() || !weapon.isDamageableItem()) {
            return;
        }
        int unbreaking = enchantmentLevel(weapon, Enchantments.UNBREAKING);
        // 原版耐久概率：nextInteger(level+1)==0 才真的掉耐久（0 级必掉）
        if (unbreaking <= 0 || level.getRandom().nextInt(unbreaking + 1) == 0) {
            weapon.setDamageValue(weapon.getDamageValue() + 1);
        }
        setChanged();

        int remaining = weapon.getMaxDamage() - weapon.getDamageValue();
        if (remaining <= 0 && !(weapon.getItem() instanceof OGECircularSawbladeItem)) {
            // 普通武器：破碎，直接消失
            setItem(SLOT_WEAPON, ItemStack.EMPTY);
            return;
        }
        if (shouldMoveWeaponToWaste()) {
            moveWeaponToWaste();
        }
    }

    /** 刃片耗尽，或任意附魔武器只剩 1 点耐久时，应转入废物槽。 */
    private boolean shouldMoveWeaponToWaste() {
        ItemStack weapon = getItem(SLOT_WEAPON);
        if (weapon.isEmpty() || !weapon.isDamageableItem()) {
            return false;
        }
        int remaining = weapon.getMaxDamage() - weapon.getDamageValue();
        return (weapon.getItem() instanceof OGECircularSawbladeItem && remaining <= 0)
                || (!weapon.getEnchantments().isEmpty() && remaining <= 1);
    }

    /** 武器槽的东西挪进废物槽；废物槽放不下就保持原样并返回 false（下次再试）。 */
    private boolean moveWeaponToWaste() {
        ItemStack weapon = getItem(SLOT_WEAPON);
        if (weapon.isEmpty()) {
            return true;
        }
        if (!getItem(SLOT_WASTE).isEmpty()) {
            return false;
        }
        setItem(SLOT_WASTE, weapon.copy());
        setItem(SLOT_WEAPON, ItemStack.EMPTY);
        setChanged();
        return true;
    }

    // ------------------------------------------------------------------
    // 经验：收集 / 修补 / 取出 / 管道
    // ------------------------------------------------------------------

    /**
     * 这里的刀刃击杀了一头生物：把这头生物该给的经验收进刃槽。
     * 规则对齐原版掉落经验的门槛（不幼崽、mobGriefing 开启、经验数为正）。
     * 玩家也在收集之列：拿的是原版"玩家死亡掉落经验"那份
     * （min(7×等级, 100)，开 keepInventory 或旁观模式时为 0，经验照旧留在玩家身上）。
     */
    public void collectKillExperience(ServerLevel serverLevel, LivingEntity victim, Entity attacker) {
        if (!(victim instanceof Player)
                && (!serverLevel.getGameRules().getBoolean(GameRules.RULE_DOMOBLOOT)
                        || !victim.shouldDropExperience())) {
            return;
        }
        int reward = victim.getExperienceReward(serverLevel, attacker);
        if (reward > 0) {
            addExperience(reward);
        }
    }

    /** 存入经验，并立即尝试用经验库修满带经验修补的武器。 */
    public void addExperience(int amount) {
        if (amount <= 0) {
            return;
        }
        long total = (long) storedExperience + amount;
        storedExperience = total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
        repairWeaponWithStoredExperience();
        setChanged();
    }

    /** 持续使用库存经验修补武器；原版比例为 1 点经验修复 2 点耐久。 */
    private void repairWeaponWithStoredExperience() {
        ItemStack weapon = getItem(SLOT_WEAPON);
        if (storedExperience <= 0 || weapon.isEmpty() || !weapon.isDamageableItem()
                || weapon.getDamageValue() <= 0 || !hasEnchantment(weapon, Enchantments.MENDING)) {
            return;
        }
        int repair = Math.min(weapon.getDamageValue(), storedExperience * 2);
        int cost = (repair + 1) / 2;
        weapon.setDamageValue(weapon.getDamageValue() - repair);
        storedExperience -= cost;
        setChanged();
    }

    /** 当前存储的经验值。 */
    public int getStoredExperience() {
        return storedExperience;
    }

    /** 最多取出 {@code requested} 点经验并返回实际取出量（扣账在这里）。 */
    public int extractExperience(int requested) {
        if (requested <= 0) {
            return 0;
        }
        int amount = Math.min(requested, storedExperience);
        if (amount <= 0) {
            return 0;
        }
        storedExperience -= amount;
        setChanged();
        return amount;
    }

    // ------------------------------------------------------------------
    // 菜单与能力
    // ------------------------------------------------------------------

    public IItemHandler getItemHandler() {
        return itemHandler;
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("block.origolds_ia.origold_sawblade_mount");
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory playerInventory) {
        return new OGESawbladeMountMenu(containerId, playerInventory, worldPosition);
    }

    /** UI“取出”按钮：取出经验并以经验球掉在操作者脚下。 */
    public void takeExperienceOut(Player player, int requested) {
        if (!(level instanceof ServerLevel serverLevel) || player == null) {
            return;
        }
        int amount = extractExperience(requested);
        if (amount > 0) {
            ExperienceOrb.award(serverLevel, Vec3.atBottomCenterOf(player.blockPosition()), amount);
        }
    }

    // ------------------------------------------------------------------
    // 存盘
    // ------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("StoredExperience", storedExperience);
        tag.putInt("BladeHits", bladeHits);
        if (boundVolunteerId != null) {
            tag.putUUID("BoundVolunteer", boundVolunteerId);
        }
        if (!volunteerBindName.isEmpty()) {
            tag.putString("VolunteerBindName", volunteerBindName);
        }
        ContainerHelper.saveAllItems(tag, items, registries);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, items, registries);
        storedExperience = Math.max(0, tag.getInt("StoredExperience"));
        bladeHits = Math.max(0, tag.getInt("BladeHits"));
        boundVolunteerId = tag.hasUUID("BoundVolunteer") ? tag.getUUID("BoundVolunteer") : null;
        volunteerBindName = tag.getString("VolunteerBindName");
        boundResolveCountdown = 1;
        boundVolunteer = null;
    }
}
