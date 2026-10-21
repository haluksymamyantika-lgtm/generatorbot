package pl.generatorbot;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import pl.generatorbot.AreaManager.Area;
import pl.generatorbot.FlatButton.Kind;

import java.util.Collections;
import java.util.List;

/** Panel miejsc: nazwa, ON/OFF, kolor, kolejność lotu, usuwanie. */
public class PlacesScreen extends Screen {
    private static final int PER_PAGE = 6, W = 340, ROW = 28;
    private int page, x0, y0, H;

    public PlacesScreen() { super(Text.literal("Miejsca")); }

    @Override
    protected void init() {
        List<Area> list = GeneratorBotClient.AREAS.areas;
        int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        H = 56 + PER_PAGE * ROW + 40;
        x0 = (width - W) / 2;
        y0 = Math.max(4, (height - H) / 2);

        for (int r = 0; r < PER_PAGE; r++) {
            int idx = page * PER_PAGE + r;
            if (idx >= list.size()) break;
            final int i = idx;
            final Area a = list.get(idx);
            int ry = y0 + 52 + r * ROW;

            TextFieldWidget tf = new TextFieldWidget(textRenderer, x0 + 16, ry + 3, 112, 20, Text.literal("Nazwa"));
            tf.setMaxLength(24);
            tf.setText(a.displayName(i));
            tf.setChangedListener(s -> a.name = s);
            addDrawableChild(tf);

            addDrawableChild(new FlatButton(x0 + 132, ry + 3, 38, 20, onOff(a.enabled), a.enabled ? Kind.GREEN : Kind.RED,
                    () -> { a.enabled = !a.enabled; clearAndInit(); }));
            addDrawableChild(new FlatButton(x0 + 174, ry + 3, 70, 20, color(a.visited), Kind.NORMAL,
                    () -> { a.visited = !a.visited; clearAndInit(); }));
            addDrawableChild(new FlatButton(x0 + 248, ry + 3, 20, 20, Text.literal("^"), Kind.NORMAL,
                    () -> { if (i > 0) { Collections.swap(list, i, i - 1); clearAndInit(); } }));
            addDrawableChild(new FlatButton(x0 + 272, ry + 3, 20, 20, Text.literal("v"), Kind.NORMAL,
                    () -> { if (i < list.size() - 1) { Collections.swap(list, i, i + 1); clearAndInit(); } }));
            addDrawableChild(new FlatButton(x0 + 296, ry + 3, 20, 20, Text.literal("X"), Kind.RED,
                    () -> { list.remove(i); GeneratorBotClient.AREAS.save(); clearAndInit(); }));
        }

        int yb = y0 + 52 + PER_PAGE * ROW + 6;
        final int lastPage = pages - 1;
        addDrawableChild(new FlatButton(x0 + 10, yb, 30, 22, Text.literal("<"), Kind.NORMAL,
                () -> { if (page > 0) { page--; clearAndInit(); } }));
        addDrawableChild(new FlatButton(x0 + 44, yb, 30, 22, Text.literal(">"), Kind.NORMAL,
                () -> { if (page < lastPage) { page++; clearAndInit(); } }));
        addDrawableChild(new FlatButton(x0 + 80, yb, 120, 22, Text.literal("Wszystkie ON"), Kind.NORMAL,
                () -> { for (Area ar : list) ar.enabled = true; clearAndInit(); }));
        addDrawableChild(new FlatButton(x0 + 206, yb, 124, 22, Text.literal("Wróć"), Kind.ACCENT,
                () -> client.setScreen(new BotScreen())));
    }

    private static Text onOff(boolean on) { return Text.literal(on ? "ON" : "OFF").formatted(Formatting.BOLD); }

    private static Text color(boolean visited) {
        return Text.literal(visited ? "Niebieski" : "Czerwony").formatted(visited ? Formatting.AQUA : Formatting.RED);
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        super.renderBackground(c, mouseX, mouseY, delta);
        Ui.shadow(c, x0, y0, x0 + W, y0 + H);
        Ui.roundBorder(c, x0 - 1, y0 - 1, x0 + W + 1, y0 + H + 1, 0xFF34313D, Ui.BG);
        c.fillGradient(x0 + 3, y0, x0 + W - 3, y0 + 2, Ui.ACCENT, 0xFF7C3AED);

        List<Area> list = GeneratorBotClient.AREAS.areas;
        int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        Ui.big(c, Text.literal("Miejsca").formatted(Formatting.BOLD), x0 + 14, y0 + 12, 1.4f);
        Ui.text(c, Text.literal("strona " + (page + 1) + "/" + pages), x0 + W - 70, y0 + 16, Ui.MUTED);
        Ui.text(c, Text.literal("Bot leci z góry na dół. ON = leci, OFF = pomija."), x0 + 14, y0 + 34, Ui.MUTED);

        for (int r = 0; r < PER_PAGE; r++) {
            int idx = page * PER_PAGE + r;
            if (idx >= list.size()) break;
            Area a = list.get(idx);
            int ry = y0 + 52 + r * ROW;
            Ui.card(c, x0 + 8, ry, x0 + W - 8, ry + ROW - 2);
            int stripe = !a.enabled ? 0xFF6B6B73 : a.visited ? 0xFF38A9FF : Ui.RED;
            c.fill(x0 + 8, ry + 3, x0 + 11, ry + ROW - 5, stripe);
        }
    }

    @Override
    public void removed() { GeneratorBotClient.AREAS.save(); }

    @Override
    public boolean shouldPause() { return false; }
}
