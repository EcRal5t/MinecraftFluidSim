package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.api.StableId;

import java.util.List;

/** Immutable cell-local solid and gas observation; pressure and temperature are derived display values. */
public record CellCarrierInventoryView(List<SolidAmount> solids, GasAmount gas) {
	public CellCarrierInventoryView {
		solids = List.copyOf(solids);
		if (solids.stream().anyMatch(java.util.Objects::isNull)) {
			throw new IllegalArgumentException("Cell carrier solids contain null");
		}
	}

	public enum SolidKind { ACTIVE, DEPOSIT, COHERENT }

	public record SolidAmount(SolidKind kind, String stableId, long solidVolumeSvq, double massKilograms) {
		public SolidAmount {
			if (kind == null) throw new IllegalArgumentException("Solid carrier kind is missing");
			StableId.require(stableId, 128, "solid carrier id");
			if (solidVolumeSvq <= 0L || !(Double.isFinite(massKilograms) && massKilograms > 0.0)) {
				throw new IllegalArgumentException("Solid carrier amount is invalid");
			}
		}
	}

	public record GasAmount(
			boolean ambient,
			int nodeId,
			double absolutePressurePascal,
			double temperatureKelvin,
			List<ComponentAmount> components
	) {
		public GasAmount {
			if (nodeId < 0 || ambient != (nodeId == 0)
					|| !(Double.isFinite(absolutePressurePascal) && absolutePressurePascal >= 0.0)
					|| !(Double.isFinite(temperatureKelvin) && temperatureKelvin > 0.0)) {
				throw new IllegalArgumentException("Gas cell observation is invalid");
			}
			components = List.copyOf(components);
			if (components.stream().anyMatch(java.util.Objects::isNull) || ambient && !components.isEmpty()) {
				throw new IllegalArgumentException("Ambient gas must not pretend to own finite component inventory");
			}
		}
	}

	public record ComponentAmount(String componentId, long standardVolumeNanos, double massKilograms) {
		public ComponentAmount {
			StableId.require(componentId, 128, "gas component id");
			if (standardVolumeNanos <= 0L || !(Double.isFinite(massKilograms) && massKilograms > 0.0)) {
				throw new IllegalArgumentException("Gas component observation is invalid");
			}
		}
	}
}
