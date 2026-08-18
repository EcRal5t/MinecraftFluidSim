package com.example.gpuprobe;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class GpuProbeShaderSource {
	private static final String FLIP_RESOURCE = "/assets/realwater_gpu_probe/shaders/flip.comp";

	private GpuProbeShaderSource() { }

	static String loadFlip() {
		try (InputStream stream = GpuProbeShaderSource.class.getResourceAsStream(FLIP_RESOURCE)) {
			if (stream == null) throw new IllegalStateException("Missing GPU FLIP shader resource: " + FLIP_RESOURCE);
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException exception) {
			throw new IllegalStateException("Failed to read GPU FLIP shader resource: " + FLIP_RESOURCE, exception);
		}
	}
}
