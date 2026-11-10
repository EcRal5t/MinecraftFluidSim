package mmm.client;

import java.util.ArrayList;
import java.util.List;
import mmm.network.SampleAnalysisPayload;
import mmm.network.SampleAnalysisRequest;
import mmm.simulation.SampleAnalysis;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.HitResult;
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
    private static final ObservationOpen pending = new ObservationOpen();
    private final SampleAnalysisPayload sample;
    private int offset;
    private SampleAnalysisScreen(SampleAnalysisPayload sample) {
        super(Component.literal("容器样品分析"));
        this.sample = sample;
    }
    public static void register() {
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (!level.isClientSide() || !player.isShiftKeyDown() || !SampleAnalysis.supported(player.getItemInHand(hand))
                    || player.pick(5.0,1.0F,false).getType()!=HitResult.Type.MISS
                    || !ClientPlayNetworking.canSend(SampleAnalysisRequest.TYPE)) return InteractionResult.PASS;
            String dimension=level.dimension().identifier().toString();
            ClientPlayNetworking.send(new SampleAnalysisRequest(pending.start(dimension),dimension,hand));
            return InteractionResult.SUCCESS;
        });
        ClientPlayNetworking.registerGlobalReceiver(SampleAnalysisPayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    var client=context.client(); var level=client.level;
                    if(level==null || client.gui.screen()!=null) return;
                    String dimension=level.dimension().identifier().toString();
                    if(!dimension.equals(payload.dimension()) || !pending.accept(payload.session(),dimension)) return;
                    comparison.observeDimension(dimension);
                    client.setScreenAndShow(new SampleAnalysisScreen(payload));
                }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { comparison.observeDimension(null); pending.clear(); });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            String dimension=client.level == null ? null : client.level.dimension().identifier().toString();
            comparison.observeDimension(dimension); pending.update(dimension,client.gui.screen()!=null);
        });
    }
    @Override protected void init() {
        int buttonWidth = Math.min(140, (width - 24) / 2);
        addRenderableWidget(IndustrialUi.button(Component.literal("保存当前样品为基准"), button -> {
            comparison.save(sample); offset = 0; rebuildWidgets();
        }).bounds(width / 2 - buttonWidth - 3, height - 28, buttonWidth, 20).build());
        var clear = IndustrialUi.button(Component.literal("清除基准"), button -> {
            comparison.clear(); offset = 0; rebuildWidgets();
        }).bounds(width / 2 + 3, height - 28, buttonWidth, 20).build();
        clear.active = comparison.baseline() != null;
        addRenderableWidget(clear);
    }
    private record DisplayLine(FormattedCharSequence text, double fraction) { }
    private List<DisplayLine> wrapped(SampleAnalysisPayload value, int columnWidth) {
        var result = new ArrayList<DisplayLine>();
        double[] fractions = new double[value.lines().size()];
        java.util.Arrays.fill(fractions, -1);
        for (var fraction : value.fractions()) fractions[fraction.line()] = fraction.value();
        for (int index = 0; index < value.lines().size(); index++) {
            String line = value.lines().get(index);
            double fraction = fractions[index];
            int textWidth = fraction >= 0 && columnWidth >= 140 ? columnWidth - 48 : columnWidth;
            var parts = font.split(Component.literal(line), Math.max(1, textWidth));
            for (int i = 0; i < parts.size(); i++) result.add(new DisplayLine(parts.get(i), i == 0 ? fraction : -1));
        }
        return result;
    }
    private int columnWidth() { return comparison.baseline() == null ? Math.max(1, width - 32) : Math.max(1, (width - 40) / 2); }
    private int visibleLines() { return Math.max(1, (height - 91) / 11); }
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
    @Override public void tick() {
        if(minecraft.level==null || !sample.dimension().equals(minecraft.level.dimension().identifier().toString())) onClose();
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        IndustrialUi.panel(graphics, 8, 8, width - 16, height - 16);
        IndustrialUi.header(graphics, 9, 9, width - 18);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        IndustrialUi.brand(graphics, 13, 11, 16);
        graphics.centeredText(font, title, width / 2, 14, IndustrialUi.TEXT);
        int columnWidth = columnWidth();
        if (comparison.baseline() != null) graphics.fill(20 + columnWidth, 48, 21 + columnWidth, height - 36, IndustrialUi.EDGE);
        IndustrialUi.icon(graphics, phase(comparison.baseline() == null ? sample : comparison.baseline()), 16, 31, 16);
        graphics.text(font, comparison.baseline() == null ? "当前样品" : "基准样品", 35, 35, IndustrialUi.CYAN, false);
        if (comparison.baseline() != null) {
            IndustrialUi.icon(graphics, phase(sample), 24 + columnWidth, 31, 16);
            graphics.text(font, "当前样品", 43 + columnWidth, 35, IndustrialUi.CYAN, false);
        }
        offset = Math.min(offset, maxOffset());
        drawLines(graphics, comparison.baseline() == null ? sample : comparison.baseline(), 16, columnWidth);
        if (comparison.baseline() != null) drawLines(graphics, sample, 24 + columnWidth, columnWidth);
        if (maxOffset() > 0) {
            int top = 50, track = Math.max(1, visibleLines() * 11 - 2);
            int thumb = Math.max(4, track * visibleLines() / (visibleLines() + maxOffset()));
            int position = (track - thumb) * offset / maxOffset();
            graphics.fill(width - 14, top, width - 12, top + track, IndustrialUi.INSET);
            graphics.fill(width - 14, top + position, width - 12, top + position + thumb, IndustrialUi.CYAN);
        }
    }
    private static IndustrialUi.Icon phase(SampleAnalysisPayload payload) {
        return switch (payload.phase()) {
            case LIQUID -> IndustrialUi.Icon.LIQUID;
            case GAS -> IndustrialUi.Icon.GAS;
            case SOLID -> IndustrialUi.Icon.SOLID;
        };
    }
    private void drawLines(GuiGraphicsExtractor graphics, SampleAnalysisPayload payload, int x, int columnWidth) {
        var lines = wrapped(payload, columnWidth);
        for (int i = offset; i < Math.min(lines.size(), offset + visibleLines()); i++)
            {
                var line = lines.get(i);
                int y = 52 + (i - offset) * 11;
                graphics.text(font, line.text(), x, y, IndustrialUi.TEXT, false);
                if (line.fraction() >= 0 && columnWidth >= 140)
                    IndustrialUi.bar(graphics, x + columnWidth - 44, y + 2, 42, 6, line.fraction(), IndustrialUi.CYAN);
            }
    }
}
