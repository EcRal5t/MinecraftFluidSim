package cc.ecisr.mmm.fluid;


import cc.ecisr.mmm.units.SignedDyadic;

/** Exact inventory, default-path handoff continuity and independent transport convergence. */
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

	private static FluidDomain representationRemapDomain(int nx, int ny, int nz, boolean fill) {
		var domain = new FluidDomain(nx, ny, nz,
				new FluidSolverConfig(.5F, 1000, 0, .05F, 400, 1E-5F, .4F, .002F, .8F), 216);
		domain.bindGranular(cc.ecisr.mmm.granular.BuiltinGranularDefinitions.createRegistry(),
				new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE), BuiltinFluidMaterials.createRegistry());
		long volume = cc.ecisr.mmm.granular.GranularLimits.MAX_PARCEL_VOLUME_SVQ / 2;
		for (double mass : new double[] {2.5625, 2.5}) {
			var group = new cc.ecisr.mmm.granular.GranularTransferLedger.Group(
					cc.ecisr.mmm.granular.BuiltinGranularDefinitions.FINE_SAND, volume, mass, null);
			if (!domain.tryInsertExactGranular(1, 1, 1, new cc.ecisr.mmm.granular.GranularTransferLedger(
					java.util.List.of(group)), 0, true).inserted()) throw new AssertionError("Granular remap setup failed");
		}
		if (fill) {
			domain.granularCoupling.rebuild(domain.granularState.parcels, domain.granularState.deposits);
			long[] solid = domain.granularCoupling.copySolidSvq();
			for (int cell = 0; cell < domain.cellCount; cell++) {
				float amount = (float) (1 - solid[cell] / (double) cc.ecisr.mmm.granular.SolidVolume.SVQ_PER_CELL);
				if (!domain.tryInsertExactMaterial(cell % nx, cell / nx % ny, cell / (nx * ny),
						amount, 1000 * amount, 0, 0).transferred()) throw new AssertionError("Liquid remap setup failed");
			}
		}
		return domain;
	}

	private static SignedDyadic liquidVerticalMassMoment(FluidDomain domain) {
		SignedDyadic moment = SignedDyadic.ZERO;
		for (int marker = 0; marker < domain.particles.count(); marker++) moment = moment.add(
				SignedDyadic.of(domain.unitScale.kilograms(domain.particles.mass(marker)))
						.multiply(SignedDyadic.of(domain.particles.positionY(marker))));
		return moment;
	}

	private static void representationRemapReportsLogicalMoments() {
		var domain = representationRemapDomain(3, 3, 3, true);
		domain.captureAuthoritativeState();
		SignedDyadic before = liquidVerticalMassMoment(domain);
		double mass = domain.totalMass(); float volume = domain.totalVolume();
		// Two stationary mechanical substeps contribute no remap; compaction then contributes exactly once.
		var logical = domain.step(false, 0, false, false, 2);
		domain.captureAuthoritativeState();
		SignedDyadic after = liquidVerticalMassMoment(domain);
		var moments = logical.liquidTransport();
		double delta = after.subtract(before).toDouble();
		double bound = 64 * Math.ulp(Math.max(Math.abs(before.toDouble()), Math.abs(after.toDouble())));
		if (domain.granularState.parcels.count() != 1 || logical.substeps() != 2
				|| domain.totalMass() != mass || domain.totalVolume() != volume
				|| !(delta > .1) || Math.abs(moments.signedMassResidual().y() - delta) > bound
				|| !moments.equals(domain.cellLiquidVolumeTransport.momentAudit.stats())
				|| moments.reconstructedGroups() != 27 || moments.projectedGroups() == 0
				|| Math.abs(moments.gravitationalPotentialErrorJoules(9.81) - 9.81 * delta) > 9.81 * bound)
			throw new AssertionError("Representation remap lost or repeated its observable mass moment error");
		var unchanged = domain.step(false, 0, false, false, 2).liquidTransport();
		if (!unchanged.equals(FluidDomain.LiquidTransportStats.INACTIVE))
			throw new AssertionError("A later unchanged representation replayed an earlier remap audit");
	}

	private static void representationRemapBudgetRejectsPrivateCandidate() {
		var domain = representationRemapDomain(100, 10, 10, false);
		var dryCandidate = new CpuFluidSolverBackend().evaluate(
				FluidStepRequest.pendingPhaseSettlement(0, domain.captureAuthoritativeState()));
		if (!dryCandidate.stats().liquidTransport().equals(FluidDomain.LiquidTransportStats.INACTIVE)
				|| dryCandidate.state().granular().activeParcels().size() != 1)
			throw new AssertionError("A dry granular compaction needlessly searched for liquid displacement");
		if (!domain.tryInsertExactMaterial(1, 1, 1, .25F, 250, 0, 0).transferred())
			throw new AssertionError("Wet displacement budget setup failed");
		var before = domain.captureAuthoritativeState();
		long revision = domain.carrierMutationRevision();
		int count = domain.particles.count();
		SignedDyadic moment = liquidVerticalMassMoment(domain);
		double mass = domain.totalMass(); float volume = domain.totalVolume();
		try {
			new CpuFluidSolverBackend().evaluate(FluidStepRequest.pendingPhaseSettlement(0, before));
			throw new AssertionError("Oversized representation graph bypassed its stage operation budget");
		} catch (FluidSolverBackend.MechanicalWorkBudgetExceededException expected) {
			if (!expected.getMessage().contains("representation-remap")) throw expected;
		}
		var after = domain.captureAuthoritativeState();
		if (domain.carrierMutationRevision() != revision || domain.particles.count() != count
				|| domain.totalMass() != mass || domain.totalVolume() != volume || !moment.equals(liquidVerticalMassMoment(domain))
				|| !before.granular().activeParcels().equals(after.granular().activeParcels())
				|| !before.granular().depositGroups().equals(after.granular().depositGroups()))
			throw new AssertionError("Rejected private remap changed its committed source inventory");
	}

	private static void logicalRemapStatsSumDistinctSubstepsAndTail() {
		var actual = representationRemapDomain(3, 3, 3, false);
		var reference = representationRemapDomain(3, 3, 3, false);
		for (var domain : new FluidDomain[] {actual, reference}) {
			for (int cell = 0; cell < 27; cell++) if (!domain.tryInsertExactMaterial(
					cell % 3, cell / 3 % 3, cell / 9, .5F, 500, 0, 0).transferred())
				throw new AssertionError("Partial liquid accumulation setup failed");
			for (int marker = 0; marker < domain.particles.count(); marker++) domain.particles.setVelocityX(marker, .1F);
			domain.gridDirty = true;
		}
		var logical = actual.step(false, 0, false, false, 2).liquidTransport();
		reference.carrierPropertiesWorkspace.beginStep();
		var expected = FluidDomain.LiquidTransportStats.INACTIVE;
		for (int substep = 0; substep < 2; substep++) {
			var contribution = reference.mechanicalPipeline.substep(.025F, substep, false, null).liquidTransport();
			if (contribution.reconstructedGroups() == 0) throw new AssertionError("Mechanical accumulation control is inactive");
			expected = expected.plus(contribution);
		}
		var tail = reference.granularTransitionStage.step().liquidTransport();
		if (tail.reconstructedGroups() == 0 || !logical.equals(expected.plus(tail)))
			throw new AssertionError("Logical stats omitted or repeated a distinct mechanical or representation remap");
	}

	private static void granularRepresentationRoundingHasExactBudget() {
		var domain = new FluidDomain(3, 3, 3,
				new FluidSolverConfig(.5F, 1000, 0, .05F, 100, 1E-5F, .45F, .002F, .8F), 128);
		var registry = cc.ecisr.mmm.granular.BuiltinGranularDefinitions.createRegistry();
		domain.bindGranular(registry, new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE), BuiltinFluidMaterials.createRegistry());
		int material = registry.materialHandle(cc.ecisr.mmm.granular.BuiltinGranularDefinitions.FINE_SAND);
		var parcel = new cc.ecisr.mmm.granular.GranularParcelStore.Parcel(.875F, .75F, .75F,
				0, 0, 0, 262144, .001, material, 0, 0, (byte) 0);
		domain.granularState.parcels.add(parcel);
		domain.granularCoupling.rebuild(domain.granularState.parcels, domain.granularState.deposits);
		domain.granularCollision.preview(domain.granularState.parcels, registry, .001F);
		domain.granularNextCoupling.rebuild(domain.granularState.parcels, domain.granularState.deposits,
				domain.granularCollision::previewPosition);
		for (var budget : GranularQuantizationError.budget(domain, .001F)) if (!budget.equals(SignedDyadic.ZERO))
			throw new AssertionError("A stationary granular kernel created a rounding budget");
		domain.granularState.parcels.overwrite(0, new cc.ecisr.mmm.granular.GranularParcelStore.Parcel(
				parcel.x(), parcel.y(), parcel.z(), Math.scalb(1F, -20), 0, 0,
				parcel.solidVolumeSvq(), parcel.mass(), material, 0, 0, (byte) 0));
		domain.granularCollision.preview(domain.granularState.parcels, registry, .001F);
		domain.granularNextCoupling.rebuild(domain.granularState.parcels, domain.granularState.deposits,
				domain.granularCollision::previewPosition);
		if (domain.granularCollision.previewPosition(0, parcel).x() != parcel.x())
			throw new AssertionError("Sub-ULP fixture moved its stored float endpoint");
		var budget = GranularQuantizationError.budget(domain, .001F);
		// N=2^18 SVQ, h=.5, v=2^-20: normalized kernel error in cell volume is dt*2^-25.
		var time = SignedDyadic.of(.001F);
		var expected = new SignedDyadic(time.significand(), time.exponent() - 25);
		SignedDyadic total = SignedDyadic.ZERO;
		for (var error : budget) total = total.add(error);
		if (!budget[domain.cellIndex(1, 1, 1)].equals(expected)
				|| !budget[domain.cellIndex(2, 1, 1)].equals(expected.negate()) || !total.equals(SignedDyadic.ZERO))
			throw new AssertionError("Lost sub-ULP motion did not retain its exact signed, conservative kernel error");
	}

	private static void representationRepairReusesOriginalDonorSupply() {
		for (int amount : new int[] {1, 2}) for (int repair = 1; repair <= amount; repair++) {
			var flux = new ConservativeLiquidFlux(3, 1, 1);
			flux.geometry(new LiquidFluxGeometry(new FlipCutCellGeometry(3, 1, 1)));
			flux.carrierShares(new int[] {7, 2, 0});
			SignedDyadic unit = SignedDyadic.of(1, -24), original = SignedDyadic.of(amount, -24), correction = SignedDyadic.of(repair, -24);
			SignedDyadic[] volume = {unit, original, SignedDyadic.ZERO};
			flux.velocity[0] = -1; flux.velocity[1] = 1;
			flux.exactFlux[0] = original; flux.exactFlux[1] = SignedDyadic.ZERO;
			try {
				var capacities = (SignedDyadic[]) privateFluxField(flux, "exactCapacity");
				capacities[0] = unit.add(original).subtract(correction); capacities[1] = SignedDyadic.ZERO; capacities[2] = original;
				var field = ConservativeLiquidFlux.class.getDeclaredField("quantizationBudget"); field.setAccessible(true);
				field.set(flux, new SignedDyadic[] {correction, SignedDyadic.ZERO, SignedDyadic.ZERO});
				var initialize = ConservativeLiquidFlux.class.getDeclaredMethod("initializeSharedFluxes", SignedDyadic[].class);
				initialize.setAccessible(true); initialize.invoke(flux, (Object) volume);
				var apply = ConservativeLiquidFlux.class.getDeclaredMethod("repairQuantization", int.class, SignedDyadic[].class, boolean[].class);
				apply.setAccessible(true);
				boolean accepted = (boolean) apply.invoke(flux, 0, volume, new boolean[3]);
				if (accepted != (repair == amount)) throw new AssertionError("Chemical outgoing slots must require complete cancellation of the old face");
				if (accepted) {
					var after = flux.resultingVolumes();
					if (!after[0].equals(unit) || !after[1].equals(SignedDyadic.ZERO) || !after[2].equals(original)
							|| !flux.exactFlux[0].equals(SignedDyadic.ZERO) || !flux.exactFlux[1].equals(original)
							|| flux.representationBlend(1) != 1)
						throw new AssertionError("Reverse cancellation failed to reassign the same original donor inventory");
				} else if (!flux.exactFlux[0].equals(original) || !flux.exactFlux[1].equals(SignedDyadic.ZERO))
					throw new AssertionError("A rejected partial cancellation changed the candidate flux");
			} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
		}
	}

	private static Object privateFluxField(ConservativeLiquidFlux flux, String name) throws ReflectiveOperationException {
		var field = ConservativeLiquidFlux.class.getDeclaredField(name); field.setAccessible(true); return field.get(flux);
	}

	private static void liquidFaceFloorHasExactBudget() {
		double quantum = ConservativeMarkerSampling.VOLUME_FLUX_RESOLUTION;
		// Continuous outward 3.5Q + 0.5Q fits the 4Q capacity reduction; integer faces lose exactly Q.
		for (int mode = 0; mode < 3; mode++) {
			var flux = new ConservativeLiquidFlux(3, 1, 1);
			flux.geometry(new LiquidFluxGeometry(new FlipCutCellGeometry(3, 1, 1)));
			if (mode == 2) flux.carrierShares(new int[] {7, 1, 7});
			flux.velocity[0] = flux.flux[0] = -3.5 * quantum;
			flux.velocity[1] = flux.flux[1] = .5 * quantum;
			SignedDyadic[] original = {SignedDyadic.ZERO, SignedDyadic.of(1), SignedDyadic.ZERO};
			try {
				java.util.Arrays.fill((double[]) privateFluxField(flux, "aperture"), 1);
				((double[]) privateFluxField(flux, "outgoing"))[1] = 4 * quantum;
				var quantize = ConservativeLiquidFlux.class.getDeclaredMethod("quantizeDonorSupply", double[].class, double.class);
				quantize.setAccessible(true); quantize.invoke(flux, new double[] {0, 1, 0}, 1.0);
				var losses = (SignedDyadic[]) privateFluxField(flux, "faceFloorLoss");
				if (!losses[0].equals(SignedDyadic.of(.5 * quantum)) || !losses[1].equals(losses[0]))
					throw new AssertionError("Accepted face floor loss differs from its exact binary64 amount");
				if (mode == 2) {
					var chemical = ConservativeLiquidFlux.class.getDeclaredMethod("limitChemicalShares");
					chemical.setAccessible(true); chemical.invoke(flux);
					if (!losses[0].equals(SignedDyadic.ZERO))
						throw new AssertionError("A chemical whole-face removal was counted as floor rounding");
				}
				var capacities = (SignedDyadic[]) privateFluxField(flux, "exactCapacity");
				capacities[0] = capacities[2] = SignedDyadic.of(1);
				capacities[1] = SignedDyadic.of(1 - (mode == 2 ? .5 : 4 + mode) * quantum);
				var repair = ConservativeLiquidFlux.class.getDeclaredMethod("repairSharedFluxes", SignedDyadic[].class, boolean[].class);
				repair.setAccessible(true);
				boolean accepted = true;
				try { repair.invoke(flux, original, new boolean[3]); }
				catch (java.lang.reflect.InvocationTargetException failure) {
					if (!(failure.getCause() instanceof MechanicalTimeRefinement)) throw failure;
					accepted = false;
				}
				if (accepted != (mode == 0)) throw new AssertionError("Face floor repair bypassed its local error or chemical share limit");
				if (accepted && (!flux.resultingVolumes()[1].equals(capacities[1])
						|| !flux.resultingVolumes()[0].add(flux.resultingVolumes()[1]).add(flux.resultingVolumes()[2]).equals(SignedDyadic.of(1))))
					throw new AssertionError("Pure liquid floor repair changed exact volume or left a capacity excess");
				if (!((SignedDyadic[]) privateFluxField(flux, "upperFlux"))[0].equals(mode == 2 ? SignedDyadic.ZERO : SignedDyadic.of(3 * quantum)))
					throw new AssertionError("Representation repair relaxed the physical velocity allowance");
			} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
		}
	}

	private static void negativeKernelErrorCancelsFaceFloor() {
		var domain = new FluidDomain(130, 3, 3,
				new FluidSolverConfig(.5F, 1000, 0, .05F, 100, 1E-5F, .45F, .002F, .8F), 128);
		var registry = cc.ecisr.mmm.granular.BuiltinGranularDefinitions.createRegistry();
		domain.bindGranular(registry, new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE), BuiltinFluidMaterials.createRegistry());
		int material = registry.materialHandle(cc.ecisr.mmm.granular.BuiltinGranularDefinitions.FINE_SAND);
		domain.granularState.parcels.add(new cc.ecisr.mmm.granular.GranularParcelStore.Parcel(
				64.125F, .75F, .75F, 1, 0, 0, 262144, .001, material, 0, 0, (byte) 0));
		domain.granularCoupling.rebuild(domain.granularState.parcels, domain.granularState.deposits);
		domain.granularCollision.preview(domain.granularState.parcels, registry, .0015F);
		domain.granularNextCoupling.rebuild(domain.granularState.parcels, domain.granularState.deposits, domain.granularCollision::previewPosition);
		int source = domain.cellIndex(127, 1, 1);
		if (GranularQuantizationError.budget(domain, .0015F)[source].compareTo(SignedDyadic.of(-1, -24)) > 0)
			throw new AssertionError("Negative kernel residual was clipped before face-error cancellation");
		var flux = new ConservativeLiquidFlux(130, 3, 3); flux.geometry(new LiquidFluxGeometry(domain));
		try {
			var losses = (SignedDyadic[]) privateFluxField(flux, "faceFloorLoss");
			for (int face = 0; face < flux.left.length; face++) if (flux.left[face] == source && flux.right[face] == source + 1)
				losses[face] = SignedDyadic.of(3, -28);
			var time = ConservativeLiquidFlux.class.getDeclaredField("dtOverH"); time.setAccessible(true); time.setDouble(flux, .003F);
			var combine = ConservativeLiquidFlux.class.getDeclaredMethod("initializeQuantizationBudget"); combine.setAccessible(true); combine.invoke(flux);
			if (!((SignedDyadic[]) privateFluxField(flux, "quantizationBudget"))[source].equals(SignedDyadic.ZERO))
				throw new AssertionError("Positive face loss hid a larger negative kernel error");
		} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}

	private static void jointErrorRoundsOnlyOnce() {
		SignedDyadic unit = SignedDyadic.of(1, -24);
		for (int bits : new int[] {4, 8, 16, 24, 40, 80}) {
			SignedDyadic epsilon = SignedDyadic.of(1, -24 - bits);
			SignedDyadic kernel = unit.subtract(epsilon), face = epsilon.negate();
			SignedDyadic oldBudget = unit.add(face), actualExcess = unit;
			SignedDyadic budget = ConservativeLiquidFlux.representationBudget(kernel, face);
			SignedDyadic completeError = kernel.add(face), envelope = budget.subtract(completeError);
			if (oldBudget.compareTo(actualExcess) >= 0 || !budget.equals(actualExcess)
					|| envelope.signum() < 0 || envelope.compareTo(unit) >= 0)
				throw new AssertionError("An arbitrarily small incoming floor loss rejected an integer joint capacity correction");
		}
		for (int kernelUnits = -40; kernelUnits <= 40; kernelUnits++) for (int faceUnits : new int[] {-7, -1, 0, 1, 7}) {
			SignedDyadic kernel = SignedDyadic.of(kernelUnits, -28), face = SignedDyadic.of(faceUnits, -28);
			SignedDyadic error = kernel.add(face), budget = ConservativeLiquidFlux.representationBudget(kernel, face);
			if (error.signum() <= 0) {
				if (!budget.equals(SignedDyadic.ZERO)) throw new AssertionError("A non-positive joint error authorized representation motion");
			} else if (kernelUnits == 0) {
				if (!budget.equals(face)) throw new AssertionError("Pure liquid floor error gained an SVQ envelope");
			} else if (budget.compareTo(error) < 0 || budget.subtract(error).compareTo(unit) >= 0 || !budget.fitsLongUnits(-24))
				throw new AssertionError("Joint representation envelope exceeds one SVQ or misses its complete error");
		}
	}

	public static void main(String[] args) {
		if (args.length == 1 && args[0].equals("representation")) {
			representationRemapReportsLogicalMoments(); logicalRemapStatsSumDistinctSubstepsAndTail();
			representationRemapBudgetRejectsPrivateCandidate();
			System.out.println("Granular representation remap diagnostics and budget validation passed"); return;
		}
		uniformDensityMassAllocation();
		if (args.length == 1 && args[0].equals("mass")) {
			System.out.println("Liquid mass allocation validation passed"); return;
		}
		granularRepresentationRoundingHasExactBudget();
		representationRepairReusesOriginalDonorSupply();
		liquidFaceFloorHasExactBudget();
		negativeKernelErrorCancelsFaceFloor();
		jointErrorRoundsOnlyOnce();
		representationRemapReportsLogicalMoments();
		logicalRemapStatsSumDistinctSubstepsAndTail();
		representationRemapBudgetRejectsPrivateCandidate();
		if (args.length == 1 && args[0].equals("quantization")) {
			System.out.println("Liquid/granular quantization handoff validation passed"); return;
		}
		if (args.length == 1 && args[0].equals("continuity")) {
			LiquidHandoffContinuityValidation.main(new String[0]);
			return;
		}
		zeroFluxPreservesCarrierColumns();
		balancedPositiveFluxStillReconstructs();
		positiveFluxPreservesFeasibleMoments();
		ballisticArrivalKeepsItsMomentAtHandoff();
		boundedMomentProjectionIsObservable();
		CellRemapMomentsValidation.main(new String[0]);
		CellPolynomialFieldValidation.main(new String[0]);
		LiquidHandoffContinuityValidation.main(new String[0]);
		if (args.length == 1 && args[0].equals("handoff")) {
			System.out.println("Liquid transport handoff validation passed");
			return;
		}
		courantUsesOccupiedDonors();
		allocationMatchesIntegerOracle();
		quadratureAndMassKeepSmallShares();
		chemicalShareCountRespectsSpeciesGroups();
		for (double duration : new double[] {1, 4}) {
			double previousError = Double.POSITIVE_INFINITY, previousReferenceError = 0;
			for (int n : duration == 1 ? new int[] {12, 24, 48} : new int[] {24, 48, 96}) {
				var experiment = new LiquidVolumeTransportValidation(n); int steps = (int) (duration * n * 10);
				long start = System.nanoTime();
				for (int step = 0; step < steps; step++) experiment.step(duration / steps);
				double error = 0, referenceError = 0, peak = 0;
				SignedDyadic inventory = SignedDyadic.ZERO;
				for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
					double reference = referenceFraction(x, y, experiment.h, duration, 128);
					referenceError += Math.abs(reference - referenceFraction(x, y, experiment.h, duration, 64));
					double v = experiment.volume[y * n + x];
					var exact = experiment.exactVolume[y * n + x];
					if (exact.compareTo(SignedDyadic.ZERO) < 0 || exact.compareTo(SignedDyadic.of(1)) > 0)
						throw new AssertionError("Liquid flux exceeded exact cell capacity: " + exact);
					inventory = inventory.add(exact); error += Math.abs(v - reference); peak = Math.max(peak, v);
				}
				if (!inventory.equals(SignedDyadic.of(n * n / 2))) throw new AssertionError("Analytic swirl changed exact liquid inventory");
				double normalizedError = error / (n * n * 0.5), normalizedReferenceError = referenceError / (n * n * 0.5);
				if (!(normalizedError + normalizedReferenceError < previousError - previousReferenceError)
						|| duration == 1 && !(normalizedError < 0.6 * previousError))
					throw new AssertionError("Liquid volume failed spatial convergence beyond reference discretization: time=" + duration
							+ " N=" + n + " L1/V=" + normalizedError + " referenceDelta=" + normalizedReferenceError);
				previousError = normalizedError; previousReferenceError = normalizedReferenceError;
				System.out.printf("geometric flux t=%g N=%d L1/V=%g referenceDelta=%g peak=%g elapsed=%g ms%n",
						duration, n, normalizedError, normalizedReferenceError, peak, (System.nanoTime() - start) / 1e6);
			}
		}
	}

	private static void zeroFluxPreservesCarrierColumns() {
		cc.ecisr.mmm.simulation.FullCapabilityTestFixture.Registries registries;
		try { registries = cc.ecisr.mmm.simulation.FullCapabilityTestFixture.load(); }
		catch (Exception failure) { throw new IllegalStateException("Could not load carrier handoff definitions", failure); }
		for (float amount : new float[] {0.01F, 0.25F}) for (float dt : new float[] {0.05F, 0.00001F}) {
			var config = new FluidSolverConfig(0.5F, 1000, 0, 0.05F, 200, 1e-5F, 0.45F, 0.002F, 0.8F);
			var domain = new FluidDomain(9, 4, 4, config, 512);
			cc.ecisr.mmm.simulation.FullCapabilityTestFixture.bindEmptyMaterialState(domain, registries.liquids());
			domain.setVolume(1, 1, 1, 1); domain.setVolume(2, 1, 1, amount); domain.setVolume(7, 1, 1, 0.01F);
			cc.ecisr.mmm.simulation.FullCapabilityTestFixture.bindFull(domain, registries, 310,
					cc.ecisr.mmm.thermal.ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
			int target = domain.cellIndex(2, 1, 1), remote = domain.cellIndex(7, 1, 1), slot = 0;
			for (int p = 0; p < domain.particles.count(); p++) {
				if (domain.particleCellIndex(p) == target) {
					// The retained rule touches the full cell through its actual left-face liquid slab.
					domain.particles.setPositionX(p, (2.0F + 0.5F * amount) * config.cellSize());
					domain.particles.setPositionY(p, (1.2F + 0.2F * slot) * config.cellSize());
					domain.particles.setPositionZ(p, (1.7F - 0.15F * slot) * config.cellSize());
					domain.particles.setVelocityY(p, (slot + 1) * 0.03F);
					slot++;
				} else if (domain.particleCellIndex(p) == remote) domain.particles.setVelocityX(p, 0.2F);
			}
			domain.rebuildGridFromParticles(); domain.gridToParticleStage.prepareAdvectionCells();
			if (!domain.gridToParticleStage.advectionCells()[target] || domain.gridToParticleStage.advectionCells()[remote])
				throw new AssertionError("Handoff fixture must contain connected and isolated carriers");
			for (float[] field : new float[][] {domain.mac.velocityX, domain.mac.velocityY, domain.mac.velocityZ})
				java.util.Arrays.fill(field, 0);
			var before = carrierColumns(domain, target);
			var volumeBefore = SignedDyadic.sum(domain.particles.directArrays().volume, domain.particles.count());
			double remoteBefore = 0;
			for (int p = 0; p < domain.particles.count(); p++) if (domain.particleCellIndex(p) == remote) remoteBefore = domain.particles.positionX(p);
			new CellLiquidVolumeTransport(domain).advect(dt);
			if (!before.equals(carrierColumns(domain, target)))
				throw new AssertionError("Zero shared flux changed carrier position, velocity, material, species or U: amount=" + amount + " dt=" + dt);
			if (!volumeBefore.equals(SignedDyadic.sum(domain.particles.directArrays().volume, domain.particles.count())))
				throw new AssertionError("Two-path publication changed exact volume");
			boolean remoteMoved = false;
			for (int p = 0; p < domain.particles.count(); p++) if (domain.particleCellIndex(p) == remote)
				remoteMoved |= domain.particles.positionX(p) > remoteBefore;
			if (!remoteMoved) throw new AssertionError("Remote ballistic motion did not trigger common publication");
		}
	}

	private static java.util.List<java.util.List<Object>> carrierColumns(FluidDomain domain, int cell) {
		var result = new java.util.ArrayList<java.util.List<Object>>();
		for (int p = 0; p < domain.particles.count(); p++) if (domain.particleCellIndex(p) == cell) {
			result.add(java.util.List.of(Float.floatToRawIntBits(domain.particles.positionX(p)),
					Float.floatToRawIntBits(domain.particles.positionY(p)), Float.floatToRawIntBits(domain.particles.positionZ(p)),
					Float.floatToRawIntBits(domain.particles.velocityX(p)), Float.floatToRawIntBits(domain.particles.velocityY(p)),
					Float.floatToRawIntBits(domain.particles.velocityZ(p)), Float.floatToRawIntBits(domain.particles.volume(p)),
					Double.doubleToRawLongBits(domain.particles.mass(p)), domain.particles.materialHandle(p), domain.particles.phaseLabel(p),
					domain.chemicalParcels.record(p), domain.chemicalParcels.indicator(p),
					Double.doubleToRawLongBits(domain.thermalState.liquidInternalEnergy(p))));
		}
		result.sort(java.util.Comparator.comparingInt(row -> (Integer) row.getFirst()));
		return result;
	}

	private static void balancedPositiveFluxStillReconstructs() {
		var domain = new FluidDomain(6, 4, 4,
				new FluidSolverConfig(1, 1000, 0, 0.05F, 200, 1e-5F, 0.45F, 0.002F, 0.8F), 256);
		domain.setVolume(1, 1, 1, 1); domain.setVolume(2, 1, 1, 1); domain.setVolume(3, 1, 1, 0.5F);
		int target = domain.cellIndex(2, 1, 1);
		for (int p = 0; p < domain.particles.count(); p++) if (domain.particleCellIndex(p) == target)
			domain.particles.setPositionX(p, domain.particles.positionX(p) + 0.04F);
		domain.rebuildGridFromParticles(); domain.gridToParticleStage.prepareAdvectionCells();
		for (float[] field : new float[][] {domain.mac.velocityX, domain.mac.velocityY, domain.mac.velocityZ}) java.util.Arrays.fill(field, 0);
		domain.mac.velocityX[domain.xFaceIndex(2, 1, 1)] = 0.1F;
		domain.mac.velocityX[domain.xFaceIndex(3, 1, 1)] = 0.1F;
		var volumeBefore = domain.markerGrid.exactCellVolumeInventory[target];
		new CellLiquidVolumeTransport(domain).advect(0.01F);
		domain.ensureInventoryGrid();
		if (!volumeBefore.equals(domain.markerGrid.exactCellVolumeInventory[target]))
			throw new AssertionError("Balanced positive flux changed the target cell inventory");
		int count = 0;
		for (int p = 0; p < domain.particles.count(); p++) if (domain.particleCellIndex(p) == target) {
			float x = domain.particles.positionX(p) - 2;
			if (Math.min(Math.abs(x - 0.25F), Math.abs(x - 0.75F)) > 2 * Math.ulp(2.5F))
				throw new AssertionError("Balanced full-cell flux displaced saturated quadrature: " + x);
			count++;
		}
		if (count != 8) throw new AssertionError("Balanced positive flux lost the full-cell quadrature");
	}

	private static void positiveFluxPreservesFeasibleMoments() {
		for (boolean thermal : new boolean[] {false, true}) for (float dt : new float[] {0.05F, 0.025F}) {
			var domain = new FluidDomain(8, 4, 4,
					new FluidSolverConfig(0.5F, 1000, 0, 0.05F, 200, 1e-5F, 0.45F, 0.002F, 0.8F), 2048);
			if (thermal) {
				try {
					var registries = cc.ecisr.mmm.simulation.FullCapabilityTestFixture.load();
					cc.ecisr.mmm.simulation.FullCapabilityTestFixture.bindEmptyMaterialState(domain, registries.liquids());
					domain.setVolume(2, 1, 1, 0.75F); domain.setVolume(3, 1, 1, 0.25F);
					cc.ecisr.mmm.simulation.FullCapabilityTestFixture.bindFull(domain, registries, 310,
							cc.ecisr.mmm.thermal.ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
				} catch (Exception failure) { throw new IllegalStateException(failure); }
			} else { domain.setVolume(2, 1, 1, 0.75F); domain.setVolume(3, 1, 1, 0.25F); }
			for (int p = 0; p < domain.particles.count(); p++) {
				domain.particles.setPositionX(p, domain.particles.positionX(p) + 0.04F);
				domain.particles.setVelocityX(p, 0.2F);
			}
			double mass = 0, initial = 0;
			for (int p = 0; p < domain.particles.count(); p++) { mass += domain.particles.mass(p); initial += domain.particles.mass(p) * domain.particles.positionX(p); }
			var inventory = SignedDyadic.sum(domain.particles.directArrays().volume, domain.particles.count());
			var transport = new CellLiquidVolumeTransport(domain); int peak = 0;
			for (int step = 1; step <= Math.round(0.5F / dt); step++) {
				domain.rebuildGridFromParticles(); domain.gridToParticleStage.prepareAdvectionCells();
				java.util.Arrays.fill(domain.mac.velocityX, 0.2F);
				java.util.Arrays.fill(domain.mac.velocityY, 0); java.util.Arrays.fill(domain.mac.velocityZ, 0);
				transport.advect(dt); peak = Math.max(peak, domain.particles.count());
				double actual = 0;
				for (int p = 0; p < domain.particles.count(); p++) actual += domain.particles.mass(p) * domain.particles.positionX(p);
				double error = Math.abs((actual - initial) / mass - step * (double) dt * 0.2F);
				if (error > step * 2 * Math.ulp(4.0F)) throw new AssertionError("Feasible uniform transport centroid drift: " + error);
				if (transport.momentAudit.projectedGroups() != 0 || transport.momentAudit.maximumCentroidProjection() != 0)
					throw new AssertionError("Feasible uniform transport unexpectedly projected its moment");
				if (!inventory.equals(SignedDyadic.sum(domain.particles.directArrays().volume, domain.particles.count())))
					throw new AssertionError("Moment reconstruction changed exact volume");
			}
			System.out.printf("positive flux moment thermal=%s dt=%g centroid advances=%g peakMarkers=%d%n", thermal, dt, 0.1, peak);
		}
	}

	private static void boundedMomentProjectionIsObservable() {
		var domain = new FluidDomain(2, 2, 2, FluidSolverConfig.prototypeDefaults(), 64);
		var offsets = new java.util.ArrayList<double[]>(); offsets.add(new double[] {-0.25, 0, 0}); offsets.add(new double[] {0.25, 0, 0});
		double[] projection = new double[3], storage = new double[3];
		CellRemapMoments.fit(domain, 0, offsets, new double[] {0.25, 0.75}, new double[] {0.25, 0, 0}, projection, storage, 0.25);
		double moment = 0.25 * offsets.get(0)[0] + 0.75 * offsets.get(1)[0];
		if (Math.abs(moment - 0.25) > Math.ulp(1.0F) || projection[0] != 0)
			throw new AssertionError("Variable-mass feasible first moment was lost");
		CellRemapMoments.fit(domain, 0, offsets, new double[] {0.25, 0.75}, new double[] {1, 0, 0}, projection, storage, 0.25);
		moment = 0.25 * offsets.get(0)[0] + 0.75 * offsets.get(1)[0];
		if (!(projection[0] < -0.5) || Math.abs(moment - 1 - projection[0] - storage[0]) > 8 * Math.ulp(1.0))
			throw new AssertionError("Unreachable first moment was hidden");
		for (double[] offset : offsets) if (offset[0] < -0.5 || offset[0] >= 0.5)
			throw new AssertionError("Projected marker left its assigned cell");
	}

	private static void ballisticArrivalKeepsItsMomentAtHandoff() {
		var domain = new FluidDomain(10, 4, 4,
				new FluidSolverConfig(0.5F, 1000, 0, 0.05F, 200, 1e-5F, 0.45F, 0.002F, 0.8F), 2048);
		domain.setVolume(1, 1, 1, 0.01F); domain.setVolume(4, 1, 1, 1); domain.setVolume(5, 1, 1, 0.5F);
		int start = domain.cellIndex(1, 1, 1), arrival = domain.cellIndex(4, 1, 1);
		for (int p = 0; p < domain.particles.count(); p++) {
			boolean drop = domain.particleCellIndex(p) == start;
			if (drop) domain.particles.setPositionX(p, 0.995F);
			domain.particles.setVelocityX(p, drop ? 2 : 0.2F);
		}
		domain.rebuildGridFromParticles(); domain.gridToParticleStage.prepareAdvectionCells();
		if (domain.gridToParticleStage.advectionCells()[start]) throw new AssertionError("Arrival fixture must begin on the ballistic path");
		java.util.Arrays.fill(domain.mac.velocityX, 0.2F);
		var inventory = SignedDyadic.sum(domain.particles.directArrays().volume, domain.particles.count());
		var transport = new CellLiquidVolumeTransport(domain); transport.advect(0.6F);
		domain.rebuildGridFromParticles(); domain.gridToParticleStage.prepareAdvectionCells();
		if (!domain.gridToParticleStage.advectionCells()[arrival] || domain.markerGrid.markerCount[arrival] <= 1)
			throw new AssertionError("Ballistic drop did not join the finite-volume support");
		double mass = 0, before = 0;
		for (int p = 0; p < domain.particles.count(); p++) {
			domain.particles.setVelocityX(p, 0.2F);
			mass += domain.particles.mass(p); before += domain.particles.mass(p) * domain.particles.positionX(p);
		}
		transport.advect(0.05F);
		double after = 0;
		for (int p = 0; p < domain.particles.count(); p++) after += domain.particles.mass(p) * domain.particles.positionX(p);
		double drift = Math.abs((after - before) / mass - 0.05F * (double) 0.2F);
		if (drift > 4 * Math.ulp(3.0F)) throw new AssertionError("Ballistic-to-volume handoff introduced centroid drift: " + drift);
		if (!inventory.equals(SignedDyadic.sum(domain.particles.directArrays().volume, domain.particles.count())))
			throw new AssertionError("Ballistic-to-volume handoff changed exact inventory");
		System.out.printf("ballistic-to-volume handoff centroidError=%g m%n", drift);
	}

	private static double referenceFraction(int x, int y, double h, double time, int samples) {
		int inside = 0;
		for (int sy = 0; sy < samples; sy++) for (int sx = 0; sx < samples; sx++) {
			double dx = (x + (sx + 0.5) / samples) * h - 3, dy = (y + (sy + 0.5) / samples) * h - 3;
			double r2 = dx * dx + dy * dy, angle = r2 < 6.25 ? 6 * time * Math.pow(1 - r2 / 6.25, 2) : 0;
			if (dx * Math.cos(angle) + dy * Math.sin(angle) < 0) inside++;
		}
		return (double) inside / (samples * samples);
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

	private static void uniformDensityMassAllocation() {
		if (CellRemapMassAllocation.exactMass(Double.MIN_VALUE, SignedDyadic.of(1.0)) != Double.MIN_VALUE
				|| !Double.isNaN(CellRemapMassAllocation.exactMass(Double.MIN_VALUE, SignedDyadic.of(0.5)))
				|| CellRemapMassAllocation.exactMass(Double.MAX_VALUE, SignedDyadic.of(1.0)) != Double.MAX_VALUE
				|| !Double.isNaN(CellRemapMassAllocation.exactMass(Double.MAX_VALUE, SignedDyadic.of(2.0)))
				|| !Double.isNaN(CellRemapMassAllocation.exactMass(1, SignedDyadic.of((1L << 53) + 1, 0))))
			throw new AssertionError("Exact density product crossed the binary64 precision or exponent range");
		for (double density : new double[] {1000, 2.5}) {
			SignedDyadic[] weights = {SignedDyadic.of(0.1234567F), SignedDyadic.of(0.2345678F), SignedDyadic.of(0.3456789F)};
			SignedDyadic volume = SignedDyadic.ZERO;
			for (SignedDyadic weight : weights) volume = volume.add(weight);
			double mass = CellRemapMassAllocation.exactMass(density, volume);
			double[] parts = CellRemapMassAllocation.allocate(mass, volume, weights, density);
			if (!SignedDyadic.sum(parts).equals(SignedDyadic.of(mass))) throw new AssertionError("Representable density changed exact mass");
			for (int i = 0; i < parts.length; i++) if (parts[i] != density * weights[i].toDouble())
				throw new AssertionError("Representable density changed a mass/volume ratio");
		}
		var domain = new FluidDomain(2, 2, 2, FluidSolverConfig.prototypeDefaults(), 64);
		domain.setVolume(0, 0, 0, 1); domain.rebuildGridFromParticles();
		var ordinals = new java.util.ArrayList<Integer>();
		for (int p = 0; p < domain.particleCount(); p++) ordinals.add(p);
		var volume = domain.markerGrid.exactCellVolumeInventory[0];
		double mass = CellRemapMassAllocation.inventory(domain, ordinals);
		if (!Double.isFinite(CellRemapMassAllocation.density(domain, ordinals, volume, mass)))
			throw new AssertionError("Exact uniform source density was rejected");
		domain.particles.setMass(0, Math.nextUp(domain.particles.mass(0)));
		if (Double.isFinite(CellRemapMassAllocation.density(domain, ordinals, volume, CellRemapMassAllocation.inventory(domain, ordinals))))
			throw new AssertionError("Unequal source density was accepted");
	}

	private static void quadratureAndMassKeepSmallShares() {
		var domain = new FluidDomain(2, 2, 2, FluidSolverConfig.prototypeDefaults(), 64);
		domain.setVolume(0, 0, 0, 1);
		for (int p = 0; p < domain.particleCount(); p++) {
			double x = domain.particles.positionX(p) / domain.config.cellSize() - 0.5, y = domain.particles.positionY(p) / domain.config.cellSize() - 0.5, z = domain.particles.positionZ(p) / domain.config.cellSize() - 0.5;
			domain.particles.setVelocityX(p, (float) (1 + 2 * x + 3 * y + 4 * z + x * y + 2 * x * z + 3 * y * z + 4 * x * y * z));
			domain.particles.setVelocityY(p, (float) (-2 + x - 2 * y + z - 2 * x * y + x * z - y * z + x * y * z));
			domain.particles.setVelocityZ(p, (float) (x + y + z + x * y - x * z + y * z - 2 * x * y * z));
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
			double x = offset[0], y = offset[1], z = offset[2];
			if ((float) marker.velocityX() != (float) (1 + 2 * x + 3 * y + 4 * z + x * y + 2 * x * z + 3 * y * z + 4 * x * y * z)
					|| (float) marker.velocityY() != (float) (-2 + x - 2 * y + z - 2 * x * y + x * z - y * z + x * y * z)
					|| (float) marker.velocityZ() != (float) (x + y + z + x * y - x * z + y * z - 2 * x * y * z))
				throw new AssertionError("Cell quadrature lost a resolved three-dimensional tensor velocity: " + marker);
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
