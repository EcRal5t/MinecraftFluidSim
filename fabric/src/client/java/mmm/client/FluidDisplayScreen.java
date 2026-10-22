package mmm.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Local display controls; changes immediately recolour the accepted snapshot. */
final class FluidDisplayScreen extends Screen {
	private final Screen parent;
	private EditBox minimum;
	private EditBox maximum;
	private Button apply;

	FluidDisplayScreen(Screen parent) {
		super(Component.translatable("screen.mmm.display.title"));
		this.parent = parent;
	}

	@Override protected void init() {
		int left = width / 2 - 150;
		FluidDisplaySettings.View view = FluidDisplaySettings.INSTANCE.view();
		for (FluidDisplaySettings.Mode mode : FluidDisplaySettings.Mode.values()) {
			addRenderableWidget(Button.builder(modeName(mode), button -> {
				FluidDisplaySettings.INSTANCE.setMode(mode);
			}).bounds(left + mode.ordinal() * 102, 46, 96, 20).build());
		}
		minimum = new EditBox(font, width / 2 - 150, 104, 96, 20, Component.translatable("screen.mmm.display.minimum"));
		maximum = new EditBox(font, width / 2 - 48, 104, 96, 20, Component.translatable("screen.mmm.display.maximum"));
		minimum.setValue(Double.toString(view.minimumK()));
		maximum.setValue(Double.toString(view.maximumK()));
		minimum.setResponder(ignored -> updateApply());
		maximum.setResponder(ignored -> updateApply());
		addRenderableWidget(minimum);
		addRenderableWidget(maximum);
		apply = addRenderableWidget(Button.builder(Component.translatable("screen.mmm.display.apply"), button ->
				FluidDisplaySettings.INSTANCE.setTemperatureRange(number(minimum), number(maximum)))
				.bounds(width / 2 + 54, 104, 96, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
				.bounds(width / 2 - 50, height - 30, 100, 20).build());
		updateApply();
	}

	private void updateApply() {
		if (apply != null) apply.active = FluidDisplaySettings.validRange(number(minimum), number(maximum));
	}
	private static double number(EditBox field) {
		try { return Double.parseDouble(field.getValue()); }
		catch (NumberFormatException invalid) { return Double.NaN; }
	}
	static Component modeName(FluidDisplaySettings.Mode mode) {
		return Component.translatable("screen.mmm.display.mode." + mode.name().toLowerCase(Locale.ROOT));
	}
	@Override public void onClose() { minecraft.setScreenAndShow(parent); }
	@Override public boolean isPauseScreen() { return false; }

	@Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		FluidDisplaySettings.View view = FluidDisplaySettings.INSTANCE.view();
		graphics.centeredText(font, title, width / 2, 12, 0xffffff);
		graphics.centeredText(font, Component.translatable("screen.mmm.display.current", modeName(view.mode())), width / 2, 30, 0xffffff);
		graphics.centeredText(font, Component.translatable("screen.mmm.display.range"), width / 2, 90, 0xd8d8d8);
		Component legend = switch (view.mode()) {
			case COMPOSITION -> Component.translatable("screen.mmm.display.legend.composition");
			case PH -> Component.translatable("screen.mmm.display.legend.ph");
			case TEMPERATURE -> Component.translatable("screen.mmm.display.legend.temperature", view.minimumK(), view.maximumK());
		};
		graphics.centeredText(font, legend, width / 2, 138, 0xd8d8d8);
		if (view.mode() != FluidDisplaySettings.Mode.COMPOSITION) {
			int left = width / 2 - 112;
			for (int i = 0; i < 224; i++) {
				int argb = view.mode() == FluidDisplaySettings.Mode.PH
						? FluidColorMapping.phColor(i * 14.0 / 223.0) : FluidColorMapping.temperatureColor(i / 223.0);
				graphics.fill(left + i, 154, left + i + 1, 164, argb);
			}
			graphics.centeredText(font, Component.translatable("screen.mmm.display.no_data"), width / 2, 174, FluidColorMapping.NO_DATA);
		}
		int noteY = height - 58;
		for (var line : font.split(Component.translatable("screen.mmm.display.local"), width - 32)) {
			graphics.text(font, line, 16, noteY, 0xa0a0a0, false);
			noteY += 11;
		}
	}
}
