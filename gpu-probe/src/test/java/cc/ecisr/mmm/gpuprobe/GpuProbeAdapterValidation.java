package cc.ecisr.mmm.gpuprobe;

/** Preserves the optional Fabric command shape after the headless kernel module split. */
public final class GpuProbeAdapterValidation {
	private GpuProbeAdapterValidation() { }

	public static void main(String[] args) {
		var probe = MmmGpuProbeMod.probeCommand().build();
		require("probe".equals(probe.getName()) && probe.getCommand() != null,
				"probe command lost its default executor");
		var device = probe.getChild("device");
		require(device != null && device.getCommand() != null,
				"probe device argument is not nested under probe");
		require(probe.getChildren().size() == 1,
				"probe command has an unexpected child layout");
		System.out.println("GPU probe Fabric command validation passed");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
