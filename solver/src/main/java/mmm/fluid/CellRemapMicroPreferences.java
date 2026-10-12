package mmm.fluid;

import mmm.units.SignedDyadic;
import java.util.Arrays;
import java.util.List;

/** Supported raw source proposals for the geometric and donor-cell parts of one face plan. */
final class CellRemapMicroPreferences {
	private CellRemapMicroPreferences() { }

	static double[][] create(FluidDomain domain, ConservativeLiquidFlux flux, List<Integer> original,
	                         int support, int[] faces, int[] receiverSupports) {
		SignedDyadic[] inventory = new SignedDyadic[8]; Arrays.fill(inventory, SignedDyadic.ZERO);
		for (int p : original) {
			int micro = CellRemapMicroTopology.positionMicro(domain.config.cellSize(), domain.particles.positionX(p),
					domain.particles.positionY(p), domain.particles.positionZ(p));
			inventory[micro] = inventory[micro].add(SignedDyadic.of(domain.particles.volume(p)));
		}
		double[][] result = new double[faces.length - 1][8];
		double[] swept = new double[8], wet = new double[8];
		for (int d = 0; d < result.length; d++) {
			flux.sweptMicroVolumes(faces[d], support, receiverSupports[d], swept, wet);
			int normalBit = 1 << flux.axis[faces[d]];
			double geometric = 0, donor = 0;
			for (int m = 0; m < 8; m++) if (inventory[m].signum() > 0) {
				int peer = m ^ normalBit;
				// A source node integrates its connected normal column; tangential columns retain
				// the frozen trace selection even when that column's face-side half has no node.
				double columnSwept = swept[m], columnWet = wet[m];
				if ((support & 1 << peer) != 0) { columnSwept += swept[peer]; columnWet += wet[peer]; }
				if (columnSwept == 0) continue;
				if (!(Double.isFinite(columnWet) && columnWet > 0 && Double.isFinite(columnSwept)))
					throw new IllegalStateException("Positive liquid micro sweep lacks its wet source measure");
				double raw = inventory[m].toDouble();
				result[d][m] = raw * (columnSwept / columnWet); geometric += result[d][m]; donor += raw;
				if (!(Double.isFinite(result[d][m]) && result[d][m] > 0))
					throw new IllegalStateException("Supported liquid micro preference is unrepresentable");
			}
			if (geometric == 0) continue;
			double blend = flux.geometricBlend(faces[d]);
			for (int m = 0; m < 8; m++) if (result[d][m] > 0)
				result[d][m] = blend * result[d][m] / geometric + (1 - blend) * inventory[m].toDouble() / donor;
		}
		return result;
	}
}
