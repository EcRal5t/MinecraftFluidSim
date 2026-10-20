package mmm.fluid;

import mmm.thermal.ThermalDomainState;

import mmm.thermal.ThermalCarrierContext;

import mmm.chemistry.ChemicalLimits;
import mmm.chemistry.BuiltinChemicalDefinitions;
import mmm.chemistry.ChemicalMixtureRecord;
import mmm.chemistry.ChemicalPhaseDefinition;
import mmm.chemistry.ChemicalReaction;
import mmm.chemistry.ChemicalRegistry;
import mmm.chemistry.ChemicalSpecies;
import mmm.chemistry.ExactRational;
import mmm.granular.BuiltinGranularDefinitions;
import mmm.granular.GranularRegistry;
import mmm.units.SignedDyadic;
import mmm.units.SolverUnitScale;
import mmm.thermal.ThermalBoundaryDefinition;
import mmm.thermal.ThermalDefinitionCodec;
import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalLimits;
import mmm.thermal.ThermalRegistry;
import mmm.thermal.ThermalRegistryCodec;
import mmm.thermal.ThermalSpeciesOffset;
import mmm.thermal.ThermalStateFunction;

import java.io.StringReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** One retained selector for M9 definition, state-function and later transport/phase invariants. */

abstract class ThermochemistryValidationScenarios extends ThermalValidationSupport {
	static void definitionAndCanonicalRegistry() {
		ThermalMaterialDefinition first = material("mmm:thermal/test", "mmm:test", 100.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 300.0, 2.0),
						new ThermalMaterialDefinition.CaloricSegment(300.0, 500.0, 4.0)));
		ThermalMaterialDefinition second = material("mmm:thermal/other", "mmm:other", 0.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 500.0, 3.0)));
		ThermalRegistry forward = new ThermalRegistry(List.of(first, second));
		ThermalRegistry reverse = new ThermalRegistry(List.of(second, first));
		require(forward.fingerprint().equals(reverse.fingerprint()), "thermal registry depends on definition order");
		require(java.util.Arrays.equals(forward.canonicalBytes(), reverse.canonicalBytes()),
				"thermal canonical bytes depend on definition order");

		String json = """
				{
				  "carrier_kind":"LIQUID",
				  "carrier_id":"mmm:test",
				  "reference_temperature_k":"300.0",
				  "reference_specific_energy_j_per_kg":"100.0",
				  "conductivity_w_per_m_k":"0.5",
				  "caloric_segments":[
				    {"minimum_temperature_k":"200.0","maximum_temperature_k":"300.0","specific_heat_j_per_kg_k":"2.0"},
				    {"minimum_temperature_k":"300.0","maximum_temperature_k":"500.0","specific_heat_j_per_kg_k":"4.0"}
				  ]
				}
				""";
		ThermalMaterialDefinition decoded = ThermalDefinitionCodec.decodeMaterial("mmm:thermal/test",
				new StringReader(json));
		require(decoded.equals(first), "thermal material JSON did not round-trip the definition contract");
		ThermalBoundaryDefinition boundary = ThermalDefinitionCodec.decodeBoundary("mmm:boundary/test",
				new StringReader("{\"target_kind\":\"BLOCK\",\"target_id\":\"minecraft:magma_block\","
						+ "\"kind\":\"FIXED_SURFACE_TEMPERATURE\",\"surface_temperature_k\":\"500.0\"}"));
		ThermalRegistry withBoundary = new ThermalRegistry(List.of(first, second), List.of(), List.of(),
				List.of(boundary), "", "");
		require(withBoundary.boundaries().equals(List.of(boundary))
				&& !withBoundary.fingerprint().equals(forward.fingerprint()),
				"thermal boundary definition was absent from canonical registry identity");
		expectFailure(() -> new ThermalMaterialDefinition("mmm:thermal/broken",
				ThermalMaterialDefinition.CarrierKind.LIQUID, "mmm:broken", 300.0, 0.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 299.0, 1.0),
						new ThermalMaterialDefinition.CaloricSegment(300.0, 500.0, 1.0))), "non-contiguous segments accepted");
	}

	static void worldRegistrySnapshotIsAuthoritative() {
		java.nio.file.Path root = null;
		try {
			root = java.nio.file.Files.createTempDirectory("mmm-thermal-registry-");
			ThermalMaterialDefinition original = material("test:thermal/store", "test:store", 0.0,
					List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 500.0, 10.0)));
			ThermalRegistry authoritative = new ThermalRegistry(List.of(original));
			java.nio.file.Path snapshot = root.resolve("thermal-registry.nbt");
			ThermalRegistryCodec.writeSnapshot(snapshot, authoritative);
			ThermalRegistry decoded = ThermalRegistryCodec.readSnapshot(snapshot, "", "", "");
			require(decoded.fingerprint().equals(authoritative.fingerprint())
							&& java.util.Arrays.equals(decoded.canonicalBytes(), authoritative.canonicalBytes()),
					"thermal registry snapshot did not round-trip canonically");
			ThermalMaterialDefinition changed = material("test:thermal/store", "test:store", 1.0,
					List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 500.0, 10.0)));
			ThermalRegistry redefined = new ThermalRegistry(List.of(changed));
			require(!redefined.fingerprint().equals(authoritative.fingerprint()),
					"changed thermal definition must change the fingerprint");
			require(!java.util.Arrays.equals(ThermalRegistryCodec.snapshotBytes(redefined),
							ThermalRegistryCodec.snapshotBytes(authoritative)),
					"changed thermal definitions produced identical snapshot bytes");
			var registryTag = net.minecraft.nbt.NbtIo.readCompressed(snapshot,
					net.minecraft.nbt.NbtAccounter.create(8L * 1024L * 1024L));
			registryTag.putString("UnknownField", "must-lock");
			net.minecraft.nbt.NbtIo.writeCompressed(registryTag, snapshot);
			try {
				ThermalRegistryCodec.readSnapshot(snapshot, "", "", "");
				throw new AssertionError("unknown thermal registry field was ignored");
			} catch (java.io.IOException expected) { }
			registryTag.remove("UnknownField");
			net.minecraft.nbt.NbtIo.writeCompressed(registryTag, snapshot);
			ThermalRegistryCodec.writeSnapshot(snapshot, redefined);
			registryTag = net.minecraft.nbt.NbtIo.readCompressed(snapshot,
					net.minecraft.nbt.NbtAccounter.create(8L * 1024L * 1024L));
			registryTag.putString("Fingerprint", authoritative.fingerprint());
			net.minecraft.nbt.NbtIo.writeCompressed(registryTag, snapshot);
			try {
				ThermalRegistryCodec.readSnapshot(snapshot, "", "", "");
				throw new AssertionError("thermal registry whose content does not match its fingerprint was accepted");
			} catch (java.io.IOException expected) { }
		} catch (java.io.IOException failure) {
			throw new AssertionError("thermal registry codec validation failed", failure);
		} finally {
			if (root != null) try (var paths = java.nio.file.Files.walk(root)) {
				for (java.nio.file.Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
					java.nio.file.Files.deleteIfExists(path);
				}
			} catch (java.io.IOException cleanup) {
				throw new AssertionError("thermal registry codec cleanup failed", cleanup);
			}
		}
	}

	static void piecewiseStateFunctionRoundTrips() {
		ThermalMaterialDefinition definition = material("mmm:thermal/test", "mmm:test", 100.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 300.0, 2.0),
						new ThermalMaterialDefinition.CaloricSegment(300.0, 500.0, 4.0)));
		ThermalRegistry registry = new ThermalRegistry(List.of(definition));
		ThermalStateFunction state = new ThermalStateFunction(registry);
		int handle = registry.materialHandle(definition.id());
		for (double temperature : new double[]{1.0, 100.0, 200.0, 250.0, 300.0, 375.0, 500.0, 750.0}) {
			double energy = state.internalEnergyAtTemperature(handle, 2.5, Map.of(), temperature);
			double restored = state.stateForInternalEnergy(handle, 2.5, Map.of(), energy).temperatureK();
			require(close(restored, temperature), "piecewise thermal state did not round-trip at " + temperature);
		}
		double cold = state.internalEnergyAtTemperature(handle, 1.0, Map.of(), 200.0);
		require(cold < 0.0, "thermal state rejected the valid negative-energy side of its reference");
		double minimumEnergy = state.internalEnergyAtTemperature(
				handle, 1.0, Map.of(), ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K);
		expectFailure(() -> state.stateForInternalEnergy(handle, 1.0, Map.of(), minimumEnergy - 1.0),
				"thermal state accepted energy below its positive-temperature domain");
	}

	static void reactionHeatIsPathIndependentStateEnergy() {
		ThermalMaterialDefinition solution = material("mmm:thermal/solution", "mmm:water", 0.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 500.0, 1_000.0)));
		List<ThermalSpeciesOffset> offsets = List.of(
				new ThermalSpeciesOffset("mmm:thermal_offset/h", "mmm:h_plus", solution.id(), ExactRational.of(10)),
				new ThermalSpeciesOffset("mmm:thermal_offset/oh", "mmm:oh_minus", solution.id(), ExactRational.of(20)),
				new ThermalSpeciesOffset("mmm:thermal_offset/water", "mmm:h2o", solution.id(), ExactRational.of(-50)));
		ThermalRegistry registry = new ThermalRegistry(List.of(solution), offsets, List.of(), CHEMICAL_FINGERPRINT, "");
		ThermalStateFunction state = new ThermalStateFunction(registry);
		int handle = registry.materialHandle(solution.id());
		Map<String, Long> reactants = Map.of("mmm:h_plus", ChemicalLimits.QUANTA_PER_MOL,
				"mmm:oh_minus", ChemicalLimits.QUANTA_PER_MOL);
		Map<String, Long> productsA = Map.of("mmm:h2o", ChemicalLimits.QUANTA_PER_MOL);
		Map<String, Long> productsB = new java.util.TreeMap<>(productsA);
		double reactantReference = state.internalEnergyAtTemperature(handle, 1.0, reactants, 300.0);
		double productTemperatureA = state.stateForInternalEnergy(handle, 1.0, productsA, reactantReference).temperatureK();
		double productTemperatureB = state.stateForInternalEnergy(handle, 1.0, productsB, reactantReference).temperatureK();
		require(productTemperatureA > 300.0 && close(productTemperatureA, productTemperatureB),
				"formation energy did not produce path-independent exothermic temperature rise");
		validateReferenceEnergyUnits(solution);
	}

	private static void validateReferenceEnergyUnits(ThermalMaterialDefinition solution) {
		ChemicalRegistry chemistry = BuiltinChemicalDefinitions.REGISTRY;
		ThermalMaterialDefinition other = material("test:thermal/other", "test:other", -13.0,
				solution.caloricSegments());
		List<ThermalSpeciesOffset> offsets = new java.util.ArrayList<>();
		for (var host : List.of(solution, other)) {
			offsets.add(new ThermalSpeciesOffset(host.id() + "/h", "mmm:h_plus", host.id(), ExactRational.parse("17/13")));
			offsets.add(new ThermalSpeciesOffset(host.id() + "/oh", "mmm:oh_minus", host.id(), ExactRational.ZERO));
			offsets.add(new ThermalSpeciesOffset(host.id() + "/water", "mmm:h2o", host.id(),
					ExactRational.parse(host == solution ? "-59/7" : "97/11")));
		}
		ThermalRegistry registry = new ThermalRegistry(List.of(solution, other), offsets, List.of(),
				chemistry.fingerprint(), "");
		ThermalStateFunction function = new ThermalStateFunction(registry);
		var shiftedSpecies = new java.util.ArrayList<>(chemistry.species());
		shiftedSpecies.add(new ChemicalSpecies("aaa:unused", Map.of("X", 1), 0, ExactRational.ONE, 0xff000000));
		ChemicalRegistry shifted = new ChemicalRegistry(shiftedSpecies, chemistry.phases(), chemistry.reactions());
		for (int index = 0; index < 12; index++) {
			int host = index & 1;
			double mass = .125 + index / 32.0;
			Map<String, Long> amounts = new java.util.TreeMap<>();
			amounts.put("mmm:h_plus", 1_000_000L + index);
			amounts.put("mmm:oh_minus", 19L);
			amounts.put("mmm:h2o", 500_000L + index);
			ChemicalRegistry currentChemistry = index % 3 == 0 ? shifted : chemistry;
			var mixture = ChemicalMixtureRecord.fromStableAmounts(currentChemistry, amounts, 0);
			double expected = mass * registry.material(host).referenceSpecificEnergyJPerKg();
			for (var entry : amounts.entrySet()) expected += registry.speciesOffset(host, entry.getKey())
					.referenceInternalEnergyJPerMol().multiply(entry.getValue())
					.divide(ChemicalLimits.QUANTA_PER_MOL).toDouble();
			// SI coefficient rounding must stay below the 0.0001 K caloric resolution.
			double energyTolerance = mass * registry.material(host).specificHeatAt(300) * 1E-4;
			for (int order = 0; order < 2; order++) {
				require(Math.abs(function.baseEnergy(host, mass, amounts) - expected) <= energyTolerance,
						"species reference coefficients changed the caloric response or SI units");
				require(Math.abs(function.baseEnergy(host, mass, mixture, currentChemistry) - expected) <= energyTolerance,
						"packed species reference energy changed under chemical handle reordering");
				if (order == 0) amounts = ((java.util.TreeMap<String, Long>) amounts).descendingMap();
			}
		}
	}

	static void reactionCommitValidatesEnergyBeforeMutation() {
		ChemicalReactionCandidateValidation.cellInventoryTransactions();
		ChemicalReactionCandidateValidation.reactionHeatChangesTemperature();
	}

	static void granularReactionHeatUsesOneConservedCellEnergy() {
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 1.0F / 20.0F,
				100, 1.0E-5F, 2.0E-3F, 0.0F);
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE);
		GranularRegistry granular = BuiltinGranularDefinitions.createRegistry();
		FluidDomain domain = new FluidDomain(3, 3, 3, config, 3 * 3 * 3 * 8);
		domain.setVolume(1, 1, 1, 0.1F);
		domain.bindGranular(granular, liquids, BuiltinFluidMaterials.createRegistry());
		domain.bindChemistry(BuiltinChemicalDefinitions.REGISTRY);
		domain.setMarkerChemical(0, Map.of(
				BuiltinChemicalDefinitions.NA_PLUS, 1_100_000L,
				BuiltinChemicalDefinitions.CL_MINUS, 1_100_000L), true);
		domain.bindThermal(reactiveGranularThermalRegistry(liquids, granular), liquids, 300.0);
		domain.setPrecipitationEnabled(true);
		SignedDyadic beforePrecipitation = domain.exactInternalEnergy();
		domain.domainStep.step();
		double precipitationTemperature = domain.thermalTemperatureInCell(1, 1, 1);
		require(domain.lastPhaseTransferStats().precipitations() == 1
				&& precipitationTemperature > 300.0
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory())
				&& Math.abs(domain.exactInternalEnergy().subtract(beforePrecipitation).toDouble()) <= 1.0E-8,
				"solid formation energy did not emerge as conserved-cell precipitation heat: T="
						+ precipitationTemperature + " deltaU="
						+ domain.exactInternalEnergy().subtract(beforePrecipitation));

		for (int marker = 0; marker < domain.particleCount(); marker++) domain.setMarkerChemical(
				marker, Map.of(BuiltinChemicalDefinitions.H2O, 1_000_000L), (marker & 1) == 0);
		SignedDyadic beforeDissolution = domain.exactInternalEnergy();
		double temperatureBeforeDissolution = domain.thermalTemperatureInCell(1, 1, 1);
		long solidBeforeDissolution = domain.totalGranularSolidVolumeSvq();
		boolean dissolved = false;
		for (int step = 0; step < 16 && domain.totalGranularSolidVolumeSvq() > 0L; step++) {
			domain.domainStep.step(); dissolved |= domain.lastPhaseTransferStats().dissolutions() > 0;
		}
		double dissolutionTemperature = domain.thermalTemperatureInCell(1, 1, 1);
		require(dissolved && domain.totalGranularSolidVolumeSvq() == 0L
				&& dissolutionTemperature < temperatureBeforeDissolution
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory())
				&& Math.abs(domain.exactInternalEnergy().subtract(beforeDissolution).toDouble()) <= 1.0E-8,
				"dissolution did not consume sensible energy through the same state function: beforeT="
						+ temperatureBeforeDissolution + " afterT=" + dissolutionTemperature
						+ " dissolved=" + dissolved + " solidSvq=" + solidBeforeDissolution + "->"
						+ domain.totalGranularSolidVolumeSvq()
						+ " audit=" + domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory())
						+ " deltaU=" + domain.exactInternalEnergy().subtract(beforeDissolution));
	}

	static ThermalRegistry reactiveGranularThermalRegistry(
			FluidMaterialRegistry liquids, GranularRegistry granular
	) {
		java.util.ArrayList<ThermalMaterialDefinition> materials = new java.util.ArrayList<>();
		for (int handle = 0; handle < liquids.size(); handle++) materials.add(new ThermalMaterialDefinition(
				"test:thermal/reactive_liquid_" + handle, ThermalMaterialDefinition.CarrierKind.LIQUID,
				liquids.material(handle).id(), 300.0, 0.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 500.0, 100.0))));
		for (int handle = 0; handle < granular.materials().size(); handle++) materials.add(
				new ThermalMaterialDefinition("test:thermal/reactive_granular_" + handle,
						ThermalMaterialDefinition.CarrierKind.GRANULAR, granular.material(handle).id(),
						300.0, 0.0, 1.0,
						List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 500.0, 100.0))));
		java.util.ArrayList<ThermalSpeciesOffset> offsets = new java.util.ArrayList<>();
		for (int material = 0; material < materials.size(); material++) {
			ThermalMaterialDefinition host = materials.get(material);
			for (int species = 0; species < BuiltinChemicalDefinitions.REGISTRY.species().size(); species++) {
				String speciesId = BuiltinChemicalDefinitions.REGISTRY.species(species).id();
				boolean precipitate = host.carrierKind() == ThermalMaterialDefinition.CarrierKind.GRANULAR
						&& host.carrierId().equals(BuiltinGranularDefinitions.PRECIPITATE)
						&& (speciesId.equals(BuiltinChemicalDefinitions.NA_PLUS)
						|| speciesId.equals(BuiltinChemicalDefinitions.CL_MINUS));
				offsets.add(new ThermalSpeciesOffset("test:thermal_offset/m" + material + "_s" + species,
						speciesId, host.id(), precipitate ? ExactRational.of(-1) : ExactRational.ZERO));
			}
		}
		return new ThermalRegistry(materials, offsets, List.of(), BuiltinChemicalDefinitions.REGISTRY.fingerprint(),
				granular.fingerprint());
	}

}
