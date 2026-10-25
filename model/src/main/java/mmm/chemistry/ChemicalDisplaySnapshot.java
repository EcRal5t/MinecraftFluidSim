package mmm.chemistry;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Bounded, display-only cell projection. Integer μmol and residual ledgers never leave the server. */
public final class ChemicalDisplaySnapshot {
	public static final int TOP_SPECIES = 4;
	public static final int PH_NO_DATA = 255;
	public static final int PH_SCALE = 10;
	private final List<SpeciesDisplay> species;
	private final short[] topSpecies;
	private final byte[] topWeights;
	private final byte[] residualWeights;
	private final byte[] ph;

	private ChemicalDisplaySnapshot(
			List<SpeciesDisplay> species, short[] topSpecies, byte[] topWeights,
			byte[] residualWeights, byte[] ph
	) {
		this.species = List.copyOf(species);
		this.topSpecies = topSpecies.clone();
		this.topWeights = topWeights.clone();
		this.residualWeights = residualWeights.clone();
		this.ph = ph.clone();
		int cells = residualWeights.length;
		if (species.isEmpty() || species.size() > ChemicalLimits.MAX_SPECIES
				|| topSpecies.length != cells * TOP_SPECIES || topWeights.length != cells * TOP_SPECIES
				|| (cells == 0 ? ph.length != 0 : ph.length % cells != 0)) {
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

    public static ChemicalDisplaySnapshot fromDisplayFields(ChemicalRegistry registry,short[] species,byte[] weights,byte[] residual,byte[] ph){
        return new ChemicalDisplaySnapshot(registry.species().stream().map(s->new SpeciesDisplay(s.id(),s.baseArgb())).toList(),species,weights,residual,ph);
    }

	public static ChemicalDisplaySnapshot fromTotals(ChemicalRegistry registry, long[][] totals, byte[] ph) {
		int cells = totals.length;
		List<SpeciesDisplay> palette = registry.species().stream()
				.map(species -> new SpeciesDisplay(species.id(), species.baseArgb())).toList();
		short[] topSpecies = new short[cells * TOP_SPECIES]; Arrays.fill(topSpecies, (short) -1);
		byte[] topWeights = new byte[cells * TOP_SPECIES];
		byte[] residual = new byte[cells];
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

		}
		return new ChemicalDisplaySnapshot(palette, topSpecies, topWeights, residual, ph);
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
	/** Phase-major pH in tenths; 255 denotes no applicable aqueous inventory. */
	public byte[] copyPh() { return ph.clone(); }
	public int cells() { return residualWeights.length; }

	public record SpeciesDisplay(String id, int argb) {
		public SpeciesDisplay {
			if (id == null || id.isBlank()) throw new IllegalArgumentException("Invalid display species id");
		}
	}
}
