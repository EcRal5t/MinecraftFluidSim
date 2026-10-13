package mmm.fluid;

import mmm.units.SignedDyadic;
import java.util.Arrays;
import java.util.List;

/** Supported raw source proposals for the geometric and donor-cell parts of one face plan. */
final class CellRemapMicroPreferences {
	private CellRemapMicroPreferences() { }

	static double[][] create(FluidDomain domain, ConservativeLiquidFlux flux, List<Integer> original,
	                         int support, int[] faces, int[] receiverSupports) {
		SignedDyadic[] inventory = rawInventory(domain, original);
		double[][] result = new double[faces.length - 1][8];
		double[] swept = new double[8], wet = new double[8];
		for (int d = 0; d < result.length; d++) {
			flux.sweptMicroVolumes(faces[d], support, receiverSupports[d], swept, wet);
			int normalBit = 1 << flux.axis[faces[d]];
			double[] donorPreference = new double[8];
			double geometric = 0, donor = 0;
			for (int m = 0; m < 8; m++) if (inventory[m].signum() > 0) {
				int peer = m ^ normalBit;
				double columnSwept = swept[m], columnWet = wet[m];
				if ((support & 1 << peer) != 0) { columnSwept += swept[peer]; columnWet += wet[peer]; }
				if (columnSwept == 0) continue;
				double raw = inventory[m].toDouble(); donorPreference[m] = raw; donor += raw;
				// The geometric proposal uses represented swept microboxes; donor-cell supply uses the column.
				boolean represented = swept[m] > 0 || ((support & 1 << peer) != 0 && inventory[peer].signum() > 0 && swept[peer] > 0);
				double sweptMeasure = represented ? swept[m] : columnSwept, wetMeasure = represented ? wet[m] : columnWet;
				if (sweptMeasure == 0) continue;
				if (!(Double.isFinite(wetMeasure) && wetMeasure > 0 && Double.isFinite(sweptMeasure)))
					throw new IllegalStateException("Positive liquid micro sweep lacks its wet source measure");
				result[d][m] = raw * (sweptMeasure / wetMeasure); geometric += result[d][m];
				if (!(Double.isFinite(result[d][m]) && result[d][m] > 0))
					throw new IllegalStateException("Supported liquid micro preference is unrepresentable");
			}
			if (geometric == 0) continue;
			double blend = flux.geometricBlend(faces[d]);
			for (int m = 0; m < 8; m++) if (donorPreference[m] > 0)
				result[d][m] = blend * result[d][m] / geometric + (1 - blend) * donorPreference[m] / donor;
		}
		return result;
	}

	/** Solid representation changes redistribute pore stock without a time-dependent sweep. */
	static double[][] inventory(FluidDomain domain, List<Integer> original, int outgoing) {
		SignedDyadic[] inventory = rawInventory(domain, original);
		double[][] result = new double[outgoing][8];
		for (double[] share : result) for (int micro = 0; micro < 8; micro++) share[micro] = inventory[micro].toDouble();
		return result;
	}

	private static SignedDyadic[] rawInventory(FluidDomain domain, List<Integer> original) {
		SignedDyadic[] inventory = new SignedDyadic[8]; Arrays.fill(inventory, SignedDyadic.ZERO);
		for (int p : original) {
			int micro = CellRemapMicroTopology.positionMicro(domain.config.cellSize(), domain.particles.positionX(p),
					domain.particles.positionY(p), domain.particles.positionZ(p));
			inventory[micro] = inventory[micro].add(SignedDyadic.of(domain.particles.volume(p)));
		}
		return inventory;
	}
}
