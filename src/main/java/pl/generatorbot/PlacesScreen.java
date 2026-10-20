package pl.generatorbot;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import pl.generatorbot.AreaManager.Area;

import java.util.Collections;
import java.util.List;

/** Panel miejsc: nazwa, ON/OFF, kolor (czerwony/niebieski), kolejność lotu, usuwanie. */
public class PlacesScreen extends Screen {
    private static final int PER_PAGE = 6;
    private int page;

    public PlacesScreen() { super(Text.literal("Miejsca")); }

    @Override
    protected void init() {
        List<Area> list = GeneratorBotClient.AREAS.areas;
        int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        int x0 = width / 2 - 160, y0 = 44;

        for (int r = 0; r < PER_PAGE; r++) {
            int idx = page * PER_PAGE + r;
            if (idx >= list.size()) break;
            final int i = idx;
            final Area a = list.get(idx);
            int y = y0 + r * 26;

            TextFieldWidget tf = new TextFieldWidget(textRenderer, x0, y, 126, 20, Text.literal("Nazwa"));
            tf.setMaxLength(24);
            tf.setText(a.displayName(i));
            tf.setChangedListener(s -> a.name = s);
            addDrawableChild(tf);

            addDrawableChild(ButtonWidget.builder(Text.literal(a.enabled ? "ON" : "OFF"), b -> {
                a.enabled = !a.enabled;
                b.setMessage(Text.literal(a.enabled ? "ON" : "OFF"));
            }).dimensions(x0 + 130, y, 36, 20).build());

            addDrawableChild(ButtonWidget.builder(Text.literal(a.visited ? "Niebieski" : "Czerwony"), b -> {
                a.visited = !a.visited;
                b.setMessage(Text.literal(a.visited ? "Niebieski" : "Czerwony"));
            }).dimensions(x0 + 170, y, 74, 20).build());

            addDrawableChild(ButtonWidget.builder(Text.literal("^"), b -> {
                if (i > 0) { Collections.swap(list, i, i - 1); clearAndInit(); }
            }).dimensions(x0 + 248, y, 20, 20).build());

            addDrawableChild(ButtonWidget.builder(Text.literal("v"), b -> {
                if (i < list.size() - 1) { Collections.swap(list, i, i + 1); clearAndInit(); }
            }).dimensions(x0 + 272, y, 20, 20).build());

            addDrawableChild(ButtonWidget.builder(Text.literal("X"), b -> {
                list.remove(i);
                GeneratorBotClient.AREAS.save();
                clearAndInit();
            }).dimensions(x0 + 296, y, 20, 20).build());
        }

        int yb = y0 + PER_PAGE * 26 + 6;
        final int lastPage = pages - 1;
        addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> {
            if (page > 0) { page--; clearAndInit(); }
        }).dimensions(x0, yb, 40, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> {
            if (page < lastPage) { page++; clearAndInit(); }
        }).dimensions(x0 + 44, yb, 40, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Wszystkie ON"), b -> {
            for (Area ar : list) ar.enabled = true;
            clearAndInit();
        }).dimensions(x0 + 90, yb, 110, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Wróć"), b ->
                client.setScreen(new BotScreen())).dimensions(x0 + 206, yb, 110, 20).build());
    }

    @Override
    public void removed() {
        GeneratorBotClient.AREAS.save();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int cx = width / 2;
        int pages = Math.max(1, (GeneratorBotClient.AREAS.areas.size() + PER_PAGE - 1) / PER_PAGE);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Miejsca  (strona " + (page + 1) + "/" + pages + ")"), cx, 12, 0xFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Bot leci po kolei z góry na dół. ON = leci, OFF = pomija. ^ v = kolejność."), cx, 26, 0xAAAAAA);
    }

    @Override
    public boolean shouldPause() { return false; }
}
