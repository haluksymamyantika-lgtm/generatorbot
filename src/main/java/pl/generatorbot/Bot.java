package pl.generatorbot;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import pl.generatorbot.AreaManager.Area;
import pl.generatorbot.mixin.ClientPlayerInteractionManagerAccessor;

import java.util.*;
import java.util.function.Predicate;

public class Bot {
    public enum State { STOPPED, SEARCH, WAIT, FLY, MINE, PAUSE }

    public State state = State.STOPPED;
    public String status = "Zatrzymany";

    private static final Set<Block> TARGETS = Set.of(
            Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG, Blocks.OAK_LOG,
            Blocks.ACACIA_WOOD, Blocks.DARK_OAK_WOOD, Blocks.OAK_WOOD);
    private static final double REACH = 4.4;
    private static final double FLEE_DIST_SQ = 20.0 * 20.0;

    private Area current, lastFinished;
    private Vec3d flyTarget = Vec3d.ZERO, lastPos = Vec3d.ZERO;
    private int ticks, waitTicks, fireworkCd, stuck, rescanIn, walkTicks, flightTicks;
    private BlockPos mineTarget;
    private final List<BlockPos> cache = new ArrayList<>();
    private final Map<BlockPos, Integer> blacklist = new HashMap<>();

    public static void msg(MinecraftClient mc, String s) {
        if (mc.inGameHud != null) mc.inGameHud.getChatHud().addMessage(Text.literal("[GeneratorBot] " + s));
    }

    private static boolean isAxe(ItemStack s) { return s.isIn(ItemTags.AXES); }
    private static boolean isRocket(ItemStack s) { return s.isOf(Items.FIREWORK_ROCKET); }
    private static boolean hasElytra(ClientPlayerEntity p) { return p.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA); }

    private static boolean hotbarHas(ClientPlayerEntity p, Predicate<ItemStack> pr) {
        for (int i = 0; i < 9; i++) if (pr.test(p.getInventory().getStack(i))) return true;
        return false;
    }

    private static boolean select(ClientPlayerEntity p, Predicate<ItemStack> pr) {
        PlayerInventory inv = p.getInventory();
        if (pr.test(inv.getStack(inv.selectedSlot))) return true;
        for (int i = 0; i < 9; i++) {
            if (pr.test(inv.getStack(i))) { inv.selectedSlot = i; return true; }
        }
        return false;
    }

    public void start(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return;
        if (GeneratorBotClient.AREAS.areas.isEmpty()) { msg(mc, "Najpierw zaznacz obszar klawiszem J (2 rogi)."); return; }
        if (!hasElytra(p)) { msg(mc, "Załóż elytrę (slot na klatkę piersiową)."); return; }
        if (!hotbarHas(p, Bot::isAxe)) { msg(mc, "Połóż siekierę na pasku szybkiego dostępu."); return; }
        if (!hotbarHas(p, Bot::isRocket)) { msg(mc, "Połóż fajerwerki na pasku szybkiego dostępu."); return; }
        current = null; lastFinished = null; mineTarget = null;
        cache.clear(); blacklist.clear();
        state = State.SEARCH; status = "Szukam obszaru";
        msg(mc, "Bot włączony.");
    }

    public void stop(MinecraftClient mc) {
        boolean was = state != State.STOPPED;
        state = State.STOPPED; status = "Zatrzymany";
        if (mc.interactionManager != null) mc.interactionManager.cancelBlockBreaking();
        KeyBinding.unpressAll();
        if (was) msg(mc, "Bot zatrzymany – sterujesz sam.");
    }

    private void fail(MinecraftClient mc, String why) { msg(mc, why); stop(mc); }

    public void tick(MinecraftClient mc) {
        if (state == State.STOPPED) return;
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        if (p == null || w == null || mc.interactionManager == null) { state = State.STOPPED; return; }
        if (!p.isAlive()) { stop(mc); return; }
        if (mc.currentScreen != null) return;
        ticks++;
        if (fireworkCd > 0) fireworkCd--;

        boolean near = playerNear(w, p);
        if (near && (state == State.MINE || state == State.SEARCH || state == State.WAIT || state == State.PAUSE)) {
            Area dest = pickArea(w, p, current, true);
            if (dest != null) {
                startFlight(mc, dest, true);
                return;
            }
            if (state != State.PAUSE) { mc.interactionManager.cancelBlockBreaking(); KeyBinding.unpressAll(); }
            state = State.PAUSE; status = "Gracz w pobliżu – czekam";
            return;
        }

        switch (state) {
            case SEARCH -> {
                Area next = pickArea(w, p, null, false);
                if (next == null) {
                    lastFinished = null; waitTicks = 100; state = State.WAIT;
                    status = "Nic do kopania – czekam";
                } else {
                    current = next;
                    if (next.containsExpanded(p.getPos(), 3) && p.isOnGround()) beginMine();
                    else startFlight(mc, next, false);
                }
            }
            case WAIT -> { if (--waitTicks <= 0) state = State.SEARCH; }
            case FLY -> flight(mc, p);
            case MINE -> mine(mc, p, w);
            case PAUSE -> { if (!near) state = current != null ? State.MINE : State.SEARCH; }
            default -> {}
        }
    }

    private boolean playerNear(ClientWorld w, ClientPlayerEntity p) {
        for (AbstractClientPlayerEntity o : w.getPlayers()) {
            if (o != p && !o.isSpectator() && o.squaredDistanceTo(p) <= FLEE_DIST_SQ) return true;
        }
        return false;
    }

    private Area pickArea(ClientWorld w, ClientPlayerEntity p, Area exclude, boolean flee) {
        Area best = null; double bd = Double.MAX_VALUE;
        for (Area a : GeneratorBotClient.AREAS.areas) {
            if (a == exclude) continue;
            if (!flee && a == lastFinished) continue;
            if (!hasTargets(w, a)) continue;
            double d = a.center().squaredDistanceTo(p.getPos());
            if (d < bd) { bd = d; best = a; }
        }
        if (best == null && flee) {
            for (Area a : GeneratorBotClient.AREAS.areas) {
                if (a == exclude) continue;
                double d = a.center().squaredDistanceTo(p.getPos());
                if (d < bd) { bd = d; best = a; }
            }
        }
        return best;
    }

    private boolean hasTargets(ClientWorld w, Area a) {
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int x = a.x1; x <= a.x2; x++) {
            for (int z = a.z1; z <= a.z2; z++) {
                if (!w.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) return true; // nieznane -> lecimy sprawdzić
                for (int y = a.y1; y <= a.y2; y++) {
                    if (TARGETS.contains(w.getBlockState(m.set(x, y, z)).getBlock())) return true;
                }
            }
        }
        return false;
    }

    private boolean isTarget(ClientWorld w, BlockPos pos) {
        return TARGETS.contains(w.getBlockState(pos).getBlock());
    }

    private void beginMine() {
        state = State.MINE; status = "Kopię";
        cache.clear(); rescanIn = 0; mineTarget = null; stuck = 0; walkTicks = 0;
    }

    private void startFlight(MinecraftClient mc, Area dest, boolean flee) {
        current = dest;
        flyTarget = dest.flyPoint();
        state = State.FLY; flightTicks = 0; mineTarget = null;
        status = flee ? "Gracz blisko – lecę do następnego generatora" : "Lecę do generatora";
        mc.interactionManager.cancelBlockBreaking();
        KeyBinding.unpressAll();
    }

    // ---------------------------------------------------------------- LOT

    private void flight(MinecraftClient mc, ClientPlayerEntity p) {
        if (!hasElytra(p)) { fail(mc, "Brak elytry."); return; }
        if (!select(p, Bot::isRocket)) { fail(mc, "Brak fajerwerków na pasku."); return; }
        ItemStack el = p.getEquippedStack(EquipmentSlot.CHEST);
        if (el.getMaxDamage() - el.getDamage() <= 3) { fail(mc, "Elytra zaraz się zepsuje – stop."); return; }
        if (++flightTicks > 2400) { fail(mc, "Lot trwa za długo – stop."); return; }

        Vec3d pos = p.getPos();
        double distH = Math.hypot(flyTarget.x - pos.x, flyTarget.z - pos.z);

        if (!p.isGliding()) {
            if (p.isOnGround() && (current.containsExpanded(pos, 6) || distH < 12)) {
                mc.options.jumpKey.setPressed(false);
                beginMine();
                return;
            }
            // start: skok, a w powietrzu "klikanie" skoku otwiera elytrę
            mc.options.jumpKey.setPressed(p.isOnGround() || ticks % 2 == 0);
            turn(p, yawTo(pos, flyTarget), -20f, 40f);
            return;
        }

        mc.options.jumpKey.setPressed(false);
        Vec3d eye = p.getEyePos();
        Vec3d aim = distH > 35 ? new Vec3d(flyTarget.x, flyTarget.y + 30, flyTarget.z) : flyTarget;
        Vec3d d = aim.subtract(eye);
        float ty = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float tp = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
        tp = MathHelper.clamp(tp, -45f, 35f);
        turn(p, ty, tp, 25f);

        if (distH > 25 && p.getVelocity().length() < 1.2 && fireworkCd <= 0) {
            mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
            fireworkCd = 15;
        }
    }

    private static float yawTo(Vec3d from, Vec3d to) {
        return (float) Math.toDegrees(Math.atan2(-(to.x - from.x), to.z - from.z));
    }

    private static void turn(ClientPlayerEntity p, float ty, float tp, float maxStep) {
        float y = p.getYaw();
        p.setYaw(y + MathHelper.clamp(MathHelper.wrapDegrees(ty - y), -maxStep, maxStep));
        float pt = p.getPitch();
        p.setPitch(pt + MathHelper.clamp(tp - pt, -maxStep, maxStep));
    }

    // ------------------------------------------------------------- KOPANIE

    private void mine(MinecraftClient mc, ClientPlayerEntity p, ClientWorld w) {
        if (current == null) { state = State.SEARCH; return; }
        if (p.isGliding()) return;
        if (!select(p, Bot::isAxe)) { fail(mc, "Brak siekiery na pasku."); return; }

        if (--rescanIn <= 0) { rescan(w); rescanIn = 100; }
        if (mineTarget == null || !isTarget(w, mineTarget)) { mineTarget = chooseTarget(w, p); stuck = 0; }
        if (mineTarget == null) {
            lastFinished = current;
            mc.interactionManager.cancelBlockBreaking();
            KeyBinding.unpressAll();
            cache.clear();
            state = State.SEARCH; status = "Obszar pusty – następny";
            return;
        }

        Vec3d eye = p.getEyePos();
        Vec3d c = Vec3d.ofCenter(mineTarget);
        double dist = eye.distanceTo(c);
        BlockHitResult hr = w.raycast(new RaycastContext(eye, c, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, p));

        BlockPos breakPos = null; Direction side = null;
        if (dist <= REACH && hr.getType() == HitResult.Type.BLOCK) {
            if (hr.getBlockPos().equals(mineTarget)) { breakPos = mineTarget; side = hr.getSide(); }
            else if (w.getBlockState(hr.getBlockPos()).isIn(BlockTags.LEAVES)) { breakPos = hr.getBlockPos(); side = hr.getSide(); }
        }

        Vec3d d = c.subtract(eye);
        p.setYaw((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
        p.setPitch((float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));

        if (breakPos != null) {
            mc.options.forwardKey.setPressed(false);
            mc.options.sprintKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
            ((ClientPlayerInteractionManagerAccessor) mc.interactionManager).generatorbot$setBlockBreakingCooldown(0);
            mc.interactionManager.updateBlockBreakingProgress(breakPos, side);
            p.swingHand(Hand.MAIN_HAND);
            stuck = 0; walkTicks = 0;
            lastPos = p.getPos();
            status = "Kopię (" + cache.size() + " bloków w obszarze)";
        } else {
            mc.interactionManager.cancelBlockBreaking();
            walk(mc, p, c);
            if (++walkTicks % 15 == 0) mineTarget = null; // przelicz cel
            status = "Podchodzę do drewna";
        }
    }

    private void walk(MinecraftClient mc, ClientPlayerEntity p, Vec3d t) {
        mc.options.forwardKey.setPressed(true);
        mc.options.sprintKey.setPressed(true);
        boolean jump = p.isTouchingWater() || (p.horizontalCollision && p.isOnGround())
                || (t.y - p.getY() > 2 && p.isOnGround());
        mc.options.jumpKey.setPressed(jump);
        if (p.getPos().squaredDistanceTo(lastPos) < 0.0004) stuck++; else stuck = 0;
        lastPos = p.getPos();
        if (stuck > 60 && mineTarget != null) {
            blacklist.put(mineTarget, ticks + 1200);
            mineTarget = null; stuck = 0;
        }
    }

    private void rescan(ClientWorld w) {
        cache.clear();
        Area a = current;
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int x = a.x1; x <= a.x2; x++)
            for (int z = a.z1; z <= a.z2; z++)
                for (int y = a.y1; y <= a.y2; y++)
                    if (TARGETS.contains(w.getBlockState(m.set(x, y, z)).getBlock())) cache.add(m.toImmutable());
    }

    private BlockPos chooseTarget(ClientWorld w, ClientPlayerEntity p) {
        Vec3d eye = p.getEyePos();
        cache.removeIf(b -> !isTarget(w, b));
        List<BlockPos> valid = new ArrayList<>();
        for (BlockPos b : cache) {
            Integer until = blacklist.get(b);
            if (until == null || until <= ticks) valid.add(b);
        }
        if (valid.isEmpty()) return null;
        valid.sort(Comparator.comparingDouble(b -> Vec3d.ofCenter(b).squaredDistanceTo(eye)));
        int tested = 0;
        for (BlockPos b : valid) {
            Vec3d c = Vec3d.ofCenter(b);
            if (c.distanceTo(eye) > REACH) break;
            BlockHitResult hr = w.raycast(new RaycastContext(eye, c, RaycastContext.ShapeType.OUTLINE,
                    RaycastContext.FluidHandling.NONE, p));
            if (hr.getType() == HitResult.Type.BLOCK && hr.getBlockPos().equals(b)) return b;
            if (++tested >= 10) break;
        }
        return valid.get(0);
    }
}
