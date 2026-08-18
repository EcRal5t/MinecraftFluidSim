package com.example.gpuprobe;

/** Standalone entrypoint used before the optional Fabric probe is installed. */
public final class HeadlessGpuProbeMain {
	private HeadlessGpuProbeMain() { }

	public static void main(String[] args) {
		int requestedDevice = args.length == 0 ? 0 : Integer.parseInt(args[0]);
		VulkanCapabilityProbe.ProbeReport report = VulkanCapabilityProbe.run(requestedDevice);
		System.out.println(report.format());
		if (!report.success()) System.exit(1);
	}
}
