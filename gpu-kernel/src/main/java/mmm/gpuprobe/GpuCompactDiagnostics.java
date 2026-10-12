package mmm.gpuprobe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/** Decodes and validates the compact, submission-local diagnostic frame copied from the GPU. */
final class GpuCompactDiagnostics {
	private GpuCompactDiagnostics() { }

	static Result decode(
			ByteBuffer bytes, VulkanFlipPipeline.Config config,
			VulkanFlipPipeline.PressureSolver pressureSolver,
			float substepTime, float particleVolumeLedger
	) {
		IntBuffer raw = bytes.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer();
		float pre = Float.intBitsToFloat(raw.get(0));
		float post = Float.intBitsToFloat(raw.get(1));
		float maxPressure = Float.intBitsToFloat(raw.get(2));
		float sumSquared = Float.intBitsToFloat(raw.get(3));
		float volume = Float.intBitsToFloat(raw.get(4));
		float weightedX = Float.intBitsToFloat(raw.get(5));
		int activeParticles = raw.get(6);
		int pressureIterations = raw.get(7);
		int reseedConservationSkipped = raw.get(8);
		float volumeAfterMerge = Float.intBitsToFloat(raw.get(9));
		float projectedRoundoffSquared = Float.intBitsToFloat(raw.get(10));
		float pcgResidual = Float.intBitsToFloat(raw.get(11));
		int pressureStatus = raw.get(12);
		int pressureUnknowns = raw.get(13);
		int reseedBudgetSkipped = raw.get(14);
		float volumeBeforeReseed = Float.intBitsToFloat(raw.get(15));

		float rms = pressureUnknowns == 0 ? 0.0F : (float) Math.sqrt(sumSquared / pressureUnknowns);
		float pressureResidual = config.density() / substepTime * rms;
		float projectedRoundoff = pressureUnknowns == 0 ? 0.0F : config.density() / substepTime
				* (float) Math.sqrt(projectedRoundoffSquared / pressureUnknowns);
		boolean pressureConverged = pressureUnknowns == 0
				|| pressureStatus != 2 && pressureResidual <= config.pressureTolerance() + projectedRoundoff;
		float centerX = volume == 0.0F ? Float.NaN : weightedX / volume;
		if (!Float.isFinite(pre) || !Float.isFinite(post) || !Float.isFinite(maxPressure)
				|| !Float.isFinite(rms) || !Float.isFinite(pressureResidual) || !Float.isFinite(projectedRoundoff)
				|| pressureUnknowns < 0 || pressureUnknowns > config.cellCount()
				|| activeParticles < 0 || activeParticles > config.maxParticles()
				|| activeParticles > 0 && (!Float.isFinite(volume) || !Float.isFinite(centerX))) {
			throw new IllegalStateException("Invalid compact GPU diagnostics: pre/post=" + pre + "/" + post
					+ ", maxPressure=" + maxPressure + ", rms=" + rms + ", pressureResidual=" + pressureResidual
					+ ", pressureUnknowns=" + pressureUnknowns + ", activeParticles=" + activeParticles
					+ ", volume=" + volume + ", centerX=" + centerX);
		}
		if (reseedBudgetSkipped != 0) {
			throw new IllegalStateException("GPU marker pool could not satisfy conservative reseeding: skipped="
					+ reseedBudgetSkipped);
		}
		float summationTolerance = volumeSummationTolerance(particleVolumeLedger, config.maxParticles());
		if (volumeBeforeReseed > 0.0F && (Math.abs(volumeBeforeReseed - particleVolumeLedger) > summationTolerance
				|| Math.abs(volumeAfterMerge - particleVolumeLedger) > summationTolerance
				|| Math.abs(volume - particleVolumeLedger) > summationTolerance)) {
			throw new IllegalStateException("GPU conservative reseeding changed marker volume: "
					+ volumeBeforeReseed + " -> merge " + volumeAfterMerge + " -> " + volume
					+ ", ledger=" + particleVolumeLedger + ", roundoffBound=" + summationTolerance);
		}
		if (pressureSolver == VulkanFlipPipeline.PressureSolver.PCG_DIAGONAL
				&& config.requirePressureConvergence() && !pressureConverged) {
			throw new IllegalStateException("GPU pressure projection did not converge: status=" + pressureStatus
					+ ", residual=" + pressureResidual + ", tolerance=" + config.pressureTolerance()
					+ ", recursiveResidual=" + pcgResidual
					+ ", projectedRoundoffBound=" + projectedRoundoff
					+ ", iterations=" + pressureIterations + "/" + config.pressureIterations());
		}
		return new Result(activeParticles, new VulkanFlipPipeline.Diagnostics(
				pre, post, rms, maxPressure, volume, centerX, pressureIterations,
				reseedConservationSkipped, pressureResidual, pressureConverged));
	}

	private static float volumeSummationTolerance(float total, int particleCapacity) {
		int groups = Math.max(1, (particleCapacity + VulkanFlipPipeline.LOCAL_SIZE - 1)
				/ VulkanFlipPipeline.LOCAL_SIZE);
		int additionsOnLongestPath = Integer.numberOfTrailingZeros(VulkanFlipPipeline.LOCAL_SIZE)
				+ Math.max(0, groups - 1);
		double unitRoundoff = Math.scalb(1.0, -24);
		double gamma = additionsOnLongestPath * unitRoundoff
				/ (1.0 - additionsOnLongestPath * unitRoundoff);
		return (float) (Math.abs(total) * gamma);
	}

	record Result(int activeParticles, VulkanFlipPipeline.Diagnostics diagnostics) { }
}
