package mmm.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;

/** Sends only explicitly selected mod diagnostics to their intended audience. */
public final class DebugDiagnostics {
    private DebugDiagnostics() { }

    public static boolean event(ServerPlayer player, DebugDiagnosticPayload.Level level, String source, String id, String text) {
        if (!ServerPlayNetworking.canSend(player, DebugDiagnosticPayload.TYPE)) return false;
        for (String part : split(text))
            ServerPlayNetworking.send(player, new DebugDiagnosticPayload(DebugDiagnosticPayload.Kind.EVENT, level,
                    dimension(player), source, id, part));
        return true;
    }

    public static void snapshot(ServerPlayer player, String id, String text) {
        if (ServerPlayNetworking.canSend(player, DebugDiagnosticPayload.TYPE))
            ServerPlayNetworking.send(player, new DebugDiagnosticPayload(DebugDiagnosticPayload.Kind.SNAPSHOT,
                    DebugDiagnosticPayload.Level.INFO, dimension(player), "solver", id, text));
    }

    static java.util.List<String> split(String text) {
        java.util.ArrayList<String> parts = new java.util.ArrayList<>();
        for (int start = 0; start < text.length();) {
            int end = Math.min(start + 2048, text.length());
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            parts.add(text.substring(start, end));
            start = end;
        }
        if (parts.isEmpty()) parts.add("");
        return parts;
    }

    static boolean allowed(boolean operator, boolean integratedOwner) { return operator || integratedOwner; }

    public static boolean canInspect(ServerPlayer player) {
        var server = player.level().getServer();
        return allowed(player.createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_ADMIN),
                server.isSingleplayer() && server.isSingleplayerOwner(player.nameAndId()));
    }

    private static String dimension(ServerPlayer player) {
        return player.level().dimension().identifier().toString();
    }

    public static void operators(ServerLevel level, String id, String text) {
        for (ServerPlayer player : level.players()) {
            if (canInspect(player))
                event(player, DebugDiagnosticPayload.Level.ERROR, "simulation", id, text);
        }
    }
}
