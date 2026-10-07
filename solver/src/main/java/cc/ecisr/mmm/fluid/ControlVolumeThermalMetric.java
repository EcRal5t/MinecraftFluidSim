package cc.ecisr.mmm.fluid;

import java.util.Arrays;
import cc.ecisr.mmm.units.SignedDyadic;

/** Symmetric diffusion action and conservative heating on a fixed cell/face mesh. */
final class ControlVolumeThermalMetric {
	record Heating(double[] cells, double reservoir, SignedDyadic[] quantities, SignedDyadic reservoirQuantity, double maximumFluxCorrection) { }
	final LiquidControlVolumeMesh mesh;
	final double[] cellDiagonal, faceDiagonal;
	private final ControlVolumeGradient[] geometry;
	private final ControlVolumeDiffusion[] diffusion;
	private final double[] conductivity, gradient = new double[3];
	private final double[][] differences, flux;

	ControlVolumeThermalMetric(LiquidControlVolumeMesh mesh, double[] conductivity) {
		if (mesh == null || conductivity.length != mesh.cells.size()) throw new IllegalArgumentException("Incomplete heat flux geometry");
		this.mesh = mesh; this.conductivity = conductivity.clone();
		geometry = ControlVolumeGradient.build(mesh.cells, mesh.faces);
		diffusion = Arrays.stream(geometry).map(ControlVolumeDiffusion::new).toArray(ControlVolumeDiffusion[]::new);
		differences = new double[geometry.length][]; flux = differences.clone();
		cellDiagonal = new double[geometry.length]; faceDiagonal = new double[mesh.faces.size()];
		for (int c = 0; c < geometry.length; c++) {
			if (!(Double.isFinite(conductivity[c]) && conductivity[c] >= 0)) throw new IllegalArgumentException("Invalid control-volume conductivity");
			var g = geometry[c]; differences[c] = new double[g.indices.length]; flux[c] = differences[c].clone();
			cellDiagonal[c] = conductivity[c] * g.basisEnergy(-1);
			for (int i = 0; i < g.indices.length; i++) faceDiagonal[g.indices[i]] += conductivity[c] * g.basisEnergy(i);
		}
		for (var values : new double[][]{cellDiagonal, faceDiagonal}) for (double value : values)
			if (!(Double.isFinite(value) && value >= 0)) throw new IllegalArgumentException("Heat flux preconditioning overflow");
	}

	/** Computes the derivative of half the diffusion energy in each cell and face unknown. */
	double apply(double[] temperature, double[] face, double[] cellForce, double[] faceForce) {
		if (temperature.length != geometry.length || face.length != faceDiagonal.length
				|| cellForce != null && cellForce.length != geometry.length || faceForce.length != face.length)
			throw new IllegalArgumentException("Incomplete thermal diffusion field");
		Arrays.fill(faceForce, 0); if (cellForce != null) Arrays.fill(cellForce, 0); double energy = 0;
		for (int c = 0; c < geometry.length; c++) {
			if (conductivity[c] == 0) continue;
			var g = geometry[c];
			for (int i = 0; i < g.indices.length; i++) differences[c][i] = face[g.indices[i]] - temperature[c];
			energy += conductivity[c] * diffusion[c].apply(differences[c], flux[c], 1, 0, gradient);
			for (int i = 0; i < g.indices.length; i++) {
				double rate = conductivity[c] * flux[c][i]; faceForce[g.indices[i]] += rate;
				if (cellForce != null) cellForce[c] -= rate;
			}
		}
		if (!Double.isFinite(energy)) throw new IllegalStateException("Total heat flux diffusion energy overflow");
		return energy;
	}

	/** Uses the last action; internal faces publish one antisymmetric flux, exterior faces use their physical condition. */
	Heating heating(double[] prescribed, double[] residual) {
		if (prescribed.length != faceDiagonal.length || residual.length != prescribed.length)
			throw new IllegalArgumentException("Incomplete heat flux publication");
		var heating = new double[geometry.length]; var firstRate = new double[prescribed.length]; var secondRate = firstRate.clone();
		var quantities = new SignedDyadic[geometry.length]; Arrays.fill(quantities, SignedDyadic.ZERO);
		for (int c = 0; c < geometry.length; c++) for (int i = 0; i < geometry[c].indices.length; i++) {
			int f = geometry[c].indices[i];
			(mesh.faces.get(f).first() == c ? firstRate : secondRate)[f] = conductivity[c] * flux[c][i];
		}
		var reservoirQuantity = SignedDyadic.ZERO; double correction = 0;
		for (int f = 0; f < prescribed.length; f++) {
			var patch = mesh.faces.get(f);
			if (patch.second() >= 0) {
				var first = SignedDyadic.of(firstRate[f]); var second = SignedDyadic.of(secondRate[f]);
				var quantity = first.subtract(second).multiply(SignedDyadic.of(0.5));
				quantities[patch.first()] = quantities[patch.first()].add(quantity); quantities[patch.second()] = quantities[patch.second()].subtract(quantity);
				var difference = quantity.subtract(first); double observed = Math.abs(difference.toDouble());
				correction = Math.max(correction, difference.signum() == 0 ? 0 : Math.nextUp(observed));
			} else if (!Double.isNaN(prescribed[f])) {
				var quantity = SignedDyadic.of(firstRate[f]);
				quantities[patch.first()] = quantities[patch.first()].add(quantity); reservoirQuantity = reservoirQuantity.add(quantity);
			}
			else correction = Math.max(correction, Math.abs(residual[f]));
		}
		var total = SignedDyadic.ZERO;
		for (int c = 0; c < heating.length; c++) {
			heating[c] = quantities[c].toDouble(); total = total.add(quantities[c]);
			if (!Double.isFinite(heating[c])) throw new IllegalStateException("Conservative heating overflow");
		}
		if (!total.equals(reservoirQuantity)) throw new IllegalStateException("Shared heat flux lost extensive closure");
		double reservoir = reservoirQuantity.toDouble();
		if (!Double.isFinite(reservoir) || !Double.isFinite(correction)) throw new IllegalStateException("Reservoir or heat flux correction overflow");
		return new Heating(heating, reservoir, quantities, reservoirQuantity, correction);
	}
}
