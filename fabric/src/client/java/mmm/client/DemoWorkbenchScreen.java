package mmm.client;

import java.util.ArrayList;
import java.util.List;
import mmm.network.DemoAction;
import mmm.network.DemoActionPayload;
import mmm.network.DemoPreset;
import mmm.network.DemoWorkbenchPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Selects an authoritative demo and sends actions using its current server token. */
final class DemoWorkbenchScreen extends Screen {
	private final DemoWorkbenchPayload state;
	private final List<Button> buttons = new ArrayList<>();
	private DemoPreset selected;
	private final Request request;
	private int failureOffset;

	DemoWorkbenchScreen(DemoWorkbenchPayload state) {
		this(state, state.preset());
		if (state.initializeDisplay() && state.regionId() != null && state.failure() == null) selectDisplay(state.preset());
	}
	private DemoWorkbenchScreen(DemoWorkbenchPayload state, DemoPreset selected) {
		super(Component.translatable("screen.mmm.demos.title"));
		this.state = state;
		this.selected = selected;
		this.request = new Request(state);
	}
	static final class Request {
		private final DemoWorkbenchPayload state;
		private DemoAction pending;
		Request(DemoWorkbenchPayload state) { this.state = state; }
		void begin(DemoAction action) { pending = action; }
		boolean awaiting(DemoWorkbenchPayload response, String dimension) {
			return pending != null && state.sessionId().equals(response.sessionId())
					&& state.generation() == response.requestGeneration() && state.dimensionId().equals(dimension)
					&& response.dimensionId().equals(dimension);
		}
	}

	@Override protected void init() {
		buttons.clear();
		int gap = 6, width = Math.min(96, Math.max(1, (this.width - 28) / 3));
		int left = (this.width - 3 * width - 2 * gap) / 2;
		choice(left, 32, width, "tank", tank(selected), () -> { if (!tank(selected)) select(DemoPreset.WATER); });
		choice(left + width + gap, 32, width, "flow", selected == DemoPreset.FLOW, () -> select(DemoPreset.FLOW));
		choice(left + 2 * (width + gap), 32, width, "air", air(selected), () -> { if (!air(selected)) select(DemoPreset.AMBIENT); });
		if (tank(selected)) {
			preset(left, 56, width, DemoPreset.WATER);
			preset(left + width + gap, 56, width, DemoPreset.MATERIAL);
			preset(left + 2 * (width + gap), 56, width, DemoPreset.CHEMISTRY);
		} else if (air(selected)) {
			preset(left, 56, width, DemoPreset.AMBIENT);
			preset(left + width + gap, 56, width, DemoPreset.PRESSURE);
			preset(left + 2 * (width + gap), 56, width, DemoPreset.VACUUM);
		}
		boolean current = state.regionId() != null && selected == state.preset();
		int actionY = height - 58;
		action(left, actionY, width, DemoAction.START, !current || state.paused() || state.blocked());
		action(left + width + gap, actionY, width, DemoAction.PAUSE, current && !state.paused());
		action(left + 2 * (width + gap), actionY, width, DemoAction.STEP, current && state.paused());
		action(left, height - 34, width, DemoAction.RESET, true);
		action(left + width + gap, height - 34, width, DemoAction.REMOVE, current);
		add(Button.builder(Component.translatable("screen.mmm.display.open"),
				button -> minecraft.setScreenAndShow(new FluidDisplayScreen(this)))
				.bounds(left + 2 * (width + gap), height - 34, width, 20).build(), true);
	}

	private static boolean tank(DemoPreset preset) {
		return preset == DemoPreset.WATER || preset == DemoPreset.MATERIAL || preset == DemoPreset.CHEMISTRY;
	}
	private static boolean air(DemoPreset preset) {
		return preset == DemoPreset.AMBIENT || preset == DemoPreset.PRESSURE || preset == DemoPreset.VACUUM;
	}
	private void select(DemoPreset preset) { selected = preset; rebuildWidgets(); }
	private void preset(int x, int y, int width, DemoPreset preset) {
		choice(x, y, width, preset.name().toLowerCase(java.util.Locale.ROOT), selected == preset, () -> select(preset));
	}
	private void choice(int x, int y, int width, String key, boolean selected, Runnable operation) {
		Component name = Component.translatable("screen.mmm.demos." + key);
		Component text = selected ? Component.literal("• ").append(name) : name;
		add(Button.builder(text, button -> operation.run()).bounds(x, y, width, 20).build(), true);
	}
	private void action(int x, int y, int width, DemoAction action, boolean enabled) {
		add(Button.builder(Component.translatable("screen.mmm.demos." + action.name().toLowerCase(java.util.Locale.ROOT)),
				button -> send(action)).bounds(x, y, width, 20).build(), enabled);
	}
	private void add(Button button, boolean enabled) {
		button.active = enabled && request.pending == null;
		buttons.add(button);
		addRenderableWidget(button);
	}
	private void send(DemoAction action) {
		begin(action);
		ClientPlayNetworking.send(new DemoActionPayload(state.sessionId(), state.generation(), state.regionId(), selected, action));
	}
	void begin(DemoAction action) {
		request.begin(action);
		buttons.forEach(button -> button.active = false);
	}
	boolean awaiting(DemoWorkbenchPayload response, String dimension) {
		return request.awaiting(response, dimension);
	}
	Screen accept(DemoWorkbenchPayload response) {
		if (response.failure() == null && (request.pending == DemoAction.START || request.pending == DemoAction.RESET)
				&& (state.regionId() == null || response.preset() != state.preset())) {
			selectDisplay(response.preset());
		}
		if (request.pending == DemoAction.START && response.failure() == null && !response.paused() && !response.blocked())
			return null;
		return new DemoWorkbenchScreen(response, response.failure() == null ? response.preset() : selected);
	}
	private static void selectDisplay(DemoPreset preset) {
		if (preset == DemoPreset.CHEMISTRY) FluidDisplaySettings.INSTANCE.setMode(FluidDisplaySettings.Mode.PH);
		else FluidDisplaySettings.INSTANCE.setMode(FluidDisplaySettings.Mode.COMPOSITION);
	}
	@Override public boolean isPauseScreen() { return false; }
	@Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
		if (state.failure() == null) return false;
		int count = font.split(Component.literal(state.failure()), Math.max(1, width - 32)).size();
		int visible = Math.max(1, (height - 158) / 11);
		failureOffset = Math.max(0, Math.min(Math.max(0, count - visible), failureOffset - (int) Math.signum(vertical)));
		return true;
	}
	@Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title, width / 2, 14, 0xffffffff);
		if (state.regionId() != null) {
			Component status = Component.translatable(state.paused() || state.blocked() ? "screen.mmm.demos.paused" : "screen.mmm.demos.running");
			if (state.preset() != selected) status = Component.translatable(
					"screen.mmm.demos." + state.preset().name().toLowerCase(java.util.Locale.ROOT))
					.append(Component.literal(" · ")).append(status);
			graphics.centeredText(font, status, width / 2, 80, 0xffa0a0a0);
		}
		if (state.failure() != null) {
			var lines = font.split(Component.literal(state.failure()), Math.max(1, width - 32));
			int visible = Math.max(1, (height - 158) / 11);
			failureOffset = Math.min(failureOffset, Math.max(0, lines.size() - visible));
			for (int i = failureOffset; i < Math.min(lines.size(), failureOffset + visible); i++)
				graphics.text(font, lines.get(i), 16, 92 + (i - failureOffset) * 11, 0xffff7777, false);
			if (failureOffset + visible < lines.size()) graphics.text(font, "↓", width - 18, height - 68, 0xffff7777, false);
		}
	}
}
