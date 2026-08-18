package com.example.logging;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Standalone UTF-8 creation/append/failure regression for the command transcript. */
public final class CommandAuditFileValidation {
	private CommandAuditFileValidation() { }

	public static void main(String[] args) throws Exception {
		Path root = Files.createTempDirectory("realwater-command-log-").toAbsolutePath().normalize();
		Path log = root.resolve("logs/realwater-commands.log");
		Path blocker = root.resolve("not-a-directory");
		try {
			CommandAuditFile.append(log, new CommandAuditFile.Entry(
					Instant.parse("2026-08-18T14:00:00Z"), "Ecisral", "/realwatergpu status",
					List.of(new CommandAuditFile.Line("SUCCESS", "暂停 | 水量 324.00 方块\n第二行诊断")), 1));
			CommandAuditFile.append(log, new CommandAuditFile.Entry(
					Instant.parse("2026-08-18T14:00:01Z"), "Server", "/realwatergpu benchmark 12 0",
					List.of(new CommandAuditFile.Line("FAILURE", "GPU benchmark 失败")), 0));

			String text = Files.readString(log, StandardCharsets.UTF_8);
			require(text.contains("COMMAND: /realwatergpu status"), "first command missing");
			require(text.contains("SUCCESS: 暂停 | 水量 324.00 方块"), "UTF-8 output missing");
			require(text.contains("第二行诊断"), "multiline detail missing");
			require(text.contains("COMMAND: /realwatergpu benchmark 12 0"), "append command missing");
			require(text.contains("FAILURE: GPU benchmark 失败"), "failure output missing");
			require(text.indexOf("/realwatergpu status") < text.indexOf("/realwatergpu benchmark 12 0"), "append order changed");

			Files.writeString(blocker, "block", StandardCharsets.UTF_8);
			boolean failed = false;
			try {
				CommandAuditFile.append(blocker.resolve("child.log"), new CommandAuditFile.Entry(
						Instant.EPOCH, "test", "/test", List.of(), 0));
			} catch (IOException expected) {
				failed = true;
			}
			require(failed, "unwritable parent did not fail visibly");
			System.out.println("CommandAuditFileValidation passed UTF-8 create, append, multiline and failure cases");
		} finally {
			Files.deleteIfExists(log);
			Files.deleteIfExists(log.getParent());
			Files.deleteIfExists(blocker);
			Files.deleteIfExists(root);
		}
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
