package cc.ecisr.mmm.client;

import cc.ecisr.mmm.network.ThermalReservoirSetpointPayload;
import cc.ecisr.mmm.thermal.ThermalReservoirSummary;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Presentation/input adapter; the server remains the sole validator and BlockEntity writer. */
public final class ThermalReservoirScreen extends Screen {
	private final ThermalReservoirSummary summary;
	private EditBox temperature;
	private Button apply;

	public ThermalReservoirScreen(ThermalReservoirSummary summary) {
		super(Component.translatable("screen.mmm.thermal_reservoir.title"));
		this.summary = summary;
	}

	@Override
	protected void init() {
		int center = width / 2;
		temperature = new EditBox(font, center - 80, 86, 160, 20,
				Component.translatable("screen.mmm.thermal_reservoir.temperature"));
		temperature.setMaxLength(32);
		temperature.setValue(summary.temperatureValid() ? Double.toString(summary.temperatureK()) : "");
		temperature.setHint(Component.translatable("screen.mmm.thermal_reservoir.temperature"));
		temperature.setResponder(ignored -> updateApplyState());
		addRenderableWidget(temperature);
		apply = Button.builder(Component.translatable("screen.mmm.thermal_reservoir.apply"), button -> submit())
				.bounds(center - 80, 116, 160, 20).build();
		addRenderableWidget(apply);
		updateApplyState();
		setInitialFocus(temperature);
	}

	private void updateApplyState() {
		if (apply == null) return;
		double candidate = parsedTemperature();
		apply.active = minecraft.player != null && minecraft.player.isCreative()
				&& Double.isFinite(candidate) && candidate >= summary.minimumTemperatureK()
				&& candidate <= summary.maximumTemperatureK();
	}

	private void submit() {
		double candidate = parsedTemperature();
		if (!Double.isFinite(candidate)) return;
		ClientPlayNetworking.send(new ThermalReservoirSetpointPayload(
				new BlockPos(summary.blockX(), summary.blockY(), summary.blockZ()),
				summary.summaryGeneration(), candidate));
	}

	private double parsedTemperature() {
		try { return Double.parseDouble(temperature.getValue()); }
		catch (NumberFormatException invalid) { return Double.NaN; }
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title, width / 2, 20, 0xffffff);
		graphics.centeredText(font, Component.translatable("screen.mmm.thermal_reservoir.position",
				summary.blockX(), summary.blockY(), summary.blockZ()), width / 2, 42, 0xd8d8d8);
		graphics.centeredText(font, Component.translatable("screen.mmm.thermal_reservoir.current",
				summary.temperatureValid() ? decimal(summary.temperatureK()) : "INVALID"), width / 2, 56, 0xd8d8d8);
		graphics.centeredText(font, Component.translatable("screen.mmm.thermal_reservoir.range",
				decimal(summary.minimumTemperatureK()), decimal(summary.maximumTemperatureK())),
				width / 2, 70, 0xa0a0a0);
		graphics.centeredText(font, Component.translatable("screen.mmm.thermal_reservoir.note"),
				width / 2, 148, 0x909090);
	}

	private static String decimal(double value) { return String.format(Locale.ROOT, "%.6f", value); }
}
