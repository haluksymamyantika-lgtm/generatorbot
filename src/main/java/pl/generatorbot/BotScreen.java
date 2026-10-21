package pl.generatorbot;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;
import pl.generatorbot.FlatButton.Kind;

public class BotScreen extends Screen {
    private static final int W = 440, H = 276, SIDE = 120;
    private static final String[] TAB_NAMES = {"Bot", "Miejsca", "Sklep", "Wygląd", "Klawisze"};
    private static final String[] TAB_DESC = {
            "Steruj pracą bota", "Zaznaczone generatory i ich kolejność", "Automatyczne zakupy w /sklep",
            "Wygląd nazw nad generatorami", "Przypisz klawisze Start i Stop"};
    private static int tab = 0;

    private KeyBinding waiting;
    private int x0, y0, cx0, cw;

    public BotScreen() { super(Text.literal("GeneratorBot")); }

    @Override
    protected void init() {
        x0 = (width - W) / 2;
        y0 = (height - H) / 2;
        cx0 = x0 + SIDE + 14;
        cw = W - SIDE - 28;

        nav(0, y0 + 66); nav(1, y0 + 90); nav(2, y0 + 114);
        nav(3, y0 + 170); nav(4, y0 + 194);

        int half = (cw - 8) / 2;
        switch (tab) {
            case 0 -> {
                addDrawableChild(new FlatButton(cx0, y0 + 124, half, 28, Text.literal("START").formatted(Formatting.BOLD), Kind.GREEN,
                        () -> { GeneratorBotClient.BOT.start(client); close(); }));
                addDrawableChild(new FlatButton(cx0 + half + 8, y0 + 124, half, 28, Text.literal("STOP").formatted(Formatting.BOLD), Kind.RED,
                        () -> { GeneratorBotClient.BOT.stop(client); close(); }));
            }
            case 1 -> {
                addDrawableChild(new FlatButton(cx0, y0 + 92, cw, 24, Text.literal("Otwórz panel miejsc"), Kind.ACCENT,
                        () -> client.setScreen(new PlacesScreen())));
                addDrawableChild(new FlatButton(cx0, y0 + 122, half, 22, Text.literal("Wszystkie na czerwono"), Kind.NORMAL,
                        () -> { for (AreaManager.Area a : GeneratorBotClient.AREAS.areas) a.visited = false; }));
                addDrawableChild(new FlatButton(cx0 + half + 8, y0 + 122, half, 22, Text.literal("Wyczyść obszary"), Kind.RED,
                        () -> GeneratorBotClient.AREAS.clear()));
            }
            case 2 -> {
                addDrawableChild(new FlatButton(cx0, y0 + 112, half, 24,
                        Text.literal(ShopRecorder.recording ? "Zakończ nagrywanie" : "Nagraj klikanie"), Kind.ACCENT, () -> {
                    if (ShopRecorder.recording) { ShopRecorder.stop(client); clearAndInit(); }
                    else { ShopRecorder.start(client); close(); }
                }));
                addDrawableChild(new FlatButton(cx0 + half + 8, y0 + 112, half, 24, Text.literal("Usuń nagranie"), Kind.RED,
                        () -> { ShopRecorder.clear(); clearAndInit(); }));
            }
            case 3 -> {
                addDrawableChild(new FlatSlider(cx0 + 10, y0 + 100, cw - 20, 14, 0.5, 5.0, Settings.labelScale,
                        v -> Settings.labelScale = (float) v, Settings::save));
                int pw = (cw - 20 - 18) / 4;
                String[] names = {"Mały", "Średni", "Duży", "Ogromny"};
                float[] vals = {0.8f, 1.5f, 2.5f, 4.0f};
                for (int i = 0; i < 4; i++) {
                    final float val = vals[i];
                    addDrawableChild(new FlatButton(cx0 + 10 + i * (pw + 6), y0 + 124, pw, 20, Text.literal(names[i]), Kind.NORMAL,
                            () -> { Settings.labelScale = val; Settings.save(); clearAndInit(); }));
                }
                addDrawableChild(new FlatButton(cx0, y0 + 164, cw, 24,
                        Text.literal("Nazwy nad generatorami: " + (Settings.showLabels ? "WŁĄCZONE" : "WYŁĄCZONE")),
                        Settings.showLabels ? Kind.GREEN : Kind.RED,
                        () -> { Settings.showLabels = !Settings.showLabels; Settings.save(); clearAndInit(); }));
            }
            case 4 -> {
                addDrawableChild(new FlatButton(cx0, y0 + 60, cw, 24, keyLabel("Start: ", GeneratorBotClient.startKey), Kind.NORMAL,
                        () -> { waiting = GeneratorBotClient.startKey; clearAndInit(); }));
                addDrawableChild(new FlatButton(cx0, y0 + 90, cw, 24, keyLabel("Stop: ", GeneratorBotClient.stopKey), Kind.NORMAL,
                        () -> { waiting = GeneratorBotClient.stopKey; clearAndInit(); }));
            }
            default -> {}
        }
    }

    private void nav(int idx, int y) {
        FlatButton b = new FlatButton(x0 + 8, y, SIDE - 16, 20, Text.literal(TAB_NAMES[idx]),
                tab == idx ? Kind.NAV_ACTIVE : Kind.NAV, () -> { tab = idx; waiting = null; clearAndInit(); });
        b.leftAlign = true;
        addDrawableChild(b);
    }

    private Text keyLabel(String prefix, KeyBinding kb) {
        if (waiting == kb) return Text.literal(prefix + "naciśnij klawisz… (Esc = brak)").formatted(Formatting.YELLOW);
        return Text.literal(prefix).append(kb.getBoundKeyLocalizedText().copy().formatted(Formatting.AQUA));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (waiting != null) {
            waiting.setBoundKey(keyCode == GLFW.GLFW_KEY_ESCAPE ? InputUtil.UNKNOWN_KEY : InputUtil.fromKeyCode(keyCode, scanCode));
            KeyBinding.updateKeysByCode();
            client.options.write();
            waiting = null;
            clearAndInit();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private static Formatting stateColor(Bot.State st) {
        return switch (st) {
            case STOPPED -> Formatting.RED;
            case MINE -> Formatting.GREEN;
            case FLY -> Formatting.AQUA;
            default -> Formatting.YELLOW;
        };
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        super.renderBackground(c, mouseX, mouseY, delta);
        Ui.roundBorder(c, x0 - 1, y0 - 1, x0 + W + 1, y0 + H + 1, Ui.LINE, Ui.BG);
        c.fill(x0 + 1, y0 + 2, x0 + SIDE, y0 + H - 2, Ui.SIDEBAR);

        // --- pasek boczny
        Ui.big(c, Text.literal("GeneratorBot").formatted(Formatting.BOLD), x0 + 10, y0 + 12, 1.2f);
        Ui.text(c, Text.literal("Wersja 1.1"), x0 + 10, y0 + 28, Ui.MUTED);
        Ui.text(c, Text.literal("FUNKCJE"), x0 + 10, y0 + 54, Ui.MUTED);
        Ui.text(c, Text.literal("INNE"), x0 + 10, y0 + 158, Ui.MUTED);
        Bot st = GeneratorBotClient.BOT;
        Ui.text(c, Text.literal("● ").formatted(stateColor(st.state)).append(Text.literal(st.state == Bot.State.STOPPED ? "Zatrzymany" : "Pracuje").formatted(Formatting.GRAY)),
                x0 + 10, y0 + H - 22, 0xFFFFFFFF);

        // --- nagłówek
        Ui.big(c, Text.literal(TAB_NAMES[tab]).formatted(Formatting.BOLD), cx0, y0 + 14, 1.4f);
        Ui.text(c, Text.literal(TAB_DESC[tab]), cx0, y0 + 34, Ui.MUTED);

        switch (tab) {
            case 0 -> {
                Ui.round(c, cx0, y0 + 52, cx0 + cw, y0 + 112, Ui.CARD);
                Ui.text(c, Text.literal("Status"), cx0 + 10, y0 + 60, Ui.MUTED);
                Ui.text(c, Text.literal(st.status).formatted(stateColor(st.state), Formatting.BOLD), cx0 + 10, y0 + 74, 0xFFFFFFFF);
                int total = GeneratorBotClient.AREAS.areas.size(), on = 0, red = 0, blue = 0;
                for (AreaManager.Area a : GeneratorBotClient.AREAS.areas) {
                    if (!a.enabled) continue;
                    on++;
                    if (a.visited) blue++; else red++;
                }
                Ui.text(c, Text.literal("Miejsca: " + total + "   ON: " + on + "   czerwone: " + red + "   niebieskie: " + blue),
                        cx0 + 10, y0 + 92, Ui.MUTED);
                Ui.round(c, cx0, y0 + 162, cx0 + cw, y0 + 232, Ui.CARD);
                Ui.text(c, Text.literal("Sloty paska"), cx0 + 10, y0 + 170, Ui.MUTED);
                Ui.text(c, Text.literal("1 siekiera   2 miecz   3 elytra   4 fajerwerki"), cx0 + 10, y0 + 186, 0xFFFFFFFF);
                Ui.text(c, Text.literal("Bot sam zakłada elytrę i naprawia przedmioty."), cx0 + 10, y0 + 204, Ui.MUTED);
            }
            case 1 -> {
                Ui.text(c, Text.literal("J – zaznacz róg (dwa rogi = obszar)"), cx0, y0 + 56, 0xFFFFFFFF);
                Ui.text(c, Text.literal("Shift+J – cofnij róg / usuń ostatni obszar"), cx0, y0 + 70, Ui.MUTED);
            }
            case 2 -> {
                Ui.round(c, cx0, y0 + 52, cx0 + cw, y0 + 102, Ui.CARD);
                Ui.text(c, Text.literal("Kliknij „Nagraj klikanie”, wpisz /sklep"), cx0 + 10, y0 + 60, 0xFFFFFFFF);
                Ui.text(c, Text.literal("i kliknij ręcznie wszystko po kolei."), cx0 + 10, y0 + 72, 0xFFFFFFFF);
                Ui.text(c, Text.literal("Bot odtworzy dokładnie te kliknięcia."), cx0 + 10, y0 + 86, Ui.MUTED);
                int steps = ShopRecorder.steps.size();
                Ui.round(c, cx0, y0 + 146, cx0 + cw, y0 + 176, Ui.CARD);
                Ui.text(c, Text.literal(ShopRecorder.recording ? "Nagrywam… kroki: " + steps
                                : steps > 0 ? "Zapisane kroki sklepu: " + steps : "Brak nagrania (bot szuka po nazwach)")
                                .formatted(steps > 0 || ShopRecorder.recording ? Formatting.GREEN : Formatting.GRAY),
                        cx0 + 10, y0 + 157, 0xFFFFFFFF);
            }
            case 3 -> {
                Ui.round(c, cx0, y0 + 52, cx0 + cw, y0 + 154, Ui.CARD);
                Ui.text(c, Text.literal("Rozmiar nazw"), cx0 + 10, y0 + 60, Ui.MUTED);
                Ui.text(c, Text.literal("x" + String.format("%.1f", Settings.labelScale)).formatted(Formatting.BOLD),
                        cx0 + cw - 34, y0 + 60, 0xFFFFFFFF);
                Ui.text(c, Text.literal("Przeciągnij suwak lub wybierz gotowy rozmiar"), cx0 + 10, y0 + 76, Ui.MUTED);
            }
            case 4 -> {
                Ui.round(c, cx0, y0 + 126, cx0 + cw, y0 + 190, Ui.CARD);
                Ui.text(c, Text.literal("Klawisze stałe"), cx0 + 10, y0 + 134, Ui.MUTED);
                Ui.text(c, Text.literal("J – zaznacz róg   Shift+J – cofnij"), cx0 + 10, y0 + 150, 0xFFFFFFFF);
                Ui.text(c, Text.literal("L – to menu (zmienisz w Sterowaniu)"), cx0 + 10, y0 + 166, 0xFFFFFFFF);
            }
            default -> {}
        }
    }

    @Override
    public boolean shouldPause() { return false; }
}
