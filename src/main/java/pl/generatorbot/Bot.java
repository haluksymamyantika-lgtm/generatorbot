package pl.generatorbot;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
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
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
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
    public enum State { STOPPED, SEARCH, WAIT, FLY, MINE, PAUSE, REPAIR }

    public State state = State.STOPPED;
    public String status = "Zatrzymany";

    private static final Set<Block> TARGETS = Set.of(
            Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG, Blocks.OAK_LOG,
            Blocks.ACACIA_WOOD, Blocks.DARK_OAK_WOOD, Blocks.OAK_WOOD);
    private static final double REACH = 4.4;
    private static final double FLEE_DIST_SQ = 5.0 * 5.0;
    private static final double CRUISE_Y = 240.0;
    private static final int AXE_SLOT = 0;     // slot 1
    private static final int ROCKET_SLOT = 3;  // slot 4
    private static final int ELYTRA_SLOT = 2;  // slot 3
    private static final int SWORD_SLOT = 1;   // slot 2
    /** Kolejność naprawy: miecz (slot 2), elytra (slot 3), siekiera (slot 1). */
    private static final int[] REPAIR_ORDER = {SWORD_SLOT, ELYTRA_SLOT, AXE_SLOT};
    /** Punkt z kowadłem: x -7, y 161, z 1 (środek bloku). Bot leci tu co rundę. */
    private static final Vec3d STATION = new Vec3d(-6.5, 162.0, 1.5);

    private Area current, lastFinished;
    private Vec3d flyTarget = Vec3d.ZERO, lastPos = Vec3d.ZERO;
    private final Random rng = new Random();
    private Vec3d aimOffset = Vec3d.ZERO;
    private int delayTicks, pauseWalk;
    private boolean climbed, diveLocked;
    private int equipCd;
    private boolean minedThisRound, toStation;
    private int rItem, rStep, rTimer, rTries, repairTicks;
    private int shopAction, plusLeft, plusSlot, restartIn, equipTries;
    private MacroPlayer player;
    private boolean rMacroDone;
    private int ticks, waitTicks, fireworkCd, stuck, rescanIn, walkTicks, flightTicks;
    private BlockPos mineTarget;
    private final List<BlockPos> cache = new ArrayList<>();
    private final Map<BlockPos, Integer> blacklist = new HashMap<>();

    public static void msg(MinecraftClient mc, String s) {
        if (mc.inGameHud != null) mc.inGameHud.getChatHud().addMessage(Text.literal("[GeneratorBot] " + s));
    }

    private static boolean isAxe(ItemStack s) { return s.isIn(ItemTags.AXES); }
    private static boolean isSword(ItemStack s) { return s.isIn(ItemTags.SWORDS); }
    private static boolean isElytraStack(ItemStack s) { return s.isOf(Items.ELYTRA); }
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
        boolean anyOn = false;
        for (Area ar : GeneratorBotClient.AREAS.areas) if (ar.enabled) anyOn = true;
        if (!anyOn) { msg(mc, "Wszystkie miejsca są wyłączone (OFF) w panelu Miejsca."); return; }
        if (!hasElytra(p) && !slotHas(p, ELYTRA_SLOT, Bot::isElytraStack)) { msg(mc, "Połóż elytrę w slocie 3 paska (bot sam ją założy)."); return; }
        if (!slotHas(p, AXE_SLOT, Bot::isAxe)) { msg(mc, "Połóż siekierę w slocie 1 paska."); return; }
        if (!slotHas(p, ROCKET_SLOT, Bot::isRocket)) { msg(mc, "Połóż fajerwerki w slocie 4 paska."); return; }
        for (Area ar : GeneratorBotClient.AREAS.areas) ar.visited = false;
        minedThisRound = false;
        player = null; equipTries = 0;
        current = null; lastFinished = null; mineTarget = null; toStation = false;
        cache.clear(); blacklist.clear();
        state = State.SEARCH; status = "Szukam obszaru";
        msg(mc, "Bot włączony.");
    }

    public void stop(MinecraftClient mc) {
        restartIn = 0; player = null; equipTries = 0;
        boolean was = state != State.STOPPED;
        state = State.STOPPED; status = "Zatrzymany";
        if (mc.interactionManager != null) mc.interactionManager.cancelBlockBreaking();
        KeyBinding.unpressAll();
        if (was) msg(mc, "Bot zatrzymany – sterujesz sam.");
    }

    private void fail(MinecraftClient mc, String why) { msg(mc, why); stop(mc); }

    public void tick(MinecraftClient mc) {
        if (restartIn > 0 && --restartIn == 0) { start(mc); return; }
        if (state == State.STOPPED) return;
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        if (p == null || w == null || mc.interactionManager == null) { state = State.STOPPED; return; }
        if (!p.isAlive()) { stop(mc); return; }
        if (mc.currentScreen != null && state != State.REPAIR) return;
        ticks++;
        if (fireworkCd > 0) fireworkCd--;
        if (equipCd > 0) equipCd--;

        boolean near = playerNear(w, p);
        if (near && state == State.MINE) {
            // odlatuje -> to miejsce robi się niebieskie, bot leci do następnego z listy
            if (current != null) current.visited = true;
            mc.interactionManager.cancelBlockBreaking();
            KeyBinding.unpressAll();
            mineTarget = null;
            state = State.SEARCH;
            status = "Gracz blisko – lecę dalej";
            return;
        }

        switch (state) {
            case SEARCH -> {
                Area next = nextRouteArea(w);
                if (next != null) {
                    current = next;
                    if (next.containsExpanded(p.getPos(), 3) && p.isOnGround()) beginMine();
                    else startFlight(mc, next, false);
                } else if (!minedThisRound) {
                    // nic nie było do kopania w całej rundzie – nie lataj na próżno do kowadła
                    for (Area ar : GeneratorBotClient.AREAS.areas) ar.visited = false;
                    waitTicks = 100; state = State.WAIT;
                    status = "Nic do kopania – czekam";
                } else {
                    goStation(mc); // koniec rundy -> naprawa u kowadła
                }
            }
            case WAIT -> { if (--waitTicks <= 0) state = State.SEARCH; }
            case FLY -> flight(mc, p);
            case MINE -> mine(mc, p, w);
            case REPAIR -> repair(mc, p, w);
            case PAUSE -> state = State.SEARCH;
            default -> {}
        }
    }

    private boolean playerNear(ClientWorld w, ClientPlayerEntity p) {
        for (AbstractClientPlayerEntity o : w.getPlayers()) {
            if (o != p && !o.isSpectator() && o.squaredDistanceTo(p) <= FLEE_DIST_SQ) return true;
        }
        return false;
    }

    /** Następne miejsce według KOLEJNOŚCI z panelu: włączone (ON) i jeszcze czerwone. Puste od razu niebieskie. */
    private Area nextRouteArea(ClientWorld w) {
        for (Area a : GeneratorBotClient.AREAS.areas) {
            if (!a.enabled || a.visited) continue;
            if (!hasTargets(w, a)) { a.visited = true; continue; }
            return a;
        }
        return null;
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
        state = State.MINE; status = "Kopię"; minedThisRound = true;
        cache.clear(); rescanIn = 0; mineTarget = null; stuck = 0; walkTicks = 0;
    }

    private void startFlight(MinecraftClient mc, Area dest, boolean flee) {
        if (current != null && current != dest) current.visited = true; // odlatuje -> niebieski
        current = dest;
        flyTarget = dest.flyPoint();
        toStation = false;
        state = State.FLY; flightTicks = 0; mineTarget = null; climbed = false; diveLocked = false;
        status = flee ? "Gracz blisko – lecę do następnego generatora" : "Lecę do generatora";
        mc.interactionManager.cancelBlockBreaking();
        KeyBinding.unpressAll();
    }

    private void goStation(MinecraftClient mc) {
        if (current != null) current.visited = true;
        current = null;
        toStation = true;
        flyTarget = STATION;
        state = State.FLY; flightTicks = 0; mineTarget = null; climbed = false; diveLocked = false;
        status = "Lecę do kowadła (naprawa)";
        mc.interactionManager.cancelBlockBreaking();
        KeyBinding.unpressAll();
    }

    // ---------------------------------------------------------------- LOT
    // Fazy: 1) start  2) wznoszenie pionowo do Y=240  3) przelot poziomo na Y=240
    //       4) zniżanie ślizgiem nad cel  5) lądowanie. Przed ścianą bot unosi nos w górę.

    private void flight(MinecraftClient mc, ClientPlayerEntity p) {
        ClientWorld w = mc.world;
        if (hasElytra(p)) equipTries = 0;
        if (!hasElytra(p) && !p.isGliding() && Macros.has("elytra_on")) {
            if (player == null) {
                if (++equipTries > 3) { fail(mc, "Nie udało się założyć elytry (nagranie nie pomogło)."); return; }
                player = new MacroPlayer(Macros.get("elytra_on"));
            }
            status = "Zakładam elytrę (nagranie)";
            if (player.tick(mc, p, w)) player = null;
            return;
        }
        if (!hasElytra(p)) {
            // sam zakłada elytrę ze slotu 3 (zamiana z napierśnikiem)
            if (!p.isGliding() && slotHas(p, ELYTRA_SLOT, Bot::isElytraStack)) {
                if (equipCd <= 0) {
                    mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, 6, ELYTRA_SLOT, SlotActionType.SWAP, p);
                    equipCd = 40;
                }
                status = "Zakładam elytrę";
                return;
            }
            fail(mc, "Brak elytry w slocie 3.");
            return;
        }
        if (!select(p, ROCKET_SLOT, Bot::isRocket)) { fail(mc, "Brak fajerwerków w slocie 4."); return; }
        ItemStack el = p.getEquippedStack(EquipmentSlot.CHEST);
        if (el.getMaxDamage() - el.getDamage() <= 3) { fail(mc, "Elytra zaraz się zepsuje – stop."); return; }
        if (++flightTicks > 4800) { fail(mc, "Lot trwa za długo – stop."); return; }

        Vec3d pos = p.getPos();
        double distH = Math.hypot(flyTarget.x - pos.x, flyTarget.z - pos.z);

        if (!p.isGliding()) {
            climbed = false; diveLocked = false;
            boolean arrived = toStation ? distH < 12
                    : ((current != null && current.containsExpanded(pos, 6)) || distH < 12);
            if (p.isOnGround() && arrived) {
                mc.options.jumpKey.setPressed(false);
                if (toStation) beginRepair(); else beginMine();
                return;
            }
            turn(p, yawTo(pos, flyTarget), -30f, 90f);   // od razu twarzą do celu
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
        boolean diving = false;
        double heightAbove = y - flyTarget.y;

        if (!climbed) {
            tp = -55f;                        // ukośnie w górę, ale już w stronę celu (bez skręcania później)
            status = "Wznoszę się na Y=240";
        } else {
            // szybki, stromy nurkowaniem prosto w cel (bez delikatnego lądowania)
            double diveStart = Math.max(20, heightAbove * 0.9);
            if (distH > diveStart && !diveLocked) {
                tp = MathHelper.clamp((float) ((y - CRUISE_Y) * 1.5), -30f, 20f); // trzymaj ~240
                status = "Lecę na Y=240 do celu";
            } else {
                diving = true; diveLocked = true;
                double ang = Math.toDegrees(Math.atan2(-(flyTarget.y - y), Math.max(distH, 1)));
                tp = MathHelper.clamp((float) ang, 10f, 80f);
                boost = speed < 1.7;          // dopalaj fajerwerkami cały czas
                status = "Nurkuję w cel";
            }
        }

        // ściana / skała przed nosem -> w górę (omijanie)
        if (climbed && !diving && distH > 12) {
            Vec3d look = p.getRotationVec(1f);
            BlockHitResult wall = w.raycast(new RaycastContext(eye, eye.add(look.multiply(16)),
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
            if (wall.getType() == HitResult.Type.BLOCK) {
                tp = -55f; boost = true;
                status = "Omijam przeszkodę górą";
            }
        }

        turn(p, ty, tp, 60f);

        if (boost && speed < (diving ? 1.7 : 1.5) && fireworkCd <= 0) {
            mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
            fireworkCd = 10;
        }
    }

    // ------------------------------------------------------------ NAPRAWA
    // Co rundę: leci na kowadło, z elytrą (slot 3) w ręce klika PPM, w GUI klika butelkę z expem,
    // potem to samo z siekierą (slot 1).

    private void beginRepair() {
        state = State.REPAIR; toStation = false;
        rItem = 0; rStep = 0; rTimer = 0; rTries = 0; repairTicks = 0; rMacroDone = false; player = null;
        status = "Naprawa: idę do kowadła";
    }

    /** Po zakupach: bot wyłącza się i (zależnie od ustawienia) włącza sam albo czeka na ręczny Start. */
    private void afterShop(MinecraftClient mc) {
        stop(mc);
        if (Settings.autoRestart) {
            msg(mc, "Zakupy zrobione – bot włączy się sam za chwilę.");
            restartIn = 40;
        } else {
            msg(mc, "Zakupy zrobione – bot zatrzymany. Naciśnij Start, gdy będziesz gotowy.");
        }
    }

    private void finishRepair(MinecraftClient mc) {
        KeyBinding.unpressAll();
        for (Area ar : GeneratorBotClient.AREAS.areas) ar.visited = false; // nowa runda: wszystko znów czerwone
        minedThisRound = false;
        current = null;
        waitTicks = 20; state = State.WAIT;
        status = "Nowa runda";
    }

    private void nextRepairItem(MinecraftClient mc, ClientPlayerEntity p) {
        if (mc.currentScreen != null) p.closeHandledScreen();
        rItem++; rTries = 0; rTimer = 0;
        if (rItem >= REPAIR_ORDER.length) { rStep = 10; rTimer = 0; shopAction = 1; plusLeft = 0; plusSlot = -1; status = "Sklep: wpisuję /sklep"; }
        else rStep = 6;
    }

    private static void lookAt(ClientPlayerEntity p, Vec3d t) {
        Vec3d d = t.subtract(p.getEyePos());
        p.setYaw((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
        p.setPitch((float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
    }

    private BlockPos findAnvil(ClientWorld w, ClientPlayerEntity p, int r, double maxDist) {
        BlockPos base = p.getBlockPos();
        BlockPos best = null; double bd = Double.MAX_VALUE;
        for (int x = -r; x <= r; x++) for (int y = -4; y <= 4; y++) for (int z = -r; z <= r; z++) {
            BlockPos b = base.add(x, y, z);
            Block bl = w.getBlockState(b).getBlock();
            if (bl != Blocks.ANVIL && bl != Blocks.CHIPPED_ANVIL && bl != Blocks.DAMAGED_ANVIL) continue;
            double d = Vec3d.ofCenter(b).distanceTo(p.getEyePos());
            if (d <= maxDist && d < bd) { bd = d; best = b; }
        }
        return best;
    }

    private static int findByName(ScreenHandler h, String needle) {
        int container = Math.max(0, h.slots.size() - 36);
        for (int i = 0; i < container; i++) {
            ItemStack st = h.getSlot(i).getStack();
            if (st.isEmpty()) continue;
            if (st.getName().getString().toLowerCase(Locale.ROOT).contains(needle)) return i;
        }
        return -1;
    }

    private static int findByItem(ScreenHandler h, net.minecraft.item.Item item) {
        int container = Math.max(0, h.slots.size() - 36);
        for (int i = 0; i < container; i++) if (h.getSlot(i).getStack().isOf(item)) return i;
        return -1;
    }

    /** Gdy nie znajdzie przycisku: wypisuje na czacie sloty okna, żebym mógł dopasować nazwy. */
    private void shopFail(MinecraftClient mc, ClientPlayerEntity p, String why, ScreenHandler h) {
        msg(mc, "Sklep: " + why);
        if (h != null) {
            StringBuilder sb = new StringBuilder("Sloty okna: ");
            int container = Math.max(0, h.slots.size() - 36);
            for (int i = 0; i < container; i++) {
                ItemStack st = h.getSlot(i).getStack();
                if (!st.isEmpty()) sb.append(i).append("=").append(st.getName().getString()).append(" | ");
            }
            msg(mc, sb.toString());
        }
        if (mc.currentScreen != null) p.closeHandledScreen();
        finishRepair(mc);
    }

    private void repair(MinecraftClient mc, ClientPlayerEntity p, ClientWorld w) {
        rTimer++;
        if (++repairTicks > 6000) { msg(mc, "Naprawa trwa za długo – pomijam."); if (mc.currentScreen != null) p.closeHandledScreen(); finishRepair(mc); return; }
        int slot = REPAIR_ORDER[Math.min(rItem, REPAIR_ORDER.length - 1)];
        Predicate<ItemStack> pr = slot == SWORD_SLOT ? Bot::isSword : slot == ELYTRA_SLOT ? Bot::isElytraStack : Bot::isAxe;

        // ---- SKLEP: /sklep -> Pieniądze -> Potrzebne przedmioty -> fajerwerki -> +32 -> Potwierdź ----
        if (rStep == 10) {
            if (rTimer < 15) return;
            p.networkHandler.sendChatCommand("sklep"); // to samo co wpisanie /sklep i Enter
            rStep = 11; rTimer = 0;
            status = "Sklep: czekam na okno";
            return;
        }
        if (rStep == 11) {
            if (mc.currentScreen instanceof HandledScreen<?> && !(mc.currentScreen instanceof InventoryScreen)) {
                if (rTimer >= 12) {
                    if (Macros.has("shop")) { player = new MacroPlayer(Macros.get("shop")); rStep = 24; } else rStep = 12;
                    rTimer = 0;
                }
                return;
            }
            if (rTimer > 100) shopFail(mc, p, "okno /sklep się nie otworzyło", null);
            return;
        }
        if (rStep == 12) {
            if (!(mc.currentScreen instanceof HandledScreen<?> hs)) {
                if (rTimer > 120) shopFail(mc, p, "okno sklepu zniknęło", null);
                return;
            }
            ScreenHandler h = hs.getScreenHandler();
            int sl = -1; String label = "";
            switch (shopAction) {
                case 1 -> { sl = findByName(h, "pieni"); label = "Pieniądze (zielona moneta)"; }
                case 2 -> { sl = findByName(h, "potrzebne"); label = "Potrzebne przedmioty (czerwona skrzynia)"; }
                case 3 -> { sl = findByItem(h, Items.FIREWORK_ROCKET); label = "fajerwerki"; }
                case 4 -> {
                    label = "+32 (lub +16 dwa razy)";
                    if (plusLeft > 0) sl = plusSlot;
                    else {
                        int a = findByName(h, "+32");
                        if (a >= 0) { sl = a; plusSlot = a; plusLeft = 1; }
                        else {
                            int b = findByName(h, "+16");
                            if (b >= 0) { sl = b; plusSlot = b; plusLeft = 2; }
                        }
                    }
                }
                case 5 -> { sl = findByName(h, "potwierd"); label = "Potwierdź (zielony ptaszek)"; }
                default -> {}
            }
            if (sl < 0) {
                if (rTimer > 100) shopFail(mc, p, "nie znalazłem przycisku: " + label, h);
                return;
            }
            if (sl >= Math.max(0, h.slots.size() - 36)) { shopFail(mc, p, "zapisany slot nie pasuje do okna", h); return; }
            if (rTimer < 14) return; // poczekaj aż serwer wyświetli nowe okno
            mc.interactionManager.clickSlot(h.syncId, sl, 0, SlotActionType.PICKUP, p);
            if (shopAction == 4) plusLeft--;
            status = "Sklep: klikam " + label;
            rStep = 13; rTimer = 0;
            return;
        }
        if (rStep == 13) {
            if (rTimer >= 22) {
                if (shopAction == 4 && plusLeft > 0) { rStep = 12; rTimer = 0; }
                else { shopAction++; rStep = shopAction > 5 ? 14 : 12; rTimer = 0; }
            }
            return;
        }
        if (rStep == 14) {
            if (rTimer < 14) return;
            if (mc.currentScreen != null) p.closeHandledScreen();
            rStep = 15; rTimer = 0;
            status = "Po zakupach: układam ekwipunek";
            return;
        }
        if (rStep == 15) { // fajerwerki z zakupów -> slot 4
            if (rTimer < 25) return;
            if (!slotHas(p, ROCKET_SLOT, Bot::isRocket)) {
                for (int i = 0; i < 36; i++) {
                    if (i == ROCKET_SLOT) continue;
                    if (isRocket(p.getInventory().getStack(i))) {
                        int id = i < 9 ? 36 + i : i;
                        mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, id, ROCKET_SLOT, SlotActionType.SWAP, p);
                        break;
                    }
                }
            }
            rStep = 16; rTimer = 0;
            return;
        }
        if (rStep == 16) { // elytra na klatę
            if (rTimer < 25) return;
            if (!hasElytra(p) && Macros.has("elytra_on")) {
                if (player == null) player = new MacroPlayer(Macros.get("elytra_on"));
                status = "Zakładam elytrę (nagranie)";
                if (player.tick(mc, p, w)) { player = null; rStep = 17; rTimer = 0; }
                return;
            }
            if (hasElytra(p)) { rStep = 17; rTimer = 0; return; }
            if (slotHas(p, ELYTRA_SLOT, Bot::isElytraStack)) {
                if (equipCd <= 0) {
                    mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, 6, ELYTRA_SLOT, SlotActionType.SWAP, p);
                    equipCd = 40;
                }
                status = "Zakładam elytrę";
            } else if (rTimer > 40) {
                msg(mc, "Brak elytry do założenia po zakupach.");
                rStep = 17; rTimer = 0;
                return;
            }
            if (rTimer > 240) { rStep = 17; rTimer = 0; }
            return;
        }
        if (rStep == 17) {
            if (rTimer < 20) return;
            afterShop(mc);
            return;
        }

        // ---- NAGRANIA: zdejmij elytrę -> naprawa -> (sklep) ----
        if (rStep == 20) {
            if (Macros.has("elytra_off")) { player = new MacroPlayer(Macros.get("elytra_off")); status = "Naprawa: zdejmuję elytrę (nagranie)"; rStep = 21; }
            else rStep = 22;
            rTimer = 0;
            return;
        }
        if (rStep == 21) { if (player.tick(mc, p, w)) { player = null; rStep = 22; rTimer = 0; } return; }
        if (rStep == 22) {
            if (Macros.has("repair")) { player = new MacroPlayer(Macros.get("repair")); status = "Naprawa (nagranie)"; rStep = 23; }
            else { rMacroDone = true; rStep = 1; }
            rTimer = 0;
            return;
        }
        if (rStep == 23) {
            if (player.tick(mc, p, w)) {
                player = null; rMacroDone = true; rItem = REPAIR_ORDER.length;
                rStep = 10; rTimer = 0; shopAction = 1; plusLeft = 0; plusSlot = -1;
                status = "Sklep: wpisuję /sklep";
            }
            return;
        }
        if (rStep == 24) {
            status = "Sklep (nagranie)";
            if (player.tick(mc, p, w)) { player = null; rStep = 14; rTimer = 0; }
            return;
        }

        if (rStep == 0) { // podejdź do kowadła (jeśli widać blok), inaczej do koordynatów
            BlockPos anv = findAnvil(w, p, 10, 99);
            Vec3d walkT = anv != null ? Vec3d.ofCenter(anv) : STATION;
            double dh = Math.hypot(walkT.x - p.getX(), walkT.z - p.getZ());
            double need = anv != null ? 2.5 : 1.0;
            if (dh < need || rTimer > 200) { KeyBinding.unpressAll(); rStep = rMacroDone ? 1 : 20; rTimer = 0; return; }
            p.setYaw(yawTo(p.getPos(), new Vec3d(walkT.x, p.getY(), walkT.z)));
            p.setPitch(10f);
            mc.options.forwardKey.setPressed(true);
            mc.options.jumpKey.setPressed(p.horizontalCollision && p.isOnGround());
            status = "Naprawa: idę do kowadła";
            return;
        }
        if (rStep == 1) { // przedmiot do ręki
            if (rTimer > 120) { msg(mc, "Nie mogę wziąć przedmiotu ze slotu " + (slot + 1) + " – pomijam."); nextRepairItem(mc, p); return; }
            if (rTimer < 15) return;
            ItemStack cur = (slot == ELYTRA_SLOT && hasElytra(p)) ? p.getEquippedStack(EquipmentSlot.CHEST) : p.getInventory().getStack(slot);
            if (pr.test(cur) && !cur.isDamaged()) {
                msg(mc, "Slot " + (slot + 1) + ": przedmiot nie jest zniszczony – pomijam.");
                nextRepairItem(mc, p);
                return;
            }
            if (!slotHas(p, slot, pr)) {
                if (slot == ELYTRA_SLOT && hasElytra(p)) { // elytra jest założona -> przenieś ją z powrotem do slotu 3
                    if (equipCd <= 0) {
                        mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, 6, ELYTRA_SLOT, SlotActionType.SWAP, p);
                        equipCd = 40;
                    }
                    status = "Naprawa: biorę elytrę do ręki";
                    return;
                }
                msg(mc, "Brak przedmiotu w slocie " + (slot + 1) + " – pomijam.");
                nextRepairItem(mc, p);
                return;
            }
            select(p, slot, pr);
            rStep = 2; rTimer = 0;
            return;
        }
        if (rStep == 2) { // jedno kliknięcie prawym
            if (rTimer < 25) return;
            // w ręce MUSI być właściwy przedmiot (inaczej np. klata zamiast elytry -> "przedmiot nie jest zniszczony")
            if (!pr.test(p.getMainHandStack())) { rStep = 1; rTimer = 0; return; }
            rTries++;
            if (rTries > 5) { msg(mc, "Nie udało się kliknąć kowadła – pomijam slot " + (slot + 1) + "."); nextRepairItem(mc, p); return; }
            BlockPos anvil = findAnvil(w, p, 6, 4.4);
            if (anvil != null) {
                Vec3d c = Vec3d.ofCenter(anvil);
                lookAt(p, c);
                mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(c, Direction.UP, anvil, false));
            } else if (findAnvil(w, p, 10, 99) != null) {
                rStep = 0; rTimer = 0; return;               // kowadło jest, ale za daleko -> podejdź
            } else if (slot == ELYTRA_SLOT) {
                // PPM elytrą w powietrze zakłada ją na klatę (zamiast naprawy) – nigdy tego nie robimy
                msg(mc, "Nie widzę kowadła przy x -7, y 161, z 1 – pomijam elytrę.");
                nextRepairItem(mc, p);
                return;
            } else {
                mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
            }
            p.swingHand(Hand.MAIN_HAND);
            rStep = 3; rTimer = 0;
            status = "Naprawa: otwieram GUI";
            return;
        }
        if (rStep == 3) { // czekaj na GUI
            if (mc.currentScreen instanceof HandledScreen<?> && !(mc.currentScreen instanceof InventoryScreen)) { rStep = 4; rTimer = 0; return; }
            if (rTimer > 25) {
                if (rTries >= 3) { msg(mc, "GUI kowadła się nie otworzyło – pomijam."); nextRepairItem(mc, p); }
                else { rStep = 2; rTimer = 0; }
            }
            return;
        }
        if (rStep == 4) { // klik w butelkę z expem
            if (rTimer < 25) return; // serwer dosyła zawartość okna
            if (!(mc.currentScreen instanceof HandledScreen<?> hs)) { rStep = 2; rTimer = 0; return; }
            ScreenHandler h = hs.getScreenHandler();
            int container = Math.max(0, h.slots.size() - 36);
            int target = -1;
            for (int i = 0; i < container; i++) {
                if (h.getSlot(i).getStack().isOf(Items.EXPERIENCE_BOTTLE)) { target = i; break; }
            }
            if (target < 0 && container > 22) target = 22; // miejsce butelki na zrzucie (rząd 3, kolumna 5)
            if (target < 0) { msg(mc, "Nie widzę butelki z expem w GUI – pomijam."); nextRepairItem(mc, p); return; }
            mc.interactionManager.clickSlot(h.syncId, target, 0, SlotActionType.PICKUP, p);
            rStep = 5; rTimer = 0;
            status = "Naprawa: klikam butelkę";
            return;
        }
        if (rStep == 5) { // zamknij okno
            if (rTimer < 20) return;
            nextRepairItem(mc, p);
            return;
        }
        if (rStep == 6) { // chwila przerwy przed kolejnym przedmiotem
            if (rTimer >= 40) { rStep = 1; rTimer = 0; }
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

    private static double clamp3(double v) { return Math.max(-0.35, Math.min(0.35, v)); }

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
            aimOffset = new Vec3d(rnd(0.3), rnd(0.3), rnd(0.3)); // za każdym razem inny punkt bloku
        }
        if (mineTarget == null) {
            lastFinished = current;
            current.visited = true;
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

        // jak człowiek: szybki, ale płynny obrót z lekkim drżeniem ręki (bez dodatkowych opóźnień)
        aimOffset = new Vec3d(clamp3(aimOffset.x + rnd(0.04)), clamp3(aimOffset.y + rnd(0.04)), clamp3(aimOffset.z + rnd(0.04)));
        Vec3d d = c.subtract(eye);
        float ty = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float tp = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
        float dyaw = MathHelper.wrapDegrees(ty - p.getYaw());
        float dpitch = tp - p.getPitch();
        float stepY = MathHelper.clamp(dyaw * 0.6f + (float) (rng.nextGaussian() * 0.35), -60f, 60f);
        float stepP = MathHelper.clamp(dpitch * 0.6f + (float) (rng.nextGaussian() * 0.3), -40f, 40f);
        p.setYaw(p.getYaw() + stepY);
        p.setPitch(MathHelper.clamp(p.getPitch() + stepP, -90f, 90f));
        boolean aimed = Math.abs(dyaw) < 20 && Math.abs(dpitch) < 20;

        if (breakPos != null) {
            boolean ahead = true; // nie boi się wysokości – idzie dalej nawet bez gruntu pod nogami
            mc.options.forwardKey.setPressed(ahead);
            mc.options.sprintKey.setPressed(false);
            mc.options.jumpKey.setPressed(ahead && p.isOnGround() && p.horizontalCollision
                    && mineTarget.getY() > p.getY() + 1.0);
            // 0 ticków przerwy: uderza w KAŻDYM ticku, obrót głowy tylko wygląda ludzko i niczego nie opóźnia
            ((ClientPlayerInteractionManagerAccessor) mc.interactionManager)
                    .generatorbot$setBlockBreakingCooldown(0);
            mc.interactionManager.updateBlockBreakingProgress(breakPos, side);
            p.swingHand(Hand.MAIN_HAND);
            status = "Kopię idąc (" + cache.size() + " bloków w obszarze)";
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
