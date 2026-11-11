package mmm.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import mmm.MmmMod;
import mmm.network.DebugDiagnosticPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Noninteractive, session-local view of MMM diagnostics. */
final class MmmDebugPanel {
    private static final DebugDiagnostics DATA = new DebugDiagnostics();
    private static boolean visible = true, expanded;
    private static String dimension;
    private static Path settings;
    private static int ticks;

    private record Line(String label, String value, int color) { }
    private record Layout(List<Line> left, List<Line> right, String notice, int noticeColor) { }
    private static Layout cachedLayout;
    private static int cachedWidth, cachedHeight;
    private static boolean cachedExpanded;
    private static long cacheTime;
    private static String fps = "FPS --", frame = "Frame --", heap = "Heap --";

    static void register() {
        settings = FabricLoader.getInstance().getConfigDir().resolve("mmm-debug-display.properties");
        load();
        var toggle = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.mmm.debug.toggle", GLFW.GLFW_KEY_F8, KeyMapping.Category.DEBUG));
        var detail = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.mmm.debug.expand", GLFW.GLFW_KEY_F9, KeyMapping.Category.DEBUG));
        ClientPlayNetworking.registerGlobalReceiver(DebugDiagnosticPayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    var client = context.client();
                    if (client.level != null && payload.dimension().equals(client.level.dimension().identifier().toString())) {
                        observeDimension(client);
                        DATA.accept(payload);
                    }
                }));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            observeDimension(client);
            if (++ticks >= 10) {
                ticks = 0;
                Runtime runtime = Runtime.getRuntime();
                fps = "FPS " + client.getFps();
                frame = String.format(java.util.Locale.ROOT, "Frame %.2f ms", client.getFrameTimeNs() / 1_000_000.0);
                heap = String.format(java.util.Locale.ROOT, "Heap %d / %d MiB",
                        (runtime.totalMemory() - runtime.freeMemory()) / 1048576, runtime.maxMemory() / 1048576);
            }
            while (toggle.consumeClick()) if (client.gui.screen() == null) { visible = !visible; save(); }
            while (detail.consumeClick()) if (client.gui.screen() == null) { expanded = !expanded; save(); }
        });
        ClientConfigurationConnectionEvents.START.register((handler, client) -> clear());
        ClientConfigurationConnectionEvents.DISCONNECT.register((handler, client) -> clear());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, MmmMod.id("debug_panel"), (g, tick) -> render(g));
    }
    private static void clear() { DATA.clear(); dimension = null; cachedLayout = null; }
    private static void observeDimension(Minecraft client) {
        String next = client.level == null ? null : client.level.dimension().identifier().toString();
        if (!java.util.Objects.equals(dimension, next)) { DATA.clear(); dimension = next; cachedLayout = null; }
    }
    private static final float SCALE = 0.8f;
    private static void render(GuiGraphicsExtractor g) {
        Minecraft client = Minecraft.getInstance();
        if (!visible || client.level == null || client.gui.screen() != null) return;
        int w = expanded ? 430 : 220;
        long now = System.nanoTime();
        if (cachedLayout == null || cachedWidth != w || cachedHeight != g.guiHeight()
                || cachedExpanded != expanded || now - cacheTime >= 500_000_000L) {
            cachedLayout = buildLayout();
            cachedWidth = w; cachedHeight = g.guiHeight(); cachedExpanded = expanded; cacheTime = now;
        }
        Layout layout = cachedLayout;
        int x = (int) (g.guiWidth() / SCALE) - w - 7, y = 7;
        int count = expanded ? Math.max(layout.left().size(), layout.right().size()) : layout.left().size();
        int h = 27 + count * 11 + (layout.notice() == null ? 0 : 13);
        g.pose().pushMatrix();
        g.pose().scale(SCALE, SCALE);
        IndustrialUi.panel(g, x, y, w, h);
        IndustrialUi.header(g, x, y, w);
        g.text(client.font, Component.translatable("hud.mmm.debug.title"), x + 7, y + 6, IndustrialUi.CYAN, false);
        String mode = tr(expanded ? "expanded" : "compact");
        g.text(client.font, mode, x + w - 7 - client.font.width(mode), y + 6, IndustrialUi.MUTED, false);
        drawRows(g, client, layout.left(), x + 7, y + 21, expanded ? 192 : w - 14);
        if (expanded) drawRows(g, client, layout.right(), x + 220, y + 21, w - 227);
        if (layout.notice() != null) {
            String notice = client.font.plainSubstrByWidth(layout.notice(), w - 14);
            g.text(client.font, notice, x + 7, y + 22 + count * 11, layout.noticeColor(), false);
        }
        g.pose().popMatrix();
    }
    private static void drawRows(GuiGraphicsExtractor g, Minecraft client, List<Line> rows, int x, int y, int width) {
        for (var row : rows) {
            int valueWidth = client.font.width(row.value());
            String label = client.font.plainSubstrByWidth(row.label(), Math.max(0, width - valueWidth - 8));
            g.text(client.font, label, x, y, row.value().isEmpty() ? row.color() : IndustrialUi.MUTED, false);
            g.text(client.font, row.value(), x + Math.max(0, width - valueWidth), y, row.color(), false);
            y += 11;
        }
    }
    private static String tr(String key) { return Component.translatable("hud.mmm.debug." + key).getString(); }
    private static Layout buildLayout() {
        List<Line> left = new ArrayList<>(), right = new ArrayList<>();
        left.add(new Line(tr("system"), "", IndustrialUi.CYAN));
        left.add(new Line(tr("fps"), fps.substring(4), IndustrialUi.TEXT));
        left.add(new Line(tr("frame"), frame.substring(6), IndustrialUi.TEXT));
        left.add(new Line(tr("heap"), heap.substring(5), IndustrialUi.TEXT));
        var snapshots = DATA.snapshots();
        if (expanded) {
            left.add(new Line(tr("region"), "", IndustrialUi.CYAN));
            right.add(new Line(tr("solver"), "", IndustrialUi.CYAN));
            var row = snapshots.isEmpty() ? null : snapshots.getLast();
            var s = row == null ? null : row.snapshot();
            if (s == null || !s.selected()) left.add(new Line(tr("selection"), tr("none"), IndustrialUi.MUTED));
            else {
                int color = row.stale(System.nanoTime()) ? IndustrialUi.MUTED : IndustrialUi.TEXT;
                left.add(new Line(tr("id"), s.region().substring(0, Math.min(8, s.region().length())), color));
                right.add(new Line(tr("operator"), Component.translatable("screen.mmm.operator." + (s.paused() ? "paused" : "running")).getString(), color));
                right.add(new Line(tr("state"), s.state() + (row.stale(System.nanoTime()) ? " (" + tr("stale") + ")" : ""), color));
                left.add(new Line(tr("grid"), s.sizeX() + " x " + s.sizeY() + " x " + s.sizeZ(), color));
                left.add(new Line(tr("tick"), Long.toString(s.tick()), color));
                right.add(new Line(tr("step"), String.format(java.util.Locale.ROOT, "%.2f ms", s.stepMillis()), color));
                right.add(new Line(tr("interval"), Integer.toString(s.interval()), color));
                if (s.hasStats()) {
                    right.add(new Line(tr("substeps"), Integer.toString(s.substeps()), color));
                    right.add(new Line(tr("iterations"), Integer.toString(s.pressureIterations()), color));
                    right.add(new Line(tr("residual"), String.format(java.util.Locale.ROOT, "%.4g", s.residual()), color));
                    right.add(new Line(tr("volume_error"), String.format(java.util.Locale.ROOT, "%.4g", s.volumeError()), color));
                    right.add(new Line(tr("mass_error"), String.format(java.util.Locale.ROOT, "%.4g", s.massError()), color));
                }
            }
        }
        String notice = null;
        int noticeColor = IndustrialUi.FAULT;
        for (var event : DATA.events()) if (event.level() == DebugDiagnosticPayload.Level.ERROR && event.source().equals("simulation")
                && System.nanoTime() - event.receivedNanos() < 15_000_000_000L) {
            String id = event.id().startsWith("region:") ? event.id().substring(7) : event.id();
            notice = tr("failure") + ": " + id.substring(0, Math.min(8, id.length()));
        }
        if (notice == null) for (var event : DATA.events())
            if (event.source().equals("command") && System.nanoTime() - event.receivedNanos() < 5_000_000_000L) {
                boolean failed = event.id().equals("failed");
                notice = tr(failed ? "command_failed" : "command_complete") + (event.text().isEmpty() ? "" : ": " + event.text());
                noticeColor = failed ? IndustrialUi.AMBER : IndustrialUi.CYAN;
            }
        return new Layout(List.copyOf(left), List.copyOf(right), notice, noticeColor);
    }

    private static void load() {
        if (!Files.exists(settings)) return;
        Properties values = new Properties();
        try (var input = Files.newInputStream(settings)) {
            values.load(input);
            visible = Boolean.parseBoolean(values.getProperty("visible", "true"));
            expanded = Boolean.parseBoolean(values.getProperty("expanded", "false"));
        } catch (IOException failure) { MmmMod.LOGGER.warn("Cannot load debug display preferences", failure); }
    }
    private static void save() {
        Properties values = new Properties();
        values.setProperty("visible", Boolean.toString(visible));
        values.setProperty("expanded", Boolean.toString(expanded));
        try {
            Files.createDirectories(settings.getParent());
            try (var output = Files.newOutputStream(settings)) { values.store(output, "MMM debug display preferences"); }
        } catch (IOException failure) { MmmMod.LOGGER.warn("Cannot save debug display preferences", failure); }
    }
}
