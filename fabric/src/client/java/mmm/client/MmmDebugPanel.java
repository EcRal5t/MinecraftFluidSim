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
    private static int eventScroll;
    private record Line(String text, int color, int column) { }
    private record Layout(List<Line> rows, List<Line> readings, int used, int eventLimit,
                          int localWidth, int readingX, boolean columns) { }
    private static Layout cachedLayout;
    private static int cachedWidth, cachedHeight, cachedScroll;
    private static boolean cachedExpanded;
    private static long cacheTime;
    private static String fps = "FPS --", frame = "Frame --", heap = "Heap --";

    static void register() {
        settings = FabricLoader.getInstance().getConfigDir().resolve("mmm-debug-display.properties");
        load();
        var toggle = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.mmm.debug.toggle", GLFW.GLFW_KEY_F8, KeyMapping.Category.DEBUG));
        var detail = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.mmm.debug.expand", GLFW.GLFW_KEY_F9, KeyMapping.Category.DEBUG));
        var older = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.mmm.debug.older", GLFW.GLFW_KEY_PAGE_UP, KeyMapping.Category.DEBUG));
        var newer = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.mmm.debug.newer", GLFW.GLFW_KEY_PAGE_DOWN, KeyMapping.Category.DEBUG));
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
            while (older.consumeClick()) if (client.gui.screen() == null && expanded) eventScroll++;
            while (newer.consumeClick()) if (client.gui.screen() == null && expanded) eventScroll = Math.max(0, eventScroll - 1);
        });
        ClientConfigurationConnectionEvents.START.register((handler, client) -> clear());
        ClientConfigurationConnectionEvents.DISCONNECT.register((handler, client) -> clear());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, MmmMod.id("debug_panel"), (g, tick) -> render(g));
    }
    private static void clear() { DATA.clear(); dimension = null; eventScroll = 0; cachedLayout = null; }
    private static void observeDimension(Minecraft client) {
        String next = client.level == null ? null : client.level.dimension().identifier().toString();
        if (!java.util.Objects.equals(dimension, next)) { DATA.clear(); dimension = next; eventScroll = 0; cachedLayout = null; }
    }
    private static void render(GuiGraphicsExtractor g) {
        Minecraft client = Minecraft.getInstance();
        if (!visible || client.level == null || client.gui.screen() != null) return;
        int w = Math.min(expanded ? 480 : 220, Math.max(1, g.guiWidth() - 12));
        int x = g.guiWidth() - w - 6, y = 6;
        long now = System.nanoTime();
        if (cachedLayout == null || cachedWidth != w || cachedHeight != g.guiHeight()
                || cachedExpanded != expanded || cachedScroll != eventScroll || now - cacheTime >= 500_000_000L) {
            cachedLayout = buildLayout(client, w, g.guiHeight());
            cachedWidth = w; cachedHeight = g.guiHeight(); cachedExpanded = expanded;
            cachedScroll = eventScroll; cacheTime = now;
        }
        Layout layout = cachedLayout;
        int h = 27 + (layout.used() + layout.eventLimit()) * 11;
        IndustrialUi.panel(g, x, y, w, h);
        IndustrialUi.header(g, x, y, w);
        g.text(client.font, Component.translatable("hud.mmm.debug.title"), x + 7, y + 6, IndustrialUi.CYAN, false);
        String mode = Component.translatable(expanded ? "hud.mmm.debug.expanded" : "hud.mmm.debug.compact").getString();
        g.text(client.font, mode, x + w - 7 - client.font.width(mode), y + 6, IndustrialUi.MUTED, false);
        int localY = y + 21, eventY = y + 21 + layout.used() * 11;
        for (var row : layout.rows()) {
            int lineY = row.column() == 0 ? localY : eventY;
            int available = row.column() == 0 ? layout.localWidth() : w - 14;
            g.text(client.font, client.font.plainSubstrByWidth(row.text(), Math.max(0, available)), x + 7, lineY, row.color(), false);
            if (row.column() == 0) localY += 11; else eventY += 11;
        }
        int readingY = layout.columns() ? y + 21 : localY;
        int readingX = x + layout.readingX();
        for (var row : layout.readings()) {
            g.text(client.font, row.text(), readingX, readingY, row.color(), false);
            readingY += 11;
        }
    }
    private static Layout buildLayout(Minecraft client, int w, int height) {
        var snapshots = DATA.snapshots();
        var events = DATA.events();
        List<Line> rows = new ArrayList<>();
        int max = Math.max(0, (height - 46) / 11);
        boolean columns = expanded && w >= 340;
        int localWidth = columns ? 140 : w - 14;
        int readingWidth = columns ? w - 164 : w - 14;
        for (String local : List.of(fps, frame, heap)) if (rows.size() < max)
            rows.add(new Line(local, IndustrialUi.CYAN, 0));
        int localCount = rows.size();
        List<Line> readings = new ArrayList<>();
        int snapshotLimit = DebugLayout.readingLimit(max, localCount, columns, expanded, !events.isEmpty());
        for (var row : snapshots) {
            if (row.stale(System.nanoTime()) && readings.size() < snapshotLimit)
                readings.add(new Line(row.source() + " · " + Component.translatable("hud.mmm.debug.stale").getString(), IndustrialUi.AMBER, 1));
            for (String part : DebugLayout.wrap(row.text(), readingWidth, client.font::plainSubstrByWidth)) {
                if (readings.size() >= snapshotLimit) break;
                readings.add(new Line(part, row.stale(System.nanoTime()) ? IndustrialUi.MUTED : IndustrialUi.TEXT, 1));
            }
        }
        int used = columns ? Math.max(localCount, readings.size()) : localCount + readings.size();
        List<Line> eventLines = new ArrayList<>();
        for (var row : events) {
            int color = switch (row.level()) { case WARN -> IndustrialUi.AMBER; case ERROR -> IndustrialUi.FAULT; default -> IndustrialUi.TEXT; };
            String text = row.time() + " " + row.source() + " [" + row.level() + "] " + row.text().replace('\n', ' ')
                    + (row.repeats() > 1 ? " ×" + row.repeats() : "");
            for (String part : DebugLayout.wrap(text, w - 14, client.font::plainSubstrByWidth)) {
                eventLines.add(new Line(part, color, 2));
            }
        }
        int eventCapacity = Math.min(expanded ? max : 2, Math.max(0, max - used));
        var page = DebugLayout.page(eventLines.size(), eventCapacity, expanded ? eventScroll : 0);
        if (expanded) eventScroll = page.scroll();
        rows.addAll(eventLines.subList(page.start(), page.end()));
        return new Layout(List.copyOf(rows), List.copyOf(readings), used, page.end() - page.start(),
                localWidth, columns ? 157 : 7, columns);
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
