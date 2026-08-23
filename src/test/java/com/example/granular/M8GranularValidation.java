package com.example.granular;

import com.example.chemistry.BuiltinChemicalDefinitions;
import com.example.fluid.BuiltinFluidMaterials;
import com.example.fluid.FlipFluidDomain;
import com.example.fluid.FluidMaterialRegistry;
import com.example.fluid.FluidSolverConfig;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** M8 granular definition and exact inventory gate. Mechanics are added to this selector incrementally. */
public final class M8GranularValidation {
	private M8GranularValidation() { }

	public static void main(String[] args) throws Exception {
		boolean fast = Arrays.asList(args).contains("--fast");
		registryIsCanonicalAndBounded();
		registryDefinitionsAndWorldSnapshotAreStrict();
		solidCompositionStateIsIndependentAndPersistent();
		phaseTransferPrecipitatesAndDissolves();
		exactDryContainerTransferIsAtomic();
		solidVolumeConversionsAreConservative();
		parcelSplitMergePreservesInventory();
		depositCapacityAndGroupBudgetAreAtomic();
		parcelDepositCyclesDoNotDrift(fast ? 1_000 : 10_000);
		mechanicsMatchesTerminalVelocity();
		twoWayDragPreservesMomentum();
		couplingOccupancyResumsExactly();
		porousBedPreservesHydrostatics();
		settlingRelaxationSleepAndWakeAreLocal();
		solverCouplingPreservesInventories(fast ? 8 : 80);
		emptyGranularGateHasBoundedWallTime(fast ? 8 : 24);
		System.out.println("M8 granular core gate passed: exact inventory, coupling, repose, sleep and local wake");
	}

	private static void exactDryContainerTransferIsAtomic() {
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F,
				100, 1.0e-5F, 0.45F, 2.0e-3F, 0.8F);
		GranularRegistry registry = BuiltinGranularDefinitions.createRegistry();
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(BuiltinFluidMaterials.M3_PALETTE);
		FlipFluidDomain domain = new FlipFluidDomain(6, 6, 6, config, 6 * 6 * 6 * 8);
		domain.bindGranular(registry, liquids); domain.bindChemistry(BuiltinChemicalDefinitions.REGISTRY);
		long reactiveSvq = 4_096L;
		SolidCompositionRecord reactive = SolidCompositionRecord.fromStableAmounts(
				BuiltinChemicalDefinitions.REGISTRY, java.util.Map.of(
						BuiltinChemicalDefinitions.CL_MINUS, reactiveSvq * 64L,
						BuiltinChemicalDefinitions.NA_PLUS, reactiveSvq * 64L), 0.0);
		GranularTransferLedger input = new GranularTransferLedger(List.of(
				new GranularTransferLedger.Group(BuiltinGranularDefinitions.FINE_SAND,
						SolidVolume.SVQ_PER_CELL + 65_536L, 2_600.25, null),
				new GranularTransferLedger.Group(BuiltinGranularDefinitions.PRECIPITATE, reactiveSvq,
						reactive.totalMass(BuiltinChemicalDefinitions.REGISTRY), reactive)));
		FlipFluidDomain.GranularInsertion inserted = domain.tryInsertExactGranular(2, 2, 2, input, 3, true);
		require(inserted.inserted() && domain.totalGranularSolidVolumeSvq() == input.totalSolidVolumeSvq()
				&& rawClose(domain.totalGranularMass(), input.totalMass()),
				"dry granular insertion did not preserve exact volume/mass across nearby cells");
		GranularDisplaySnapshot display = domain.copyGranularDisplaySnapshot();
		require(display.registryFingerprint().equals(registry.fingerprint())
				&& display.materials().size() == registry.materials().size()
				&& !display.activeParcels().isEmpty()
				&& display.activeParcels().size() <= GranularLimits.MAX_DISPLAY_PARCELS_PER_REGION
				&& display.depositBulkFill().length == 6 * 6 * 6,
				"bounded granular display snapshot lost its identity, geometry or active sample");
		FlipFluidDomain.GranularExtraction extracted = domain.tryExtractExactGranular(
				2, 2, 2, SolidVolume.SVQ_PER_BV, 3, true);
		require(extracted.transferred() && extracted.ledger().totalSolidVolumeSvq() == input.totalSolidVolumeSvq()
				&& rawClose(extracted.ledger().totalMass(), input.totalMass())
				&& domain.totalGranularSolidVolumeSvq() == 0L,
				"dry granular extraction did not close the active inventory");
		GranularTransferLedger.Group extractedReactive = extracted.ledger().groups().stream()
				.filter(group -> group.materialId().equals(BuiltinGranularDefinitions.PRECIPITATE)).findFirst().orElseThrow();
		require(extractedReactive.composition().equals(reactive),
				"dry granular extraction changed reactive solid composition");
		require(domain.granularExternalSource().entries().size() == 2
				&& domain.granularEscapedSink().entries().size() == 2
				&& domain.granularExternalSource().entries().stream().mapToLong(GranularAuditLedger.Entry::solidVolumeSvq).sum()
				== input.totalSolidVolumeSvq(), "dry granular boundary audit did not close source/sink SVQ");

		FlipFluidDomain sealed = new FlipFluidDomain(3, 3, 3, config, 3 * 3 * 3 * 8);
		for (int z = 0; z < 3; z++) for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) sealed.setVolume(x, y, z, 1.0F);
		sealed.bindGranular(registry, liquids); sealed.bindChemistry(BuiltinChemicalDefinitions.REGISTRY);
		GranularTransferLedger small = new GranularTransferLedger(List.of(new GranularTransferLedger.Group(
				BuiltinGranularDefinitions.FINE_SAND, 1_024L, 1.0, null)));
		require(!sealed.tryInsertExactGranular(1, 1, 1, small, 1).inserted()
				&& sealed.totalGranularSolidVolumeSvq() == 0L,
				"sealed dry granular insertion was not rejected atomically");
	}

	private static void phaseTransferPrecipitatesAndDissolves() {
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F,
				100, 1.0e-5F, 0.45F, 2.0e-3F, 0.8F);
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(BuiltinFluidMaterials.M3_PALETTE);
		GranularRegistry granular = BuiltinGranularDefinitions.createRegistry();
		FlipFluidDomain domain = new FlipFluidDomain(3, 3, 3, config, 3 * 3 * 3 * 8);
		// Real transported/reseeded marker volumes are arbitrary IEEE-754 dyadics, not necessarily
		// integer SVQ. Exercise the production bridge with such a tail instead of an aligned full cell.
		domain.setVolume(1, 1, 1, 0.1F);
		domain.bindGranular(granular, liquids);
		domain.bindChemistry(BuiltinChemicalDefinitions.REGISTRY);
		domain.setPrecipitationEnabled(true);
		domain.setMarkerChemical(0, java.util.Map.of(
				BuiltinChemicalDefinitions.NA_PLUS, 8_320_000L,
				BuiltinChemicalDefinitions.CL_MINUS, 8_320_000L), true);
		long[] combinedBefore = combinedSpecies(domain);
		double massBefore = domain.totalMass() + domain.totalGranularMass();
		double occupiedBefore = domain.totalVolumeExact()
				+ SolidVolume.toCellVolume(domain.totalGranularSolidVolumeSvq());
		domain.step();
		require(domain.lastPhaseTransferStats().precipitations() == 1
				&& domain.lastPhaseTransferStats().signedSolidSvq() == 4_096L,
				"supersaturated aqueous inventory did not precipitate to the bounded target");
		require(java.util.Arrays.equals(combinedBefore, combinedSpecies(domain)),
				"precipitation changed combined aqueous+solid species");
		require(rawClose(massBefore, domain.totalMass() + domain.totalGranularMass()),
				"precipitation changed combined liquid+solid mass");
		require(rawClose(occupiedBefore, domain.totalVolumeExact()
				+ SolidVolume.toCellVolume(domain.totalGranularSolidVolumeSvq())),
				"precipitation did not exchange liquid and solid volume exactly");

		for (int marker = 0; marker < domain.particleCount(); marker++) {
			domain.setMarkerChemical(marker, java.util.Map.of(BuiltinChemicalDefinitions.H2O, 1_000_000L),
					(marker & 1) == 0);
		}
		long[] beforeDissolution = combinedSpecies(domain);
		double massBeforeDissolution = domain.totalMass() + domain.totalGranularMass();
		double occupiedBeforeDissolution = domain.totalVolumeExact()
				+ SolidVolume.toCellVolume(domain.totalGranularSolidVolumeSvq());
		domain.step();
		require(domain.lastPhaseTransferStats().dissolutions() == 1
				&& domain.totalGranularSolidVolumeSvq() == 0L,
				"undersaturated liquid did not dissolve the accessible precipitate");
		require(java.util.Arrays.equals(beforeDissolution, combinedSpecies(domain)),
				"dissolution changed combined aqueous+solid species");
		require(rawClose(massBeforeDissolution, domain.totalMass() + domain.totalGranularMass()),
				"dissolution changed combined liquid+solid mass");
		require(rawClose(occupiedBeforeDissolution, domain.totalVolumeExact()
				+ SolidVolume.toCellVolume(domain.totalGranularSolidVolumeSvq())),
				"dissolution did not exchange liquid and solid volume exactly");
		boolean indicated = false;
		for (int marker = 0; marker < domain.particleCount(); marker++) indicated |= domain.chemicalParcels().indicator(marker);
		require(indicated, "dissolution erased carrier-marker indicator provenance");
	}

	private static long[] combinedSpecies(FlipFluidDomain domain) {
		long[] result = domain.chemicalParcels().speciesTotals(domain.particleCount());
		if (domain.totalGranularSolidVolumeSvq() == 0L) return result;
		long[] solid = domain.solidCompositions().speciesTotals();
		for (int species = 0; species < result.length; species++) result[species] = Math.addExact(result[species], solid[species]);
		return result;
	}

	private static void solidCompositionStateIsIndependentAndPersistent() {
		GranularRegistry registry = BuiltinGranularDefinitions.createRegistry();
		int precipitate = registry.materialHandle(BuiltinGranularDefinitions.PRECIPITATE);
		SolidCompositionPool compositions = new SolidCompositionPool(BuiltinChemicalDefinitions.REGISTRY, 4);
		SolidCompositionRecord activeRecord = SolidCompositionRecord.fromStableAmounts(
				BuiltinChemicalDefinitions.REGISTRY, java.util.Map.of(
						BuiltinChemicalDefinitions.NA_PLUS, 640L,
						BuiltinChemicalDefinitions.CL_MINUS, 640L), 0.0);
		int activeComposition = compositions.create(activeRecord);
		GranularParcelStore parcels = new GranularParcelStore(registry, 4);
		parcels.add(new GranularParcelStore.Parcel(0.5F, 0.5F, 0.5F, 0.0F, 0.0F, 0.0F,
				10L, activeRecord.totalMass(BuiltinChemicalDefinitions.REGISTRY), precipitate, activeComposition,
				0, (byte) 0));
		SolidCompositionRecord depositRecord = SolidCompositionRecord.fromStableAmounts(
				BuiltinChemicalDefinitions.REGISTRY, java.util.Map.of(
						BuiltinChemicalDefinitions.NA_PLUS, 1_280L,
						BuiltinChemicalDefinitions.CL_MINUS, 1_280L), 0.0);
		int depositComposition = compositions.create(depositRecord);
		GranularDepositStore deposits = new GranularDepositStore(registry, 8, 4);
		require(deposits.tryAdd(0, new GranularDepositStore.SolidPortion(precipitate, depositComposition, 20L,
				depositRecord.totalMass(BuiltinChemicalDefinitions.REGISTRY)), SolidVolume.SVQ_PER_CELL),
				"reactive deposit fixture was rejected");
		GranularStateSnapshot snapshot = GranularStateSnapshot.capture(2, 2, 2, registry, parcels, deposits, compositions);
		snapshot.validateSolidCompositionMasses(BuiltinChemicalDefinitions.REGISTRY);
		require(snapshot.solidSpeciesTotals().get(BuiltinChemicalDefinitions.NA_PLUS) == 1_920L
				&& snapshot.solidSpeciesTotals().get(BuiltinChemicalDefinitions.CL_MINUS) == 1_920L,
				"solid species totals did not close");

		GranularParcelStore restoredParcels = new GranularParcelStore(registry, 4);
		GranularDepositStore restoredDeposits = new GranularDepositStore(registry, 8, 4);
		SolidCompositionPool restoredCompositions = new SolidCompositionPool(BuiltinChemicalDefinitions.REGISTRY, 4);
		snapshot.restoreInto(registry, restoredParcels, restoredDeposits, restoredCompositions,
				ignored -> SolidVolume.SVQ_PER_CELL);
		GranularStateSnapshot restored = GranularStateSnapshot.capture(
				2, 2, 2, registry, restoredParcels, restoredDeposits, restoredCompositions);
		require(restored.solidSpeciesTotals().equals(snapshot.solidSpeciesTotals())
				&& restored.totalSolidVolumeSvq() == snapshot.totalSolidVolumeSvq()
				&& restored.totalMassBits() == snapshot.totalMassBits(),
				"solid composition snapshot round-trip changed inventory");

		SolidCompositionPool mergeCompositions = new SolidCompositionPool(BuiltinChemicalDefinitions.REGISTRY, 4);
		GranularParcelStore mergeParcels = new GranularParcelStore(registry, 4);
		GranularDepositStore mergeDeposits = new GranularDepositStore(registry, 1, 4);
		GranularInventory mergeInventory = new GranularInventory(mergeParcels, mergeDeposits);
		mergeInventory.bindSolidCompositions(mergeCompositions);
		for (int parcel = 0; parcel < 2; parcel++) {
			int composition = mergeCompositions.create(activeRecord);
			mergeParcels.add(new GranularParcelStore.Parcel(0.25F, 0.25F, 0.25F, 0.0F, 0.0F, 0.0F,
					10L, activeRecord.totalMass(BuiltinChemicalDefinitions.REGISTRY), precipitate, composition,
					GranularDepositDynamics.SETTLE_CONTACT_STEPS, GranularParcelStore.FLAG_SUPPORTED));
			require(mergeInventory.depositParcel(0, 0, SolidVolume.SVQ_PER_CELL),
					"compatible reactive parcel did not enter its deposit group");
		}
		GranularStateSnapshot merged = GranularStateSnapshot.capture(
				1, 1, 1, registry, mergeParcels, mergeDeposits, mergeCompositions);
		require(mergeDeposits.groupCount() == 1 && mergeCompositions.liveCount() == 1
				&& merged.depositGroups().size() == 1
				&& merged.solidSpeciesTotals().get(BuiltinChemicalDefinitions.NA_PLUS) == 1_280L,
				"proportional reactive deposits did not merge into one persistent canonical group");
	}

	private static void registryDefinitionsAndWorldSnapshotAreStrict() throws Exception {
		String materialJson = "{\"reference_density\":\"2600.0\",\"primary_grain_diameter_meters\":\"0.0005\","
				+ "\"rest_porosity_q\":\"380000\",\"repose_angle_degrees\":\"32.0\",\"static_friction\":\"0.6\","
				+ "\"dynamic_friction\":\"0.45\",\"restitution\":\"0.1\",\"critical_shields\":\"0.05\","
				+ "\"erosion_rate_svq_per_second\":\"4194304\",\"maximum_active_parcel_svq\":\"262144\","
				+ "\"base_argb\":\"#ffc2a065\",\"display_texture\":\"realwater:fine_sand\",\"reactive\":false}";
		ParticulateMaterial decoded = GranularDefinitionCodec.decodeMaterial(
				BuiltinGranularDefinitions.FINE_SAND, new StringReader(materialJson));
		require(decoded.equals(BuiltinGranularDefinitions.createRegistry().material(
				BuiltinGranularDefinitions.createRegistry().materialHandle(BuiltinGranularDefinitions.FINE_SAND))),
				"granular material JSON codec changed the frozen definition");
		expectFailure(() -> GranularDefinitionCodec.decodeMaterial("test:bad",
				new StringReader(materialJson.replace("\"2600.0\"", "2600.0"))),
				"JSON number bypassed granular decimal-string codec");

		var rootUrl = M8GranularValidation.class.getClassLoader().getResource("data/realwater/realwater");
		require(rootUrl != null && rootUrl.getProtocol().equals("file"), "bundled granular resource root is missing");
		Path resourceRoot = Path.of(rootUrl.toURI());
		GranularRegistry bundled = new GranularRegistry(
				decodeDirectory(resourceRoot.resolve("particulate_materials"), GranularDefinitionCodec::decodeMaterial),
				decodeDirectory(resourceRoot.resolve("granular_carriers"), GranularDefinitionCodec::decodeCarrier),
				decodeDirectory(resourceRoot.resolve("solid_liquid_reactions"), GranularDefinitionCodec::decodeReaction),
				BuiltinChemicalDefinitions.REGISTRY);
		GranularRegistry frozen = BuiltinGranularDefinitions.createRegistry();
		require(bundled.fingerprint().equals(frozen.fingerprint()),
				"bundled granular datapack definitions differ from the frozen fixture registry");

		Path world = Files.createTempDirectory("realwater-granular-registry-");
		try {
			GranularRegistryStore store = new GranularRegistryStore(world, BuiltinChemicalDefinitions.REGISTRY);
			GranularRegistryStore.LoadResult first = store.loadOrCreate(bundled);
			require(first.created() && !first.definitionMismatch(), "first granular registry snapshot was not published");
			List<ParticulateMaterial> changedMaterials = new ArrayList<>(bundled.materials());
			ParticulateMaterial original = changedMaterials.getFirst();
			changedMaterials.set(0, new ParticulateMaterial(original.id(), original.referenceDensity(),
					original.primaryGrainDiameterMeters(), original.restPorosityQ(), original.reposeAngleRadians(),
					original.staticFriction(), original.dynamicFriction(), original.restitution(), original.criticalShields(),
					original.erosionRateSvqPerSecond(), original.maximumActiveParcelSvq(), original.baseArgb() ^ 0x00010101,
					original.displayTextureId(), original.reactive()));
			GranularRegistryStore.LoadResult mismatch = store.loadOrCreate(
					new GranularRegistry(changedMaterials, bundled.carriers(), bundled.reactions(),
							BuiltinChemicalDefinitions.REGISTRY));
			require(mismatch.definitionMismatch() && mismatch.registry().fingerprint().equals(bundled.fingerprint()),
					"changed granular definitions reinterpreted the authoritative world snapshot");
			Files.writeString(world.resolve("realwater").resolve("granular").resolve("foreign.bin"), "foreign");
			try {
				store.read();
				throw new AssertionError("unknown granular registry content was ignored");
			} catch (java.io.IOException expected) { }
		} finally {
			try (var paths = Files.walk(world)) {
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
			}
		}
	}

	private static <T> List<T> decodeDirectory(Path directory, FileDecoder<T> decoder) throws Exception {
		ArrayList<T> result = new ArrayList<>();
		try (var files = Files.walk(directory)) {
			for (Path file : files.filter(path -> path.toString().endsWith(".json")).sorted().toList()) {
				String relative = directory.relativize(file).toString().replace('\\', '/');
				String id = "realwater:" + relative.substring(0, relative.length() - 5);
				try (var reader = Files.newBufferedReader(file)) { result.add(decoder.decode(id, reader)); }
			}
		}
		return List.copyOf(result);
	}

	@FunctionalInterface
	private interface FileDecoder<T> { T decode(String stableId, java.io.Reader reader); }

	private static void registryIsCanonicalAndBounded() {
		GranularRegistry builtins = BuiltinGranularDefinitions.createRegistry();
		GranularRegistry reordered = new GranularRegistry(
				List.of(builtins.materials().get(2), builtins.materials().get(0), builtins.materials().get(1)),
				List.of(builtins.carriers().get(1), builtins.carriers().get(2), builtins.carriers().get(0)),
				builtins.reactions(), BuiltinChemicalDefinitions.REGISTRY);
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

	private static void porousBedPreservesHydrostatics() {
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F,
				200, 1.0e-6F, 0.45F, 2.0e-3F, 0.8F);
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(BuiltinFluidMaterials.M3_PALETTE);
		GranularRegistry registry = BuiltinGranularDefinitions.createRegistry();
		int sizeX = 2, sizeY = 5, sizeZ = 2;
		FlipFluidDomain domain = new FlipFluidDomain(sizeX, sizeY, sizeZ, config, sizeX * sizeY * sizeZ * 8);
		for (int z = 0; z < sizeZ; z++) for (int x = 0; x < sizeX; x++) {
			domain.setVolume(x, 0, z, 0.75F);
			domain.setVolume(x, 1, z, 1.0F);
			domain.setVolume(x, 2, z, 0.75F);
		}
		int sand = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long cellSvq = SolidVolume.SVQ_PER_CELL / 4L;
		double cellMass = SolidVolume.toCellVolume(cellSvq) * registry.material(sand).referenceDensity();
		ArrayList<GranularStateSnapshot.DepositGroup> groups = new ArrayList<>();
		for (int z = 0; z < sizeZ; z++) for (int x = 0; x < sizeX; x++) groups.add(
				new GranularStateSnapshot.DepositGroup(cellIndex(x, 0, z, sizeX, sizeY),
						registry.material(sand).restPorosityQ(), sand, 0, cellSvq, cellMass));
		long totalSvq = Math.multiplyExact(cellSvq, groups.size());
		double totalMass = cellMass * groups.size();
		domain.restoreGranularState(new GranularStateSnapshot(sizeX, sizeY, sizeZ, registry.fingerprint(),
				registry.materials().stream().map(ParticulateMaterial::id).toList(), List.of(), groups,
				totalSvq, Double.doubleToRawLongBits(totalMass)), registry, liquids);

		FlipFluidDomain.StepStats stats = null;
		for (int step = 0; step < 200; step++) stats = domain.step();
		float[] actualPressure = {domain.pressure(0, 0, 0), domain.pressure(0, 1, 0), domain.pressure(0, 2, 0)};
		float expectedGradient = config.density() * config.gravity() * config.cellSize();
		for (int y = 0; y < 2; y++) {
			float actualGradient = actualPressure[y] - actualPressure[y + 1];
			float relativeError = Math.abs(actualGradient - expectedGradient) / expectedGradient;
			require(relativeError < 3.0e-4F, "porous granular bed missed analytic hydrostatic gradient: y="
					+ y + ", pressure=" + Arrays.toString(actualPressure) + ", expectedGradient="
					+ expectedGradient + ", speed=" + domain.maximumParticleSpeed() + ", error=" + relativeError);
		}
		require(stats.pressureIterations() > 0 && Float.isFinite(stats.pressureResidual())
				&& domain.maximumParticleSpeed() < 3.0e-4F,
				"porous granular bed left a non-hydrostatic liquid state");
		require(domain.totalGranularSolidVolumeSvq() == totalSvq && rawClose(domain.totalGranularMass(), totalMass),
				"porous hydrostatic projection changed granular truth");
	}

	private static void settlingRelaxationSleepAndWakeAreLocal() {
		GranularRegistry registry = BuiltinGranularDefinitions.createRegistry();
		int sand = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		int sizeX = 5, sizeY = 2, sizeZ = 3, cellCount = sizeX * sizeY * sizeZ;
		GranularParcelStore parcels = new GranularParcelStore(registry, 8);
		GranularDepositStore deposits = new GranularDepositStore(registry, cellCount, 32);
		GranularInventory inventory = new GranularInventory(parcels, deposits);
		GranularDepositDynamics dynamics = new GranularDepositDynamics(
				registry, parcels, deposits, inventory, sizeX, sizeY, sizeZ, 0.5F);
		long initialSvq = SolidVolume.SVQ_PER_CELL * 3L / 5L;
		double initialMass = SolidVolume.toCellVolume(initialSvq) * registry.material(sand).referenceDensity();
		long parcelSvq = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		double parcelMass = initialMass * parcelSvq / initialSvq;
		parcels.add(new GranularParcelStore.Parcel(0.75F, 0.05F, 0.75F, 0.0F, 0.0F, 0.0F,
				parcelSvq, parcelMass, sand, 0, GranularDepositDynamics.SETTLE_CONTACT_STEPS,
				GranularParcelStore.FLAG_SUPPORTED));
		TestDepositEnvironment calm = new TestDepositEnvironment(sizeX, sizeY, sizeZ, 0.0);
		GranularDepositDynamics.Stats settled = dynamics.step(calm, 0.05F);
		require(settled.settledParcels() == 1 && parcels.count() == 0, "supported granular parcel did not settle");
		int centerCell = cellIndex(1, 0, 1, sizeX, sizeY);
		require(deposits.tryAdd(centerCell, new GranularDepositStore.SolidPortion(
				sand, 0, initialSvq - parcelSvq, initialMass - parcelMass), SolidVolume.SVQ_PER_CELL),
				"repose fixture deposit was rejected");
		dynamics.disturbCell(centerCell);
		require(inventory.totalSolidVolumeSvq() == initialSvq && rawClose(inventory.totalMass(), initialMass),
				"settling changed exact granular inventory");

		for (int step = 0; step < 128 && dynamics.dirtyColumnCount() > 0; step++) dynamics.step(calm, 0.05F);
		require(dynamics.dirtyColumnCount() == 0, "stable granular surface did not leave the dirty queue");
		require(dynamics.sleepingColumnCount() * 5 >= dynamics.occupiedColumnCount() * 4,
				"less than 80% of a stable deposit reached sleep");
		assertReposeBound(deposits, registry.material(sand).reposeAngleRadians(), sizeX, sizeY, sizeZ);

		int remoteCell = cellIndex(4, 0, 1, sizeX, sizeY);
		long remoteSvq = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		double remoteMass = SolidVolume.toCellVolume(remoteSvq) * registry.material(sand).referenceDensity();
		require(deposits.tryAdd(remoteCell, new GranularDepositStore.SolidPortion(sand, 0, remoteSvq, remoteMass),
				SolidVolume.SVQ_PER_CELL), "remote wake fixture deposit was rejected");
		long beforeSubcritical = inventory.totalSolidVolumeSvq();
		double massBeforeWake = inventory.totalMass();
		long remoteBefore = deposits.cellSolidVolumeSvq(remoteCell);
		dynamics.disturbCell(centerCell);
		GranularDepositDynamics.Stats quiet = dynamics.step(calm, 0.05F);
		require(quiet.wokenParcels() == 0 && parcels.count() == 0
				&& inventory.totalSolidVolumeSvq() == beforeSubcritical, "subcritical flow caused granular creep");

		TestDepositEnvironment eroding = new TestDepositEnvironment(sizeX, sizeY, sizeZ, 1.0);
		GranularDepositDynamics.Stats wake = dynamics.step(eroding, 0.05F);
		require(wake.wokenParcels() > 0 && parcels.count() == wake.wokenParcels(),
				"supercritical exposed deposit did not wake locally");
		require(deposits.cellSolidVolumeSvq(remoteCell) == remoteBefore,
				"local shear wake touched a sleeping remote deposit");
		require(inventory.totalSolidVolumeSvq() == beforeSubcritical && rawClose(inventory.totalMass(), massBeforeWake),
				"local granular wake changed exact inventory");
	}

	private static void assertReposeBound(GranularDepositStore deposits, double repose,
	                                      int sizeX, int sizeY, int sizeZ) {
		long[] bulk = new long[sizeX * sizeZ];
		for (int z = 0; z < sizeZ; z++) for (int x = 0; x < sizeX; x++) {
			for (int y = 0; y < sizeY; y++) {
				bulk[z * sizeX + x] += deposits.cellBulkVolumeSvq(cellIndex(x, y, z, sizeX, sizeY));
			}
		}
		for (int z = 0; z < sizeZ; z++) for (int x = 0; x < sizeX; x++) {
			for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
				if (dx == 0 && dz == 0 || x + dx < 0 || x + dx >= sizeX || z + dz < 0 || z + dz >= sizeZ) continue;
				double difference = Math.abs((double) bulk[z * sizeX + x] - bulk[(z + dz) * sizeX + x + dx])
						/ SolidVolume.SVQ_PER_CELL;
				double limit = Math.hypot(dx, dz) * Math.tan(repose) + 2.0e-6;
				require(difference <= limit, "granular deposit exceeded its eight-neighbor repose angle");
			}
		}
	}

	private static int cellIndex(int x, int y, int z, int sizeX, int sizeY) {
		return (z * sizeY + y) * sizeX + x;
	}

	private static final class TestDepositEnvironment implements GranularDepositDynamics.Environment {
		private final int sizeX, sizeY, sizeZ;
		private final double shields;

		private TestDepositEnvironment(int sizeX, int sizeY, int sizeZ, double shields) {
			this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ; this.shields = shields;
		}

		@Override public long geometricOpenSvq(int cell) { return SolidVolume.SVQ_PER_CELL; }
		@Override public boolean horizontalPathOpen(int fromCell, int directionX, int directionZ) {
			int x = fromCell % sizeX, z = fromCell / (sizeX * sizeY);
			return x + directionX >= 0 && x + directionX < sizeX
					&& z + directionZ >= 0 && z + directionZ < sizeZ;
		}
		@Override public double shields(int surfaceCell, ParticulateMaterial material) { return shields; }
		@Override public GranularDepositDynamics.Velocity wakeVelocity(int surfaceCell) {
			return new GranularDepositDynamics.Velocity(0.2F, 0.0F, 0.0F);
		}
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

	private static void emptyGranularGateHasBoundedWallTime(int samples) {
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F,
				100, 1.0e-5F, 0.45F, 2.0e-3F, 0.8F);
		int sizeX = 24, sizeY = 16, sizeZ = 24, capacity = sizeX * sizeY * sizeZ * 8;
		FlipFluidDomain baseline = new FlipFluidDomain(sizeX, sizeY, sizeZ, config, capacity);
		FlipFluidDomain granular = new FlipFluidDomain(sizeX, sizeY, sizeZ, config, capacity);
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < 2; y++) for (int x = 0; x < sizeX; x++) {
			baseline.setVolume(x, y, z, 1.0F); granular.setVolume(x, y, z, 1.0F);
		}
		granular.bindGranular(BuiltinGranularDefinitions.createRegistry(),
				new FluidMaterialRegistry(BuiltinFluidMaterials.M3_PALETTE));
		for (int warmup = 0; warmup < 8; warmup++) { baseline.step(); granular.step(); }
		long[] baselineNanos = new long[samples], granularNanos = new long[samples];
		for (int sample = 0; sample < samples; sample++) {
			long start;
			if ((sample & 1) == 0) {
				start = System.nanoTime(); baseline.step(); baselineNanos[sample] = System.nanoTime() - start;
				start = System.nanoTime(); granular.step(); granularNanos[sample] = System.nanoTime() - start;
			} else {
				start = System.nanoTime(); granular.step(); granularNanos[sample] = System.nanoTime() - start;
				start = System.nanoTime(); baseline.step(); baselineNanos[sample] = System.nanoTime() - start;
			}
		}
		Arrays.sort(baselineNanos); Arrays.sort(granularNanos);
		long baselineMedian = baselineNanos[samples / 2], granularMedian = granularNanos[samples / 2];
		double overhead = baselineMedian == 0L ? Double.POSITIVE_INFINITY
				: (granularMedian - baselineMedian) * 100.0 / baselineMedian;
		System.out.printf("  empty 24x16x24 gate wall-time baseline=%.3f ms granular=%.3f ms overhead=%+.1f%%%n",
				baselineMedian / 1.0e6, granularMedian / 1.0e6, overhead);
		require(Double.doubleToRawLongBits(baseline.totalVolumeExact())
				== Double.doubleToRawLongBits(granular.totalVolumeExact())
				&& Float.floatToRawIntBits(baseline.maximumParticleSpeed())
				== Float.floatToRawIntBits(granular.maximumParticleSpeed()),
				"empty granular inventory changed the baseline liquid result");
		long relativeOrTimerBound = Math.max((long) Math.ceil(baselineMedian * 1.05), baselineMedian + 500_000L);
		require(granularMedian <= relativeOrTimerBound && granularMedian <= 40_000_000L,
				"empty granular gate exceeded its 5%/0.5 ms noise envelope or the existing 40 ms pacing boundary");
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
