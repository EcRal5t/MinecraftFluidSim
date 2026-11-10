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
		int buttonWidth = Math.min(92, Math.max(1, (width - 36) / 3));
		int gap = 6;
		int total = buttonWidth * 3 + gap * 2;
		int left = (width - total) / 2;
		int y = height - 30;
		addServerAction(IndustrialUi.button(Component.translatable("screen.mmm.controller.select"),
				button -> send(ControllerConsoleAction.SELECT)).bounds(left, y, buttonWidth, 20).build());
		Component runAction = Component.translatable(summary.operatorIntent() == ControllerSummary.OperatorIntent.PAUSED
				? "screen.mmm.controller.resume" : "screen.mmm.controller.pause");
		Button pause = IndustrialUi.button(runAction, button -> send(
				summary.operatorIntent() == ControllerSummary.OperatorIntent.PAUSED
						? ControllerConsoleAction.RESUME : ControllerConsoleAction.PAUSE))
				.bounds(left + buttonWidth + gap, y, buttonWidth, 20).build();
		addServerAction(pause);
		addServerAction(IndustrialUi.button(Component.translatable("screen.mmm.controller.refresh"),
				button -> send(ControllerConsoleAction.REFRESH_AVAILABILITY))
				.bounds(left + (buttonWidth + gap) * 2, y, buttonWidth, 20).build());

		int lifecycleWidth = Math.min(76, Math.max(1, (width - 32 - 3 * gap) / 4));
		int lifecycleTotal = lifecycleWidth * 4 + gap * 3;
		int lifecycleLeft = (width - lifecycleTotal) / 2;
		int lifecycleY = height - 54;
		int displayWidth = Math.min(200, Math.max(1, width - 24));
		addServerAction(IndustrialUi.button(Component.translatable("screen.mmm.display.open"),
				button -> minecraft.setScreenAndShow(new FluidDisplayScreen(this)))
				.bounds((width - displayWidth) / 2, height - 78, displayWidth, 20).build());
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

    private record DashboardCard(IndustrialUi.Icon icon, Component label, String value, String secondary) { }

    private List<DashboardCard> dashboardCards() {
        var inventory = summary.inventory();
        if (!inventory.live()) return List.of(new DashboardCard(IndustrialUi.Icon.WARNING,
                Component.translatable("screen.mmm.controller.inventory"),
                Component.translatable("screen.mmm.controller.inventory_unloaded").getString(), ""));
        var range = inventory.temperatureRange();
        return List.of(
                new DashboardCard(IndustrialUi.Icon.LIQUID, Component.translatable("screen.mmm.controller.liquid"),
                        UnitFormat.volume(inventory.liquidVolumeCubicMetres()), decimal(inventory.liquidMassKilograms()) + " kg"),
                new DashboardCard(IndustrialUi.Icon.GAS, Component.translatable("screen.mmm.controller.gas"),
                        UnitFormat.volumeNanos(inventory.gasStandardVolumeNanos()), decimal(inventory.gasMassKilograms()) + " kg"),
                new DashboardCard(IndustrialUi.Icon.SOLID, Component.translatable("screen.mmm.controller.granular"),
                        UnitFormat.solidVolume(inventory.granularSolidVolumeSvq()), decimal(inventory.granularMassKilograms()) + " kg"),
                new DashboardCard(IndustrialUi.Icon.SOLID, Component.translatable("screen.mmm.controller.coherent"),
                        UnitFormat.solidVolume(inventory.coherentSolidVolumeSvq()), decimal(inventory.coherentMassKilograms()) + " kg"),
                new DashboardCard(IndustrialUi.Icon.TEMPERATURE, Component.translatable("screen.mmm.controller.temperature"),
                        range.available() ? decimal(range.minimumK()) + " – " + decimal(range.maximumK()) + " K" : "—", ""),
                new DashboardCard(IndustrialUi.Icon.POWER, Component.translatable("screen.mmm.controller.energy"),
                        inventory.internalEnergyAvailable() ? UnitFormat.energy(inventory.totalInternalEnergyJ()) : "—", ""));
    }
    private int dashboardColumns() { return width >= 360 && height >= 230 ? 2 : 1; }
    private int dashboardCardHeight() { return height >= 230 ? 46 : 36; }
    private int dashboardVisibleRows() { return Math.max(1, (height - 142) / dashboardCardHeight()); }
    private int dashboardTotalRows() { return 1 + (dashboardCards().size() + dashboardColumns() - 1) / dashboardColumns(); }
    private void renderDashboard(GuiGraphicsExtractor graphics) {
        var cards = dashboardCards();
        int columns = dashboardColumns(), cardHeight = dashboardCardHeight();
        int visible = dashboardVisibleRows(), total = dashboardTotalRows();
        scroll = Math.min(scroll, Math.max(0, total - visible));
        int left = 15, gap = 5, contentWidth = Math.max(1, width - 37);
        int cardWidth = Math.max(1, (contentWidth - gap * (columns - 1)) / columns);
        for (int row = scroll; row < Math.min(total, scroll + visible); row++) {
            int y = 58 + (row - scroll) * cardHeight;
            if (row == 0) {
                IndustrialUi.panel(graphics, left, y, contentWidth, cardHeight - 3);
                IndustrialUi.icon(graphics, !summary.availability().ready() || failure != null ? IndustrialUi.Icon.WARNING
                        : summary.operatorIntent() == ControllerSummary.OperatorIntent.PAUSED ? IndustrialUi.Icon.PAUSE : IndustrialUi.Icon.PLAY,
                        left + 5, y + 6, 16);
                Component status = failure != null ? Component.literal(failure) : Component.empty()
                        .append(localized("operator", summary.operatorIntent())).append(" · ")
                        .append(summary.availability().ready() ? common("ready") : localized("availability", summary.availability().reason()));
                var statusLines = font.split(status, Math.max(1, contentWidth - 28));
                for (int i = 0; i < Math.min(statusLines.size(), (cardHeight - 8) / 11); i++)
                    graphics.text(font, statusLines.get(i), left + 25, y + 5 + i * 11,
                            !summary.availability().ready() || failure != null ? IndustrialUi.AMBER : IndustrialUi.TEXT, false);
            } else {
                for (int col = 0; col < columns; col++) {
                    int index = (row - 1) * columns + col;
                    if (index >= cards.size()) break;
                    DashboardCard card = cards.get(index);
                    int x = left + col * (cardWidth + gap);
                    IndustrialUi.panel(graphics, x, y, cardWidth, cardHeight - 3);
                    IndustrialUi.icon(graphics, card.icon(), x + 5, y + 5, 16);
                    graphics.enableScissor(x + 24, y + 3, x + cardWidth - 3, y + cardHeight - 4);
                    graphics.text(font, card.label(), x + 25, y + 5, IndustrialUi.MUTED, false);
                    var valueLines = font.split(Component.literal(card.value()), Math.max(1, cardWidth - 30));
                    for (int i = 0; i < Math.min(valueLines.size(), cardHeight >= 46 ? 2 : 1); i++)
                        graphics.text(font, valueLines.get(i), x + 25, y + 17 + i * 11, IndustrialUi.TEXT, false);
                    if (cardHeight >= 46 && valueLines.size() <= 1 && !card.secondary().isEmpty())
                        graphics.text(font, card.secondary(), x + 25, y + 29, IndustrialUi.CYAN, false);
                    graphics.disableScissor();
                }
            }
        }
        IndustrialUi.scrollbar(graphics, width - 17, 58, visible * cardHeight - 3, scroll, visible, total);
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
	private static String decimal(double value) { return String.format(Locale.ROOT, "%.6g", value); }
}
