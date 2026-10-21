package pl.generatorbot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.text.Text;

/** Płaski, ciemny przycisk w stylu nowoczesnych aplikacji. */
public class FlatButton extends ClickableWidget {
    public enum Kind { NORMAL, ACCENT, GREEN, RED, NAV, NAV_ACTIVE }

    private final Kind kind;
    private final Runnable action;
    public boolean leftAlign;

    public FlatButton(int x, int y, int w, int h, Text label, Kind kind, Runnable action) {
        super(x, y, w, h, label);
        this.kind = kind;
        this.action = action;
    }

    @Override
    protected void renderWidget(DrawContext c, int mouseX, int mouseY, float delta) {
        boolean hov = isHovered();
        int bg = switch (kind) {
            case ACCENT, NAV_ACTIVE -> hov ? Ui.ACCENT_HOVER : Ui.ACCENT;
            case GREEN -> hov ? Ui.GREEN_HOVER : Ui.GREEN;
            case RED -> hov ? Ui.RED_HOVER : Ui.RED;
            case NAV -> hov ? Ui.CARD : 0x00000000;
            default -> hov ? Ui.CARD_HOVER : Ui.CARD;
        };
        if (kind == Kind.NAV_ACTIVE) {
            Ui.round(c, getX(), getY(), getX() + getWidth(), getY() + getHeight(), Ui.ACCENT_DARK);
            c.fill(getX(), getY() + 3, getX() + 3, getY() + getHeight() - 3, Ui.ACCENT);
        } else if ((bg >>> 24) != 0) {
            if (kind == Kind.NORMAL) Ui.round(c, getX(), getY(), getX() + getWidth(), getY() + getHeight(), hov ? 0xFF4A4655 : 0xFF34313D);
            Ui.round(c, getX() + (kind == Kind.NORMAL ? 1 : 0), getY() + (kind == Kind.NORMAL ? 1 : 0),
                    getX() + getWidth() - (kind == Kind.NORMAL ? 1 : 0), getY() + getHeight() - (kind == Kind.NORMAL ? 1 : 0), bg);
        }
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        Text msg = getMessage();
        int tw = tr.getWidth(msg);
        int tx = leftAlign ? getX() + 10 : getX() + (getWidth() - tw) / 2;
        int ty = getY() + (getHeight() - 8) / 2;
        int col = (kind == Kind.NAV && !hov) ? Ui.MUTED : Ui.TEXT;
        if (kind == Kind.NAV_ACTIVE) tx += 4;
        c.drawTextWithShadow(tr, msg, tx, ty, col);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        action.run();
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        appendDefaultNarrations(builder);
    }
}
