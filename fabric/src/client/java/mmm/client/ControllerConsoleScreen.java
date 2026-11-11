package mmm.client;

import mmm.units.UnitFormat;

import mmm.controller.ControllerConsoleAction;
import mmm.controller.ControllerSummary;
import mmm.controller.LifecycleOperation;
import mmm.network.ControllerActionPayload;
import mmm.network.LifecycleOperationRequestPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Controller status, server actions and player-local fluid display controls. */
public final class ControllerConsoleScreen extends Screen {
	private final ControllerSummary summary;
	private final String failure;
	private final BlockPos controllerPosition;
	private List<Component> rows = List.of();
	private final List<Button> serverActions = new ArrayList<>();
	private int scroll;
	private boolean details;
	private final Request request;
	static final class Request {
		private final java.util.UUID region;
		private final long generation;
		private boolean pending;
		Request(ControllerSummary summary) { region = summary.regionId(); generation = summary.summaryGeneration(); }
		void begin() { pending = true; }
		boolean awaiting(java.util.UUID regionId, long generation) {
			return pending && region.equals(regionId) && this.generation == generation;
		}
	}

	public ControllerConsoleScreen(ControllerSummary summary) {
		this(summary, null);
	}
	ControllerConsoleScreen(ControllerSummary summary, String failure) {
		super(Component.literal(summary.footprint().name()));
		this.summary = summary;
		this.failure = failure;
		this.request = new Request(summary);
		ControllerSummary.Footprint footprint = summary.footprint();
		controllerPosition = new BlockPos(footprint.controllerX(), footprint.controllerY(), footprint.controllerZ());
	}

	@Override
	protected void init() {
		rows = details ? rows(summary, failure) : List.of();
        int tabWidth = Math.min(110, Math.max(1, (width - 38) / 2));
        int tabLeft = (width - tabWidth * 2 - 6) / 2;
        Button dashboard = IndustrialUi.button(Component.translatable("screen.mmm.controller.dashboard"), button -> {
            details = false; scroll = 0; rebuildWidgets();
        }).bounds(tabLeft, 32, tabWidth, 20).selected(!details).build();
        dashboard.active = details;
        addRenderableWidget(dashboard);
        Button detailTab = IndustrialUi.button(Component.translatable("screen.mmm.controller.details"), button -> {
            details = true; scroll = 0; rebuildWidgets();
        }).bounds(tabLeft + tabWidth + 6, 32, tabWidth, 20).selected(details).build();
        detailTab.active = !details;
        addRenderableWidget(detailTab);
		serverActions.clear();
        int gap = 6;
        int buttonWidth = Math.min(92, Math.max(1, (width - 32 - 3 * gap) / 4));
        int left = (width - (buttonWidth * 4 + gap * 3)) / 2;
        int y = height - 30;
        addServerAction(IndustrialUi.button(Component.translatable("screen.mmm.controller.select"),
                button -> send(ControllerConsoleAction.SELECT)).bounds(left, y, buttonWidth, 20).build());
        Component runAction = Component.translatable(summary.operatorIntent() == ControllerSummary.OperatorIntent.PAUSED
                ? "screen.mmm.controller.resume" : "screen.mmm.controller.pause");
        addServerAction(IndustrialUi.button(runAction, button -> send(
                summary.operatorIntent() == ControllerSummary.OperatorIntent.PAUSED
                        ? ControllerConsoleAction.RESUME : ControllerConsoleAction.PAUSE))
                .bounds(left + buttonWidth + gap, y, buttonWidth, 20).build());
        addServerAction(IndustrialUi.button(Component.translatable("screen.mmm.controller.refresh"),
                button -> send(ControllerConsoleAction.REFRESH_AVAILABILITY))
                .bounds(left + (buttonWidth + gap) * 2, y, buttonWidth, 20).build());
        addServerAction(IndustrialUi.button(Component.translatable("screen.mmm.display.open"),
                button -> minecraft.setScreenAndShow(new FluidDisplayScreen(this)))
                .bounds(left + (buttonWidth + gap) * 3, y, buttonWidth, 20).build());
        int lifecycleWidth = buttonWidth;
        int lifecycleLeft = left;
        int lifecycleY = height - 54;
		addLifecycleButton(lifecycleLeft, lifecycleY, lifecycleWidth, "empty", LifecycleOperation.EMPTY_CONDENSED);
		addLifecycleButton(lifecycleLeft + lifecycleWidth + gap, lifecycleY, lifecycleWidth,
				"vent", LifecycleOperation.VENT_SEALED_GAS);
		addLifecycleButton(lifecycleLeft + (lifecycleWidth + gap) * 2, lifecycleY, lifecycleWidth,
				"release", LifecycleOperation.RELEASE_OWNERSHIP);
		addLifecycleButton(lifecycleLeft + (lifecycleWidth + gap) * 3, lifecycleY, lifecycleWidth,
				"remove", LifecycleOperation.REMOVE_EMPTY_REGION);
	}

	private void addLifecycleButton(int x, int y, int width, String label, LifecycleOperation operation) {
		addServerAction(IndustrialUi.button(Component.translatable("screen.mmm.controller." + label),
				button -> {
					disableServerActions();
					ClientPlayNetworking.send(LifecycleOperationRequestPayload.preview(
							summary.regionId(), controllerPosition, summary.summaryGeneration(), operation));
				})
				.bounds(x, y, width, 20).build());
	}

	private void addServerAction(Button button) {
		if (request.pending) button.active = false;
		serverActions.add(button);
		addRenderableWidget(button);
	}

	void disableServerActions() { request.begin(); serverActions.forEach(button -> button.active = false); }
	boolean awaiting(java.util.UUID regionId, long generation) {
		return request.awaiting(regionId, generation);
	}
	boolean inDimension(String dimension) { return summary.dimensionId().equals(dimension); }
	ControllerConsoleScreen updated(ControllerSummary next, String failure) {
		var screen = new ControllerConsoleScreen(next, failure);
		screen.details = details;
		screen.scroll = failure == null ? scroll : 0;
		return screen;
	}

	private void send(ControllerConsoleAction action) {
		disableServerActions();
		ClientPlayNetworking.send(new ControllerActionPayload(summary.regionId(), controllerPosition,
				summary.summaryGeneration(), action));
	}
	@Override public boolean isPauseScreen() { return false; }

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		IndustrialUi.panel(graphics, 8, 8, width - 16, height - 16);
        IndustrialUi.header(graphics, 9, 9, width - 18);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        IndustrialUi.brand(graphics, 13, 11, 16);
        graphics.centeredText(font, title, width / 2, 14, IndustrialUi.TEXT);
        if (!details) {
            renderDashboard(graphics);
            return;
        }
        List<FormattedCharSequence> lines = wrappedRows();
        int visible = detailVisibleLines();
        scroll = Math.min(scroll, Math.max(0, lines.size() - visible));
        for (int i = scroll; i < Math.min(lines.size(), scroll + visible); i++)
            graphics.text(font, lines.get(i), 20, 58 + (i - scroll) * 12, IndustrialUi.TEXT, false);
        IndustrialUi.scrollbar(graphics, width - 17, 58, Math.max(1, visible * 12), scroll, visible, lines.size());
    }

    private int dashboardVisibleRows() { return Math.max(1, (height - 176) / 16); }
    private int dashboardTotalRows() { return summary.inventory().components().size(); }
    private void renderDashboard(GuiGraphicsExtractor graphics) {
        int left = 16, right = width - 22, contentWidth = Math.max(1, right - left);
        int statusY = 58;
        graphics.fill(left, statusY, right, statusY + 18, 0xff1c2a30);
        Component operator = localized("operator", summary.operatorIntent());
        Component availability = summary.availability().ready() ? common("ready")
                : localized("availability", summary.availability().reason());
        graphics.text(font, operator, left + 5, statusY + 5, IndustrialUi.TEXT, false);
        graphics.text(font, availability, left + Math.max(100, contentWidth / 3), statusY + 5,
                summary.availability().ready() ? IndustrialUi.CYAN : IndustrialUi.AMBER, false);
        if (failure != null) {
            int x = left + contentWidth * 2 / 3;
            String message = failure.lines().findFirst().orElse("");
            if (font.width(message) > right - x - 5)
                message = font.plainSubstrByWidth(message, right - x - 5 - font.width("…")) + "…";
            graphics.text(font, message, x, statusY + 5, IndustrialUi.AMBER, false);
        }
        int nameX = left + 4, massX = left + contentWidth * 38 / 100;
        int volumeX = left + contentWidth * 57 / 100, tempX = left + contentWidth * 77 / 100;
        graphics.text(font, Component.translatable("screen.mmm.controller.column_component"), nameX, 82, IndustrialUi.MUTED, false);
        graphics.text(font, Component.translatable("screen.mmm.controller.column_mass"), massX, 82, IndustrialUi.MUTED, false);
        graphics.text(font, Component.translatable("screen.mmm.controller.column_quantity"), volumeX, 82, IndustrialUi.MUTED, false);
        graphics.text(font, Component.translatable("screen.mmm.controller.column_temperature"), tempX, 82, IndustrialUi.MUTED, false);
        var rows = summary.inventory().components();
        int visible = dashboardVisibleRows();
        scroll = Math.min(scroll, Math.max(0, rows.size() - visible));
        for (int i = scroll; i < Math.min(rows.size(), scroll + visible); i++) {
            var row = rows.get(i); int y = 106 + (i - scroll) * 16;
            graphics.fill(left, y, right, y + 16, i % 2 == 0 ? 0xff202f35 : 0xff19262c);
            String name = CompositionNames.label(row.id()).getString();
            if (row.residualMass()) name += " " + Component.translatable("screen.mmm.controller.residual_mass").getString();
            var icon = CompositionNames.phaseIcon(row.phase(), row.ion());
            if (icon != null) IndustrialUi.icon(graphics, icon, nameX, y + 2, 12);
            tableText(graphics, name, nameX + (icon == null ? 0 : 14), massX - 4, y + 4);
            tableText(graphics, Double.isNaN(row.massKilograms()) ? "—" : decimal(row.massKilograms()) + " kg", massX, volumeX - 4, y + 4);
            String quantity = row.ion() ? "" : row.phase() == ControllerSummary.Phase.GAS
                    ? (Double.isNaN(row.amountMoles()) ? "—" : decimal(row.amountMoles()) + " mol")
                    : (Double.isNaN(row.volumeCubicMetres()) ? "—" : UnitFormat.volume(row.volumeCubicMetres()));
            tableText(graphics, quantity, volumeX, tempX - 4, y + 4);
            var range = row.temperatureRange();
            if (range.available() && Double.isFinite(row.medianTemperatureK())) {
                String center = decimal(row.medianTemperatureK());
                String upper = "+" + temperatureDeviation(range.maximumK() - row.medianTemperatureK());
                String lower = "−" + temperatureDeviation(row.medianTemperatureK() - range.minimumK());
                tableText(graphics, center, tempX, right - 2, y + 4);
                int deviationX = tempX + font.width(center) + 2;
                graphics.enableScissor(deviationX, y, Math.max(deviationX + 1, right - 2), y + 16);
                graphics.pose().pushMatrix();
                graphics.pose().scale(.65f, .65f);
                graphics.text(font, upper, (int)(deviationX / .65f), (int)(y / .65f), IndustrialUi.TEXT, false);
                graphics.text(font, lower, (int)(deviationX / .65f), (int)((y + 9) / .65f), IndustrialUi.TEXT, false);
                graphics.pose().popMatrix();
                graphics.disableScissor();
                int unitX = deviationX + (int)Math.ceil(Math.max(font.width(upper), font.width(lower)) * .65) + 2;
                tableText(graphics, "K", unitX, right - 2, y + 4);
            } else tableText(graphics, "—", tempX, right - 2, y + 4);
        }
        if (!summary.inventory().live()) graphics.text(font, Component.translatable("screen.mmm.controller.inventory_unloaded"), nameX, 110, IndustrialUi.MUTED, false);
        else if (rows.isEmpty()) graphics.text(font, Component.translatable("screen.mmm.controller.inventory_empty"), nameX, 110, IndustrialUi.MUTED, false);
        var inventory = summary.inventory();
        String energy = inventory.internalEnergyAvailable() ? UnitFormat.energy(inventory.totalInternalEnergyJ()) : "—";
        graphics.text(font, Component.translatable("screen.mmm.controller.total_energy", energy), left + 4,
                height - 70, IndustrialUi.TEXT, false);
        if (rows.stream().anyMatch(row -> !row.ion() && row.phase() != ControllerSummary.Phase.GAS && Double.isNaN(row.volumeCubicMetres())))
            graphics.text(font, Component.translatable("screen.mmm.controller.volume_unavailable"),
                    left + contentWidth / 2, height - 70, IndustrialUi.MUTED, false);
        IndustrialUi.scrollbar(graphics, width - 17, 106, visible * 16, scroll, visible, rows.size());
    }
    private void tableText(GuiGraphicsExtractor graphics, String text, int x, int right, int y) {
        graphics.enableScissor(x, y - 2, Math.max(x + 1, right), y + 11);
        graphics.text(font, text, x, y, IndustrialUi.TEXT, false);
        graphics.disableScissor();
    }
    private List<FormattedCharSequence> wrappedRows() {
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (Component row : rows) lines.addAll(font.split(row, Math.max(1, width - 42)));
        return lines;
    }
    private int detailVisibleLines() { return Math.max(1, (height - 142) / 12); }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        int maximum = details ? Math.max(0, wrappedRows().size() - detailVisibleLines())
                : Math.max(0, dashboardTotalRows() - dashboardVisibleRows());
        scroll = Math.max(0, Math.min(maximum, scroll - (int)Math.signum(vertical) * (details ? 3 : 1)));
        return true;
    }

	private static List<Component> rows(ControllerSummary value, String failure) {
		ArrayList<Component> result = new ArrayList<>();
		if (failure != null) result.add(Component.literal(failure).withStyle(net.minecraft.ChatFormatting.RED));
		result.add(Component.translatable("screen.mmm.controller.identity",
				value.regionId().toString().substring(0, 8), value.dimensionId(),
				value.profileFingerprint().substring(0, 12)));
		result.add(Component.translatable("screen.mmm.controller.generations",
				value.checkpointGeneration(), value.networkGeneration()));
		result.add(Component.translatable("screen.mmm.controller.state",
				common(value.selected() ? "selected" : "not_selected"),
				localized("operator", value.operatorIntent()), localized("motion", value.motionState()),
				localized("thermal", value.thermalState()), value.availability().ready()
						? common("ready") : localized("availability", value.availability().reason())));
		ControllerSummary.Footprint footprint = value.footprint();
		result.add(Component.translatable("screen.mmm.controller.footprint", footprint.blockSizeX(),
				footprint.blockSizeY(), footprint.blockSizeZ(), footprint.interiorBlocks(), footprint.requiredChunks()));
		ControllerSummary.Usage usage = value.usage();
		result.add(Component.translatable("screen.mmm.controller.usage_primary",
				capacity(usage.cells()),
				capacity(usage.activeParcels())));
		result.add(Component.translatable("screen.mmm.controller.usage_secondary",
				capacity(usage.depositGroups()), capacity(usage.coherentGroups()),
				capacity(usage.gasNodes()), capacity(usage.finiteDevices())));
		ControllerSummary.Inventory inventory = value.inventory();
		if (inventory.live()) {
			result.add(Component.translatable("screen.mmm.controller.inventory_liquid",
					UnitFormat.volume(inventory.liquidVolumeCubicMetres()),
					decimal(inventory.liquidMassKilograms()), UnitFormat.amount(inventory.speciesQuanta())));
			result.add(Component.translatable("screen.mmm.controller.inventory_solid",
					UnitFormat.solidVolume(inventory.granularSolidVolumeSvq()), UnitFormat.solidVolume(inventory.coherentSolidVolumeSvq())));
			result.add(Component.translatable("screen.mmm.controller.inventory_gas_energy",
					UnitFormat.volumeNanos(inventory.gasStandardVolumeNanos()), decimal(inventory.gasMassKilograms()),
					inventory.internalEnergyAvailable() ? UnitFormat.energy(inventory.totalInternalEnergyJ()) : "—"));
			ControllerSummary.TemperatureRange range = inventory.temperatureRange();
			result.add(Component.translatable("screen.mmm.controller.temperature_range",
					range.available() ? decimal(range.minimumK()) : "—",
					range.available() ? decimal(range.maximumK()) : "—"));
		} else {
			result.add(Component.translatable("screen.mmm.controller.inventory_unloaded"));
		}
		ControllerSummary.Gates gates = value.gates();
		result.add(Component.translatable("screen.mmm.controller.gates", state(gates.ownershipReady()),
				state(!gates.containerWalPending()), state(!gates.phaseWorkPending()),
				common(gates.worldBridgeEnabled() ? "on" : "off"), state(gates.checkpointAllowed())));
		ControllerSummary.Diagnostics diagnostics = value.diagnostics();
		result.add(Component.translatable("screen.mmm.controller.diagnostics",
				decimal(diagnostics.lastStepMilliseconds()), diagnostics.pressureIterations(),
				decimal(diagnostics.pressureResidual())));
		if (!value.availability().ready()) result.add(Component.literal(value.availability().detail()));
		return List.copyOf(result);
	}

	private static String capacity(ControllerSummary.Capacity value) {
		return value.available() ? value.used() + "/" + value.limit() : "—/" + value.limit();
	}

	private static Component state(boolean value) { return common(value ? "ready" : "blocked"); }
	private static Component common(String value) {
		return Component.translatable("screen.mmm.common." + value);
	}
	private static Component localized(String family, Enum<?> value) {
		return Component.translatable("screen.mmm." + family + "." + value.name().toLowerCase(Locale.ROOT));
	}
    private static String temperatureDeviation(double value) {
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
	private static String decimal(double value) {
        String text = String.format(Locale.ROOT, "%.6g", value);
        int exponent = Math.max(text.indexOf('e'), text.indexOf('E'));
        String suffix = exponent < 0 ? "" : text.substring(exponent);
        String mantissa = exponent < 0 ? text : text.substring(0, exponent);
        if (mantissa.contains(".")) mantissa = mantissa.replaceAll("0+$", "").replaceAll("\\.$", "");
        return mantissa + suffix;
    }
}
