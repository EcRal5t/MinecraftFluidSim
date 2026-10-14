package mmm.fluid;


import mmm.units.SignedDyadic;

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
				new FluidSolverConfig(.5F, 1000, 0, .05F, 400, 1E-5F, .4F, .002F, .8F), 8 * nx * ny * nz);
		domain.bindGranular(mmm.granular.BuiltinGranularDefinitions.createRegistry(),
				new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE), BuiltinFluidMaterials.createRegistry());
		long volume = mmm.granular.GranularLimits.MAX_PARCEL_VOLUME_SVQ / 2;
		for (double mass : new double[] {2.5625, 2.5}) {
			var group = new mmm.granular.GranularTransferLedger.Group(
					mmm.granular.BuiltinGranularDefinitions.FINE_SAND, volume, mass, null);
			if (!domain.tryInsertExactGranular(1, 1, 1, new mmm.granular.GranularTransferLedger(
					java.util.List.of(group)), 0, true).inserted()) throw new AssertionError("Granular remap setup failed");
		}
		if (fill) {
			domain.granularCoupling.rebuild(domain.granularState.parcels, domain.granularState.deposits);
			long[] solid = domain.granularCoupling.copySolidSvq();
			for (int cell = 0; cell < domain.cellCount; cell++) {
				float amount = (float) (1 - solid[cell] / (double) mmm.granular.SolidVolume.SVQ_PER_CELL);
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
		for (int[] counts : new int[][]{{1, 2, 2}, {32, 2, 64}, {32, 96, 96}})
			if (CpuFluidSolverBackend.refinedSubsteps(counts[0], new MechanicalTimeRefinement(counts[1])) != counts[2])
				throw new AssertionError("Mechanical refinement repeated an actual CFL-selected step or lost a requested count");
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
				|| Math.abs(delta) > moments.massStorage().y() + bound
				|| Math.abs(moments.signedMassResidual().y() - delta) > bound
				|| !moments.equals(domain.cellLiquidVolumeTransport.momentAudit.stats())
				|| moments.reconstructedGroups() != 27 || moments.projectedGroups() != 0
				|| !moments.massProjection().equals(FluidDomain.Moment3.ZERO)
				|| Math.abs(moments.gravitationalPotentialErrorJoules(9.81) - 9.81 * delta) > 9.81 * bound)
			throw new AssertionError("Representation remap lost or repeated its observable mass moment error: parcels="
					+ domain.granularState.parcels.count() + ", substeps=" + logical.substeps()
					+ ", mass=" + domain.totalMass() + "/" + mass + ", volume=" + domain.totalVolume() + "/" + volume
					+ ", massMomentDelta=" + delta + ", audit=" + moments
					+ ", latestAudit=" + domain.cellLiquidVolumeTransport.momentAudit.stats() + ", bound=" + bound);
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
		var actual = representationRemapDomain(7, 3, 3, false);
		var reference = representationRemapDomain(7, 3, 3, false);
		for (var domain : new FluidDomain[] {actual, reference}) {
			// Separate stationary saturated compaction from the moving partial liquid.
			for (int z = 0; z < 3; z++) for (int y = 0; y < 3; y++) domain.setSolid(3, y, z, true);
			domain.granularCoupling.rebuild(domain.granularState.parcels, domain.granularState.deposits);
			long[] solid = domain.granularCoupling.copySolidSvq();
			for (int cell = 0; cell < domain.cellCount; cell++) {
				int x = cell % 7; if (x == 3) continue;
				float volume = x > 3 ? .5F : (float) (1 - solid[cell] / (double) mmm.granular.SolidVolume.SVQ_PER_CELL);
				if (cell == domain.cellIndex(5, 1, 1)) volume = 1;
				if (!domain.tryInsertExactMaterial(x, cell / 7 % 3, cell / 21, volume, 1000 * volume, 0, 0).transferred())
					throw new AssertionError("Partially filled transport and saturated compaction setup failed");
			}
			for (int marker = 0; marker < domain.particles.count(); marker++)
				if (domain.particleCellIndex(marker) % 7 > 3) domain.particles.setVelocityX(marker, .1F);
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
			throw new AssertionError("Logical stats omitted or repeated a distinct mechanical or representation remap: actual="
					+ logical + ", expected=" + expected + ", tail=" + tail);
	}

	private static void granularRepresentationRoundingHasExactBudget() {
		var domain = new FluidDomain(3, 3, 3,
				new FluidSolverConfig(.5F, 1000, 0, .05F, 100, 1E-5F, .45F, .002F, .8F), 128);
		var registry = mmm.granular.BuiltinGranularDefinitions.createRegistry();
		domain.bindGranular(registry, new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE), BuiltinFluidMaterials.createRegistry());
		int material = registry.materialHandle(mmm.granular.BuiltinGranularDefinitions.FINE_SAND);
		var parcel = new mmm.granular.GranularParcelStore.Parcel(.875F, .75F, .75F,
				0, 0, 0, 262144, .001, material, 0, 0, (byte) 0);
		domain.granularState.parcels.add(parcel);
		domain.granularCoupling.rebuild(domain.granularState.parcels, domain.granularState.deposits);
		domain.granularCollision.preview(domain.granularState.parcels, registry, .001F);
		domain.granularNextCoupling.rebuild(domain.granularState.parcels, domain.granularState.deposits,
				domain.granularCollision::previewPosition);
		for (var budget : GranularQuantizationError.budget(domain, .001F)) if (!budget.equals(SignedDyadic.ZERO))
			throw new AssertionError("A stationary granular kernel created a rounding budget");
		domain.granularState.parcels.overwrite(0, new mmm.granular.GranularParcelStore.Parcel(
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

		var joint = new FluidDomain(3, 3, 3, domain.config, 128);
		byte[] masks = new byte[27]; java.util.Arrays.fill(masks, (byte) 255); masks[12] = masks[13] = 0;
		float leftVolume = 1 - Math.scalb(2F, -24), rightVolume = 1 - Math.scalb(1F, -24);
		joint.restoreMaterialParticleState(new FlipMaterialParticleState(3, 3, 3, .5F, 2,
				(double) leftVolume + rightVolume, 1000 * (double) leftVolume + 1000 * (double) rightVolume,
				BuiltinFluidMaterials.DEFAULT_PALETTE,
				new float[]{.25F, .75F, .75F, leftVolume, .75F, .75F, .75F, rightVolume}, new float[6], masks,
				new double[]{1000 * (double) leftVolume, 1000 * (double) rightVolume}, new int[2]));
		joint.bindGranular(registry, new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE), BuiltinFluidMaterials.createRegistry());
		var chemistry = mmm.chemistry.BuiltinChemicalDefinitions.REGISTRY;
		joint.bindChemistry(chemistry);
		for (int p = 0; p < 2; p++) {
			var mixture = new mmm.chemistry.ChemicalMixtureRecord(new int[]{chemistry.speciesHandle(mmm.chemistry.BuiltinChemicalDefinitions.H2O)}, new long[]{1}, 0);
			joint.chemicalParcels.assign(p, mixture.withMassResidualKilograms(joint.unitScale.kilograms(joint.particles.mass(p))
					- mixture.exactSpeciesMassKilograms(chemistry).toDouble()), false);
		}
		float dt = .05F, x = .5F, vx = Math.ulp(x) / dt;
		joint.granularState.parcels.add(new mmm.granular.GranularParcelStore.Parcel(x, .75F, .75F, vx, 0, 0, 3, .001, material, 0, 0, (byte) 0));
		joint.markGranularExternalChange(12); joint.sealDomainBoundary(); joint.ensureGrid();
		joint.gridToParticleStage.prepareAdvectionCells(); joint.gasTopologyCoordinator.refresh();
		var sourceFrame = joint.gasState.frame;
		var source = joint.granularState.parcels.parcel(0); long revision = joint.granularState.parcels.mutationRevision();
		var sourceLiquid = joint.exportChemicalParticleState(); long[] occupied = joint.granularCoupling.copySolidSvq();
		joint.granularMechanicalStage.previewCollision(dt);
		var endpoint = joint.granularCollision.previewPosition(0, source);
		var endpointKernel = new mmm.granular.GranularKernel(3, 3, 3, .5);
		endpointKernel.capture(endpoint.x(), endpoint.y(), endpoint.z(), 3);
		if (endpoint.x() != Math.nextUp(x) || endpoint.ceilMask() == endpointKernel.canonicalMask()
				|| !joint.granularNextCoupling.matchesSolidSvq(occupied)
				|| joint.granularState.parcels.mutationRevision() != revision || !joint.granularState.parcels.parcel(0).equals(source))
			throw new AssertionError("Full-liquid preview did not select a complete noncanonical feasible allocation without source writes");
		var rejected = joint.granularCollision.planFixedLiquidCapacity(joint.granularState.parcels, joint.granularCoupling, c -> 0);
		if (rejected.status() != mmm.granular.GranularIntegerAllocation.Status.INFEASIBLE || rejected.masks() != null
				|| !joint.granularCollision.previewPosition(0, source).equals(endpoint) || joint.granularState.parcels.mutationRevision() != revision)
			throw new AssertionError("An infeasible fixed-liquid allocation changed the prepared or live state");
		new CellLiquidVolumeTransport(joint).advect(dt);
		joint.granularMechanicalStage.commitCollision();
		var committed = joint.granularState.parcels.parcel(0);
		if (committed.x() != endpoint.x() || committed.ceilMask() != endpoint.ceilMask()
				|| !joint.granularCoupling.matchesSolidSvq(occupied) || joint.particleCount() != 2
				|| sourceFrame == null || joint.gasState.frame == sourceFrame
				|| !joint.gasState.frame.matchesSourceCarrierGeometry(joint, occupied)
				|| !java.util.Arrays.equals(joint.exportChemicalParticleState().materials().particles().copyPositionVolume(),
						sourceLiquid.materials().particles().copyPositionVolume()))
			throw new AssertionError("Joint flux/gas publication lost selected occupancy or moved an indivisible chemical source");
		for (int p = 0; p < 2; p++) if (joint.chemicalParcels.record(p).totalQuanta() != 1
				|| joint.particles.volume(p) != (p == 0 ? leftVolume : rightVolume))
			throw new AssertionError("Joint ceil selection changed liquid volume or indivisible species inventory");
		var captured = joint.captureAuthoritativeState(); var restored = FluidDomain.restoreAuthoritativeState(captured);
		restored.ensureInventoryGrid();
		if (!restored.granularState.parcels.parcel(0).equals(committed) || !restored.granularCoupling.matchesSolidSvq(occupied))
			throw new AssertionError("Joint integer allocation changed its committed capacity interpretation after restore");
		restored.granularMechanicalStage.previewCollision(dt);
		var imposter = new mmm.granular.GranularParcelStore(registry, 1, 3, 3, 3, .5); imposter.add(committed);
		boolean stale = false;
		try { restored.granularCollision.planFixedLiquidCapacity(imposter, restored.granularCoupling, c -> mmm.granular.SolidVolume.SVQ_PER_CELL); }
		catch (IllegalStateException expectedStale) { stale = true; }
		if (!stale || !imposter.parcel(0).equals(committed)) throw new AssertionError("An unrelated granular store reused another source's occupancy preview");
		restored.granularState.parcels.overwrite(0, new mmm.granular.GranularParcelStore.Parcel(committed.x(), committed.y(), committed.z(),
				0, 0, 0, committed.solidVolumeSvq(), committed.mass(), material, 0, committed.contactAge(), committed.flags()));
		var changedSource = restored.granularState.parcels.parcel(0); revision = restored.granularState.parcels.mutationRevision(); stale = false;
		try { restored.granularMechanicalStage.commitCollision(); } catch (IllegalStateException expectedStale) { stale = true; }
		if (!stale || restored.granularState.parcels.mutationRevision() != revision || !restored.granularState.parcels.parcel(0).equals(changedSource))
			throw new AssertionError("A stale occupancy preview overwrote a changed granular source");
		try { restored.granularCollision.preview(restored.granularState.parcels, registry, 0); }
		catch (IllegalArgumentException expectedInvalidTime) { }
		stale = false;
		try { restored.granularMechanicalStage.commitCollision(); } catch (IllegalStateException expectedIncomplete) { stale = true; }
		if (!stale || restored.granularState.parcels.mutationRevision() != revision)
			throw new AssertionError("An incomplete granular collision preview remained publishable");
	}

	private static void representationRepairReusesOriginalDonorSupply() {
		cancelledFluxCanBeAugmentedAgain();
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

	private static void cancelledFluxCanBeAugmentedAgain() {
		var flux = new ConservativeLiquidFlux(2, 1, 1);
		SignedDyadic one = SignedDyadic.of(1), amount = SignedDyadic.of(.125);
		flux.velocity[0] = .5; flux.exactFlux[0] = amount;
		try {
			var capacity = (SignedDyadic[]) privateFluxField(flux, "exactCapacity");
			capacity[0] = one.subtract(amount); capacity[1] = one;
			var initialize = ConservativeLiquidFlux.class.getDeclaredMethod("initializeSharedFluxes", SignedDyadic[].class);
			initialize.setAccessible(true); initialize.invoke(flux, (Object) new SignedDyadic[] {one, SignedDyadic.ZERO});
			((SignedDyadic[]) privateFluxField(flux, "upperFlux"))[0] = SignedDyadic.of(.5);
			var augment = ConservativeLiquidFlux.class.getDeclaredMethod("augmentOutgoing", int.class); augment.setAccessible(true);
			for (int repeat = 0; repeat < 2; repeat++) {
				// A later capacity correction reopens a previously cancelled face transfer.
				flux.exactFlux[0] = SignedDyadic.ZERO;
				flux.resultingVolumes()[0] = one; flux.resultingVolumes()[1] = SignedDyadic.ZERO;
				((SignedDyadic[]) privateFluxField(flux, "remainingSupply"))[0] = one;
				if (!(boolean) augment.invoke(flux, 0) || !flux.exactFlux[0].equals(amount))
					throw new AssertionError("Cancelled face did not reuse its original donor supply");
				int[] head = (int[]) privateFluxField(flux, "incomingHead"), next = (int[]) privateFluxField(flux, "incomingNext");
				int visits = 0;
				for (int face = head[1]; face >= 0; face = next[face]) if (++visits > flux.flux.length)
					throw new AssertionError("Capacity repair duplicated an incoming face and created an adjacency cycle");
				if (visits != 1) throw new AssertionError("Reopened transfer lost its incoming adjacency");
			}
		} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
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
					if (!losses[0].equals(SignedDyadic.ZERO) || !losses[1].equals(SignedDyadic.ZERO))
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
		var registry = mmm.granular.BuiltinGranularDefinitions.createRegistry();
		domain.bindGranular(registry, new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE), BuiltinFluidMaterials.createRegistry());
		int material = registry.materialHandle(mmm.granular.BuiltinGranularDefinitions.FINE_SAND);
		domain.granularState.parcels.add(new mmm.granular.GranularParcelStore.Parcel(
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
		if (args.length > 1 || args.length == 1 && !java.util.List.of("--fast", "chemical-tails", "components",
				"representation", "geometry-remap", "mass", "quantization", "continuity", "handoff", "handoff-short").contains(args[0]))
			throw new IllegalArgumentException("Unknown liquid transport validation entry");
		boolean fast = args.length == 1 && args[0].equals("--fast");
		if (args.length == 1 && args[0].equals("handoff-short")) {
			validateHandoff(); courantUsesOccupiedDonors();
			System.out.println("Liquid transport short handoff validation passed"); return;
		}
		if (args.length == 1 && args[0].equals("geometry-remap")) {
			geometryRemapRechecksMicroCapacity(); chemicalGeometryRemapRejectsUnrepresentableCandidate();
			representationRemapReportsLogicalMoments();
			System.out.println("Zero-duration liquid geometry remap validation passed"); return;
		}
		if (args.length == 1 && args[0].equals("chemical-tails")) {
			chemicalFloatTailsShareExistingQuanta(); chemicalGeometryRemapRejectsUnrepresentableCandidate(); chemicalLazyPortBuildRechecksActualShares();
			System.out.println("Liquid chemical binary32-tail validation passed"); return;
		}
		if (args.length == 1 && args[0].equals("components")) {
			disconnectedMicroComponentsKeepTheirInventory(); microPortsUseTheirOwnBeginningSupply();
			microComponentCapacityIncludesBallisticArrivals(); geometryRemapRechecksMicroCapacity();
			ballisticComponentUsesPublishedFloatPosition();
			System.out.println("Liquid micro-component provenance validation passed"); return;
		}
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
		validateHandoff();
		if (!fast) LiquidHandoffContinuityValidation.main(new String[0]);
		if (args.length == 1 && args[0].equals("handoff")) {
			System.out.println("Liquid transport handoff validation passed");
			return;
		}
		courantUsesOccupiedDonors();
		disconnectedMicroComponentsKeepTheirInventory(); microPortsUseTheirOwnBeginningSupply();
		microComponentCapacityIncludesBallisticArrivals(); geometryRemapRechecksMicroCapacity();
		ballisticComponentUsesPublishedFloatPosition();
		chemicalFloatTailsShareExistingQuanta(); chemicalGeometryRemapRejectsUnrepresentableCandidate(); chemicalLazyPortBuildRechecksActualShares();
		allocationMatchesIntegerOracle();
		quadratureAndMassKeepSmallShares();
		chemicalShareCountRespectsSpeciesGroups();
		if (fast) {
			System.out.println("Liquid transport validation passed (short; continuity trajectories and 1- and 4-second multi-grid swirl skipped)");
			return;
		}
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

	private static void validateHandoff() {
		zeroFluxPreservesCarrierColumns(); balancedPositiveFluxStillReconstructs();
		positiveFluxPreservesFeasibleMoments(); ballisticArrivalKeepsItsMomentAtHandoff();
		boundedMomentProjectionIsObservable(); CellRemapMomentsValidation.main(new String[0]);
		CellPolynomialFieldValidation.main(new String[0]);
	}

	private static void zeroFluxPreservesCarrierColumns() {
		mmm.simulation.FullCapabilityTestFixture.Registries registries;
		try { registries = mmm.simulation.FullCapabilityTestFixture.load(); }
		catch (Exception failure) { throw new IllegalStateException("Could not load carrier handoff definitions", failure); }
		for (float amount : new float[] {0.01F, 0.25F}) for (float dt : new float[] {0.05F, 0.00001F}) {
			var config = new FluidSolverConfig(0.5F, 1000, 0, 0.05F, 200, 1e-5F, 0.45F, 0.002F, 0.8F);
			var domain = new FluidDomain(9, 4, 4, config, 512);
			mmm.simulation.FullCapabilityTestFixture.bindEmptyMaterialState(domain, registries.liquids());
			domain.setVolume(1, 1, 1, 1); domain.setVolume(2, 1, 1, amount); domain.setVolume(7, 1, 1, 0.01F);
			mmm.simulation.FullCapabilityTestFixture.bindFull(domain, registries, 310,
					mmm.thermal.ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
			int target = domain.cellIndex(2, 1, 1), remote = domain.cellIndex(7, 1, 1), slot = 0;
			int targetCount = 0;
			for (int p = 0; p < domain.particleCount(); p++) if (domain.particleCellIndex(p) == target) targetCount++;
			for (int p = 0; p < domain.particles.count(); p++) {
				if (domain.particleCellIndex(p) == target) {
					// The retained rule touches the full cell through its actual left-face liquid slab.
					domain.particles.setPositionX(p, (2.0F + 0.5F * amount) * config.cellSize());
					float fraction = slot / (float) Math.max(1, targetCount - 1);
					domain.particles.setPositionY(p, (1.2F + .6F * fraction) * config.cellSize());
					domain.particles.setPositionZ(p, (1.7F - .4F * fraction) * config.cellSize());
					slot++;
				} else if (domain.particleCellIndex(p) == remote) domain.particles.setVelocityX(p, 0.2F);
			}
			domain.rebuildGridFromParticles(); domain.gridToParticleStage.prepareAdvectionCells();
			if (!domain.gridToParticleStage.advectionCells()[target] || domain.gridToParticleStage.advectionCells()[remote])
				throw new AssertionError("Handoff fixture must contain connected and isolated carriers");
			var trace = domain.pressureSystem.markerVelocityField();
			for (int axis = 0; axis < 3; axis++) for (boolean positive : new boolean[] {false, true}) {
				for (var component : trace.faceTraces(target, axis, positive, domain.sizeX, domain.sizeY)) for (double coefficient : component.q())
					if (coefficient != 0) throw new AssertionError("Zero-flux fixture has a moving donor trace");
			}
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
		for (int p = 0; p < domain.particles.count(); p++) {
			if (domain.particleCellIndex(p) == target)
				domain.particles.setPositionX(p, domain.particles.positionX(p) + 0.04F);
			domain.particles.setVelocityX(p, 0.1F);
		}
		domain.rebuildGridFromParticles(); domain.gridToParticleStage.prepareAdvectionCells();
		var volumeBefore = domain.markerGrid.exactCellVolumeInventory[target];
		var positionsBefore = new java.util.ArrayList<Float>();
		for (int p = 0; p < domain.particles.count(); p++) if (domain.particleCellIndex(p) == target)
			positionsBefore.add(domain.particles.positionX(p));
		var transport = new CellLiquidVolumeTransport(domain); transport.advect(0.01F);
		domain.ensureInventoryGrid();
		if (!volumeBefore.equals(domain.markerGrid.exactCellVolumeInventory[target]))
			throw new AssertionError("Balanced positive flux changed the target cell inventory");
		var positionsAfter = new java.util.ArrayList<Float>();
		for (int p = 0; p < domain.particles.count(); p++) if (domain.particleCellIndex(p) == target) {
			positionsAfter.add(domain.particles.positionX(p));
		}
		positionsBefore.sort(Float::compare); positionsAfter.sort(Float::compare);
		if (positionsAfter.size() != 8) throw new AssertionError("Balanced positive flux lost the full-cell quadrature");
		if (!transport.publishedQuadrature || transport.momentAudit.reconstructedGroups() == 0
				|| positionsBefore.equals(positionsAfter))
			throw new AssertionError("Balanced positive flux retained the unchanged source rule");
	}

	private static void positiveFluxPreservesFeasibleMoments() {
		for (boolean thermal : new boolean[] {false, true}) for (float dt : new float[] {0.05F, 0.025F}) {
			var domain = new FluidDomain(8, 4, 4,
					new FluidSolverConfig(0.5F, 1000, 0, 0.05F, 200, 1e-5F, 0.45F, 0.002F, 0.8F), 2048);
			if (thermal) {
				try {
					var registries = mmm.simulation.FullCapabilityTestFixture.load();
					mmm.simulation.FullCapabilityTestFixture.bindEmptyMaterialState(domain, registries.liquids());
					domain.setVolume(2, 1, 1, 0.75F); domain.setVolume(3, 1, 1, 0.25F);
					mmm.simulation.FullCapabilityTestFixture.bindFull(domain, registries, 310,
							mmm.thermal.ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
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
				transport.advect(dt); peak = Math.max(peak, domain.particles.count());
				double actual = 0;
				for (int p = 0; p < domain.particles.count(); p++) actual += domain.particles.mass(p) * domain.particles.positionX(p);
				double error = Math.abs((actual - initial) / mass - step * (double) dt * 0.2F);
				if (error > step * 2 * Math.ulp(4.0F)) throw new AssertionError("Feasible uniform transport centroid drift: " + error);
				if (transport.momentAudit.projectedGroups() != 0 || transport.momentAudit.maximumCentroidProjection() != 0)
					throw new AssertionError("Feasible uniform transport unexpectedly projected its moment: thermal=" + thermal
							+ " dt=" + dt + " step=" + step + " groups=" + transport.momentAudit.projectedGroups()
							+ " centroidProjection=" + transport.momentAudit.maximumCentroidProjection());
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
		domain.rebuildGridFromParticles(); domain.gridToParticleStage.prepareAdvectionCells();
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
		domain.setVolume(1, 0, 0, 1); domain.ensureGrid(); domain.gridToParticleStage.prepareAdvectionCells();
		var transport = new CellLiquidVolumeTransport(domain);
		domain.mac.velocityX[domain.xFaceIndex(3, 0, 0)] = 100;
		domain.mac.velocityX[domain.xFaceIndex(2, 0, 0)] = -100;
		if (transport.courantRate(0) != 0) throw new AssertionError("Empty donor constrained liquid volume transport");
		for (int p = 0; p < domain.particles.count(); p++) domain.particles.setVelocityX(p, 2);
		domain.rebuildGridFromParticles(); domain.gridToParticleStage.prepareAdvectionCells();
		double expectedRate = 2.0 / domain.config.cellSize(), actualRate = transport.courantRate(0);
		if (actualRate < expectedRate || actualRate - expectedRate > 4 * Math.ulp(expectedRate))
			throw new AssertionError("Occupied donor lost its liquid outgoing Courant constraint");
		domain.setVolume(2, 0, 0, 0.125F); domain.setVolume(3, 1, 1, 0.01F); domain.ensureGrid();
		int connected = domain.cellIndex(2, 0, 0), isolated = domain.cellIndex(3, 1, 1);
		if (domain.projection.pressureFluid[connected]) throw new AssertionError("Fringe fixture must lie below its pressure cell center");
		for (int repetition = 0; repetition < 2; repetition++) {
			domain.gridToParticleStage.prepareAdvectionCells();
			if (!domain.gridToParticleStage.advectionCells()[connected] || domain.gridToParticleStage.advectionCells()[isolated])
				throw new AssertionError("Connected fringe and isolated droplet received the same advection classification");
			domain.particleToGridStage.rebuild();
		}
		double eulerianRate = transport.courantRate(0);
		for (int p = 0; p < domain.particles.count(); p++) if (domain.particleCellIndex(p) == isolated)
			domain.particles.setVelocityX(p, 100);
		domain.rebuildGridFromParticles(); domain.gridToParticleStage.prepareAdvectionCells();
		if (transport.courantRate(0) != eulerianRate)
			throw new AssertionError("Ballistic speed changed the Eulerian donor outflow constraint");
		double ballisticSteps = 100 * (double) domain.config.timeStep()
				/ (domain.config.cellSize() * domain.config.maxCourantNumber());
		if (domain.calculateSubsteps(domain.config.timeStep()) < Math.ceil(ballisticSteps))
			throw new AssertionError("Ballistic motion lost its independent displacement constraint");
	}

	private static void disconnectedMicroComponentsKeepTheirInventory() {
		for (boolean full : new boolean[] {false, true}) for (int quantaPerCarrier : new int[] {0,1,2}) {
			boolean chemical=quantaPerCarrier>0;
			var domain = new FluidDomain(4, 3, 3,
					new FluidSolverConfig(.5F, 1000, 0, .05F, 400, 1e-5F, .4F, .002F, .8F), 256);
			byte[] masks = new byte[36]; java.util.Arrays.fill(masks, (byte) 255);
			masks[16] = masks[17] = 0; masks[18] = 126;
			float[] points = new float[36], velocity = new float[27];
			for (int bit = 0; bit < 8; bit++) {
				points[4 * bit] = .625F + .25F * (bit & 1);
				points[4 * bit + 1] = .625F + .25F * (bit >>> 1 & 1);
				points[4 * bit + 2] = .625F + .25F * (bit >>> 2 & 1);
				points[4 * bit + 3] = .09375F; velocity[3 * bit] = .1F;
			}
			points[32] = full ? 1.125F : 1.0625F; points[33] = points[34] = .625F;
			points[35] = full ? .125F : .0625F; velocity[24] = .1F;
			if (chemical) {
				double[] masses = new double[9]; for (int p = 0; p < 9; p++) masses[p] = 1000 * points[4 * p + 3];
				domain.restoreMaterialParticleState(new FlipMaterialParticleState(4, 3, 3, .5F, 9,
						.75 + points[35], 1000 * (.75 + points[35]), BuiltinFluidMaterials.DEFAULT_PALETTE.stream()
						.filter(material -> material.id().equals("mmm:water")).toList(), points, velocity, masks, masses, new int[9]));
				var species = new mmm.chemistry.ChemicalSpecies("test:solute", java.util.Map.of("X", 1), 0,
						mmm.chemistry.ExactRational.ONE, 0xff000000);
				var product = new mmm.chemistry.ChemicalSpecies("test:product", java.util.Map.of("X", 1), 0,
						mmm.chemistry.ExactRational.ONE, 0xff000000);
				var registry = new mmm.chemistry.ChemicalRegistry(java.util.List.of(species, product), java.util.List.of(
						new mmm.chemistry.ChemicalPhaseDefinition("test:phase", "mmm:water", java.util.List.of(species.id(), product.id()),
								java.util.Map.of(species.id(), mmm.chemistry.ExactRational.ONE), false)), java.util.List.of(
						new mmm.chemistry.ChemicalReaction("test:conversion", mmm.chemistry.ChemicalReaction.Kind.INSTANTANEOUS,
								"test:phase", java.util.Map.of(species.id(), 1), java.util.Map.of(product.id(), 1), mmm.chemistry.ExactRational.ZERO)));
				domain.bindChemistry(registry);
				for (int p = 0; p < 9; p++) {
					var mixture = new mmm.chemistry.ChemicalMixtureRecord(new int[] {0}, new long[] {quantaPerCarrier}, 0);
					domain.chemicalParcels.assign(p, mixture.withMassResidualKilograms(domain.unitScale.kilograms(masses[p])
							- mixture.exactSpeciesMassKilograms(registry).toDouble()), false);
				}
			} else domain.restoreParticleState(new FlipParticleState(4, 3, 3, .5F, 9, .75F + points[35], points, velocity, masks));
			domain.sealDomainBoundary(); double before = domain.totalVolumeExact();
			FluidDomain.StepStats stats;
			try { stats=domain.stepMechanicsOnly(); }
			catch(CellRemapSpeciesAllocation.Unavailable unavailable) {
				if(quantaPerCarrier!=1 || domain.particleCount()!=9 || domain.totalVolumeExact()!=before)throw unavailable;
				for(int p=0;p<9;p++)if(domain.chemicalParcels.record(p).totalQuanta()!=1)throw new AssertionError("Rejected component sampling changed chemical inventory");
				System.out.println("Component chemical sampling rejected its insufficient nine-quanta inventory: "+unavailable.getMessage());
				continue;
			}
			double entered = 0;
			for (int p = 0; p < domain.particleCount(); p++) if (domain.particleCellIndex(p) == 18) {
				if (CellRemapMicroTopology.markerSupport(domain, p) != 1)
					throw new AssertionError("Shared liquid crossed a solid micro wall into the isolated corner");
				entered += domain.particles.volume(p);
			}
			if (domain.totalVolumeExact() != before || entered > .125 || !full && !(entered > .0625)
					|| stats.substeps() > 4) throw new AssertionError("Legal connected liquid arrival failed to advance conservatively: full="
					+ full + ", chemical=" + chemical + ", entered=" + entered + ", volume=" + domain.totalVolumeExact()
					+ ", before=" + before + ", substeps=" + stats.substeps());
		}
	}

	private static void chemicalFloatTailsShareExistingQuanta() {
		for (long quanta : new long[] {2, 3}) for (float speed : new float[] {.001F, .1F}) {
			var domain = lowQuantumTailDomain(speed, quanta); domain.rebuildGridFromParticles();
			var flux=new ConservativeLiquidFlux(4,3,3); flux.geometry(new LiquidFluxGeometry(domain)); flux.plan(domain.markerGrid.exactCellMarkerVolume,domain.geometry.solid,0,domain.markerGrid.exactCellVolumeInventory);
			long proposedUnits=speed==.001F ? 1693 : 169300;
			for(int f=0;f<flux.flux.length;f++)if(flux.left[f]==17 && flux.right[f]==18) {
				flux.velocity[f]=speed; flux.exactFlux[f]=SignedDyadic.of(proposedUnits,-26);
			}
			var ports=new CellRemapPorts(domain,flux,new BallisticLiquidRemap(36),true,null);
			var amount=SignedDyadic.of(proposedUnits,-26); var retained=SignedDyadic.of(.75).subtract(amount);
			if(!CellRemapChemicalSupport.fits(quanta,new SignedDyadic[]{amount,retained}))ports.reduceChemical(17,255,retained,true);
			amount=SignedDyadic.ZERO; for(var port:ports.outgoing(17,255))amount=amount.add(port.volume);
			long expectedUnits = speed == .001F ? quanta == 2 ? 1692 : 1693 : 169300;
			if(!amount.equals(SignedDyadic.of(expectedUnits,-26)) || !CellRemapChemicalSupport.fits(quanta,new SignedDyadic[]{amount,SignedDyadic.of(.75).subtract(amount)}))
				throw new AssertionError("Chemical ports lost the nearest conservative float-representable retained share");
			if(expectedUnits<proposedUnits && CellRemapChemicalSupport.fits(quanta,new SignedDyadic[]{SignedDyadic.of(expectedUnits+1,-26),SignedDyadic.of(.75).subtract(SignedDyadic.of(expectedUnits+1,-26))}))
				throw new AssertionError("Chemical port reduction skipped a closer representable share");
		}
		var first=new CellSpeciesInventory(); first.add(new mmm.chemistry.ChemicalMixtureRecord(new int[]{0},new long[]{3},0));
		var second=new CellSpeciesInventory(); second.add(new mmm.chemistry.ChemicalMixtureRecord(new int[]{1},new long[]{2},0));
		var allocated=CellRemapSpeciesAllocation.allocate(java.util.List.of(
				new CellRemapSpeciesAllocation.Source(first,new SignedDyadic[]{SignedDyadic.of(.1),SignedDyadic.of(.9)},new int[]{1,2}),
				new CellRemapSpeciesAllocation.Source(second,new SignedDyadic[]{SignedDyadic.of(.5),SignedDyadic.of(.5)},new int[]{0,1})),new int[]{2,2,1});
		long[] totals=new long[3]; int[][] targets={{1,2},{0,1}};
		for(int s=0;s<2;s++) {
			long source=0; for(int p=0;p<2;p++)if(allocated[s][p]!=null) {
				if(allocated[s][p].entryCount()!=1 || allocated[s][p].speciesHandle(0)!=s)throw new AssertionError("Chemical allocation changed source species lineage");
				source+=allocated[s][p].totalQuanta(); totals[targets[s][p]]+=allocated[s][p].totalQuanta();
			}
			if(source!=(s==0 ? 3 : 2))throw new AssertionError("Chemical allocation changed source inventory");
		}
		if(!java.util.Arrays.equals(totals,new long[]{2,2,1}))throw new AssertionError("Chemical allocation lost its alternating source path");
		var rule=CellRemapChemicalSupport.quadrature(SignedDyadic.of(.75),8,5,5);
		SignedDyadic ruleVolume=SignedDyadic.ZERO; for(float volume:rule) { if(!(volume>0))throw new AssertionError("Chemical rule contains a nonpositive weight"); ruleVolume=ruleVolume.add(SignedDyadic.of(volume)); }
		if(rule.size()!=5 || !ruleVolume.equals(SignedDyadic.of(.75)))throw new AssertionError("Chemical quadrature discarded a usable fifth node");
		for(long quanta:new long[]{2,3}) {
			var domain=lowQuantumTailDomain(.1F,quanta,(byte)0); domain.rebuildGridFromParticles(); domain.gridToParticleStage.prepareAdvectionCells();
			long revision=domain.carrierMutationRevision(); var composition=new mmm.chemistry.ChemicalMixtureRecord[]{domain.chemicalParcels.record(0),domain.chemicalParcels.record(1)};
			var original=new double[2][8];
			for(int p=0;p<2;p++)original[p]=new double[]{domain.particles.positionX(p),domain.particles.positionY(p),domain.particles.positionZ(p),domain.particles.volume(p),domain.particles.mass(p),domain.particles.velocityX(p),domain.particles.velocityY(p),domain.particles.velocityZ(p)};
			var flux=new ConservativeLiquidFlux(4,3,3); flux.geometry(new LiquidFluxGeometry(domain));
			for(int f=0;f<flux.flux.length;f++)if(flux.left[f]==17 && flux.right[f]==18)flux.velocity[f]=.1F;
			flux.plan(domain.markerGrid.exactCellMarkerVolume,domain.geometry.solid,.05F/domain.config.cellSize(),domain.markerGrid.exactCellVolumeInventory);
			boolean rejected=false;
			try { new CellCarrierRemapPlan(domain,flux,.05F,new BallisticLiquidRemap(36)); }
			catch(CellRemapSpeciesAllocation.Unavailable unavailable) { rejected=true; }
			if(!rejected || domain.particleCount()!=2 || domain.carrierMutationRevision()!=revision)throw new AssertionError("Insufficient chemical nodes did not reject before publication");
			for(int p=0;p<2;p++) {
				var current=new double[]{domain.particles.positionX(p),domain.particles.positionY(p),domain.particles.positionZ(p),domain.particles.volume(p),domain.particles.mass(p),domain.particles.velocityX(p),domain.particles.velocityY(p),domain.particles.velocityZ(p)};
				if(!java.util.Arrays.equals(original[p],current) || !composition[p].equals(domain.chemicalParcels.record(p)))throw new AssertionError("Rejected chemical node allocation changed source columns");
			}
		}
	}

	private static void chemicalGeometryRemapRejectsUnrepresentableCandidate() {
		var domain = lowQuantumTailDomain(0); domain.rebuildGridFromParticles();
		var flux = new ConservativeLiquidFlux(4, 3, 3);
		flux.plan(domain.markerGrid.exactCellMarkerVolume, domain.geometry.solid, 0, domain.markerGrid.exactCellVolumeInventory);
		var capacities = new SignedDyadic[36];
		for (int c = 0; c < 36; c++) capacities[c] = SignedDyadic.of(Integer.bitCount(~domain.geometry.cutCells.mask(c) & 255), -3);
		// This future geometry needs two destinations while the source only has two quanta.
		for (int f = 0; f < flux.flux.length; f++) {
			if (flux.left[f] == 16 && flux.right[f] == 17) { flux.velocity[f] = -1; flux.exactFlux[f] = SignedDyadic.of(.125); }
			if (flux.left[f] == 17 && flux.right[f] == 18) { flux.velocity[f] = 1; flux.exactFlux[f] = SignedDyadic.of(.0625); }
		}
		capacities[17] = SignedDyadic.of(.5625);
		var result = domain.markerGrid.exactCellVolumeInventory.clone();
		result[17] = SignedDyadic.of(.5625); result[16] = result[18] = SignedDyadic.of(.125);
		flux.geometricResult(result, capacities);
		try {
			new CellCarrierRemapPlan(domain, flux, 0, new BallisticLiquidRemap(36), true);
			throw new AssertionError("Geometry remap published more positive chemical carriers than its integer inventory supports");
		} catch (MechanicalTimeRefinement expected) { }
		if (domain.particleCount() != 2 || domain.totalVolumeExact() != .8125
				|| domain.chemicalParcels.record(0).totalQuanta() != 2)
			throw new AssertionError("Rejected chemical geometry candidate changed original inventory");
	}

	private static void chemicalLazyPortBuildRechecksActualShares() {
		var domain = lowQuantumTailDomain(0, 2, (byte) 0); domain.rebuildGridFromParticles();
		if (CellRemapMicroTopology.needed(domain)) throw new AssertionError("Lazy chemical fixture unexpectedly prebuilds micro ports");
		var flux = new ConservativeLiquidFlux(4, 3, 3);
		flux.plan(domain.markerGrid.exactCellMarkerVolume, domain.geometry.solid, 0, domain.markerGrid.exactCellVolumeInventory);
		var capacities = new SignedDyadic[36];
		for (int c = 0; c < 36; c++) capacities[c] = SignedDyadic.of(Integer.bitCount(~domain.geometry.cutCells.mask(c) & 255), -3);
		for (int f = 0; f < flux.flux.length; f++) {
			if (flux.left[f] == 16 && flux.right[f] == 17) { flux.velocity[f] = -1; flux.exactFlux[f] = SignedDyadic.of(.125); }
			if (flux.left[f] == 17 && flux.right[f] == 18) { flux.velocity[f] = 1; flux.exactFlux[f] = SignedDyadic.of(.125); }
		}
		var result = domain.markerGrid.exactCellVolumeInventory.clone();
		result[17] = SignedDyadic.of(.5); result[16] = SignedDyadic.of(.125); result[18] = SignedDyadic.of(.1875);
		flux.geometricResult(result, capacities);
		var candidate = new CellCarrierRemapPlan(domain, flux, 0, new BallisticLiquidRemap(36), true);
		SignedDyadic transferred = SignedDyadic.ZERO, retained = SignedDyadic.ZERO; int positiveFaces = 0; long quanta = 0;
		for (var amount : flux.exactFlux) if (amount.signum() > 0) { transferred = transferred.add(amount); positiveFaces++; }
		for (var marker : candidate.markers) {
			if (marker.cell() == 17) retained = retained.add(SignedDyadic.of(marker.volume()));
			quanta += marker.mixture().totalQuanta();
		}
		if (positiveFaces != 1 || !transferred.equals(SignedDyadic.of(.125)) || !retained.equals(SignedDyadic.of(.625)) || quanta != 3)
			throw new AssertionError("Lazy micro-port construction cancelled an already representable chemical flow using stale shares");
		if (domain.particleCount() != 2 || domain.totalVolumeExact() != .8125 || domain.chemicalParcels.record(0).totalQuanta() != 2)
			throw new AssertionError("Chemical candidate preview changed source inventory");
	}

	private static FluidDomain lowQuantumTailDomain(float speed) {
		return lowQuantumTailDomain(speed, 2);
	}
	private static FluidDomain lowQuantumTailDomain(float speed, long quanta) {
		return lowQuantumTailDomain(speed, quanta, (byte) 126);
	}
	private static FluidDomain lowQuantumTailDomain(float speed, long quanta, byte targetMask) {
		var domain = new FluidDomain(4, 3, 3, new FluidSolverConfig(.5F, 1000, 0, .05F, 400, 1e-5F, .4F, .002F, .8F), 256);
		byte[] masks = new byte[36]; java.util.Arrays.fill(masks, (byte) 255); masks[16] = masks[17] = 0; masks[18] = targetMask;
		domain.restoreMaterialParticleState(new FlipMaterialParticleState(4, 3, 3, .5F, 2, .8125, 812.5,
				BuiltinFluidMaterials.DEFAULT_PALETTE.stream().filter(material -> material.id().equals("mmm:water")).toList(),
				new float[] {.75F, .75F, .75F, .75F, 1.125F, .625F, .625F, .0625F}, new float[] {speed, 0, 0, speed, 0, 0},
				masks, new double[] {750, 62.5}, new int[2]));
		var a = new mmm.chemistry.ChemicalSpecies("test:a", java.util.Map.of("X", 1), 0, mmm.chemistry.ExactRational.ONE, 0xff000000);
		var b = new mmm.chemistry.ChemicalSpecies("test:b", java.util.Map.of("X", 1), 0, mmm.chemistry.ExactRational.ONE, 0xff000000);
		var registry = new mmm.chemistry.ChemicalRegistry(java.util.List.of(a, b), java.util.List.of(
				new mmm.chemistry.ChemicalPhaseDefinition("test:phase", "mmm:water", java.util.List.of(a.id(), b.id()),
						java.util.Map.of(a.id(), mmm.chemistry.ExactRational.ONE), false)), java.util.List.of(
				new mmm.chemistry.ChemicalReaction("test:conversion", mmm.chemistry.ChemicalReaction.Kind.INSTANTANEOUS,
						"test:phase", java.util.Map.of(a.id(), 1), java.util.Map.of(b.id(), 1), mmm.chemistry.ExactRational.ZERO)));
		domain.bindChemistry(registry);
		for (int p = 0; p < 2; p++) {
			var mixture = new mmm.chemistry.ChemicalMixtureRecord(new int[] {0}, new long[] {p == 0 ? quanta : 1}, 0);
			domain.chemicalParcels.assign(p, mixture.withMassResidualKilograms(domain.unitScale.kilograms(domain.particles.mass(p))
					- mixture.exactSpeciesMassKilograms(registry).toDouble()), false);
		}
		domain.sealDomainBoundary(); return domain;
	}

	private static void microPortsUseTheirOwnBeginningSupply() {
		var domain = microPortDomain((byte) 126, (byte) 0, new float[] {.125F, .125F, .125F, .125F});
		var flux = microPortFlux(domain, .0625);
		new CellRemapPorts(domain, flux, new BallisticLiquidRemap(8), false, null);
		if (flux.exactFlux[0].signum() != 0 || !flux.resultingVolumes()[0].equals(SignedDyadic.of(.125)))
			throw new AssertionError("An empty outgoing component borrowed another component's beginning inventory");
		var chemical = microPortDomain((byte) 126, (byte) 0, new float[] {.375F, .375F, .375F, .125F});
		var limited = microPortFlux(chemical, .0625);
		new CellRemapPorts(chemical, limited, new BallisticLiquidRemap(8), false, (cell, support) -> 1);
		if (limited.exactFlux[0].signum() != 0)
			throw new AssertionError("A one-quantum component split retained and outgoing chemical shares");
	}

	private static void microComponentCapacityIncludesBallisticArrivals() {
		var domain = microPortDomain((byte) 0, (byte) 126,
				new float[] {.125F, .125F, .125F, .5F, .625F, .125F, .125F, .125F});
		var flux = microPortFlux(domain, .0625);
		var independent = new BallisticLiquidRemap(8);
		independent.markers.add(new CellCarrierRemapPlan.Marker(1, 0, false, .03125F, 31.25,
				.25, .25, .25, 0, 0, 0, null, 0));
		independent.reserved[1] = SignedDyadic.of(.03125); flux.reserve(independent.reserved);
		var capacities = new SignedDyadic[8]; java.util.Arrays.fill(capacities, SignedDyadic.ZERO);
		capacities[0] = SignedDyadic.of(1); capacities[1] = SignedDyadic.of(.25).subtract(independent.reserved[1]);
		flux.geometricResult(domain.markerGrid.exactCellVolumeInventory, capacities);
		var ports = new CellRemapPorts(domain, flux, independent, false, null);
		if (flux.exactFlux[0].signum() != 0 || !ports.reserved(1, 128).equals(SignedDyadic.of(.03125))
				|| !flux.resultingVolumes()[1].equals(SignedDyadic.of(.125)))
			throw new AssertionError("Full connected corner spilled into an independently occupied isolated corner");
	}

	private static void geometryRemapRechecksMicroCapacity() {
		var moving = microPortDomain((byte) 0, (byte) 126,
				new float[] {.375F, .125F, .125F, .125F, .875F, .375F, .375F, .125F});
		var transfer = microPortFlux(moving, .0625);
		var capacities = new SignedDyadic[8]; java.util.Arrays.fill(capacities, SignedDyadic.ZERO);
		capacities[0] = SignedDyadic.of(1); capacities[1] = SignedDyadic.of(.25);
		var result = moving.markerGrid.exactCellVolumeInventory.clone();
		result[0] = SignedDyadic.of(.0625); result[1] = SignedDyadic.of(.1875);
		transfer.geometricResult(result, capacities);
		var before = moving.exportParticleState();
		var raw = moving.particles.directArrays();
		var masses = raw.mass.clone(); var materials = raw.materialHandle.clone(); var phases = raw.phaseLabel.clone();
		var relocated = new CellCarrierRemapPlan(moving, transfer, 0, new BallisticLiquidRemap(8), true);
		SignedDyadic isolated = SignedDyadic.ZERO, received = SignedDyadic.ZERO;
		for (var marker : relocated.markers) if (marker.cell() == 1) {
			int support = CellRemapMicroTopology.markerSupport(moving, marker);
			if (support == 128) isolated = isolated.add(SignedDyadic.of(marker.volume()));
			else if (support == 1) received = received.add(SignedDyadic.of(marker.volume()));
			else throw new AssertionError("Geometry relocation changed the receiving micro component");
		}
		var after = moving.exportParticleState();
		if (!isolated.equals(SignedDyadic.of(.125)) || !received.equals(SignedDyadic.of(.0625))
				|| before.particleCount() != after.particleCount()
				|| !java.util.Arrays.equals(before.positionVolume(), after.positionVolume())
				|| !java.util.Arrays.equals(before.particleVelocities(), after.particleVelocities())
				|| !java.util.Arrays.equals(before.collisionMicroMasks(), after.collisionMicroMasks())
				|| !java.util.Arrays.equals(masses, moving.particles.directArrays().mass)
				|| !java.util.Arrays.equals(materials, moving.particles.directArrays().materialHandle)
				|| !java.util.Arrays.equals(phases, moving.particles.directArrays().phaseLabel))
			throw new AssertionError("Zero-duration geometry relocation lost source stock or isolated component inventory");
		var domain = microPortDomain((byte) 126, (byte) 0, new float[] {.125F, .125F, .125F, .125F});
		var flux = microPortFlux(domain, 0);
		var unchanged = new CellCarrierRemapPlan(domain, flux, 0, new BallisticLiquidRemap(8), true);
		for (var marker : unchanged.markers) if (marker.cell() == 0 && (marker.offsetX() >= 0 || marker.offsetY() >= 0 || marker.offsetZ() >= 0))
			throw new AssertionError("Geometry reconstruction sampled another micro component");
		var capacity = new SignedDyadic[8]; java.util.Arrays.fill(capacity, SignedDyadic.ZERO);
		capacity[0] = SignedDyadic.of(.125); capacity[1] = SignedDyadic.of(1);
		flux.geometricResult(domain.markerGrid.exactCellVolumeInventory, capacity);
		try {
			new CellCarrierRemapPlan(domain, flux, 0, new BallisticLiquidRemap(8), true);
			throw new AssertionError("A no-face-change geometry remap retained over-capacity micro inventory");
		} catch (MechanicalTimeRefinement expected) { }
	}

	private static void ballisticComponentUsesPublishedFloatPosition() {
		var domain = new FluidDomain(4, 4, 4, new FluidSolverConfig(.1F, 1000, 0, .05F, 200, 1e-5F, .4F, .002F, .8F), 64);
		byte[] masks = new byte[64]; java.util.Arrays.fill(masks, (byte) 255); masks[42] = 126;
		domain.restoreParticleState(new FlipParticleState(4, 4, 4, .1F, 1, .0625F,
				new float[] {.25F, .25F, .25F, .0625F}, new float[3], masks));
		domain.rebuildGridFromParticles(); java.util.Arrays.fill(domain.gridToParticleStage.advectionCells(), false);
		double offset = .25 / (double) domain.config.cellSize() - 2.5;
		var marker = new CellCarrierRemapPlan.Marker(42, 0, false, .0625F, 62.5, offset, offset, offset, 0, 0, 0, null, 0);
		if (!(offset < 0) || CellRemapMicroTopology.markerSupport(domain, 0) != 128
				|| CellRemapMicroTopology.markerSupport(domain, marker) != 128)
			throw new AssertionError("Independent arrival classification differs from its actual binary32 physical position");
		var ballistic = new BallisticLiquidRemap(64); ballistic.markers.add(marker); ballistic.reserved[42] = SignedDyadic.of(.0625);
		var flux = new ConservativeLiquidFlux(4, 4, 4); flux.reserve(ballistic.reserved);
		flux.plan(domain.markerGrid.exactCellMarkerVolume, domain.geometry.solid, 0, domain.markerGrid.exactCellVolumeInventory);
		var capacities = new SignedDyadic[64]; java.util.Arrays.fill(capacities, SignedDyadic.ZERO); capacities[42] = SignedDyadic.of(.1875);
		flux.geometricResult(domain.markerGrid.exactCellVolumeInventory, capacities);
		var ports = new CellRemapPorts(domain, flux, ballistic, false, null);
		if (!ports.reserved(42, 128).equals(SignedDyadic.of(.0625)) || ports.reserved(42, 1).signum() != 0)
			throw new AssertionError("Independent arrival reserved another micro component's capacity");
	}

	private static FluidDomain microPortDomain(byte leftMask, byte rightMask, float[] points) {
		int count = points.length / 4; float total = 0; for (int p = 0; p < count; p++) total += points[4 * p + 3];
		var domain = new FluidDomain(2, 2, 2, new FluidSolverConfig(.5F, 1000, 0, .05F, 200, 1e-5F, .4F, .002F, .8F), 64);
		byte[] masks = new byte[8]; java.util.Arrays.fill(masks, (byte) 255); masks[0] = leftMask; masks[1] = rightMask;
		domain.restoreParticleState(new FlipParticleState(2, 2, 2, .5F, count, total, points, new float[3 * count], masks));
		domain.rebuildGridFromParticles(); java.util.Arrays.fill(domain.gridToParticleStage.advectionCells(), true); return domain;
	}
	private static ConservativeLiquidFlux microPortFlux(FluidDomain domain, double amount) {
		var flux = new ConservativeLiquidFlux(2, 2, 2);
		flux.plan(domain.markerGrid.exactCellMarkerVolume, domain.geometry.solid, 0, domain.markerGrid.exactCellVolumeInventory);
		var capacity = new SignedDyadic[8];
		for (int cell = 0; cell < 8; cell++) capacity[cell] = SignedDyadic.of(Integer.bitCount(~domain.geometry.cutCells.mask(cell) & 255), -3);
		flux.geometricResult(domain.markerGrid.exactCellVolumeInventory, capacity);
		flux.exactFlux[0] = SignedDyadic.of(amount); flux.velocity[0] = 1; return flux;
	}

	private static void chemicalShareCountRespectsSpeciesGroups() {
		var definitions = new java.util.ArrayList<mmm.chemistry.ChemicalSpecies>();
		for (int species = 0; species < 18; species++) definitions.add(new mmm.chemistry.ChemicalSpecies(
				"test:s" + String.format(java.util.Locale.ROOT, "%02d", species), java.util.Map.of("X", 1), 0,
				mmm.chemistry.ExactRational.ONE, 0xff000000));
		var chemistry = new mmm.chemistry.ChemicalRegistry(definitions, java.util.List.of(
				new mmm.chemistry.ChemicalPhaseDefinition("test:phase", "mmm:water",
						definitions.stream().map(mmm.chemistry.ChemicalSpecies::id).toList(),
						java.util.Map.of("test:s00", mmm.chemistry.ExactRational.ONE), false)), java.util.List.of(
				new mmm.chemistry.ChemicalReaction("test:conversion", mmm.chemistry.ChemicalReaction.Kind.INSTANTANEOUS,
						"test:phase", java.util.Map.of("test:s00", 1), java.util.Map.of("test:s01", 1), mmm.chemistry.ExactRational.ZERO)));
		var domain = new FluidDomain(2, 2, 2, FluidSolverConfig.prototypeDefaults(), 64);
		mmm.simulation.FullCapabilityTestFixture.bindEmptyMaterialState(domain,
				new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE.stream()
						.filter(material -> material.id().equals("mmm:water")).toList()));
		domain.setVolume(0, 0, 0, 1); domain.bindChemistry(chemistry);
		if (domain.particles.count() != 8) throw new AssertionError("Chemical grouping fixture requires eight quadrature points");
		int handle = 0;
		for (int marker = 0; marker < 8; marker++) {
			int entries = marker < 5 ? 3 : 1;
			int[] handles = new int[entries]; long[] amounts = new long[entries];
			for (int entry = 0; entry < entries; entry++) { handles[entry] = handle++; amounts[entry] = 1; }
			var mixture = new mmm.chemistry.ChemicalMixtureRecord(handles, amounts, 0);
			domain.chemicalParcels.assign(marker, mixture.withMassResidualKilograms(domain.unitScale.kilograms(domain.particles.mass(marker))
					- mixture.exactSpeciesMassKilograms(chemistry).toDouble()), false);
		}
		if (CellCarrierRemapPlan.chemicalShares(domain)[0] != 2)
			throw new AssertionError("Flux share count merged carriers beyond their sixteen-species representation");
		for (int quanta : new int[] {3, 5, 6, 7}) {
			var coarse = new FluidDomain(2, 2, 2, FluidSolverConfig.prototypeDefaults(), 64);
			float h = coarse.config.cellSize();
			double initialMass = .01F * (double) coarse.config.density();
			coarse.restoreMaterialParticleState(new FlipMaterialParticleState(2, 2, 2, h, 1, .01F, initialMass,
					domain.materialRegistry.materials(), new float[]{.5F * h, .5F * h, .5F * h, .01F},
					new float[3], new byte[8], new double[]{initialMass}, new int[1]));
			coarse.bindChemistry(chemistry);
			if (coarse.particles.count() != 1) throw new AssertionError("Coarse chemistry fixture requires one carrier");
			coarse.particles.setVolume(0, 1); coarse.particles.setMass(0, 1000);
			var composition = new mmm.chemistry.ChemicalMixtureRecord(new int[] {0}, new long[] {quanta}, 0);
			coarse.chemicalParcels.assign(0, composition.withMassResidualKilograms(coarse.unitScale.kilograms(1000)
					- composition.exactSpeciesMassKilograms(chemistry).toDouble()), false);
			coarse.rebuildGridFromParticles();
			var flux = new ConservativeLiquidFlux(2, 2, 2);
			flux.plan(coarse.markerGrid.exactCellMarkerVolume, coarse.geometry.solid, 0,
					coarse.markerGrid.exactCellVolumeInventory);
			var candidate = new CellCarrierRemapPlan(coarse, flux, 0, new BallisticLiquidRemap(8), true);
			if (candidate.markers.size() != 1 || candidate.markers.size() > quanta)
				throw new AssertionError("Rank-zero chemical remap added redundant nodes or exceeded its integer budget");
			SignedDyadic volume = SignedDyadic.ZERO, mass = SignedDyadic.ZERO; long[] amounts = new long[definitions.size()];
			for (var marker : candidate.markers) {
				if (!(Float.isFinite(marker.volume()) && marker.volume() > 0 && Double.isFinite(marker.mass()) && marker.mass() > 0))
					throw new AssertionError("Coarse chemical remap generated a nonpositive inventory node");
				volume = volume.add(SignedDyadic.of(marker.volume())); mass = mass.add(SignedDyadic.of(marker.mass()));
				for (int axis = 0; axis < 3; axis++) if (marker.offset(axis) != 0 || marker.velocity(axis) != 0)
					throw new AssertionError("Rank-zero chemical remap displaced its center or constant velocity");
				for (int species = 0; species < amounts.length; species++) amounts[species] = Math.addExact(amounts[species], marker.mixture().amount(species));
			}
			if (!volume.equals(SignedDyadic.of(1)) || !mass.equals(SignedDyadic.of(1000)) || amounts[0] != quanta)
				throw new AssertionError("Coarse chemical remap changed its exact volume, mass or species inventory");
			for (int species = 1; species < amounts.length; species++) if (amounts[species] != 0)
				throw new AssertionError("Coarse chemical remap introduced an unrelated species");
		}

	}

	private static void uniformDensityMassAllocation() {
		SignedDyadic[] equalWeights = new SignedDyadic[8]; java.util.Arrays.fill(equalWeights, SignedDyadic.of(.125));
		for (double source : new double[] {999.999999, Math.nextUp(1000.0), 1.0000001}) {
			double[] shares = CellRemapMassAllocation.allocate(source, SignedDyadic.of(1.0), equalWeights);
			double error = SignedDyadic.sum(shares).subtract(SignedDyadic.of(source)).toDouble();
			if (Math.abs(error) > .5 * Math.ulp(shares[0]))
				throw new AssertionError("Nonuniform-density mass remainder accumulated repeated subtraction roundoff");
		}
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
		double[] sourceMean = new double[6]; double[][] sourceSecond = new double[6][6];
		for (int p = 0; p < domain.particleCount(); p++) {
			double h = domain.config.cellSize();
			double[] joint = {domain.particles.positionX(p) / h - .5, domain.particles.positionY(p) / h - .5,
					domain.particles.positionZ(p) / h - .5, domain.particles.velocityX(p), domain.particles.velocityY(p), domain.particles.velocityZ(p)};
			for (int a = 0; a < 6; a++) {
				sourceMean[a] += joint[a] / 8;
				for (int b = a; b < 6; b++) sourceSecond[a][b] += joint[a] * joint[b] / 8;
			}
		}
		domain.rebuildGridFromParticles(); var transport = new ConservativeLiquidFlux(2, 2, 2);
		transport.plan(domain.markerGrid.exactCellMarkerVolume, domain.geometry.solid, 0, domain.markerGrid.exactCellVolumeInventory);
		var candidate = new CellCarrierRemapPlan(domain, transport, 0, new BallisticLiquidRemap(8), true);
		if (candidate.markers.size() != 8) throw new AssertionError("Full-cell quadrature requires the complete tensor rule");
		double[] firstMoment = new double[3]; double[][] secondMoment = new double[3][3];
		double[] receivedMean = new double[6]; double[][] receivedSecond = new double[6][6];
		for (var marker : candidate.markers) {
			double[] offset = {marker.offsetX(), marker.offsetY(), marker.offsetZ()};
			for (int a = 0; a < 3; a++) {
				firstMoment[a] += marker.volume() * offset[a];
				for (int b = 0; b < 3; b++) secondMoment[a][b] += marker.volume() * offset[a] * offset[b];
			}
			double[] joint = {offset[0], offset[1], offset[2], marker.velocityX(), marker.velocityY(), marker.velocityZ()};
			for (int a = 0; a < 6; a++) {
				receivedMean[a] += marker.mass() / 1000 * joint[a];
				for (int b = a; b < 6; b++) receivedSecond[a][b] += marker.mass() / 1000 * joint[a] * joint[b];
			}
		}
		for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++)
			if (firstMoment[a] != 0 || secondMoment[a][b] != (a == b ? 1.0 / 16 : 0))
				throw new AssertionError("Tensor cell quadrature lost its centered isotropic covariance");
		for (int a = 0; a < 6; a++) {
			double scale = Math.max(Math.abs(sourceMean[a]), Math.sqrt(sourceSecond[a][a]));
			if (Math.abs(receivedMean[a] - sourceMean[a]) > 512 * Math.ulp(scale))
				throw new AssertionError("Cell quadrature lost its joint position/velocity first moment: " + a);
			for (int b = a; b < 6; b++) {
				double secondScale = Math.sqrt(sourceSecond[a][a]) * Math.sqrt(sourceSecond[b][b]);
				if (Math.abs(receivedSecond[a][b] - sourceSecond[a][b]) > 1024 * Math.ulp(secondScale))
					throw new AssertionError("Cell quadrature lost its joint position/velocity second moment: " + a + "," + b);
			}
		}

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
