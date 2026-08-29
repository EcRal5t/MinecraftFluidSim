package cc.ecisr.mmm.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Fixed localized guide; it owns no world, region, or inventory state. */
final class QuickStartScreen extends Screen {
	private static final int STEP_COUNT = 8;
	private static final int STEPS_PER_PAGE = 4;
	private int page;

	QuickStartScreen() { super(Component.translatable("screen.mmm.quick_start.title")); }

	@Override
	protected void init() {
		int buttonWidth = 88, gap = 6, left = (width - buttonWidth * 3 - gap * 2) / 2;
		Button previous = Button.builder(Component.translatable("screen.mmm.quick_start.previous"), button -> {
			page--;
			rebuildWidgets();
		}).bounds(left, height - 30, buttonWidth, 20).build();
		previous.active = page > 0;
		addRenderableWidget(previous);
		addRenderableWidget(Button.builder(Component.translatable("screen.mmm.quick_start.close"), button -> {
			if (minecraft != null) minecraft.setScreenAndShow(null);
		}).bounds(left + buttonWidth + gap, height - 30, buttonWidth, 20).build());
		Button next = Button.builder(Component.translatable("screen.mmm.quick_start.next"), button -> {
			page++;
			rebuildWidgets();
		}).bounds(left + (buttonWidth + gap) * 2, height - 30, buttonWidth, 20).build();
		next.active = page < STEP_COUNT / STEPS_PER_PAGE - 1;
		addRenderableWidget(next);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title, width / 2, 14, 0xffffff);
		graphics.centeredText(font, Component.translatable("screen.mmm.quick_start.page", page + 1,
				STEP_COUNT / STEPS_PER_PAGE), width / 2, 27, 0x9a9a9a);
		int y = 44;
		int first = page * STEPS_PER_PAGE + 1;
		for (int step = first; step < first + STEPS_PER_PAGE; step++) {
			Component line = Component.translatable("screen.mmm.quick_start.step." + step);
			for (var wrapped : font.split(line, Math.max(80, width - 32))) {
				graphics.text(font, wrapped, 16, y, 0xd8d8d8, false);
				y += 11;
			}
			y += 7;
		}
	}
}
