package mmm.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import java.util.ArrayList;
import java.util.List;

/** Fixed localized guide; it owns no world, region, or inventory state. */
final class QuickStartScreen extends Screen {
	private static final int STEP_COUNT = 8;
	private static final int STEPS_PER_PAGE = 4;
	private int page;
	private int scroll;

	QuickStartScreen() { super(Component.translatable("screen.mmm.quick_start.title")); }

	@Override
	protected void init() {
		int buttonWidth = 88, gap = 6, left = (width - buttonWidth * 3 - gap * 2) / 2;
		Button previous = Button.builder(Component.translatable("screen.mmm.quick_start.previous"), button -> {
			page--;
			scroll = 0;
			rebuildWidgets();
		}).bounds(left, height - 30, buttonWidth, 20).build();
		previous.active = page > 0;
		addRenderableWidget(previous);
		addRenderableWidget(Button.builder(Component.translatable("screen.mmm.quick_start.close"), button -> {
			if (minecraft != null) minecraft.setScreenAndShow(null);
		}).bounds(left + buttonWidth + gap, height - 30, buttonWidth, 20).build());
		Button next = Button.builder(Component.translatable("screen.mmm.quick_start.next"), button -> {
			page++;
			scroll = 0;
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
		List<FormattedCharSequence> lines = lines();
		int visible = visibleLines();
		scroll = Math.min(scroll, Math.max(0, lines.size() - visible));
		for (int i = scroll; i < Math.min(lines.size(), scroll + visible); i++)
			graphics.text(font, lines.get(i), 16, 44 + (i - scroll) * 11, 0xd8d8d8, false);
		if (scroll + visible < lines.size()) graphics.text(font, "↓", width - 18, height - 44, 0xa0a0a0, false);
	}

	private List<FormattedCharSequence> lines() {
		List<FormattedCharSequence> lines = new ArrayList<>();
		int first = page * STEPS_PER_PAGE + 1;
		for (int step = first; step < first + STEPS_PER_PAGE; step++) {
			lines.addAll(font.split(Component.translatable("screen.mmm.quick_start.step." + step), Math.max(1, width - 32)));
			lines.add(FormattedCharSequence.EMPTY);
		}
		return lines;
	}
	private int visibleLines() { return Math.max(1, (height - 84) / 11); }
	@Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
		int maximum = Math.max(0, lines().size() - visibleLines());
		scroll = Math.max(0, Math.min(maximum, scroll - (int) Math.signum(vertical) * 3));
		return true;
	}
	@Override public boolean isPauseScreen() { return false; }
}
