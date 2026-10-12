package mmm.gpuprobe;

import mmm.fluid.ConservativeMarkerSampling;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class GpuProbeShaderSource {
	private static final String FLIP_RESOURCE = "/assets/mmm_gpu_probe/shaders/flip.comp";

	private GpuProbeShaderSource() { }

	static String loadFlip() {
		try (InputStream stream = GpuProbeShaderSource.class.getResourceAsStream(FLIP_RESOURCE)) {
			if (stream == null) throw new IllegalStateException("Missing GPU FLIP shader resource: " + FLIP_RESOURCE);
			String source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
			return source.replace("/*__CONSERVATIVE_MARKER_SAMPLING__*/", markerSamplingDefinitions());
		} catch (IOException exception) {
			throw new IllegalStateException("Failed to read GPU FLIP shader resource: " + FLIP_RESOURCE, exception);
		}
	}

	private static String markerSamplingDefinitions() {
		return "#define RESEED_AXIS_SAMPLES " + ConservativeMarkerSampling.AXIS_SAMPLES + "\n"
				+ "#define RESEED_TARGET_MAX " + ConservativeMarkerSampling.NOMINAL_MARKERS_PER_FULL_CELL + "\n"
				+ "#define RESEED_NOMINAL_VOLUME " + ConservativeMarkerSampling.NOMINAL_MARKER_VOLUME + "\n"
				+ "#define RESEED_DOMAIN_CONTACT " + ConservativeMarkerSampling.DOMAIN_CONTACT_FRACTION + "\n"
				+ "#define RESEED_SOLID_CONTACT " + ConservativeMarkerSampling.SOLID_CONTACT_FRACTION + "\n"
				+ "#define RESEED_POSITION_EPSILON " + ConservativeMarkerSampling.POSITION_EPSILON_FRACTION + "\n";
	}
}
