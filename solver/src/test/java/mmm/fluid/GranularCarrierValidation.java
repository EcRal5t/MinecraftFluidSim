package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import mmm.granular.BuiltinGranularDefinitions;
import mmm.granular.GranularLimits;
import mmm.granular.GranularParcelStore;
import mmm.granular.SolidVolume;

/** Physical invariants at the production MAC/parcel and pressure/parcel interfaces. */
public final class GranularCarrierValidation {
	private GranularCarrierValidation() { }

	public static void main(String[] args) {
		if (args.length == 0) validate();
		else if (args.length == 1 && args[0].equals("--contacts")) validateContacts();
		else throw new IllegalArgumentException("Expected no arguments or --contacts");
		System.out.println("GranularCarrierValidation PASS");
	}

	public static void validate() {
		validateContacts();
		mechanicalAuditIncludesGranularEnergy();
		carrierCacheFollowsAdvection();
		for (boolean massWeighted : new boolean[]{false, true}) sharedCarrierExchange(massWeighted);
		finitePressureResponse();
		cellOccupancyVolumeRate();
		internalPressureImpulseCloses();
		hydrostaticParcelForce();
		sharedGasPressureAlgebra();
		uniformCavityPressureDoesNotAccelerateMatter();
		continuousLiquidContact();
		double below = mmm.granular.GranularMechanics.dragResistanceFactor(Math.nextDown(1_000.0));
		double above = mmm.granular.GranularMechanics.dragResistanceFactor(Math.nextUp(1_000.0));
		require(Math.abs(below - above) < 1.0e-12, "drag resistance must remain continuous at the branch transition");
		implicitSharedCarrier();
		implicitCarrierValidity();
		thinBedRetainsFreeFlow();
	}

	private static void validateContacts() {
		sphereMicroBoxContacts();
		activeCompactionRespectsThirdSphere();
		depositContactsRespectCellGeometry();
		depositPathsRespectSuspendedBeds();
		cutBedGeometryMatchesFinalPlan();
		relaxationRequiresAnActualHorizontalPath();
		straightSphereContactsAreSymmetric();
		contactImpulsePreservesSolvedDrift();
		contactImpulseObeysCoulombCone();
		persistentFixedContactsKeepMaterialResponse();
		committedContactMomentumCloses();
		activePairContactsPreserveMechanicalMoments();
		commonContactsMatchMassMetricProjection();
		capacityHoldRequiresActualSupport();
		contactAgeSaturates();
	}

	private static void sphereMicroBoxContacts() {
		var admissionRegistry = BuiltinGranularDefinitions.createRegistry();
		int admissionMaterial = admissionRegistry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long admissionVolume = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		float admissionRadius = mmm.granular.GranularMechanics.representativeRadius(admissionVolume, .5F);
		for (int axis = 0; axis < 3; axis++) {
			float[] center = {.25F, .25F, .25F}; center[axis] = Math.nextDown(.25F);
			var live = new GranularParcelStore(admissionRegistry, 1);
			live.add(new GranularParcelStore.Parcel(center[0], center[1], center[2], 0, 0, 0,
					admissionVolume, mmm.units.Mass.fromKilograms(1), admissionMaterial, 0, 0, (byte) 0));
			var created = new GranularSpawnAdmission(3, 3, 3, .5F, 1);
			created.append(center[0], center[1], center[2], admissionVolume);
			var restored = new GranularSpawnAdmission(3, 3, 3, .5F, live, 0);
			for (long volume : new long[]{admissionVolume, admissionVolume / 8}) {
				float r = mmm.granular.GranularMechanics.representativeRadius(volume, .5F);
				float tangent = (float) (center[axis] + (double) admissionRadius + r);
				double tolerance=GranularSpawnAdmission.positionTolerance(admissionRadius,r);
				for (float coordinate : new float[]{(float)(tangent-2*tolerance),(float)(tangent-.5*tolerance),tangent,(float)(tangent+tolerance)}) {
					float[] point = center.clone(); point[axis] = coordinate;
					boolean expected = !GranularSpawnAdmission.interiorsOverlap(point[0], point[1], point[2], r,
							center[0], center[1], center[2], admissionRadius);
					require(created.allows(point[0], point[1], point[2], volume) == expected
							&& restored.allows(point[0], point[1], point[2], volume) == expected,
							"created and restored center indexes disagree on representative contact admission");
				}
			}
		}
		for (int axis = 0; axis < 3; axis++) {
			float[] point = new float[3]; point[axis] = Math.nextDown(.25F);
			require(!GranularSpawnAdmission.interiorsOverlap(0, 0, 0, .125F, point[0], point[1], point[2], .125F),
					"Stored contact must admit coordinate-rounding penetration");
			for(float radius:new float[]{.125F,admissionRadius,
					mmm.granular.GranularMechanics.representativeRadius(admissionVolume/8,.5F)}) {
				double tolerance=GranularSpawnAdmission.positionTolerance(radius,radius);
				for(double fraction:new double[]{.5,2}) {
					point[axis]=(float)(2*radius-fraction*tolerance);
					require(GranularSpawnAdmission.interiorsOverlap(0,0,0,radius,point[0],point[1],point[2],radius)==(fraction>1),
							"Representative sphere admission must distinguish tolerated and resolved penetration");
				}
			}
		}
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
		float radius = mmm.granular.GranularMechanics.representativeRadius(svq, .5F);
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
				svq, mmm.units.Mass.fromKilograms(2_600 * svq / (double) SolidVolume.SVQ_PER_CUBIC_METER), sand, 0, 0, (byte) 0));
		collision.preview(parcels, registry, .05F);
		collision.commit(parcels);
		var next = parcels.parcel(0);
		require(next.x() > .42F && collision.positionValid(next.x(), next.y(), next.z(), svq)
				&& next.velocityY() < 0 && next.velocityZ() < 0,
				"sphere corner contact must use its oblique normal and preserve the remaining tangent drift");
	}

	/** Full-pair check intentionally bypasses the production spatial index. */
	public static void requireNonOverlappingRelease(mmm.granular.GranularStateSnapshot state, float h) {
		var parcels = state.activeParcels();
		float[] radii = new float[parcels.size()];
		for (int i = 0; i < parcels.size(); i++) {
			var first = parcels.get(i);
			radii[i] = mmm.granular.GranularMechanics.representativeRadius(first.solidVolumeSvq(), h);
			for (int j = 0; j < i; j++) {
				var second = parcels.get(j);
				if (GranularSpawnAdmission.interiorsOverlap(first.x(), first.y(), first.z(), radii[i],
						second.x(), second.y(), second.z(), radii[j]))
					throw new AssertionError("dry release spheres overlap: first=" + i + ", second=" + j);
			}
		}
	}

	private static void activeCompactionRespectsThirdSphere() {
		var domain = domain(); var granular = domain.granularState; var parcels = granular.parcels;
		int sand = granular.registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long maximum = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		for (float[] point : new float[][]{{.65F, .6F, .65F}, {.65F, .9F, .65F}, {.79F, .75F, .65F}}) {
			long svq = parcels.count() < 2 ? maximum / 2 : maximum;
			parcels.add(new GranularParcelStore.Parcel(point[0], point[1], point[2], 0, 0, 0, svq,
					mmm.units.Mass.fromKilograms(2_600 * svq / (double) SolidVolume.SVQ_PER_CUBIC_METER), sand, 0, 0, (byte) 0));
		}
		requireNonOverlappingRelease(domain.exportGranularState(), domain.config.cellSize());
		var before = java.util.stream.IntStream.range(0, 3).mapToObj(parcels::parcel).toList();
		long revision = parcels.mutationRevision();
		var audit = mmm.granular.GranularAuditLedger.captureInventory(granular.registry, parcels, granular.deposits, null);
		float r = mmm.granular.GranularMechanics.representativeRadius(maximum, domain.config.cellSize());
		require(GranularSpawnAdmission.interiorsOverlap(.65F, .75F, .65F, r, .79F, .75F, .65F, r),
				"third-sphere fixture must obstruct the otherwise compatible merged representative");
		domain.granularTransitionStage.step();
		require(parcels.count() == 3 && parcels.mutationRevision() == revision
				&& before.equals(java.util.stream.IntStream.range(0, 3).mapToObj(parcels::parcel).toList())
				&& audit.equals(mmm.granular.GranularAuditLedger.captureInventory(granular.registry, parcels, granular.deposits, null)),
				"third-sphere merge rejection changed source carriers or exact inventory");
	}

	private static void depositContactsRespectCellGeometry() {
		var registry = BuiltinGranularDefinitions.createRegistry();
		int sand = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long svq = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		float radius = mmm.granular.GranularMechanics.representativeRadius(svq, .5F);
		var geometry = new FlipCutCellGeometry(3, 3, 3);
		var beds = new mmm.granular.GranularDepositStore(registry, 27, 8);
		for (int cell : new int[]{9, 10}) {
			long skeleton = (long) (SolidVolume.SVQ_PER_CELL * .62 * (cell == 9 ? .1 : .6));
			require(beds.tryAdd(cell, new mmm.granular.GranularDepositStore.SolidPortion(
					sand, 0, skeleton, mmm.units.Mass.fromKilograms(2_600.0 * skeleton / SolidVolume.SVQ_PER_CUBIC_METER)), SolidVolume.SVQ_PER_CELL), "bed fixture must fit its cell capacity");
		}
		for (float vy : new float[]{0, -.4905F}) {
			var parcels = new GranularParcelStore(registry, 1);
			parcels.add(new GranularParcelStore.Parcel(.4F, .25F, .75F, 3.2F, vy, 0,
					svq, mmm.units.Mass.fromKilograms(2_600.0 * svq / SolidVolume.SVQ_PER_CUBIC_METER), sand, 0, 0, (byte) 0));
			var collision = new FlipGranularCollision(3, 3, 3, .5F, geometry, beds, 1);
			require((3.2 + Math.abs(vy)) * .05 <= .5 * .4 && collision.positionValid(.4F, .25F, .75F, svq),
					"rising-bed fixture must have a legal start and a subcell drift");
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
		beds = new mmm.granular.GranularDepositStore(registry, 27, 8);
		for (int cell : new int[]{9, 15}) {
			long skeleton = (long) (SolidVolume.SVQ_PER_CELL * .62 * .6);
			require(beds.tryAdd(cell, new mmm.granular.GranularDepositStore.SolidPortion(
					sand, 0, skeleton, mmm.units.Mass.fromKilograms(2_600.0 * skeleton / SolidVolume.SVQ_PER_CUBIC_METER)), SolidVolume.SVQ_PER_CELL), "separated beds must fit their cells");
		}
		geometry.replaceMask(12, 255); // [.0,.5] x [.5,1] x [.5,1], upper bed rests above it.
		double top = beds.cellBulkVolumeSvq(9) * .5 / SolidVolume.SVQ_PER_CELL;
		var parcels = new GranularParcelStore(registry, 1);
		parcels.add(new GranularParcelStore.Parcel(.25F, (float) (top + radius + .01), .75F, 0, -.4F, 0,
				svq, mmm.units.Mass.fromKilograms(2_600.0 * svq / SolidVolume.SVQ_PER_CUBIC_METER), sand, 0, 0, (byte) 0));
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
					p.solidVolumeSvq(), p.massUnits(), sand, 0, p.contactAge(), p.flags()));
			collision = new FlipGranularCollision(3, 3, 3, .5F, geometry, beds, 1);
			collision.preview(parcels, registry, .05F); collision.commit(parcels);
			require(parcels.parcel(0).y() == resting.y() && parcels.parcel(0).velocityY() == 0
					&& parcels.parcel(0).contactAge() == step + 2, "cold bed contact must retain support after rounded zero drift");
		}
		var p = parcels.parcel(0); long remaining = svq / 8;
		parcels.overwrite(0, new GranularParcelStore.Parcel(p.x(), p.y(), p.z(), 0, -.4F, 0,
				remaining, mmm.units.Mass.fromKilograms(p.mass() / 8), sand, 0, p.contactAge(), p.flags()));
		require(parcels.parcel(0).contactAge() == 0 && parcels.parcel(0).flags() == 0, "changed representative radius must invalidate the old contact");
		collision.preview(parcels, registry, .05F); collision.commit(parcels);
		require(parcels.parcel(0).contactAge() == 0 && parcels.parcel(0).velocityY() < 0, "a shrunken sphere must fall through its newly vacated gap");
		collision.preview(parcels, registry, .05F); collision.commit(parcels);
		require(parcels.parcel(0).contactAge() == 1 && parcels.parcel(0).velocityY() == 0,
				"a shrunken sphere must reacquire the actual lower bed at its new radius");
	}

	private static void depositPathsRespectSuspendedBeds() {
		var domain = new FluidDomain(3, 4, 3,
				new FluidSolverConfig(.5F, 1_000F, 9.81F, .05F, 400, 1.0e-5F, .002F, .8F), 512);
		domain.bindGranular(BuiltinGranularDefinitions.createRegistry(),
				new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE), BuiltinFluidMaterials.createRegistry());
		domain.sealDomainBoundary();
		var state = domain.granularState;
		int sand = state.registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long volume = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		long skeleton = (long) (SolidVolume.SVQ_PER_CELL * .62);
		for (int z = 0; z < 3; z++) for (int x = 0; x < 3; x++) require(state.deposits.tryAdd(domain.domainFlowFields.cellIndex(x, 1, z),
				new mmm.granular.GranularDepositStore.SolidPortion(sand, 0, skeleton,
						mmm.units.Mass.fromKilograms(2_600.0 * skeleton / SolidVolume.SVQ_PER_CUBIC_METER)), SolidVolume.SVQ_PER_CELL), "suspended bed must fit each cell");
		int barrier = domain.domainFlowFields.cellIndex(1, 1, 1);
		double top = (1 + state.deposits.cellBulkVolumeSvq(barrier) / (double) SolidVolume.SVQ_PER_CELL) * .5;
		float radius = mmm.granular.GranularMechanics.representativeRadius(volume, .5F);
		float y = (float) (top + radius); if (y < top + radius) y = Math.nextUp(y);
		state.parcels.add(new GranularParcelStore.Parcel(.75F, y, .75F, 0, 0, 0, volume,
				mmm.units.Mass.fromKilograms(2_600.0 * volume / SolidVolume.SVQ_PER_CUBIC_METER), sand, 0, 0, (byte) 0));
		domain.domainFlowFields.ensureGrid();
		require(domain.granularCollision.positionValid(.75F, y, .75F, volume), "bed support must start outside the actual bed");
		for (int step = 0; step < 3; step++) {
			domain.granularCollision.preview(state.parcels, state.registry, .05F);
			domain.granularCollision.commit(state.parcels);
		}
		var source = state.parcels.parcel(0);
		require(source.contactAge() == 3 && (source.flags() & GranularParcelStore.FLAG_SUPPORTED) != 0,
				"cold collision must acquire actual support without manufacturing settlement eligibility");
		long[] planned = domain.granularCoupling.copySolidSvq(), original = planned.clone();
		var environment = domain.domainStep.granularDepositEnvironment(planned);
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
		domain.domainStep.granularDepositDynamics().disturbCell(barrier);
		var exhausted = new mmm.granular.GranularDepositDynamics.Environment() {
			@Override public long geometricOpenSvq(int cell) { return environment.geometricOpenSvq(cell); }
			@Override public boolean horizontalPathOpen(int cell, int dx, int dz) { return environment.horizontalPathOpen(cell, dx, dz); }
			@Override public double shields(int cell, mmm.granular.ParticulateMaterial material) { return environment.shields(cell, material); }
			@Override public double gravity() { return environment.gravity(); }
			@Override public int verticalPathWork(float x, float from, float z, float to, long amount) {
				return GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP;
			}
			@Override public boolean verticalPathOpen(float x, float from, float z, float to, long amount) {
				throw new AssertionError("exhausted path budget must stop before geometry query");
			}
		};
		var deferred = domain.domainStep.granularDepositDynamics().step(exhausted, .05F);
		require(deferred.settledParcels() == 0 && state.parcels.parcel(0).equals(source)
				&& state.deposits.mutationRevision() == revision && java.util.Arrays.equals(planned, original)
				&& deferred.dirtyColumns() > 0, "path work exhaustion must preserve all inventory and pending columns");
		var result = domain.domainStep.granularDepositDynamics().step(environment, .05F);
		require(state.deposits.cellSolidVolumeSvq(domain.domainFlowFields.cellIndex(1, 0, 1)) == 0
				&& state.deposits.cellSolidVolumeSvq(barrier) == skeleton
				&& result.wokenParcels() == 0 && result.relaxedMoves() == 0,
				"settlement must preserve the suspended barrier and the lower cavity");
		require(result.settledParcels() == 1 && state.parcels.count() == 0
				&& state.deposits.cellSolidVolumeSvq(domain.domainFlowFields.cellIndex(1, 2, 1)) == volume,
				"the lowest reachable upper layer must accept this capacity- and potential-valid parcel");
		long tower = (long) (SolidVolume.SVQ_PER_CELL * .62 * .8);
		require(state.deposits.tryAdd(domain.domainFlowFields.cellIndex(1, 2, 1),
				new mmm.granular.GranularDepositStore.SolidPortion(sand, 0, tower,
						mmm.units.Mass.fromKilograms(2_600.0 * tower / SolidVolume.SVQ_PER_CUBIC_METER)), SolidVolume.SVQ_PER_CELL), "upper source bed must fit its layer");
		domain.granularCoupling.rebuild(state.parcels, state.deposits);
		domain.domainStep.granularDepositDynamics().disturbCell(domain.domainFlowFields.cellIndex(1, 2, 1));
		var relaxed = domain.domainStep.granularDepositDynamics().step(domain.domainStep.granularDepositEnvironment(domain.granularCoupling.copySolidSvq()), .05F);
		long upperTotal = 0;
		for (int z = 0; z < 3; z++) for (int x = 0; x < 3; x++) {
			require(state.deposits.cellSolidVolumeSvq(domain.domainFlowFields.cellIndex(x, 0, z)) == 0
					&& state.deposits.cellSolidVolumeSvq(domain.domainFlowFields.cellIndex(x, 1, z)) == skeleton,
					"neighbor relaxation must preserve every lower cavity and intervening bed");
			upperTotal += state.deposits.cellSolidVolumeSvq(domain.domainFlowFields.cellIndex(x, 2, z));
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
					new FluidSolverConfig(.5F, 1_000F, 9.81F, .05F, 400, 1.0e-5F, .002F, .8F), 512);
			domain.bindGranular(BuiltinGranularDefinitions.createRegistry(),
					new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE), BuiltinFluidMaterials.createRegistry());
			domain.sealDomainBoundary();
			var state = domain.granularState;
			int sand = state.registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND), target = domain.domainFlowFields.cellIndex(1, 0, 1);
			domain.geometry.cutCells.replaceMask(target, scenario == 0 ? 1 : 4);
			long volume = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
			if (scenario == 2) {
				long oldSkeleton = (long) (SolidVolume.SVQ_PER_CELL * .62 * .46);
				require(state.deposits.tryAdd(target, new mmm.granular.GranularDepositStore.SolidPortion(sand, 0,
						oldSkeleton, mmm.units.Mass.fromKilograms(2_600.0 * oldSkeleton / SolidVolume.SVQ_PER_CUBIC_METER)), SolidVolume.SVQ_PER_CELL * 7 / 8),
						"initial mixed-space bed must fit below the upper cut micro box");
			}
			float radius = mmm.granular.GranularMechanics.representativeRadius(volume, .5F);
			double top = state.deposits.cellBulkVolumeSvq(target) * .5 / SolidVolume.SVQ_PER_CELL;
			float y = (float) (top + radius); if (y < top + radius) y = Math.nextUp(y);
			for (int index = 0; index < (scenario == 2 ? 2 : 1); index++) state.parcels.add(new GranularParcelStore.Parcel(
					index == 0 ? .9F : .72F, y, .9F, 0, 0, 0, volume, mmm.units.Mass.fromKilograms(2_600.0 * volume / SolidVolume.SVQ_PER_CUBIC_METER), sand, 0, 0, (byte) 0));
			domain.domainFlowFields.ensureGrid();
			for (int index = 0; index < state.parcels.count(); index++) {
				var p = state.parcels.parcel(index);
				require(domain.granularCollision.positionValid(p.x(), p.y(), p.z(), volume), "cut-bed fixture needs a legal supported sphere start");
			}
			requireNonOverlappingRelease(domain.exportGranularState(), .5F);
			for (int step = 0; step < 3; step++) {
				domain.granularCollision.preview(state.parcels, state.registry, .05F); domain.granularCollision.commit(state.parcels);
			}
			var originals = new GranularParcelStore.Parcel[state.parcels.count()];
			for (int index = 0; index < originals.length; index++) {
				originals[index] = state.parcels.parcel(index);
				require(originals[index].contactAge() == 3, "cut-bed sources must acquire actual contact age");
			}
			long[] planned = domain.granularCoupling.copySolidSvq(), before = planned.clone();
			var environment = domain.domainStep.granularDepositEnvironment(planned);
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
						&& mmm.granular.GranularBedPotential.allowsSettlement(joint, .5F, 3, 4, 9.81),
						"joint cut rejection must protect a height crossing that each single source and potential guard allow");
			}
			long revision = state.deposits.mutationRevision(), oldStock = state.deposits.cellSolidVolumeSvq(target);
			domain.domainStep.granularDepositDynamics().disturbCell(target);
			var result = domain.domainStep.granularDepositDynamics().step(environment, .05F);
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
					new FluidSolverConfig(.5F, 1_000F, 9.81F, .05F, 400, 1.0e-5F, .002F, .8F), 512);
			domain.bindGranular(BuiltinGranularDefinitions.createRegistry(),
					new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE), BuiltinFluidMaterials.createRegistry());
			domain.sealDomainBoundary();
			var state = domain.granularState;
			int sand = state.registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
			int source = domain.domainFlowFields.cellIndex(1, 1, 1), portal = domain.domainFlowFields.cellIndex(2, 1, 1), receiver = domain.domainFlowFields.cellIndex(2, 1, 2);
			for (int z = 0; z < 3; z++) for (int x = 0; x < 3; x++) {
				long floor = (long) (SolidVolume.SVQ_PER_CELL * .62);
				require(state.deposits.tryAdd(domain.domainFlowFields.cellIndex(x, 0, z), new mmm.granular.GranularDepositStore.SolidPortion(
						sand, 0, floor, mmm.units.Mass.fromKilograms(2_600.0 * floor / SolidVolume.SVQ_PER_CUBIC_METER)), SolidVolume.SVQ_PER_CELL), "floor bed must fit its cell");
				domain.geometry.cutCells.replaceMask(domain.domainFlowFields.cellIndex(x, 2, z), 255);
				int cell = domain.domainFlowFields.cellIndex(x, 1, z);
				if (cell != source && cell != portal && cell != receiver) domain.geometry.cutCells.replaceMask(cell, 255);
			}
			domain.geometry.cutCells.replaceMask(portal, blocked ? 126 : 0); // Open octants 0 and 7 have no face connection.
			if (!blocked) {
				domain.geometry.cutCells.replaceMask(domain.domainFlowFields.cellIndex(1, 1, 2), 0);
				long side = (long) (SolidVolume.SVQ_PER_CELL * .62 * .3);
				require(state.deposits.tryAdd(domain.domainFlowFields.cellIndex(1, 1, 2), new mmm.granular.GranularDepositStore.SolidPortion(
						sand, 0, side, mmm.units.Mass.fromKilograms(2_600.0 * side / SolidVolume.SVQ_PER_CUBIC_METER)), SolidVolume.SVQ_PER_CELL), "open diagonal corridor must clear both adjacent corners");
			}
			for (int cell : new int[]{source, domain.domainFlowFields.cellIndex(2, 3, 1)}) {
				long volume = (long) (SolidVolume.SVQ_PER_CELL * .62 * (cell == source ? .885 : .1));
				require(state.deposits.tryAdd(cell, new mmm.granular.GranularDepositStore.SolidPortion(
						sand, 0, volume, mmm.units.Mass.fromKilograms(2_600.0 * volume / SolidVolume.SVQ_PER_CUBIC_METER)), SolidVolume.SVQ_PER_CELL), "supported upper beds must fit");
			}
			for (int cell = 0; cell < domain.cellCount; cell++) domain.geometry.solid[cell] = domain.geometry.cutCells.fullySolid(cell);
			domain.domainFlowFields.ensureGrid();
			long[] planned = domain.granularCoupling.copySolidSvq();
			var environment = domain.domainStep.granularDepositEnvironment(planned);
			for (int cell = 0; cell < state.deposits.cellCount(); cell++) require(environment.bedGeometryFits(cell, state.deposits.cellBulkVolumeSvq(cell)),
					"horizontal-path fixture must begin with every bed outside cut geometry");
			require(environment.horizontalPathOpen(source, 1, 1), "both scalar open-face routes must admit this diagonal candidate");
			if (exhausted) {
				var actual = environment;
				environment = (mmm.granular.GranularDepositDynamics.Environment) java.lang.reflect.Proxy.newProxyInstance(
						mmm.granular.GranularDepositDynamics.Environment.class.getClassLoader(),
						new Class<?>[]{mmm.granular.GranularDepositDynamics.Environment.class}, (proxy, method, args) -> {
							if (method.getName().equals("horizontalTransferPathWork")) return GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP;
							if (method.getName().equals("horizontalTransferPathOpen")) throw new AssertionError("path work must be charged before querying geometry");
							return method.invoke(actual, args);
						});
			}
			long sourceBefore = state.deposits.cellSolidVolumeSvq(source), stock = state.deposits.totalSolidVolumeSvq();
			domain.domainStep.granularDepositDynamics().disturbCell(source);
			var result = domain.domainStep.granularDepositDynamics().step(environment, .05F);
			long arrived = state.deposits.cellSolidVolumeSvq(receiver);
			require(state.deposits.totalSolidVolumeSvq() == stock && state.parcels.count() == 0 && result.wokenParcels() == 0,
					"horizontal topology admission must preserve the exact inventory");
			if (blocked) require(arrived == 0, "A disconnected horizontal corridor admitted bed material");
			else if (exhausted) require(arrived == 0 && result.dirtyColumns() > 0,
					"An unprocessed horizontal transfer must retain its receiving stock and pending work");
			else require(result.relaxedMoves() > 0 && arrived > 0 && sourceBefore - state.deposits.cellSolidVolumeSvq(source) >= arrived,
					"an actual open horizontal corridor must preserve legal source contact and permit downward relaxation: " + result + ", arrived=" + arrived);
		}
	}

	private static void straightSphereContactsAreSymmetric() {
		var registry = BuiltinGranularDefinitions.createRegistry();
		int sand = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long svq = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		float radius = mmm.granular.GranularMechanics.representativeRadius(svq, .5F);
		double positionError=GranularSpawnAdmission.positionTolerance(radius,radius),velocityError=positionError/.05;
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
						"straight sphere fixture must retain a subcell drift");
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
					require(Math.abs(endpoint[a] - analytic) <= positionError
							&& Math.abs(response[a] - 1.6 * registry.material(sand).restitution() * normal) <= velocityError,
							"radial face, edge or corner contact disagrees with the independent distance solution");
				}
				if (reference == null) reference = motion;
				float[] referencePosition = {reference.x(), reference.y(), reference.z()}, referenceVelocity = {reference.vx(), reference.vy(), reference.vz()};
				for (int a = 0; a < 3; a++) {
					boolean reflected = (reflection & 1 << a) != 0;
					double expectedPosition = reflected ? 1.25 - referencePosition[permutation[a]] : referencePosition[permutation[a]];
					double expectedVelocity = reflected ? -referenceVelocity[permutation[a]] : referenceVelocity[permutation[a]];
					require(Math.abs(endpoint[a] - expectedPosition) <= positionError
							&& Math.abs(response[a] - expectedVelocity) <= velocityError, "sphere contact changed under an axis permutation or reflection beyond representative resolution");
				}
			}
		}
		var geometry = new FlipCutCellGeometry(3, 3, 3); geometry.replaceMask(13, 1);
		var material = registry.material(sand);
		geometry.replaceMask(13, 33); // Two successive, non-orthogonal edge contacts.
		float edge = (float) (.75 + (radius + .01) / Math.sqrt(2));
		var response = new GranularSphereCollision(3, 3, 3, .5F, geometry, null)
				.preview(new float[]{edge, edge, .60F}, new float[]{-.2F, -.2F, 2}, radius, .05F, material, 32);
		require(response.operations() > 0 && response.operations() <= 8 && response.z() < .70F && response.y() > .75 + radius / Math.sqrt(2)
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
		require(response.operations() > 0 && response.operations() <= 8 && response.vx() >= 0 && response.vy() >= 0
				&& !geometry.sphereTouchesSolid(response.x(), response.y(), response.z(), radius, .5F),
				"simultaneous face contacts must share one admissible response");
		geometry.replaceMask(10, 0); geometry.replaceMask(12, 0); geometry.replaceMask(13, 1);
		var parcels = new GranularParcelStore(registry, 1);
		parcels.add(new GranularParcelStore.Parcel(.625F, .75F + radius + .01F, .625F, 0, -.2F, 0,
				svq, mmm.units.Mass.fromKilograms(2_600 * svq / (double) SolidVolume.SVQ_PER_CUBIC_METER), sand, 0, 0, (byte) 0));
		var collision = new FlipGranularCollision(3, 3, 3, .5F, geometry, null, 1);
		collision.preview(parcels, registry, .1F); collision.commit(parcels);
		for (int step = 1; step <= 8; step++) {
			var p = parcels.parcel(0);
			parcels.overwrite(0, new GranularParcelStore.Parcel(p.x(), p.y(), p.z(), 0, -.0001F, 0,
					p.solidVolumeSvq(), p.massUnits(), p.materialHandle(), p.solidCompositionHandle(), p.contactAge(), p.flags()));
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
		float radius = mmm.granular.GranularMechanics.representativeRadius(svq, .5F);
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
					svq, mmm.units.Mass.fromKilograms(2_600 * svq / (double) SolidVolume.SVQ_PER_CUBIC_METER), sand, 0, 0, (byte) 0));
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
		// A .27 m drift crosses an internal micro box inside this .5 m cell.
		var config = new FluidSolverConfig(.5F, 1_000F, 0, .05F, 400, 1.0e-5F, .002F, .8F);
		for (int axis = 0; axis < 3; axis++) for (boolean lower : new boolean[]{false, true}) {
			for (boolean internal : new boolean[]{false, true}) for (float step : new float[]{.01F, .027F}) {
				float[] position = {.625F, .625F, .625F}, velocity = new float[3];
				position[axis] = internal ? (lower ? .25F + radius + .01F : 1.25F - radius - .01F)
						: (lower ? radius + .01F : 1.5F - radius - .01F);
				velocity[axis] = lower ? -10F : 10F;
				velocity[(axis + 1) % 3] = .05F;
				require((10 + .05) * step <= config.cellSize(),
						"first-contact fixture must retain a subcell drift");
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
						velocity[0], velocity[1], velocity[2], svq, mmm.units.Mass.fromKilograms(2_600 * svq / (double) SolidVolume.SVQ_PER_CUBIC_METER),
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
					double[][].class, mmm.granular.ParticulateMaterial.class);
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

	private static void persistentFixedContactsKeepMaterialResponse() {
		var registry = BuiltinGranularDefinitions.createRegistry();
		int sand = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		var material = registry.material(sand); long svq = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		float radius = mmm.granular.GranularMechanics.representativeRadius(svq, .5F);
		float[] freePairVelocity = null;
		for (boolean touchingFloor : new boolean[]{false, true}) {
			var parcels = new GranularParcelStore(registry, 2);
			float height = touchingFloor ? radius : 1.25F;
			parcels.add(new GranularParcelStore.Parcel(1.25F, height, 1.25F, .1F, 0, 0, svq, mmm.units.Mass.fromKilograms(2), sand, 0, 0, (byte) 0));
			parcels.add(new GranularParcelStore.Parcel(Math.nextUp(1.25F + 2 * radius), height, 1.25F, -.1F, 0, 0, svq, mmm.units.Mass.fromKilograms(2), sand, 0, 0, (byte) 0));
			var collision = new FlipGranularCollision(6, 6, 6, .5F, new FlipCutCellGeometry(6, 6, 6), null, 2);
			collision.preview(parcels, registry, .05F); var reaction = collision.commit(parcels);
			float first = parcels.parcel(0).velocityX(), second = parcels.parcel(1).velocityX();
			require(first < 0 && second > 0 && Math.abs(second - first - .2F * material.restitution()) <= 2 * Math.ulp(second - first),
					"an unloaded fixed tangent changed the pair's material restitution");
			require(reaction.y() == 0 && parcels.parcel(0).velocityY() == 0 && parcels.parcel(1).velocityY() == 0,
					"horizontal pair impact generated a spurious fixed support load");
			if (freePairVelocity == null) freePairVelocity = new float[]{first, second};
			else require(first == freePairVelocity[0] && second == freePairVelocity[1],
					"geometric floor tangency changed a collision with zero fixed normal impulse");
		}
		var fixed = new GranularSphereCollision(6, 6, 6, .5F, new FlipCutCellGeometry(6, 6, 6), null);
		for (int axis = 0; axis < 3; axis++) for (int side : new int[]{-1, 1}) for (double fraction : new double[]{.5, 1}) {
			double plane = side > 0 ? radius : 3 - (double) radius;
			double[] position = {1.25, 1.25, 1.25}, incoming = new double[3];
			position[axis] = plane + side * fraction * Math.ulp((float) plane); incoming[axis] = -side;
			var normals = fixed.contactNormals(position, radius);
			var compressed = fixed.compress(incoming, normals);
			require(normals.length > 0 && compressed[axis] * side >= 0,
					"fixed support within stored-coordinate resolution must block an incoming normal velocity");
		}
		for (float incomingNormal : new float[]{-.2F, -2}) {
			int steps = incomingNormal == -.2F ? 3 : 1;
			var reference = new GranularParcelStore.Parcel[steps];
			for (int count : new int[]{1, 2}) {
				var parcels = new GranularParcelStore(registry, 2);
				parcels.add(new GranularParcelStore.Parcel(1.25F, radius, 1.25F, .6F, incomingNormal, .8F,
						svq, mmm.units.Mass.fromKilograms(2), sand, 0, 0, (byte) 0));
				if (count == 2) parcels.add(new GranularParcelStore.Parcel(2.25F, 1.25F, 2.25F, 0, 0, 0,
						svq, mmm.units.Mass.fromKilograms(3), sand, 0, 0, (byte) 0));
				var collision = new FlipGranularCollision(6, 6, 6, .5F, new FlipCutCellGeometry(6, 6, 6), null, 2);
				for (int step = 0; step < steps; step++) {
					var before = parcels.parcel(0);
					parcels.overwrite(0, new GranularParcelStore.Parcel(before.x(), before.y(), before.z(), before.velocityX(),
							incomingNormal, before.velocityZ(), svq, mmm.units.Mass.fromKilograms(2), sand, 0, before.contactAge(), before.flags()));
					before = parcels.parcel(0); collision.preview(parcels, registry, .025F); var reaction = collision.commit(parcels);
					var after = parcels.parcel(0);
					float expectedNormal = (float) (-incomingNormal * material.restitution());
					if (Math.abs(expectedNormal) <= .5F * .125F) expectedNormal = 0;
					double load = Math.abs((double) expectedNormal - incomingNormal);
					double speed = Math.sqrt(before.velocityX() * (double) before.velocityX() + before.velocityZ() * (double) before.velocityZ());
					double expectedSpeed = speed <= material.staticFriction() * load ? 0 : Math.max(0, speed - material.dynamicFriction() * load);
					double actualSpeed = Math.hypot(after.velocityX(), after.velocityZ());
					require(after.velocityY() == expectedNormal && Math.abs(actualSpeed - expectedSpeed) < 2 * Math.ulp(1F)
							&& speed - actualSpeed <= material.staticFriction() * load + 2 * Math.ulp(1F),
							"persistent fixed support lost restitution or the actual-load Coulomb response");
					require(actualSpeed < speed && after.y() == radius && collision.positionValid(after.x(), after.y(), after.z(), svq)
							&& actualSpeed * actualSpeed + after.velocityY() * (double) after.velocityY()
							<= speed * speed + incomingNormal * (double) incomingNormal,
							"fixed friction must reduce sliding speed without increasing energy or moving the supported height");
					double[] oldV = {before.velocityX(), before.velocityY(), before.velocityZ()}, newV = {after.velocityX(), after.velocityY(), after.velocityZ()};
					double[] impulse = {reaction.x(), reaction.y(), reaction.z()};
					for (int a = 0; a < 3; a++) require(impulse[a] == 2 * (oldV[a] - newV[a]),
							"fixed Coulomb response did not close the external momentum reaction");
					if (count == 1) reference[step] = after;
					else {
						var expected = reference[step];
						require(after.x() == expected.x() && after.y() == expected.y() && after.z() == expected.z()
								&& after.velocityX() == expected.velocityX() && after.velocityY() == expected.velocityY() && after.velocityZ() == expected.velocityZ(),
								"an unrelated sphere changed the same fixed-contact material response");
						var remote = parcels.parcel(1);
						require(remote.x() == 2.25F && remote.y() == 1.25F && remote.z() == 2.25F
								&& remote.velocityX() == 0 && remote.velocityY() == 0 && remote.velocityZ() == 0,
								"fixed-contact component changed a disconnected stationary sphere");
					}
				}
			}
		}
	}

	private static void committedContactMomentumCloses() {
		for (boolean held : new boolean[]{false, true}) {
			var domain = domain();
			long svq = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
			float radius = mmm.granular.GranularMechanics.representativeRadius(svq, .5F);
			addParcel(domain, .75F, .75F, held ? .75F : Math.nextDown(3F - radius), .4F, -.6F, .8F);
			domain.domainFlowFields.ensureGrid();
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

	private static void activePairContactsPreserveMechanicalMoments() {
		var registry = BuiltinGranularDefinitions.createRegistry();
		int material = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long svq = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		float radius = mmm.granular.GranularMechanics.representativeRadius(svq, .5F);
		for (int axis = 0; axis < 3; axis++) for (int sign : new int[]{-1, 1}) {
			var parcels = new GranularParcelStore(registry, 4);
			float[] first = {1.25F, 1.25F, 1.25F}, second = first.clone();
			second[axis] += sign * .25F;
			float[] firstVelocity = new float[3], secondVelocity = new float[3];
			firstVelocity[axis] = sign; secondVelocity[axis] = -sign;
			firstVelocity[(axis + 1) % 3] = .25F; secondVelocity[(axis + 1) % 3] = -.125F;
			parcels.add(new GranularParcelStore.Parcel(first[0], first[1], first[2], firstVelocity[0], firstVelocity[1], firstVelocity[2], svq, mmm.units.Mass.fromKilograms(2), material, 0, 0, (byte) 0));
			parcels.add(new GranularParcelStore.Parcel(second[0], second[1], second[2], secondVelocity[0], secondVelocity[1], secondVelocity[2], svq, mmm.units.Mass.fromKilograms(3), material, 0, 0, (byte) 0));
			var original = new GranularParcelStore.Parcel[]{parcels.parcel(0), parcels.parcel(1)};
			var collision = new FlipGranularCollision(6, 6, 6, .5F, new FlipCutCellGeometry(6, 6, 6), null, 4);
			collision.preview(parcels, registry, .1F); var boundary = collision.commit(parcels);
			require(!GranularSpawnAdmission.interiorsOverlap(parcels.parcel(0).x(), parcels.parcel(0).y(), parcels.parcel(0).z(), radius,
					parcels.parcel(1).x(), parcels.parcel(1).y(), parcels.parcel(1).z(), radius), "pair collision published overlapping float spheres");
			double energyBefore = 0, energyAfter = 0, energyRounding = 0;
			for (int a = 0; a < 3; a++) {
				double momentumBefore = 0, momentumAfter = 0, momentBefore = 0, momentAfter = 0, momentRounding = 0, velocityRounding = 0;
				for (int index = 0; index < 2; index++) {
					var old = original[index]; var next = parcels.parcel(index);
					float p = a == 0 ? old.x() : a == 1 ? old.y() : old.z(), q = a == 0 ? next.x() : a == 1 ? next.y() : next.z();
					float v = a == 0 ? old.velocityX() : a == 1 ? old.velocityY() : old.velocityZ(), w = a == 0 ? next.velocityX() : a == 1 ? next.velocityY() : next.velocityZ();
					momentumBefore += old.mass() * v; momentumAfter += next.mass() * w;
					momentBefore += old.mass() * (p + v * (double) .1F); momentAfter += next.mass() * q;
					momentRounding += old.mass() * 2 * Math.ulp(q); velocityRounding += old.mass() * Math.ulp(w);
					energyBefore += .5 * old.mass() * v * v; energyAfter += .5 * next.mass() * w * w;
					energyRounding += old.mass() * Math.ulp(w) * (Math.abs(w) + Math.ulp(w));
				}
				require(Math.abs(momentumAfter - momentumBefore) <= velocityRounding, "finite-mass pair impulse changed total momentum");
				require(Math.abs(momentAfter - momentBefore) <= momentRounding, "pair collision changed mass first moment beyond float coordinates");
				int b = (a + 1) % 3, c = (a + 2) % 3; double oldAngular = 0, nextAngular = 0, angularRounding = 0;
				for (int index = 0; index < 2; index++) {
					var old = original[index]; var next = parcels.parcel(index);
					float[] p = {old.x(), old.y(), old.z()}, q = {next.x(), next.y(), next.z()};
					float[] v = {old.velocityX(), old.velocityY(), old.velocityZ()}, w = {next.velocityX(), next.velocityY(), next.velocityZ()};
					oldAngular += old.mass() * ((double) p[b] * v[c] - (double) p[c] * v[b]);
					nextAngular += next.mass() * ((double) q[b] * w[c] - (double) q[c] * w[b]);
					angularRounding += old.mass() * (GranularSpawnAdmission.positionTolerance(radius, radius) * (Math.abs(w[b]) + Math.abs(w[c]))
							+ .05 * radius * (Math.abs(v[b]) + Math.abs(v[c]) + Math.abs(w[b]) + Math.abs(w[c]))
							+ 2 * Math.ulp(q[b]) * Math.abs(w[c]) + 2 * Math.ulp(q[c]) * Math.abs(w[b])
							+ Math.ulp(w[c]) * Math.abs(q[b]) + Math.ulp(w[b]) * Math.abs(q[c]));
				}
				require(Math.abs(nextAngular - oldAngular) <= angularRounding, "pair contact changed angular momentum beyond float publication");
			}
			require(energyAfter <= energyBefore + energyRounding && Math.abs(boundary.x()) + Math.abs(boundary.y()) + Math.abs(boundary.z()) <= 16 * Math.ulp(1.0F),
					"internal pair contact gained mechanical energy or generated an external reaction");
		}
		var chain = new GranularParcelStore(registry, 3);
		for (int index = 0; index < 3; index++) chain.add(new GranularParcelStore.Parcel(
				new float[]{1, 1.25F, 1.45F}[index], 1.25F, 1.25F, index == 0 ? 1 : 0, 0, 0,
				svq, mmm.units.Mass.fromKilograms(2), material, 0, 0, (byte) 0));
		double expectedMoment = 0;
		for (int index = 0; index < chain.count(); index++) expectedMoment += 2 * (chain.parcel(index).x() + chain.parcel(index).velocityX() * (double) .2F);
		var chainCollision = new FlipGranularCollision(6, 6, 6, .5F, new FlipCutCellGeometry(6, 6, 6), null, 3);
		chainCollision.preview(chain, registry, .2F); chainCollision.commit(chain);
		double momentum = 0, moment = 0, energy = 0, momentumBound = 0, momentBound = 0;
		for (int index = 0; index < chain.count(); index++) {
			var parcel = chain.parcel(index); momentum += 2 * parcel.velocityX(); moment += 2 * parcel.x(); energy += parcel.velocityX() * (double) parcel.velocityX();
			momentumBound += 2 * Math.ulp(parcel.velocityX()); momentBound += 4 * Math.ulp(parcel.x());
		}
		require(chain.parcel(2).velocityX() > 0 && Math.abs(momentum - 2) <= momentumBound
				&& Math.abs(moment - expectedMoment) <= momentBound && energy <= 1,
				"changed swept contact paths must reach the third sphere and preserve mechanical moments");
		var stack = new GranularParcelStore(registry, 4);
		stack.add(new GranularParcelStore.Parcel(1.25F, radius, 1.25F, 0, 0, 0, svq, mmm.units.Mass.fromKilograms(2), material, 0, 0, (byte) 0));
		stack.add(new GranularParcelStore.Parcel(1.25F, Math.nextUp(3 * radius), 1.25F, 0, 0, 0, svq, mmm.units.Mass.fromKilograms(3), material, 0, 0, (byte) 0));
		stack.add(new GranularParcelStore.Parcel(1.25F, Math.nextUp(5 * radius), 1.25F, 0, 0, 0, svq, mmm.units.Mass.fromKilograms(4), material, 0, 0, (byte) 0));
		var collision = new FlipGranularCollision(6, 6, 6, .5F, new FlipCutCellGeometry(6, 6, 6), null, 4);
		for (int step = 0; step < 3; step++) {
			for (int index = 0; index < stack.count(); index++) {
				var p = stack.parcel(index); stack.overwrite(index, new GranularParcelStore.Parcel(p.x(), p.y(), p.z(), p.velocityX(), p.velocityY() - .49F, p.velocityZ(), p.solidVolumeSvq(), p.massUnits(), p.materialHandle(), p.solidCompositionHandle(), p.contactAge(), p.flags()));
			}
			collision.preview(stack, registry, .05F); var reaction = collision.commit(stack);
			require(reaction.y() < 0, "resting contact chain must carry an actual fixed support impulse");
		}
		require(stack.parcel(1).contactAge() == 3 && (stack.parcel(1).flags() & GranularParcelStore.FLAG_SUPPORTED) != 0
				&& stack.parcel(2).contactAge() == 3 && (stack.parcel(2).flags() & GranularParcelStore.FLAG_SUPPORTED) != 0,
				"actual active contact did not transmit the fixed bed support age");
	}

	private static void commonContactsMatchMassMetricProjection() {
		var floor = new GranularContactCompression.Row(0, -1, new double[]{0, 1, 0});
		var pair = new GranularContactCompression.Row(0, 1, new double[]{0, 1, 0});
		for (int exponent : new int[]{-30, -10, 0, 10, 25}) {
			double unit = Math.scalb(1.0, exponent); double[] mass = {2 * unit, 3 * unit};
			double[][] input = {{0, -.49, 0}, {0, -.49, 0}};
			var rows = new GranularContactCompression.Row[]{pair, floor};
			var result = runContactSolver(mass, input, rows);
			for (var value : result.values()) require(Math.abs(value[1]) < 1e-4,
					"A resting pair retained visible normal motion");
			require(Math.abs(result.impulses()[1] - 2.45 * unit) <= 5e-4 * unit,
					"Fixed support missed the incoming macroscopic momentum");
			checkContactMechanicalMoments(mass, input, result, new double[][]{{0, 0, 0}, {0, 1, 0}},
					new double[]{0, result.impulses()[1], 0});
		}
		double[] mass = {2, 7}, normal = {.6, .8, 0};
		double[][] input = {{1, 2, .25}, {-1, -.5, -.125}};
		var pairRows = new GranularContactCompression.Row[]{new GranularContactCompression.Row(0, 1, normal)};
		var result = runContactSolver(mass, input, pairRows);
		double closing = .6 * (input[1][0] - input[0][0]) + .8 * (input[1][1] - input[0][1]);
		double expectedImpulse = -closing / (1.0 / mass[0] + 1.0 / mass[1]);
		require(Math.abs(result.impulses()[0] - expectedImpulse) <= 1e-12,
				"Oblique unequal-mass contact disagrees with its analytic impulse");
		checkContactMechanicalMoments(mass, input, result, new double[][]{{0, 0, 0}, {1.2, 1.6, 0}});
		for (double restitution : new double[]{0, .1, .5, 1}) {
			var response = contactResponse(mass,input,pairRows,restitution);
			double afterGap = .6 * (response.values()[1][0] - response.values()[0][0])
					+ .8 * (response.values()[1][1] - response.values()[0][1]);
			require(Math.abs(afterGap + restitution * closing) <= 1e-12,
					"A single contact lost its material restitution");
			checkContactMechanicalMoments(mass, input, response, new double[][]{{0, 0, 0}, {1.2, 1.6, 0}});
		}
		var wedge = new GranularContactCompression.Row[]{
				new GranularContactCompression.Row(0, -1, new double[]{1, 0, 0}),
				new GranularContactCompression.Row(0, -1, new double[]{-.6, .8, 0})};
		double[][] incoming = {{-.4, -.8, 0}};
		for (double restitution : new double[]{0,.1,.5,1}) {
			var response = contactResponse(new double[]{1},incoming,wedge,restitution);
			double[] reaction = new double[3];
			for (int k = 0; k < wedge.length; k++) for (int a = 0; a < 3; a++)
				reaction[a] += response.impulses()[k] * wedge[k].normal()[a];
			checkContactMechanicalMoments(new double[]{1}, incoming, response, new double[][]{{0, 0, 0}}, reaction);
		}
		// Nine representatives and 23 rows exercise a coupled, redundant bed-sized contact network.
		var rows = new java.util.ArrayList<GranularContactCompression.Row>();
		double[][] centers = new double[9][3], velocities = new double[9][3]; double[] masses = new double[9];
		for (int i = 0; i < 9; i++) {
			centers[i] = new double[]{i % 3, i / 3, 0}; masses[i] = 5.078125;
			velocities[i] = new double[]{.03 * (4 - i), -.49, .01 * i};
			if (i < 3) rows.add(new GranularContactCompression.Row(i, -1, new double[]{0, 1, 0}));
			if (i % 3 < 2) rows.add(new GranularContactCompression.Row(i, i + 1, new double[]{1, 0, 0}));
			if (i < 6) rows.add(new GranularContactCompression.Row(i, i + 3, new double[]{0, 1, 0}));
			if (i < 6 && i % 3 < 2) rows.add(new GranularContactCompression.Row(i, i + 4, new double[]{Math.sqrt(.5), Math.sqrt(.5), 0}));
			if (i < 6 && i % 3 > 0) rows.add(new GranularContactCompression.Row(i, i + 2, new double[]{-Math.sqrt(.5), Math.sqrt(.5), 0}));
		}
		require(rows.size() == 23, "Bed contact fixture changed");
		var network = rows.toArray(GranularContactCompression.Row[]::new);
		int[] rowVisits = {0};
		result = GranularContactCompression.solve(masses, velocities, network, () -> rowVisits[0]++);
		checkContactCertificate(result, network);
		require(rowVisits[0] <= (2 * GranularContactCompression.SWEEPS + 1) * network.length,
				"Contact solve exceeded its fixed row-scan cost");
		double maximumClosing = 0;
		for (var row : network) {
			double gap = 0;
			for (int a = 0; a < 3; a++) gap += row.normal()[a] * (row.second() < 0
					? result.values()[row.first()][a] : result.values()[row.second()][a] - result.values()[row.first()][a]);
			maximumClosing = Math.max(maximumClosing, -gap);
		}
		System.out.printf("CONTACT representatives=9 contacts=23 rowVisits=%d maximumClosingMps=%g%n", rowVisits[0], maximumClosing);
		double[] reaction = new double[3];
		for (int k = 0; k < network.length; k++) if (network[k].second() < 0)
			for (int a = 0; a < 3; a++) reaction[a] += result.impulses()[k] * network[k].normal()[a];
		double[] torque = new double[3];
		for (int k = 0; k < network.length; k++) if (network[k].second() < 0) for (int a = 0; a < 3; a++) {
			int b = (a + 1) % 3, c = (a + 2) % 3; int i = network[k].first();
			torque[a] += result.impulses()[k] * (centers[i][b] * network[k].normal()[c] - centers[i][c] * network[k].normal()[b]);
		}
		checkContactMechanicalMoments(masses, velocities, result, centers, reaction, torque);
		componentResponseIsIndependent(masses, velocities, network);
	}

	private static void componentResponseIsIndependent(double[] mass, double[][] terminal, GranularContactCompression.Row[] rows) {
		var registry = BuiltinGranularDefinitions.createRegistry();
		var material = registry.material(registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND));
		var materials = new mmm.granular.ParticulateMaterial[mass.length]; java.util.Arrays.fill(materials, material);
		var processor = new GranularComponentResponse(.5, contactBudget());
		var first = processor.respond(mass, terminal, materials, rows);
		double[][] expected = java.util.Arrays.stream(first.values()).map(double[]::clone).toArray(double[][]::new);
		first.values()[0][0] = 123;
		var repeated = processor.respond(mass, terminal, materials, rows);
		for (int i = 0; i < mass.length; i++) for (int a = 0; a < 3; a++)
			require(Math.abs(repeated.values()[i][a] - expected[i][a]) <= repeated.arithmeticError()[i][a],
					"contact response retained mutable result state between calls");
	}
	private static GranularContactCompression.Result contactResponse(double[] mass,double[][] input,
			GranularContactCompression.Row[] rows,double restitution){
		var registry=BuiltinGranularDefinitions.createRegistry();var base=registry.material(registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND));
		var material=new mmm.granular.ParticulateMaterial(base.id(),base.referenceDensity(),base.primaryGrainDiameterMeters(),base.restPorosityQ(),base.reposeAngleRadians(),
				0,0,restitution,base.criticalShields(),base.erosionRateSvqPerSecond(),base.maximumActiveParcelSvq(),base.baseArgb(),base.displayTextureId(),base.reactive());
		var materials=new mmm.granular.ParticulateMaterial[mass.length];java.util.Arrays.fill(materials,material);
		return new GranularComponentResponse(.5,contactBudget()).respond(mass,input,materials,rows);
	}
	private static Runnable contactBudget() {
		int[] operations = {0};
		return () -> {
			if (++operations[0] > GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP)
				throw new AssertionError("small common contact component exhausted the production operation budget");
		};
	}

	private static GranularContactCompression.Result runContactSolver(double[] mass, double[][] input, GranularContactCompression.Row[] rows) {
		var result = GranularContactCompression.solve(mass, input, rows, contactBudget());
		checkContactCertificate(result, rows); return result;
	}

	private static void checkContactCertificate(GranularContactCompression.Result result, GranularContactCompression.Row[] rows) {
		for (int k = 0; k < rows.length; k++) {
			var row = rows[k]; double gap = 0;
			for (int a = 0; a < 3; a++) gap += row.normal()[a] * (row.second() < 0 ? result.values()[row.first()][a]
					: result.values()[row.second()][a] - result.values()[row.first()][a]);
			require(result.impulses()[k] >= 0 && gap >= -.001,
					"Bounded contact solve left visible closing motion: " + gap);
		}
	}

	private static void checkContactMechanicalMoments(double[] mass, double[][] input, GranularContactCompression.Result result, double[][] position) {
		checkContactMechanicalMoments(mass, input, result, position, new double[3]);
	}

	private static void checkContactMechanicalMoments(double[] mass, double[][] input, GranularContactCompression.Result result, double[][] position, double[] externalImpulse) {
		checkContactMechanicalMoments(mass, input, result, position, externalImpulse, new double[3]);
	}
	private static void checkContactMechanicalMoments(double[] mass, double[][] input, GranularContactCompression.Result result, double[][] position, double[] externalImpulse, double[] externalTorque) {
		double beforeEnergy = 0, afterEnergy = 0, energyBound = 0;
		for (int a = 0; a < 3; a++) {
			double before = 0, after = 0, bound = 0, oldAngular = 0, newAngular = 0, angularBound = 0;
			int b = (a + 1) % 3, c = (a + 2) % 3;
			for (int i = 0; i < mass.length; i++) {
				double[] v = result.values()[i], error = result.arithmeticError()[i];
				before += mass[i] * input[i][a]; after += mass[i] * v[a]; bound += mass[i] * error[a] + Math.ulp(before) + Math.ulp(after);
				oldAngular += mass[i] * (position[i][b] * input[i][c] - position[i][c] * input[i][b]);
				newAngular += mass[i] * (position[i][b] * v[c] - position[i][c] * v[b]);
				angularBound += mass[i] * (Math.abs(position[i][b]) * error[c] + Math.abs(position[i][c]) * error[b])
						+ Math.ulp(oldAngular) + Math.ulp(newAngular);
				beforeEnergy += .5 * mass[i] * input[i][a] * input[i][a]; afterEnergy += .5 * mass[i] * v[a] * v[a];
				energyBound += mass[i] * error[a] * (Math.abs(v[a]) + error[a]) + Math.ulp(beforeEnergy) + Math.ulp(afterEnergy);
			}
			require(Math.abs(after - before - externalImpulse[a]) <= bound + (externalImpulse[a] == 0 ? 0 : Math.ulp(externalImpulse[a]))
					&& Math.abs(newAngular - oldAngular - externalTorque[a]) <= angularBound + Math.ulp(externalTorque[a]),
					"common internal contact changed momentum or angular momentum beyond its reconstruction certificate");
		}
		require(afterEnergy <= beforeEnergy + energyBound, "common compression increased kinetic energy");
	}

	private static void capacityHoldRequiresActualSupport() {
		var registry = BuiltinGranularDefinitions.createRegistry();
		int sand = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long svq = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		double mass = 2_600.0 * svq / SolidVolume.SVQ_PER_CUBIC_METER;
		float radius = mmm.granular.GranularMechanics.representativeRadius(svq, .5F);
		for (boolean bedContact : new boolean[]{false, true}) {
			var parcels = new GranularParcelStore(registry, 1);
			var beds = new mmm.granular.GranularDepositStore(registry, 27, 27);
			// A full fixed condensed-phase cell supplies the same exact SVQ blocker as the old cloud.
			long[] fixed = new long[27]; fixed[bedContact ? 10 : 13] = 64 * svq;
			float px = bedContact ? .25F : .75F, py = 1.25F;
			if (bedContact) {
				require(beds.tryAdd(9, new mmm.granular.GranularDepositStore.SolidPortion(
						sand, 0, svq, mmm.units.Mass.fromKilograms(mass)), SolidVolume.SVQ_PER_CELL), "support bed must fit");
				double exactY = beds.cellBulkVolumeSvq(9) / (double) SolidVolume.SVQ_PER_CELL * .5 + radius;
				py = (float) exactY; if (py < exactY) py = Math.nextUp(py);
			}
			parcels.add(new GranularParcelStore.Parcel(px, py, .75F, 0, 0, 0, svq, mmm.units.Mass.fromKilograms(mass), sand, 0, 0, (byte) 0));
			var collision = new FlipGranularCollision(3, 3, 3, .5F, new FlipCutCellGeometry(3, 3, 3), beds, 1);
			var coupling = new mmm.granular.GranularCouplingFields(3, 3, 3, .5);
			coupling.setFixedCellSvq(fixed);
			if (bedContact) for (int i = 0; i < 3; i++) {
				collision.preview(parcels, registry, .05F); collision.commit(parcels);
			}
			for (int step = 0; step < 3; step++) {
				var source = parcels.parcel(0);
				parcels.overwrite(0, new GranularParcelStore.Parcel(px, py, .75F,
						bedContact ? .1F : 0, -.1F, 0, svq, mmm.units.Mass.fromKilograms(mass), sand, 0, source.contactAge(), source.flags()));
				coupling.rebuild(parcels, beds);
				require(fixed[bedContact ? 10 : 13] == SolidVolume.SVQ_PER_CELL
						&& coupling.totalSolidSvq() == (bedContact ? 66 : 65) * svq,
						"capacity fixture changed its fixed blocker or total solid inventory");
				// The fixture keeps its total displacement within .01 m.
				collision.preview(parcels, registry, .05F);
				require(collision.resolveCapacity(parcels, coupling, cell -> SolidVolume.SVQ_PER_CELL) == 1,
						"full neighboring kernel must reject exactly the moving parcel");
				collision.commit(parcels);
				var held = parcels.parcel(0);
				require(held.x() == px && held.y() == py && held.velocityX() == 0 && held.velocityY() == 0,
						"capacity hold must restore the source pose and rest velocity");
				require(held.contactAge() == (bedContact ? step + 4 : 0)
						&& ((held.flags() & GranularParcelStore.FLAG_SUPPORTED) != 0) == bedContact,
						"capacity hold must derive support from its original actual contact");
			}
			if (!bedContact) {
				var inventory = new mmm.granular.GranularInventory(parcels, beds);
				var dynamics = new mmm.granular.GranularDepositDynamics(registry, parcels, beds, inventory, 3, 3, 3, .5F);
				var stats = dynamics.step(new mmm.granular.GranularDepositDynamics.Environment() {
					public long geometricOpenSvq(int cell) { return SolidVolume.SVQ_PER_CELL; }
					public double gravity() { return 9.81; }
					public boolean horizontalPathOpen(int cell, int dx, int dz) { return true; }
					public double shields(int cell, mmm.granular.ParticulateMaterial material) { return 0; }
				}, .05F);
				require(stats.settledParcels() == 0 && parcels.count() == 1 && beds.groupCount() == 0,
						"an airborne held parcel must remain active rather than acquire deposit eligibility");
			}
		}
	}

	private static void granularRestoreValidatesPrivateCandidate() {
		var registry = BuiltinGranularDefinitions.createRegistry();
		int sand = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		var material = registry.material(sand);
		var config = new FluidSolverConfig(.5F, 1000F, 0F, .05F, 100, 1e-5F, .002F, 0F);
		long volume = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		double mass = volume / (double) SolidVolume.SVQ_PER_CUBIC_METER * material.referenceDensity();
		for (int scene = 0; scene < 7; scene++) {
			var domain = new FluidDomain(4, 4, 4, config, 128);
			int cell = domain.domainFlowFields.cellIndex(1, 1, 1);
			if (scene == 3) domain.geometry.cutCells.replaceMask(cell, 1);
			domain.setVolume(2, 2, 2, .125F);
			if (scene == 4) domain.setVolume(1, 1, 1, 1);
			var liquid = domain.exportLiquidCellState();
			var input = domain.exportMacMomentum();
			double[] px = input.copyX(), py = input.copyY(), pz = input.copyZ();
			px[domain.domainFlowFields.xFaceIndex(2, 2, 2)] = .25;
			py[domain.domainFlowFields.yFaceIndex(2, 2, 2)] = -.5;
			pz[domain.domainFlowFields.zFaceIndex(2, 2, 2)] = .125;
			domain.restoreMacMomentum(new MacMomentumState(4, 4, 4, px, py, pz));
			var momentum = domain.exportMacMomentum();
			long liquidRevision = domain.liquidCells.revision();
			long geometryRevision = domain.domainFlowFields.carrierMutationRevision();
			var kernel = new mmm.granular.GranularKernel(4, 4, 4, .5F);
			kernel.capture(scene == 0 ? 0 : .75F, scene == 2 ? .52F : .75F, .75F, volume);
			var parcel = new mmm.granular.GranularStateSnapshot.ActiveParcel(
					scene == 0 ? 0 : .75F, scene == 2 ? .52F : .75F, .75F,
					.1F, -.2F, .3F, volume, mmm.units.Mass.fromKilograms(mass), sand, 0, 0, (byte) 0,
					kernel.canonicalMask());
			var active = scene == 1 ? java.util.List.of(parcel, parcel) : java.util.List.of(parcel);
			var beds = scene >= 2 && scene != 4 ? java.util.List.of(new mmm.granular.GranularStateSnapshot.DepositGroup(
					scene == 6 ? domain.domainFlowFields.cellIndex(2, 1, 1) : cell,
					material.restPorosityQ() + (scene == 5 ? 1 : 0), sand, 0, volume, mmm.units.Mass.fromKilograms(mass))) :
					java.util.List.<mmm.granular.GranularStateSnapshot.DepositGroup>of();
			int carriers = active.size() + beds.size();
			var snapshot = new mmm.granular.GranularStateSnapshot(4, 4, 4, registry.fingerprint(),
					registry.materials().stream().map(mmm.granular.ParticulateMaterial::id).toList(),
					active, beds, carriers * volume, Math.multiplyExact(carriers, mmm.units.Mass.fromKilograms(mass)));
			boolean rejected = false;
			try {
				domain.restoreGranularState(snapshot, registry, new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE),
						BuiltinFluidMaterials.createRegistry());
			} catch (IllegalArgumentException | IllegalStateException expected) { rejected = true; }
			require(domain.exportLiquidCellState().stocks().equals(liquid.stocks())
					&& domain.liquidCells.revision() == liquidRevision
					&& java.util.Arrays.equals(momentum.copyX(),domain.exportMacMomentum().copyX())
					&& java.util.Arrays.equals(momentum.copyY(),domain.exportMacMomentum().copyY())
					&& java.util.Arrays.equals(momentum.copyZ(),domain.exportMacMomentum().copyZ()),
					"Granular restore changed liquid inventory or MAC momentum");
			if (scene < 6 && scene != 1) {
				require(rejected && domain.granularState == null && domain.materialRegistry == null
						&& domain.materialDefinitions == null && domain.granularCoupling == null
						&& domain.granularPhaseTransfer == null
						&& domain.domainFlowFields.carrierMutationRevision() == geometryRevision,
						"rejected granular restore published partial state, scene=" + scene);
			} else {
				require(!rejected, "valid granular restore was rejected");
				require(domain.gridDirty && domain.pressureGeometryDirty && domain.velocityGridDirty,
						"Granular restore must refresh joint occupancy and derived face inertia");
				var restored = domain.exportGranularState();
				require(restored.activeParcels().equals(active) && restored.depositGroups().equals(beds)
						&& restored.totalSolidVolumeSvq() == snapshot.totalSolidVolumeSvq()
						&& restored.totalMassUnits() == snapshot.totalMassUnits()
						&& restored.externalSource().equals(snapshot.externalSource())
						&& restored.escapedSink().equals(snapshot.escapedSink()),
						"valid granular restore changed physical columns or ledgers");
			}
		}
	}

	private static void mechanicalAuditIncludesGranularEnergy() {
		granularRestoreValidatesPrivateCandidate();
		var registry = BuiltinGranularDefinitions.createRegistry();
		int sand = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		var material = registry.material(sand);
		var config = new FluidSolverConfig(.5F, 1000F, 9.81F, .05F, 100, 1e-5F, .002F, 0F);
		long volume = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		double mass = volume / (double) SolidVolume.SVQ_PER_CUBIC_METER * material.referenceDensity();
		float radius = mmm.granular.GranularMechanics.representativeRadius(volume, .5F);
		for (int scene = 0; scene < 3; scene++) {
			float y = scene == 0 ? 1.25F : scene == 1 ? radius + .01F : radius;
			float vy = scene == 1 ? -.4F : 0;
			var kernel = new mmm.granular.GranularKernel(4, 4, 4, .5F);
			kernel.capture(.75F, y, .75F, volume);
			var parcel = new mmm.granular.GranularStateSnapshot.ActiveParcel(
					.75F, y, .75F, 0, vy, 0, volume, mmm.units.Mass.fromKilograms(mass), sand, 0,
					scene == 2 ? 3 : 0, scene == 2 ? GranularParcelStore.FLAG_SUPPORTED : (byte) 0,
					kernel.canonicalMask());
			var snapshot = new mmm.granular.GranularStateSnapshot(4, 4, 4, registry.fingerprint(),
					registry.materials().stream().map(mmm.granular.ParticulateMaterial::id).toList(),
					java.util.List.of(parcel), java.util.List.of(), volume, mmm.units.Mass.fromKilograms(mass));
			FluidDomain[] domains = {new FluidDomain(4, 4, 4, config, 128), new FluidDomain(4, 4, 4, config, 128)};
			for (var domain : domains) {
				domain.sealDomainBoundary();
				domain.restoreGranularState(snapshot, registry, new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE),
						BuiltinFluidMaterials.createRegistry());
			}
			var audit = domains[0].stepWithMechanicalAudit(false, 0).mechanicalAudit();
			domains[1].domainStep.step();
			var result = domains[0].exportGranularState();
			var control = domains[1].exportGranularState();
			require(result.activeParcels().equals(control.activeParcels()) && result.depositGroups().equals(control.depositGroups()),
					"mechanical audit changed the actual granular step");
			double finalK = 0, finalP = 0;
			for (var p : result.activeParcels()) {
				finalK += .5 * p.mass() * (p.velocityX() * (double) p.velocityX()
						+ p.velocityY() * (double) p.velocityY() + p.velocityZ() * (double) p.velocityZ());
				finalP += p.mass() * config.gravity() * p.y();
			}
			long denominator = GranularLimits.POROSITY_SCALE - material.restPorosityQ();
			for (var group : result.depositGroups()) {
				long bulk = (group.solidVolumeSvq() * GranularLimits.POROSITY_SCALE + denominator - 1) / denominator;
				double centre = .5 * (group.cell() / 4 % 4 + .5 * bulk / SolidVolume.SVQ_PER_CELL);
				finalP += group.mass() * config.gravity() * centre;
			}
			mechanicalNear(audit.initialParticleKineticEnergyJoules(), .5 * mass * vy * (double) vy, "initial granular K");
			mechanicalNear(audit.finalParticleKineticEnergyJoules(), finalK, "final granular K");
			mechanicalNear(audit.initialGravitationalPotentialEnergyJoules(), mass * config.gravity() * y, "initial granular potential");
			mechanicalNear(audit.finalGravitationalPotentialEnergyJoules(), finalP, "final granular/bed potential");
			require(audit.gravityWorkJoules() > 0 && audit.closureResidualJoules() == 0,
					"granular gravity was omitted from the stage kinetic-energy accounting");
			if (scene == 0) {
				mechanicalNear(audit.gravityWorkJoules(), finalK, "freefall gravity work");
				require(audit.finalParticleKineticEnergyJoules() + finalP
						< audit.initialGravitationalPotentialEnergyJoules(), "semi-implicit freefall error was concealed");
			} else require(finalK<audit.initialParticleKineticEnergyJoules()+audit.gravityWorkJoules(),
					"Granular contact did not dissipate kinetic energy");
			if (scene == 2) require(result.activeParcels().isEmpty() && result.depositGroups().size() == 1,
					"bed-potential control did not exercise an actual settlement");
		}
	}

	private static void mechanicalNear(double actual, double expected, String quantity) {
		require(Math.abs(actual - expected) <= 8 * Math.ulp(expected), quantity + ": " + actual + " vs " + expected);
	}

	private static void contactAgeSaturates() {
		var registry = BuiltinGranularDefinitions.createRegistry();
		int sand = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		var parcels = new GranularParcelStore(registry, 1);
		var deposits = new mmm.granular.GranularDepositStore(registry, 8, 8);
		long svq = 1_024;
		float radius = mmm.granular.GranularMechanics.representativeRadius(svq, .5F);
		parcels.add(new GranularParcelStore.Parcel(.5F, radius, .5F, 0, -.01F, 0,
				svq, mmm.units.Mass.fromKilograms(2_600 * svq / (double) SolidVolume.SVQ_PER_CUBIC_METER), sand, 0,
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
		int source = domain.domainFlowFields.cellIndex(1, 1, 1), target = domain.domainFlowFields.cellIndex(2, 1, 1);
		double expected = BuiltinFluidMaterials.createRegistry().dynamicViscosityAt("mmm:water", 293.15);
		require(domain.carrierViscosityPascalSeconds(source) == expected
				&& domain.carrierViscosityPascalSeconds(target) == null,
				"liquid properties must be available without any granular inventory");
		long revision = domain.carrierMutationRevision;
		var volume=domain.liquidCells.exactVolume();long mass=domain.liquidCells.massUnits();
		var sourceVolume=domain.liquidCells.exactCellVolume(source);
		java.util.Arrays.fill(domain.mac.velocityX, 1);
		java.util.Arrays.fill(domain.mac.velocityY, 0);
		java.util.Arrays.fill(domain.mac.velocityZ, 0);
		domain.cellMacStage.publishMomentum();
		domain.cellMacStage.transport(.5F);
		require(domain.carrierMutationRevision > revision, "Cell transport did not publish its inventory revision");
		require(domain.liquidCells.exactVolume().equals(volume)&&domain.liquidCells.massUnits()==mass
				&&domain.liquidCells.exactCellVolume(source).compareTo(sourceVolume)<0
				&&domain.liquidCells.exactCellVolume(target).signum()>0,
				"Cell transport must move the carrier while preserving liquid inventory");
		var restored=FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		for(int cell=0;cell<domain.cellCount;cell++){
			Double viscosity=domain.liquidCells.exactCellVolume(cell).signum()>0?expected:null;
			require(java.util.Objects.equals(domain.carrierViscosityPascalSeconds(cell),viscosity)
					&&java.util.Objects.equals(restored.carrierViscosityPascalSeconds(cell),viscosity),
					"Transport and recovery must derive carrier properties from the retained cell inventory");
		}
	}

	private static void thinBedRetainsFreeFlow() {
		var domain = domain();
		for (int z = 0; z < 6; z++) for (int y = 0; y < 3; y++) for (int x = 0; x < 6; x++) domain.setVolume(x, y, z, .5F);
		domain.domainFlowFields.ensureGrid();
		int sand = domain.granularState.registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long volume = SolidVolume.SVQ_PER_CELL / 4;
		for (int x = 2; x <= 3; x++) require(domain.granularState.deposits.tryAdd(domain.domainFlowFields.cellIndex(x, 1, 2),
				new mmm.granular.GranularDepositStore.SolidPortion(sand, 0, volume,
						mmm.units.Mass.fromKilograms(2600 * volume / (double) SolidVolume.SVQ_PER_CUBIC_METER)), SolidVolume.SVQ_PER_CELL), "Thin bed rejected");
		int face = domain.domainFlowFields.xFaceIndex(3, 1, 2), vertical = domain.domainFlowFields.yFaceIndex(2, 1, 2);
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

	private static void uniformCavityPressureDoesNotAccelerateMatter() {
		for (double pressureRatio : new double[]{.1, 1, 10}) {
			var domain = headspacePressureDomain();
			domain.setGasNodeStandardVolume(1, domain.gasState.network.availableVolume(1) * pressureRatio);
			var result = domain.pressureSystem.solveProjection(.05F);
			require(result.converged(), "Uniform physical cavity pressure did not converge");
			domain.pressureSystem.projectVelocities(.05F);
			require(pressureEnergy(domain) + solidEnergy(domain) < 1.0e-12,
					"Uniform cavity pressure created liquid or granular kinetic energy: ratio=" + pressureRatio);
		}
	}

	private static void implicitCarrierValidity() {
		var domain = domain();
		addParcel(domain, 1.26F, 1.28F, 1.29F, 250, 0, 0);
		fillPoreWater(domain, domain.sizeY);
		domain.domainFlowFields.ensureGrid();
		domain.granularMechanicalStage.validateVelocities();
		double before = pressureEnergy(domain) + solidEnergy(domain);
		float dt = .4F * domain.config.cellSize() / 250;
		domain.granularMechanicalStage.predictVelocities(dt);
		domain.pressureSystem.solve(dt);
		domain.pressureSystem.projectVelocities(dt);
		domain.granularMechanicalStage.validateVelocities();
		require(pressureEnergy(domain) + solidEnergy(domain) < before,
				"implicit drag must dissipate an initially unresolved relative velocity into a valid solution");
	}

	private static FluidDomain domain() { return domain(0); }
	private static FluidDomain domain(float gravity) {
		var domain = new FluidDomain(6, 6, 6,
				new FluidSolverConfig(0.5F, 1_000F, gravity, 0.05F, 400, 1.0e-5F, 0.002F, 0.8F), 4096);
		domain.bindGranular(BuiltinGranularDefinitions.createRegistry(),
				new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE),
				BuiltinFluidMaterials.createRegistry());
		return domain;
	}

	/** Saturated algebra inputs retain each original solid source and fill its actual pore capacity. */
	private static void fillPoreWater(FluidDomain domain, int height) {
		for (int z = 0; z < domain.sizeZ; z++) for (int y = 0; y < height; y++)
			for (int x = 0; x < domain.sizeX; x++) domain.setVolume(x, y, z, 1);
		domain.domainFlowFields.ensureInventoryGrid();
		for (int cell = 0; cell < domain.cellCount; cell++) {
			var occupied = domain.liquidGrid.exactCellVolumeInventory[cell]
					.add(mmm.units.SignedDyadic.of(domain.granularCoupling.solidSvq(cell), -24));
			var open = mmm.units.SignedDyadic.of(domain.geometry.cutCells.openCellVolume(cell));
			require(occupied.compareTo(open) <= 0
					&& (cell / domain.sizeX % domain.sizeY >= height || occupied.equals(open)),
					"pressure fixture exceeds joint liquid/solid capacity at cell " + cell);
		}
	}

	private static FluidDomain headspacePressureDomain() {
		var domain = domain();
		domain.sealDomainBoundary();
		addParcel(domain, 1.43F, 1.5F, 1.39F, 0, 0, 0);
		fillPoreWater(domain, 3);
		var displaced = mmm.units.SignedDyadic.of(108)
				.subtract(mmm.units.SignedDyadic.sum(domain.liquidGrid.exactCellVolumeInventory));
		float moved = (float) displaced.toDouble();
		// Preserve the original water in headspace outside the parcel kernel.
		require(displaced.signum() > 0 && mmm.units.SignedDyadic.of(moved).equals(displaced)
				&& domain.addVolumeNear(1, 4, 1, moved, 0) == moved,
				"mixed gas-pressure fixture could not retain its exact displaced water");
		require(domain.totalVolumeExact() == 108 && domain.unitScale.kilograms(domain.totalMass()) == 13_500,
				"mixed gas-pressure fixture must preserve the original 13.5 m³ and 13500 kg of water");
		domain.bootstrapGasState(null); domain.domainFlowFields.ensureGrid();
		return domain;
	}

	private static void addParcel(FluidDomain domain, float x, float y, float z, float vx, float vy, float vz) {
		int sand = domain.granularState.registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long volume = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		double mass = volume / (double) SolidVolume.SVQ_PER_CUBIC_METER
				* domain.granularState.registry.material(sand).referenceDensity();
		domain.granularState.parcels.add(new GranularParcelStore.Parcel(x, y, z, vx, vy, vz,
				volume, mmm.units.Mass.fromKilograms(mass), sand, 0, 0, (byte) 0));
	}

	private static void sharedCarrierExchange(boolean massWeighted) {
		var domain = domain();
		domain.setVolume(2, 2, 2, 0.001F);
		if (massWeighted) {
			var changes = new java.util.ArrayList<LiquidCellInventory.Edit>();
			for(var stock:domain.liquidCells.stocks())changes.add(new LiquidCellInventory.Edit(stock.key(),
					new LiquidCellInventory.Stock(stock.key(),stock.volume(),
							mmm.units.Mass.fromKilograms(stock.massKilograms()*13.6),stock.mixture(),stock.internalEnergy(),stock.indicator())));
			domain.liquidCells.planEdits(changes).commit();domain.markCellInventoryChanged();
		}
		for (int p = 0; p < 32; p++) addParcel(domain,
				1.26F + 0.005F * (p % 4), 1.28F + 0.005F * (p % 3), 1.29F,
				1, -0.5F, 0.25F);
		domain.domainFlowFields.ensureGrid();
		double beforeEnergy = (pressureEnergy(domain) + solidEnergy(domain));
		double[] beforeMomentum = pressureMomentum(domain);
		relaxDrag(domain, 0.05F);
		double afterEnergy = (pressureEnergy(domain) + solidEnergy(domain));
		double[] afterMomentum = pressureMomentum(domain);
		for (int axis = 0; axis < 3; axis++) require(Math.abs(afterMomentum[axis] - beforeMomentum[axis]) < 2.0e-5,
				"shared carrier drag lost momentum on axis " + axis + ": " + (afterMomentum[axis] - beforeMomentum[axis]));
		require(afterEnergy < beforeEnergy - 1.0e-5, "shared carrier drag must dissipate relative kinetic energy");
		if(!massWeighted) {
			long[] mass=new long[domain.cellCount];double[] volume=new double[domain.cellCount];
			for(var stock:domain.liquidCells.physicalStocks()) {
				mass[stock.key().cell()]+=stock.massUnits();volume[stock.key().cell()]+=stock.volumeFraction();
			}
			var quantities=new LiquidCellInventory.TransportQuantities(java.util.List.of(),mass,mass,volume);
			double heat=domain.cellMacStage.finishDissipation(quantities,java.util.Map.of()).values().stream()
					.mapToDouble(mmm.units.SignedDyadic::toDouble).sum();
			require(Math.abs(heat-(beforeEnergy-afterEnergy))<=1e-5*beforeEnergy+1e-6,
					"implicit drag heat did not close the finite-mass liquid and parcel kinetic-energy loss");
		}

	}

	private static void relaxDrag(FluidDomain domain, float dt) {
		// Assemble the common face partition, then isolate the finite-mass drag response at zero pressure.
		domain.domainFlowFields.ensureGrid(); domain.pressureSystem.faces.rebuild(dt);
		var pressure = new GranularPressureCoupling(domain); pressure.prepare(dt,0);
		var drag = new GranularImplicitDrag(domain); drag.prepare(dt); drag.project();
	}

	private static void implicitSharedCarrier() {
		double[] finalEnergy = new double[2];
		for (int order = 0; order < 2; order++) {
			var domain = domain();
			for (int p = 0; p < 16; p++) {
				int ordinal = order == 0 ? p : 15 - p;
				addParcel(domain, 1.26F, 1.28F, 1.29F, (ordinal - 8) * 0.1F, -0.5F, 0.25F);
			}
			fillPoreWater(domain, domain.sizeY);
			domain.domainFlowFields.ensureGrid();
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
			int first = domain.domainFlowFields.cellIndex(x, y, z), second = domain.domainFlowFields.cellIndex(nx, ny, nz);
			float open = domain.domainFlowFields.pressureOpenFaceFromCell(first, second, nx - x, ny - y, nz - z);
			double velocity = axis == 0 ? domain.mac.velocityX[domain.domainFlowFields.xFaceIndex(nx, y, z)] : axis == 1
					? domain.mac.velocityY[domain.domainFlowFields.yFaceIndex(x, ny, z)] : domain.mac.velocityZ[domain.domainFlowFields.zFaceIndex(x, y, nz)];
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
			domain.domainFlowFields.ensureGrid();
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
		var domain=domain();for(int p=0;p<60;p++)addParcel(domain,1.75F,1.75F,1.75F,.25F,-1,.5F);
		fillPoreWater(domain,domain.sizeY);domain.domainFlowFields.ensureGrid();
		domain.pressureSystem.solve(.05F);domain.pressureSystem.projectVelocities(.05F);
		domain.granularMechanicalStage.previewCollision(.05F);domain.granularMechanicalStage.publishCollisionVelocities();
		require(Double.isFinite(pressureEnergy(domain)+solidEnergy(domain)),"Joint pressure response produced invalid kinetic energy");
	}

	private static void cellOccupancyVolumeRate() {
		var domain=domain();addParcel(domain,1.749F,1.751F,1.749F,.5F,-.5F,.5F);domain.domainFlowFields.ensureGrid();
		long before=0;for(int c=0;c<domain.cellCount;c++)before+=domain.granularCoupling.solidSvq(c);
		domain.granularMechanicalStage.previewCollision(.01F);long after=0;boolean moved=false;
		for(int c=0;c<domain.cellCount;c++){after+=domain.granularNextCoupling.solidSvq(c);moved|=domain.granularNextCoupling.solidSvq(c)!=domain.granularCoupling.solidSvq(c);}
		require(before==after&&moved,"Actual prepared solid endpoint must move the conserved integer volume");
	}

	private static void internalPressureImpulseCloses() {
		var domain = domain(); addParcel(domain, 1.43F, 1.62F, 1.39F, 0, 0, 0);
		fillPoreWater(domain, domain.sizeY); domain.domainFlowFields.ensureGrid();
		domain.pressureSystem.solve(.05F);
		java.util.Arrays.fill(domain.projection.pressure, 0);
		domain.projection.pressure[domain.domainFlowFields.cellIndex(2,2,2)] = 300;
		domain.projection.pressure[domain.domainFlowFields.cellIndex(3,2,2)] = -100;
		double[] impulse=new double[3];double dt=.05;
		var graph=domain.pressureSystem.faces;
		double[] p=new double[domain.cellCount+domain.gasState.network.nodeCount()+1],a=new double[graph.faceCount];
		for(int cell=0;cell<domain.cellCount;cell++)p[cell]=domain.projection.pressure[cell];
		graph.pressureVelocity(p,a);
		for(int face=0;face<graph.faceCount;face++)impulse[graph.axis[face]]+=dt*graph.faceMass(face)*a[face];
		var coupling = new GranularPressureCoupling(domain); coupling.prepare((float)dt,0); coupling.project((float)dt);
		var parcel = domain.granularState.parcels.parcel(0);
		impulse[0] += parcel.mass() * parcel.velocityX(); impulse[1] += parcel.mass() * parcel.velocityY();
		impulse[2] += parcel.mass() * parcel.velocityZ();
		for (double value : impulse) require(Math.abs(value) < 1e-8,
				"Internal pressure changed combined liquid/solid momentum: " + value);
	}

	private static void hydrostaticParcelForce() {
		for (float py : new float[]{.1F, .24F, 1.62F, 2.9F}) {
		var domain = domain(9.81F);
		addParcel(domain, 1.43F, py, 1.39F, 0, 0, 0);
		fillPoreWater(domain, domain.sizeY);
		domain.domainFlowFields.ensureGrid();
		domain.pressureSystem.faces.rebuild(0.05F);
		for (int cell = 0; cell < domain.cellCount; cell++) {
			int y = cell / 6 % 6;
			domain.projection.pressure[cell] = (float)(1_000F * 9.81F * (3 - (y + 0.5F) * 0.5F)
					-domain.pressureSystem.faces.referencePressure(cell,255));
		}
		var coupling = new GranularPressureCoupling(domain);
		coupling.prepare(0.05F,0);
		coupling.project(0.05F);
		var p = domain.granularState.parcels.parcel(0);
		double expected = 0.05 * 9.81 * 1_000 * p.solidVolumeSvq() / SolidVolume.SVQ_PER_CUBIC_METER / p.mass();
		// CIC derivatives and the clipped-wall hydrostatic term share the represented solid inventory.
		double tolerance = .01 * expected + 1e-7;
		require(Math.abs(p.velocityY() - expected) < tolerance && Math.abs(p.velocityX()) < 1.0e-7
				&& Math.abs(p.velocityZ()) < 1.0e-7, "Archimedes buoyancy at y=" + py + ": actual="
				+ p.velocityY() + ", expected=" + expected + ", vx=" + p.velocityX() + ", vz=" + p.velocityZ());

		}
	}

	private static void sharedGasPressureAlgebra() {
		var domain=headspacePressureDomain();domain.pressureSystem.solve(.05F);var graph=domain.pressureSystem.faces;
		int size=domain.cellCount+domain.gasState.network.nodeCount()+1;
		double[] a=new double[size],b=new double[size],aa=new double[size],ab=new double[size],velocity=new double[graph.faceCount];
		for(int i=0;i<size;i++){a[i]=i%7-3;b[i]=i%11-5;}
		graph.pressureVelocity(a,velocity);graph.addDivergence(velocity,aa,domain.config.density());
		graph.pressureVelocity(b,velocity);graph.addDivergence(velocity,ab,domain.config.density());
		double x=0,y=0,q=0;for(int i=0;i<size;i++){x+=a[i]*ab[i];y+=b[i]*aa[i];q+=a[i]*aa[i];}
		require(Math.abs(x-y)<1e-9*Math.max(1,Math.abs(x))&&q>=0,"Shared fixed fluid aperture pressure operator must remain symmetric positive");
	}

	private static double pressureEnergy(FluidDomain domain) {
		double energy = 0, h = domain.config.cellSize();
		for (int axis = 0; axis < 3; axis++) for (int z = 0; z < 6; z++) for (int y = 0; y < 6; y++) for (int x = 0; x < 6; x++) {
			int nx = x + (axis == 0 ? 1 : 0), ny = y + (axis == 1 ? 1 : 0), nz = z + (axis == 2 ? 1 : 0);
			if (nx >= 6 || ny >= 6 || nz >= 6) continue;
			int first = domain.domainFlowFields.cellIndex(x, y, z), second = domain.domainFlowFields.cellIndex(nx, ny, nz);
			float open = axis == 0 ? domain.domainFlowFields.pressureXFaceOpen(nx, y, z) : axis == 1
					? domain.domainFlowFields.pressureYFaceOpen(x, ny, z) : domain.domainFlowFields.pressureZFaceOpen(x, y, nz);
			double velocity = axis == 0 ? domain.mac.velocityX[domain.domainFlowFields.xFaceIndex(nx, y, z)] : axis == 1
					? domain.mac.velocityY[domain.domainFlowFields.yFaceIndex(x, ny, z)] : domain.mac.velocityZ[domain.domainFlowFields.zFaceIndex(x, y, nz)];
			energy += 0.5 * faceMass(domain, axis, x, y, z, nx, ny, nz, first, second, open) * velocity * velocity;
		}
		return energy;
	}

	private static double faceMass(FluidDomain domain, int axis, int x, int y, int z,
	                               int nx, int ny, int nz, int first, int second, float open) {
		if (!domain.domainFlowFields.carrierFaceActive(first, second, open)) return 0;
		double p2g = axis == 0 ? domain.mac.faceMassX[domain.domainFlowFields.xFaceIndex(nx, y, z)] : axis == 1
				? domain.mac.faceMassY[domain.domainFlowFields.yFaceIndex(x, ny, z)] : domain.mac.faceMassZ[domain.domainFlowFields.zFaceIndex(x, y, nz)];
		return domain.unitScale.kilograms(p2g);
	}

	private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
