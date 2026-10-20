package pl.generatorbot;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

public class BotScreen extends Screen {
    private static final int PANEL_W = 250, PANEL_H = 292;
    private KeyBinding waiting;
    private ButtonWidget startKeyBtn, stopKeyBtn, recBtn;
    private int top;

    public BotScreen() { super(Text.literal("GeneratorBot")); }

    @Override
    protected void init() {
        int cx = width / 2;
        top = Math.max(4, height / 2 - PANEL_H / 2);
        int x = cx - 110;

        // --- START / STOP
        addDrawableChild(ButtonWidget.builder(Text.literal("START").formatted(Formatting.GREEN, Formatting.BOLD), b -> {
            GeneratorBotClient.BOT.start(client);
            close();
        }).dimensions(x, top + 52, 107, 22).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("STOP").formatted(Formatting.RED, Formatting.BOLD), b -> {
            GeneratorBotClient.BOT.stop(client);
            close();
        }).dimensions(x + 113, top + 52, 107, 22).build());

        // --- MIEJSCA
        addDrawableChild(ButtonWidget.builder(Text.literal("Panel miejsc: nazwy, kolejność, ON/OFF"), b ->
                client.setScreen(new PlacesScreen())).dimensions(x, top + 98, 220, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Wszystkie na czerwono"), b -> {
            for (AreaManager.Area a : GeneratorBotClient.AREAS.areas) a.visited = false;
        }).dimensions(x, top + 122, 107, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Wyczyść obszary"), b ->
                GeneratorBotClient.AREAS.clear()).dimensions(x + 113, top + 122, 107, 20).build());

        // --- SKLEP
        recBtn = addDrawableChild(ButtonWidget.builder(Text.empty(), b -> {
            if (ShopRecorder.recording) ShopRecorder.stop(client);
            else { ShopRecorder.start(client); close(); }
            refresh();
        }).dimensions(x, top + 166, 107, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Usuń nagranie"), b -> {
            ShopRecorder.clear();
            refresh();
        }).dimensions(x + 113, top + 166, 107, 20).build());

        // --- KLAWISZE
        startKeyBtn = addDrawableChild(ButtonWidget.builder(Text.empty(), b -> { waiting = GeneratorBotClient.startKey; refresh(); })
                .dimensions(x, top + 214, 220, 20).build());
        stopKeyBtn = addDrawableChild(ButtonWidget.builder(Text.empty(), b -> { waiting = GeneratorBotClient.stopKey; refresh(); })
                .dimensions(x, top + 238, 220, 20).build());
        refresh();
    }

    private void refresh() {
        startKeyBtn.setMessage(label("Klawisz START: ", GeneratorBotClient.startKey));
        stopKeyBtn.setMessage(label("Klawisz STOP: ", GeneratorBotClient.stopKey));
        recBtn.setMessage(Text.literal(ShopRecorder.recording ? "Zakończ nagrywanie" : "Nagraj klikanie sklepu")
                .formatted(ShopRecorder.recording ? Formatting.YELLOW : Formatting.WHITE));
    }

    private Text label(String prefix, KeyBinding kb) {
        if (waiting == kb) return Text.literal(prefix + "> naciśnij klawisz (Esc = brak) <").formatted(Formatting.YELLOW);
        return Text.literal(prefix).append(kb.getBoundKeyLocalizedText().copy().formatted(Formatting.AQUA));
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

    /** Panel pod przyciskami (renderBackground woła się przed widgetami). */
    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.renderBackground(ctx, mouseX, mouseY, delta);
        int x0 = width / 2 - PANEL_W / 2, x1 = x0 + PANEL_W, y0 = top - 6, y1 = top + PANEL_H;
        ctx.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, 0xFF3A7BD5);   // ramka
        ctx.fill(x0, y0, x1, y1, 0xE0101420);                    // tło
        ctx.fill(x0, y0, x1, y0 + 34, 0xFF1B2A4A);               // pasek tytułu
        ctx.fill(x0 + 10, top + 90, x1 - 10, top + 91, 0xFF2C3E66);
        ctx.fill(x0 + 10, top + 158, x1 - 10, top + 159, 0xFF2C3E66);
        ctx.fill(x0 + 10, top + 206, x1 - 10, top + 207, 0xFF2C3E66);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int cx = width / 2, x = cx - 110;

        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("GeneratorBot").formatted(Formatting.GOLD, Formatting.BOLD), cx, top + 2, 0xFFFFFF);

        Bot.State st = GeneratorBotClient.BOT.state;
        Formatting col = switch (st) {
            case STOPPED -> Formatting.RED;
            case MINE -> Formatting.GREEN;
            case FLY -> Formatting.AQUA;
            default -> Formatting.YELLOW;
        };
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal(GeneratorBotClient.BOT.status).formatted(col), cx, top + 16, 0xFFFFFF);

        int total = GeneratorBotClient.AREAS.areas.size(), on = 0, red = 0, blue = 0;
        for (AreaManager.Area a : GeneratorBotClient.AREAS.areas) {
            if (!a.enabled) continue;
            on++;
            if (a.visited) blue++; else red++;
        }
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Miejsca: " + total + "  ON: " + on + "  czerwone: " + red + "  niebieskie: " + blue)
                        .formatted(Formatting.GRAY), cx, top + 30, 0xFFFFFF);

        ctx.drawTextWithShadow(textRenderer, Text.literal("MIEJSCA").formatted(Formatting.GRAY, Formatting.BOLD), x, top + 84, 0xFFFFFF);
        ctx.drawTextWithShadow(textRenderer, Text.literal("SKLEP (/sklep)").formatted(Formatting.GRAY, Formatting.BOLD), x, top + 152, 0xFFFFFF);
        int steps = ShopRecorder.steps.size();
        ctx.drawTextWithShadow(textRenderer,
                Text.literal(ShopRecorder.recording ? "Nagrywam… kroki: " + steps
                        : steps > 0 ? "Nagrane kroki sklepu: " + steps : "Brak nagrania (bot szuka po nazwach)")
                        .formatted(steps > 0 || ShopRecorder.recording ? Formatting.GREEN : Formatting.DARK_GRAY),
                x, top + 190, 0xFFFFFF);
        ctx.drawTextWithShadow(textRenderer, Text.literal("KLAWISZE").formatted(Formatting.GRAY, Formatting.BOLD), x, top + 200, 0xFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("J – róg   Shift+J – cofnij   L – menu").formatted(Formatting.DARK_GRAY), cx, top + 266, 0xFFFFFF);
    }

    @Override
    public boolean shouldPause() { return false; }
}
