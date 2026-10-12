package mmm.logging;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** UTF-8 append-only transcript format. It is diagnostic output, never simulation or recovery truth. */
public final class CommandAuditFile {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS xxx")
			.withZone(ZoneId.systemDefault());

	private CommandAuditFile() { }

	public static void append(Path path, Entry entry) throws IOException {
		Path parent = path.toAbsolutePath().normalize().getParent();
		if (parent != null) Files.createDirectories(parent);
		Files.writeString(path, render(entry), StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
	}

	static String render(Entry entry) {
		StringBuilder text = new StringBuilder(256);
		text.append('[').append(TIME.format(entry.timestamp())).append("]\n");
		text.append("SOURCE: ").append(singleLine(entry.source())).append('\n');
		text.append("COMMAND: ").append(singleLine(entry.command())).append('\n');
		for (Line line : entry.lines()) appendLine(text, line.kind(), line.text());
		text.append("RESULT: ").append(entry.result()).append("\n\n");
		return text.toString();
	}

	private static void appendLine(StringBuilder target, String kind, String value) {
		String normalized = value == null ? "null" : value.replace("\r\n", "\n").replace('\r', '\n');
		String[] lines = normalized.split("\n", -1);
		for (int i = 0; i < lines.length; i++) {
			target.append(i == 0 ? kind + ": " : "  ").append(lines[i]).append('\n');
		}
	}

	private static String singleLine(String value) {
		return value == null ? "null" : value.replace('\r', ' ').replace('\n', ' ');
	}

	public record Entry(Instant timestamp, String source, String command, List<Line> lines, int result) {
		public Entry {
			lines = List.copyOf(lines);
		}
	}

	public record Line(String kind, String text) { }
}
