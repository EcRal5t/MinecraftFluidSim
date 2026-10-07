package cc.ecisr.mmm.fluid;

import java.util.Arrays;
import cc.ecisr.mmm.units.SignedDyadic;

/** Symmetric diffusion action and conservative heating on a fixed cell/face mesh. */
final class ControlVolumeThermalMetric {
	record Heating(double[] inventoryRates, double reservoir, SignedDyadic[] quantities, SignedDyadic reservoirQuantity, double maximumFluxCorrection) { }
	final LiquidControlVolumeMesh mesh;
	final double[] inventoryDiagonal, faceDiagonal;
	private final int[] inventoryByCell;
	private final ControlVolumeGradient[] geometry;
	private final ControlVolumeDiffusion[] diffusion;
	private final double[] conductivity, gradient = new double[3];
	private final double[][] differences, flux;

	ControlVolumeThermalMetric(LiquidControlVolumeMesh mesh, double[] conductivity) {
		this(mesh, identity(mesh), mesh.cells.size(), conductivity);
	}
	/** A complete caloric owner can occupy several geometric cells while retaining one finite heat capacity. */
	ControlVolumeThermalMetric(LiquidControlVolumeMesh mesh, int[] inventoryByCell, int inventoryCount, double[] conductivity) {
		if (mesh == null || conductivity.length != mesh.cells.size()) throw new IllegalArgumentException("Incomplete heat flux geometry");
		if (inventoryByCell == null || inventoryByCell.length != mesh.cells.size() || inventoryCount < 0)
			throw new IllegalArgumentException("Incomplete caloric control-volume mapping");
		this.mesh = mesh; this.conductivity = conductivity.clone(); this.inventoryByCell = inventoryByCell.clone();
		var represented = new boolean[inventoryCount];
		for (int owner : this.inventoryByCell) {
			if (owner < 0 || owner >= inventoryCount) throw new IllegalArgumentException("Unknown control-volume caloric owner");
			represented[owner] = true;
		}
		for (boolean present : represented) if (!present) throw new IllegalArgumentException("Caloric owner has no geometric support");
		geometry = ControlVolumeGradient.build(mesh.cells, mesh.faces);
		diffusion = Arrays.stream(geometry).map(ControlVolumeDiffusion::new).toArray(ControlVolumeDiffusion[]::new);
		differences = new double[geometry.length][]; flux = differences.clone();
		inventoryDiagonal = new double[inventoryCount]; faceDiagonal = new double[mesh.faces.size()];
		for (int c = 0; c < geometry.length; c++) {
			if (!(Double.isFinite(conductivity[c]) && conductivity[c] >= 0)) throw new IllegalArgumentException("Invalid control-volume conductivity");
			var g = geometry[c]; differences[c] = new double[g.indices.length]; flux[c] = differences[c].clone();
			inventoryDiagonal[this.inventoryByCell[c]] += conductivity[c] * g.basisEnergy(-1);
			for (int i = 0; i < g.indices.length; i++) faceDiagonal[g.indices[i]] += conductivity[c] * g.basisEnergy(i);
		}
		for (var values : new double[][]{inventoryDiagonal, faceDiagonal}) for (double value : values)
			if (!(Double.isFinite(value) && value >= 0)) throw new IllegalArgumentException("Heat flux preconditioning overflow");
	}

	/** Computes the derivative of half the diffusion energy in each caloric inventory and face unknown. */
	double apply(double[] temperature, double[] face, double[] inventoryForce, double[] faceForce) {
		if (temperature.length != inventoryDiagonal.length || face.length != faceDiagonal.length
				|| inventoryForce != null && inventoryForce.length != inventoryDiagonal.length || faceForce.length != face.length)
			throw new IllegalArgumentException("Incomplete thermal diffusion field");
		Arrays.fill(faceForce, 0); if (inventoryForce != null) Arrays.fill(inventoryForce, 0); double energy = 0;
		for (int c = 0; c < geometry.length; c++) {
			if (conductivity[c] == 0) continue;
			var g = geometry[c];
			for (int i = 0; i < g.indices.length; i++) differences[c][i] = face[g.indices[i]] - temperature[inventoryByCell[c]];
			energy += conductivity[c] * diffusion[c].apply(differences[c], flux[c], 1, 0, gradient);
			for (int i = 0; i < g.indices.length; i++) {
				double rate = conductivity[c] * flux[c][i]; faceForce[g.indices[i]] += rate;
				if (inventoryForce != null) inventoryForce[inventoryByCell[c]] -= rate;
			}
		}
		if (!Double.isFinite(energy)) throw new IllegalStateException("Total heat flux diffusion energy overflow");
		return energy;
	}
	int inventory(int cell) { return inventoryByCell[cell]; }
	private static int[] identity(LiquidControlVolumeMesh mesh) {
		if (mesh == null) throw new IllegalArgumentException("Incomplete heat flux geometry");
		return java.util.stream.IntStream.range(0, mesh.cells.size()).toArray();
	}

	/** Uses the last action; internal faces publish one antisymmetric flux, exterior faces use their physical condition. */
	Heating heating(double[] prescribed, double[] residual) {
		if (prescribed.length != faceDiagonal.length || residual.length != prescribed.length)
			throw new IllegalArgumentException("Incomplete heat flux publication");
		var heating = new double[inventoryDiagonal.length]; var firstRate = new double[prescribed.length]; var secondRate = firstRate.clone();
		var quantities = new SignedDyadic[inventoryDiagonal.length]; Arrays.fill(quantities, SignedDyadic.ZERO);
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
				int a = inventoryByCell[patch.first()], b = inventoryByCell[patch.second()];
				quantities[a] = quantities[a].add(quantity); quantities[b] = quantities[b].subtract(quantity);
				var difference = quantity.subtract(first); double observed = Math.abs(difference.toDouble());
				correction = Math.max(correction, difference.signum() == 0 ? 0 : Math.nextUp(observed));
			} else if (!Double.isNaN(prescribed[f])) {
				var quantity = SignedDyadic.of(firstRate[f]);
				int owner = inventoryByCell[patch.first()]; quantities[owner] = quantities[owner].add(quantity); reservoirQuantity = reservoirQuantity.add(quantity);
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
