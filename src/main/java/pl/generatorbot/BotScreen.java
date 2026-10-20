package pl.generatorbot;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public class BotScreen extends Screen {
    private KeyBinding waiting;
    private ButtonWidget startKeyBtn, stopKeyBtn;

    public BotScreen() { super(Text.literal("GeneratorBot")); }

    @Override
    protected void init() {
        int cx = width / 2, y = height / 2 - 75;

        addDrawableChild(ButtonWidget.builder(Text.literal("Start"), b -> {
            GeneratorBotClient.BOT.start(client);
            close();
        }).dimensions(cx - 100, y, 98, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Stop"), b -> {
            GeneratorBotClient.BOT.stop(client);
            close();
        }).dimensions(cx + 2, y, 98, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Miejsca: nazwy, kolejność, ON/OFF"), b ->
                client.setScreen(new PlacesScreen())).dimensions(cx - 100, y + 26, 200, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Wszystkie na czerwono"), b -> {
            for (AreaManager.Area a : GeneratorBotClient.AREAS.areas) a.visited = false;
        }).dimensions(cx - 100, y + 52, 200, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Wyczyść wszystkie obszary"), b ->
                GeneratorBotClient.AREAS.clear()).dimensions(cx - 100, y + 78, 200, 20).build());

        startKeyBtn = addDrawableChild(ButtonWidget.builder(Text.empty(), b -> { waiting = GeneratorBotClient.startKey; refresh(); })
                .dimensions(cx - 100, y + 110, 200, 20).build());
        stopKeyBtn = addDrawableChild(ButtonWidget.builder(Text.empty(), b -> { waiting = GeneratorBotClient.stopKey; refresh(); })
                .dimensions(cx - 100, y + 134, 200, 20).build());
        refresh();
    }

    private void refresh() {
        startKeyBtn.setMessage(label("Klawisz START: ", GeneratorBotClient.startKey));
        stopKeyBtn.setMessage(label("Klawisz STOP: ", GeneratorBotClient.stopKey));
    }

    private Text label(String prefix, KeyBinding kb) {
        if (waiting == kb) return Text.literal(prefix + "> naciśnij klawisz (Esc = brak) <");
        return Text.literal(prefix).append(kb.getBoundKeyLocalizedText());
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (waiting != null) {
            waiting.setBoundKey(keyCode == GLFW.GLFW_KEY_ESCAPE ? InputUtil.UNKNOWN_KEY : InputUtil.fromKeyCode(keyCode, scanCode));
            KeyBinding.updateKeysByCode();
            client.options.write();
            waiting = null;
            refresh();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int cx = width / 2, y = height / 2 - 112;
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("GeneratorBot"), cx, y, 0xFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Status: " + GeneratorBotClient.BOT.status
                + "  |  miejsca: " + GeneratorBotClient.AREAS.areas.size()), cx, y + 12, 0xAAAAAA);
    }

    @Override
    public boolean shouldPause() { return false; }
}
