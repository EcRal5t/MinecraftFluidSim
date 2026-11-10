package mmm.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Shared pixel-edged instrument surfaces for the client screens. */
final class IndustrialUi {
    private static final net.minecraft.resources.Identifier BRAND = mmm.MmmMod.id("textures/gui/brand.png");
    private static final net.minecraft.resources.Identifier WORDMARK = mmm.MmmMod.id("textures/gui/wordmark.png");
    private static final net.minecraft.resources.Identifier ICONS = mmm.MmmMod.id("textures/gui/industrial_atlas.png");
    enum Icon { TEMPERATURE, PRESSURE, FLOW, LIQUID, GAS, SOLID, SAMPLE, VALVE, PLAY, PAUSE, WARNING, ERROR, CHART, SETTINGS, POWER, LOG }
    static final int BACKGROUND = 0xff151c22, PANEL = 0xff222d35, INSET = 0xff182229;
    static final int EDGE = 0xff526570, TEXT = 0xffd8e2e3, MUTED = 0xff91a7ac;
    static final int CYAN = 0xff6fc4c9, COPPER = 0xffbb815a, AMBER = 0xffe2b359, FAULT = 0xffe17669;
    private IndustrialUi() { }
    static void icon(GuiGraphicsExtractor g, Icon icon, int x, int y, int size) {
        if (size <= 0) return;
        g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, ICONS, x, y,
                (icon.ordinal() % 4) * 32f, (icon.ordinal() / 4) * 32f, size, size, 32, 32, 128, 128);
    }
    static void brand(GuiGraphicsExtractor g, int x, int y, int size) {
        if (size <= 0) return;
        g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, BRAND, x, y, 0f, 0f, size, size, 128, 128, 128, 128);
    }
    static void wordmark(GuiGraphicsExtractor g, int x, int y, int height) {
        if (height <= 0) return;
        g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, WORDMARK, x, y, 0f, 0f,
                height * 3, height, 192, 64, 192, 64);
    }
    static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) return;
        g.fill(x, y, x+w, y+h, EDGE);
        if (w > 2 && h > 2) g.fill(x+1, y+1, x+w-1, y+h-1, PANEL);
        if (w > 6 && h > 4) g.fill(x+2, y+2, x+w-2, y+3, 0xff34454d);
        if (w > 20 && h > 16) {
            g.fill(x+3, y+3, x+5, y+5, COPPER);
            g.fill(x+w-5, y+3, x+w-3, y+5, COPPER);
            g.fill(x+3, y+h-5, x+5, y+h-3, EDGE);
            g.fill(x+w-5, y+h-5, x+w-3, y+h-3, EDGE);
        }
    }
    static void header(GuiGraphicsExtractor g, int x, int y, int w) {
        if (w <= 0) return;
        g.fill(x, y, x+w, y+20, INSET);
        g.fill(x, y+19, x+w, y+20, COPPER);
        g.fill(x, y+3, x+2, y+16, CYAN);
    }
    static void bar(GuiGraphicsExtractor g, int x, int y, int w, int h, double fraction, int color) {
        if (w <= 0 || h <= 0) return;
        g.fill(x, y, x+w, y+h, INSET);
        int inner = Math.max(0, w-2);
        int filled = (int)Math.round(inner * Math.max(0, Math.min(1, fraction)));
        if (h > 2 && filled > 0) g.fill(x+1, y+1, x+1+filled, y+h-1, color);
    }
    static void scrollbar(GuiGraphicsExtractor g, int x, int y, int height, int offset, int visible, int total) {
        if (height <= 0 || total <= visible) return;
        g.fill(x, y, x+3, y+height, INSET);
        int thumb = Math.min(height, Math.max(8, height * visible / Math.max(1, total)));
        int position = (height-thumb) * Math.clamp(offset, 0, total-visible) / (total-visible);
        g.fill(x, y+position, x+3, y+position+thumb, CYAN);
    }
    static ButtonBuilder button(net.minecraft.network.chat.Component label, net.minecraft.client.gui.components.Button.OnPress action) {
        return new ButtonBuilder(label, action);
    }
    static final class ButtonBuilder {
        private final net.minecraft.network.chat.Component label;
        private final net.minecraft.client.gui.components.Button.OnPress action;
        private int x, y, width, height;
        private boolean selected;
        ButtonBuilder(net.minecraft.network.chat.Component label, net.minecraft.client.gui.components.Button.OnPress action) {
            this.label = label; this.action = action;
        }
        ButtonBuilder bounds(int x, int y, int width, int height) {
            this.x = x; this.y = y; this.width = width; this.height = height; return this;
        }
        ButtonBuilder selected(boolean value) { selected = value; return this; }
        net.minecraft.client.gui.components.Button build() { return new InstrumentButton(x, y, width, height, label, action, selected); }
    }
    private static final class InstrumentButton extends net.minecraft.client.gui.components.Button {
        private final boolean selected;
        InstrumentButton(int x, int y, int width, int height, net.minecraft.network.chat.Component label, OnPress action, boolean selected) {
            super(x, y, width, height, label, action, DEFAULT_NARRATION);
            this.selected = selected;
        }
        @Override protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            int edge = selected ? CYAN : !active ? 0xff39474c : isHoveredOrFocused() ? CYAN : EDGE;
            int face = selected ? 0xff29454c : !active ? 0xff202a2e : isHoveredOrFocused() ? 0xff36525a : 0xff2b3b42;
            g.fill(x, y, x + w, y + h, edge);
            if (w > 2 && h > 2) g.fill(x + 1, y + 1, x + w - 1, y + h - 1, face);
            if (w > 6 && h > 4) g.fill(x + 3, y + h - 3, x + w - 3, y + h - 2, selected ? CYAN : active ? COPPER : 0xff465054);
            extractDefaultLabel(g.textRenderer());
        }
    }
}
