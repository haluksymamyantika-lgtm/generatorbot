package pl.generatorbot;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class AreaManager {
    public static class Area {
        public int x1, y1, z1, x2, y2, z2;
        /** false = czerwony (do odwiedzenia), true = niebieski (bot już tu był). Nie zapisuje się do pliku. */
        public transient boolean visited;
        /** Nazwa nadana w panelu "Miejsca" (pusta = domyślna). */
        public String name = "";
        /** ON/OFF z panelu: wyłączone miejsce bot pomija. */
        public boolean enabled = true;

        public String displayName(int index) {
            return (name == null || name.isBlank()) ? "Miejsce " + (index + 1) : name;
        }

        public Area() {}

        public Area(BlockPos a, BlockPos b) {
            x1 = Math.min(a.getX(), b.getX()); x2 = Math.max(a.getX(), b.getX());
            y1 = Math.min(a.getY(), b.getY()); y2 = Math.max(a.getY(), b.getY());
            z1 = Math.min(a.getZ(), b.getZ()); z2 = Math.max(a.getZ(), b.getZ());
        }

        public Box box() { return new Box(x1, y1, z1, x2 + 1, y2 + 1, z2 + 1); }

        public boolean containsExpanded(Vec3d v, double e) {
            return v.x >= x1 - e && v.x <= x2 + 1 + e
                    && v.y >= y1 - e && v.y <= y2 + 1 + e
                    && v.z >= z1 - e && v.z <= z2 + 1 + e;
        }

        public Vec3d center() { return new Vec3d((x1 + x2 + 1) / 2.0, (y1 + y2 + 1) / 2.0, (z1 + z2 + 1) / 2.0); }

        public Vec3d flyPoint() { return new Vec3d((x1 + x2 + 1) / 2.0, y2 + 2.0, (z1 + z2 + 1) / 2.0); }
    }

    public final List<Area> areas = new ArrayList<>();
    public BlockPos pending;

    private final Path file = FabricLoader.getInstance().getConfigDir().resolve("generatorbot_areas.json");
    private final Gson gson = new Gson();

    public AreaManager() {
        try {
            if (Files.exists(file)) {
                List<Area> loaded = gson.fromJson(Files.readString(file), new TypeToken<List<Area>>() {}.getType());
                if (loaded != null) areas.addAll(loaded);
            }
        } catch (Exception ignored) {}
    }

    public void save() {
        try { Files.writeString(file, gson.toJson(areas)); } catch (Exception ignored) {}
    }

    public void clear() { areas.clear(); pending = null; save(); }

    public void onSelectPressed(MinecraftClient mc) {
        if (mc.player == null) return;
        if (mc.player.isSneaking()) { // Shift+J: cofnij
            if (pending != null) { pending = null; Bot.msg(mc, "Anulowano zaznaczanie."); }
            else if (!areas.isEmpty()) { areas.remove(areas.size() - 1); Bot.msg(mc, "Usunięto ostatni obszar."); }
            save();
            return;
        }
        BlockPos p = aimedPos(mc);
        if (pending == null) {
            pending = p;
            Bot.msg(mc, "Pierwszy róg: " + p.toShortString() + " – zaznacz drugi (J). Shift+J = anuluj/usuń ostatni.");
        } else {
            areas.add(new Area(pending, p));
            pending = null;
            save();
            Bot.msg(mc, "Dodano obszar nr " + areas.size() + ".");
        }
    }

    private BlockPos aimedPos(MinecraftClient mc) {
        HitResult hr = mc.player.raycast(160, 1f, false);
        if (hr.getType() == HitResult.Type.BLOCK) return BlockPos.ofFloored(hr.getPos().add(
                mc.player.getRotationVec(1f).multiply(0.01)));
        return mc.player.getBlockPos();
    }
}
