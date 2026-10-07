package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.*;
import cc.ecisr.mmm.simulation.FullCapabilityTestFixture;
import cc.ecisr.mmm.thermal.*;
import cc.ecisr.mmm.units.SignedDyadic;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Native control placement, complete mass, caloric retry and formal backend publication. */
final class ResolvedLiquidReactionValidation {
	static void validate() throws Exception {
		var domain = fixture(false); var original = domain.captureAuthoritativeState();
		var geometry = domain.liquidGeometryState; var inventory = domain.particles.inventory();
		var energies = domain.thermalState.liquid().copyEnergies(domain.particleCount()); var ledger = domain.thermalAudit();
		var residuals = new long[4]; for (int p = 0; p < 4; p++) residuals[p] = domain.chemicalParcels.record(p).massResidualKilogramsBits();
		var motion = LiquidGeometryMechanicalEnergy.measure(domain); var plan = new ResolvedLiquidReactionPlan(domain);
		plan.publish(); var registry = domain.chemicalParcels.registry();
		int acid = registry.speciesHandle(BuiltinChemicalDefinitions.H_PLUS), base = registry.speciesHandle(BuiltinChemicalDefinitions.OH_MINUS);
		for (int p = 0; p < 2; p++) if (domain.chemicalParcels.record(p).amount(acid) != 0 || domain.chemicalParcels.record(p).amount(base) != 0)
			throw new AssertionError("Cooccupied native stocks did not react on their actual control");
		if (domain.chemicalParcels.record(2).amount(acid) != 1_000_000 || domain.chemicalParcels.record(3).amount(base) != 1_000_000)
			throw new AssertionError("Distinct resolved controls reacted through their common coarse grid cell");
		if (plan.stats.changedMarkers() != 4 || domain.chemicalParcels.hasDirtyCells() || domain.liquidGeometryState != geometry
				|| !domain.particles.inventory().totalMass().equals(inventory.totalMass()) || !domain.particles.inventory().totalVolume().equals(inventory.totalVolume())
				|| !domain.particles.exactMass(0).add(domain.particles.exactMass(1)).equals(inventory.mass(0).add(inventory.mass(1)))
				|| !domain.particles.exactMass(2).equals(inventory.mass(2)) || !domain.particles.exactMass(3).equals(inventory.mass(3))
				|| !Arrays.equals(energies, domain.thermalState.liquid().copyEnergies(4)) || !ledger.equals(domain.thermalAudit())
				|| !motion.equals(LiquidGeometryMechanicalEnergy.measure(domain)))
			throw new AssertionError("Native reaction changed a complete control's mass, geometry, mechanical energy or stored U");
		for (int p = 0; p < 4; p++) if (domain.chemicalParcels.record(p).massResidualKilogramsBits() != residuals[p]
				|| !domain.particles.exactVolume(p).equals(inventory.volume(p)) || domain.chemicalParcels.indicator(p) != (p == 0))
			throw new AssertionError("Native reaction changed residual mass, occupied stock volume or indicator provenance");
		var restored = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		FluidDomainStateAssertions.assertSameState(domain.captureAuthoritativeState(), restored.captureAuthoritativeState(), "native reaction cold handoff");
		var before = domain.captureAuthoritativeState();
		try { plan.publish(); throw new AssertionError("Native reaction candidate published twice"); }
		catch (IllegalStateException expected) { }
		FluidDomainStateAssertions.assertSameState(before, domain.captureAuthoritativeState(), "duplicate native reaction");
		staleQuantityRejects(); caloricRetryPreservesGroup();
		var source = FluidDomain.restoreAuthoritativeState(original); var input = source.captureAuthoritativeState();
		var candidate = new CpuFluidSolverBackend().evaluateThermal(new FluidThermalStepRequest(24, input, false, 0));
		FluidDomainStateAssertions.assertSameState(input, source.captureAuthoritativeState(), "native thermochemical backend input");
		var next = candidate.claimPreparedCpuDomain();
		if (candidate.stats().chemistry().changedMarkers() != 4 || !next.particles.inventory().totalMass().equals(inventory.totalMass())
				|| !next.exactInternalEnergy().equals(next.thermalAudit().expectedInventory()))
			throw new AssertionError("Formal native thermochemical stage lost conservative chemical publication");
		var invariant = chemicalInvariant(source);
		var mechanical = new CpuFluidSolverBackend().evaluate(new FluidStepRequest(25, input, List.of(), false, 0, true));
		FluidDomainStateAssertions.assertSameState(input, source.captureAuthoritativeState(), "native reactive mechanical backend input");
		var moved = mechanical.claimPreparedCpuDomain();
		if (!moved.particles.inventory().totalMass().equals(inventory.totalMass()) || !moved.particles.inventory().totalVolume().equals(inventory.totalVolume())
				|| !invariant.equals(chemicalInvariant(moved)) || !moved.exactInternalEnergy().equals(moved.thermalAudit().expectedInventory()))
			throw new AssertionError("Complete moving native reaction lost extensive inventories, elements, charge or energy closure");
	}
	private static String chemicalInvariant(FluidDomain domain) {
		var registry = domain.chemicalParcels.registry(); var amounts = domain.chemicalParcels.speciesTotals(domain.particleCount());
		var elements = new java.util.TreeMap<String, java.math.BigInteger>(); var charge = java.math.BigInteger.ZERO; var mass = ExactRational.ZERO;
		for (int s = 0; s < amounts.length; s++) if (amounts[s] > 0) {
			var species = registry.species(s); var amount = java.math.BigInteger.valueOf(amounts[s]);
			for (var entry : species.composition().entrySet()) elements.merge(entry.getKey(), amount.multiply(java.math.BigInteger.valueOf(entry.getValue())), java.math.BigInteger::add);
			charge = charge.add(amount.multiply(java.math.BigInteger.valueOf(species.charge())));
			mass = mass.add(species.massKilogramsPerAu().multiply(amounts[s]).divide(ChemicalLimits.QUANTA_PER_AU));
		}
		return elements + "|" + charge + "|" + mass.canonical();
	}
	private static void staleQuantityRejects() throws Exception {
		var domain = fixture(false); var candidate = new ResolvedLiquidReactionPlan(domain);
		domain.particles.setQuantities(0, domain.particles.exactVolume(0), domain.particles.exactMass(0).add(SignedDyadic.of(1, -200)));
		var before = domain.captureAuthoritativeState();
		try { candidate.publish(); throw new AssertionError("Changed complete mass retained a native reaction candidate"); }
		catch (IllegalStateException expected) { }
		FluidDomainStateAssertions.assertSameState(before, domain.captureAuthoritativeState(), "stale native reaction mass");
	}
	private static void caloricRetryPreservesGroup() throws Exception {
		var domain = fixture(true); var originals = List.of(domain.chemicalParcels.record(0), domain.chemicalParcels.record(1));
		var mass = domain.particles.inventory(); var energies = domain.thermalState.liquid().copyEnergies(4); var ledger = domain.thermalAudit();
		for (int retry = 0; retry < 2; retry++) {
			var result = new ResolvedLiquidReactionPlan(domain).publish();
			if (result.changedMarkers() != (retry == 0 ? 2 : 0) || !domain.chemicalParcels.hasDirtyCells()
					|| !domain.chemicalParcels.record(0).equals(originals.get(0)) || !domain.chemicalParcels.record(1).equals(originals.get(1))
					|| !domain.particles.exactMass(0).equals(mass.mass(0)) || !domain.particles.exactMass(1).equals(mass.mass(1))
					|| !Arrays.equals(energies, domain.thermalState.liquid().copyEnergies(4)) || !ledger.equals(domain.thermalAudit()))
				throw new AssertionError("Caloric-limited native reaction changed its source group or prevented independent control progress");
		}
	}
	private static FluidDomain fixture(boolean endothermic) throws Exception {
		var definitions = FullCapabilityTestFixture.load();
		var domain = new FluidDomain(2, 2, 2, new FluidSolverConfig(.5F, 1000, 0, .05F, 512, 1e-7F, .4F, .002F, 0), 16);
		var cells = List.of(new LiquidGeometryRefinement.Cell(3, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, .125, .125, .125)),
				new LiquidGeometryRefinement.Cell(3, 2, 0, 0, LiquidPolyhedron.box(.25, 0, 0, .375, .125, .125)),
				new LiquidGeometryRefinement.Cell(3, 3, 0, 0, LiquidPolyhedron.box(.375, 0, 0, .5, .125, .125)));
		var geometry = LiquidGeometryStateMapper.capture(cells, 1, new int[]{0, 0, 1, 2}, new int[]{0, 1, 2}, new double[]{.25, 0, 0, .75, 0, 0, -.25, 0, 0});
		var volume = new SignedDyadic[]{SignedDyadic.of(1, -7), SignedDyadic.of(1, -7), SignedDyadic.of(1, -6), SignedDyadic.of(1, -6)};
		var mass = Arrays.stream(volume).map(v -> v.multiply(SignedDyadic.of(1000)).add(SignedDyadic.of(1, -100))).toArray(SignedDyadic[]::new);
		var inventory = new LiquidInventoryState(volume, mass);
		domain.restoreMaterialParticleState(new FlipMaterialParticleState(2, 2, 2, .5F, 4, inventory.totalVolume().toDouble(), inventory.totalMass().toDouble(), definitions.liquids().materials(),
				new float[]{.0625F, .0625F, .0625F, volume[0].toFloat(), .0625F, .0625F, .0625F, volume[1].toFloat(),
						.3125F, .0625F, .0625F, volume[2].toFloat(), .4375F, .0625F, .0625F, volume[3].toFloat()},
				new float[]{.25F, 0, 0, .25F, 0, 0, .75F, 0, 0, -.25F, 0, 0}, new byte[8], Arrays.stream(mass).mapToDouble(SignedDyadic::toDouble).toArray(), new int[4], geometry, inventory));
		domain.sealDomainBoundary(); domain.bindChemistry(definitions.chemical());
		for (int p = 0; p < 4; p++) domain.setMarkerChemical(p, Map.of(p == 0 || p == 2 ? BuiltinChemicalDefinitions.HCL : BuiltinChemicalDefinitions.NAOH, 1_000_000L), p == 0);
		domain.bindGasRegistry(definitions.gas());
		var offsets = definitions.thermal().speciesOffsets().stream().map(offset -> endothermic && offset.speciesId().equals(BuiltinChemicalDefinitions.H2O)
				? new ThermalSpeciesOffset(offset.id(), offset.speciesId(), offset.hostThermalMaterialId(), ExactRational.of(1_000_000_000_000_000L)) : offset).toList();
		domain.bindThermal(new ThermalRegistry(definitions.thermal().materials(), offsets, List.of(), List.of(), List.of(), definitions.chemical().fingerprint(), "", definitions.gas().fingerprint()),
				domain.materialRegistry(), 300, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		return domain;
	}
}
