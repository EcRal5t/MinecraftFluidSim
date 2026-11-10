package mmm.client;

import java.util.ArrayList;
import java.util.List;
import mmm.network.SampleAnalysisPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Scrollable view of the current sample and an optional locally retained baseline. */
public final class SampleAnalysisScreen extends Screen {
    private static final SampleComparison comparison = new SampleComparison();
    private final SampleAnalysisPayload sample;
    private int offset;
    private SampleAnalysisScreen(SampleAnalysisPayload sample) {
        super(Component.literal("容器样品分析"));
        this.sample = sample;
    }
    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(SampleAnalysisPayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    var level = context.client().level;
                    comparison.observeDimension(level == null ? null : level.dimension().identifier().toString());
                    context.client().setScreenAndShow(new SampleAnalysisScreen(payload));
                }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> comparison.observeDimension(null));
        ClientTickEvents.END_CLIENT_TICK.register(client -> comparison.observeDimension(
                client.level == null ? null : client.level.dimension().identifier().toString()));
    }
    @Override protected void init() {
        int buttonWidth = Math.min(140, (width - 24) / 2);
        addRenderableWidget(Button.builder(Component.literal("保存当前样品为基准"), button -> {
            comparison.save(sample); offset = 0; rebuildWidgets();
        }).bounds(width / 2 - buttonWidth - 3, height - 28, buttonWidth, 20).build());
        var clear = Button.builder(Component.literal("清除基准"), button -> {
            comparison.clear(); offset = 0; rebuildWidgets();
        }).bounds(width / 2 + 3, height - 28, buttonWidth, 20).build();
        clear.active = comparison.baseline() != null;
        addRenderableWidget(clear);
    }
    private List<FormattedCharSequence> wrapped(SampleAnalysisPayload value, int columnWidth) {
        var result = new ArrayList<FormattedCharSequence>();
        for (String line : value.lines()) result.addAll(font.split(Component.literal(line), Math.max(1, columnWidth)));
        return result;
    }
    private int columnWidth() { return comparison.baseline() == null ? Math.max(1, width - 32) : Math.max(1, (width - 40) / 2); }
    private int visibleLines() { return Math.max(1, (height - 70) / 11); }
    private int maxOffset() {
        int count = wrapped(sample, columnWidth()).size();
        if (comparison.baseline() != null) count = Math.max(count, wrapped(comparison.baseline(), columnWidth()).size());
        return Math.max(0, count - visibleLines());
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        offset = Math.max(0, Math.min(maxOffset(), offset - (int) Math.signum(vertical) * 3));
        return true;
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, 8, 0xffffffff);
        int columnWidth = columnWidth();
        graphics.text(font, comparison.baseline() == null ? "当前样品" : "基准样品", 16, 23, 0xffaaddff, false);
        if (comparison.baseline() != null) graphics.text(font, "当前样品", 24 + columnWidth, 23, 0xffaaddff, false);
        offset = Math.min(offset, maxOffset());
        drawLines(graphics, comparison.baseline() == null ? sample : comparison.baseline(), 16, columnWidth);
        if (comparison.baseline() != null) drawLines(graphics, sample, 24 + columnWidth, columnWidth);
        if (offset < maxOffset()) graphics.text(font, "↓", width - 16, height - 40, 0xffaaaaaa, false);
    }
    private void drawLines(GuiGraphicsExtractor graphics, SampleAnalysisPayload payload, int x, int columnWidth) {
        var lines = wrapped(payload, columnWidth);
        for (int i = offset; i < Math.min(lines.size(), offset + visibleLines()); i++)
            graphics.text(font, lines.get(i), x, 37 + (i - offset) * 11, 0xffffffff, false);
    }
}
