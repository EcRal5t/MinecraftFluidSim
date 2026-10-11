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
		contactAgeSaturates();
		carrierCacheFollowsAdvection();
		for (boolean massWeighted : new boolean[]{false, true}) sharedCarrierExchange(massWeighted);
		finitePressureResponse();
		sweptOccupancyConservation();
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
		require(parcels.parcel(0).x() == .42F,
				"granular motion entered an occupied micro-box through its corner");
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
