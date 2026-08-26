package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.chemistry.ExactRational;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Frozen gas/liquid species exchange pair: one exact batch bridging gas component standard nanos and
 * liquid species quanta, an ideal-dilute Henry relation and physical film transport. The liquid
 * residual closes the batch mass bitwise: liquidKilograms + residual == gasKilograms.
 */
public record GasLiquidSpeciesPair(
		String id,
		String gasComponentId,
		String liquidPhaseId,
		String speciesId,
		long gasStandardVolumeNanosPerBatch,
		double gasKilogramsPerBatch,
		long liquidQuantaPerBatch,
		double liquidKilogramsPerBatch,
		double liquidResidualKilogramsPerBatch,
		ExactRational molesPerBatch,
		List<HenrySegment> henrySegments,
		double minimumPressurePa,
		double maximumPressurePa,
		double maximumLiquidConcentrationMolPerCubicMeter,
		double liquidDiffusionSquareMetersPerSecond,
		double liquidFilmThicknessMeters,
		double gasResistanceSecondsPerMeter
) {
	/** Ideal-dilute Henry constant k_H in mol/(m^3*Pa), linear in temperature on one closed interval. */
	public record HenrySegment(double minimumTemperatureK, double maximumTemperatureK,
			double henryConstantMolPerCubicMeterPaAtMinimum, double henryConstantMolPerCubicMeterPaAtMaximum) {
		public HenrySegment {
			if (!(Double.isFinite(minimumTemperatureK) && Double.isFinite(maximumTemperatureK)
					&& minimumTemperatureK > 0.0 && minimumTemperatureK < maximumTemperatureK)) {
				throw new IllegalArgumentException("Henry segment temperature bounds are invalid");
			}
			if (!(Double.isFinite(henryConstantMolPerCubicMeterPaAtMinimum)
					&& henryConstantMolPerCubicMeterPaAtMinimum > 0.0
					&& Double.isFinite(henryConstantMolPerCubicMeterPaAtMaximum)
					&& henryConstantMolPerCubicMeterPaAtMaximum > 0.0)) {
				throw new IllegalArgumentException("Henry constant must be finite and positive");
			}
		}
	}

	public GasLiquidSpeciesPair {
		ThermalIds.requireId(id, "gas/liquid pair id");
		ThermalIds.requireId(gasComponentId, "gas/liquid pair gas component id");
		ThermalIds.requireId(liquidPhaseId, "gas/liquid pair liquid phase id");
		ThermalIds.requireId(speciesId, "gas/liquid pair species id");
		if (gasStandardVolumeNanosPerBatch <= 0L || liquidQuantaPerBatch <= 0L) {
			throw new IllegalArgumentException("Gas/liquid pair batch amounts must be positive");
		}
		if (!(Double.isFinite(gasKilogramsPerBatch) && gasKilogramsPerBatch > 0.0
				&& Double.isFinite(liquidKilogramsPerBatch) && liquidKilogramsPerBatch > 0.0)) {
			throw new IllegalArgumentException("Gas/liquid pair batch kilograms must be finite and positive");
		}
		if (!Double.isFinite(liquidResidualKilogramsPerBatch)
				|| Double.doubleToRawLongBits(liquidResidualKilogramsPerBatch)
						!= Double.doubleToRawLongBits(gasKilogramsPerBatch - liquidKilogramsPerBatch)) {
			throw new IllegalArgumentException("Gas/liquid pair residual must close the batch mass exactly");
		}
		if (molesPerBatch == null || molesPerBatch.signum() <= 0) {
			throw new IllegalArgumentException("Gas/liquid pair moles per batch must be positive");
		}
		if (henrySegments == null || henrySegments.isEmpty()
				|| henrySegments.size() > ThermalLimits.MAX_HENRY_SEGMENTS_PER_PAIR) {
			throw new IllegalArgumentException("Henry segments are missing or over budget");
		}
		TreeMap<Double, HenrySegment> sorted = new TreeMap<>();
		for (HenrySegment segment : henrySegments) {
			if (segment == null) throw new IllegalArgumentException("Henry segment is missing");
			if (sorted.put(segment.minimumTemperatureK(), segment) != null) {
				throw new IllegalArgumentException("Duplicate Henry segment start in " + id);
			}
		}
		ArrayList<HenrySegment> ordered = new ArrayList<>(sorted.values());
		for (int index = 1; index < ordered.size(); index++) {
			HenrySegment previous = ordered.get(index - 1);
			HenrySegment current = ordered.get(index);
			if (previous.maximumTemperatureK() != current.minimumTemperatureK()
					|| previous.henryConstantMolPerCubicMeterPaAtMaximum()
							!= current.henryConstantMolPerCubicMeterPaAtMinimum()) {
				throw new IllegalArgumentException("Henry segments must be contiguous in " + id);
			}
		}
		henrySegments = List.copyOf(ordered);
		if (!(Double.isFinite(minimumPressurePa) && Double.isFinite(maximumPressurePa)
				&& minimumPressurePa > 0.0 && minimumPressurePa <= maximumPressurePa)) {
			throw new IllegalArgumentException("Gas/liquid pair pressure domain is invalid");
		}
		if (!(Double.isFinite(maximumLiquidConcentrationMolPerCubicMeter)
				&& maximumLiquidConcentrationMolPerCubicMeter > 0.0)) {
			throw new IllegalArgumentException("Gas/liquid pair concentration domain is invalid");
		}
		if (!(Double.isFinite(liquidDiffusionSquareMetersPerSecond) && liquidDiffusionSquareMetersPerSecond > 0.0)) {
			throw new IllegalArgumentException("Liquid diffusion coefficient must be finite and positive");
		}
		if (!(Double.isFinite(liquidFilmThicknessMeters) && liquidFilmThicknessMeters > 0.0)) {
			throw new IllegalArgumentException("Liquid film thickness must be finite and positive");
		}
		if (!(Double.isFinite(gasResistanceSecondsPerMeter) && gasResistanceSecondsPerMeter >= 0.0)) {
			throw new IllegalArgumentException("Mapped gas resistance must be finite and non-negative");
		}
	}

	/** Series film resistance per unit area in s/m; zero mapped gas resistance declares liquid-side control. */
	public double totalResistanceSecondsPerMeter() {
		return liquidFilmThicknessMeters / liquidDiffusionSquareMetersPerSecond + gasResistanceSecondsPerMeter;
	}

	public double henryConstantMolPerCubicMeterPaAt(double temperatureK) {
		if (temperatureK < henrySegments.getFirst().minimumTemperatureK()
				|| temperatureK > henrySegments.getLast().maximumTemperatureK()) {
			throw new IllegalArgumentException("Temperature " + temperatureK + " is outside the Henry domain of " + id);
		}
		int low = 0;
		int high = henrySegments.size() - 1;
		while (low < high) {
			int middle = (low + high) >>> 1;
			if (henrySegments.get(middle).maximumTemperatureK() < temperatureK) low = middle + 1;
			else high = middle;
		}
		HenrySegment segment = henrySegments.get(low);
		double span = segment.maximumTemperatureK() - segment.minimumTemperatureK();
		double alpha = span <= 0.0 ? 0.0 : (temperatureK - segment.minimumTemperatureK()) / span;
		return segment.henryConstantMolPerCubicMeterPaAtMinimum() * (1.0 - alpha)
				+ segment.henryConstantMolPerCubicMeterPaAtMaximum() * alpha;
	}
}
