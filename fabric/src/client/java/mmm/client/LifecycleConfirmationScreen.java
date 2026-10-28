package mmm.client;

import mmm.units.UnitFormat;

import mmm.controller.LifecycleOperationPreview;
import mmm.network.LifecycleOperationPreviewPayload;
import mmm.network.LifecycleOperationRequestPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Displays only server-authored quantities and submits the opaque nonce unchanged. */
public final class LifecycleConfirmationScreen extends Screen {
	private final LifecycleOperationPreviewPayload payload;
	private List<Component> rows = List.of();
	private int scroll;
	private boolean requestPending;

	public LifecycleConfirmationScreen(LifecycleOperationPreviewPayload payload) {
		super(Component.translatable("screen.mmm.lifecycle.title"));
		this.payload = payload;
	}

	@Override protected void init() {
		rows = rows(payload.preview());
		int width = Math.min(112, Math.max(1, (this.width - 32) / 2));
		int gap = 8, left = (this.width - width * 2 - gap) / 2, y = height - 32;
		Button confirm = Button.builder(Component.translatable("screen.mmm.lifecycle.confirm"), button -> {
			requestPending = true;
			button.active = false;
			var preview = payload.preview();
			ClientPlayNetworking.send(LifecycleOperationRequestPayload.confirm(preview.regionId(),
					payload.controllerPosition(), payload.refreshedSummary().summaryGeneration(),
					preview.operation(), preview.nonce()));
		}).bounds(left, y, width, 20).build();
		confirm.active = payload.preview().ready() && !requestPending;
		addRenderableWidget(confirm);
		addRenderableWidget(Button.builder(Component.translatable("screen.mmm.lifecycle.cancel"), button -> {
			onClose();
		}).bounds(left + width + gap, y, width, 20).build());
	}

	@Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title, width / 2, 14, 0xffffff);
		List<FormattedCharSequence> lines = wrappedRows();
		int visible = visibleLines();
		scroll = Math.min(scroll, Math.max(0, lines.size() - visible));
		for (int i = scroll; i < Math.min(lines.size(), scroll + visible); i++) {
			graphics.text(font, lines.get(i), 16, 38 + (i - scroll) * 14, 0xd8d8d8, false);
		}
		if (scroll + visible < lines.size()) graphics.text(font, "↓", width - 18, height - 48, 0xa0a0a0, false);
	}

	private List<FormattedCharSequence> wrappedRows() {
		List<FormattedCharSequence> lines = new ArrayList<>();
		for (Component row : rows) lines.addAll(font.split(row, Math.max(1, width - 32)));
		return lines;
	}
	private int visibleLines() { return Math.max(1, (height - 86) / 14); }
	@Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
		int maximum = Math.max(0, wrappedRows().size() - visibleLines());
		scroll = Math.max(0, Math.min(maximum, scroll - (int) Math.signum(vertical) * 3));
		return true;
	}
	@Override public boolean isPauseScreen() { return false; }
	boolean awaiting(java.util.UUID regionId, long generation) {
		return requestPending && payload.refreshedSummary().regionId().equals(regionId)
				&& payload.refreshedSummary().summaryGeneration() == generation;
	}
	boolean inDimension(String dimension) { return payload.refreshedSummary().dimensionId().equals(dimension); }
	@Override public void onClose() {
		if (minecraft != null) minecraft.setScreenAndShow(requestPending ? null : new ControllerConsoleScreen(payload.refreshedSummary()));
	}

	private static List<Component> rows(LifecycleOperationPreview preview) {
		ArrayList<Component> rows = new ArrayList<>();
		rows.add(Component.translatable("screen.mmm.lifecycle.identity", localized("operation", preview.operation()),
				preview.regionId().toString().substring(0, 8), preview.regionGeneration()));
		LifecycleOperationPreview.Removal removal = preview.removal();
		rows.add(Component.translatable("screen.mmm.lifecycle.liquid", UnitFormat.volumeNanos(removal.liquidVolumeNanos()),
				decimal(removal.liquidMassKilograms()), UnitFormat.amount(removal.liquidSpeciesQuanta())));
		rows.add(Component.translatable("screen.mmm.lifecycle.solids", UnitFormat.solidVolume(removal.granularSolidVolumeSvq()),
				decimal(removal.granularMassKilograms()), UnitFormat.solidVolume(removal.coherentSolidVolumeSvq()),
				decimal(removal.coherentMassKilograms())));
		rows.add(Component.translatable("screen.mmm.lifecycle.gas_removal", UnitFormat.volumeNanos(removal.sealedGasStandardVolumeNanos()),
				decimal(removal.sealedGasMassKilograms()), UnitFormat.amount(removal.sealedGasSpeciesQuanta())));
		LifecycleOperationPreview.GasState gas = preview.gasState();
		rows.add(Component.translatable("screen.mmm.lifecycle.gas_state", UnitFormat.volumeNanos(gas.standardVolumeNanos()),
				decimal(gas.massKilograms()), UnitFormat.amount(gas.speciesQuanta()), gas.sealedNodes(),
				common(gas.ambientBoundaryPresent() ? "present" : "absent")));
		rows.add(Component.translatable("screen.mmm.lifecycle.energy_ownership",
				removal.internalEnergyAvailable() ? UnitFormat.energy(removal.internalEnergyJ()) : "—", preview.ownershipBlocks()));
		rows.add(Component.translatable("screen.mmm.lifecycle.permission",
				localized("permission", preview.permissionDecision())));
		rows.add(Component.translatable(preview.ready() ? "screen.mmm.lifecycle.ready" : "screen.mmm.lifecycle.blocked",
				preview.ready() ? "" : blockers(preview.blockers())));
		return List.copyOf(rows);
	}

	private static Component blockers(List<LifecycleOperationPreview.Blocker> blockers) {
		var result = Component.empty();
		for (int index = 0; index < blockers.size(); index++) {
			if (index > 0) result.append(Component.literal(" · "));
			result.append(localized("blocker", blockers.get(index)));
		}
		return result;
	}

	private static Component common(String value) {
		return Component.translatable("screen.mmm.common." + value);
	}

	private static Component localized(String family, Enum<?> value) {
		return Component.translatable("screen.mmm." + family + "." + value.name().toLowerCase(Locale.ROOT));
	}

	private static String decimal(double value) { return String.format(Locale.ROOT, "%.6g", value); }
}
