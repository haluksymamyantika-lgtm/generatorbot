package pl.generatorbot;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector4f;

/**
 * Nazwy miejsc rysowane na ekranie (HUD), a nie w świecie: zawsze widoczne, rozmiar z menu (Wygląd).
 * Pozycję na ekranie liczymy z macierzy kamery zapisanej podczas renderowania świata.
 */
public class LabelHud {
    private static Matrix4f proj, view;
    private static Vec3d cam;

    public static void capture(WorldRenderContext ctx) {
        proj = new Matrix4f(ctx.projectionMatrix());
        view = new Matrix4f().rotation(new Quaternionf(ctx.camera().getRotation()).conjugate());
        cam = ctx.camera().getPos();
    }

    public static void render(DrawContext dc) {
        if (!Settings.showLabels || proj == null || view == null || cam == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.options.hudHidden) return;
        TextRenderer tr = mc.textRenderer;
        int sw = dc.getScaledWindowWidth(), sh = dc.getScaledWindowHeight();

        for (int i = 0; i < GeneratorBotClient.AREAS.areas.size(); i++) {
            AreaManager.Area a = GeneratorBotClient.AREAS.areas.get(i);
            Vec3d c = new Vec3d((a.x1 + a.x2 + 1) / 2.0, a.y2 + 3.0, (a.z1 + a.z2 + 1) / 2.0);
            Vec3d rel = c.subtract(cam);
            Vector4f v = new Vector4f((float) rel.x, (float) rel.y, (float) rel.z, 1f);
            view.transform(v);
            proj.transform(v);
            if (v.w <= 0.05f) continue;                       // za plecami
            float nx = v.x / v.w, ny = v.y / v.w;
            if (Math.abs(nx) > 1.4f || Math.abs(ny) > 1.4f) continue;
            float sx = (nx * 0.5f + 0.5f) * sw;
            float sy = (0.5f - ny * 0.5f) * sh;

            Formatting col = !a.enabled ? Formatting.GRAY : a.visited ? Formatting.AQUA : Formatting.RED;
            Text name = Text.literal((i + 1) + ". " + a.displayName(i)).formatted(col, Formatting.BOLD);
            Text dist = Text.literal(Math.round(rel.length()) + " m").formatted(Formatting.GRAY);

            MatrixStack ms = dc.getMatrices();
            ms.push();
            ms.translate(sx, sy, 0f);
            float k = Settings.labelScale;
            ms.scale(k, k, 1f);
            int w = tr.getWidth(name);
            dc.fill(-w / 2 - 4, -3, w / 2 + 4, 20, 0xA0000000);
            dc.drawTextWithShadow(tr, name, -w / 2, 0, 0xFFFFFFFF);
            int dw = tr.getWidth(dist);
            dc.drawTextWithShadow(tr, dist, -dw / 2, 10, 0xFFFFFFFF);
            ms.pop();
        }
    }
}
