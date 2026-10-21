package pl.generatorbot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;

/** Wspólny styl menu: ciemny motyw, różowy akcent, zaokrąglone karty. */
public final class Ui {
    public static final int BG = 0xFF17161B, SIDEBAR = 0xFF111014, CARD = 0xFF25232B, CARD_HOVER = 0xFF322F3B,
            ACCENT = 0xFFE4318A, ACCENT_HOVER = 0xFFF0489A, ACCENT_DARK = 0xFF4A1E3A,
            GREEN = 0xFF2FBF71, GREEN_HOVER = 0xFF3DD584, RED = 0xFFD9435A, RED_HOVER = 0xFFEB5870,
            LINE = 0xFF2A2830, TEXT = 0xFFFFFFFF, MUTED = 0xFF9A98A3;

    private Ui() {}

    public static void round(DrawContext c, int x1, int y1, int x2, int y2, int col) {
        c.fill(x1 + 2, y1, x2 - 2, y2, col);
        c.fill(x1, y1 + 2, x2, y2 - 2, col);
        c.fill(x1 + 1, y1 + 1, x2 - 1, y2 - 1, col);
    }

    public static void roundBorder(DrawContext c, int x1, int y1, int x2, int y2, int border, int fill) {
        round(c, x1, y1, x2, y2, border);
        round(c, x1 + 1, y1 + 1, x2 - 1, y2 - 1, fill);
    }

    /** Karta z delikatną ramką. */
    public static void card(DrawContext c, int x1, int y1, int x2, int y2) {
        roundBorder(c, x1, y1, x2, y2, 0xFF34313D, CARD);
    }

    /** Kolorowa kropka statusu. */
    public static void dot(DrawContext c, int x, int y, int col) {
        round(c, x, y, x + 6, y + 6, col);
    }

    /** Cień pod oknem. */
    public static void shadow(DrawContext c, int x1, int y1, int x2, int y2) {
        for (int i = 6; i >= 1; i--) c.fill(x1 - i, y1 - i + 3, x2 + i, y2 + i + 3, (0x0C * (7 - i) / 2) << 24);
    }

    public static void text(DrawContext c, Text t, int x, int y, int color) {
        c.drawTextWithShadow(MinecraftClient.getInstance().textRenderer, t, x, y, color);
    }

    public static void big(DrawContext c, Text t, int x, int y, float scale) {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        MatrixStack m = c.getMatrices();
        m.push();
        m.translate(x, y, 0);
        m.scale(scale, scale, 1f);
        c.drawTextWithShadow(tr, t, 0, 0, TEXT);
        m.pop();
    }
}
