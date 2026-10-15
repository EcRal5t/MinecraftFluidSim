package mmm.fluid;

import mmm.units.SignedDyadic;
import java.util.Arrays;
import java.util.List;

/** One positive coupling between a component's raw quadrature and its continuous wet geometry. */
final class CellRemapMicroPreferences {
	private final FluidDomain domain;
	private final double[] wet = new double[8];
	private final double wetTotal;
	private final double[][] geometricRatio, donorRatio;
	private final double[] geometricBlend, representationBlend;

	CellRemapMicroPreferences(FluidDomain domain, ConservativeLiquidFlux flux, int cell,
	                         int support, int[] faces, int[] receiverSupports) {
		this.domain = domain;
		int outgoing = faces.length - 1;
		geometricRatio = new double[outgoing][8]; donorRatio = new double[outgoing][8];
		geometricBlend = new double[outgoing]; representationBlend = new double[outgoing];
		if (outgoing == 0) { wetTotal = 0; return; }
		LiquidAffineSweepGeometry.sourceWetMicroVolumes(domain, cell, support, wet);
		double total = 0;
		for (double value : wet) total += value;
		if (!(total > 0 && Double.isFinite(total)))
			throw new IllegalStateException("Liquid source coupling requires a positive wet measure");
		wetTotal = total;
		double[] faceWet = new double[8];
		for (int d = 0; d < outgoing; d++) {
			double[] swept = geometricRatio[d], low = donorRatio[d];
			flux.sweptMicroVolumes(faces[d], support, receiverSupports[d], swept, faceWet);
			boolean phaseLow = flux.lowOrderMicroVolumes(faces[d], support, receiverSupports[d], low);
			if (!phaseLow) {
				int normalBit = 1 << flux.axis[faces[d]];
				for (int m = 0; m < 8; m++)
					low[m] = swept[m] + ((support & 1 << (m ^ normalBit)) != 0 ? swept[m ^ normalBit] : 0) > 0 ? faceWet[m] : 0;
			}
			for (int m = 0; m < 8; m++) {
				if (!(Double.isFinite(swept[m]) && swept[m] >= 0 && Double.isFinite(low[m]) && low[m] >= 0)
						|| (swept[m] > 0 || low[m] > 0) && !(wet[m] > 0))
					throw new IllegalStateException("Liquid source proposal lacks its wet source measure");
				swept[m] = swept[m] == 0 ? 0 : swept[m] / wet[m];
				low[m] = low[m] == 0 ? 0 : low[m] / wet[m];
			}
			geometricBlend[d] = flux.geometricBlend(faces[d]); representationBlend[d] = flux.representationBlend(faces[d]);
		}
	}

	double[][] forSource(List<Integer> original) {
		double[][] result = new double[geometricRatio.length][];
		if (result.length == 0) return result;
		SignedDyadic[] inventory = rawInventory(domain, original);
		double[] raw = new double[8], diagonal = new double[8], rowRest = new double[8], columnRest = new double[8];
		double rawTotal = 0;
		for (int m = 0; m < 8; m++) rawTotal += raw[m] = inventory[m].toDouble();
		if (!(rawTotal > 0 && Double.isFinite(rawTotal)))
			throw new IllegalStateException("Liquid source coupling requires a positive raw measure");
		double remainder = 0;
		for (int m = 0; m < 8; m++) {
			double otherRaw = 0, otherWet = 0;
			for (int k = 0; k < 8; k++) if (k != m) { otherRaw += raw[k]; otherWet += wet[k]; }
			double row = raw[m] / rawTotal;
			double column = wet[m] / wetTotal;
			diagonal[m] = Math.min(row, column);
			// Excluding this item preserves a small unmatched measure beside a dominant one.
			double difference = row * (otherWet / wetTotal) - column * (otherRaw / rawTotal);
			rowRest[m] = Math.max(difference, 0); columnRest[m] = Math.max(-difference, 0);
			remainder += columnRest[m];
		}
		for (int m = 0; m < 8; m++) {
			raw[m] /= rawTotal;
			if (remainder > 0) columnRest[m] /= remainder;
		}
		for (int d = 0; d < result.length; d++) {
			double[] geometricPreference = result[d] = couple(geometricRatio[d], diagonal, rowRest, columnRest);
			double[] donorPreference = couple(donorRatio[d], diagonal, rowRest, columnRest);
			double geometric = 0, donor = 0;
			for (int m = 0; m < 8; m++) { geometric += geometricPreference[m]; donor += donorPreference[m]; }
			double representation = representationBlend[d], available = 1 - representation;
			double blend = geometric == 0 ? 0 : donor == 0 ? available : Math.min(available, geometricBlend[d]);
			for (int m = 0; m < 8; m++)
				result[d][m] = (geometric == 0 ? 0 : blend * geometricPreference[m] / geometric)
						+ (donor == 0 ? 0 : (available - blend) * donorPreference[m] / donor)
						+ representation * raw[m];
		}
		return result;
	}

	/** T = diag + rowRest * residual column probabilities, shared by every outgoing measure. */
	private static double[] couple(double[] ratio, double[] diagonal, double[] rowRest,
	                               double[] columnRest) {
		double[] result = new double[8]; double residual = 0;
		for (int m = 0; m < 8; m++) {
			result[m] = diagonal[m] * ratio[m];
			residual += columnRest[m] * ratio[m];
		}
		for (int m = 0; m < 8; m++)
			result[m] += rowRest[m] * residual;
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
