package pl.generatorbot;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.hit.BlockHitResult;
import org.lwjgl.glfw.GLFW;
import pl.generatorbot.mixin.HandledScreenAccessor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Nagrania: zapisuje to, co robisz ręcznie (kliknięcia w okna, ekwipunek, prawy klik w kowadło, wybór slotu),
 * a bot potem odtwarza dokładnie to samo. Cztery nagrania: zdejmij elytrę, naprawa, sklep, załóż elytrę.
 */
public class Macros {
    public static class Step {
        public String type;        // SELECT, SLOT, BLOCK, ITEM
        public int a, b, c;        // SELECT: a=slot | SLOT: a=id slotu, b=przycisk, c=0 klik / 1 shift+klik / 2 klawisz cyfry (b=slot paska) | BLOCK, ITEM: a=slot paska
        public boolean inv;        // SLOT: true = ekwipunek gracza (E)
        public int x, y, z, side;  // BLOCK: pozycja bloku i strona
        public double hx, hy, hz;  // BLOCK: punkt kliknięcia
        public int delay;          // ticki od poprzedniego kroku (przy odtwarzaniu min. 14, max. 40)
    }

    public static final String[] KEYS = {"elytra_off", "repair", "shop", "elytra_on"};
    public static final String[] TITLES = {"1. Zdejmij elytrę", "2. Naprawa u kowadła", "3. Zakupy w /sklep", "4. Załóż elytrę"};
    private static final String[] HINTS = {
            "Otwórz ekwipunek (E) i zrób to, co robisz ręcznie, żeby zdjąć elytrę (elytra ma trafić do slotu 3, a klata na ciebie). Zamknij ekwipunek.",
            "Stań przy kowadle. Dla każdego przedmiotu: wybierz jego slot, kliknij PPM kowadło, kliknij butelkę z expem, zamknij okno (Esc). Zrób to dla miecza, elytry i siekiery.",
            "Bot sam wpisze /sklep. Ty (po wpisaniu /sklep) kliknij po kolei: Pieniądze, Potrzebne przedmioty, fajerwerki, +16 (tyle razy ile chcesz), Potwierdź. Zamknij okno.",
            "Otwórz ekwipunek (E) i załóż elytrę tak, jak robisz to ręcznie (ze slotu 3 na klatę). Zamknij ekwipunek."};

    private static final Map<String, List<Step>> DATA = new HashMap<>();
    public static String recording;
    private static int sinceLast, lastSelected = -1;
    private static long lastClickMs, lastBlockMs;

    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("generatorbot_macros.json");
    private static final Gson GSON = new Gson();

    static { load(); }

    public static void load() {
        try {
            if (Files.exists(FILE)) {
                Map<String, List<Step>> m = GSON.fromJson(Files.readString(FILE), new TypeToken<Map<String, List<Step>>>() {}.getType());
                DATA.clear();
                if (m != null) DATA.putAll(m);
            }
        } catch (Exception ignored) {}
    }

    public static void save() {
        try { Files.writeString(FILE, GSON.toJson(DATA)); } catch (Exception ignored) {}
    }

    public static List<Step> get(String key) { return DATA.getOrDefault(key, Collections.emptyList()); }
    public static boolean has(String key) { return !get(key).isEmpty(); }
    public static int count(String key) { return get(key).size(); }

    public static void clear(String key) {
        DATA.remove(key);
        if (key.equals(recording)) recording = null;
        save();
    }

    public static void start(MinecraftClient mc, String key) {
        if (mc.player == null) return;
        DATA.put(key, new ArrayList<>());
        recording = key;
        sinceLast = 0;
        lastSelected = mc.player.getInventory().selectedSlot;
        Step s = new Step();
        s.type = "SELECT"; s.a = lastSelected;
        DATA.get(key).add(s);
        int idx = Arrays.asList(KEYS).indexOf(key);
        Bot.msg(mc, "NAGRYWANIE: " + TITLES[idx] + ". " + HINTS[idx]);
        Bot.msg(mc, "Gdy skończysz: naciśnij L → zakładka Nagrania → Zakończ.");
    }

    public static void stop(MinecraftClient mc) {
        if (recording == null) return;
        String k = recording;
        recording = null;
        save();
        Bot.msg(mc, "Zapisano nagranie (" + count(k) + " kroków).");
    }

    private static void add(MinecraftClient mc, Step st, String info) {
        st.delay = sinceLast;
        sinceLast = 0;
        List<Step> l = DATA.computeIfAbsent(recording, k -> new ArrayList<>());
        l.add(st);
        Bot.msg(mc, "Nagranie: krok " + l.size() + " – " + info);
    }

    public static void tick(MinecraftClient mc) {
        if (recording == null) return;
        sinceLast++;
        if (mc.player != null && mc.currentScreen == null) {
            int sel = mc.player.getInventory().selectedSlot;
            if (sel != lastSelected) {
                lastSelected = sel;
                Step s = new Step();
                s.type = "SELECT"; s.a = sel;
                add(mc, s, "wybrany slot " + (sel + 1));
            }
        }
    }

    public static void onSlotClick(MinecraftClient mc, HandledScreen<?> hs, int button) {
        if (recording == null) return;
        long now = System.currentTimeMillis();
        if (now - lastClickMs < 60) return;
        lastClickMs = now;
        Slot s = ((HandledScreenAccessor) hs).generatorbot$getFocusedSlot();
        if (s == null) return;
        Step st = new Step();
        st.type = "SLOT"; st.inv = hs instanceof InventoryScreen;
        st.a = s.id; st.b = button; st.c = Screen.hasShiftDown() ? 1 : 0;
        add(mc, st, "klik w slot " + s.id + " (" + s.getStack().getName().getString() + ")");
    }

    public static void onKey(MinecraftClient mc, HandledScreen<?> hs, int key) {
        if (recording == null) return;
        if (key < GLFW.GLFW_KEY_1 || key > GLFW.GLFW_KEY_9) return;
        Slot s = ((HandledScreenAccessor) hs).generatorbot$getFocusedSlot();
        if (s == null) return;
        Step st = new Step();
        st.type = "SLOT"; st.inv = hs instanceof InventoryScreen;
        st.a = s.id; st.b = key - GLFW.GLFW_KEY_1; st.c = 2;
        add(mc, st, "slot " + s.id + " zamieniony z paskiem " + (st.b + 1));
    }

    public static void onBlock(MinecraftClient mc, BlockHitResult hit) {
        if (recording == null || mc.player == null) return;
        lastBlockMs = System.currentTimeMillis();
        Step st = new Step();
        st.type = "BLOCK"; st.a = mc.player.getInventory().selectedSlot;
        st.x = hit.getBlockPos().getX(); st.y = hit.getBlockPos().getY(); st.z = hit.getBlockPos().getZ();
        st.side = hit.getSide().getId();
        st.hx = hit.getPos().x; st.hy = hit.getPos().y; st.hz = hit.getPos().z;
        add(mc, st, "PPM w blok " + st.x + " " + st.y + " " + st.z);
    }

    public static void onItem(MinecraftClient mc) {
        if (recording == null || mc.player == null) return;
        if (System.currentTimeMillis() - lastBlockMs < 250) return; // to samo kliknięcie co PPM w blok
        Step st = new Step();
        st.type = "ITEM"; st.a = mc.player.getInventory().selectedSlot;
        add(mc, st, "PPM przedmiotem w powietrze");
    }
}
