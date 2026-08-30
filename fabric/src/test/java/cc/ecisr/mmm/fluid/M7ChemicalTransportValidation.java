package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.BinaryVolumeAllocator;
import cc.ecisr.mmm.chemistry.BuiltinChemicalDefinitions;
import cc.ecisr.mmm.chemistry.ChemicalAuditLedgerCodec;
import cc.ecisr.mmm.chemistry.ChemicalLimits;
import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalParcelStore;
import cc.ecisr.mmm.chemistry.ChemicalPhaseDefinition;
import cc.ecisr.mmm.chemistry.ChemicalReaction;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.chemistry.ChemicalSpecies;
import cc.ecisr.mmm.chemistry.ChemicalTransferLedger;
import cc.ecisr.mmm.chemistry.ExactRational;
import cc.ecisr.mmm.transfer.ChemicalContents;
import cc.ecisr.mmm.transfer.ContainerFluidLedger;
import cc.ecisr.mmm.units.SolverUnitScale;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Active-domain M7 gate for marker ownership, reaction placement and conservative reseeding hooks. */
public final class M7ChemicalTransportValidation {
	private static final FluidSolverConfig STATIC_CONFIG = new FluidSolverConfig(
			1.0F, 1_000.0F, 0.0F, 0.01F, 64, 1.0E-6F, 0.45F, 0.002F, 0.0F);
	private static final FluidMaterialRegistry MATERIALS = new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE);
	private static final ChemicalRegistry CHEMISTRY = BuiltinChemicalDefinitions.REGISTRY;

	private M7ChemicalTransportValidation() { }

	public static void main(String[] args) {
		boolean fast = args.length > 0 && args[0].equals("--fast");
		validatePackedBudgetAndBinaryAllocation();
		validateInternalTransferLedgerDiversity();
		validateMissingPhaseRejectedBeforeBinding();
		validateExternalSourceBoundary();
		validateSameCellReaction();
		validateReactionKeepsTinyParcels();
		validateExactNoOpReactionPreservesParcels();
		validateAdjacentCellsDoNotReact();
		validateWallSeparatedCellsDoNotReact();
		validateImmiscibleMarkersDoNotReact();
		validateSplitAndIndicator();
		validateIndicatorFloodFill();
		validateMergeAndCompaction();
		validateCollisionDisplacement();
		validateChemicalHandoff();
		validateConcentrationControlsDensity();
		validateChemicalContainerRoundTrip();
		if (!fast) validatePerformanceBudgets();
		System.out.println("M7 chemical transport gate passed: packed budget, same-cell reaction, separation and reseeding conservation");
	}

	private static void validatePackedBudgetAndBinaryAllocation() {
		ChemicalParcelStore maximum = new ChemicalParcelStore(
				CHEMISTRY, SolverUnitScale.forCellSize(1.0F), 73_728, 9_216);
		long residentBytes = Math.addExact(maximum.cpuBytes(), 2L * 73_728L * Integer.BYTES);
		check(residentBytes <= 64L * 1024L * 1024L,
				"maximum default-domain chemical heap model exceeds 64 MiB: " + residentBytes);
		check(maximum.pool().handleCapacity() == 73_729, "packed handle cap is not reservedMarkerCapacity + 1");
		check(maximum.pool().packedEntryCapacity() == 16 * 73_729, "packed entry cap is not 16 * handle cap");
		long[] equal = BinaryVolumeAllocator.allocate(5L, new float[]{0.125F, 0.125F}, new int[]{7, 3});
		check(Arrays.equals(equal, new long[]{2L, 3L}), "largest remainder did not use marker ordinal tie-break");
		long[] unequal = BinaryVolumeAllocator.allocate(10L, new float[]{0.1F, 0.2F, 0.3F}, new int[]{0, 1, 2});
		check(Arrays.stream(unequal).sum() == 10L, "binary volume allocation did not close");
	}

	private static void validateInternalTransferLedgerDiversity() {
		java.util.TreeMap<String, Long> species = new java.util.TreeMap<>();
		for (int index = 0; index < 32; index++) species.put("test:species_" + index, 1L);
		ChemicalTransferLedger ledger = new ChemicalTransferLedger(List.of(new ChemicalTransferLedger.Group(
				"mmm:water", false, 1.0, 1.0, Double.doubleToRawLongBits(0.0), species)));
		try {
			ChemicalAuditLedgerCodec.AuditLedgers decoded = ChemicalAuditLedgerCodec.decode(
					ChemicalAuditLedgerCodec.encode(ledger, ChemicalTransferLedger.EMPTY));
			check(decoded.externalSource().groups().equals(ledger.groups()),
					"complete internal transfer ledger was truncated to the per-parcel species budget");
		} catch (java.io.IOException invalid) {
			throw new AssertionError("complete internal transfer ledger did not round-trip", invalid);
		}
	}

	private static void validateMissingPhaseRejectedBeforeBinding() {
		FluidMaterialRegistry materials = new FluidMaterialRegistry(List.of(
				BuiltinFluidMaterials.DEFAULT_PALETTE.getFirst(),
				new FluidMaterial("test:missing_phase", 1_000.0, "test:missing_phase", 0.0, Math.PI / 2.0)));
		FluidDomain domain = new FluidDomain(4, 4, 4, STATIC_CONFIG, 64);
		domain.setVolume(1, 1, 1, 0.125F);
		domain.setMarkerMaterial(0, materials, 0);
		try {
			domain.bindChemistry(CHEMISTRY);
			throw new AssertionError("material palette with no chemical phase was accepted");
		} catch (IllegalArgumentException expected) {
			check(!domain.hasChemistryState(), "failed chemistry binding left a partial parcel store");
		}
	}

	private static void validateExternalSourceBoundary() {
		FluidDomain domain = domainWithCell(1, 1, 1, 0.125F);
		FluidDomain.ExactVolumeTransfer survival = domain.tryInsertExactVolume(1, 1, 1,
				0.125F, 0, 0, false);
		check(survival.transferred() && domain.chemicalParcels().externalSource().isEmpty(),
				"conservative insertion was recorded as an external source");
		FluidDomain.ExactVolumeTransfer creative = domain.tryInsertExactVolume(1, 1, 1,
				0.125F, 0, 0, true);
		check(creative.transferred() && !domain.chemicalParcels().externalSource().isEmpty(),
				"explicit external insertion was not recorded atomically");
	}

	private static void validateWallSeparatedCellsDoNotReact() {
		FluidDomain domain = new FluidDomain(5, 4, 4, STATIC_CONFIG, 64);
		domain.setVolume(1, 1, 1, 0.125F);
		domain.setVolume(3, 1, 1, 0.125F);
		domain.setSolid(2, 1, 1, true);
		domain.setMarkerMaterial(0, MATERIALS, 0);
		domain.bindChemistry(CHEMISTRY);
		domain.setMarkerChemical(0, Map.of(BuiltinChemicalDefinitions.HCL, 10_000L), false);
		domain.setMarkerChemical(1, Map.of(BuiltinChemicalDefinitions.NAOH, 10_000L), false);
		domain.step();
		long[] totals = domain.chemicalParcels().speciesTotals(domain.particleCount());
		check(totals[CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.H_PLUS)] == 10_000L
				&& totals[CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.OH_MINUS)] == 10_000L,
				"acid/base reacted through a solid wall");
	}

	private static void validateImmiscibleMarkersDoNotReact() {
		ChemicalSpecies acid = new ChemicalSpecies("test:h_plus", Map.of("H", 1), 1, ExactRational.ONE, 0xffff0000);
		ChemicalSpecies base = new ChemicalSpecies("test:oh_minus", Map.of("H", 1, "O", 1), -1,
				ExactRational.of(17), 0xff0000ff);
		ChemicalSpecies water = new ChemicalSpecies("test:h2o", Map.of("H", 2, "O", 1), 0,
				ExactRational.of(18), 0xff4e9cff);
		List<String> allowed = List.of(acid.id(), base.id(), water.id());
		ChemicalPhaseDefinition phaseA = new ChemicalPhaseDefinition("test:phase_a", "test:material_a",
				allowed, Map.of(water.id(), ExactRational.ONE), true);
		ChemicalPhaseDefinition phaseB = new ChemicalPhaseDefinition("test:phase_b", "test:material_b",
				allowed, Map.of(water.id(), ExactRational.ONE), true);
		ChemicalReaction neutralizeA = new ChemicalReaction("test:neutralize_a", ChemicalReaction.Kind.INSTANTANEOUS,
				phaseA.id(), Map.of(acid.id(), 1, base.id(), 1), Map.of(water.id(), 1), ExactRational.ZERO);
		ChemicalRegistry chemistry = new ChemicalRegistry(List.of(acid, base, water),
				List.of(phaseA, phaseB), List.of(neutralizeA));
		FluidMaterialRegistry materials = new FluidMaterialRegistry(List.of(
				new FluidMaterial("test:material_a", 1_000.0, "test:immiscible_a", 0.0, Math.PI / 2.0),
				new FluidMaterial("test:material_b", 1_000.0, "test:immiscible_b", 0.0, Math.PI / 2.0)));
		FluidDomain domain = new FluidDomain(4, 4, 4, STATIC_CONFIG, 64);
		domain.setVolume(1, 1, 1, 0.25F);
		domain.setMarkerMaterial(0, materials, 0);
		domain.setMarkerMaterial(1, materials, 1);
		domain.bindChemistry(chemistry);
		domain.setMarkerChemical(0, Map.of(acid.id(), 10_000L), false);
		domain.setMarkerChemical(1, Map.of(base.id(), 10_000L), false);
		domain.step();
		long[] totals = domain.chemicalParcels().speciesTotals(domain.particleCount());
		check(totals[chemistry.speciesHandle(acid.id())] == 10_000L
				&& totals[chemistry.speciesHandle(base.id())] == 10_000L,
				"immiscible chemical phases reacted within one grid cell");
	}

	private static void validateSameCellReaction() {
		FluidDomain domain = domainWithCell(1, 1, 1, 1.0F);
		domain.setMarkerChemical(0, Map.of(BuiltinChemicalDefinitions.HCL, 1_000_000L), true);
		domain.setMarkerChemical(1, Map.of(BuiltinChemicalDefinitions.NAOH, 1_000_000L), false);
		int markerCount = domain.particleCount();
		long[] residualBits = new long[markerCount];
		for (int marker = 0; marker < markerCount; marker++) {
			residualBits[marker] = domain.chemicalParcels().record(marker).massResidualKilogramsBits();
		}
		double massBefore = domain.totalMass();
		FluidDomain.StepStats stats = domain.step();
		long[] totals = domain.chemicalParcels().speciesTotals(domain.particleCount());
		check(totals[CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.H_PLUS)] == 0L, "same-cell reaction left H+");
		check(totals[CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.OH_MINUS)] == 0L, "same-cell reaction left OH-");
		check(totals[CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.NA_PLUS)] == 1_000_000L,
				"same-cell reaction lost Na+");
		check(totals[CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.CL_MINUS)] == 1_000_000L,
				"same-cell reaction lost Cl-");
		check(stats.chemistryDirtyCells() == 1 && stats.chemistryChangedMarkers() > 0,
				"logical chemistry did not run exactly on the dirty cell");
		check(domain.particleCount() == markerCount, "reaction-only step changed marker ownership");
		for (int marker = 0; marker < markerCount; marker++) {
			check(domain.chemicalParcels().record(marker).massResidualKilogramsBits() == residualBits[marker],
					"same-cell reaction rewrote marker mass residual truth");
		}
		check(Math.abs(domain.totalMass() - massBefore) <= Math.max(1.0E-9, Math.ulp(massBefore) * 8.0),
				"same-cell reaction changed marker mass truth");
		int indicated = 0;
		for (int marker = 0; marker < domain.particleCount(); marker++) if (domain.chemicalParcels().indicator(marker)) indicated++;
		check(indicated == 1, "reaction redistributed the indicator bit");
	}

	private static void validateReactionKeepsTinyParcels() {
		final int markerCount = 3;
		ChemicalParcelStore parcels = new ChemicalParcelStore(
				CHEMISTRY, SolverUnitScale.forCellSize(1.0F), markerCount, 64);
		ChemicalMixtureRecord acid = ChemicalMixtureRecord.fromStableAmounts(
				CHEMISTRY, Map.of(BuiltinChemicalDefinitions.HCL, 1_000_000L), 0.0);
		ChemicalMixtureRecord base = ChemicalMixtureRecord.fromStableAmounts(
				CHEMISTRY, Map.of(BuiltinChemicalDefinitions.NAOH, 1L), 0.0);
		ChemicalMixtureRecord water = ChemicalMixtureRecord.fromStableAmounts(
				CHEMISTRY, Map.of(BuiltinChemicalDefinitions.H2O, 1L), 0.0);
		parcels.assign(0, acid, false);
		parcels.assign(1, base, true);
		parcels.assign(2, water, false);
		parcels.markDirtyCell((1 * 4 + 1) * 4 + 1);
		float[] positions = {1.5F, 1.5F, 1.5F};
		double[] masses = {acid.massKilograms(CHEMISTRY), base.massKilograms(CHEMISTRY), water.massKilograms(CHEMISTRY)};
		int[] material = new int[markerCount];
		ChemicalParcelStore.ReactionStats stats = parcels.reactDirtyCells(4, 4, 4, 1.0F,
				positions, positions, positions, masses, material,
				ignored -> BuiltinFluidMaterials.WATER.id(), markerCount);
		long[] totals = parcels.speciesTotals(markerCount);
		check(stats.changedMarkers() == markerCount, "tiny-parcel reaction did not commit all replacements");
		check(totals[CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.H_PLUS)] == 999_999L
				&& totals[CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.CL_MINUS)] == 1_000_000L
				&& totals[CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.NA_PLUS)] == 1L
				&& totals[CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.H2O)] == 2L,
				"tiny-parcel repair changed the reacted species ledger");
		for (int marker = 0; marker < markerCount; marker++) {
			check(parcels.record(marker).totalQuanta() > 0L, "reaction left an empty live parcel");
		}
		check(parcels.indicator(1), "tiny-parcel repair changed indicator provenance");
	}

	private static void validateExactNoOpReactionPreservesParcels() {
		final int markerCount = 2;
		ChemicalParcelStore parcels = new ChemicalParcelStore(
				CHEMISTRY, SolverUnitScale.forCellSize(1.0F), markerCount, 64);
		ChemicalMixtureRecord first = ChemicalMixtureRecord.fromStableAmounts(
				CHEMISTRY, Map.of(BuiltinChemicalDefinitions.H2O, 1_001L), 0.125);
		ChemicalMixtureRecord second = ChemicalMixtureRecord.fromStableAmounts(
				CHEMISTRY, Map.of(BuiltinChemicalDefinitions.H2O, 2_003L), -0.25);
		parcels.assign(0, first, true);
		parcels.assign(1, second, false);
		parcels.markDirtyCell((1 * 4 + 1) * 4 + 1);
		float[] positions = {1.5F, 1.5F};
		double[] masses = {first.massKilograms(CHEMISTRY), second.massKilograms(CHEMISTRY)};
		double[] beforeMasses = masses.clone();
		int[] validations = {0};
		ChemicalParcelStore.ReactionStats stats = parcels.reactDirtyCells(4, 4, 4, 1.0F,
				positions, positions, positions, masses, new int[markerCount],
				ignored -> BuiltinFluidMaterials.WATER.id(), markerCount,
				(marker, mixture, mass) -> validations[0]++);
		check(stats.dirtyCells() == 1 && stats.evaluations() > 0 && stats.changedMarkers() == 0,
				"exact no-op reaction did not report its evaluated unchanged cell");
		check(validations[0] == 0, "exact no-op reaction entered candidate thermal validation");
		check(parcels.record(0).equals(first) && parcels.record(1).equals(second),
				"exact no-op reaction redistributed marker composition or residuals");
		check(Arrays.equals(masses, beforeMasses) && parcels.indicator(0) && !parcels.indicator(1),
				"exact no-op reaction changed marker mass or indicator provenance");
	}

	private static void validateAdjacentCellsDoNotReact() {
		FluidDomain domain = new FluidDomain(4, 4, 4, STATIC_CONFIG, 64);
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
		FluidDomain domain = domainWithCell(1, 1, 1, 0.125F);
		domain.setMarkerChemical(0, Map.of(BuiltinChemicalDefinitions.H2O, 1_001L), true);
		long before = domain.chemicalParcels().totalQuanta();
		FluidDomain.StepStats stats = domain.step();
		check(stats.reseededSplits() == 1 && domain.particleCount() == 2, "chemical marker did not split");
		check(domain.chemicalParcels().totalQuanta() == before, "split changed total chemical quanta");
		check(domain.chemicalParcels().record(0).totalQuanta()
				+ domain.chemicalParcels().record(1).totalQuanta() == before, "split parcel ledgers do not close");
		check(domain.chemicalParcels().indicator(0) && domain.chemicalParcels().indicator(1),
				"split did not copy indicator provenance");
	}

	private static void validateIndicatorFloodFill() {
		FluidDomain domain = new FluidDomain(4, 4, 4, STATIC_CONFIG, 64);
		domain.setVolume(1, 1, 1, 0.125F);
		domain.setVolume(2, 1, 1, 0.125F);
		domain.setMarkerMaterial(0, MATERIALS, 0);
		domain.bindChemistry(CHEMISTRY);
		FluidDomain.IndicatorEditResult rejected = domain.editIndicatorFloodFill(1, 1, 1,
				true, 64, 0.1);
		check(!rejected.accepted(), "over-volume indicator fill was partially accepted");
		for (int marker = 0; marker < domain.particleCount(); marker++) {
			check(!domain.chemicalParcels().indicator(marker), "rejected indicator fill changed a marker");
		}
		FluidDomain.IndicatorEditResult added = domain.editIndicatorFloodFill(1, 1, 1,
				true, 64, 100.0);
		check(added.accepted() && added.changedMarkers() == 2 && added.affectedBlockVolume() == 0.25,
				"connected aqueous indicator fill did not cover its exact component");
		for (int marker = 0; marker < domain.particleCount(); marker++) {
			check(domain.chemicalParcels().indicator(marker), "connected indicated marker was skipped");
		}
		FluidDomain.IndicatorEditResult shallowAim = domain.editIndicatorFloodFill(1, 3, 1,
				false, 64, Double.POSITIVE_INFINITY);
		check(shallowAim.accepted() && shallowAim.changedMarkers() == 2,
				"shallow-surface aim did not snap to nearby chemical marker truth");
		for (int marker = 0; marker < domain.particleCount(); marker++) {
			check(!domain.chemicalParcels().indicator(marker), "shallow-surface eraser skipped a marker");
		}
		added = domain.editIndicatorFloodFill(1, 1, 1, true, 64, 100.0);
		check(added.accepted() && added.changedMarkers() == 2,
				"indicator could not be restored after shallow-aim regression");

		// A marker introduced after the fill keeps its own unmarked provenance.
		domain.setVolume(2, 1, 1, 0.25F);
		int marked = 0, plain = 0;
		for (int marker = 0; marker < domain.particleCount(); marker++) {
			if (domain.chemicalParcels().indicator(marker)) marked++; else plain++;
		}
		check(marked > 0 && plain > 0, "later mixed markers inherited indicator retroactively");
		FluidDomain.IndicatorEditResult erased = domain.editIndicatorFloodFill(1, 1, 1,
				false, 64, Double.POSITIVE_INFINITY);
		check(erased.accepted() && erased.changedMarkers() == marked, "eraser did not clear only current marked provenance");
		for (int marker = 0; marker < domain.particleCount(); marker++) {
			check(!domain.chemicalParcels().indicator(marker), "eraser left an indicated marker");
		}

		FluidDomain wall = new FluidDomain(5, 4, 4, STATIC_CONFIG, 64);
		wall.setVolume(1, 1, 1, 0.125F);
		wall.setSolid(2, 1, 1, true);
		wall.setMarkerMaterial(0, MATERIALS, 0);
		wall.bindChemistry(CHEMISTRY);
		FluidDomain.IndicatorEditResult throughWall = wall.editIndicatorFloodFill(3, 1, 1,
				true, 64, 100.0);
		check(!throughWall.accepted(), "shallow-aim snapping crossed a closed cut face");
	}

	private static void validateMergeAndCompaction() {
		int capacity = 32, count = 16;
		float[] x = new float[capacity], y = new float[capacity], z = new float[capacity];
		float[] vx = new float[capacity], vy = new float[capacity], vz = new float[capacity];
		float[] volume = new float[capacity];
		double[] mass = new double[capacity];
		int[] material = new int[capacity], phase = new int[capacity];
		boolean[] solid = new boolean[64];
		ChemicalParcelStore parcels = new ChemicalParcelStore(
				CHEMISTRY, SolverUnitScale.forCellSize(1.0F), capacity, 64);
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
		FluidDomain domain = domainWithCell(1, 1, 1, 1.0F);
		domain.setMarkerChemical(0, Map.of(
				BuiltinChemicalDefinitions.H2O, 100_000L,
				BuiltinChemicalDefinitions.NACL, 10_000L), true);
		long[] before = domain.chemicalParcels().speciesTotals(domain.particleCount());
		double volumeBefore = domain.totalVolumeExact();
		FluidDomain.ChemicalDisplacement displaced = domain.setSolidMicroMaskChemical(1, 1, 1, 0xff);
		check(domain.particleCount() == 0 && domain.chemicalParcels().totalQuanta() == 0L,
				"collision did not remove parcel ownership");
		check(displaced.ledger().totalQuanta() == Arrays.stream(before).sum(),
				"collision displacement ledger lost species");
		check(domain.chemicalParcels().escapedSink().isEmpty(),
				"recoverable collision displacement was recorded as escaped before restoration");
		domain.setSolidMicroMaskChemical(1, 1, 1, 0);
		FluidDomain.ExactChemicalTransfer restored = domain.tryInsertExactChemical(
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
		FluidDomain source = domainWithCell(1, 1, 1, 0.25F);
		source.setMarkerChemical(0, Map.of(
				BuiltinChemicalDefinitions.H2O, 123_457L,
				BuiltinChemicalDefinitions.NACL, 999L), true);
		var auditFixture = source.chemicalLedgerForMarkerRange(0, 1);
		source.chemicalParcels().recordExternalSource(auditFixture);
		source.chemicalParcels().recordEscapedSink(auditFixture);
		FlipChemicalParticleState state = source.exportChemicalParticleState();
		FluidDomain restored = new FluidDomain(4, 4, 4, STATIC_CONFIG, 64);
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
		check(state.externalSource().groups().equals(restored.exportChemicalParticleState().externalSource().groups())
				&& state.escapedSink().groups().equals(restored.exportChemicalParticleState().escapedSink().groups()),
				"chemical handoff changed external source/sink audit ledgers");
	}

	private static void validateConcentrationControlsDensity() {
		validateConcentrationControlsDensity(STATIC_CONFIG);
		validateConcentrationControlsDensity(new FluidSolverConfig(
				0.5F, 1_000.0F, 0.0F, 0.01F, 64, 1.0E-6F, 0.45F, 0.002F, 0.0F));
	}

	private static void validateConcentrationControlsDensity(FluidSolverConfig config) {
		FluidDomain source = new FluidDomain(4, 4, 4, config, 128);
		source.setVolume(1, 1, 1, 1.0F);
		source.setVolume(2, 1, 1, 1.0F);
		source.setMarkerMaterial(0, MATERIALS, 0);
		source.bindChemistry(CHEMISTRY);
		FlipMaterialParticleState original = source.exportMaterialParticleState();
		double[] mass = original.copyParticleMass();
		float[] positionVolume = original.copyPositionVolume();
		SolverUnitScale unitScale = SolverUnitScale.forCellSize(config.cellSize());
		java.util.ArrayList<ChemicalMixtureRecord> mixtures = new java.util.ArrayList<>(original.particleCount());
		byte[] indicators = new byte[original.particleCount()];
		for (int marker = 0; marker < original.particleCount(); marker++) {
			ChemicalMixtureRecord initial = source.chemicalParcels().record(marker);
			java.util.TreeMap<String, Long> amounts = new java.util.TreeMap<>(initial.stableAmounts(CHEMISTRY));
			int cellX = (int) Math.floor(positionVolume[marker * 4] / config.cellSize());
			if (cellX == 2) {
				long saltQuanta = Math.round(unitScale.kilograms(mass[marker]) * 0.10
						* ChemicalLimits.QUANTA_PER_AU / 58.0);
				amounts.merge(BuiltinChemicalDefinitions.NACL, saltQuanta, Math::addExact);
			}
			ChemicalMixtureRecord mixture = ChemicalMixtureRecord.fromStableAmounts(
					CHEMISTRY, amounts, initial.massResidualKilograms());
			mixtures.add(mixture);
			mass[marker] = unitScale.solverMass(mixture.massKilograms(CHEMISTRY));
		}
		double totalMass = 0.0;
		for (double markerMass : mass) totalMass += markerMass;
		FlipMaterialParticleState materials = new FlipMaterialParticleState(
				original.sizeX(), original.sizeY(), original.sizeZ(), original.cellSize(), original.particleCount(),
				original.totalVolume(), totalMass, original.materials(), positionVolume,
				original.copyParticleVelocities(), original.copySolidMask(), mass,
				original.copyMaterialPaletteIndex());
		FlipChemicalParticleState chemical = FlipChemicalParticleState.capture(
				materials, CHEMISTRY, mixtures, indicators,
				cc.ecisr.mmm.chemistry.ChemicalTransferLedger.EMPTY,
				cc.ecisr.mmm.chemistry.ChemicalTransferLedger.EMPTY);
		FluidDomain stratified = new FluidDomain(4, 4, 4, config, 128);
		stratified.restoreChemicalParticleState(chemical, CHEMISTRY);
		double freshDensity = stratified.densityInCell(1, 1, 1);
		double brineDensity = stratified.densityInCell(2, 1, 1);
		FluidCellInventoryView brine = stratified.observeCellInventory(2, 1, 1);
		check(Math.abs(freshDensity - 1_000.0) < 1.0E-9
				&& brineDensity > 1_099.999 && brineDensity < 1_100.001
				&& stratified.usesMassWeightedP2G()
				&& brine != null && Math.abs(brine.densityKilogramsPerCubicMetre() - brineDensity) < 1.0E-9
				&& brine.species().stream().anyMatch(value -> value.speciesId().equals(BuiltinChemicalDefinitions.NACL)),
				"species mass/marker volume did not produce concentration-dependent cell density: fresh="
						+ freshDensity + " brine=" + brineDensity + " cellSize=" + config.cellSize());
	}

	private static void validateChemicalContainerRoundTrip() {
		FluidDomain source = domainWithCell(1, 1, 1, 0.25F);
		for (int marker = 0; marker < source.particleCount(); marker++) {
			source.setMarkerChemical(marker, Map.of(
					BuiltinChemicalDefinitions.H2O, 12_345_678L + marker,
					BuiltinChemicalDefinitions.NACL, 456_789L + marker), true);
		}
		long[] expectedSpecies = source.chemicalParcels().speciesTotals(source.particleCount());
		long expectedResidual = source.exportChemicalParticleState().residualLedgerBits();
		double expectedMass = source.totalMass();

		FluidDomain.ExactChemicalTransfer extracted = source.tryExtractExactChemical(1, 1, 1, 0.25F, 0);
		check(extracted.transferred() && source.particleCount() == 0,
				"chemical bucket extraction was not all-or-nothing");
		ChemicalContents contents = ChemicalContents.fromLedger(extracted.ledger(), CHEMISTRY,
				ContainerFluidLedger.volumeToNanos(extracted.ledger().totalVolume()));
		check(ChemicalContents.decode(contents.encode()).equals(contents),
				"chemical bucket canonical codec did not round-trip");
		ChemicalContents.Split partial = contents.split(123_456_789L, CHEMISTRY);
		check(partial.remaining() != null
				&& partial.inserted().totalVolumeNanos() + partial.remaining().totalVolumeNanos()
				== contents.totalVolumeNanos()
				&& partial.inserted().totalQuanta() + partial.remaining().totalQuanta() == contents.totalQuanta()
				&& mergedContentsSpecies(partial.inserted(), partial.remaining()).equals(contentsSpecies(contents))
				&& Math.abs(partial.inserted().totalMass() + partial.remaining().totalMass() - contents.totalMass())
				<= Math.max(1.0E-9, Math.ulp(contents.totalMass()) * 8.0),
				"partial chemical bucket split did not close volume/species/mass");
		partial.inserted().validateAgainst(CHEMISTRY);
		partial.remaining().validateAgainst(CHEMISTRY);
		FluidDomain restored = source;
		FluidDomain.ExactChemicalTransfer inserted = restored.tryInsertExactChemical(
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
		long[] beforeRefusedSpecies = restored.chemicalParcels().speciesTotals(restored.particleCount());
		double beforeRefusedVolume = restored.totalVolumeExact();
		FluidDomain.ExactChemicalTransfer refusedRepresentation = restored.tryExtractExactChemical(
				1, 1, 1, 0.125F, 0, ignored -> false);
		check(!refusedRepresentation.transferred()
				&& Double.doubleToRawLongBits(restored.totalVolumeExact()) == Double.doubleToRawLongBits(beforeRefusedVolume)
				&& Arrays.equals(beforeRefusedSpecies,
						restored.chemicalParcels().speciesTotals(restored.particleCount())),
				"unrepresentable chemical bucket extraction committed marker changes");

		ChemicalContents incompatible = new ChemicalContents(CHEMISTRY.fingerprint(),
				cc.ecisr.mmm.chemistry.ChemicalLimits.QUANTA_PER_AU, List.of(new ChemicalContents.Group(
				BuiltinFluidMaterials.OIL.id(), false, 1L,
				Double.doubleToRawLongBits(CHEMISTRY.species(CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.H2O))
						.massKilogramsPerAu().toDouble()), Double.doubleToRawLongBits(0.0),
				List.of(new ChemicalContents.SpeciesAmount(BuiltinChemicalDefinitions.H2O,
						cc.ecisr.mmm.chemistry.ChemicalLimits.QUANTA_PER_AU)))));
		try {
			incompatible.validateAgainst(CHEMISTRY);
			throw new AssertionError("Expected chemical phase incompatibility rejection");
		} catch (IllegalArgumentException expected) {
			// The item remains intact; no solver mutation is attempted.
		}
	}

	/** Frozen M7.0 wall-time budgets, measured only by the explicit full chemistry selector. */
	private static void validatePerformanceBudgets() {
		FluidDomain baseline = benchmarkDomain(false);
		FluidDomain chemical = benchmarkDomain(true);
		for (int warmup = 0; warmup < 30; warmup++) {
			baseline.step();
			chemical.step();
		}
		long[] baselineSamples = new long[15];
		long[] chemicalSamples = new long[15];
		double[] pairedDeltaSamples = new double[15];
		for (int sample = 0; sample < baselineSamples.length; sample++) {
			if ((sample & 1) == 0) {
				baselineSamples[sample] = timedSteps(baseline, 20);
				chemicalSamples[sample] = timedSteps(chemical, 20);
			} else {
				chemicalSamples[sample] = timedSteps(chemical, 20);
				baselineSamples[sample] = timedSteps(baseline, 20);
			}
			pairedDeltaSamples[sample] = (double) chemicalSamples[sample] / baselineSamples[sample] - 1.0;
		}
		long baselineMedian = percentile(baselineSamples, 0.50);
		long chemicalMedian = percentile(chemicalSamples, 0.50);
		Arrays.sort(pairedDeltaSamples);
		double stepDelta = pairedDeltaSamples[pairedDeltaSamples.length / 2];
		System.out.printf("M7.0 clean-step raw median: baseline=%.3f ms chemistry=%.3f ms paired-delta=%.2f%%%n",
				baselineMedian / 20_000_000.0, chemicalMedian / 20_000_000.0, stepDelta * 100.0);
		check(stepDelta <= 0.05,
				"chemistry-on clean-step median overhead exceeds 5%: " + stepDelta * 100.0 + "%");

		long reactionP95 = benchmarkAcidBaseReactionP95();
		check(reactionP95 <= 10_000_000L,
				"typical acid/base chemistry p95 exceeds 10 ms: " + reactionP95 / 1_000_000.0 + " ms");
		long residentBytes = Math.addExact(new ChemicalParcelStore(
				CHEMISTRY, SolverUnitScale.forCellSize(1.0F), 73_728, 9_216).cpuBytes(),
				2L * 73_728L * Integer.BYTES);
		System.out.printf("M7.0 budgets: heap=%.2f MiB clean-step-median=%.3f/%.3f ms delta=%.2f%% acid-base-p95=%.3f ms%n",
				residentBytes / 1048576.0, baselineMedian / 20_000_000.0, chemicalMedian / 20_000_000.0,
				stepDelta * 100.0, reactionP95 / 1_000_000.0);
	}

	private static FluidDomain benchmarkDomain(boolean chemistry) {
		FluidDomain domain = new FluidDomain(10, 7, 10, STATIC_CONFIG, 4_096);
		for (int z = 1; z <= 8; z++) for (int y = 1; y <= 4; y++) for (int x = 1; x <= 8; x++) {
			domain.setVolume(x, y, z, 1.0F);
		}
		domain.setMarkerMaterial(0, MATERIALS, 0);
		if (chemistry) domain.bindChemistry(CHEMISTRY);
		return domain;
	}

	private static long timedSteps(FluidDomain domain, int count) {
		long start = System.nanoTime();
		for (int step = 0; step < count; step++) {
			FluidDomain.StepStats stats = domain.step();
			check(stats.chemistryDirtyCells() == 0, "clean-step benchmark became chemically dirty");
		}
		return System.nanoTime() - start;
	}

	private static long benchmarkAcidBaseReactionP95() {
		final int markers = 64, cells = 64;
		ChemicalParcelStore parcels = new ChemicalParcelStore(
				CHEMISTRY, SolverUnitScale.forCellSize(1.0F), markers, cells);
		float[] x = new float[markers], y = new float[markers], z = new float[markers];
		double[] mass = new double[markers];
		int[] material = new int[markers];
		ChemicalMixtureRecord acid = ChemicalMixtureRecord.fromStableAmounts(CHEMISTRY,
				Map.of(BuiltinChemicalDefinitions.HCL, 1_000_003L), 0.0);
		ChemicalMixtureRecord base = ChemicalMixtureRecord.fromStableAmounts(CHEMISTRY,
				Map.of(BuiltinChemicalDefinitions.NAOH, 1_000_003L), 0.0);
		Arrays.fill(x, 1.5F); Arrays.fill(y, 1.5F); Arrays.fill(z, 1.5F);
		long[] samples = new long[100];
		for (int iteration = -20; iteration < samples.length; iteration++) {
			for (int marker = 0; marker < markers; marker++) {
				ChemicalMixtureRecord input = (marker & 1) == 0 ? acid : base;
				parcels.assign(marker, input, false);
				mass[marker] = input.massKilograms(CHEMISTRY);
			}
			parcels.markDirtyCell((1 * 4 + 1) * 4 + 1);
			long start = System.nanoTime();
			ChemicalParcelStore.ReactionStats stats = parcels.reactDirtyCells(4, 4, 4, 1.0F,
					x, y, z, mass, material, ignored -> BuiltinFluidMaterials.WATER.id(), markers);
			long elapsed = System.nanoTime() - start;
			check(stats.dirtyCells() == 1 && stats.changedMarkers() == markers,
					"acid/base performance fixture did not execute its logical reaction");
			if (iteration >= 0) samples[iteration] = elapsed;
		}
		return percentile(samples, 0.95);
	}

	private static long percentile(long[] values, double fraction) {
		long[] sorted = values.clone();
		Arrays.sort(sorted);
		int index = Math.max(0, Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * fraction) - 1));
		return sorted[index];
	}

	private static Map<String, Long> contentsSpecies(ChemicalContents... contents) {
		java.util.TreeMap<String, Long> totals = new java.util.TreeMap<>();
		for (ChemicalContents value : contents) for (ChemicalContents.Group group : value.groups()) {
			for (ChemicalContents.SpeciesAmount species : group.species()) {
				totals.merge(species.speciesId(), species.amountQuanta(), Math::addExact);
			}
		}
		return Map.copyOf(totals);
	}

	private static Map<String, Long> mergedContentsSpecies(ChemicalContents first, ChemicalContents second) {
		return contentsSpecies(first, second);
	}

	private static FluidDomain domainWithCell(int x, int y, int z, float volume) {
		FluidDomain domain = new FluidDomain(4, 4, 4, STATIC_CONFIG, 64);
		domain.setVolume(x, y, z, volume);
		domain.setMarkerMaterial(0, MATERIALS, 0);
		domain.bindChemistry(CHEMISTRY);
		return domain;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
