package mmm.client;

import mmm.chemistry.ChemicalDisplaySnapshot;
import mmm.network.FluidMaterialSnapshotPayload;
import mmm.network.FluidThermalSnapshotPayload;

/** Shared material, composition, pH and thermal colour rules, independent of mesh construction. */
final class FluidColorMapping {
	static final int NO_DATA = 0xff929292;
	private FluidColorMapping() { }

	static int color(FluidDisplaySettings.View view, ClientRegionSnapshotStore.ChemicalFrame chemical,
			short[] temperatures, FluidMaterialSnapshotPayload state, int cell, int phase, int materialArgb) {
		return switch (view.mode()) {
			case COMPOSITION -> composition(chemical, cell, materialArgb);
			case PH -> {
				int cells = state.sizeX() * state.sizeY() * state.sizeZ();
				int encoded = chemical == null ? ChemicalDisplaySnapshot.PH_NO_DATA
						: Byte.toUnsignedInt(chemical.ph()[phase * cells + cell]);
				yield encoded == ChemicalDisplaySnapshot.PH_NO_DATA ? NO_DATA
						: phColor(encoded / (double) ChemicalDisplaySnapshot.PH_SCALE);
			}
			case TEMPERATURE -> {
				int encoded = temperatures == null ? FluidThermalSnapshotPayload.EMPTY_TEMPERATURE_Q
						: Short.toUnsignedInt(temperatures[cell]);
				yield encoded == FluidThermalSnapshotPayload.EMPTY_TEMPERATURE_Q ? NO_DATA
						: temperatureColor((encoded / 16.0 - view.minimumK()) / (view.maximumK() - view.minimumK()));
			}
		};
	}

	static int withoutDisplayData(int compositionArgb) {
		return FluidDisplaySettings.INSTANCE.view().mode() == FluidDisplaySettings.Mode.COMPOSITION ? compositionArgb : NO_DATA;
	}

	private static int composition(ClientRegionSnapshotStore.ChemicalFrame chemical, int cell, int materialArgb) {
		if (chemical == null) return materialArgb;
		int residual = Byte.toUnsignedInt(chemical.residualWeights()[cell]);
		int red = ((materialArgb >>> 16) & 255) * residual;
		int green = ((materialArgb >>> 8) & 255) * residual;
		int blue = (materialArgb & 255) * residual;
		int total = residual;
		for (int slot = 0; slot < ChemicalDisplaySnapshot.TOP_SPECIES; slot++) {
			int offset = cell * ChemicalDisplaySnapshot.TOP_SPECIES + slot;
			int species = chemical.topSpecies()[offset], weight = Byte.toUnsignedInt(chemical.topWeights()[offset]);
			if (species < 0 || weight == 0) continue;
			int argb = chemical.payload().species().get(species).argb();
			red += ((argb >>> 16) & 255) * weight;
			green += ((argb >>> 8) & 255) * weight;
			blue += (argb & 255) * weight;
			total += weight;
		}
		return total == 0 ? materialArgb : 0xff000000 | (red / total) << 16 | (green / total) << 8 | blue / total;
	}

	static int phColor(double value) {
		return value <= 7 ? blend(0xffff3048, 0xff35d06f, value / 7)
				: blend(0xff35d06f, 0xff305cff, (value - 7) / 7);
	}
	static int temperatureColor(double value) {
		double clamped = Math.clamp(value, 0, 1);
		return clamped <= 0.5 ? blend(0xff244bdb, 0xffe8e4bf, clamped * 2)
				: blend(0xffe8e4bf, 0xffed3524, clamped * 2 - 1);
	}
	static int blend(int first, int second, double amount) {
		double t = Math.clamp(amount, 0, 1);
		int red = (int) Math.round(((first >>> 16) & 255) * (1 - t) + ((second >>> 16) & 255) * t);
		int green = (int) Math.round(((first >>> 8) & 255) * (1 - t) + ((second >>> 8) & 255) * t);
		int blue = (int) Math.round((first & 255) * (1 - t) + (second & 255) * t);
		return 0xff000000 | red << 16 | green << 8 | blue;
	}
}
