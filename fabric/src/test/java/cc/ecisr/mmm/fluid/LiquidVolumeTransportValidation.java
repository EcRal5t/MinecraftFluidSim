package cc.ecisr.mmm.fluid;


import cc.ecisr.mmm.units.SignedDyadic;

/** Analytic incompressible swirl: exact inventory, local capacity and independent mesh convergence. */
public final class LiquidVolumeTransportValidation {
	private final int n;
	private final double h;
	private final double[] volume;
	private final boolean[] solid;
	private final SignedDyadic[] exactVolume;
	private final ConservativeLiquidFlux transport;

	private LiquidVolumeTransportValidation(int n) {
		this.n = n; h = 6.0 / n;
		volume = new double[n * n]; solid = new boolean[n * n]; exactVolume = new SignedDyadic[n * n];
		transport = new ConservativeLiquidFlux(n, n, 1);
		int f = 0;
		for (int y = 0; y < n; y++) for (int x = 1; x < n; x++) {
			transport.velocity[f++] = (stream(x * h, (y + 1) * h) - stream(x * h, y * h)) / h;
		}
		for (int y = 1; y < n; y++) for (int x = 0; x < n; x++) {
			transport.velocity[f++] = (stream(x * h, y * h) - stream((x + 1) * h, y * h)) / h;
		}
		for (int y = 0; y < n; y++) for (int x = 0; x < n / 2; x++) volume[y * n + x] = 1;
		for (int c = 0; c < volume.length; c++) exactVolume[c] = SignedDyadic.of(volume[c]);
	}

	private void step(double dt) {
		transport.plan(volume, solid, dt / h, exactVolume);
		System.arraycopy(transport.resultingVolumes(), 0, exactVolume, 0, volume.length);
		for (int c = 0; c < volume.length; c++) volume[c] = exactVolume[c].toDouble();
	}
	private static double stream(double x, double y) {
		double r2 = (x - 3) * (x - 3) + (y - 3) * (y - 3);
		return r2 >= 6.25 ? 0 : 6.25 * Math.pow(1 - r2 / 6.25, 3);
	}

	public static void main(String[] args) {
		courantUsesOccupiedDonors();
		allocationMatchesIntegerOracle();
		quadratureAndMassKeepSmallShares();
		chemicalShareCountRespectsSpeciesGroups();
		double previousError = Double.POSITIVE_INFINITY;
		for (int n : new int[] {12, 24, 48}) {
			var experiment = new LiquidVolumeTransportValidation(n); int steps = n * 10;
			long start = System.nanoTime();
			for (int s = 0; s < steps; s++) experiment.step(1.0 / steps);
			double error = 0, total = 0, peak = 0;
			for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
				int inside = 0, reference = 128;
				for (int sy = 0; sy < reference; sy++) for (int sx = 0; sx < reference; sx++) {
					double dx = (x + (sx + 0.5) / reference) * experiment.h - 3;
					double dy = (y + (sy + 0.5) / reference) * experiment.h - 3;
					double r2 = dx * dx + dy * dy, angle = r2 < 6.25 ? 6 * Math.pow(1 - r2 / 6.25, 2) : 0;
					if (dx * Math.cos(angle) + dy * Math.sin(angle) < 0) inside++;
				}
				double v = experiment.volume[y * n + x];
				var exact = experiment.exactVolume[y * n + x];
				if (exact.compareTo(SignedDyadic.ZERO) < 0 || exact.compareTo(SignedDyadic.of(1)) > 0)
					throw new AssertionError("Liquid flux exceeded exact cell capacity: " + exact);
				error += Math.abs(v - (double) inside / (reference * reference)); total += v; peak = Math.max(peak, v);
			}
			SignedDyadic inventory = SignedDyadic.ZERO;
			for (SignedDyadic v : experiment.exactVolume) inventory = inventory.add(v);
			if (!inventory.equals(SignedDyadic.of(n * n / 2))) throw new AssertionError("Analytic swirl changed exact liquid inventory");
			double normalizedError = error / (n * n * 0.5);
			if (!(normalizedError < 0.6 * previousError)) throw new AssertionError("Liquid volume failed independent spatial convergence");
			previousError = normalizedError;
			System.out.printf("geometric flux N=%d L1/V=%g peak=%g ledger=%g elapsed=%g ms%n", n, error / (n * n * 0.5), peak, total - n * n * 0.5, (System.nanoTime() - start) / 1e6);
		}
	}

	private static void courantUsesOccupiedDonors() {
		var domain = new FluidDomain(4, 2, 2, FluidSolverConfig.prototypeDefaults(), 64);
		domain.setVolume(0, 0, 0, 1); domain.ensureGrid();
		var transport = new CellLiquidVolumeTransport(domain);
		domain.mac.velocityX[domain.xFaceIndex(3, 0, 0)] = 100;
		domain.mac.velocityX[domain.xFaceIndex(1, 0, 0)] = -100;
		if (transport.courantRate(0) != 0) throw new AssertionError("Empty donor constrained liquid volume transport");
		domain.mac.velocityX[domain.xFaceIndex(1, 0, 0)] = 2;
		if (transport.courantRate(0) != 2.0 / domain.config.cellSize())
			throw new AssertionError("Occupied donor lost its liquid outgoing Courant constraint");
		domain.setVolume(1, 0, 0, 0.125F); domain.setVolume(3, 1, 1, 0.01F); domain.ensureGrid();
		int connected = domain.cellIndex(1, 0, 0), isolated = domain.cellIndex(3, 1, 1);
		if (domain.projection.pressureFluid[connected]) throw new AssertionError("Fringe fixture must lie below its pressure cell center");
		for (int repetition = 0; repetition < 2; repetition++) {
			domain.gridToParticleStage.prepareAdvectionCells();
			if (!domain.gridToParticleStage.advectionCells()[connected] || domain.gridToParticleStage.advectionCells()[isolated])
				throw new AssertionError("Connected fringe and isolated droplet received the same advection classification");
			domain.particleToGridStage.rebuild();
		}
	}

	private static void chemicalShareCountRespectsSpeciesGroups() {
		var definitions = new java.util.ArrayList<cc.ecisr.mmm.chemistry.ChemicalSpecies>();
		for (int species = 0; species < 18; species++) definitions.add(new cc.ecisr.mmm.chemistry.ChemicalSpecies(
				"test:s" + String.format(java.util.Locale.ROOT, "%02d", species), java.util.Map.of("X", 1), 0,
				cc.ecisr.mmm.chemistry.ExactRational.ONE, 0xff000000));
		var chemistry = new cc.ecisr.mmm.chemistry.ChemicalRegistry(definitions, java.util.List.of(
				new cc.ecisr.mmm.chemistry.ChemicalPhaseDefinition("test:phase", "mmm:water",
						definitions.stream().map(cc.ecisr.mmm.chemistry.ChemicalSpecies::id).toList(),
						java.util.Map.of("test:s00", cc.ecisr.mmm.chemistry.ExactRational.ONE), false)), java.util.List.of(
				new cc.ecisr.mmm.chemistry.ChemicalReaction("test:conversion", cc.ecisr.mmm.chemistry.ChemicalReaction.Kind.INSTANTANEOUS,
						"test:phase", java.util.Map.of("test:s00", 1), java.util.Map.of("test:s01", 1), cc.ecisr.mmm.chemistry.ExactRational.ZERO)));
		var domain = new FluidDomain(2, 2, 2, FluidSolverConfig.prototypeDefaults(), 64);
		cc.ecisr.mmm.simulation.FullCapabilityTestFixture.bindEmptyMaterialState(domain,
				new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE.stream()
						.filter(material -> material.id().equals("mmm:water")).toList()));
		domain.setVolume(0, 0, 0, 1); domain.bindChemistry(chemistry);
		if (domain.particles.count() != 8) throw new AssertionError("Chemical grouping fixture requires eight quadrature points");
		int handle = 0;
		for (int marker = 0; marker < 8; marker++) {
			int entries = marker < 5 ? 3 : 1;
			int[] handles = new int[entries]; long[] amounts = new long[entries];
			for (int entry = 0; entry < entries; entry++) { handles[entry] = handle++; amounts[entry] = 1; }
			var mixture = new cc.ecisr.mmm.chemistry.ChemicalMixtureRecord(handles, amounts, 0);
			domain.chemicalParcels.assign(marker, mixture.withMassResidualKilograms(domain.unitScale.kilograms(domain.particles.mass(marker))
					- mixture.exactSpeciesMassKilograms(chemistry).toDouble()), false);
		}
		if (CellCarrierRemapPlan.chemicalShares(domain)[0] != 2)
			throw new AssertionError("Flux share count merged carriers beyond their sixteen-species representation");
		for (int quanta : new int[] {3, 5, 6, 7}) {
			var coarse = new FluidDomain(2, 2, 2, FluidSolverConfig.prototypeDefaults(), 64);
			cc.ecisr.mmm.simulation.FullCapabilityTestFixture.bindEmptyMaterialState(coarse, domain.materialRegistry);
			coarse.setVolume(0, 0, 0, 0.01F); coarse.bindChemistry(chemistry);
			if (coarse.particles.count() != 1) throw new AssertionError("Coarse chemistry fixture requires one carrier");
			coarse.particles.setVolume(0, 1); coarse.particles.setMass(0, 1000);
			var composition = new cc.ecisr.mmm.chemistry.ChemicalMixtureRecord(new int[] {0}, new long[] {quanta}, 0);
			coarse.chemicalParcels.assign(0, composition.withMassResidualKilograms(coarse.unitScale.kilograms(1000)
					- composition.exactSpeciesMassKilograms(chemistry).toDouble()), false);
			coarse.rebuildGridFromParticles();
			var flux = new ConservativeLiquidFlux(2, 2, 2);
			flux.plan(coarse.markerGrid.exactCellMarkerVolume, coarse.geometry.solid, 0,
					coarse.markerGrid.exactCellVolumeInventory);
			var candidate = new CellCarrierRemapPlan(coarse, flux, 0, new BallisticLiquidRemap(8), true);
			if (candidate.markers.size() != Integer.highestOneBit(quanta))
				throw new AssertionError("Chemical quantum count broke the complete orthogonal quadrature");
			var points = new java.util.HashSet<java.util.List<Double>>(); double[] centroid = new double[3];
			for (var marker : candidate.markers) {
				points.add(java.util.List.of(marker.offset(0), marker.offset(1), marker.offset(2)));
				for (int axis = 0; axis < 3; axis++) centroid[axis] += marker.volume() * marker.offset(axis);
			}
			if (points.size() != candidate.markers.size() || centroid[0] != 0 || centroid[1] != 0 || centroid[2] != 0)
				throw new AssertionError("Coarse chemical remap duplicated a quadrature point or displaced the cell centroid");
		}

	}

	private static void quadratureAndMassKeepSmallShares() {
		var domain = new FluidDomain(2, 2, 2, FluidSolverConfig.prototypeDefaults(), 64);
		domain.setVolume(0, 0, 0, 1);
		for (int p = 0; p < domain.particleCount(); p++) {
			double x = domain.particles.positionX(p) / domain.config.cellSize() - 0.5, y = domain.particles.positionY(p) / domain.config.cellSize() - 0.5, z = domain.particles.positionZ(p) / domain.config.cellSize() - 0.5;
			domain.particles.setVelocityX(p, (float) (1 + 2 * x + 3 * y + 4 * z));
			domain.particles.setVelocityY(p, (float) (-2 + x - 2 * y + z));
			domain.particles.setVelocityZ(p, (float) (x + y + z));
		}
		domain.rebuildGridFromParticles(); var transport = new ConservativeLiquidFlux(2, 2, 2);
		transport.plan(domain.markerGrid.exactCellMarkerVolume, domain.geometry.solid, 0, domain.markerGrid.exactCellVolumeInventory);
		var candidate = new CellCarrierRemapPlan(domain, transport, 0, new BallisticLiquidRemap(8), true);
		if (candidate.markers.size() != 8) throw new AssertionError("Full-cell quadrature requires the complete tensor rule");
		double[] firstMoment = new double[3]; double[][] secondMoment = new double[3][3];
		for (var marker : candidate.markers) {
			double[] offset = {marker.offsetX(), marker.offsetY(), marker.offsetZ()};
			for (int a = 0; a < 3; a++) {
				firstMoment[a] += marker.volume() * offset[a];
				for (int b = 0; b < 3; b++) secondMoment[a][b] += marker.volume() * offset[a] * offset[b];
			}
			if ((float) marker.velocityX() != (float) (1 + 2 * offset[0] + 3 * offset[1] + 4 * offset[2])
					|| (float) marker.velocityY() != (float) (-2 + offset[0] - 2 * offset[1] + offset[2])
					|| (float) marker.velocityZ() != (float) (offset[0] + offset[1] + offset[2]))
				throw new AssertionError("Cell quadrature lost a resolved three-dimensional affine velocity: offset=" + java.util.Arrays.toString(offset) + " velocity=" + marker.toString() + " h=" + domain.config.cellSize());
		}
		for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++)
			if (firstMoment[a] != 0 || secondMoment[a][b] != (a == b ? 1.0 / 16 : 0))
				throw new AssertionError("Tensor cell quadrature lost its centered isotropic covariance");

		var tiny = SignedDyadic.of(Math.scalb(1.0F, -60));
		var wide = SignedDyadic.of(0.5).add(tiny);
		var masses = CellRemapMassAllocation.allocate(500, wide, new SignedDyadic[] {SignedDyadic.of(0.5), tiny});
		if (masses[1] != 1000 * tiny.toDouble() || SignedDyadic.sum(masses).toDouble() != 500)
			throw new AssertionError("Mass allocation lost a positive small share or its once-rounded total");
		var expanded = LiquidCellQuadrature.volumes(wide, 4);
		var sum = SignedDyadic.ZERO;
		for (float piece : expanded) { if (!(piece > 0)) throw new AssertionError("Non-positive quadrature weight"); sum = sum.add(SignedDyadic.of(piece)); }
		if (!sum.equals(wide)) throw new AssertionError("Quadrature expansion discarded a small inventory tail");
		for (int units = 33554433; units < 67108864; units += 15485863) {
			var volume = new SignedDyadic(java.math.BigInteger.valueOf(units), -26);
			var weights = LiquidCellQuadrature.volumes(volume, 8);
			if (weights.size() != 8) throw new AssertionError("Common volume lattice generated a redundant microscopic carrier");
			var exact = SignedDyadic.ZERO;
			for (float weight : weights) exact = exact.add(SignedDyadic.of(weight));
			if (!exact.equals(volume)) throw new AssertionError("Common volume lattice changed exact inventory");
		}
	}

	private static void allocationMatchesIntegerOracle() {
		var random = new java.util.Random(987654321);
		for (int trial = 0; trial < 2048; trial++) {
			long amount = trial % 2 == 0 ? random.nextLong(1L << 35) : random.nextLong(Long.MAX_VALUE);
			SignedDyadic[] weights = new SignedDyadic[1 + trial % 8]; int exponent = 0;
			for (int i = 0; i < weights.length; i++) {
				weights[i] = new SignedDyadic(java.math.BigInteger.valueOf(trial % 5 == 0 ? random.nextLong(1, Long.MAX_VALUE / weights.length) : 1 + random.nextInt(100000)),
						trial % 5 == 0 ? 0 : -random.nextInt(trial % 3 == 0 ? 90 : 27));
				exponent = Math.min(exponent, weights[i].exponent());
			}
			long[] actual = ExactVolumeAllocation.allocate(amount, weights);
			var dyadic = ExactVolumeAllocation.allocate(new SignedDyadic(java.math.BigInteger.valueOf(amount), 0), weights);
			var expected = oracle(java.math.BigInteger.valueOf(amount), weights, exponent);
			int dyadicExponent = new SignedDyadic(java.math.BigInteger.valueOf(amount), 0).exponent();
			for (var weight : weights) dyadicExponent = Math.min(dyadicExponent, weight.exponent());
			var expectedDyadic = oracle(java.math.BigInteger.valueOf(amount).shiftLeft(-dyadicExponent), weights, dyadicExponent);
			for (int i = 0; i < weights.length; i++) {
				if (actual[i] != expected[i].longValueExact() || !dyadic[i].equals(new SignedDyadic(expectedDyadic[i], dyadicExponent)))
					throw new AssertionError("Exact allocation trial=" + trial + " amount=" + amount + " weights=" + java.util.Arrays.toString(weights)
						+ " actual=" + java.util.Arrays.toString(actual) + " expected=" + java.util.Arrays.toString(expected)
						+ " dyadic=" + java.util.Arrays.toString(dyadic) + " expectedDyadic=" + java.util.Arrays.toString(expectedDyadic));
			}
		}
		var tied = ExactVolumeAllocation.allocate(2, new SignedDyadic[] {SignedDyadic.of(1), SignedDyadic.of(1), SignedDyadic.of(1)});
		if (!java.util.Arrays.equals(tied, new long[] {1, 1, 0})) throw new AssertionError("Allocation tie order changed");
		for (int count = 1; count <= 8; count++) {
			SignedDyadic[] equal = new SignedDyadic[count + 1]; java.util.Arrays.fill(equal, SignedDyadic.of(3, -100)); equal[count / 2] = SignedDyadic.ZERO;
			for (long amount : new long[] {0, 1, 2, 9, Long.MAX_VALUE}) {
				var expected = oracle(java.math.BigInteger.valueOf(amount), equal, -100);
				long[] actual = ExactVolumeAllocation.allocate(amount, equal);
				for (int i = 0; i < actual.length; i++) if (actual[i] != expected[i].longValueExact())
					throw new AssertionError("Equal-support integer allocation changed exact remainder order");
			}
		}
	}

	private static java.math.BigInteger[] oracle(java.math.BigInteger amount, SignedDyadic[] weights, int exponent) {
		var total = java.math.BigInteger.ZERO;
		var units = new java.math.BigInteger[weights.length];
		for (int i = 0; i < units.length; i++) { units[i] = weights[i].significand().shiftLeft(weights[i].exponent() - exponent); total = total.add(units[i]); }
		var result = new java.math.BigInteger[weights.length]; var remainders = new java.math.BigInteger[weights.length];
		var remaining = amount;
		for (int i = 0; i < result.length; i++) {
			var division = amount.multiply(units[i]).divideAndRemainder(total);
			result[i] = division[0]; remainders[i] = division[1]; remaining = remaining.subtract(result[i]);
		}
		while (remaining.signum() > 0) {
			int largest = 0;
			for (int i = 1; i < result.length; i++) if (remainders[i].compareTo(remainders[largest]) > 0) largest = i;
			result[largest] = result[largest].add(java.math.BigInteger.ONE); remainders[largest] = java.math.BigInteger.valueOf(-1);
			remaining = remaining.subtract(java.math.BigInteger.ONE);
		}
		return result;
	}
}
