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
    private static final double FLEE_DIST_SQ = 10.0 * 10.0;
    private static final double CRUISE_Y = 249.0;
    private static final int AXE_SLOT = 0;     // slot 1
    private static final int ROCKET_SLOT = 3;  // slot 4

    private Area current, lastFinished;
    private Vec3d flyTarget = Vec3d.ZERO, lastPos = Vec3d.ZERO;
    private final Random rng = new Random();
    private Vec3d aimOffset = Vec3d.ZERO;
    private int delayTicks, pauseWalk;
    private boolean climbed;
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

    private static boolean slotHas(ClientPlayerEntity p, int slot, Predicate<ItemStack> pr) {
        return pr.test(p.getInventory().getStack(slot));
    }

    private static boolean select(ClientPlayerEntity p, int slot, Predicate<ItemStack> pr) {
        PlayerInventory inv = p.getInventory();
        if (!pr.test(inv.getStack(slot))) return false;
        inv.selectedSlot = slot;
        return true;
    }

    public void start(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return;
        if (GeneratorBotClient.AREAS.areas.isEmpty()) { msg(mc, "Najpierw zaznacz obszar klawiszem J (2 rogi)."); return; }
        if (!hasElytra(p)) { msg(mc, "Załóż elytrę (slot na klatkę piersiową)."); return; }
        if (!slotHas(p, AXE_SLOT, Bot::isAxe)) { msg(mc, "Połóż siekierę w slocie 1 paska."); return; }
        if (!slotHas(p, ROCKET_SLOT, Bot::isRocket)) { msg(mc, "Połóż fajerwerki w slocie 4 paska."); return; }
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
        state = State.FLY; flightTicks = 0; mineTarget = null; climbed = false;
        status = flee ? "Gracz blisko – lecę do następnego generatora" : "Lecę do generatora";
        mc.interactionManager.cancelBlockBreaking();
        KeyBinding.unpressAll();
    }

    // ---------------------------------------------------------------- LOT
    // Fazy: 1) start  2) wznoszenie pionowo do Y=249  3) przelot poziomo na Y=249
    //       4) zniżanie ślizgiem nad cel  5) lądowanie. Przed ścianą bot unosi nos w górę.

    private void flight(MinecraftClient mc, ClientPlayerEntity p) {
        ClientWorld w = mc.world;
        if (!hasElytra(p)) { fail(mc, "Brak elytry."); return; }
        if (!select(p, ROCKET_SLOT, Bot::isRocket)) { fail(mc, "Brak fajerwerków w slocie 4."); return; }
        ItemStack el = p.getEquippedStack(EquipmentSlot.CHEST);
        if (el.getMaxDamage() - el.getDamage() <= 3) { fail(mc, "Elytra zaraz się zepsuje – stop."); return; }
        if (++flightTicks > 4800) { fail(mc, "Lot trwa za długo – stop."); return; }

        Vec3d pos = p.getPos();
        double distH = Math.hypot(flyTarget.x - pos.x, flyTarget.z - pos.z);

        if (!p.isGliding()) {
            climbed = false;
            if (p.isOnGround() && (current.containsExpanded(pos, 6) || distH < 12)) {
                mc.options.jumpKey.setPressed(false);
                beginMine();
                return;
            }
            mc.options.jumpKey.setPressed(p.isOnGround() || ticks % 2 == 0);
            status = "Start elytry";
            return;
        }

        mc.options.jumpKey.setPressed(false);
        Vec3d eye = p.getEyePos();
        double y = eye.y;
        double speed = p.getVelocity().length();
        if (!climbed && y >= CRUISE_Y - 4) climbed = true;

        float ty = yawTo(pos, flyTarget);
        float tp;
        boolean boost = true;
        double heightAbove = y - flyTarget.y;

        if (!climbed) {
            tp = -80f;                        // prawie pionowo w górę, dopóki nie dojdzie do Y=249
            ty = p.getYaw();                  // bez zbaczania na boki, żeby nie trafić w ścianę
            status = "Wznoszę się na Y=249";
        } else {
            double glideStart = Math.max(30, heightAbove * 2.6);
            if (distH > glideStart) {
                tp = MathHelper.clamp((float) ((y - CRUISE_Y) * 1.5), -30f, 20f); // trzymaj ~249
                status = "Lecę na Y=249 do celu";
            } else if (distH < 10 && heightAbove > 8) {
                ty = p.getYaw() + 25f;        // krąży nad celem i spada w dół
                tp = 20f; boost = false;
                status = "Krążę nad celem";
            } else {
                double ang = Math.toDegrees(Math.atan2(-(flyTarget.y - y), Math.max(distH, 1)));
                tp = MathHelper.clamp((float) ang, 8f, 38f);
                boost = speed < 0.7;
                status = "Schodzę do lądowania";
            }
        }

        // ściana / skała przed nosem -> w górę (omijanie)
        if (distH > 12) {
            Vec3d look = p.getRotationVec(1f);
            BlockHitResult wall = w.raycast(new RaycastContext(eye, eye.add(look.multiply(16)),
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
            if (wall.getType() == HitResult.Type.BLOCK) {
                tp = -55f; boost = true;
                status = "Omijam przeszkodę górą";
            }
        }

        turn(p, ty, tp, 25f);

        if (boost && speed < (climbed ? 1.2 : 1.0) && fireworkCd <= 0) {
            mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
            fireworkCd = 12;
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

    private double rnd(double r) { return (rng.nextDouble() * 2 - 1) * r; }

    private void mine(MinecraftClient mc, ClientPlayerEntity p, ClientWorld w) {
        if (current == null) { state = State.SEARCH; return; }
        if (p.isGliding()) return;
        if (!select(p, AXE_SLOT, Bot::isAxe)) { fail(mc, "Brak siekiery w slocie 1."); return; }

        if (--rescanIn <= 0) { rescan(w); rescanIn = 100; }
        if (mineTarget == null || !isTarget(w, mineTarget)) {
            boolean hadTarget = mineTarget != null;
            mineTarget = chooseTarget(w, p);
            stuck = 0;
            aimOffset = new Vec3d(rnd(0.28), rnd(0.28), rnd(0.28)); // celuje w losny punkt bloku, nie w środek
            if (hadTarget) {
                delayTicks = rng.nextInt(4);                                  // "reakcja" człowieka
                if (rng.nextInt(30) == 0) delayTicks = 6 + rng.nextInt(10);   // czasem się rozejrzy
            }
        }
        if (mineTarget == null) {
            lastFinished = current;
            mc.interactionManager.cancelBlockBreaking();
            KeyBinding.unpressAll();
            cache.clear();
            state = State.SEARCH; status = "Obszar pusty – następny";
            return;
        }

        Vec3d eye = p.getEyePos();
        Vec3d c = Vec3d.ofCenter(mineTarget).add(aimOffset);
        double dist = eye.distanceTo(c);
        BlockHitResult hr = w.raycast(new RaycastContext(eye, c, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, p));

        BlockPos breakPos = null; Direction side = null;
        if (dist <= REACH && hr.getType() == HitResult.Type.BLOCK) {
            if (hr.getBlockPos().equals(mineTarget)) { breakPos = mineTarget; side = hr.getSide(); }
            else if (w.getBlockState(hr.getBlockPos()).isIn(BlockTags.LEAVES)) { breakPos = hr.getBlockPos(); side = hr.getSide(); }
        }

        // płynne, nieidealne obracanie głowy (ease + drobny szum) zamiast natychmiastowego "przyklejenia"
        Vec3d d = c.subtract(eye);
        float ty = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float tp = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
        float dyaw = MathHelper.wrapDegrees(ty - p.getYaw());
        float dpitch = tp - p.getPitch();
        float stepY = MathHelper.clamp(dyaw * 0.4f + (float) (rng.nextGaussian() * 0.4), -35f, 35f);
        float stepP = MathHelper.clamp(dpitch * 0.4f + (float) (rng.nextGaussian() * 0.3), -25f, 25f);
        p.setYaw(p.getYaw() + stepY);
        p.setPitch(MathHelper.clamp(p.getPitch() + stepP, -90f, 90f));
        boolean aimed = Math.abs(dyaw) < 10 && Math.abs(dpitch) < 10;

        // czasem krótko przystaje, ale zwykle idzie
        if (pauseWalk > 0) pauseWalk--;
        else if (rng.nextInt(60) == 0) pauseWalk = 3 + rng.nextInt(6);

        if (breakPos != null) {
            boolean ahead = pauseWalk == 0 && safeAhead(w, p);
            mc.options.forwardKey.setPressed(ahead);
            mc.options.sprintKey.setPressed(false);
            mc.options.jumpKey.setPressed(ahead && p.isOnGround() && p.horizontalCollision
                    && mineTarget.getY() > p.getY() + 1.0);
            if (delayTicks > 0) {
                delayTicks--;
                status = "Rozgląda się";
            } else if (aimed) {
                // 0–2 ticki przerwy między blokami zamiast zawsze 0
                ((ClientPlayerInteractionManagerAccessor) mc.interactionManager)
                        .generatorbot$setBlockBreakingCooldown(rng.nextInt(3));
                mc.interactionManager.updateBlockBreakingProgress(breakPos, side);
                p.swingHand(Hand.MAIN_HAND);
                status = "Kopię idąc (" + cache.size() + " bloków w obszarze)";
            } else {
                status = "Celuje";
            }
            stuck = 0; walkTicks = 0;
            lastPos = p.getPos();
        } else {
            mc.interactionManager.cancelBlockBreaking();
            walk(mc, p, c);
            if (++walkTicks % 15 == 0) mineTarget = null; // przelicz cel
            status = "Podchodzę do drewna";
        }
    }

    /** Nie wchodź w przepaść: musi być grunt w ciągu 3 bloków pod miejscem tuż przed botem. */
    private boolean safeAhead(ClientWorld w, ClientPlayerEntity p) {
        double yaw = Math.toRadians(p.getYaw());
        double fx = p.getX() - Math.sin(yaw) * 0.9;
        double fz = p.getZ() + Math.cos(yaw) * 0.9;
        BlockPos base = BlockPos.ofFloored(fx, p.getY() - 0.5, fz);
        for (int i = 0; i < 3; i++) {
            if (!w.getBlockState(base.down(i)).isAir()) return true;
        }
        return false;
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
