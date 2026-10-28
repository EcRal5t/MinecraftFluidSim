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
		super(Component.translatable("screen.mmm.controller.title"));
		this.summary = summary;
		this.failure = failure;
		this.request = new Request(summary);
		ControllerSummary.Footprint footprint = summary.footprint();
		controllerPosition = new BlockPos(footprint.controllerX(), footprint.controllerY(), footprint.controllerZ());
	}

	@Override
	protected void init() {
		rows = rows(summary, failure);
		serverActions.clear();
		int buttonWidth = Math.min(92, Math.max(1, (width - 36) / 3));
		int gap = 6;
		int total = buttonWidth * 3 + gap * 2;
		int left = (width - total) / 2;
		int y = height - 30;
		addServerAction(Button.builder(Component.translatable("screen.mmm.controller.select"),
				button -> send(ControllerConsoleAction.SELECT)).bounds(left, y, buttonWidth, 20).build());
		Component runAction = Component.translatable(summary.operatorIntent() == ControllerSummary.OperatorIntent.PAUSED
				? "screen.mmm.controller.resume" : "screen.mmm.controller.pause");
		Button pause = Button.builder(runAction, button -> send(
				summary.operatorIntent() == ControllerSummary.OperatorIntent.PAUSED
						? ControllerConsoleAction.RESUME : ControllerConsoleAction.PAUSE))
				.bounds(left + buttonWidth + gap, y, buttonWidth, 20).build();
		addServerAction(pause);
		addServerAction(Button.builder(Component.translatable("screen.mmm.controller.refresh"),
				button -> send(ControllerConsoleAction.REFRESH_AVAILABILITY))
				.bounds(left + (buttonWidth + gap) * 2, y, buttonWidth, 20).build());

		int lifecycleWidth = Math.min(76, Math.max(1, (width - 24) / 4));
		int lifecycleTotal = lifecycleWidth * 4 + gap * 3;
		int lifecycleLeft = (width - lifecycleTotal) / 2;
		int lifecycleY = height - 54;
		int displayWidth = Math.min(200, Math.max(1, width - 24));
		addServerAction(Button.builder(Component.translatable("screen.mmm.display.open"),
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
		addServerAction(Button.builder(Component.translatable("screen.mmm.controller." + label),
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
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title, width / 2, 14, 0xffffffff);
		List<FormattedCharSequence> lines = wrappedRows();
		int visible = visibleLines();
		scroll = Math.min(scroll, Math.max(0, lines.size() - visible));
		for (int i = scroll; i < Math.min(lines.size(), scroll + visible); i++) {
			graphics.text(font, lines.get(i), 16, 36 + (i - scroll) * 12, 0xffd8d8d8, false);
		}
		if (scroll + visible < lines.size()) graphics.text(font, "↓", width - 18, height - 100, 0xffa0a0a0, false);
	}

	private List<FormattedCharSequence> wrappedRows() {
		List<FormattedCharSequence> lines = new ArrayList<>();
		for (Component row : rows) lines.addAll(font.split(row, Math.max(1, width - 32)));
		return lines;
	}
	private int visibleLines() { return Math.max(1, (height - 128) / 12); }
	@Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
		int maximum = Math.max(0, wrappedRows().size() - visibleLines());
		scroll = Math.max(0, Math.min(maximum, scroll - (int) Math.signum(vertical) * 3));
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
