package cc.ecisr.mmm.logging;

import cc.ecisr.mmm.MmmMod;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Captures exact handled command text and every response emitted by Matter, Motion & Mechanics command handlers. */
public final class MmmCommandLog {
	public static final String RELATIVE_PATH = "logs/mmm-commands.log";
	private static final Object FILE_LOCK = new Object();
	private static final ThreadLocal<Session> ACTIVE = new ThreadLocal<>();

	private MmmCommandLog() { }

	public static int run(CommandContext<CommandSourceStack> context, Action action) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		Session previous = ACTIVE.get();
		Session session = new Session(source, commandText(context.getInput()));
		ACTIVE.set(session);
		try {
			session.result = action.run();
			return session.result;
		} catch (CommandSyntaxException exception) {
			session.lines.add(new CommandAuditFile.Line("EXCEPTION", exception.getMessage()));
			session.result = 0;
			throw exception;
		} catch (RuntimeException | LinkageError exception) {
			session.lines.add(new CommandAuditFile.Line("EXCEPTION", exception.getClass().getSimpleName() + ": " + exception.getMessage()));
			session.result = 0;
			throw exception;
		} finally {
			persist(session);
			if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
		}
	}

	public static void sendSuccess(CommandSourceStack source, String message, boolean broadcastToOps) {
		source.sendSuccess(() -> Component.literal(message), broadcastToOps);
		record("SUCCESS", message);
	}

	public static void sendFailure(CommandSourceStack source, String message) {
		source.sendFailure(Component.literal(message));
		record("FAILURE", message);
	}

	public static void detail(String message) {
		record("DETAIL", message);
	}

	public static Path path(CommandSourceStack source) {
		return source.getServer().getServerDirectory().resolve(RELATIVE_PATH).toAbsolutePath().normalize();
	}

	private static void record(String kind, String message) {
		Session session = ACTIVE.get();
		if (session != null) session.lines.add(new CommandAuditFile.Line(kind, message));
	}

	private static void persist(Session session) {
		if (session.lines.isEmpty()) session.lines.add(new CommandAuditFile.Line("OUTPUT", "(handler produced no chat response)"));
		CommandAuditFile.Entry entry = new CommandAuditFile.Entry(
				Instant.now(), session.source.getTextName(), session.command, session.lines, session.result);
		Path path = null;
		try {
			path = path(session.source);
			synchronized (FILE_LOCK) {
				CommandAuditFile.append(path, entry);
			}
		} catch (IOException | RuntimeException exception) {
			MmmMod.LOGGER.error("Could not append Matter, Motion & Mechanics command transcript to {}",
					path == null ? RELATIVE_PATH : path, exception);
		}
	}

	private static String commandText(String input) {
		return input.startsWith("/") ? input : "/" + input;
	}

	@FunctionalInterface
	public interface Action {
		int run() throws CommandSyntaxException;
	}

	private static final class Session {
		private final CommandSourceStack source;
		private final String command;
		private final List<CommandAuditFile.Line> lines = new ArrayList<>();
		private int result;

		private Session(CommandSourceStack source, String command) {
			this.source = source;
			this.command = command;
		}
	}
}
