package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import cc.ecisr.mmm.units.SignedDyadic;

/** Symmetric diffusion action and conservative heating on fixed caloric support. */
final class ControlVolumeThermalMetric {
	/** Conductivity includes the occupied fraction; ordinal -1 explicitly represents a vacuum with zero conductivity. */
	record Portion(int cell, int inventory, double conductivity) {
		Portion {
			if (cell < 0 || inventory < -1 || inventory == -1 && conductivity != 0 || !(Double.isFinite(conductivity) && conductivity >= 0))
				throw new IllegalArgumentException("Invalid caloric support portion");
		}
	}
	private record Contribution(int portion, int local) { }
	record Heating(double[] inventoryRates, double reservoir, SignedDyadic[] quantities, SignedDyadic reservoirQuantity, double maximumFluxCorrection) { }
	final LiquidControlVolumeMesh mesh;
	final double[] inventoryDiagonal, faceDiagonal;
	private final List<Portion> portions;
	private final List<List<Contribution>> contributions;
	private final SignedDyadic[][] balanceWeights;
	private final double[] initialWeights, gradient = new double[3];
	private final ControlVolumeGradient[] geometry;
	private final ControlVolumeDiffusion[] diffusion;
	private final double[][] differences, flux;

	ControlVolumeThermalMetric(LiquidControlVolumeMesh mesh, double[] conductivity) {
		this(mesh, identity(mesh), mesh.cells.size(), conductivity);
	}
	ControlVolumeThermalMetric(LiquidControlVolumeMesh mesh, int[] inventoryByCell, int inventoryCount, double[] conductivity) {
		this(mesh, inventoryCount, mapped(mesh, inventoryByCell, conductivity));
	}
	/** Each caloric owner keeps one finite heat capacity over all of its geometric portions. */
	ControlVolumeThermalMetric(LiquidControlVolumeMesh mesh, int inventoryCount, List<Portion> portions) {
		if (mesh == null || inventoryCount < 0 || portions == null) throw new IllegalArgumentException("Incomplete caloric support");
		this.mesh = mesh; this.portions = List.copyOf(portions);
		var represented = new boolean[inventoryCount]; var occupied = new boolean[mesh.cells.size()];
		var maximum = new double[occupied.length]; var normalization = maximum.clone();
		for (var portion : this.portions) {
			if (portion.cell() >= occupied.length || portion.inventory() >= inventoryCount)
				throw new IllegalArgumentException("Unknown geometric or caloric support ordinal");
			if (portion.inventory() >= 0) represented[portion.inventory()] = true;
			occupied[portion.cell()] = true;
			maximum[portion.cell()] = Math.max(maximum[portion.cell()], portion.conductivity());
		}
		for (boolean present : represented) if (!present) throw new IllegalArgumentException("Caloric owner has no geometric support");
		for (boolean present : occupied) if (!present) throw new IllegalArgumentException("Geometric control volume has no caloric support");
		initialWeights = new double[this.portions.size()];
		for (int p = 0; p < initialWeights.length; p++) {
			var portion = this.portions.get(p); double largest = maximum[portion.cell()];
			initialWeights[p] = largest == 0 ? 1 : portion.conductivity() / largest;
			normalization[portion.cell()] += initialWeights[p];
		}
		for (int p = 0; p < initialWeights.length; p++) initialWeights[p] /= normalization[this.portions.get(p).cell()];
		geometry = ControlVolumeGradient.caloric(mesh.cells, mesh.faces);
		diffusion = Arrays.stream(geometry).map(ControlVolumeDiffusion::new).toArray(ControlVolumeDiffusion[]::new);
		var cellBasisEnergy = new double[geometry.length]; var faceBasisEnergy = new double[geometry.length][];
		for (int c = 0; c < geometry.length; c++) {
			cellBasisEnergy[c] = geometry[c].basisEnergy(-1); faceBasisEnergy[c] = new double[geometry[c].indices.length];
			for (int i = 0; i < faceBasisEnergy[c].length; i++) faceBasisEnergy[c][i] = geometry[c].basisEnergy(i);
		}
		differences = new double[this.portions.size()][]; flux = differences.clone();
		inventoryDiagonal = new double[inventoryCount]; faceDiagonal = new double[mesh.faces.size()];
		var incidence = new ArrayList<ArrayList<Contribution>>();
		for (int f = 0; f < faceDiagonal.length; f++) incidence.add(new ArrayList<>());
		for (int p = 0; p < this.portions.size(); p++) {
			var portion = this.portions.get(p); var g = geometry[portion.cell()]; double k = portion.conductivity();
			differences[p] = new double[g.indices.length]; flux[p] = differences[p].clone();
			if (portion.inventory() >= 0) inventoryDiagonal[portion.inventory()] += k * cellBasisEnergy[portion.cell()];
			for (int i = 0; i < g.indices.length; i++) {
				faceDiagonal[g.indices[i]] += k * faceBasisEnergy[portion.cell()][i]; incidence.get(g.indices[i]).add(new Contribution(p, i));
			}
		}
		for (var values : new double[][]{inventoryDiagonal, faceDiagonal}) for (double value : values)
			if (!(Double.isFinite(value) && value >= 0)) throw new IllegalArgumentException("Heat flux preconditioning overflow");
		contributions = incidence.stream().map(List::copyOf).toList(); balanceWeights = new SignedDyadic[faceDiagonal.length][];
		for (int f = 0; f < faceDiagonal.length; f++) {
			balanceWeights[f] = new SignedDyadic[contributions.get(f).size()];
			for (int q = 0; q < balanceWeights[f].length; q++) {
				var contribution = contributions.get(f).get(q); var portion = this.portions.get(contribution.portion());
				balanceWeights[f][q] = SignedDyadic.of(portion.conductivity())
						.multiply(SignedDyadic.of(faceBasisEnergy[portion.cell()][contribution.local()]));
			}
		}
	}

	/** Derivative of half the sum of each occupied portion's diffusion energy. */
	double apply(double[] temperature, double[] face, double[] inventoryForce, double[] faceForce) {
		if (temperature.length != inventoryDiagonal.length || face.length != faceDiagonal.length
				|| inventoryForce != null && inventoryForce.length != temperature.length || faceForce.length != face.length)
			throw new IllegalArgumentException("Incomplete thermal diffusion field");
		Arrays.fill(faceForce, 0); if (inventoryForce != null) Arrays.fill(inventoryForce, 0); double energy = 0;
		for (int p = 0; p < portions.size(); p++) {
			var portion = portions.get(p); double k = portion.conductivity(); if (k == 0) continue;
			var g = geometry[portion.cell()];
			for (int i = 0; i < g.indices.length; i++) differences[p][i] = face[g.indices[i]] - temperature[portion.inventory()];
			energy += k * diffusion[portion.cell()].apply(differences[p], flux[p], 1, 0, gradient);
			for (int i = 0; i < g.indices.length; i++) {
				double rate = k * flux[p][i]; faceForce[g.indices[i]] += rate;
				if (inventoryForce != null) inventoryForce[portion.inventory()] -= rate;
			}
		}
		if (!Double.isFinite(energy)) throw new IllegalStateException("Total heat flux diffusion energy overflow");
		return energy;
	}
	double[] initialTemperatures(double[] inventories) {
		if (inventories.length != inventoryDiagonal.length) throw new IllegalArgumentException("Incomplete caloric initial temperatures");
		var values = new double[geometry.length];
		for (int p = 0; p < portions.size(); p++) if (portions.get(p).inventory() >= 0)
			values[portions.get(p).cell()] += initialWeights[p] * inventories[portions.get(p).inventory()];
		return values;
	}
	boolean faceHasInventory(int face) {
		for (var contribution : contributions.get(face)) if (portions.get(contribution.portion()).inventory() >= 0) return true;
		return false;
	}
	/** Half an ULP in each absolute temperature gives one ULP in a face-minus-cell difference. */
	double[] faceObservationPrecision(double temperaturePrecision, double dt) {
		var bounds = new double[faceDiagonal.length];
		var local = Arrays.stream(diffusion).map(operator -> operator.observationError(temperaturePrecision)).toList();
		for (var portion : portions) {
			if (portion.conductivity() == 0) continue;
			var g = geometry[portion.cell()]; var error = local.get(portion.cell());
			for (int i = 0; i < g.indices.length; i++) bounds[g.indices[i]] = Math.nextUp(bounds[g.indices[i]]
					+ Math.nextUp(Math.nextUp(dt * portion.conductivity()) * error[i]));
		}
		for (int f = 0; f < bounds.length; f++) bounds[f] = Math.nextUp(bounds[f]);
		return bounds;
	}

	/** Projects the last action's free-face residual using the same positive face-energy coefficients. */
	Heating heating(double[] prescribed) {
		if (prescribed.length != faceDiagonal.length)
			throw new IllegalArgumentException("Incomplete heat flux publication");
		var quantities = new SignedDyadic[inventoryDiagonal.length]; Arrays.fill(quantities, SignedDyadic.ZERO);
		var reservoirQuantity = SignedDyadic.ZERO; double correction = 0;
		for (int f = 0; f < prescribed.length; f++) {
			var support = contributions.get(f); var rates = new SignedDyadic[support.size()]; var total = SignedDyadic.ZERO;
			for (int q = 0; q < rates.length; q++) {
				var contribution = support.get(q); var portion = portions.get(contribution.portion());
				rates[q] = SignedDyadic.of(portion.conductivity() * flux[contribution.portion()][contribution.local()]); total = total.add(rates[q]);
			}
			boolean reservoir = mesh.faces.get(f).second() < 0 && !Double.isNaN(prescribed[f]);
			if (reservoir) reservoirQuantity = reservoirQuantity.add(total);
			else if (total.signum() != 0) {
				var shares = ExactVolumeAllocation.allocateContinuous(total.signum() < 0 ? total.negate() : total, balanceWeights[f]).values();
				for (int q = 0; q < rates.length; q++) {
					var delta = total.signum() > 0 ? shares[q].negate() : shares[q]; rates[q] = rates[q].add(delta);
					if (delta.signum() != 0) correction = Math.max(correction, Math.nextUp(Math.abs(delta.toDouble())));
				}
			}
			for (int q = 0; q < rates.length; q++) {
				int owner = portions.get(support.get(q).portion()).inventory();
				if (owner >= 0) quantities[owner] = quantities[owner].add(rates[q]);
			}
		}
		var heating = new double[quantities.length]; var total = SignedDyadic.ZERO;
		for (int owner = 0; owner < heating.length; owner++) {
			heating[owner] = quantities[owner].toDouble(); total = total.add(quantities[owner]);
			if (!Double.isFinite(heating[owner])) throw new IllegalStateException("Conservative heating overflow");
		}
		if (!total.equals(reservoirQuantity)) throw new IllegalStateException("Shared heat flux lost extensive closure");
		double reservoir = reservoirQuantity.toDouble();
		if (!Double.isFinite(reservoir) || !Double.isFinite(correction)) throw new IllegalStateException("Reservoir or heat flux correction overflow");
		return new Heating(heating, reservoir, quantities, reservoirQuantity, correction);
	}
	private static int[] identity(LiquidControlVolumeMesh mesh) {
		if (mesh == null) throw new IllegalArgumentException("Incomplete heat flux geometry");
		return java.util.stream.IntStream.range(0, mesh.cells.size()).toArray();
	}
	private static List<Portion> mapped(LiquidControlVolumeMesh mesh, int[] inventories, double[] conductivity) {
		if (mesh == null || inventories == null || conductivity == null || inventories.length != mesh.cells.size() || conductivity.length != inventories.length)
			throw new IllegalArgumentException("Incomplete caloric control-volume mapping");
		var portions = new ArrayList<Portion>();
		for (int c = 0; c < inventories.length; c++) portions.add(new Portion(c, inventories[c], conductivity[c]));
		return portions;
	}
}
