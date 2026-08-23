package com.example.chemistry;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Bounded, display-only cell projection. Exact AU and residual ledgers never leave the server. */
public final class ChemicalDisplaySnapshot {
	public static final int TOP_SPECIES = 4;
	private final List<SpeciesDisplay> species;
	private final short[] topSpecies;
	private final byte[] topWeights;
	private final byte[] residualWeights;
	private final byte[] ph;
	private final byte[] indicatorFraction;

	private ChemicalDisplaySnapshot(
			List<SpeciesDisplay> species, short[] topSpecies, byte[] topWeights,
			byte[] residualWeights, byte[] ph, byte[] indicatorFraction
	) {
		this.species = List.copyOf(species);
		this.topSpecies = topSpecies.clone();
		this.topWeights = topWeights.clone();
		this.residualWeights = residualWeights.clone();
		this.ph = ph.clone();
		this.indicatorFraction = indicatorFraction.clone();
		int cells = residualWeights.length;
		if (species.isEmpty() || species.size() > ChemicalLimits.MAX_SPECIES
				|| topSpecies.length != cells * TOP_SPECIES || topWeights.length != cells * TOP_SPECIES
				|| ph.length != cells || indicatorFraction.length != cells) {
			throw new IllegalArgumentException("Invalid chemical display field layout");
		}
		for (int cell = 0; cell < cells; cell++) {
			int sum = Byte.toUnsignedInt(residualWeights[cell]);
			for (int slot = 0; slot < TOP_SPECIES; slot++) {
				int index = topSpecies[cell * TOP_SPECIES + slot];
				int weight = Byte.toUnsignedInt(topWeights[cell * TOP_SPECIES + slot]);
				if ((index < 0 && weight != 0) || index >= species.size()) {
					throw new IllegalArgumentException("Invalid chemical display species index");
				}
				sum += weight;
			}
			if (sum != 0 && sum != 255) throw new IllegalArgumentException("Chemical display weights do not close");
		}
	}

	public static ChemicalDisplaySnapshot build(
			ChemicalRegistry registry, ChemicalParcelStore parcels,
			int sizeX, int sizeY, int sizeZ, float cellSize,
			float[] x, float[] y, float[] z, float[] volume, int particleCount
	) {
		int cells = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		if (particleCount < 0 || particleCount > x.length || particleCount > y.length
				|| particleCount > z.length || particleCount > volume.length) {
			throw new IllegalArgumentException("Invalid chemical display marker arrays");
		}
		long[][] totals = new long[cells][registry.species().size()];
		double[] cellVolume = new double[cells], indicatedVolume = new double[cells];
		for (int marker = 0; marker < particleCount; marker++) {
			int cellX = Math.min(sizeX - 1, Math.max(0, (int) Math.floor(x[marker] / cellSize)));
			int cellY = Math.min(sizeY - 1, Math.max(0, (int) Math.floor(y[marker] / cellSize)));
			int cellZ = Math.min(sizeZ - 1, Math.max(0, (int) Math.floor(z[marker] / cellSize)));
			int cell = (cellZ * sizeY + cellY) * sizeX + cellX;
			ChemicalMixtureRecord mixture = parcels.record(marker);
			for (int entry = 0; entry < mixture.entryCount(); entry++) {
				int handle = mixture.speciesHandle(entry);
				totals[cell][handle] = Math.addExact(totals[cell][handle], mixture.amountAt(entry));
			}
			cellVolume[cell] += volume[marker];
			if (parcels.indicator(marker)) indicatedVolume[cell] += volume[marker];
		}
		List<SpeciesDisplay> palette = registry.species().stream()
				.map(species -> new SpeciesDisplay(species.id(), species.baseArgb())).toList();
		short[] topSpecies = new short[cells * TOP_SPECIES]; Arrays.fill(topSpecies, (short) -1);
		byte[] topWeights = new byte[cells * TOP_SPECIES];
		byte[] residual = new byte[cells], ph = new byte[cells], indicator = new byte[cells];
		int acid = registry.speciesHandle(BuiltinChemicalDefinitions.H_PLUS);
		int base = registry.speciesHandle(BuiltinChemicalDefinitions.OH_MINUS);
		double blockScale = (double) cellSize * cellSize * cellSize;
		for (int cell = 0; cell < cells; cell++) {
			long[] cellTotals = totals[cell];
			long total = 0L;
			ArrayList<Integer> present = new ArrayList<>();
			for (int species = 0; species < cellTotals.length; species++) if (cellTotals[species] > 0L) {
				total = Math.addExact(total, cellTotals[species]); present.add(species);
			}
			if (total == 0L) continue;
			present.sort(Comparator.<Integer>comparingLong(species -> cellTotals[species]).reversed()
					.thenComparingInt(Integer::intValue));
			long residualAmount = 0L;
			for (int index = TOP_SPECIES; index < present.size(); index++) {
				residualAmount = Math.addExact(residualAmount, cellTotals[present.get(index)]);
			}
			int bucketCount = Math.min(TOP_SPECIES, present.size()) + 1;
			long[] amounts = new long[bucketCount]; int[] ties = new int[bucketCount];
			for (int slot = 0; slot < bucketCount - 1; slot++) {
				int species = present.get(slot); amounts[slot] = cellTotals[species]; ties[slot] = species;
				topSpecies[cell * TOP_SPECIES + slot] = (short) species;
			}
			amounts[bucketCount - 1] = residualAmount; ties[bucketCount - 1] = Integer.MAX_VALUE;
			int[] quantized = quantize255(amounts, total, ties);
			for (int slot = 0; slot < bucketCount - 1; slot++) topWeights[cell * TOP_SPECIES + slot] = (byte) quantized[slot];
			residual[cell] = (byte) quantized[bucketCount - 1];
			long difference = Math.abs(cellTotals[acid] - cellTotals[base]);
			double strength = difference == 0L ? 0.0 : Math.min(7.0,
					Math.log10(1.0 + difference / (1_000.0 * cellVolume[cell] * blockScale)));
			double value = cellTotals[acid] > cellTotals[base] ? 7.0 - strength
					: cellTotals[base] > cellTotals[acid] ? 7.0 + strength : 7.0;
			ph[cell] = (byte) Math.round(value * 255.0 / 14.0);
			indicator[cell] = (byte) Math.round(255.0 * indicatedVolume[cell] / cellVolume[cell]);
		}
		return new ChemicalDisplaySnapshot(palette, topSpecies, topWeights, residual, ph, indicator);
	}

	private static int[] quantize255(long[] amounts, long total, int[] ties) {
		int[] result = new int[amounts.length]; BigInteger denominator = BigInteger.valueOf(total);
		BigInteger[] remainders = new BigInteger[amounts.length]; int assigned = 0;
		for (int i = 0; i < amounts.length; i++) {
			BigInteger[] division = BigInteger.valueOf(amounts[i]).multiply(BigInteger.valueOf(255L))
					.divideAndRemainder(denominator);
			result[i] = division[0].intValueExact(); remainders[i] = division[1]; assigned += result[i];
		}
		Integer[] order = new Integer[amounts.length]; for (int i = 0; i < order.length; i++) order[i] = i;
		Arrays.sort(order, Comparator.<Integer, BigInteger>comparing(i -> remainders[i]).reversed()
				.thenComparingInt(i -> ties[i]));
		for (int i = 0; i < 255 - assigned; i++) result[order[i]]++;
		return result;
	}

	public List<SpeciesDisplay> species() { return species; }
	public short[] copyTopSpecies() { return topSpecies.clone(); }
	public byte[] copyTopWeights() { return topWeights.clone(); }
	public byte[] copyResidualWeights() { return residualWeights.clone(); }
	public byte[] copyPh() { return ph.clone(); }
	public byte[] copyIndicatorFraction() { return indicatorFraction.clone(); }
	public int cells() { return ph.length; }

	public record SpeciesDisplay(String id, int argb) {
		public SpeciesDisplay {
			if (id == null || id.isBlank()) throw new IllegalArgumentException("Invalid display species id");
		}
	}
}
