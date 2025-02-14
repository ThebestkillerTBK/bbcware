package bbcdevelopment.addon.bbcaddon.modules.combat;

import bbcdevelopment.addon.bbcaddon.BBCAddon;
import bbcdevelopment.addon.bbcaddon.event.BlockUpdateEvent;
import bbcdevelopment.addon.bbcaddon.event.BreakBlockEvent;
import bbcdevelopment.addon.bbcaddon.modules.BBCModule;
import bbcdevelopment.addon.bbcaddon.utils.entity.TargetHelper;
import bbcdevelopment.addon.bbcaddon.utils.math.TimerUtils;
import bbcdevelopment.addon.bbcaddon.utils.player.InvHelper;
import bbcdevelopment.addon.bbcaddon.utils.world.BlockHelper;
import bbcdevelopment.addon.bbcaddon.utils.world.BlockPosX;
import bbcdevelopment.addon.bbcaddon.utils.world.DamageHelper;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.block.FireBlock;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DeathAnchor extends BBCModule {
    public DeathAnchor(){
        super(BBCAddon.BBC, "Death Anchor", "");
    }

    private final SettingGroup sgPlacing = settings.createGroup("Placing");
    private final SettingGroup sgCalculating = settings.createGroup("Calculating");
    private final SettingGroup sgDamage = settings.createGroup("Damage");
    private final SettingGroup sgInventory = settings.createGroup("Inventory");
    private final SettingGroup sgPause = settings.createGroup("Pause");
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgDebug = settings.createGroup("Debug");


    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final Setting<Double> enemyRange = sgGeneral.add(new DoubleSetting.Builder().name("enemy-range").defaultValue(15).range(1, 30).build());


    private final Setting<Double> placeRange = sgPlacing.add(new DoubleSetting.Builder().name("place-range").description("The distance where you can place/break anchors.").defaultValue(5).range(0, 7).sliderRange(0, 7).build());
    private final Setting<Integer> radius = sgPlacing.add(new IntSetting.Builder().name("radius").description("Delay between place.").defaultValue(5).range(0, 10).sliderRange(0, 10).build());
    private final Setting<Integer> height = sgPlacing.add(new IntSetting.Builder().name("height").description("Delay between place.").defaultValue(3).range(0, 7).sliderRange(0, 7).build());
    private final Setting<Integer> placeDelay = sgPlacing.add(new IntSetting.Builder().name("place-delay").description("Delay between place.").defaultValue(10).range(0, 20).sliderRange(0, 20).build());
    private final Setting<Boolean> airPlace = sgPlacing.add(new BoolSetting.Builder().name("air-place").description("Disabling air place.").defaultValue(true).build());
    private final Setting<Boolean> terrainIgnore = sgPlacing.add(new BoolSetting.Builder().name("terrain-ignore").description("Ignore blocks with blast resistane less than 600.").defaultValue(true).build());
    private final Setting<Boolean> quickPlace = sgPlacing.add(new BoolSetting.Builder().name("quick-place").description("Immediately places the anchor after breaking the self-trap/surround.").defaultValue(true).build());


    private final Setting<Boolean> useThread = sgCalculating.add(new BoolSetting.Builder().name("use-thread").description("Calculate positions in a separate thread.").defaultValue(true).build());
    private final Setting<CalculatingMode> calculatingMode = sgCalculating.add(new EnumSetting.Builder<CalculatingMode>().name("calculating-mode").description("Position calculation method.").defaultValue(CalculatingMode.Normal).build());
    private final Setting<Double> minDistance = sgCalculating.add(new DoubleSetting.Builder().name("min-distance").description("Min distance to target.").defaultValue(3).range(1, 5).sliderRange(1, 5).visible(() -> calculatingMode.get() == CalculatingMode.ByDistance).build());
    private final Setting<Double> radiusFromTarget = sgCalculating.add(new DoubleSetting.Builder().name("radius-from-target").description("Radius around the target.").defaultValue(3).range(1, 5).sliderRange(1, 5).visible(() -> calculatingMode.get() == CalculatingMode.ByRadius).build());


    private final Setting<DamageMode> damageMode = sgDamage.add(new EnumSetting.Builder<DamageMode>().name("damage-mode").description("Damage calculation method.").defaultValue(DamageMode.BestDamage).build());
    private final Setting<Double> minDamage = sgDamage.add(new DoubleSetting.Builder().name("min-damage").description("Minimum damage for place.").defaultValue(7.0).range(0, 36).sliderRange(0, 36).build());
    private final Setting<Boolean> lethalDamage = sgDamage.add(new BoolSetting.Builder().name("lethal-damage").description("Keep placing anchors ignoring damage if the target is low on HP.").defaultValue(true).build());
    private final Setting<Double> lethalHealth = sgDamage.add(new DoubleSetting.Builder().name("lethal-health").description("Health point at which the lethal damage function will turn on.").defaultValue(3).range(0, 36).sliderRange(0, 36).visible(lethalDamage::get).build());
    private final Setting<Double> safety = sgDamage.add(new DoubleSetting.Builder().name("safety").description("By what percentage should the target damage be greater than the self damage in order to continue to place.").defaultValue(25).range(0, 100).sliderRange(0, 100).build());
    private final Setting<Boolean> antiSelfPop = sgDamage.add(new BoolSetting.Builder().name("anti-self-pop").description("Try not to deal lethal damage to yourself.").defaultValue(true).build());
    private final Setting<Boolean> antiFriendPop = sgDamage.add(new BoolSetting.Builder().name("anti-friend-pop").description("Try not to deal lethal damage to friends.").defaultValue(true).build());
    private final Setting<Double> maxFriendDamage = sgDamage.add(new DoubleSetting.Builder().name("max-friend-damage").description("Maximum damage that can be dealt to a friend.").defaultValue(8).range(0, 36).sliderRange(0, 36).visible(antiFriendPop::get).build());


    private final Setting<SwapMode> swapMode = sgInventory.add(new EnumSetting.Builder<SwapMode>().name("swap-mode").description("Slot swap method.").defaultValue(SwapMode.Silent).build());
    private final Setting<Boolean> syncSlot = sgInventory.add(new BoolSetting.Builder().name("sync-slot").description("Synchronize the slot to get rid of fakes.").defaultValue(true).build());
    private final Setting<Boolean> refill = sgInventory.add(new BoolSetting.Builder().name("refill").description("Moves anchors into a selected hotbar slot.").defaultValue(true).build());
    private final Setting<Integer> refillAnchorSlot = sgInventory.add(new IntSetting.Builder().name("refill-anchor-slot").description("The slot auto move moves anchors to.").defaultValue(5).range(1, 9).sliderRange(1, 9).visible(refill::get).build());
    private final Setting<Integer> refillGlowStoneSlot = sgInventory.add(new IntSetting.Builder().name("refill-glowstone-slot").description("The slot auto move moves anchors to.").defaultValue(6).range(1, 9).sliderRange(1, 9).visible(refill::get).build());


    private final Setting<Boolean> pauseOnEat = sgPause.add(new BoolSetting.Builder().name("pause-on-eat").description("Pause placing while eating.").defaultValue(false).build());
    private final Setting<Boolean> pauseOnDrink = sgPause.add(new BoolSetting.Builder().name("pause-on-drink").description("Pause placing while drinking.").defaultValue(false).build());
    private final Setting<Boolean> pauseOnMine = sgPause.add(new BoolSetting.Builder().name("pause-on-mine").description("Pause placing while mining.").defaultValue(false).build());


    private final Setting<Boolean> swing = sgRender.add(new BoolSetting.Builder().name("swing").description("Whether to swing hand client-side.").defaultValue(true).build());
    private final Setting<RenderMode> render = sgRender.add(new EnumSetting.Builder<RenderMode>().name("render").description("Renders the block where it is placing a anchor.").defaultValue(RenderMode.Smooth).build());
    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>().name("shape-mode").description("How the shapes are rendered.").defaultValue(ShapeMode.Both).visible(() -> render.get() != RenderMode.Off).build());
    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder().name("side-color").description("The side color for positions to be placed.").defaultValue(new SettingColor(15, 255, 211,75)).visible(() -> render.get() != RenderMode.Off).build());
    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder().name("line-color").description("The line color for positions to be placed.").defaultValue(new SettingColor(15, 255, 211)).visible(() -> render.get() != RenderMode.Off).build());
    private final Setting<Integer> smoothFactor = sgRender.add(new IntSetting.Builder().name("smooth-factor").description("Speed of transition from position to another.").defaultValue(6).range(1, 30).sliderRange(1, 30).visible(() -> render.get() == RenderMode.Smooth).build());
    private final Setting<Integer> fadeTime = sgRender.add(new IntSetting.Builder().name("fade-time").description("Render fade time.").defaultValue(10).range(1, 30).sliderRange(1, 30).visible(() -> render.get() == RenderMode.Fade).build());


    private final Setting<Boolean> debugChat = sgDebug.add(new BoolSetting.Builder().name("debug-chat").description("Send information to the chat.").defaultValue(false).build());
    private final Setting<Boolean> debugRender = sgDebug.add(new BoolSetting.Builder().name("debug-render").description("Render information.").defaultValue(false).build());

    private final ExecutorService thread = Executors.newScheduledThreadPool(2);


    private List<PlayerEntity> targets = new ArrayList<>();
    private List<BlockPosX> finalSphere = new ArrayList<>();
    private List<BlockPosX> renderSphere;

    private BlockPosX bestPos;
    private double bestDamage;

    private final TimerUtils placeTimer = new TimerUtils();

    private long lastTime;

    private Box renderBox;

    @Override
    public void onActivate() {

    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc == null || mc.player == null || mc.world == null) return;

        if (!isValidDimension()) return;
        FindItemResult anchor = InvUtils.find(Items.RESPAWN_ANCHOR);
        FindItemResult glowstone = InvUtils.find(Items.GLOWSTONE);

        if (!anchor.found()){
            warning("No respawn anchor");
            toggle();
            return;
        }
        if (!glowstone.found()){
            warning("No glowstone");
            toggle();
            return;
        }

        if (refill.get() && swapMode.get() != SwapMode.Inventory) doRefill(anchor, glowstone);
        if (shouldPause()) return;

        targets = TargetHelper.getTargetsInRange(enemyRange.get());
        if (targets.isEmpty()) return;

        if (useThread.get()) thread.execute(this::doCalculate);
        else doCalculate();

        if (placeTimer.passedTicks(placeDelay.get())){
            placeTimer.reset();
            if (bestPos != null){
                doPlace();
            }
        }
    }

    @EventHandler
    private void onUpdate(BlockUpdateEvent event){
        if (!quickPlace.get()) return;
        BlockPosX pos = new BlockPosX(event.pos);
        if (isReplaceable(pos) && !event.oldState.isOf(Blocks.RESPAWN_ANCHOR)){
            quickPlace(pos);
        }
    }

    @EventHandler
    private void onBreak(BreakBlockEvent event){
        if (!quickPlace.get()) return;
        quickPlace(new BlockPosX(event.getPos()));
    }

    @EventHandler
    private void onRender(Render3DEvent event){
        if (bestPos == null || render.get() == RenderMode.Off) return;

        try {
            if (debugRender.get() && renderSphere != null && !renderSphere.isEmpty()){
                renderSphere.forEach(pos -> {
                    event.renderer.box(pos, new Color().a(10), new Color().a(100), ShapeMode.Both, 0);
                });
            }
        }catch (ConcurrentModificationException ignored){
        }

        Box box = new Box(bestPos);

        switch (render.get()){
            case Fade -> {

            }
            case Smooth -> renderBox(event, box);
        }
    }

    // [Misc] //

    private boolean isValidDimension(){
        if (mc.world.getDimension().respawnAnchorWorks()){
            warning("It is nether...toggle");
            toggle();
            return false;
        }
        return true;
    }

    private boolean shouldPause(){
        return PlayerUtils.shouldPause(pauseOnMine.get(), pauseOnEat.get(), pauseOnDrink.get());
    }

    private boolean isReplaceable(BlockPosX pos){
        return pos.air() || BlockHelper.getState(pos).getFluidState().isStill() || pos.of(FireBlock.class) || pos.of(Blocks.GRASS) || pos.of(Blocks.TALL_GRASS) || pos.of(Blocks.SEAGRASS);
    }

    // [Inv] //

    private void doRefill(FindItemResult anchor, FindItemResult glowStone){
        if (!anchor.found() || anchor.slot() == refillAnchorSlot.get() - 1) return;
        InvUtils.move().from(anchor.slot()).toHotbar(refillAnchorSlot.get() - 1);

        if (!glowStone.found() || glowStone.slot() == refillGlowStoneSlot.get() - 1) return;
        InvUtils.move().from(glowStone.slot()).toHotbar(refillGlowStoneSlot.get() - 1);
    }

    // [Place] //

    private void doPlace(){
        doPlace(bestPos);
    }

    private void doPlace(BlockPosX bestPos){
        assert mc.interactionManager != null;
        assert mc.getNetworkHandler() != null;
        assert mc.world != null;

        FindItemResult anchor = InvUtils.find(Items.RESPAWN_ANCHOR);
        FindItemResult glowstone = InvUtils.find(Items.GLOWSTONE);

        if (bestPos.of(Blocks.RESPAWN_ANCHOR)){
            if (bestPos.state().get(Properties.CHARGES) >= 1){
                breakBlock(bestPos);
            }
            else {
                placeBlock(bestPos, glowstone);
                breakBlock(bestPos);
            }
        }
        else {
            placeBlock(bestPos, anchor);
            placeBlock(bestPos, glowstone);
            breakBlock(bestPos);
        }
    }

    private void placeBlock(BlockPosX posX, FindItemResult itemResult){
        if (swapMode.get() == SwapMode.Silent || swapMode.get() == SwapMode.Normal && !itemResult.isOffhand() && !itemResult.isMain()){
            InvUtils.swap(itemResult.slot(), true);
        }
        boolean holdsAnchor = (itemResult.isOffhand() || itemResult.isMainHand()) || swapMode.get() == SwapMode.Inventory;
        if (holdsAnchor){
            mc.player.swingHand(mc.player.getActiveHand());
            if (swapMode.get() == SwapMode.Inventory) {
                move(itemResult, () -> place(posX));
            } else place(posX);
        }

        boolean canSilent = swapMode.get() == SwapMode.Silent || (itemResult.isHotbar() && swapMode.get() == SwapMode.Inventory);
        if (canSilent) {
            InvUtils.swapBack();
            if (syncSlot.get()) InvHelper.syncSlot();
        }
    }

    private void breakBlock(BlockPosX posX){
        BlockHitResult breakResult = new BlockHitResult(posX.closestVec3d(), Direction.UP, posX, false);
        mc.interactionManager.interactBlock(mc.player, Hand.OFF_HAND, breakResult);
    }

    private void place(BlockPosX posX){
        BlockHitResult placeResult = new BlockHitResult(posX.closestVec3d(), Direction.UP, posX, false);
        BlockHitResult breakResult = new BlockHitResult(posX.closestVec3d(), Direction.UP, posX, false);

        mc.getNetworkHandler().sendPacket(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, placeResult, 0));
        mc.interactionManager.interactBlock(mc.player, Hand.OFF_HAND, breakResult);
    }

    private void quickPlace(BlockPosX posX){
        FindItemResult anchor = InvUtils.find(Items.RESPAWN_ANCHOR);
        if (anchor.found()) placeBlock(posX, anchor);
    }

    public boolean move(FindItemResult itemResult, Runnable runnable) {
        if (itemResult.isOffhand()) {
            runnable.run();
            return true;
        }

        move(mc.player.getInventory().selectedSlot, itemResult.slot());
        runnable.run();
        move(mc.player.getInventory().selectedSlot, itemResult.slot());
        return true;
    }

    private void move(int from, int to) {
        ScreenHandler handler = mc.player.currentScreenHandler;

        Int2ObjectArrayMap<ItemStack> stack = new Int2ObjectArrayMap<>();
        stack.put(to, handler.getSlot(to).getStack());

        mc.getNetworkHandler().sendPacket(new ClickSlotC2SPacket(handler.syncId, handler.getRevision(), PlayerInventory.MAIN_SIZE + from, to, SlotActionType.SWAP, handler.getCursorStack().copy(), stack));
    }

    // [Calculating] //

    private void doCalculate(){
        long pre = System.currentTimeMillis();

        BlockPosX bestPos = null;
        double bestDamage = 0.0;
        double safety = 0.0;
        int size = 0;

        if (debugRender.get()) renderSphere = new ArrayList<>();

        BlockPosX p = new BlockPosX(mc.player.getBlockPos());

        for (int i = p.getX() - radius.get(); i < p.getX() + radius.get(); i++) {
            for (int j = p.getY() - height.get(); j < p.getY() + height.get(); j++) {
                for (int k = p.getZ() - radius.get(); k < p.getZ() + radius.get(); k++) {
                    BlockPosX pos = new BlockPosX(i, j, k);
                    if (BlockHelper.distance(p, pos) <= radius.get()) {
                        if ((!isReplaceable(pos) && !(pos.of(Blocks.RESPAWN_ANCHOR))) || (!airPlace.get() && !pos.down().solid()) || EntityUtils.intersectsWithEntity(new Box(pos), entity -> entity instanceof EndCrystalEntity || entity instanceof PlayerEntity)) continue;

                        boolean shouldSkip = true;

                        switch (calculatingMode.get()){
                            case Normal -> shouldSkip = false;
                            case ByRadius -> {
                                for (PlayerEntity target : targets){
                                    if (pos.distance(target) <= radiusFromTarget.get()) shouldSkip = false;
                                }
                            }
                            case ByDistance -> {
                                for (PlayerEntity target : targets){
                                    double distance = mc.player.distanceTo(target);
                                    if (distance < minDistance.get()) distance = minDistance.get();
                                    if (pos.distance(target) <= distance) shouldSkip = false;
                                }
                            }
                        }

                        if (shouldSkip) continue;

                        double targetDamage = getBestDamage(pos);
                        double selfDamage = DamageHelper.anchorDamage(mc.player, pos, terrainIgnore.get());
                        safety = (targetDamage / 36 - selfDamage / 36) * 100;

                        if (safety < this.safety.get()
                            || antiSelfPop.get() && selfDamage > mc.player.getHealth() + mc.player.getAbsorptionAmount())
                            continue;

                        boolean validPos = true;
                        if (antiFriendPop.get()) {
                            for (PlayerEntity friend : mc.world.getPlayers()) {
                                if (!Friends.get().isFriend(friend)) continue;

                                double friendDamage = DamageHelper.anchorDamage(friend, pos, terrainIgnore.get());
                                if (friendDamage > maxFriendDamage.get() || EntityUtils.getTotalHealth(friend) - friendDamage <= 0) {
                                    validPos = false;
                                    break;
                                }
                            }
                        }

                        if (!validPos) continue;

                        if (debugChat.get()) size++;
                        if (debugRender.get()) renderSphere.add(pos);

                        if (targetDamage > bestDamage) {
                            bestDamage = targetDamage;
                            bestPos = pos;
                        }
                    }
                }
            }
        }
        this.bestPos = bestPos;
        this.bestDamage = bestDamage;
        this.lastTime = System.currentTimeMillis() - pre;
        if (debugChat.get()) info(lastTime + ": " + size);
    }

    private double getBestDamage(BlockPos pos) {
        double highestDamage = 0;

        for (PlayerEntity player : targets) {
            double targetDamage = DamageHelper.anchorDamage(player, pos, terrainIgnore.get());
            double health = player.getHealth() + player.getAbsorptionAmount();

            if (targetDamage >= minDamage.get() || (lethalDamage.get() && health - targetDamage <= lethalHealth.get())) {
                switch (damageMode.get()) {
                    case BestDamage -> {
                        if (targetDamage > highestDamage) {
                            highestDamage = targetDamage;
                        }
                    }
                    case MostDamage -> highestDamage += targetDamage;
                }
            }
        }
        return highestDamage;
    }

    // [Render] //

    private void renderBox(Render3DEvent event, Box post){
        if (renderBox == null) renderBox = post;

        double minxX = (post.minX - renderBox.minX) / smoothFactor.get();
        double minxY = (post.minY - renderBox.minY) / smoothFactor.get();
        double minxZ = (post.minZ - renderBox.minZ) / smoothFactor.get();

        double maxX = (post.maxX - renderBox.maxX) / smoothFactor.get();
        double maxY = (post.maxY - renderBox.maxY) / smoothFactor.get();
        double maxZ = (post.maxZ - renderBox.maxZ) / smoothFactor.get();

        renderBox = new Box(renderBox.minX + minxX, renderBox.minY + minxY, renderBox.minZ + minxZ, renderBox.maxX + maxX, renderBox.maxY + maxY,  renderBox.maxZ + maxZ);

        event.renderer.box(renderBox, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
    }

    public enum RenderMode{
        Off,
        Fade,
        Smooth
    }

    public enum CalculatingMode{
        Normal,
        ByDistance,
        ByRadius
    }

    public enum DamageMode{
        BestDamage,
        MostDamage
    }

    public enum SwapMode{
        Off,
        Normal,
        Silent,
        Inventory
    }
}
