package pl.generatorbot;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/** Czerwone zaznaczenie jak w Litematice: półprzezroczysta bryła + krawędzie. */
public class AreaRenderer {
    private static final int[][] FACES = {{0,1,2,3},{4,7,6,5},{0,4,5,1},{1,5,6,2},{2,6,7,3},{3,7,4,0}};
    private static final int[][] EDGES = {{0,1},{1,2},{2,3},{3,0},{4,5},{5,6},{6,7},{7,4},{0,4},{1,5},{2,6},{3,7}};

    public static void render(WorldRenderContext ctx) {
        AreaManager am = GeneratorBotClient.AREAS;
        if (am.areas.isEmpty() && am.pending == null) return;
        MatrixStack ms = ctx.matrixStack();
        VertexConsumerProvider vcp = ctx.consumers();
        if (ms == null || vcp == null) return;

        List<Box> boxes = new ArrayList<>();
        List<float[]> cols = new ArrayList<>();
        float[] RED = {1f, 0.05f, 0.05f}, BLUE = {0.15f, 0.4f, 1f}, GRAY = {0.6f, 0.6f, 0.6f};
        for (AreaManager.Area a : am.areas) { boxes.add(a.box().expand(0.003)); cols.add(!a.enabled ? GRAY : a.visited ? BLUE : RED); }
        if (am.pending != null) { boxes.add(new Box(am.pending).expand(0.003)); cols.add(RED); }

        Vec3d cam = ctx.camera().getPos();
        ms.push();
        ms.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f mt = ms.peek().getPositionMatrix();

        VertexConsumer fill = vcp.getBuffer(RenderLayer.getDebugFilledBox());
        for (int i = 0; i < boxes.size(); i++) fill(fill, mt, boxes.get(i), cols.get(i));
        if (vcp instanceof VertexConsumerProvider.Immediate imm) imm.draw(RenderLayer.getDebugFilledBox());

        VertexConsumer lines = vcp.getBuffer(RenderLayer.getLines());
        for (int i = 0; i < boxes.size(); i++) outline(lines, ms.peek(), boxes.get(i), cols.get(i));
        if (vcp instanceof VertexConsumerProvider.Immediate imm) imm.draw(RenderLayer.getLines());

        // duże nazwy nad miejscami (rosną z odległością, więc widać je z daleka, także przez bloki)
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        for (int i = 0; i < am.areas.size(); i++) {
            AreaManager.Area a = am.areas.get(i);
            Vec3d c = new Vec3d((a.x1 + a.x2 + 1) / 2.0, a.y2 + 3.0, (a.z1 + a.z2 + 1) / 2.0);
            double dist = c.distanceTo(cam);
            if (dist > 500) continue;
            Formatting col = !a.enabled ? Formatting.GRAY : a.visited ? Formatting.AQUA : Formatting.RED;
            Text label = Text.literal((i + 1) + ". " + a.displayName(i)).formatted(col, Formatting.BOLD);
            float s = (float) Math.max(0.08, dist * 0.018);
            ms.push();
            ms.translate(c.x, c.y, c.z);
            ms.multiply(ctx.camera().getRotation());
            ms.scale(-s, -s, s);
            Matrix4f m = ms.peek().getPositionMatrix();
            float w = tr.getWidth(label);
            tr.draw(label, -w / 2f, 0f, 0xFFFFFFFF, false, m, vcp,
                    TextRenderer.TextLayerType.SEE_THROUGH, 0x90000000, 0xF000F0);
            ms.pop();
        }
        if (vcp instanceof VertexConsumerProvider.Immediate imm) imm.draw();

        ms.pop();
    }

    private static float[][] corners(Box b) {
        float x1 = (float) b.minX, y1 = (float) b.minY, z1 = (float) b.minZ;
        float x2 = (float) b.maxX, y2 = (float) b.maxY, z2 = (float) b.maxZ;
        return new float[][]{{x1,y1,z1},{x2,y1,z1},{x2,y1,z2},{x1,y1,z2},{x1,y2,z1},{x2,y2,z1},{x2,y2,z2},{x1,y2,z2}};
    }

    private static void fill(VertexConsumer c, Matrix4f mt, Box b, float[] col) {
        float[][] v = corners(b);
        for (int[] f : FACES) {
            for (int k = 0; k < 4; k++) c.vertex(mt, v[f[k]][0], v[f[k]][1], v[f[k]][2]).color(col[0], col[1], col[2], 0.22f);
            for (int k = 3; k >= 0; k--) c.vertex(mt, v[f[k]][0], v[f[k]][1], v[f[k]][2]).color(col[0], col[1], col[2], 0.22f);
        }
    }

    private static void outline(VertexConsumer c, MatrixStack.Entry e, Box b, float[] col) {
        float[][] v = corners(b);
        Matrix4f mt = e.getPositionMatrix();
        for (int[] ed : EDGES) {
            float[] a = v[ed[0]], d = v[ed[1]];
            float nx = d[0] - a[0], ny = d[1] - a[1], nz = d[2] - a[2];
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len == 0) continue;
            nx /= len; ny /= len; nz /= len;
            c.vertex(mt, a[0], a[1], a[2]).color(col[0], col[1], col[2], 1f).normal(e, nx, ny, nz);
            c.vertex(mt, d[0], d[1], d[2]).color(col[0], col[1], col[2], 1f).normal(e, nx, ny, nz);
        }
    }
}
