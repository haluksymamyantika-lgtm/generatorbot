package pl.generatorbot;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import pl.generatorbot.mixin.HandledScreenAccessor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Nagrywa, w które sloty okna sklepu klikasz ręcznie, i zapisuje je do pliku.
 * Bot potem odtwarza dokładnie te kliknięcia (nie musi znać nazw przycisków).
 */
public class ShopRecorder {
    public static boolean recording;
    public static final List<Integer> steps = new ArrayList<>();

    private static int noScreenTicks;
    private static long lastClickMs;
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("generatorbot_shop.json");
    private static final Gson GSON = new Gson();

    static { load(); }

    public static void load() {
        try {
            if (Files.exists(FILE)) {
                List<Integer> l = GSON.fromJson(Files.readString(FILE), new TypeToken<List<Integer>>() {}.getType());
                steps.clear();
                if (l != null) steps.addAll(l);
            }
        } catch (Exception ignored) {}
    }

    public static void save() {
        try { Files.writeString(FILE, GSON.toJson(steps)); } catch (Exception ignored) {}
    }

    public static void clear() { steps.clear(); recording = false; save(); }

    public static void start(MinecraftClient mc) {
        steps.clear();
        recording = true;
        noScreenTicks = 0;
        Bot.msg(mc, "NAGRYWANIE: wpisz /sklep i kliknij ręcznie po kolei: Pieniądze, Potrzebne przedmioty, fajerwerki, "
                + "+16 (tyle razy ile chcesz), Potwierdź. Potem zamknij okno (Esc).");
    }

    public static void stop(MinecraftClient mc) {
        recording = false;
        save();
        Bot.msg(mc, "Zapisano " + steps.size() + " kroków sklepu.");
    }

    public static void onClick(MinecraftClient mc, HandledScreen<?> hs) {
        if (!recording) return;
        long now = System.currentTimeMillis();
        if (now - lastClickMs < 60) return;
        lastClickMs = now;
        Slot s = ((HandledScreenAccessor) hs).generatorbot$getFocusedSlot();
        if (s == null) return;
        int container = hs.getScreenHandler().slots.size() - 36;
        if (s.id < 0 || s.id >= container) return; // klik w ekwipunek gracza pomijamy
        steps.add(s.id);
        Bot.msg(mc, "Krok " + steps.size() + ": slot " + s.id + " (" + s.getStack().getName().getString() + ")");
    }

    public static void tick(MinecraftClient mc) {
        if (!recording) return;
        if (mc.currentScreen == null && !steps.isEmpty()) {
            if (++noScreenTicks > 40) stop(mc);
        } else {
            noScreenTicks = 0;
        }
    }
}
