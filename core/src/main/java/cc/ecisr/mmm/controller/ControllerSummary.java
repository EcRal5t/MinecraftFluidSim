package cc.ecisr.mmm.controller;

import cc.ecisr.mmm.api.RegionAvailabilityReason;

import java.util.Objects;
import java.util.UUID;

/**
 * Loader-neutral, bounded controller observation authored by the owning server thread.
 * Counts and inventory values are authoritative ledgers; clients may only present them.
 */
public record ControllerSummary(
		long summaryGeneration,
		UUID regionId,
		String dimensionId,
		String profileFingerprint,
		long checkpointGeneration,
		long networkGeneration,
		Footprint footprint,
		boolean selected,
		OperatorIntent operatorIntent,
		MotionState motionState,
		ThermalState thermalState,
		Availability availability,
		Usage usage,
		Inventory inventory,
		Gates gates,
		Diagnostics diagnostics
) {
	public ControllerSummary {
		if (summaryGeneration <= 0L || checkpointGeneration <= 0L || networkGeneration <= 0L) {
			throw new IllegalArgumentException("Controller summary generations must be positive");
		}
		Objects.requireNonNull(regionId, "regionId");
		if (dimensionId == null || dimensionId.isBlank()) {
			throw new IllegalArgumentException("Controller summary dimension is missing");
		}
		if (profileFingerprint == null || !profileFingerprint.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("Controller summary profile fingerprint is invalid");
		}
		Objects.requireNonNull(footprint, "footprint");
		Objects.requireNonNull(operatorIntent, "operatorIntent");
		Objects.requireNonNull(motionState, "motionState");
		Objects.requireNonNull(thermalState, "thermalState");
		Objects.requireNonNull(availability, "availability");
		Objects.requireNonNull(usage, "usage");
		Objects.requireNonNull(inventory, "inventory");
		Objects.requireNonNull(gates, "gates");
		Objects.requireNonNull(diagnostics, "diagnostics");
	}

	public enum OperatorIntent { RUNNING, PAUSED }
	public enum MotionState { ACTIVE, REDUCED, HYDROSTATIC_SLEEP, UNLOADED }
	public enum ThermalState { UNBOUND, ACTIVE, REDUCED, SLEEP, PAUSED }

	public record Footprint(
			int controllerX, int controllerY, int controllerZ,
			int blockSizeX, int blockSizeY, int blockSizeZ,
			long interiorBlocks, int requiredChunks
	) {
		public Footprint {
			if (blockSizeX <= 0 || blockSizeY <= 0 || blockSizeZ <= 0
					|| interiorBlocks <= 0L || requiredChunks <= 0) {
				throw new IllegalArgumentException("Controller summary footprint is invalid");
			}
		}
	}

	public record Availability(RegionAvailabilityReason reason, String detail) {
		public Availability {
			if ((reason == null) != (detail == null)) {
				throw new IllegalArgumentException("Controller summary availability reason/detail disagree");
			}
			if (detail != null && detail.isBlank()) {
				throw new IllegalArgumentException("Controller summary availability detail is blank");
			}
		}

		public boolean ready() { return reason == null; }
	}

	public record Capacity(boolean available, long used, long limit) {
		public Capacity {
			if (used < 0L || limit <= 0L || used > limit || !available && used != 0L) {
				throw new IllegalArgumentException("Controller summary capacity is invalid");
			}
		}

		public static Capacity available(long used, long limit) { return new Capacity(true, used, limit); }
		public static Capacity unavailable(long limit) { return new Capacity(false, 0L, limit); }
	}

	public record Usage(
			Capacity cells,
			Capacity markers,
			Capacity chemicalParcels,
			Capacity activeParcels,
			Capacity depositGroups,
			Capacity coherentGroups,
			Capacity gasNodes,
			Capacity finiteDevices
	) {
		public Usage {
			Objects.requireNonNull(cells, "cells");
			Objects.requireNonNull(markers, "markers");
			Objects.requireNonNull(chemicalParcels, "chemicalParcels");
			Objects.requireNonNull(activeParcels, "activeParcels");
			Objects.requireNonNull(depositGroups, "depositGroups");
			Objects.requireNonNull(coherentGroups, "coherentGroups");
			Objects.requireNonNull(gasNodes, "gasNodes");
			Objects.requireNonNull(finiteDevices, "finiteDevices");
		}
	}

	public record Inventory(
			boolean live,
			double liquidVolumeCubicMetres,
			double liquidMassKilograms,
			long speciesQuanta,
			long granularSolidVolumeSvq,
			double granularMassKilograms,
			long coherentSolidVolumeSvq,
			double coherentMassKilograms,
			long gasStandardVolumeNanos,
			double gasMassKilograms,
			boolean internalEnergyAvailable,
			double totalInternalEnergyEu,
			TemperatureRange temperatureRange
	) {
		public Inventory {
			if (!finiteNonNegative(liquidVolumeCubicMetres) || !finiteNonNegative(liquidMassKilograms)
					|| speciesQuanta < 0L || granularSolidVolumeSvq < 0L
					|| !finiteNonNegative(granularMassKilograms) || coherentSolidVolumeSvq < 0L
					|| !finiteNonNegative(coherentMassKilograms) || gasStandardVolumeNanos < 0L
					|| !finiteNonNegative(gasMassKilograms)
					|| internalEnergyAvailable != Double.isFinite(totalInternalEnergyEu)
					|| temperatureRange == null) {
				throw new IllegalArgumentException("Controller summary inventory is invalid");
			}
			if (!live && (liquidVolumeCubicMetres != 0.0 || liquidMassKilograms != 0.0
					|| speciesQuanta != 0L || granularSolidVolumeSvq != 0L || granularMassKilograms != 0.0
					|| coherentSolidVolumeSvq != 0L || coherentMassKilograms != 0.0
					|| gasStandardVolumeNanos != 0L || gasMassKilograms != 0.0 || internalEnergyAvailable
					|| temperatureRange.available())) {
				throw new IllegalArgumentException("Metadata-only controller summary invented live inventory");
			}
		}

		public static Inventory unavailable() {
			return new Inventory(false, 0.0, 0.0, 0L, 0L, 0.0, 0L, 0.0,
					0L, 0.0, false, Double.NaN, TemperatureRange.unavailable());
		}

		private static boolean finiteNonNegative(double value) {
			return Double.isFinite(value) && value >= 0.0;
		}
	}

	public record TemperatureRange(boolean available, double minimumK, double maximumK) {
		public TemperatureRange {
			boolean valid = Double.isFinite(minimumK) && Double.isFinite(maximumK)
					&& minimumK >= 0.0 && maximumK >= minimumK;
			if (available != valid || !available
					&& !(Double.isNaN(minimumK) && Double.isNaN(maximumK))) {
				throw new IllegalArgumentException("Controller summary temperature range is invalid");
			}
		}

		public static TemperatureRange available(double minimumK, double maximumK) {
			return new TemperatureRange(true, minimumK, maximumK);
		}

		public static TemperatureRange unavailable() {
			return new TemperatureRange(false, Double.NaN, Double.NaN);
		}
	}

	public record Gates(
			boolean ownershipReady,
			boolean containerWalPending,
			boolean phaseWorkPending,
			boolean worldBridgeEnabled,
			boolean checkpointAllowed
	) { }

	public record Diagnostics(
			double lastStepMilliseconds,
			int pressureIterations,
			double pressureResidual,
			String numericalFailure
	) {
		public Diagnostics {
			if (!Double.isFinite(lastStepMilliseconds) || lastStepMilliseconds < 0.0
					|| pressureIterations < 0 || !Double.isFinite(pressureResidual)) {
				throw new IllegalArgumentException("Controller summary diagnostics are invalid");
			}
			if (numericalFailure != null && numericalFailure.isBlank()) {
				throw new IllegalArgumentException("Controller summary numerical failure is blank");
			}
		}
	}
}
