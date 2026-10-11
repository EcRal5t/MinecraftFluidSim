package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.granular.BuiltinGranularDefinitions;
import cc.ecisr.mmm.granular.GranularLimits;
import cc.ecisr.mmm.granular.GranularParcelStore;
import cc.ecisr.mmm.granular.SolidVolume;

/** Physical invariants at the production MAC/parcel and pressure/parcel interfaces. */
public final class GranularCarrierValidation {
	private GranularCarrierValidation() { }

	public static void validate() {
		sphereMicroBoxContacts();
		depositContactsRespectCellGeometry();
		depositPathsRespectSuspendedBeds();
		cutBedGeometryMatchesFinalPlan();
		relaxationRequiresAnActualHorizontalPath();
		straightSphereContactsAreSymmetric();
		contactImpulsePreservesSolvedDrift();
		contactImpulseObeysCoulombCone();
		committedContactMomentumCloses();
		contactAgeSaturates();
		carrierCacheFollowsAdvection();
		for (boolean massWeighted : new boolean[]{false, true}) sharedCarrierExchange(massWeighted);
		finitePressureResponse();
		sweptOccupancyConservation();
		sweptKnotDirectionsMatchHatKernel();
		hydrostaticParcelForce();
		sharedGasPressureAlgebra();
		pressureReferenceDoesNotAccelerateMatter();
		continuousLiquidContact();
		double below = cc.ecisr.mmm.granular.GranularMechanics.dragResistanceFactor(Math.nextDown(1_000.0));
		double above = cc.ecisr.mmm.granular.GranularMechanics.dragResistanceFactor(Math.nextUp(1_000.0));
		require(Math.abs(below - above) < 1.0e-12, "drag resistance must remain continuous at the branch transition");
		implicitSharedCarrier();
		implicitCarrierValidity();
		thinBedRetainsFreeFlow();
	}

	private static void sphereMicroBoxContacts() {
		var geometry = new FlipCutCellGeometry(3, 3, 3);
		geometry.replaceMask((1 * 3 + 1) * 3 + 1, 1); // [.5,.75]^3
		require(geometry.sphereTouchesSolid(.46F, .46F, .46F, .08F, .5F),
				"sphere corner contact fell between the seven axis probes");
		for (int outsideAxes = 1; outsideAxes < 8; outsideAxes++) for (int sides = 0; sides < 8; sides++) {
			float[] center = new float[3];
			double distanceSquared = 0;
			for (int axis = 0; axis < 3; axis++) {
				center[axis] = (outsideAxes & 1 << axis) == 0 ? .625F
						: (sides & 1 << axis) == 0 ? .46F : .79F;
				double nearest = Math.min(.75, Math.max(.5, center[axis]));
				double distance = center[axis] - nearest;
				distanceSquared += distance * distance;
			}
			float threshold = (float) Math.sqrt(distanceSquared);
			require(geometry.sphereTouchesSolid(center[0], center[1], center[2], Math.nextUp(threshold), .5F)
					&& !geometry.sphereTouchesSolid(center[0], center[1], center[2], Math.nextDown(threshold), .5F),
					"rotated/reflected face, edge or corner near-contact lost its geometric threshold");
		}
		// The sphere's lower bound coincides with the occupied cell's upper bound.
		geometry.replaceMask(13, 2); // [.75,1] x [.5,.75]^2
		require(geometry.sphereTouchesSolid(1.25F, .625F, .625F, .25F, .5F),
				"closed tangency was omitted from the broad-phase cell range");
		geometry.replaceMask(13, 1);
		long svq = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		float radius = cc.ecisr.mmm.granular.GranularMechanics.representativeRadius(svq, .5F);
		float corner = .5F - radius * .5F;
		var collision = new FlipGranularCollision(3, 3, 3, .5F, geometry, null, 1);
		require(!collision.positionValid(corner, corner, corner, svq),
				"granular placement accepted an intersecting sphere corner");
		int spawnedCount = 0;
		for (int sequence = 0; sequence < 48; sequence++) {
			var spawned = collision.spawnPosition(1, 1, 1, svq, sequence, .76F, .76F, .76F);
			if (spawned != null) {
				spawnedCount++;
				double squared = 0;
				for (float coordinate : new float[]{spawned.x(), spawned.y(), spawned.z()}) {
					double nearest = Math.min(.75, Math.max(.5, coordinate));
					squared += (coordinate - nearest) * (coordinate - nearest);
				}
				require(squared > radius * (double) radius,
						"granular spawn intersected the occupied micro-box");
			}
		}
		require(spawnedCount > 0, "granular spawn fixture never found an open position");
		var registry = BuiltinGranularDefinitions.createRegistry();
		int sand = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		var parcels = new GranularParcelStore(registry, 1);
		parcels.add(new GranularParcelStore.Parcel(.42F, .46F, .46F, .8F, 0, 0,
				svq, 2_600 * svq / (double) SolidVolume.SVQ_PER_BV, sand, 0, 0, (byte) 0));
		collision.preview(parcels, registry, .05F);
		collision.commit(parcels);
		var next = parcels.parcel(0);
		require(next.x() > .42F && collision.positionValid(next.x(), next.y(), next.z(), svq)
				&& next.velocityY() < 0 && next.velocityZ() < 0,
				"sphere corner contact must use its oblique normal and preserve the remaining tangent drift");
	}

	private static void depositContactsRespectCellGeometry() {
		var registry = BuiltinGranularDefinitions.createRegistry();
		int sand = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long svq = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		float radius = cc.ecisr.mmm.granular.GranularMechanics.representativeRadius(svq, .5F);
		var geometry = new FlipCutCellGeometry(3, 3, 3);
		var beds = new cc.ecisr.mmm.granular.GranularDepositStore(registry, 27, 8);
		for (int cell : new int[]{9, 10}) {
			long skeleton = (long) (SolidVolume.SVQ_PER_CELL * .62 * (cell == 9 ? .1 : .6));
			require(beds.tryAdd(cell, new cc.ecisr.mmm.granular.GranularDepositStore.SolidPortion(
					sand, 0, skeleton, 2_600.0 * skeleton / SolidVolume.SVQ_PER_BV), SolidVolume.SVQ_PER_CELL), "bed fixture must fit its cell capacity");
		}
		for (float vy : new float[]{0, -.4905F}) {
			var parcels = new GranularParcelStore(registry, 1);
			parcels.add(new GranularParcelStore.Parcel(.4F, .25F, .75F, 3.2F, vy, 0,
					svq, 2_600.0 * svq / SolidVolume.SVQ_PER_BV, sand, 0, 0, (byte) 0));
			var collision = new FlipGranularCollision(3, 3, 3, .5F, geometry, beds, 1);
			require((3.2 + Math.abs(vy)) * .05 <= .5 * .4 && collision.positionValid(.4F, .25F, .75F, svq),
					"rising-bed fixture must have a legal start and default Courant drift");
			collision.preview(parcels, registry, .05F); var impulse = collision.commit(parcels); var next = parcels.parcel(0);
			require(next.x() <= .5 - radius && next.y() == .25F + vy * .05F
					&& next.velocityX() < 0 && collision.positionValid(next.x(), next.y(), next.z(), svq),
					"a bed side must stop normal drift while preserving solved tangential drift");
			require(impulse.x() == next.mass() * ((double) 3.2F - next.velocityX())
					&& impulse.y() == next.mass() * ((double) vy - next.velocityY())
					&& next.velocityX() * (double) next.velocityX() + next.velocityY() * (double) next.velocityY()
							<= 3.2F * (double) 3.2F + vy * (double) vy,
					"bed contact must close momentum and dissipate kinetic energy");
		}
		// A supported upper bed and a lower bed leave a genuine open cavity.
		beds = new cc.ecisr.mmm.granular.GranularDepositStore(registry, 27, 8);
		for (int cell : new int[]{9, 15}) {
			long skeleton = (long) (SolidVolume.SVQ_PER_CELL * .62 * .6);
			require(beds.tryAdd(cell, new cc.ecisr.mmm.granular.GranularDepositStore.SolidPortion(
					sand, 0, skeleton, 2_600.0 * skeleton / SolidVolume.SVQ_PER_BV), SolidVolume.SVQ_PER_CELL), "separated beds must fit their cells");
		}
		geometry.replaceMask(12, 255); // [.0,.5] x [.5,1] x [.5,1], upper bed rests above it.
		double top = beds.cellBulkVolumeSvq(9) * .5 / SolidVolume.SVQ_PER_CELL;
		var parcels = new GranularParcelStore(registry, 1);
		parcels.add(new GranularParcelStore.Parcel(.25F, (float) (top + radius + .01), .75F, 0, -.4F, 0,
				svq, 2_600.0 * svq / SolidVolume.SVQ_PER_BV, sand, 0, 0, (byte) 0));
		var collision = new FlipGranularCollision(3, 3, 3, .5F, geometry, beds, 1);
		var initial = parcels.parcel(0);
		require(collision.positionValid(initial.x(), initial.y(), initial.z(), svq)
				&& collision.spawnPosition(0, 0, 1, svq, 0, initial.x(), initial.y(), initial.z()) != null,
				"an upper bed must not cover placement in a separated lower cavity");
		collision.preview(parcels, registry, .05F); collision.commit(parcels); var resting = parcels.parcel(0);
		require(resting.y() > top + radius && resting.y() < .5 - radius && resting.contactAge() == 1
				&& (resting.flags() & GranularParcelStore.FLAG_SUPPORTED) != 0,
				"the actual lower bed must support its cavity without lifting the parcel to the column maximum");
		for (int step = 0; step < 2; step++) {
			var p = parcels.parcel(0); parcels.overwrite(0, new GranularParcelStore.Parcel(p.x(), p.y(), p.z(), 0, -1.0e-8F, 0,
					p.solidVolumeSvq(), p.mass(), sand, 0, p.contactAge(), p.flags()));
			collision = new FlipGranularCollision(3, 3, 3, .5F, geometry, beds, 1);
			collision.preview(parcels, registry, .05F); collision.commit(parcels);
			require(parcels.parcel(0).y() == resting.y() && parcels.parcel(0).velocityY() == 0
					&& parcels.parcel(0).contactAge() == step + 2, "cold bed contact must retain support after rounded zero drift");
		}
		var p = parcels.parcel(0); long remaining = svq / 8;
		parcels.overwrite(0, new GranularParcelStore.Parcel(p.x(), p.y(), p.z(), 0, -.4F, 0,
				remaining, p.mass() / 8, sand, 0, p.contactAge(), p.flags()));
		require(parcels.parcel(0).contactAge() == 0 && parcels.parcel(0).flags() == 0, "changed representative radius must invalidate the old contact");
		collision.preview(parcels, registry, .05F); collision.commit(parcels);
		require(parcels.parcel(0).contactAge() == 0 && parcels.parcel(0).velocityY() < 0, "a shrunken sphere must fall through its newly vacated gap");
		collision.preview(parcels, registry, .05F); collision.commit(parcels);
		require(parcels.parcel(0).contactAge() == 1 && parcels.parcel(0).velocityY() == 0,
				"a shrunken sphere must reacquire the actual lower bed at its new radius");
	}

	private static void depositPathsRespectSuspendedBeds() {
		var domain = new FluidDomain(3, 4, 3,
				new FluidSolverConfig(.5F, 1_000F, 9.81F, .05F, 400, 1.0e-5F, .4F, .002F, .8F), 512);
		domain.bindGranular(BuiltinGranularDefinitions.createRegistry(),
				new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE), BuiltinFluidMaterials.createRegistry());
		domain.sealDomainBoundary();
		var state = domain.granularState;
		int sand = state.registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long volume = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		long skeleton = (long) (SolidVolume.SVQ_PER_CELL * .62);
		for (int z = 0; z < 3; z++) for (int x = 0; x < 3; x++) require(state.deposits.tryAdd(domain.cellIndex(x, 1, z),
				new cc.ecisr.mmm.granular.GranularDepositStore.SolidPortion(sand, 0, skeleton,
						2_600.0 * skeleton / SolidVolume.SVQ_PER_BV), SolidVolume.SVQ_PER_CELL), "suspended bed must fit each cell");
		int barrier = domain.cellIndex(1, 1, 1);
		double top = (1 + state.deposits.cellBulkVolumeSvq(barrier) / (double) SolidVolume.SVQ_PER_CELL) * .5;
		float radius = cc.ecisr.mmm.granular.GranularMechanics.representativeRadius(volume, .5F);
		float y = (float) (top + radius); if (y < top + radius) y = Math.nextUp(y);
		state.parcels.add(new GranularParcelStore.Parcel(.75F, y, .75F, 0, 0, 0, volume,
				2_600.0 * volume / SolidVolume.SVQ_PER_BV, sand, 0, 0, (byte) 0));
		domain.ensureGrid();
		require(domain.granularCollision.positionValid(.75F, y, .75F, volume), "bed support must start outside the actual bed");
		for (int step = 0; step < 3; step++) {
			domain.granularCollision.preview(state.parcels, state.registry, .05F);
			domain.granularCollision.commit(state.parcels);
		}
		var source = state.parcels.parcel(0);
		require(source.contactAge() == 3 && (source.flags() & GranularParcelStore.FLAG_SUPPORTED) != 0,
				"cold collision must acquire actual support without manufacturing settlement eligibility");
		long[] planned = domain.granularCoupling.copySolidSvq(), original = planned.clone();
		var environment = domain.granularDepositEnvironment(planned);
		require(environment.verticalPathWork(source.x(), source.y(), source.z(), Math.nextUp(radius), volume) == 3,
				"vertical path must charge its three candidate bed layers before querying them");
		require(!environment.verticalPathOpen(source.x(), source.y(), source.z(), Math.nextUp(radius), volume)
				&& environment.verticalPathOpen(source.x(), source.y(), source.z(), Math.nextUp(1 + radius), volume)
				&& environment.verticalPathOpen(source.x(), source.y(), source.z(), source.y(), volume)
				&& !environment.verticalPathOpen(.75F, .75F, .75F, .75F, volume),
				"vertical admission must block bed interior while allowing its legal top contact");
		require(java.util.Arrays.equals(planned, original) && state.parcels.parcel(0).equals(source)
				&& state.deposits.cellSolidVolumeSvq(barrier) == skeleton, "path preview must preserve inventory and planned occupancy");
		long revision = state.deposits.mutationRevision();
		domain.granularDepositDynamics().disturbCell(barrier);
		var exhausted = new cc.ecisr.mmm.granular.GranularDepositDynamics.Environment() {
			@Override public long geometricOpenSvq(int cell) { return environment.geometricOpenSvq(cell); }
			@Override public boolean horizontalPathOpen(int cell, int dx, int dz) { return environment.horizontalPathOpen(cell, dx, dz); }
			@Override public double shields(int cell, cc.ecisr.mmm.granular.ParticulateMaterial material) { return environment.shields(cell, material); }
			@Override public double gravity() { return environment.gravity(); }
			@Override public int verticalPathWork(float x, float from, float z, float to, long amount) {
				return GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP;
			}
			@Override public boolean verticalPathOpen(float x, float from, float z, float to, long amount) {
				throw new AssertionError("exhausted path budget must stop before geometry query");
			}
		};
		var deferred = domain.granularDepositDynamics().step(exhausted, .05F);
		require(deferred.settledParcels() == 0 && state.parcels.parcel(0).equals(source)
				&& state.deposits.mutationRevision() == revision && java.util.Arrays.equals(planned, original)
				&& deferred.dirtyColumns() > 0, "path work exhaustion must preserve all inventory and pending columns");
		var result = domain.granularDepositDynamics().step(environment, .05F);
		require(state.deposits.cellSolidVolumeSvq(domain.cellIndex(1, 0, 1)) == 0
				&& state.deposits.cellSolidVolumeSvq(barrier) == skeleton
				&& result.wokenParcels() == 0 && result.relaxedMoves() == 0,
				"settlement must preserve the suspended barrier and the lower cavity");
		require(result.settledParcels() == 1 && state.parcels.count() == 0
				&& state.deposits.cellSolidVolumeSvq(domain.cellIndex(1, 2, 1)) == volume,
				"the lowest reachable upper layer must accept this capacity- and potential-valid parcel");
		long tower = (long) (SolidVolume.SVQ_PER_CELL * .62 * .8);
		require(state.deposits.tryAdd(domain.cellIndex(1, 2, 1),
				new cc.ecisr.mmm.granular.GranularDepositStore.SolidPortion(sand, 0, tower,
						2_600.0 * tower / SolidVolume.SVQ_PER_BV), SolidVolume.SVQ_PER_CELL), "upper source bed must fit its layer");
		domain.granularCoupling.rebuild(state.parcels, state.deposits);
		domain.granularDepositDynamics().disturbCell(domain.cellIndex(1, 2, 1));
		var relaxed = domain.granularDepositDynamics().step(domain.granularDepositEnvironment(domain.granularCoupling.copySolidSvq()), .05F);
		long upperTotal = 0;
		for (int z = 0; z < 3; z++) for (int x = 0; x < 3; x++) {
			require(state.deposits.cellSolidVolumeSvq(domain.cellIndex(x, 0, z)) == 0
					&& state.deposits.cellSolidVolumeSvq(domain.cellIndex(x, 1, z)) == skeleton,
					"neighbor relaxation must preserve every lower cavity and intervening bed");
			upperTotal += state.deposits.cellSolidVolumeSvq(domain.cellIndex(x, 2, z));
		}
		require(relaxed.relaxedMoves() > 0 && relaxed.wokenParcels() == 0 && upperTotal == tower + volume,
				"capacity- and potential-valid relaxation must reach an upper receiver without passing through its lower bed");
		var cuts = new FlipCutCellGeometry(3, 3, 3); cuts.replaceMask(13, 1);
		var geometry = new GranularCollisionGeometry(3, 3, 3, .5F, cuts, null);
		require(!geometry.verticalSpherePathTouchesSolid(.625F, .8125F, .625F, 1.0F, .0625F)
				&& geometry.verticalSpherePathTouchesSolid(.625F, .8125F, .625F, .7F, .0625F),
				"exact source support tangency must allow departure and reject passage into a micro box");
	}

	private static void cutBedGeometryMatchesFinalPlan() {
		for (int scenario = 0; scenario < 3; scenario++) {
			var domain = new FluidDomain(3, 4, 3,
					new FluidSolverConfig(.5F, 1_000F, 9.81F, .05F, 400, 1.0e-5F, .4F, .002F, .8F), 512);
			domain.bindGranular(BuiltinGranularDefinitions.createRegistry(),
					new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE), BuiltinFluidMaterials.createRegistry());
			domain.sealDomainBoundary();
			var state = domain.granularState;
			int sand = state.registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND), target = domain.cellIndex(1, 0, 1);
			domain.geometry.cutCells.replaceMask(target, scenario == 0 ? 1 : 4);
			long volume = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
			if (scenario == 2) {
				long oldSkeleton = (long) (SolidVolume.SVQ_PER_CELL * .62 * .46);
				require(state.deposits.tryAdd(target, new cc.ecisr.mmm.granular.GranularDepositStore.SolidPortion(sand, 0,
						oldSkeleton, 2_600.0 * oldSkeleton / SolidVolume.SVQ_PER_BV), SolidVolume.SVQ_PER_CELL * 7 / 8),
						"initial mixed-space bed must fit below the upper cut micro box");
			}
			float radius = cc.ecisr.mmm.granular.GranularMechanics.representativeRadius(volume, .5F);
			double top = state.deposits.cellBulkVolumeSvq(target) * .5 / SolidVolume.SVQ_PER_CELL;
			float y = (float) (top + radius); if (y < top + radius) y = Math.nextUp(y);
			for (int index = 0; index < (scenario == 2 ? 2 : 1); index++) state.parcels.add(new GranularParcelStore.Parcel(
					.9F, y, .9F, 0, 0, 0, volume, 2_600.0 * volume / SolidVolume.SVQ_PER_BV, sand, 0, 0, (byte) 0));
			domain.ensureGrid();
			require(domain.granularCollision.positionValid(.9F, y, .9F, volume), "cut-bed fixture needs a legal supported sphere start");
			for (int step = 0; step < 3; step++) {
				domain.granularCollision.preview(state.parcels, state.registry, .05F); domain.granularCollision.commit(state.parcels);
			}
			var originals = new GranularParcelStore.Parcel[state.parcels.count()];
			for (int index = 0; index < originals.length; index++) {
				originals[index] = state.parcels.parcel(index);
				require(originals[index].contactAge() == 3, "cut-bed sources must acquire actual contact age");
			}
			long[] planned = domain.granularCoupling.copySolidSvq(), before = planned.clone();
			var environment = domain.granularDepositEnvironment(planned);
			require(environment.bedGeometryFits(target, 0) && (scenario == 0
					? !environment.bedGeometryFits(target, 1)
					: environment.bedGeometryFits(target, SolidVolume.SVQ_PER_CELL / 2)
						&& !environment.bedGeometryFits(target, SolidVolume.SVQ_PER_CELL / 2 + 1)),
					"full-footprint bed must preserve pure top tangency and reject cut interior overlap");
			if (scenario == 2) {
				var single = state.inventory.planSettlement(new int[]{0}, target, environment.geometricOpenSvq(target));
				var joint = state.inventory.planSettlement(new int[]{0, 1}, target, environment.geometricOpenSvq(target));
				require(single != null && joint != null && environment.bedGeometryFits(target, single.bulkVolumeSvq())
						&& !environment.bedGeometryFits(target, joint.bulkVolumeSvq())
						&& cc.ecisr.mmm.granular.GranularBedPotential.allowsSettlement(joint, .5F, 3, 4, 9.81),
						"joint cut rejection must protect a height crossing that each single source and potential guard allow");
			}
			long revision = state.deposits.mutationRevision(), oldStock = state.deposits.cellSolidVolumeSvq(target);
			domain.granularDepositDynamics().disturbCell(target);
			var result = domain.granularDepositDynamics().step(environment, .05F);
			if (scenario == 1) require(result.settledParcels() == 1 && state.parcels.count() == 0
					&& state.deposits.cellSolidVolumeSvq(target) == volume && environment.bedGeometryFits(target, state.deposits.cellBulkVolumeSvq(target)),
					"a low bed below an upper micro box must remain representable and settle");
			else {
				require(result.settledParcels() == 0 && result.relaxedMoves() == 0 && result.wokenParcels() == 0
						&& state.parcels.count() == originals.length && state.deposits.mutationRevision() == revision
						&& state.deposits.cellSolidVolumeSvq(target) == oldStock && java.util.Arrays.equals(planned, before)
						&& (scenario == 0 || result.dirtyColumns() > 0), "unrepresentable cut-bed growth must defer the complete inventory atomically: scenario=" + scenario + ", " + result);
				for (int index = 0; index < originals.length; index++) require(state.parcels.parcel(index).equals(originals[index]),
						"cut-bed rejection must preserve each original source");
			}
		}
	}

	private static void relaxationRequiresAnActualHorizontalPath() {
		for (int mode = 0; mode < 3; mode++) {
			boolean blocked = mode == 0, exhausted = mode == 2;
			var domain = new FluidDomain(3, 4, 3,
					new FluidSolverConfig(.5F, 1_000F, 9.81F, .05F, 400, 1.0e-5F, .4F, .002F, .8F), 512);
			domain.bindGranular(BuiltinGranularDefinitions.createRegistry(),
					new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE), BuiltinFluidMaterials.createRegistry());
			domain.sealDomainBoundary();
			var state = domain.granularState;
			int sand = state.registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
			int source = domain.cellIndex(1, 1, 1), portal = domain.cellIndex(2, 1, 1), receiver = domain.cellIndex(2, 1, 2);
			for (int z = 0; z < 3; z++) for (int x = 0; x < 3; x++) {
				long floor = (long) (SolidVolume.SVQ_PER_CELL * .62);
				require(state.deposits.tryAdd(domain.cellIndex(x, 0, z), new cc.ecisr.mmm.granular.GranularDepositStore.SolidPortion(
						sand, 0, floor, 2_600.0 * floor / SolidVolume.SVQ_PER_BV), SolidVolume.SVQ_PER_CELL), "floor bed must fit its cell");
				domain.geometry.cutCells.replaceMask(domain.cellIndex(x, 2, z), 255);
				int cell = domain.cellIndex(x, 1, z);
				if (cell != source && cell != portal && cell != receiver) domain.geometry.cutCells.replaceMask(cell, 255);
			}
			domain.geometry.cutCells.replaceMask(portal, blocked ? 126 : 0); // Open octants 0 and 7 have no face connection.
			if (!blocked) {
				domain.geometry.cutCells.replaceMask(domain.cellIndex(1, 1, 2), 0);
				long side = (long) (SolidVolume.SVQ_PER_CELL * .62 * .3);
				require(state.deposits.tryAdd(domain.cellIndex(1, 1, 2), new cc.ecisr.mmm.granular.GranularDepositStore.SolidPortion(
						sand, 0, side, 2_600.0 * side / SolidVolume.SVQ_PER_BV), SolidVolume.SVQ_PER_CELL), "open diagonal corridor must clear both adjacent corners");
			}
			for (int cell : new int[]{source, domain.cellIndex(2, 3, 1)}) {
				long volume = (long) (SolidVolume.SVQ_PER_CELL * .62 * (cell == source ? .885 : .1));
				require(state.deposits.tryAdd(cell, new cc.ecisr.mmm.granular.GranularDepositStore.SolidPortion(
						sand, 0, volume, 2_600.0 * volume / SolidVolume.SVQ_PER_BV), SolidVolume.SVQ_PER_CELL), "supported upper beds must fit");
			}
			domain.ensureGrid();
			long[] planned = domain.granularCoupling.copySolidSvq(), before = planned.clone();
			var environment = domain.granularDepositEnvironment(planned);
			for (int cell = 0; cell < state.deposits.cellCount(); cell++) require(environment.bedGeometryFits(cell, state.deposits.cellBulkVolumeSvq(cell)),
					"horizontal-path fixture must begin with every bed outside cut geometry");
			require(environment.horizontalPathOpen(source, 1, 1), "both scalar open-face routes must admit this diagonal candidate");
			if (exhausted) {
				var actual = environment;
				environment = (cc.ecisr.mmm.granular.GranularDepositDynamics.Environment) java.lang.reflect.Proxy.newProxyInstance(
						cc.ecisr.mmm.granular.GranularDepositDynamics.Environment.class.getClassLoader(),
						new Class<?>[]{cc.ecisr.mmm.granular.GranularDepositDynamics.Environment.class}, (proxy, method, args) -> {
							if (method.getName().equals("horizontalTransferPathWork")) return GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP;
							if (method.getName().equals("horizontalTransferPathOpen")) throw new AssertionError("path work must be charged before querying geometry");
							return method.invoke(actual, args);
						});
			}
			long sourceBefore = state.deposits.cellSolidVolumeSvq(source), stock = state.deposits.totalSolidVolumeSvq();
			long revision = state.deposits.mutationRevision();
			domain.granularDepositDynamics().disturbCell(source);
			var result = domain.granularDepositDynamics().step(environment, .05F);
			long arrived = state.deposits.cellSolidVolumeSvq(receiver);
			require(state.deposits.totalSolidVolumeSvq() == stock && state.parcels.count() == 0 && result.wokenParcels() == 0,
					"horizontal topology admission must preserve the exact inventory");
			if (blocked || exhausted) require(result.relaxedMoves() == 0 && arrived == 0 && state.deposits.mutationRevision() == revision
					&& result.dirtyColumns() > 0 && java.util.Arrays.equals(planned, before), "disconnected or over-budget horizontal paths must preserve bed stock and pending columns");
			else require(result.relaxedMoves() == 1 && arrived > 0 && sourceBefore - state.deposits.cellSolidVolumeSvq(source) == arrived,
					"an actual open horizontal corridor must preserve legal source contact and permit downward relaxation: " + result + ", arrived=" + arrived);
		}
	}

	private static void straightSphereContactsAreSymmetric() {
		var registry = BuiltinGranularDefinitions.createRegistry();
		int sand = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long svq = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		float radius = cc.ecisr.mmm.granular.GranularMechanics.representativeRadius(svq, .5F);
		int[][] permutations = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
		for (int feature : new int[]{1, 3, 7, 0}) {
			GranularSphereCollision.Motion reference = null;
			for (int[] permutation : permutations) for (int reflection = 0; reflection < 8; reflection++) {
				float[] position = {.39F, .51F, .625F}, velocity = {1.76F, -2.16F, 0};
				if (feature != 0) for (int a = 0; a < 3; a++) {
					double normal = (feature & 1 << a) == 0 ? 0 : 1 / Math.sqrt(Integer.bitCount(feature));
					position[a] = (float) (normal == 0 ? .625 : .75 + (radius + .02) * normal);
					velocity[a] = (float) (-1.6 * normal);
				}
				float[] transformedPosition = new float[3], transformedVelocity = new float[3];
				for (int a = 0; a < 3; a++) {
					boolean reflected = (reflection & 1 << a) != 0;
					transformedPosition[a] = reflected ? 1.25F - position[permutation[a]] : position[permutation[a]];
					transformedVelocity[a] = reflected ? -velocity[permutation[a]] : velocity[permutation[a]];
				}
				var geometry = new FlipCutCellGeometry(3, 3, 3); geometry.replaceMask(13, 1);
				var collision = new GranularSphereCollision(3, 3, 3, .5F, geometry, null);
				require((Math.abs(transformedVelocity[0]) + Math.abs(transformedVelocity[1]) + Math.abs(transformedVelocity[2])) * .05F <= .5F * .4F,
						"straight sphere fixture must fit the default Courant bound");
				var motion = collision.preview(transformedPosition, transformedVelocity, radius, .05F, registry.material(sand), 32);
				float[] endpoint = {motion.x(), motion.y(), motion.z()}, response = {motion.vx(), motion.vy(), motion.vz()};
				require(!geometry.sphereTouchesSolid(motion.x(), motion.y(), motion.z(), radius, .5F), "rounded CCD endpoint intersects a micro box");
				if (feature == 0) for (int a = 0; a < 3; a++) {
					// The straight diagonal misses the rounded box; an axis-folded path collides.
					require(endpoint[a] == transformedPosition[a] + transformedVelocity[a] * .05F && response[a] == transformedVelocity[a],
							"straight sphere trajectory acquired a spurious contact");
				}
				else for (int a = 0; a < 3; a++) {
					double normal = (feature & 1 << permutation[a]) == 0 ? 0 : 1 / Math.sqrt(Integer.bitCount(feature));
					double analytic = normal == 0 ? .625 : .75 + radius * normal;
					if ((reflection & 1 << a) != 0) { analytic = 1.25 - analytic; normal = -normal; }
					require(Math.abs(endpoint[a] - analytic) <= 2 * Math.ulp(endpoint[a])
							&& Math.abs(response[a] - 1.6 * registry.material(sand).restitution() * normal) < 8 * Math.ulp(1F),
							"radial face, edge or corner contact disagrees with the independent distance solution");
				}
				if (reference == null) reference = motion;
				float[] referencePosition = {reference.x(), reference.y(), reference.z()}, referenceVelocity = {reference.vx(), reference.vy(), reference.vz()};
				for (int a = 0; a < 3; a++) {
					boolean reflected = (reflection & 1 << a) != 0;
					double expectedPosition = reflected ? 1.25 - referencePosition[permutation[a]] : referencePosition[permutation[a]];
					double expectedVelocity = reflected ? -referenceVelocity[permutation[a]] : referenceVelocity[permutation[a]];
					require(Math.abs(endpoint[a] - expectedPosition) <= 2 * Math.ulp(endpoint[a])
							&& Math.abs(response[a] - expectedVelocity) <= 8 * Math.ulp(1F), "sphere contact changed under an axis permutation or reflection");
				}
			}
		}
		var geometry = new FlipCutCellGeometry(3, 3, 3); geometry.replaceMask(13, 1);
		var material = registry.material(sand);
		geometry.replaceMask(13, 33); // Two successive, non-orthogonal edge contacts.
		float edge = (float) (.75 + (radius + .01) / Math.sqrt(2));
		var response = new GranularSphereCollision(3, 3, 3, .5F, geometry, null)
				.preview(new float[]{edge, edge, .60F}, new float[]{-.2F, -.2F, 2}, radius, .05F, material, 32);
		require(response.operations() == 2 && response.z() < .70F && response.y() > .75 + radius / Math.sqrt(2)
				&& !geometry.sphereTouchesSolid(response.x(), response.y(), response.z(), radius, .5F)
				&& response.vx() + response.vy() >= 0 && response.vy() - response.vz() >= 0
				&& response.vx() * (double) response.vx() + response.vy() * (double) response.vy()
						+ response.vz() * (double) response.vz() <= 4.08,
				"successive edge contacts must advance remaining drift and retain the earlier tangent constraint");
		// Two simultaneous perpendicular faces: neither contact may undo the other.
		geometry.replaceMask(13, 0); geometry.replaceMask(10, 4); geometry.replaceMask(12, 2);
		float start = .5F + radius + .01F;
		response = new GranularSphereCollision(3, 3, 3, .5F, geometry, null)
				.preview(new float[]{start, start, .625F}, new float[]{-1, -1, .2F}, radius, .05F, material, 32);
		require(response.operations() == 1 && response.vx() >= 0 && response.vy() >= 0
				&& !geometry.sphereTouchesSolid(response.x(), response.y(), response.z(), radius, .5F),
				"simultaneous face contacts must share one admissible response");
		geometry.replaceMask(10, 0); geometry.replaceMask(12, 0); geometry.replaceMask(13, 1);
		var parcels = new GranularParcelStore(registry, 1);
		parcels.add(new GranularParcelStore.Parcel(.625F, .75F + radius + .01F, .625F, 0, -.2F, 0,
				svq, 2_600 * svq / (double) SolidVolume.SVQ_PER_BV, sand, 0, 0, (byte) 0));
		var collision = new FlipGranularCollision(3, 3, 3, .5F, geometry, null, 1);
		collision.preview(parcels, registry, .1F); collision.commit(parcels);
		for (int step = 1; step <= 8; step++) {
			var p = parcels.parcel(0);
			parcels.overwrite(0, new GranularParcelStore.Parcel(p.x(), p.y(), p.z(), 0, -.0001F, 0,
					p.solidVolumeSvq(), p.mass(), p.materialHandle(), p.solidCompositionHandle(), p.contactAge(), p.flags()));
			// A fresh collision object proves support is derived from the stored pose.
			collision = new FlipGranularCollision(3, 3, 3, .5F, geometry, null, 1);
			collision.preview(parcels, registry, .0001F); var impulse = collision.commit(parcels);
			var next = parcels.parcel(0);
			require(next.y() == p.y() && next.velocityY() == 0 && next.contactAge() == step + 1
					&& (next.flags() & GranularParcelStore.FLAG_SUPPORTED) != 0
					&& impulse.y() == next.mass() * (double) -.0001F,
					"rounded resting contact lost support or normal impulse when drift rounded to zero");
		}
	}

	private static void contactImpulsePreservesSolvedDrift() {
		var registry = BuiltinGranularDefinitions.createRegistry();
		int sand = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		var material = registry.material(sand);
		long svq = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		float radius = cc.ecisr.mmm.granular.GranularMechanics.representativeRadius(svq, .5F);
		float dt = .05F, vx = .4F, vy = -.6F, vz = .8F;
		double tangentScale = 1 - material.dynamicFriction() * vz * (1 + material.restitution()) / Math.hypot(vx, vy);
		for (boolean internal : new boolean[]{false, true}) {
			var geometry = new FlipCutCellGeometry(3, 3, 3);
			float x = .625F, y = .625F, z = Math.nextDown(1.5F - radius);
			if (internal) {
				geometry.replaceMask(13, 1); // [.5,.75]^3, approached along Z.
				z = .5F - radius - .01F;
			}
			var parcels = new GranularParcelStore(registry, 1);
			parcels.add(new GranularParcelStore.Parcel(x, y, z, vx, vy, vz,
					svq, 2_600 * svq / (double) SolidVolume.SVQ_PER_BV, sand, 0, 0, (byte) 0));
			var collision = new FlipGranularCollision(3, 3, 3, .5F, geometry, null, 1);
			require(collision.positionValid(x, y, z, svq), "contact drift fixture starts inside solid geometry");
			collision.preview(parcels, registry, dt);
			collision.commit(parcels);
			var next = parcels.parcel(0);
			require(next.x() == x + vx * dt && next.y() == y + vy * dt,
					"Z contact changed a solved tangential drift according to the axis sweep order");
			require(next.z() >= z && next.z() < z + vz * dt
					&& collision.positionValid(next.x(), next.y(), next.z(), svq),
					"contact must clip normal drift without entering solid geometry");
			require(next.velocityX() == (float) (vx * tangentScale) && next.velocityY() == (float) (vy * tangentScale)
					&& next.velocityZ() == (float) (-vz * material.restitution()),
					"contact friction and restitution must update the end-of-step velocity");
		}
		// The supported Courant range includes .6. A .27 m drift then fits h=.5,
		// but can cross an internal micro box before reaching the outer shell.
		var config = new FluidSolverConfig(.5F, 1_000F, 0, .05F, 400, 1.0e-5F, .6F, .002F, .8F);
		for (int axis = 0; axis < 3; axis++) for (boolean lower : new boolean[]{false, true}) {
			for (boolean internal : new boolean[]{false, true}) for (float step : new float[]{.01F, .027F}) {
				float[] position = {.625F, .625F, .625F}, velocity = new float[3];
				position[axis] = internal ? (lower ? .25F + radius + .01F : 1.25F - radius - .01F)
						: (lower ? radius + .01F : 1.5F - radius - .01F);
				velocity[axis] = lower ? -10F : 10F;
				velocity[(axis + 1) % 3] = .05F;
				require((10 + .05) * step <= config.cellSize() * config.maxCourantNumber(),
						"first-contact fixture must fit its supported mechanical Courant configuration");
				var geometry = new FlipCutCellGeometry(3, 3, 3);
				if (internal) {
					int[] cell = {1, 1, 1}; cell[axis] = lower ? 0 : 2;
					geometry.replaceMask((cell[2] * 3 + cell[1]) * 3 + cell[0],
							1 << (lower ? 0 : 1 << axis));
				}
				var collision = new FlipGranularCollision(3, 3, 3, .5F, geometry, null, 1);
				require(collision.positionValid(position[0], position[1], position[2], svq),
						"first-contact fixture must start clear of the internal and outer geometry");
				var parcels = new GranularParcelStore(registry, 1);
				parcels.add(new GranularParcelStore.Parcel(position[0], position[1], position[2],
						velocity[0], velocity[1], velocity[2], svq, 2_600 * svq / (double) SolidVolume.SVQ_PER_BV,
						sand, 0, 0, (byte) 0));
				collision.preview(parcels, registry, step);
				var impulse = collision.commit(parcels);
				var next = parcels.parcel(0);
				float[] actual = {next.velocityX(), next.velocityY(), next.velocityZ()};
				require(actual[axis] == (float) (-velocity[axis] * material.restitution()),
						"only the first reached wall may apply restitution, including a farther outer shell");
				require(actual[(axis + 1) % 3] == 0,
						"the first normal impulse must apply the material's static friction");
				double boundary = axis == 0 ? impulse.x() : axis == 1 ? impulse.y() : impulse.z();
				require(boundary == next.mass() * ((double) velocity[axis] - actual[axis])
						&& collision.positionValid(next.x(), next.y(), next.z(), svq),
						"the first-contact impulse and committed geometry must remain consistent");
			}
		}
	}

	private static void contactImpulseObeysCoulombCone() {
		var registry = BuiltinGranularDefinitions.createRegistry();
		var material = registry.material(registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND));
		var collision = new GranularSphereCollision(2, 2, 2, .5F, new FlipCutCellGeometry(2, 2, 2), null);
		try {
			var contact = GranularSphereCollision.class.getDeclaredMethod("impulse", double[].class,
					double[][].class, cc.ecisr.mmm.granular.ParticulateMaterial.class);
			contact.setAccessible(true);
			for (int axis = 0; axis < 3; axis++) for (float normal : new float[]{0, .01F, .2F, 2, -2}) {
				int first = (axis + 1) % 3, second = (axis + 2) % 3;
				double[] velocity = new double[3];
				velocity[axis] = normal; velocity[first] = .6F; velocity[second] = .8F;
				double speed = Math.hypot(velocity[first], velocity[second]);
				float nextNormal = (float) (-normal * material.restitution());
				if (Math.abs(nextNormal) <= .5F * .125F) nextNormal = 0;
				double impulse = Math.abs((double) nextNormal - normal);
				double expectedSpeed = speed <= material.staticFriction() * impulse ? 0
						: Math.max(0, speed - material.dynamicFriction() * impulse);
				double[][] normals = {new double[3]}; normals[0][axis] = normal > 0 ? -1 : 1;
				velocity = (double[]) contact.invoke(collision, velocity, normals, material);
				double nextSpeed = Math.hypot(velocity[first], velocity[second]);
				require((float) velocity[axis] == nextNormal && Math.abs(nextSpeed - expectedSpeed) < 2 * Math.ulp(1F),
						"Coulomb friction must use the actual normal impulse including the resting dead zone");
				require(speed - nextSpeed <= material.staticFriction() * impulse + 2 * Math.ulp(1F)
						&& nextSpeed * nextSpeed + nextNormal * (double) nextNormal <= speed * speed + normal * (double) normal,
						"contact impulse exceeded its friction cone or increased kinetic energy");
				if (normal == 0) for (int repeat = 0; repeat < 10; repeat++) {
					velocity = (double[]) contact.invoke(collision, velocity, normals, material);
					require(velocity[first] == .6F && velocity[second] == .8F,
							"zero normal impulse must preserve tangent velocity under repeated contact");
				}
			}
		} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}

	private static void committedContactMomentumCloses() {
		for (boolean held : new boolean[]{false, true}) {
			var domain = domain();
			long svq = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
			float radius = cc.ecisr.mmm.granular.GranularMechanics.representativeRadius(svq, .5F);
			addParcel(domain, .75F, .75F, held ? .75F : Math.nextDown(3F - radius), .4F, -.6F, .8F);
			domain.ensureGrid();
			var before = domain.granularState.parcels.parcel(0);
			domain.granularMechanicalStage.previewCollision(.05F);
			if (held) {
				require(domain.granularCollision.resolveCapacity(domain.granularState.parcels,
						domain.granularCoupling, domain.granularCoupling::solidSvq) == 1,
						"capacity fixture must hold the moving parcel at its original kernel");
			}
			domain.granularMechanicalStage.commitCollision();
			var after = domain.granularState.parcels.parcel(0);
			double[] initial = {before.velocityX(), before.velocityY(), before.velocityZ()};
			double[] committed = {after.velocityX(), after.velocityY(), after.velocityZ()};
			double[] boundary = {domain.lastGranularBoundaryImpulseX(), domain.lastGranularBoundaryImpulseY(),
					domain.lastGranularBoundaryImpulseZ()};
			for (int axis = 0; axis < 3; axis++) {
				double originalMomentum = before.mass() * initial[axis];
				double finalMomentum = after.mass() * committed[axis];
				require(boundary[axis] != 0 && Math.abs(finalMomentum + boundary[axis] - originalMomentum)
						<= 4 * Math.ulp(originalMomentum),
						"committed contact or capacity hold lost its signed boundary momentum on axis " + axis);
			}
			if (held) require(after.x() == before.x() && after.y() == before.y() && after.z() == before.z()
					&& after.velocityX() == 0 && after.velocityY() == 0 && after.velocityZ() == 0,
					"held parcel must commit its original position and zero velocity");
			else require(after.velocityX() != before.velocityX() && after.velocityY() != before.velocityY()
					&& after.velocityZ() < 0, "wall fixture must include tangent friction and normal restitution");
		}
	}

	private static void contactAgeSaturates() {
		var registry = BuiltinGranularDefinitions.createRegistry();
		int sand = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		var parcels = new GranularParcelStore(registry, 1);
		var deposits = new cc.ecisr.mmm.granular.GranularDepositStore(registry, 8, 8);
		long svq = 1_024;
		float radius = cc.ecisr.mmm.granular.GranularMechanics.representativeRadius(svq, .5F);
		parcels.add(new GranularParcelStore.Parcel(.5F, radius, .5F, 0, -.01F, 0,
				svq, 2_600 * svq / (double) SolidVolume.SVQ_PER_BV, sand, 0,
				Integer.MAX_VALUE, GranularParcelStore.FLAG_SUPPORTED));
		var collision = new FlipGranularCollision(2, 2, 2, .5F, new FlipCutCellGeometry(2, 2, 2), deposits, 1);
		collision.preview(parcels, registry, .05F);
		collision.commit(parcels);
		require(parcels.parcel(0).contactAge() == Integer.MAX_VALUE,
				"resting contact age must saturate without overflowing its valid range");
	}

	private static void carrierCacheFollowsAdvection() {
		FluidDomain domain = domain();
		domain.setVolume(1, 1, 1, .125F);
		int source = domain.cellIndex(1, 1, 1), target = domain.cellIndex(2, 1, 1);
		double expected = BuiltinFluidMaterials.createRegistry().dynamicViscosityAt("mmm:water", 293.15);
		require(domain.carrierViscosityPaPerS(source) == expected
				&& domain.carrierViscosityPaPerS(target) == null,
				"liquid properties must be available without any granular inventory");
		long revision = domain.carrierMutationRevision;
		java.util.Arrays.fill(domain.mac.velocityX, 1);
		java.util.Arrays.fill(domain.mac.velocityY, 0);
		java.util.Arrays.fill(domain.mac.velocityZ, 0);
		// This isolated sub-grid carrier follows its authoritative velocity.
		for (int marker = 0; marker < domain.particleCount(); marker++) domain.particles.setVelocityX(marker, 1);
		domain.particleAdvectionStage.advect(.5F);
		require(domain.carrierMutationRevision == revision, "advection fixture must retain carrier structure");
		require(domain.carrierViscosityPaPerS(source) == null
				&& domain.carrierViscosityPaPerS(target) == expected,
				"advection must invalidate spatial carrier properties despite unchanged inventory revision");
	}

	private static void thinBedRetainsFreeFlow() {
		var domain = domain();
		for (int z = 0; z < 6; z++) for (int y = 0; y < 3; y++) for (int x = 0; x < 6; x++) domain.setVolume(x, y, z, .5F);
		domain.ensureGrid();
		int sand = domain.granularState.registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long volume = SolidVolume.SVQ_PER_CELL / 4;
		for (int x = 2; x <= 3; x++) require(domain.granularState.deposits.tryAdd(domain.cellIndex(x, 1, 2),
				new cc.ecisr.mmm.granular.GranularDepositStore.SolidPortion(sand, 0, volume,
						2600 * volume / (double) SolidVolume.SVQ_PER_BV), SolidVolume.SVQ_PER_CELL), "Thin bed rejected");
		int face = domain.xFaceIndex(3, 1, 2), vertical = domain.yFaceIndex(2, 1, 2);
		java.util.Arrays.fill(domain.mac.velocityX, 1);
		java.util.Arrays.fill(domain.mac.velocityY, 1);
		domain.bodyForceStage.applyGranularPorousDamping(.05F);
		double oneStep = domain.mac.velocityX[face];
		require(oneStep > .99 && oneStep < 1 && domain.mac.velocityY[vertical] < .05,
				"Thin bed must admit tangential free flow while resisting flow through pores: " + oneStep + ", " + domain.mac.velocityY[vertical]);
		java.util.Arrays.fill(domain.mac.velocityX, 1);
		for (int step = 0; step < 100; step++) domain.bodyForceStage.applyGranularPorousDamping(.0005F);
		require(Math.abs(domain.mac.velocityX[face] - oneStep) < 2e-5,
				"Porous free-flow response changed under time refinement");
	}

	private static void pressureReferenceDoesNotAccelerateMatter() {
		var domain = domain();
		for (int z = 0; z < 6; z++) for (int y = 0; y < 3; y++) for (int x = 0; x < 6; x++) domain.setVolume(x, y, z, 1);
		addParcel(domain, 1.43F, 1.5F, 1.39F, 0, 0, 0);
		domain.sealDomainBoundary();
		domain.bootstrapGasState(null);
		domain.ensureGrid();
		float dt = 1.0e-6F;
		domain.pressureStage.solve(dt);
		// Changing the pressure origin leaves every physical pressure difference unchanged.
		// The resulting gas storage terms are much larger than the pressure tolerance.
		domain.pressureReference -= 1_000_000;
		var system = new PressureSystem(domain);
		system.prepareGranular(dt);
		var result = system.solve(domain.projection.fluidUnknownCount + domain.projection.gasUnknownCount,
				domain.config.pressureTolerance());
		require(result.converged(), "gas pressure increments must converge independently of the pressure origin");
		require(system.projectGranular(dt), "reference test must exercise the joint granular pressure response");
		require(pressureEnergy(domain) + solidEnergy(domain) < 1.0e-12,
				"a uniform pressure reference shift must not create liquid or granular kinetic energy");
	}

	private static void implicitCarrierValidity() {
		var domain = domain();
		for (int z = 0; z < 6; z++) for (int y = 0; y < 6; y++) for (int x = 0; x < 6; x++) domain.setVolume(x, y, z, 1);
		addParcel(domain, 1.26F, 1.28F, 1.29F, 250, 0, 0);
		domain.ensureGrid();
		domain.granularMechanicalStage.validateVelocities();
		double before = pressureEnergy(domain) + solidEnergy(domain);
		float dt = domain.config.maxCourantNumber() * domain.config.cellSize() / 250;
		domain.granularMechanicalStage.predictVelocities(dt);
		domain.pressureStage.solve(dt);
		domain.pressureStage.projectVelocities(dt);
		domain.granularMechanicalStage.validateVelocities();
		require(pressureEnergy(domain) + solidEnergy(domain) < before,
				"implicit drag must dissipate an initially unresolved relative velocity into a valid solution");
	}

	private static FluidDomain domain() {
		var domain = new FluidDomain(6, 6, 6,
				new FluidSolverConfig(0.5F, 1_000F, 0, 0.05F, 400, 1.0e-5F, 0.45F, 0.002F, 0.8F), 4096);
		domain.bindGranular(BuiltinGranularDefinitions.createRegistry(),
				new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE),
				BuiltinFluidMaterials.createRegistry());
		return domain;
	}

	private static void addParcel(FluidDomain domain, float x, float y, float z, float vx, float vy, float vz) {
		int sand = domain.granularState.registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long volume = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		double mass = volume / (double) SolidVolume.SVQ_PER_BV
				* domain.granularState.registry.material(sand).referenceDensity();
		domain.granularState.parcels.add(new GranularParcelStore.Parcel(x, y, z, vx, vy, vz,
				volume, mass, sand, 0, 0, (byte) 0));
	}

	private static void sharedCarrierExchange(boolean massWeighted) {
		var domain = domain();
		domain.setVolume(2, 2, 2, 0.001F);
		if (massWeighted) for (int p = 0; p < domain.particles.count(); p++) {
			domain.particles.setMass(p, domain.particles.mass(p) * 13.6);
		}
		for (int p = 0; p < 32; p++) addParcel(domain,
				1.26F + 0.005F * (p % 4), 1.28F + 0.005F * (p % 3), 1.29F,
				1, -0.5F, 0.25F);
		domain.ensureGrid();
		require(domain.variableDensityActive == massWeighted, "both P2G mass conventions are exercised");
		double beforeEnergy = (pressureEnergy(domain) + solidEnergy(domain));
		double[] beforeMomentum = pressureMomentum(domain);
		relaxDrag(domain, 0.05F);
		double afterEnergy = (pressureEnergy(domain) + solidEnergy(domain));
		double[] afterMomentum = pressureMomentum(domain);
		for (int axis = 0; axis < 3; axis++) require(Math.abs(afterMomentum[axis] - beforeMomentum[axis]) < 2.0e-5,
				"shared carrier drag lost momentum on axis " + axis + ": " + (afterMomentum[axis] - beforeMomentum[axis]));
		require(afterEnergy < beforeEnergy - 1.0e-5, "shared carrier drag must dissipate relative kinetic energy");

	}

	private static void relaxDrag(FluidDomain domain, float dt) {
		var pressure = new GranularPressureCoupling(domain);
		pressure.prepare(dt);
		var drag = new GranularImplicitDrag(domain, pressure);
		drag.prepare(dt);
		int iteration = 0;
		while (drag.updateResistance()) require(++iteration < 32, "shared drag nonlinear solve did not converge");
		drag.project();
	}

	private static void implicitSharedCarrier() {
		double[] finalEnergy = new double[2];
		for (int order = 0; order < 2; order++) {
			var domain = domain();
			for (int z = 0; z < 6; z++) for (int y = 0; y < 6; y++) for (int x = 0; x < 6; x++) domain.setVolume(x, y, z, 1);
			for (int p = 0; p < 16; p++) {
				int ordinal = order == 0 ? p : 15 - p;
				addParcel(domain, 1.26F, 1.28F, 1.29F, (ordinal - 8) * 0.1F, -0.5F, 0.25F);
			}
			domain.ensureGrid();
			double beforeEnergy = pressureEnergy(domain) + solidEnergy(domain);
			double[] beforeMomentum = pressureMomentum(domain);
			relaxDrag(domain, 0.05F);
			finalEnergy[order] = pressureEnergy(domain) + solidEnergy(domain);
			double[] afterMomentum = pressureMomentum(domain);
			for (int axis = 0; axis < 3; axis++) require(Math.abs(afterMomentum[axis] - beforeMomentum[axis]) < 2.0e-5,
					"implicit drag must conserve finite-volume momentum on axis " + axis);
			require(finalEnergy[order] < beforeEnergy - 1.0e-3, "implicit shared drag must dissipate kinetic energy");
		}
		require(Math.abs(finalEnergy[0] - finalEnergy[1]) < 1.0e-5, "parcel order must not change the shared drag solution");
	}

	private static double[] pressureMomentum(FluidDomain domain) {
		double[] result = new double[3];
		double h = domain.config.cellSize();
		for (int axis = 0; axis < 3; axis++) for (int z = 0; z < 6; z++) for (int y = 0; y < 6; y++) for (int x = 0; x < 6; x++) {
			int nx = x + (axis == 0 ? 1 : 0), ny = y + (axis == 1 ? 1 : 0), nz = z + (axis == 2 ? 1 : 0);
			if (nx >= 6 || ny >= 6 || nz >= 6) continue;
			int first = domain.cellIndex(x, y, z), second = domain.cellIndex(nx, ny, nz);
			float open = domain.pressureOpenFaceFromCell(first, second, nx - x, ny - y, nz - z);
			double velocity = axis == 0 ? domain.mac.velocityX[domain.xFaceIndex(nx, y, z)] : axis == 1
					? domain.mac.velocityY[domain.yFaceIndex(x, ny, z)] : domain.mac.velocityZ[domain.zFaceIndex(x, y, nz)];
			result[axis] += faceMass(domain, axis, x, y, z, nx, ny, nz, first, second, open) * velocity;
		}
		for (int p = 0; p < domain.granularState.parcels.count(); p++) {
			var parcel = domain.granularState.parcels.parcel(p);
			result[0] += parcel.mass() * parcel.velocityX();
			result[1] += parcel.mass() * parcel.velocityY();
			result[2] += parcel.mass() * parcel.velocityZ();
		}
		return result;
	}

	private static void continuousLiquidContact() {
		double[] velocities = new double[2];
		for (int side = 0; side < 2; side++) {
			var domain = domain();
			domain.setVolume(2, 2, 2, 0.5F);
			addParcel(domain, 1.5F + (side == 0 ? -1.0e-5F : 1.0e-5F), 1.25F, 1.25F, 1, 0, 0);
			domain.ensureGrid();
			double before = (pressureEnergy(domain) + solidEnergy(domain));
			var fluid = domain.granularMechanicalStage.sample(1.5F + (side == 0 ? -1.0e-5F : 1.0e-5F), 1.25F, 1.25F);
			relaxDrag(domain, 0.01F);
			velocities[side] = domain.granularState.parcels.parcel(0).velocityX();
			require(fluid.hasLiquid() && velocities[side] < 0.99
					&& (pressureEnergy(domain) + solidEnergy(domain)) <= before * (1 + 2.0e-6),
					"partial parcel contact lost drag or created energy across the liquid-cell boundary");
		}
		require(Math.abs(velocities[0] - velocities[1]) < 1.0e-4,
				"drag jumped when a parcel center crossed a cell face");
	}

	private static double solidEnergy(FluidDomain domain) {
		double energy = 0;
		for (int p = 0; p < domain.granularState.parcels.count(); p++) {
			var parcel = domain.granularState.parcels.parcel(p);
			energy += 0.5 * parcel.mass() * (parcel.velocityX() * (double) parcel.velocityX()
					+ parcel.velocityY() * (double) parcel.velocityY() + parcel.velocityZ() * (double) parcel.velocityZ());
		}
		return energy;
	}

	private static void finitePressureResponse() {
		var domain = domain();
		for (int z = 0; z < 6; z++) for (int y = 0; y < 6; y++) for (int x = 0; x < 6; x++) domain.setVolume(x, y, z, 1);
		for (int p = 0; p < 60; p++) addParcel(domain, 1.75F, 1.75F, 1.75F, 0.25F, -1, 0.5F);
		domain.ensureGrid();
		double before = pressureEnergy(domain) + solidEnergy(domain);
		double solidBefore = solidEnergy(domain);
		var coupling = new GranularPressureCoupling(domain);
		coupling.prepare(0.05F);
		domain.pressureStage.solve(0.05F);
		domain.pressureStage.projectVelocities(0.05F);
		double liquidAfter = pressureEnergy(domain), solidAfter = solidEnergy(domain);
		require(liquidAfter > 1.0e-3 && solidAfter < solidBefore - 1.0e-3,
				"finite parcel pressure must exchange work with the liquid");
		require(liquidAfter + solidAfter <= before * (1 + 2.0e-6),
				"coupled pressure injected kinetic energy: before=" + before + ", after=" + (liquidAfter + solidAfter));
		// Check the independent finite-volume continuity equation after both velocities were projected.
		// The pressure solve holds the predictor's swept geometry fixed. Check both
		// projected velocities against that same operator, including its transpose.
		double[] velocity = new double[3 * domain.granularState.parcels.count()], solidRates = new double[2 * domain.cellCount + 1];
		for (int p = 0; p < domain.granularState.parcels.count(); p++) {
			var parcel = domain.granularState.parcels.parcel(p);
			velocity[3 * p] = parcel.velocityX(); velocity[3 * p + 1] = parcel.velocityY(); velocity[3 * p + 2] = parcel.velocityZ();
		}
		coupling.addVelocityDivergence(velocity, 0, solidRates, -1);
		for (int ordinal = 0; ordinal < domain.projection.fluidUnknownCount; ordinal++) {
			int cell = domain.projection.fluidUnknownCells[ordinal];
			int x = cell % 6, y = cell / 6 % 6, z = cell / 36;
			double div = (domain.pressureXFaceOpen(x + 1, y, z) * domain.mac.velocityX[domain.xFaceIndex(x + 1, y, z)]
					- domain.pressureXFaceOpen(x, y, z) * domain.mac.velocityX[domain.xFaceIndex(x, y, z)]
					+ domain.pressureYFaceOpen(x, y + 1, z) * domain.mac.velocityY[domain.yFaceIndex(x, y + 1, z)]
					- domain.pressureYFaceOpen(x, y, z) * domain.mac.velocityY[domain.yFaceIndex(x, y, z)]
					+ domain.pressureZFaceOpen(x, y, z + 1) * domain.mac.velocityZ[domain.zFaceIndex(x, y, z + 1)]
					- domain.pressureZFaceOpen(x, y, z) * domain.mac.velocityZ[domain.zFaceIndex(x, y, z)]) / domain.config.cellSize();
			double solidRate = solidRates[cell];
			// Float pressure/velocity writes and the parcel reduction can cancel to almost zero.
			// Bound roundoff at the fixture's original 1 m/s velocity scale, not the cancelled flux.
			double tolerance = 2 * domain.granularState.parcels.count() * Math.ulp(1.0F) / domain.config.cellSize();
			require(Math.abs(div - solidRate) < tolerance, "liquid and solid velocities disagree on volume flux: " + (div - solidRate));
		}
	}

	private static void sweptOccupancyConservation() {
		for (float[] position : new float[][] {{1.749F, 1.751F, 1.749F}, {.2F, 1.751F, 1.749F}}) {
			var domain = domain(); addParcel(domain, position[0], position[1], position[2], .5F, -.5F, .5F);
			var p = domain.granularState.parcels.parcel(0); double dt = .05;
			var gradient = new SweptGranularVolumeGradient(domain); int count = gradient.build(p, dt);
			domain.granularCoupling.rebuild(domain.granularState.parcels, domain.granularState.deposits);
			long[] before = domain.granularCoupling.copySolidSvq();
			domain.granularNextCoupling.rebuild(domain.granularState.parcels, domain.granularState.deposits,
					(index, parcel) -> new cc.ecisr.mmm.granular.GranularCouplingFields.Position(
							(float) (p.x() + dt * p.velocityX()), (float) (p.y() + dt * p.velocityY()), (float) (p.z() + dt * p.velocityZ())));
			double sum = 0; boolean crossed = false;
			for (int slot = 0; slot < count; slot++) {
				int cell = gradient.cells[slot];
				double change = dt * (gradient.x[slot] * p.velocityX() + gradient.y[slot] * p.velocityY() + gradient.z[slot] * p.velocityZ());
				double expected = (domain.granularNextCoupling.solidSvq(cell) - before[cell]) / (double) SolidVolume.SVQ_PER_CELL;
				require(Math.abs(change - expected) < 2.0 / SolidVolume.SVQ_PER_CELL,
						"Swept solid gradient missed actual SVQ displacement at cell " + cell);
				sum += change; crossed |= before[cell] == 0 && expected > 0;
			}
			require(crossed && Math.abs(sum) < 32 * Math.ulp(p.solidVolumeSvq() / (double) SolidVolume.SVQ_PER_CELL),
					"Swept support failed to enter a new cell or changed total solid volume");
		}
	}

	private static void sweptKnotDirectionsMatchHatKernel() {
		var domain = domain();
		var gradient = new SweptGranularVolumeGradient(domain);
		int sand = domain.granularState.registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long svq = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		double fraction = svq / (double) SolidVolume.SVQ_PER_CELL;
		for (int axis = 0; axis < 3; axis++) for (int reflection = 0; reflection < 8; reflection++) {
			float[] position = new float[3];
			for (int coordinate = 0; coordinate < 3; coordinate++) position[coordinate] = (reflection & 1 << coordinate) == 0 ? .75F : 2.25F;
			double[] velocity = new double[3]; velocity[axis] = (reflection & 1 << axis) == 0 ? .5 : -.5;
			var parcel = new GranularParcelStore.Parcel(position[0], position[1], position[2], (float) velocity[0], (float) velocity[1], (float) velocity[2],
					svq, 2_600 * svq / (double) SolidVolume.SVQ_PER_BV, sand, 0, 0, (byte) 0);
			for (int power = 3; power <= 20; power++) {
				double dt = Math.scalb(1, -power);
				int count = gradient.build(parcel, dt);
				double[] changes = new double[domain.cellCount];
				for (int slot = 0; slot < count; slot++) changes[gradient.cells[slot]] = dt
						* (gradient.x[slot] * velocity[0] + gradient.y[slot] * velocity[1] + gradient.z[slot] * velocity[2]);
				for (int cell = 0; cell < changes.length; cell++) {
					int x = cell % domain.sizeX, y = cell / domain.sizeX % domain.sizeY, z = cell / (domain.sizeX * domain.sizeY);
					double old = interiorHatKernel(position[0], position[1], position[2], x, y, z);
					double next = interiorHatKernel(position[0] + dt * velocity[0], position[1] + dt * velocity[1],
							position[2] + dt * velocity[2], x, y, z);
					require(changes[cell] == fraction * (next - old),
							"selected knot direction disagrees with the independent hat kernel after axis reflection");
				}
			}
			// The sampled coordinate rounds back to the exact knot; its entered side still determines the derivative.
			int count = gradient.build(parcel, Math.scalb(1, -60));
			int oldCell = domain.cellIndex((int) (position[0] / .5 - .5), (int) (position[1] / .5 - .5), (int) (position[2] / .5 - .5));
			double rate = 0;
			for (int slot = 0; slot < count; slot++) if (gradient.cells[slot] == oldCell) {
				rate = gradient.x[slot] * velocity[0] + gradient.y[slot] * velocity[1] + gradient.z[slot] * velocity[2];
			}
			require(rate == -fraction, "a sub-ULP Gauss coordinate chose the outgoing side of its knot");
		}
	}

	private static double interiorHatKernel(double x, double y, double z, int cx, int cy, int cz) {
		return Math.max(0, 1 - Math.abs(x / .5 - .5 - cx)) * Math.max(0, 1 - Math.abs(y / .5 - .5 - cy))
				* Math.max(0, 1 - Math.abs(z / .5 - .5 - cz));
	}

	private static void hydrostaticParcelForce() {
		var domain = domain();
		for (int z = 0; z < 6; z++) for (int y = 0; y < 6; y++) for (int x = 0; x < 6; x++) domain.setVolume(x, y, z, 1);
		addParcel(domain, 1.43F, 1.62F, 1.39F, 0, 0, 0);
		domain.ensureGrid();
		domain.pressureStage.solve(0.05F);
		for (int cell = 0; cell < domain.cellCount; cell++) {
			int y = cell / 6 % 6;
			domain.projection.pressure[cell] = 1_000F * 9.81F * (3 - (y + 0.5F) * 0.5F);
		}
		var coupling = new GranularPressureCoupling(domain);
		coupling.prepare(0.05F);
		coupling.project(0.05F);
		var p = domain.granularState.parcels.parcel(0);
		double expected = 0.05 * 9.81 * 1_000 * p.solidVolumeSvq() / SolidVolume.SVQ_PER_BV / p.mass();
		require(Math.abs(p.velocityY() - expected) < 2.0e-7 && Math.abs(p.velocityX()) < 1.0e-7
				&& Math.abs(p.velocityZ()) < 1.0e-7, "pressure gradient must give Archimedes buoyancy without a second buoyancy impulse");
	}

	private static void sharedGasPressureAlgebra() {
		var domain = domain();
		for (int z = 0; z < 6; z++) for (int y = 0; y < 3; y++) for (int x = 0; x < 6; x++) domain.setVolume(x, y, z, 1);
		addParcel(domain, 1.43F, 1.5F, 1.39F, 0, 0, 0);
		domain.sealDomainBoundary();
		domain.bootstrapGasState(null);
		domain.ensureGrid();
		domain.pressureStage.solve(0.05F);
		require(domain.projection.gasUnknownCount == 1, "mixed parcel support must reach the sealed gas unknown");
		var coupling = new GranularPressureCoupling(domain);
		coupling.prepare(0.05F);
		coupling.rebuildDiagonal();
		int cells = domain.cellCount, node = domain.projection.gasUnknownNodes[0];
		float[] a = new float[cells], b = new float[cells], ag = new float[cells + 1], bg = new float[cells + 1];
		float[] aa = new float[cells], ab = new float[cells], aag = new float[cells + 1], abg = new float[cells + 1];
		java.util.Arrays.fill(a, 1); ag[node] = 1;
		coupling.apply(a, ag, aa, aag);
		for (float value : aa) require(Math.abs(value) < 1.0e-12, "uniform liquid/gas pressure must exert no parcel force");
		require(Math.abs(aag[node]) < 1.0e-12, "uniform pressure must preserve gas volume");
		java.util.Arrays.fill(a, 0); java.util.Arrays.fill(aa, 0); java.util.Arrays.fill(aag, 0);
		coupling.apply(a, ag, aa, aag);
		require(coupling.gasDiagonal(node) > 0 && aag[node] == coupling.gasDiagonal(node),
				"gas diagonal must square the merged support gradient");
		for (int cell = 0; cell < cells; cell++) { a[cell] = (cell % 7) - 3; b[cell] = (cell % 11) - 5; }
		bg[node] = -2;
		java.util.Arrays.fill(aa, 0); java.util.Arrays.fill(aag, 0);
		coupling.apply(a, ag, aa, aag); coupling.apply(b, bg, ab, abg);
		double aAb = ag[node] * abg[node], bAa = bg[node] * aag[node], aAa = ag[node] * aag[node];
		for (int cell = 0; cell < cells; cell++) {
			aAb += a[cell] * (double) ab[cell]; bAa += b[cell] * (double) aa[cell]; aAa += a[cell] * (double) aa[cell];
		}
		require(Math.abs(aAb - bAa) < 1.0e-6 && aAa >= 0,
				"mixed liquid/gas parcel pressure block must be symmetric positive semidefinite");
		var drag = new GranularImplicitDrag(domain, coupling);
		drag.prepare(0.05F);
		double[] fullA = new double[2 * cells + 1], fullB = new double[2 * cells + 1];
		double[] responseA = new double[2 * cells + 1], responseB = new double[2 * cells + 1];
		for (int cell = 0; cell < cells; cell++) { fullA[cell] = a[cell]; fullB[cell] = b[cell]; }
		fullA[cells + node] = ag[node]; fullB[cells + node] = bg[node];
		drag.applyMatrix(fullA, responseA); drag.applyMatrix(fullB, responseB);
		double crossAB = 0, crossBA = 0, quadratic = 0;
		for (int row = 0; row < fullA.length; row++) {
			crossAB += fullA[row] * responseB[row]; crossBA += fullB[row] * responseA[row];
			quadratic += fullA[row] * responseA[row];
		}
		require(Math.abs(crossAB - crossBA) < 1.0e-10 * Math.max(1, Math.abs(crossAB)) && quadratic > 0,
				"shared drag and compressible gas must form one symmetric positive pressure operator");
		int[] unknowns = new int[fullA.length];
		double[] diagonal = new double[fullA.length];
		int count = 0;
		for (int ordinal = 0; ordinal < domain.projection.fluidUnknownCount; ordinal++) {
			int cell = domain.projection.fluidUnknownCells[ordinal];
			unknowns[count++] = cell;
			diagonal[cell] = domain.projection.matrixDiagonal[cell] / 0.25 + coupling.liquidDiagonal(cell);
		}
		unknowns[count++] = cells + node;
		diagonal[cells + node] = domain.projection.gasMatrixDiagonal[node] + coupling.gasDiagonal(node);
		var preconditioner = new GranularPressurePreconditioner(domain);
		preconditioner.prepare(unknowns, count, diagonal);
		preconditioner.apply(unknowns, count, fullA, responseA);
		preconditioner.apply(unknowns, count, fullB, responseB);
		crossAB = crossBA = quadratic = 0;
		for (int ordinal = 0; ordinal < count; ordinal++) {
			int row = unknowns[ordinal];
			crossAB += fullA[row] * responseB[row]; crossBA += fullB[row] * responseA[row];
			quadratic += fullA[row] * responseA[row];
		}
		require(Math.abs(crossAB - crossBA) < 1.0e-10 * Math.max(1, Math.abs(crossAB)) && quadratic > 0,
				"liquid/gas triangular preconditioning must preserve symmetry and positive definiteness");
	}

	/** Kinetic metric of the finite-volume pressure operator (including pore aperture). */
	private static double pressureEnergy(FluidDomain domain) {
		double energy = 0, h = domain.config.cellSize();
		for (int axis = 0; axis < 3; axis++) for (int z = 0; z < 6; z++) for (int y = 0; y < 6; y++) for (int x = 0; x < 6; x++) {
			int nx = x + (axis == 0 ? 1 : 0), ny = y + (axis == 1 ? 1 : 0), nz = z + (axis == 2 ? 1 : 0);
			if (nx >= 6 || ny >= 6 || nz >= 6) continue;
			int first = domain.cellIndex(x, y, z), second = domain.cellIndex(nx, ny, nz);
			float open = axis == 0 ? domain.pressureXFaceOpen(nx, y, z) : axis == 1
					? domain.pressureYFaceOpen(x, ny, z) : domain.pressureZFaceOpen(x, y, nz);
			double velocity = axis == 0 ? domain.mac.velocityX[domain.xFaceIndex(nx, y, z)] : axis == 1
					? domain.mac.velocityY[domain.yFaceIndex(x, ny, z)] : domain.mac.velocityZ[domain.zFaceIndex(x, y, nz)];
			energy += 0.5 * faceMass(domain, axis, x, y, z, nx, ny, nz, first, second, open) * velocity * velocity;
		}
		return energy;
	}

	private static double faceMass(FluidDomain domain, int axis, int x, int y, int z,
	                               int nx, int ny, int nz, int first, int second, float open) {
		if (domain.isFluid(first) || domain.isFluid(second)) {
			double h = domain.config.cellSize();
			return domain.config.density() * h * h * h * open
					/ domain.pressureFaceCoupling(first, second, nx - x, ny - y, nz - z).mobilityScale();
		}
		if (!domain.carrierFaceActive(first, second, open)) return 0;
		double p2g = axis == 0 ? domain.mac.p2gWeightX[domain.xFaceIndex(nx, y, z)] : axis == 1
				? domain.mac.p2gWeightY[domain.yFaceIndex(x, ny, z)] : domain.mac.p2gWeightZ[domain.zFaceIndex(x, y, nz)];
		return domain.unitScale.kilograms(p2g * (domain.variableDensityActive ? 1 : domain.config.density()));
	}

	private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
