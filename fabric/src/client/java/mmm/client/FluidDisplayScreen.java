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
		FluidDisplaySettings.View view = FluidDisplaySettings.INSTANCE.view();
		String minimumText = minimum == null ? Double.toString(view.minimumK()) : minimum.getValue();
		String maximumText = maximum == null ? Double.toString(view.maximumK()) : maximum.getValue();
		int column = Math.min(96, Math.max(1, (width - 36) / 3));
		int left = (width - (3 * column + 12)) / 2;
		for (FluidDisplaySettings.Mode mode : FluidDisplaySettings.Mode.values()) {
			addRenderableWidget(Button.builder(modeName(mode), button -> {
				FluidDisplaySettings.INSTANCE.setMode(mode);
			}).bounds(left + mode.ordinal() * (column + 6), 46, column, 20).build());
		}
		minimum = new EditBox(font, left, 104, column, 20, Component.translatable("screen.mmm.display.minimum"));
		maximum = new EditBox(font, left + column + 6, 104, column, 20, Component.translatable("screen.mmm.display.maximum"));
		minimum.setValue(minimumText);
		maximum.setValue(maximumText);
		minimum.setResponder(ignored -> updateApply());
		maximum.setResponder(ignored -> updateApply());
		addRenderableWidget(minimum);
		addRenderableWidget(maximum);
		apply = addRenderableWidget(Button.builder(Component.translatable("screen.mmm.display.apply"), button ->
				FluidDisplaySettings.INSTANCE.setTemperatureRange(number(minimum), number(maximum)))
				.bounds(left + 2 * (column + 6), 104, column, 20).build());
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
			int gradientWidth = Math.min(224, Math.max(1, width - 32));
			int left = (width - gradientWidth) / 2;
			for (int i = 0; i < gradientWidth; i++) {
				double fraction = gradientWidth == 1 ? 0 : i / (double) (gradientWidth - 1);
				int argb = view.mode() == FluidDisplaySettings.Mode.PH
						? FluidColorMapping.phColor(fraction * 14.0) : FluidColorMapping.temperatureColor(fraction);
				graphics.fill(left + i, 154, left + i + 1, 164, argb);
			}
			graphics.centeredText(font, Component.translatable("screen.mmm.display.no_data"), width / 2, 174, FluidColorMapping.NO_DATA);
		}
		int noteY = height - 58;
		for (var line : font.split(Component.translatable("screen.mmm.display.local"), Math.max(1, width - 32))) {
			graphics.text(font, line, 16, noteY, 0xa0a0a0, false);
			noteY += 11;
		}
	}
}
