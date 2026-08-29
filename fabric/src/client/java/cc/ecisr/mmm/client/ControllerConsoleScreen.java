package cc.ecisr.mmm.client;

import cc.ecisr.mmm.controller.ControllerConsoleAction;
import cc.ecisr.mmm.controller.ControllerSummary;
import cc.ecisr.mmm.network.ControllerActionPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Presentation-only controller console. Every button submits the last server-issued opaque token. */
public final class ControllerConsoleScreen extends Screen {
	private final ControllerSummary summary;
	private final BlockPos controllerPosition;
	private List<Component> rows = List.of();

	public ControllerConsoleScreen(ControllerSummary summary) {
		super(Component.translatable("screen.mmm.controller.title"));
		this.summary = summary;
		ControllerSummary.Footprint footprint = summary.footprint();
		controllerPosition = new BlockPos(footprint.controllerX(), footprint.controllerY(), footprint.controllerZ());
	}

	@Override
	protected void init() {
		rows = rows(summary);
		int buttonWidth = 92;
		int gap = 6;
		int total = buttonWidth * 3 + gap * 2;
		int left = (width - total) / 2;
		int y = height - 34;
		addRenderableWidget(Button.builder(Component.translatable("screen.mmm.controller.select"),
				button -> send(ControllerConsoleAction.SELECT)).bounds(left, y, buttonWidth, 20).build());
		Component runAction = Component.translatable(summary.operatorIntent() == ControllerSummary.OperatorIntent.PAUSED
				? "screen.mmm.controller.resume" : "screen.mmm.controller.pause");
		Button pause = Button.builder(runAction, button -> send(
				summary.operatorIntent() == ControllerSummary.OperatorIntent.PAUSED
						? ControllerConsoleAction.RESUME : ControllerConsoleAction.PAUSE))
				.bounds(left + buttonWidth + gap, y, buttonWidth, 20).build();
		pause.active = minecraft != null && minecraft.player != null && minecraft.player.isCreative();
		addRenderableWidget(pause);
		addRenderableWidget(Button.builder(Component.translatable("screen.mmm.controller.refresh"),
				button -> send(ControllerConsoleAction.REFRESH_AVAILABILITY))
				.bounds(left + (buttonWidth + gap) * 2, y, buttonWidth, 20).build());
	}

	private void send(ControllerConsoleAction action) {
		ClientPlayNetworking.send(new ControllerActionPayload(summary.regionId(), controllerPosition,
				summary.summaryGeneration(), action));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title, width / 2, 14, 0xffffff);
		int y = 36;
		for (Component row : rows) {
			String clipped = font.plainSubstrByWidth(row.getString(), Math.max(80, width - 32));
			graphics.text(font, clipped, 16, y, 0xd8d8d8, false);
			y += 12;
			if (y >= height - 48) break;
		}
	}

	private static List<Component> rows(ControllerSummary value) {
		ArrayList<Component> result = new ArrayList<>();
		result.add(Component.translatable("screen.mmm.controller.identity",
				value.regionId().toString().substring(0, 8), value.dimensionId(),
				value.profileFingerprint().substring(0, 12)));
		result.add(Component.translatable("screen.mmm.controller.generations",
				value.checkpointGeneration(), value.networkGeneration()));
		result.add(Component.translatable("screen.mmm.controller.state",
				value.selected() ? "SELECTED" : "NOT_SELECTED", value.operatorIntent().name(),
				value.motionState().name(), value.thermalState().name(),
				value.availability().ready() ? "READY" : value.availability().reason().name()));
		ControllerSummary.Footprint footprint = value.footprint();
		result.add(Component.translatable("screen.mmm.controller.footprint", footprint.blockSizeX(),
				footprint.blockSizeY(), footprint.blockSizeZ(), footprint.interiorBlocks(), footprint.requiredChunks()));
		ControllerSummary.Usage usage = value.usage();
		result.add(Component.translatable("screen.mmm.controller.usage_primary",
				capacity(usage.cells()), capacity(usage.markers()), capacity(usage.chemicalParcels()),
				capacity(usage.activeParcels())));
		result.add(Component.translatable("screen.mmm.controller.usage_secondary",
				capacity(usage.depositGroups()), capacity(usage.coherentGroups()),
				capacity(usage.gasNodes()), capacity(usage.finiteDevices())));
		ControllerSummary.Inventory inventory = value.inventory();
		if (inventory.live()) {
			result.add(Component.translatable("screen.mmm.controller.inventory_liquid",
					decimal(inventory.liquidVolumeCubicMetres() * 1_000.0),
					decimal(inventory.liquidMassKilograms()), inventory.speciesQuanta()));
			result.add(Component.translatable("screen.mmm.controller.inventory_solid",
					inventory.granularSolidVolumeSvq(), inventory.coherentSolidVolumeSvq()));
			result.add(Component.translatable("screen.mmm.controller.inventory_gas_energy",
					inventory.gasStandardVolumeNanos(), decimal(inventory.gasMassKilograms()),
					inventory.internalEnergyAvailable() ? decimal(inventory.totalInternalEnergyEu()) : "—"));
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
				gates.worldBridgeEnabled() ? "ON" : "OFF", state(gates.checkpointAllowed())));
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

	private static String state(boolean value) { return value ? "READY" : "BLOCKED"; }
	private static String decimal(double value) { return String.format(Locale.ROOT, "%.6g", value); }
}
