package cc.ecisr.mmm.client;

import cc.ecisr.mmm.controller.LifecycleOperationPreview;
import cc.ecisr.mmm.network.LifecycleOperationPreviewPayload;
import cc.ecisr.mmm.network.LifecycleOperationRequestPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Displays only server-authored quantities and submits the opaque nonce unchanged. */
public final class LifecycleConfirmationScreen extends Screen {
	private final LifecycleOperationPreviewPayload payload;
	private List<Component> rows = List.of();

	public LifecycleConfirmationScreen(LifecycleOperationPreviewPayload payload) {
		super(Component.translatable("screen.mmm.lifecycle.title"));
		this.payload = payload;
	}

	@Override protected void init() {
		rows = rows(payload.preview());
		int width = 112, gap = 8, left = (this.width - width * 2 - gap) / 2, y = height - 32;
		Button confirm = Button.builder(Component.translatable("screen.mmm.lifecycle.confirm"), button -> {
			button.active = false;
			var preview = payload.preview();
			ClientPlayNetworking.send(LifecycleOperationRequestPayload.confirm(preview.regionId(),
					payload.controllerPosition(), payload.refreshedSummary().summaryGeneration(),
					preview.operation(), preview.nonce()));
		}).bounds(left, y, width, 20).build();
		confirm.active = payload.preview().ready();
		addRenderableWidget(confirm);
		addRenderableWidget(Button.builder(Component.translatable("screen.mmm.lifecycle.cancel"), button -> {
			if (minecraft != null) minecraft.setScreenAndShow(new ControllerConsoleScreen(payload.refreshedSummary()));
		}).bounds(left + width + gap, y, width, 20).build());
	}

	@Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title, width / 2, 14, 0xffffff);
		int y = 38;
		for (Component row : rows) {
			String clipped = font.plainSubstrByWidth(row.getString(), Math.max(80, width - 32));
			graphics.text(font, clipped, 16, y, 0xd8d8d8, false);
			y += 14;
			if (y >= height - 48) break;
		}
	}

	private static List<Component> rows(LifecycleOperationPreview preview) {
		ArrayList<Component> rows = new ArrayList<>();
		rows.add(Component.translatable("screen.mmm.lifecycle.identity", localized("operation", preview.operation()),
				preview.regionId().toString().substring(0, 8), preview.regionGeneration()));
		LifecycleOperationPreview.Removal removal = preview.removal();
		rows.add(Component.translatable("screen.mmm.lifecycle.liquid", removal.liquidVolumeNanos(),
				decimal(removal.liquidMassKilograms()), removal.liquidSpeciesQuanta()));
		rows.add(Component.translatable("screen.mmm.lifecycle.solids", removal.granularSolidVolumeSvq(),
				decimal(removal.granularMassKilograms()), removal.coherentSolidVolumeSvq(),
				decimal(removal.coherentMassKilograms())));
		rows.add(Component.translatable("screen.mmm.lifecycle.gas_removal", removal.sealedGasStandardVolumeNanos(),
				decimal(removal.sealedGasMassKilograms()), removal.sealedGasSpeciesQuanta()));
		LifecycleOperationPreview.GasState gas = preview.gasState();
		rows.add(Component.translatable("screen.mmm.lifecycle.gas_state", gas.standardVolumeNanos(),
				decimal(gas.massKilograms()), gas.speciesQuanta(), gas.sealedNodes(),
				common(gas.ambientBoundaryPresent() ? "present" : "absent")));
		rows.add(Component.translatable("screen.mmm.lifecycle.energy_ownership",
				removal.internalEnergyAvailable() ? decimal(removal.internalEnergyEu()) : "—", preview.ownershipBlocks()));
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
