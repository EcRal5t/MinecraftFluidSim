package com.example.fluid;

import java.util.Arrays;
import java.util.List;

/**
 * Immutable, read-only material reconstruction fields. Quantized fractions and marker seeds are display data;
 * exact volume and mass remain on the domain markers and are deliberately absent here.
 */
public final class FluidMaterialRenderSnapshot {
	private final int sizeX;
	private final int sizeY;
	private final int sizeZ;
	private final float cellSize;
	private final List<FluidMaterial> materials;
	private final List<String> phaseGroups;
	private final byte[] totalVolume;
	private final byte[] markers;
	private final byte[] solids;
	private final byte[][] materialFractions;
	private final byte[][] materialMarkers;
	private final byte[][] phaseFractions;
	private final byte[][] phaseMarkers;

	private FluidMaterialRenderSnapshot(
			int sizeX, int sizeY, int sizeZ, float cellSize,
			List<FluidMaterial> materials, List<String> phaseGroups,
			byte[] totalVolume, byte[] markers, byte[] solids,
			byte[][] materialFractions, byte[][] materialMarkers,
			byte[][] phaseFractions, byte[][] phaseMarkers
	) {
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.cellSize = cellSize;
		this.materials = List.copyOf(materials);
		this.phaseGroups = List.copyOf(phaseGroups);
		int cells = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		this.totalVolume = copyField(totalVolume, cells, "total volume");
		this.markers = copyField(markers, cells, "marker mask");
		this.solids = copyField(solids, cells, "solid mask");
		this.materialFractions = copyFields(materialFractions, this.materials.size(), cells, "material fractions");
		this.materialMarkers = copyFields(materialMarkers, this.materials.size(), cells, "material markers");
		this.phaseFractions = copyFields(phaseFractions, this.phaseGroups.size(), cells, "phase fractions");
		this.phaseMarkers = copyFields(phaseMarkers, this.phaseGroups.size(), cells, "phase markers");
	}

	static FluidMaterialRenderSnapshot capture(
			int sizeX, int sizeY, int sizeZ, float cellSize, FluidMaterialRegistry registry,
			byte[] totalVolume, byte[] markers, byte[] solids,
			float[] particleX, float[] particleY, float[] particleZ, float[] particleVolume,
			int[] particleMaterialHandle, int particleCount, double[] exactCellVolume
	) {
		int cells = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		byte[][] materialFractions = new byte[registry.size()][cells];
		byte[][] materialMarkers = new byte[registry.size()][cells];
		byte[][] phaseFractions = new byte[registry.phaseGroups().size()][cells];
		byte[][] phaseMarkers = new byte[registry.phaseGroups().size()][cells];
		EntryTable materialEntries = new EntryTable(cells, particleCount);
		EntryTable phaseEntries = new EntryTable(cells, particleCount);
		for (int particle = 0; particle < particleCount; particle++) {
			int x = clampCell(particleX[particle], cellSize, sizeX);
			int y = clampCell(particleY[particle], cellSize, sizeY);
			int z = clampCell(particleZ[particle], cellSize, sizeZ);
			int cell = x + sizeX * (y + sizeY * z);
			int material = particleMaterialHandle[particle];
			registry.material(material);
			int phase = registry.phaseHandle(material);
			materialEntries.add(cell, material, particleVolume[particle]);
			phaseEntries.add(cell, phase, particleVolume[particle]);
			materialMarkers[material][cell] = 1;
			phaseMarkers[phase][cell] = 1;
		}
		materialEntries.quantize(exactCellVolume, materialFractions);
		phaseEntries.quantize(exactCellVolume, phaseFractions);
		return new FluidMaterialRenderSnapshot(
				sizeX, sizeY, sizeZ, cellSize, registry.materials(), registry.phaseGroups(),
				totalVolume, markers, solids, materialFractions, materialMarkers, phaseFractions, phaseMarkers
		);
	}

	/** Rebuilds display-only CPU mesher input from validated quantized wire fields. */
	public static FluidMaterialRenderSnapshot fromQuantizedFields(
			int sizeX, int sizeY, int sizeZ, float cellSize,
			List<FluidMaterial> materials, List<String> phaseGroups,
			byte[] totalVolume, byte[] markers, byte[] solids,
			byte[][] materialFractions, byte[][] phaseFractions
	) {
		int cells = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		return new FluidMaterialRenderSnapshot(
				sizeX, sizeY, sizeZ, cellSize, materials, phaseGroups, totalVolume, markers, solids,
				materialFractions, markerFields(materialFractions, cells),
				phaseFractions, markerFields(phaseFractions, cells)
		);
	}

	public int sizeX() { return sizeX; }
	public int sizeY() { return sizeY; }
	public int sizeZ() { return sizeZ; }
	public float cellSize() { return cellSize; }
	public int cellCount() { return totalVolume.length; }
	public List<FluidMaterial> materials() { return materials; }
	public List<String> phaseGroups() { return phaseGroups; }
	public byte[] copyTotalVolume() { return totalVolume.clone(); }
	public byte[] copyMarkers() { return markers.clone(); }
	public byte[] copySolids() { return solids.clone(); }
	public byte[] copyMaterialFractions(int material) { return materialFractions[material].clone(); }
	public byte[] copyMaterialMarkers(int material) { return materialMarkers[material].clone(); }
	public byte[] copyPhaseFractions(int phase) { return phaseFractions[phase].clone(); }
	public byte[] copyPhaseMarkers(int phase) { return phaseMarkers[phase].clone(); }
	public int materialFraction(int material, int cell) { return Byte.toUnsignedInt(materialFractions[material][cell]); }
	public int phaseFraction(int phase, int cell) { return Byte.toUnsignedInt(phaseFractions[phase][cell]); }

	byte[] totalVolumeField() { return totalVolume; }
	byte[] markerField() { return markers; }
	byte[] solidField() { return solids; }
	byte[] phaseFractionField(int phase) { return phaseFractions[phase]; }

	private static int clampCell(float position, float cellSize, int size) {
		return Math.min(size - 1, Math.max(0, (int) Math.floor(position / cellSize)));
	}

	private static byte[] copyField(byte[] field, int cells, String label) {
		if (field == null || field.length != cells) throw new IllegalArgumentException("Invalid " + label + " field");
		return field.clone();
	}

	private static byte[][] copyFields(byte[][] fields, int expectedFields, int cells, String label) {
		if (fields == null || fields.length != expectedFields) throw new IllegalArgumentException("Invalid " + label + " palette");
		byte[][] result = new byte[fields.length][];
		for (int field = 0; field < fields.length; field++) result[field] = copyField(fields[field], cells, label);
		return result;
	}

	private static byte[][] markerFields(byte[][] fractions, int cells) {
		byte[][] result = new byte[fractions.length][cells];
		for (int field = 0; field < fractions.length; field++) {
			if (fractions[field] == null || fractions[field].length != cells) {
				throw new IllegalArgumentException("Invalid quantized fraction field");
			}
			for (int cell = 0; cell < cells; cell++) if (fractions[field][cell] != 0) result[field][cell] = 1;
		}
		return result;
	}

	private static final class EntryTable {
		private final int[] head;
		private final int[] label;
		private final int[] cell;
		private final int[] next;
		private final double[] volume;
		private int count;

		private EntryTable(int cells, int capacity) {
			head = new int[cells];
			Arrays.fill(head, -1);
			label = new int[capacity];
			cell = new int[capacity];
			next = new int[capacity];
			volume = new double[capacity];
		}

		private void add(int targetCell, int targetLabel, float amount) {
			for (int entry = head[targetCell]; entry >= 0; entry = next[entry]) {
				if (label[entry] != targetLabel) continue;
				volume[entry] += amount;
				return;
			}
			if (count >= label.length) throw new IllegalStateException("Render inventory exceeded marker count");
			label[count] = targetLabel;
			cell[count] = targetCell;
			volume[count] = amount;
			next[count] = head[targetCell];
			head[targetCell] = count++;
		}

		private void quantize(double[] exactCellVolume, byte[][] fields) {
			int[] quantized = new int[count];
			double[] remainder = new double[count];
			for (int targetCell = 0; targetCell < head.length; targetCell++) {
				if (head[targetCell] < 0) continue;
				double total = exactCellVolume[targetCell];
				if (!(Double.isFinite(total) && total > 0.0)) {
					throw new IllegalStateException("Marker phase field has no exact cell volume");
				}
				int assigned = 0, entries = 0;
				for (int entry = head[targetCell]; entry >= 0; entry = next[entry]) {
					double scaled = volume[entry] / total * 255.0;
					quantized[entry] = (int) Math.floor(scaled);
					remainder[entry] = scaled - quantized[entry];
					assigned += quantized[entry];
					entries++;
				}
				int missingCount = 255 - assigned;
				if (missingCount < 0 || missingCount > entries) {
					throw new IllegalStateException("Render fractions do not close to one cell");
				}
				for (int missing = missingCount; missing > 0; missing--) {
					int best = -1;
					for (int entry = head[targetCell]; entry >= 0; entry = next[entry]) {
						if (best < 0 || remainder[entry] > remainder[best]
								|| remainder[entry] == remainder[best] && label[entry] < label[best]) best = entry;
					}
					if (best < 0 || entries == 0) throw new IllegalStateException("Cannot normalize render fractions");
					quantized[best]++;
					remainder[best] = -1.0;
				}
			}
			for (int entry = 0; entry < count; entry++) fields[label[entry]][cell[entry]] = (byte) quantized[entry];
		}
	}
}
