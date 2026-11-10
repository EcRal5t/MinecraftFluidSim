package mmm.client;

import java.util.ArrayList;
import java.util.Locale;
import java.util.TreeSet;
import mmm.chemistry.ChemicalLimits;
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

/** Scrollable view of the current sample and an optional locally retained baseline. */
public final class SampleAnalysisScreen extends Screen {
    private static final SampleComparison comparison = new SampleComparison();
    private static final ObservationOpen pending = new ObservationOpen();
    private final SampleAnalysisPayload sample;
    private int offset;
    private SampleAnalysisScreen(SampleAnalysisPayload sample) {
        super(text("title"));
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
    private final List<Row> rows = new ArrayList<>();
    private record Cell(String value, double fraction) {
        Cell(String value) { this(value, Double.NaN); }
    }
    private record Row(Component label, List<Cell> cells, boolean header) { }
    private static Component text(String key) { return Component.translatable("screen.mmm.sample." + key); }
    @Override protected void init() {
        int w = 140;
        addRenderableWidget(IndustrialUi.button(text("save"), button -> {
            comparison.save(sample); offset = 0; rebuildWidgets();
        }).bounds(width / 2 - w - 3, height - 32, w, 20).build());
        var clear = IndustrialUi.button(text("clear"), button -> {
            comparison.clear(); offset = 0; rebuildWidgets();
        }).bounds(width / 2 + 3, height - 32, w, 20).build();
        clear.active = comparison.baseline() != null;
        addRenderableWidget(clear);
        buildRows();
    }
    private void buildRows() {
        rows.clear();
        var base = comparison.baseline();
        boolean paired = base != null;
        header("quantity", paired ? new String[]{"baseline", "current", "change"} : new String[]{"current"});
        quantity("mass", base == null ? Double.NaN : base.massKg(), sample.massKg(), "kg", true);
        quantity("volume", base == null ? Double.NaN : base.volumeM3(), sample.volumeM3(), "m³",
                base == null || base.phase() == sample.phase());
        quantity("temperature", base == null ? Double.NaN : base.temperatureK(), sample.temperatureK(), "K", true);
        quantity("energy", base == null ? Double.NaN : base.internalEnergyJ() / 1e6, sample.internalEnergyJ() / 1e6, "MJ", true);
        header("materials", paired ? new String[]{"mass", "volume", "mass", "volume"} : new String[]{"mass", "volume"});
        var materials = new TreeSet<String>();
        sample.materials().forEach(m -> materials.add(m.id()));
        if (base != null) base.materials().forEach(m -> materials.add(m.id()));
        for (String id : materials) {
            var cells = new ArrayList<Cell>();
            if (base != null) materialCells(cells, base, id);
            materialCells(cells, sample, id);
            rows.add(new Row(CompositionNames.label(id), cells, false));
        }
        if (materials.isEmpty()) rows.add(new Row(text("empty"), List.of(), false));
        header("species", paired ? new String[]{"amount", "mass_fraction", "mole_fraction", "concentration",
                "amount", "mass_fraction", "mole_fraction", "concentration"}
                : new String[]{"amount", "mass_fraction", "mole_fraction", "concentration"});
        var species = new TreeSet<String>();
        sample.species().forEach(s -> species.add(s.id()));
        if (base != null) base.species().forEach(s -> species.add(s.id()));
        for (String id : species) {
            var cells = new ArrayList<Cell>();
            if (base != null) speciesCells(cells, base, id);
            speciesCells(cells, sample, id);
            rows.add(new Row(CompositionNames.label(id), cells, false));
        }
        if (species.isEmpty()) rows.add(new Row(text("unrecorded"), List.of(), false));
    }
    private void header(String label, String[] columns) {
        rows.add(new Row(text(label), java.util.Arrays.stream(columns).map(k -> new Cell(text(k).getString())).toList(), true));
    }
    private void quantity(String key, double baseline, double current, String unit, boolean comparable) {
        var cells = new ArrayList<Cell>();
        if (comparison.baseline() != null) cells.add(new Cell(number(baseline) + " " + unit));
        cells.add(new Cell(number(current) + " " + unit));
        if (comparison.baseline() != null) cells.add(new Cell(comparable ? signed(current - baseline) + " " + unit : "—"));
        rows.add(new Row(text(key), cells, false));
    }
    private static void materialCells(List<Cell> cells, SampleAnalysisPayload value, String id) {
        var material = value.materials().stream().filter(m -> m.id().equals(id)).findFirst().orElse(null);
        cells.add(new Cell(number(material == null ? 0 : material.massKg()) + " kg"));
        cells.add(new Cell(number(material == null ? 0 : material.volumeM3()) + " m³"));
    }
    private static void speciesCells(List<Cell> cells, SampleAnalysisPayload value, String id) {
        var species = value.species().stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
        if (species == null) {
            for (int i = 0; i < 4; i++) cells.add(new Cell(value.completeSpecies() ? "0" : "—"));
            return;
        }
        double moles = (double) species.amountQuanta() / ChemicalLimits.QUANTA_PER_MOL;
        cells.add(new Cell(number(moles)));
        cells.add(new Cell(number(species.massFraction() * 100), species.massFraction()));
        cells.add(new Cell(number(species.moleFraction() * 100)));
        cells.add(new Cell(number(value.volumeM3() > 0 ? moles / value.volumeM3() : Double.NaN)));
    }
    private int tableTop() {
        return sample.phase() == SampleAnalysisPayload.Phase.GAS
                || comparison.baseline() != null && comparison.baseline().phase() == SampleAnalysisPayload.Phase.GAS ? 62 : 50;
    }
    private int visibleRows() { return Math.max(1, (height - tableTop() - 40) / 21); }
    private int maxOffset() { return Math.max(0, rows.size() - visibleRows()); }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        offset = Math.clamp(offset - (int) Math.signum(vertical) * 3, 0, maxOffset());
        return true;
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void tick() {
        if (minecraft.level == null || !sample.dimension().equals(minecraft.level.dimension().identifier().toString())) onClose();
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        IndustrialUi.panel(g, 8, 8, width - 16, height - 16);
        IndustrialUi.header(g, 9, 9, width - 18);
        IndustrialUi.brand(g, 13, 11, 16);
        g.text(font, title, 35, 14, IndustrialUi.TEXT, false);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int labelWidth = 128, dataLeft = 20 + labelWidth, dataWidth = width - 40 - labelWidth;
        var base = comparison.baseline();
        if (base == null) sampleHeading(g, sample, text("current"), dataLeft, dataWidth);
        else {
            sampleHeading(g, base, text("baseline"), dataLeft, dataWidth / 2);
            sampleHeading(g, sample, text("current"), dataLeft + dataWidth / 2, dataWidth / 2);
        }
        offset = Math.min(offset, maxOffset());
        for (int i = offset; i < Math.min(rows.size(), offset + visibleRows()); i++) {
            Row row = rows.get(i);
            int y = tableTop() + (i - offset) * 21;
            g.fill(16, y, width - 20, y + 21, row.header() ? IndustrialUi.INSET
                    : (i & 1) == 0 ? IndustrialUi.PANEL : 0xff28353e);
            g.text(font, row.label(), 21, y + 6, row.header() ? IndustrialUi.CYAN : IndustrialUi.TEXT, false);
            int n = row.cells().size();
            for (int col = 0; col < n; col++) {
                Cell cell = row.cells().get(col);
                int x = dataLeft + dataWidth * col / n, end = dataLeft + dataWidth * (col + 1) / n - 8;
                g.enableScissor(x, y, end, y + 21);
                if (row.header()) {
                    String[] lines = cell.value().split("\\n|\n", -1);
                    for (int line = 0; line < lines.length; line++)
                        g.text(font, lines[line], end - font.width(lines[line]), y + (lines.length > 1 ? 1 : 6) + line * 10, IndustrialUi.MUTED, false);
                } else {
                    g.text(font, cell.value(), end - font.width(cell.value()), y + 5, IndustrialUi.TEXT, false);
                    if (Double.isFinite(cell.fraction())) IndustrialUi.bar(g, end - 46, y + 16, 46, 3, cell.fraction(), IndustrialUi.CYAN);
                }
                g.disableScissor();
            }
        }
        IndustrialUi.scrollbar(g, width - 16, tableTop(), visibleRows() * 21, offset, visibleRows(), rows.size());
    }
    private void sampleHeading(GuiGraphicsExtractor g, SampleAnalysisPayload value, Component heading, int x, int w) {
        IndustrialUi.icon(g, switch (value.phase()) {
            case LIQUID -> IndustrialUi.Icon.LIQUID;
            case GAS -> IndustrialUi.Icon.GAS;
            case SOLID -> IndustrialUi.Icon.SOLID;
        }, x, 35, 14);
        g.text(font, heading, x + 20, 36, IndustrialUi.CYAN, false);
        String phase = text(value.phase().name().toLowerCase(Locale.ROOT)).getString();
        g.text(font, phase, x + w - 8 - font.width(phase), 36, IndustrialUi.MUTED, false);
        if (value.phase() == SampleAnalysisPayload.Phase.GAS) g.text(font, text("standard"), x + 20, 48, IndustrialUi.MUTED, false);
    }
    private static String number(double value) {
        if (!Double.isFinite(value)) return "—";
        if (value == 0) return "0";
        double magnitude = Math.abs(value);
        return magnitude >= 1e5 || magnitude < .001 ? String.format(Locale.ROOT, "%.3g", value)
                : java.math.BigDecimal.valueOf(value).round(new java.math.MathContext(5)).stripTrailingZeros().toPlainString();
    }
    private static String signed(double value) { return Double.isFinite(value) && value > 0 ? "+" + number(value) : number(value); }
}
