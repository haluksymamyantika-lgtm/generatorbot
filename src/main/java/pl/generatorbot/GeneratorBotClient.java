package pl.generatorbot;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import org.lwjgl.glfw.GLFW;

public class GeneratorBotClient implements ClientModInitializer {
    public static KeyBinding selectKey, menuKey, startKey, stopKey;
    public static final AreaManager AREAS = new AreaManager();
    public static final Bot BOT = new Bot();

    @Override
    public void onInitializeClient() {
        selectKey = reg("key.generatorbot.select", GLFW.GLFW_KEY_J);
        menuKey = reg("key.generatorbot.menu", GLFW.GLFW_KEY_L);
        startKey = reg("key.generatorbot.start", GLFW.GLFW_KEY_UNKNOWN);
        stopKey = reg("key.generatorbot.stop", GLFW.GLFW_KEY_UNKNOWN);

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (selectKey.wasPressed()) AREAS.onSelectPressed(mc);
            while (menuKey.wasPressed()) if (mc.currentScreen == null) mc.setScreen(new BotScreen());
            while (startKey.wasPressed()) BOT.start(mc);
            while (stopKey.wasPressed()) BOT.stop(mc);
            Macros.tick(mc);
            BOT.tick(mc);
        });

        // nagrywanie: kliknięcia w okna i klawisze cyfr nad slotem
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof HandledScreen<?> hs) {
                ScreenMouseEvents.beforeMouseClick(screen).register((scr, mx, my, btn) -> Macros.onSlotClick(client, hs, btn));
                ScreenKeyboardEvents.beforeKeyPress(screen).register((scr, key, sc, mod) -> Macros.onKey(client, hs, key));
            }
        });

        // nagrywanie: prawy klik w blok (kowadło) i przedmiotem w powietrze
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClient && hand == Hand.MAIN_HAND) Macros.onBlock(MinecraftClient.getInstance(), hit);
            return ActionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClient && hand == Hand.MAIN_HAND) Macros.onItem(MinecraftClient.getInstance());
            return ActionResult.PASS;
        });

        WorldRenderEvents.AFTER_ENTITIES.register(AreaRenderer::render);
        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> LabelHud.render(drawContext));
    }

    private static KeyBinding reg(String name, int key) {
        return KeyBindingHelper.registerKeyBinding(
                new KeyBinding(name, InputUtil.Type.KEYSYM, key, "category.generatorbot"));
    }
}
