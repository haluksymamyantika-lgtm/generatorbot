package pl.generatorbot;

import com.google.gson.Gson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** Ustawienia wyglądu (zapisywane w config/generatorbot_settings.json). */
public class Settings {
    public static float labelScale = 1.5f;
    public static boolean showLabels = true;
    /** true = po zakupach w /sklep bot sam się wyłącza i włącza; false = zatrzymuje się i czeka na ręczny Start. */
    public static boolean autoRestart = true;

    private static class Data { float labelScale = 1.5f; boolean showLabels = true; boolean autoRestart = true; }

    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("generatorbot_settings.json");
    private static final Gson GSON = new Gson();

    static { load(); }

    public static void load() {
        try {
            if (Files.exists(FILE)) {
                Data d = GSON.fromJson(Files.readString(FILE), Data.class);
                if (d != null) { labelScale = d.labelScale; showLabels = d.showLabels; autoRestart = d.autoRestart; }
            }
        } catch (Exception ignored) {}
    }

    public static void save() {
        try {
            Data d = new Data();
            d.labelScale = labelScale; d.showLabels = showLabels; d.autoRestart = autoRestart;
            Files.writeString(FILE, GSON.toJson(d));
        } catch (Exception ignored) {}
    }
}
