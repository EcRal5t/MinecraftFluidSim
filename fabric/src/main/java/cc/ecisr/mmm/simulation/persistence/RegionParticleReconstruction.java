package cc.ecisr.mmm.simulation.persistence;

import cc.ecisr.mmm.model.region.RegionManifest;
import cc.ecisr.mmm.model.region.RegionSectionState;
import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.units.ExactVolume;
import cc.ecisr.mmm.fluid.LiquidInventoryState;
import java.io.IOException;
import java.util.List;

/** Restores original region ordinals for cross-column validation and immutable handoff. */
final class RegionParticleReconstruction {
	private RegionParticleReconstruction() { }
	static State reconstruct(RegionManifest manifest, List<RegionSectionState> sections, LiquidInventoryState inventory) throws IOException {
		int count = manifest.totalMarkerCount();
		float[] positions = new float[Math.multiplyExact(count, 4)];
		float[] velocities = new float[Math.multiplyExact(count, 3)];
		double[] markerMass = manifest.layout().hasMaterials() ? new double[count] : new double[0];
		int[] materialPaletteIndex = manifest.layout().hasMaterials() ? new int[count] : new int[0];
		int[] chemicalMixtureIndex = manifest.hasChemicalState() ? new int[count] : new int[0];
		byte[] indicator = manifest.hasChemicalState() ? new byte[count] : new byte[0];
		byte[] solids = new byte[Math.multiplyExact(Math.multiplyExact(manifest.sizeX(), manifest.sizeY()), manifest.sizeZ())];
		boolean[] seenMarkers = new boolean[count];
		boolean[] seenSolids = new boolean[solids.length];
		for (RegionSectionState section : sections) {
			int[] ordinals = section.markerOrdinals();
			float[] sectionPositions = section.positionVolume();
			float[] sectionVelocities = section.velocities();
			double[] sectionMass = section.markerMass();
			int[] sectionMaterials = section.materialPaletteIndex();
			int[] sectionMixtures = section.chemicalMixtureIndex();
			byte[] sectionIndicator = section.indicator();
			for (int i = 0; i < ordinals.length; i++) {
				int ordinal = ordinals[i];
				if (ordinal < 0 || ordinal >= count || seenMarkers[ordinal]) throw new IOException("Duplicate or invalid marker ordinal");
				seenMarkers[ordinal] = true;
				System.arraycopy(sectionPositions, i * 4, positions, ordinal * 4, 4);
				System.arraycopy(sectionVelocities, i * 3, velocities, ordinal * 3, 3);
				if (manifest.layout().hasMaterials()) {
					markerMass[ordinal] = sectionMass[i];
					materialPaletteIndex[ordinal] = sectionMaterials[i];
				}
				if (manifest.hasChemicalState()) {
					chemicalMixtureIndex[ordinal] = sectionMixtures[i];
					indicator[ordinal] = sectionIndicator[i];
				}
			}
			byte[] slice = section.solidMask();
			int source = 0;
			for (int z = 0; z < section.sizeZ(); z++) for (int y = 0; y < section.sizeY(); y++) for (int x = 0; x < section.sizeX(); x++) {
				int localX = section.localStartX() + x, localY = section.localStartY() + y, localZ = section.localStartZ() + z;
				if (localX < 0 || localX >= manifest.sizeX() || localY < 0 || localY >= manifest.sizeY()
						|| localZ < 0 || localZ >= manifest.sizeZ()) throw new IOException("Solid slice exceeds region bounds");
				int index = localX + manifest.sizeX() * (localY + manifest.sizeY() * localZ);
				if (seenSolids[index]) throw new IOException("Overlapping solid slices");
				seenSolids[index] = true;
				solids[index] = slice[source++];
			}
		}
		for (boolean seen : seenMarkers) if (!seen) throw new IOException("Missing marker ordinal");
		for (boolean seen : seenSolids) if (!seen) throw new IOException("Incomplete solid-mask coverage");
		try { inventory.requireObservations(positions, manifest.layout().hasMaterials() ? markerMass : null); }
		catch (IllegalArgumentException invalid) { throw new IOException("Liquid inventory observations disagree with checkpoint", invalid); }
		double volume = inventory.totalVolume().toDouble();
		if (Double.doubleToRawLongBits(volume) != manifest.totalVolumeBits()) throw new IOException("Marker volume ledger mismatch");
		return new State(positions, velocities, solids, markerMass, materialPaletteIndex,
				chemicalMixtureIndex, indicator, inventory);
	}

	static ExactVolume sectionVolume(RegionSectionState section, LiquidInventoryState inventory) {
		var sum = ExactVolume.ZERO;
		for (int p : section.markerOrdinals()) {
			if (p < 0 || p >= inventory.count()) throw new IllegalArgumentException("Liquid section inventory ordinal is invalid");
			sum = sum.add(inventory.volume(p));
		}
		return sum;
	}
	static SignedDyadic sectionMass(RegionSectionState section, LiquidInventoryState inventory) {
		var sum = SignedDyadic.ZERO;
		for (int p : section.markerOrdinals()) {
			if (p < 0 || p >= inventory.count()) throw new IllegalArgumentException("Liquid section inventory ordinal is invalid");
			sum = sum.add(inventory.mass(p));
		}
		return sum;
	}

	static List<RegionManifest.MaterialLedger> materialLedgers(int[] materials, int paletteSize, LiquidInventoryState inventory) {
		int[] count = new int[paletteSize]; var volumes = new ExactVolume[paletteSize]; var masses = new SignedDyadic[paletteSize];
		java.util.Arrays.fill(volumes, ExactVolume.ZERO); java.util.Arrays.fill(masses, SignedDyadic.ZERO);
		for (int p = 0; p < materials.length; p++) {
			int material = materials[p]; if (material < 0 || material >= paletteSize) throw new IllegalArgumentException("Material palette index out of range");
			count[material]++; volumes[material] = volumes[material].add(inventory.volume(p));
			masses[material] = masses[material].add(inventory.mass(p));
		}
		var ledgers = new java.util.ArrayList<RegionManifest.MaterialLedger>();
		for (int m = 0; m < paletteSize; m++) ledgers.add(new RegionManifest.MaterialLedger(count[m],
				Double.doubleToRawLongBits(volumes[m].toDouble()), Double.doubleToRawLongBits(masses[m].toDouble())));
		return List.copyOf(ledgers);
	}
	record State(
			float[] positionVolume,
			float[] velocities,
			byte[] solidMask,
			double[] markerMass,
			int[] materialPaletteIndex,
			int[] chemicalMixtureIndex,
			byte[] indicator,
			LiquidInventoryState inventory
	) { }
}
