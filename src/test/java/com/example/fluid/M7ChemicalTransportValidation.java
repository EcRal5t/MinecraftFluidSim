package com.example.fluid;

import com.example.chemistry.BinaryVolumeAllocator;
import com.example.chemistry.BuiltinChemicalDefinitions;
import com.example.chemistry.ChemicalMixtureRecord;
import com.example.chemistry.ChemicalParcelStore;
import com.example.chemistry.ChemicalRegistry;
import com.example.item.ChemicalContents;
import com.example.item.ContainerFluidLedger;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Active-domain M7 gate for marker ownership, reaction placement and conservative reseeding hooks. */
public final class M7ChemicalTransportValidation {
	private static final FluidSolverConfig STATIC_CONFIG = new FluidSolverConfig(
			1.0F, 1_000.0F, 0.0F, 0.01F, 64, 1.0E-6F, 0.45F, 0.002F, 0.0F);
	private static final FluidMaterialRegistry MATERIALS = new FluidMaterialRegistry(BuiltinFluidMaterials.M3_PALETTE);
	private static final ChemicalRegistry CHEMISTRY = BuiltinChemicalDefinitions.REGISTRY;

	private M7ChemicalTransportValidation() { }

	public static void main(String[] args) {
		validatePackedBudgetAndBinaryAllocation();
		validateSameCellReaction();
		validateAdjacentCellsDoNotReact();
		validateSplitAndIndicator();
		validateMergeAndCompaction();
		validateCollisionDisplacement();
		validateChemicalHandoff();
		validateChemicalContainerRoundTrip();
		System.out.println("M7 chemical transport gate passed: packed budget, same-cell reaction, separation and reseeding conservation");
	}

	private static void validatePackedBudgetAndBinaryAllocation() {
		ChemicalParcelStore maximum = new ChemicalParcelStore(CHEMISTRY, 73_728, 9_216);
		check(maximum.cpuBytes() <= 64L * 1024L * 1024L,
				"maximum default-domain chemical heap model exceeds 64 MiB: " + maximum.cpuBytes());
		check(maximum.pool().handleCapacity() == 73_729, "packed handle cap is not reservedMarkerCapacity + 1");
		check(maximum.pool().packedEntryCapacity() == 16 * 73_729, "packed entry cap is not 16 * handle cap");
		long[] equal = BinaryVolumeAllocator.allocate(5L, new float[]{0.125F, 0.125F}, new int[]{7, 3});
		check(Arrays.equals(equal, new long[]{2L, 3L}), "largest remainder did not use marker ordinal tie-break");
		long[] unequal = BinaryVolumeAllocator.allocate(10L, new float[]{0.1F, 0.2F, 0.3F}, new int[]{0, 1, 2});
		check(Arrays.stream(unequal).sum() == 10L, "binary volume allocation did not close");
	}

	private static void validateSameCellReaction() {
		FlipFluidDomain domain = domainWithCell(1, 1, 1, 1.0F);
		domain.setMarkerChemical(0, Map.of(BuiltinChemicalDefinitions.HCL, 1_000_000L), true);
		domain.setMarkerChemical(1, Map.of(BuiltinChemicalDefinitions.NAOH, 1_000_000L), false);
		FlipFluidDomain.StepStats stats = domain.step();
		long[] totals = domain.chemicalParcels().speciesTotals(domain.particleCount());
		check(totals[CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.H_PLUS)] == 0L, "same-cell reaction left H+");
		check(totals[CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.OH_MINUS)] == 0L, "same-cell reaction left OH-");
		check(totals[CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.NA_PLUS)] == 1_000_000L,
				"same-cell reaction lost Na+");
		check(totals[CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.CL_MINUS)] == 1_000_000L,
				"same-cell reaction lost Cl-");
		check(stats.chemistryDirtyCells() == 1 && stats.chemistryChangedMarkers() > 0,
				"logical chemistry did not run exactly on the dirty cell");
		int indicated = 0;
		for (int marker = 0; marker < domain.particleCount(); marker++) if (domain.chemicalParcels().indicator(marker)) indicated++;
		check(indicated == 1, "reaction redistributed the indicator bit");
	}

	private static void validateAdjacentCellsDoNotReact() {
		FlipFluidDomain domain = new FlipFluidDomain(4, 4, 4, STATIC_CONFIG, 64);
		domain.setVolume(1, 1, 1, 0.125F);
		domain.setVolume(2, 1, 1, 0.125F);
		domain.setMarkerMaterial(0, MATERIALS, 0);
		domain.bindChemistry(CHEMISTRY);
		domain.setMarkerChemical(0, Map.of(BuiltinChemicalDefinitions.HCL, 10_000L), false);
		domain.setMarkerChemical(1, Map.of(BuiltinChemicalDefinitions.NAOH, 10_000L), false);
		domain.step();
		long[] totals = domain.chemicalParcels().speciesTotals(domain.particleCount());
		check(totals[CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.H_PLUS)] == 10_000L,
				"acid reacted across an adjacent-cell boundary");
		check(totals[CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.OH_MINUS)] == 10_000L,
				"base reacted across an adjacent-cell boundary");
	}

	private static void validateSplitAndIndicator() {
		FlipFluidDomain domain = domainWithCell(1, 1, 1, 0.125F);
		domain.setMarkerChemical(0, Map.of(BuiltinChemicalDefinitions.H2O, 1_001L), true);
		long before = domain.chemicalParcels().totalQuanta();
		FlipFluidDomain.StepStats stats = domain.step();
		check(stats.reseededSplits() == 1 && domain.particleCount() == 2, "chemical marker did not split");
		check(domain.chemicalParcels().totalQuanta() == before, "split changed total chemical quanta");
		check(domain.chemicalParcels().record(0).totalQuanta()
				+ domain.chemicalParcels().record(1).totalQuanta() == before, "split parcel ledgers do not close");
		check(domain.chemicalParcels().indicator(0) && domain.chemicalParcels().indicator(1),
				"split did not copy indicator provenance");
	}

	private static void validateMergeAndCompaction() {
		int capacity = 32, count = 16;
		float[] x = new float[capacity], y = new float[capacity], z = new float[capacity];
		float[] vx = new float[capacity], vy = new float[capacity], vz = new float[capacity];
		float[] volume = new float[capacity];
		double[] mass = new double[capacity];
		int[] material = new int[capacity], phase = new int[capacity];
		boolean[] solid = new boolean[64];
		ChemicalParcelStore parcels = new ChemicalParcelStore(CHEMISTRY, capacity, 64);
		for (int marker = 0; marker < count; marker++) {
			x[marker] = 1.1F + 0.18F * (marker & 3);
			y[marker] = 1.1F + 0.18F * ((marker >>> 2) & 3);
			z[marker] = 1.2F + 0.03F * (marker & 1);
			volume[marker] = 0.03F;
			mass[marker] = 30.0;
			parcels.assignDefault(marker, BuiltinFluidMaterials.WATER.id(), volume[marker], 1.0F, mass[marker],
					(marker & 1) != 0);
		}
		long[] before = parcels.speciesTotals(count);
		FlipParticleReseeder.Result result = new FlipParticleReseeder(4, 4, 4, 1.0F, capacity).reseed(
				solid, x, y, z, vx, vy, vz, volume, mass, material, phase, parcels, count, true,
				(candidateX, candidateY, candidateZ) -> true);
		check(result.merged() > 0 && result.particleCount() < count, "overpopulated chemical cell did not merge");
		check(parcels.pool().liveCount() == result.particleCount(), "compaction leaked or aliased mixture handles");
		check(Arrays.equals(before, parcels.speciesTotals(result.particleCount())), "merge/compaction changed species totals");
		int plain = 0, indicated = 0;
		for (int marker = 0; marker < result.particleCount(); marker++) {
			if (parcels.indicator(marker)) indicated++; else plain++;
		}
		check(plain > 0 && indicated > 0, "merge crossed indicator provenance groups");
	}

	private static void validateCollisionDisplacement() {
		FlipFluidDomain domain = domainWithCell(1, 1, 1, 1.0F);
		domain.setMarkerChemical(0, Map.of(
				BuiltinChemicalDefinitions.H2O, 100_000L,
				BuiltinChemicalDefinitions.NACL, 10_000L), true);
		long[] before = domain.chemicalParcels().speciesTotals(domain.particleCount());
		double volumeBefore = domain.totalVolumeExact();
		FlipFluidDomain.ChemicalDisplacement displaced = domain.setSolidMicroMaskChemical(1, 1, 1, 0xff);
		check(domain.particleCount() == 0 && domain.chemicalParcels().totalQuanta() == 0L,
				"collision did not remove parcel ownership");
		check(displaced.ledger().totalQuanta() == Arrays.stream(before).sum(),
				"collision displacement ledger lost species");
		check(domain.chemicalParcels().escapedSink().isEmpty(),
				"recoverable collision displacement was recorded as escaped before restoration");
		domain.setSolidMicroMaskChemical(1, 1, 1, 0);
		FlipFluidDomain.ExactChemicalTransfer restored = domain.tryInsertExactChemical(
				1, 1, 1, displaced.ledger(), 0);
		check(restored.transferred(), "exact collision chemistry could not be restored");
		check(Double.doubleToRawLongBits(domain.totalVolumeExact()) == Double.doubleToRawLongBits(volumeBefore),
				"collision restoration changed exact marker volume");
		check(Arrays.equals(before, domain.chemicalParcels().speciesTotals(domain.particleCount())),
				"collision restoration changed species inventory");
		int indicated = 0;
		for (int marker = 0; marker < domain.particleCount(); marker++) if (domain.chemicalParcels().indicator(marker)) indicated++;
		check(indicated > 0, "collision restoration lost indicator provenance");
	}

	private static void validateChemicalHandoff() {
		FlipFluidDomain source = domainWithCell(1, 1, 1, 0.25F);
		source.setMarkerChemical(0, Map.of(
				BuiltinChemicalDefinitions.H2O, 123_457L,
				BuiltinChemicalDefinitions.NACL, 999L), true);
		FlipChemicalParticleState state = source.exportChemicalParticleState();
		FlipFluidDomain restored = new FlipFluidDomain(4, 4, 4, STATIC_CONFIG, 64);
		restored.restoreChemicalParticleState(state, CHEMISTRY);
		check(Arrays.equals(source.chemicalParcels().speciesTotals(source.particleCount()),
				restored.chemicalParcels().speciesTotals(restored.particleCount())),
				"chemical handoff changed species inventory");
		check(Arrays.equals(state.copyIndicator(), restored.exportChemicalParticleState().copyIndicator()),
				"chemical handoff changed indicator bits");
		check(state.mixtures().equals(restored.exportChemicalParticleState().mixtures()),
				"chemical handoff changed canonical mixture table");
		check(state.residualLedgerBits() == restored.exportChemicalParticleState().residualLedgerBits(),
				"chemical handoff changed raw residual ledger");
	}

	private static void validateChemicalContainerRoundTrip() {
		FlipFluidDomain source = domainWithCell(1, 1, 1, 0.25F);
		for (int marker = 0; marker < source.particleCount(); marker++) {
			source.setMarkerChemical(marker, Map.of(
					BuiltinChemicalDefinitions.H2O, 12_345_678L + marker,
					BuiltinChemicalDefinitions.NACL, 456_789L + marker), true);
		}
		long[] expectedSpecies = source.chemicalParcels().speciesTotals(source.particleCount());
		long expectedResidual = source.exportChemicalParticleState().residualLedgerBits();
		double expectedMass = source.totalMass();

		FlipFluidDomain.ExactChemicalTransfer extracted = source.tryExtractExactChemical(1, 1, 1, 0.25F, 0);
		check(extracted.transferred() && source.particleCount() == 0,
				"chemical bucket extraction was not all-or-nothing");
		ChemicalContents contents = ChemicalContents.fromLedger(extracted.ledger(), CHEMISTRY,
				ContainerFluidLedger.volumeToNanos(extracted.ledger().totalVolume()));
		check(ChemicalContents.decode(contents.encode()).equals(contents),
				"chemical bucket canonical codec did not round-trip");
		FlipFluidDomain restored = source;
		FlipFluidDomain.ExactChemicalTransfer inserted = restored.tryInsertExactChemical(
				1, 1, 1, contents.toLedger(CHEMISTRY), 0);
		check(inserted.transferred(), "canonical chemical bucket could not be inserted");
		check(Arrays.equals(expectedSpecies, restored.chemicalParcels().speciesTotals(restored.particleCount())),
				"chemical bucket round-trip changed species inventory");
		check(restored.exportChemicalParticleState().residualLedgerBits() == expectedResidual,
				"chemical bucket round-trip changed raw mass residual ledger");
		check(Double.doubleToRawLongBits(restored.totalMass()) == Double.doubleToRawLongBits(expectedMass),
				"chemical bucket round-trip changed exact mass");
		for (int marker = 0; marker < restored.particleCount(); marker++) {
			check(restored.chemicalParcels().indicator(marker), "chemical bucket round-trip lost indicator provenance");
		}

		ChemicalContents incompatible = new ChemicalContents(CHEMISTRY.fingerprint(),
				com.example.chemistry.ChemicalLimits.QUANTA_PER_AU, List.of(new ChemicalContents.Group(
				BuiltinFluidMaterials.OIL.id(), false, 1L,
				Double.doubleToRawLongBits(CHEMISTRY.species(CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.H2O))
						.massPerAu().toDouble()), Double.doubleToRawLongBits(0.0),
				List.of(new ChemicalContents.SpeciesAmount(BuiltinChemicalDefinitions.H2O,
						com.example.chemistry.ChemicalLimits.QUANTA_PER_AU)))));
		try {
			incompatible.validateAgainst(CHEMISTRY);
			throw new AssertionError("Expected chemical phase incompatibility rejection");
		} catch (IllegalArgumentException expected) {
			// The item remains intact; no solver mutation is attempted.
		}
	}

	private static FlipFluidDomain domainWithCell(int x, int y, int z, float volume) {
		FlipFluidDomain domain = new FlipFluidDomain(4, 4, 4, STATIC_CONFIG, 64);
		domain.setVolume(x, y, z, volume);
		domain.setMarkerMaterial(0, MATERIALS, 0);
		domain.bindChemistry(CHEMISTRY);
		return domain;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
