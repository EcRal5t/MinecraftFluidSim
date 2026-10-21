package mmm.validation;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Properties;

/** Build provenance plus a fingerprint of the class files actually loaded by this JVM. */
public final class ValidationRunInfo {
	private static boolean printed;
	private ValidationRunInfo() { }

	public static synchronized void print(String entry, String[] args) throws Exception {
		if (printed) return;
		var build = new Properties();
		try (var input = ValidationRunInfo.class.getResourceAsStream("/validation-build.properties")) {
			if (input == null) throw new IOException("Validation build provenance is missing; rebuild testClasses");
			build.load(input);
		}
		Path root = Path.of(build.getProperty("workspaceRoot")).toAbsolutePath().normalize();
		var artifacts = new ArrayList<Path>();
		for (String item : System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator))) {
			Path path = Path.of(item).toAbsolutePath().normalize();
			if (!path.startsWith(root) || !Files.exists(path)) continue;
			if (Files.isDirectory(path)) {
				try (var files = Files.walk(path)) {
					files.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".class")).forEach(artifacts::add);
				}
			} else artifacts.add(path);
		}
		artifacts.sort(Comparator.comparing(Path::toString));
		var digest = MessageDigest.getInstance("SHA-256");
		byte[] buffer = new byte[65536];
		for (Path path : artifacts) {
			digest.update(root.relativize(path).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
			try (var stream = Files.newInputStream(path)) {
				int count; while ((count = stream.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count);
			}
		}
		System.out.printf("CPU validation revision=%s, sourceSHA256=%s, artifactSHA256=%s%n",
				build.getProperty("revision"), build.getProperty("sourceSHA256"), HexFormat.of().formatHex(digest.digest()));
		System.out.printf("CPU validation JVM=%s, arguments=%s, entry=%s, selection=%s, assertions=%s%n",
				ProcessHandle.current().info().command().orElse("unknown"), ManagementFactory.getRuntimeMXBean().getInputArguments(),
				entry, java.util.Arrays.toString(args), ValidationRunInfo.class.desiredAssertionStatus());
		printed = true;
	}
}
