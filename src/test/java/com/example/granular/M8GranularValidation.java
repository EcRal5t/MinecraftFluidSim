package com.example.granular;

import com.example.fluid.BuiltinFluidMaterials;
import com.example.fluid.FlipFluidDomain;
import com.example.fluid.FluidMaterialRegistry;
import com.example.fluid.FluidSolverConfig;

import java.util.Arrays;
import java.util.List;

/** M8 granular definition and exact inventory gate. Mechanics are added to this selector incrementally. */
public final class M8GranularValidation {
	private M8GranularValidation() { }

	public static void main(String[] args) {
		boolean fast = Arrays.asList(args).contains("--fast");
		registryIsCanonicalAndBounded();
		solidVolumeConversionsAreConservative();
		parcelSplitMergePreservesInventory();
		depositCapacityAndGroupBudgetAreAtomic();
		parcelDepositCyclesDoNotDrift(fast ? 1_000 : 10_000);
		mechanicsMatchesTerminalVelocity();
		twoWayDragPreservesMomentum();
		couplingOccupancyResumsExactly();
		solverCouplingPreservesInventories(fast ? 8 : 80);
		System.out.println("M8 granular core gate passed: canonical registry, exact inventory, drag and coupling occupancy");
	}

	private static void registryIsCanonicalAndBounded() {
		GranularRegistry builtins = BuiltinGranularDefinitions.createRegistry();
		GranularRegistry reordered = new GranularRegistry(
				List.of(builtins.materials().get(2), builtins.materials().get(0), builtins.materials().get(1)),
				List.of(builtins.carriers().get(1), builtins.carriers().get(2), builtins.carriers().get(0)));
		require(builtins.fingerprint().equals(reordered.fingerprint()), "registry fingerprint depends on input order");
		expectFailure(() -> new GranularRegistry(List.of(builtins.materials().get(0), builtins.materials().get(0)),
				builtins.carriers()), "duplicate material accepted");
		expectFailure(() -> new ParticulateMaterial("BAD", 1.0, 0.001, 400_000, 0.5,
				0.5, 0.4, 0.1, 0.05, 1L, 1L, 0, "realwater:bad", false), "bad id accepted");
	}

	private static void solidVolumeConversionsAreConservative() {
		for (long svq : new long[] {0L, 1L, SolidVolume.SVQ_PER_CELL / 8L, SolidVolume.SVQ_PER_CELL,
				SolidVolume.SVQ_PER_BV}) {
			require(SolidVolume.fromCellVolumeExact(SolidVolume.toCellVolume(svq)) == svq,
					"SVQ/cell-volume conversion drifted");
		}
		for (long nanos : new long[] {0L, 1L, 100_000L, 123_456_789L, SolidVolume.NANOS_PER_BV}) {
			long svq = SolidVolume.nanosToSvqAtMost(nanos);
			long settled = SolidVolume.svqToNanosAtMost(svq);
			require(settled <= nanos && nanos - settled <= 8L, "SVQ/nanovolume conversion exceeded bound");
		}
	}

	private static void parcelSplitMergePreservesInventory() {
		GranularRegistry registry = BuiltinGranularDefinitions.createRegistry();
		int material = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		GranularParcelStore store = new GranularParcelStore(registry, 4);
		long volume = registry.material(material).maximumActiveParcelSvq();
		double mass = 7.25;
		store.add(parcel(material, volume, mass));
		int split = store.split(0, volume / 3L);
		require(store.totalSolidVolumeSvq() == volume, "parcel split changed SVQ");
		require(rawClose(store.totalMass(), mass), "parcel split changed mass");
		store.merge(0, split);
		require(store.count() == 1 && store.totalSolidVolumeSvq() == volume, "parcel merge changed SVQ");
		require(rawClose(store.totalMass(), mass), "parcel merge changed mass");
	}

	private static void depositCapacityAndGroupBudgetAreAtomic() {
		GranularRegistry registry = BuiltinGranularDefinitions.createRegistry();
		int reactive = registry.materialHandle(BuiltinGranularDefinitions.PRECIPITATE);
		GranularDepositStore deposits = new GranularDepositStore(registry, 2, 8);
		long portion = SolidVolume.SVQ_PER_CELL / 32L;
		for (int composition = 1; composition <= GranularLimits.MAX_DEPOSIT_GROUPS_PER_CELL; composition++) {
			require(deposits.tryAdd(0, new GranularDepositStore.SolidPortion(reactive, composition, portion, 1.0),
					SolidVolume.SVQ_PER_CELL), "valid deposit group rejected");
		}
		long beforeVolume = deposits.totalSolidVolumeSvq();
		double beforeMass = deposits.totalMass();
		require(!deposits.tryAdd(0, new GranularDepositStore.SolidPortion(reactive, 9, portion, 1.0),
				SolidVolume.SVQ_PER_CELL), "ninth deposit group accepted");
		require(deposits.totalSolidVolumeSvq() == beforeVolume
				&& Double.doubleToRawLongBits(deposits.totalMass()) == Double.doubleToRawLongBits(beforeMass),
				"deposit group rejection left residue");

		int sand = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		GranularDepositStore full = new GranularDepositStore(registry, 1, 8);
		long tooMuchSkeleton = SolidVolume.SVQ_PER_CELL * 3L / 4L;
		require(!full.tryAdd(0, new GranularDepositStore.SolidPortion(sand, 0, tooMuchSkeleton, 5.0),
				SolidVolume.SVQ_PER_CELL), "bulk-capacity overflow accepted");
		require(full.groupCount() == 0 && full.totalSolidVolumeSvq() == 0L, "capacity rejection left residue");
	}

	private static void parcelDepositCyclesDoNotDrift(int iterations) {
		GranularRegistry registry = BuiltinGranularDefinitions.createRegistry();
		int material = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		GranularParcelStore parcels = new GranularParcelStore(registry, 2);
		GranularDepositStore deposits = new GranularDepositStore(registry, 1, 2);
		GranularInventory inventory = new GranularInventory(parcels, deposits);
		long volume = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		double mass = 2.75;
		parcels.add(parcel(material, volume, mass));
		for (int iteration = 0; iteration < iterations; iteration++) {
			require(inventory.depositParcel(0, 0, SolidVolume.SVQ_PER_CELL), "parcel deposit failed");
			require(inventory.wake(0, material, 0, volume, 0.5F, 0.5F, 0.5F, 0.0F, 0.0F, 0.0F),
					"deposit wake failed");
		}
		require(inventory.totalSolidVolumeSvq() == volume, "parcel/deposit cycles changed SVQ");
		require(rawClose(inventory.totalMass(), mass), "parcel/deposit cycles changed mass");
	}

	private static GranularParcelStore.Parcel parcel(int material, long volume, double mass) {
		return new GranularParcelStore.Parcel(0.5F, 0.5F, 0.5F, 0.25F, -0.5F, 0.125F,
				volume, mass, material, 0, 0, (byte) 0);
	}

	private static void mechanicsMatchesTerminalVelocity() {
		GranularRegistry registry = BuiltinGranularDefinitions.createRegistry();
		for (String id : List.of(BuiltinGranularDefinitions.LIGHT_BEADS, BuiltinGranularDefinitions.FINE_SAND)) {
			int handle = registry.materialHandle(id);
			ParticulateMaterial material = registry.material(handle);
			GranularParcelStore store = new GranularParcelStore(registry, 1);
			long svq = material.maximumActiveParcelSvq();
			double mass = SolidVolume.toCellVolume(svq) * material.referenceDensity();
			store.add(new GranularParcelStore.Parcel(0.5F, 0.5F, 0.5F, 0.0F, 0.0F, 0.0F,
					svq, mass, handle, material.reactive() ? 1 : 0, 0, (byte) 0));
			GranularMechanics.Environment water = constantWater(0.0, 0.0, 0.0, null);
			for (int step = 0; step < 10_000; step++) {
				GranularMechanics.stepVelocities(store, registry, water, 0.5, 1.0 / 120.0);
			}
			double expected = terminalVelocity(material.referenceDensity(), 1_000.0,
					1.002e-3, material.primaryGrainDiameterMeters());
			double actual = store.parcel(0).velocityY();
			double tolerance = Math.max(2.0e-3, Math.abs(expected) * 0.05);
			require(Math.abs(actual - expected) <= tolerance, "granular terminal velocity mismatch");
		}
	}

	private static void twoWayDragPreservesMomentum() {
		GranularRegistry registry = neutralRegistry();
		int handle = registry.materialHandle("realwater:neutral_probe");
		GranularParcelStore store = new GranularParcelStore(registry, 1);
		long svq = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		double mass = SolidVolume.toCellVolume(svq) * 1_000.0;
		store.add(new GranularParcelStore.Parcel(0.5F, 0.5F, 0.5F, 1.0F, 0.0F, 0.0F,
				svq, mass, handle, 0, 0, (byte) 0));
		double[] reaction = new double[3];
		GranularMechanics.Environment environment = constantWater(-0.25, 0.0, 0.0, reaction);
		double before = mass * store.parcel(0).velocityX();
		GranularMechanics.stepVelocities(store, registry, environment, 0.5, 1.0 / 60.0);
		double after = mass * store.parcel(0).velocityX() + reaction[0];
		require(Math.abs(after - before) <= Math.max(1.0e-12, 8.0 * Math.ulp(before)),
				"two-way granular drag changed total momentum");
	}

	private static void couplingOccupancyResumsExactly() {
		GranularRegistry registry = BuiltinGranularDefinitions.createRegistry();
		int sand = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		GranularParcelStore parcels = new GranularParcelStore(registry, 2);
		GranularDepositStore deposits = new GranularDepositStore(registry, 4 * 4 * 4, 4);
		long parcelVolume = GranularLimits.MAX_PARCEL_VOLUME_SVQ - 17L;
		parcels.add(new GranularParcelStore.Parcel(0.73F, 0.91F, 1.12F, 0.0F, 0.0F, 0.0F,
				parcelVolume, 1.0, sand, 0, 0, (byte) 0));
		require(deposits.tryAdd(0, new GranularDepositStore.SolidPortion(sand, 0, 123_457L, 0.5),
				SolidVolume.SVQ_PER_CELL), "fixture deposit rejected");
		GranularCouplingFields fields = new GranularCouplingFields(4, 4, 4, 0.5);
		fields.rebuild(parcels, deposits);
		long[] before = fields.copySolidSvq();
		require(fields.totalSolidSvq() == parcelVolume + 123_457L, "coupling field lost SVQ");
		GranularParcelStore.Parcel old = parcels.parcel(0);
		parcels.overwrite(0, new GranularParcelStore.Parcel(1.23F, 0.61F, 0.72F,
				old.velocityX(), old.velocityY(), old.velocityZ(), old.solidVolumeSvq(), old.mass(),
				old.materialHandle(), old.solidCompositionHandle(), old.contactAge(), old.flags()));
		fields.rebuild(parcels, deposits);
		long[] after = fields.copySolidSvq();
		long delta = 0L;
		for (int cell = 0; cell < before.length; cell++) delta = Math.addExact(delta, after[cell] - before[cell]);
		require(delta == 0L, "moving granular occupancy does not exact re-sum");
		require(fields.faceVoidScale(0, 1.0F, 1, 1.0F) == fields.faceVoidScale(1, 1.0F, 0, 1.0F),
				"granular face void scale is asymmetric");
	}

	private static GranularRegistry neutralRegistry() {
		return new GranularRegistry(List.of(new ParticulateMaterial("realwater:neutral_probe", 1_000.0, 0.0005,
				400_000, Math.toRadians(30.0), 0.6, 0.4, 0.1, 0.05, 1L,
				GranularLimits.MAX_PARCEL_VOLUME_SVQ, 0xffffffff, "realwater:neutral_probe", false)),
				List.of(new GranularCarrier("realwater:water", 1.002e-3, 100_000.0)));
	}

	private static void solverCouplingPreservesInventories(int steps) {
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F,
				200, 1.0e-5F, 0.45F, 2.0e-3F, 0.8F);
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(BuiltinFluidMaterials.M3_PALETTE);
		GranularRegistry granular = BuiltinGranularDefinitions.createRegistry();
		FlipFluidDomain domain = new FlipFluidDomain(4, 8, 4, config, 4 * 8 * 4 * 8);
		for (int z = 0; z < 4; z++) for (int y = 0; y < 3; y++) for (int x = 0; x < 4; x++) {
			domain.setVolume(x, y, z, 1.0F);
		}
		domain.bindGranular(granular, liquids);
		int sand = granular.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long svq = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		double mass = SolidVolume.toCellVolume(svq) * granular.material(sand).referenceDensity();
		FlipFluidDomain.GranularInsertion inserted = domain.tryInsertExactGranular(2, 5, 2, sand, 0, svq, mass);
		require(inserted.inserted(), "solver granular insertion was rejected: " + inserted.failure());
		double waterBefore = domain.totalVolumeExact();
		for (int step = 0; step < steps; step++) {
			FlipFluidDomain.StepStats stats = domain.step();
			require(Float.isFinite(stats.pressureResidual()), "granular pressure coupling produced non-finite residual");
		}
		require(Double.doubleToRawLongBits(domain.totalVolumeExact()) == Double.doubleToRawLongBits(waterBefore),
				"granular coupling changed exact liquid volume");
		require(domain.totalGranularSolidVolumeSvq() == svq, "solver granular coupling changed solid SVQ");
		require(rawClose(domain.totalGranularMass(), mass), "solver granular coupling changed solid mass");

		FlipFluidDomain sealed = new FlipFluidDomain(3, 3, 3, config, 3 * 3 * 3 * 8);
		for (int z = 0; z < 3; z++) for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) {
			sealed.setVolume(x, y, z, 1.0F);
		}
		sealed.bindGranular(granular, liquids);
		FlipFluidDomain.GranularInsertion refused = sealed.tryInsertExactGranular(1, 1, 1, sand, 0, svq, mass);
		require(!refused.inserted() && sealed.totalGranularSolidVolumeSvq() == 0L,
				"sealed granular insertion did not refuse atomically");
	}

	private static GranularMechanics.Environment constantWater(
			double velocityX, double velocityY, double velocityZ, double[] reaction
	) {
		return new GranularMechanics.Environment() {
			@Override public GranularMechanics.FluidSample sample(float x, float y, float z) {
				return new GranularMechanics.FluidSample(true, velocityX, velocityY, velocityZ,
						1_000.0, 1.002e-3, 100_000.0, 1.0);
			}
			@Override public void addFluidImpulse(float x, float y, float z, double ix, double iy, double iz) {
				if (reaction != null) { reaction[0] += ix; reaction[1] += iy; reaction[2] += iz; }
			}
		};
	}

	private static double terminalVelocity(double particleDensity, double fluidDensity, double viscosity, double diameter) {
		if (particleDensity == fluidDensity) return 0.0;
		double direction = particleDensity > fluidDensity ? -1.0 : 1.0;
		double volume = Math.PI * diameter * diameter * diameter / 6.0;
		double area = Math.PI * diameter * diameter / 4.0;
		double force = Math.abs(particleDensity - fluidDensity) * volume * 9.81;
		double lower = 0.0, upper = 20.0;
		for (int iteration = 0; iteration < 100; iteration++) {
			double middle = 0.5 * (lower + upper);
			double reynolds = fluidDensity * diameter * middle / viscosity;
			double drag = 0.5 * GranularMechanics.dragCoefficient(reynolds)
					* fluidDensity * area * middle * middle;
			if (drag < force) lower = middle; else upper = middle;
		}
		return direction * 0.5 * (lower + upper);
	}

	private static boolean rawClose(double actual, double expected) {
		return Math.abs(actual - expected) <= Math.max(1.0e-12, 8.0 * Math.ulp(expected));
	}

	private static void expectFailure(Runnable action, String message) {
		try { action.run(); } catch (IllegalArgumentException expected) { return; }
		throw new AssertionError(message);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
