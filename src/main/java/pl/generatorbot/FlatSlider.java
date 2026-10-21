package pl.generatorbot;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.function.DoubleConsumer;

/** Suwak w stylu menu (różowy pasek + biała gałka). */
public class FlatSlider extends ClickableWidget {
    private final double min, max;
    private double value;
    private final DoubleConsumer onChange;
    private final Runnable onDone;

    public FlatSlider(int x, int y, int w, int h, double min, double max, double value,
                      DoubleConsumer onChange, Runnable onDone) {
        super(x, y, w, h, Text.empty());
        this.min = min; this.max = max; this.value = value;
        this.onChange = onChange; this.onDone = onDone;
    }

    private void setFromMouse(double mx) {
        double t = MathHelper.clamp((mx - (getX() + 4)) / (double) (getWidth() - 8), 0.0, 1.0);
        value = Math.round((min + t * (max - min)) * 10.0) / 10.0;
        onChange.accept(value);
    }

    @Override
    public void onClick(double mouseX, double mouseY) { setFromMouse(mouseX); }

    @Override
    protected void onDrag(double mouseX, double mouseY, double deltaX, double deltaY) { setFromMouse(mouseX); }

    @Override
    public void onRelease(double mouseX, double mouseY) { onDone.run(); }

    @Override
    protected void renderWidget(DrawContext c, int mouseX, int mouseY, float delta) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight(), cy = y + h / 2;
        double t = (value - min) / (max - min);
        int kx = x + 4 + (int) (t * (w - 8));
        Ui.round(c, x, cy - 2, x + w, cy + 2, 0xFF3A3842);
        Ui.round(c, x, cy - 2, Math.max(x + 4, kx), cy + 2, Ui.ACCENT);
        Ui.round(c, kx - 4, y + 1, kx + 4, y + h - 1, isHovered() ? 0xFFFFFFFF : 0xFFE8E8EC);
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        appendDefaultNarrations(builder);
    }
}
