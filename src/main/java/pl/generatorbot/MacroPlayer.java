package pl.generatorbot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/** Odtwarza nagranie krok po kroku, wolno (między krokami min. 14 ticków). */
public class MacroPlayer {
    private final List<Macros.Step> steps;
    private int idx, wait = 12, guard;

    public MacroPlayer(List<Macros.Step> steps) { this.steps = new ArrayList<>(steps); }

    private static void lookAt(ClientPlayerEntity p, Vec3d t) {
        Vec3d d = t.subtract(p.getEyePos());
        p.setYaw((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
        p.setPitch((float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
    }

    /** @return true, gdy nagranie się skończyło. */
    public boolean tick(MinecraftClient mc, ClientPlayerEntity p, ClientWorld w) {
        if (wait > 0) { wait--; return false; }
        if (idx >= steps.size()) {
            KeyBinding.unpressAll();
            if (mc.currentScreen instanceof HandledScreen<?>) p.closeHandledScreen();
            return true;
        }
        Macros.Step s = steps.get(idx);
        boolean ok = true;
        switch (s.type) {
            case "SELECT" -> p.getInventory().selectedSlot = MathHelper.clamp(s.a, 0, 8);
            case "ITEM" -> {
                p.getInventory().selectedSlot = MathHelper.clamp(s.a, 0, 8);
                mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
                p.swingHand(Hand.MAIN_HAND);
            }
            case "BLOCK" -> ok = doBlock(mc, p, s);
            case "SLOT" -> ok = doSlot(mc, p, s);
            default -> {}
        }
        if (!ok) return false;
        idx++;
        guard = 0;
        wait = idx < steps.size() ? MathHelper.clamp(steps.get(idx).delay, 14, 40) : 14;
        return false;
    }

    private boolean doBlock(MinecraftClient mc, ClientPlayerEntity p, Macros.Step s) {
        p.getInventory().selectedSlot = MathHelper.clamp(s.a, 0, 8);
        BlockPos pos = new BlockPos(s.x, s.y, s.z);
        Vec3d c = Vec3d.ofCenter(pos);
        if (p.getEyePos().distanceTo(c) > 4.4 && ++guard < 120) {
            // za daleko od kowadła – podejdź
            lookAt(p, c);
            p.setPitch(10f);
            mc.options.forwardKey.setPressed(true);
            mc.options.jumpKey.setPressed(p.horizontalCollision && p.isOnGround());
            return false;
        }
        KeyBinding.unpressAll();
        Vec3d hit = new Vec3d(s.hx, s.hy, s.hz);
        lookAt(p, hit);
        mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, Direction.byId(s.side), pos, false));
        p.swingHand(Hand.MAIN_HAND);
        return true;
    }

    private boolean doSlot(MinecraftClient mc, ClientPlayerEntity p, Macros.Step s) {
        int syncId;
        if (s.inv) {
            syncId = p.playerScreenHandler.syncId;
        } else {
            if (!(mc.currentScreen instanceof HandledScreen<?>) || mc.currentScreen instanceof InventoryScreen) {
                if (++guard > 160) { idx = steps.size(); wait = 0; Bot.msg(mc, "Nagranie: okno się nie otworzyło – przerywam."); }
                return false;
            }
            ScreenHandler h = ((HandledScreen<?>) mc.currentScreen).getScreenHandler();
            if (s.a >= h.slots.size()) { idx = steps.size(); wait = 0; Bot.msg(mc, "Nagranie: slot spoza okna – przerywam."); return false; }
            syncId = h.syncId;
        }
        SlotActionType type = s.c == 1 ? SlotActionType.QUICK_MOVE : s.c == 2 ? SlotActionType.SWAP : SlotActionType.PICKUP;
        mc.interactionManager.clickSlot(syncId, s.a, s.b, type, p);
        return true;
    }
}
