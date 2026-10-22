package mmm.item;

import mmm.chemistry.BuiltinChemicalDefinitions;
import mmm.chemistry.ChemicalLimits;
import mmm.chemistry.ExactRational;
import mmm.fluid.FluidDomain;
import mmm.fluid.FluidDomainStateAssertions;
import mmm.fluid.FlipParticleState;
import mmm.fluid.FluidMaterial;
import mmm.fluid.FluidSolverConfig;
import mmm.granular.BuiltinGranularDefinitions;
import mmm.granular.GranularRegistry;
import mmm.granular.SolidCompositionRecord;
import mmm.granular.SolidVolume;
import mmm.transfer.ChemicalContents;
import mmm.transfer.ContainerFluidLedger;
import mmm.transfer.CreativeSourceDescriptor;
import mmm.transfer.FluidMixture;
import mmm.transfer.GranularContents;
import mmm.transfer.LiquidContainerThermodynamics;
import mmm.transfer.ThermalContents;
import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalRegistry;
import mmm.thermal.ThermalSpeciesOffset;
import mmm.thermal.ThermalStateFunction;
import mmm.units.SignedDyadic;

import java.util.ArrayList;
import java.util.List;

/** Exact container quantities, actual cell transfer transactions and complete recovery. */
public final class M6ContainerValidation {
	private static final FluidSolverConfig CONFIG = new FluidSolverConfig(
			0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F, 48, 1.0E-4F, 2.0E-3F);
	private static final long BUCKET = ContainerFluidLedger.NANOS_PER_BLOCK;
	private static int passed;

	private M6ContainerValidation() { }

	public static void main(String[] args) throws Exception {
		validateCreativeSourceDescriptor();
		run("ledger arithmetic is exact and overflow-checked", M6ContainerValidation::testArithmetic);
		run("display volume conversions round within half a container quantum", M6ContainerValidation::testConversions);
		run("bucket canonicalization boundaries", M6ContainerValidation::testCanonicalization);
		run("exact insert plans or refuses atomically", M6ContainerValidation::testExactInsert);
		run("exact extract removes exactly or refuses", M6ContainerValidation::testExactExtract);
		run("sub-micro residual and independent material mass survive exact transfer", M6ContainerValidation::testExactMaterialMass);
		run("multi-material container and solver transfer close every component ledger", M6ContainerValidation::testMixtureTransfer);
		run("malformed mixture mass and volume are rejected", M6ContainerValidation::testMixtureHardening);
		run("granular contents are canonical and registry-bound", M6ContainerValidation::testGranularContents);
		run("thermal contents bind raw energy to exact liquid inventory", M6ContainerValidation::testThermalContents);
		run("tiny container thermal shares preserve actual caloric inventories", M6ContainerValidation::testTinyThermalShares);
		run("full bucket round-trips through the primitives", M6ContainerValidation::testBucketRoundTrip);
		System.out.println("M6ContainerValidation passed " + passed + " scenarios");
	}

	private static void validateCreativeSourceDescriptor() {
		CreativeSourceDescriptor descriptor = new CreativeSourceDescriptor(
				"mmm:source/material/water", ContainerFluidLedger.NANOS_PER_BLOCK, 293.15);
		check(CreativeSourceDescriptor.decode(descriptor.encode()).equals(descriptor),
				"creative source descriptor did not round-trip canonically");
		try {
			CreativeSourceDescriptor.decode("mmm:source/material/water;1000000000;7ff8000000000000");
			throw new AssertionError("non-finite creative source temperature was accepted");
		} catch (IllegalArgumentException expected) { }
	}

	private static void testThermalContents() {
		ThermalMaterialDefinition heavy = thermalLiquid("test:thermal/heavy", "test:heavy", 1.0);
		ThermalMaterialDefinition light = thermalLiquid("test:thermal/light", "test:light", 1_000.0);
		ThermalRegistry registry = new ThermalRegistry(List.of(heavy, light));
		ThermalStateFunction state = new ThermalStateFunction(registry);
		FluidMixture mixture = new FluidMixture(List.of(
				new FluidMixture.Component("test:heavy", 500_000_000L, mmm.units.Mass.fromKilograms(100.0)),
				new FluidMixture.Component("test:light", 500_000_000L, mmm.units.Mass.fromKilograms(1.0))));
		double energy = state.internalEnergyAtTemperature(
				registry.materialHandle(ThermalMaterialDefinition.CarrierKind.LIQUID, "test:heavy"),
				100.0, java.util.Map.of(), 320.0)
				+ state.internalEnergyAtTemperature(
				registry.materialHandle(ThermalMaterialDefinition.CarrierKind.LIQUID, "test:light"),
				1.0, java.util.Map.of(), 320.0);
		ThermalContents contents = ThermalContents.forMixture(registry.fingerprint(), mixture, energy);
		check(ThermalContents.decode(contents.encode()).equals(contents),
				"thermal contents canonical round-trip changed identity or raw U");
		FluidMixture selected = new FluidMixture(List.of(
				new FluidMixture.Component("test:heavy", 125_000_000L, mmm.units.Mass.fromKilograms(25.0)),
				new FluidMixture.Component("test:light", 375_000_000L, mmm.units.Mass.fromKilograms(0.75))));
		FluidMixture remaining = new FluidMixture(List.of(
				new FluidMixture.Component("test:heavy", 375_000_000L, mmm.units.Mass.fromKilograms(75.0)),
				new FluidMixture.Component("test:light", 125_000_000L, mmm.units.Mass.fromKilograms(0.25))));
		ThermalContents.EnergySplit split = LiquidContainerThermodynamics.split(
				registry, contents, mixture, selected, remaining);
		check(split.selectedEnergy() == 15_500.0 && split.remainingEnergy() == 6_500.0,
				"heterogeneous thermal split ignored selected mass and heat capacity");
		check(LiquidContainerThermodynamics.temperatureK(registry,
				ThermalContents.forMixture(registry.fingerprint(), selected, split.selectedEnergy()), selected) == 320.0
				&& LiquidContainerThermodynamics.temperatureK(registry,
				ThermalContents.forMixture(registry.fingerprint(), remaining, split.remainingEnergy()), remaining) == 320.0,
				"bucket split did not preserve the source equilibrium temperature on both inventories");
		var zeroSplit = LiquidContainerThermodynamics.split(registry,
				ThermalContents.forMixture(registry.fingerprint(), mixture, 0.0), mixture, selected, remaining);
		check(zeroSplit.selectedEnergy() == 0.0 && zeroSplit.remainingEnergy() == 0.0,
				"zero reference-coordinate energy changed during heterogeneous split");
		assertChemicalThermalSplit();
		try {
			contents.splitHomogeneousEnergy(500_000_000L, BUCKET);
			throw new AssertionError("Expected heterogeneous volume-only energy split rejection");
		} catch (IllegalArgumentException expected) { }
		ThermalContents pure = ThermalContents.forPureWater(registry.fingerprint(), BUCKET, -12_345.25);
		ThermalContents.EnergySplit pureSplit = pure.splitHomogeneousEnergy(333_333_333L, BUCKET);
		check(Double.doubleToRawLongBits(pureSplit.selectedEnergy() + pureSplit.remainingEnergy())
				== Double.doubleToRawLongBits(pure.internalEnergy()),
				"homogeneous thermal split did not return the raw U remainder");
		FluidMixture changed = new FluidMixture(List.of(
				new FluidMixture.Component("test:heavy", 500_000_001L, mmm.units.Mass.fromKilograms(100.0)),
				new FluidMixture.Component("test:light", 499_999_999L, mmm.units.Mass.fromKilograms(1.0))));
		check(!ThermalContents.forMixture(registry.fingerprint(), changed, contents.internalEnergy()).underlyingHash()
					.equals(contents.underlyingHash()), "thermal contents hash ignored an exact liquid-ledger change");
	}

	private static void testTinyThermalShares() {
		long total = 999_999_999L;
		for (double sourceEnergy : new double[] {0.0, -1.226E9, 1.0E20}) {
			ThermalContents pure = ThermalContents.forPureWater("0".repeat(64), total, sourceEnergy);
			for (long selected : new long[] {59, total - 59}) {
				var split = pure.splitHomogeneousEnergy(selected, total);
				double smaller = sourceEnergy * (59.0 / total);
				check(Double.doubleToRawLongBits(selected == 59 ? split.selectedEnergy() : split.remainingEnergy())
						== Double.doubleToRawLongBits(smaller), "pure tiny energy was formed by cancellation");
				check(split.coupledEnergy(sourceEnergy) == SignedDyadic.of(sourceEnergy)
						.subtract(SignedDyadic.of(split.remainingEnergy())).toDouble(), "item debit changed raw energy definition");
			}
		}
		for (double reference : new double[] {0.0, 1.0E18, -1.0E18}) {
			var definition = new ThermalMaterialDefinition("test:thermal/tiny", ThermalMaterialDefinition.CarrierKind.LIQUID,
					"mmm:water", 293.15, reference, 0.0,
					List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 600.0, 4184.0)));
			var registry = new ThermalRegistry(List.of(definition));
			var state = new ThermalStateFunction(registry);
			var source = new FluidMixture(List.of(new FluidMixture.Component("mmm:water", total, mmm.units.Mass.fromKilograms(999.0))));
			double energy = state.internalEnergyAtTemperature(0, 999.0, java.util.Map.of(), reference == 0 ? 1.0E-8 : 300.0);
			var contents = ThermalContents.forMixture(registry.fingerprint(), source, energy);
			double sourceT = LiquidContainerThermodynamics.temperatureK(registry, contents, source);
			for (long selectedNanos : new long[] {59, total - 59}) {
				double selectedMass = 999.0 * ((double) selectedNanos / total);
				var selected = new FluidMixture(List.of(new FluidMixture.Component("mmm:water", selectedNanos, mmm.units.Mass.fromKilograms(selectedMass))));
				var remaining = new FluidMixture(List.of(new FluidMixture.Component("mmm:water", total - selectedNanos, mmm.units.Mass.fromKilograms(999.0 - selectedMass))));
				var split = LiquidContainerThermodynamics.split(registry, contents, source, selected, remaining);
				boolean smallSelected = selectedNanos == 59;
				double direct = state.internalEnergyAtTemperature(0, smallSelected ? selected.totalMass() : remaining.totalMass(),
						java.util.Map.of(), sourceT);
				check((smallSelected ? split.selectedEnergy() : split.remainingEnergy()) == direct,
						"tiny actual-mass caloric energy was replaced by a large-share subtraction");
				SignedDyadic error = SignedDyadic.of(split.selectedEnergy()).add(SignedDyadic.of(split.remainingEnergy()))
						.subtract(SignedDyadic.of(energy));
				check(Math.abs(error.toDouble()) <= 0.5 * Math.ulp(smallSelected ? split.remainingEnergy() : split.selectedEnergy()),
						"split exceeded its single larger-share rounding bound");
			}
		}
	}

	private static void assertChemicalThermalSplit() {
		ThermalMaterialDefinition solution = thermalLiquid("test:thermal/solution", "mmm:water", 100.0);
		ThermalRegistry registry = new ThermalRegistry(List.of(solution), List.of(new ThermalSpeciesOffset(
				"test:thermal_offset/water", BuiltinChemicalDefinitions.H2O, solution.id(), ExactRational.of(12))),
				List.of(), BuiltinChemicalDefinitions.REGISTRY.fingerprint(), "");
		ChemicalContents source = new ChemicalContents(BuiltinChemicalDefinitions.REGISTRY.fingerprint(),
				ChemicalLimits.QUANTA_PER_MOL, List.of(new ChemicalContents.Group("mmm:water", true, BUCKET,
				mmm.units.Mass.fromKilograms(18.0), mmm.units.Mass.fromKilograms(0.0), List.of(
				new ChemicalContents.SpeciesAmount(BuiltinChemicalDefinitions.H2O, 1_000 * ChemicalLimits.QUANTA_PER_MOL)))));
		ChemicalContents.Split inventory = source.split(333_333_333L, BuiltinChemicalDefinitions.REGISTRY);
		ThermalStateFunction state = new ThermalStateFunction(registry);
		double sourceEnergy = state.internalEnergyAtTemperature(registry.materialHandle(solution.id()),
				source.totalMass(), java.util.Map.of(BuiltinChemicalDefinitions.H2O,
				1_000 * ChemicalLimits.QUANTA_PER_MOL), 320.0);
		ThermalContents thermal = ThermalContents.forChemical(registry.fingerprint(), source, sourceEnergy);
		ThermalContents.EnergySplit split = LiquidContainerThermodynamics.split(registry, thermal, source,
				inventory.inserted(), inventory.remaining());
		check(Double.doubleToRawLongBits(split.selectedEnergy() + split.remainingEnergy())
				== Double.doubleToRawLongBits(sourceEnergy), "chemical thermal split did not conserve raw U");
		check(Math.abs(LiquidContainerThermodynamics.temperatureK(registry,
				ThermalContents.forChemical(registry.fingerprint(), inventory.inserted(), split.selectedEnergy()),
				inventory.inserted()) - 320.0) < 1.0E-12,
				"chemical thermal split ignored selected mass or species reference energy");
		for (long selectedNanos : new long[] {1L, BUCKET - 1L}) {
			var tinyInventory = source.split(selectedNanos, BuiltinChemicalDefinitions.REGISTRY);
			var tinyEnergy = LiquidContainerThermodynamics.split(registry, thermal, source,
					tinyInventory.inserted(), tinyInventory.remaining());
			var smaller = selectedNanos == 1L ? tinyInventory.inserted() : tinyInventory.remaining();
			var group = smaller.groups().getFirst();
			var amounts = java.util.Map.of(group.species().getFirst().speciesId(), group.species().getFirst().amountQuanta());
			double actualT = LiquidContainerThermodynamics.temperatureK(registry, thermal, source);
			double expected = state.internalEnergyAtTemperature(0, group.massKilograms(), amounts, actualT);
			check((selectedNanos == 1L ? tinyEnergy.selectedEnergy() : tinyEnergy.remainingEnergy()) == expected,
					"tiny chemical share ignored its actual integer species offset");
		}
	}

	private static ThermalMaterialDefinition thermalLiquid(String id, String carrierId, double heatCapacity) {
		return new ThermalMaterialDefinition(id, ThermalMaterialDefinition.CarrierKind.LIQUID, carrierId,
				300.0, 0.0, 0.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 500.0, heatCapacity)));
	}

	private static void testGranularContents() {
		GranularRegistry registry = BuiltinGranularDefinitions.createRegistry();
		SolidCompositionRecord solid = SolidCompositionRecord.fromStableAmounts(
				BuiltinChemicalDefinitions.REGISTRY, java.util.Map.of(
						BuiltinChemicalDefinitions.CL_MINUS, 640L,
						BuiltinChemicalDefinitions.NA_PLUS, 640L), 0L);
		List<GranularContents.SpeciesAmount> species = java.util.Map.of(
				BuiltinChemicalDefinitions.CL_MINUS, 640L,
				BuiltinChemicalDefinitions.NA_PLUS, 640L).entrySet().stream()
				.sorted(java.util.Map.Entry.comparingByKey())
				.map(entry -> new GranularContents.SpeciesAmount(entry.getKey(), entry.getValue())).toList();
		GranularContents contents = new GranularContents(registry.fingerprint(), SolidVolume.SVQ_PER_CUBIC_METER, List.of(
				new GranularContents.Group(BuiltinGranularDefinitions.FINE_SAND, 65_536L, mmm.units.Mass.fromKilograms(127.5)),
				new GranularContents.Group(BuiltinGranularDefinitions.PRECIPITATE, 10L,
						mmm.units.Mass.fromKilograms(solid.totalMass(BuiltinChemicalDefinitions.REGISTRY)),
						Double.doubleToRawLongBits(0.0), species)));
		contents.validateAgainst(registry, BuiltinChemicalDefinitions.REGISTRY);
		GranularContents decoded = GranularContents.decode(contents.encode());
		check(contents.equals(decoded) && decoded.totalSolidVolumeSvq() == 65_546L,
				"granular contents canonical round-trip changed exact inventory");
		try {
			new GranularContents("0".repeat(64), SolidVolume.SVQ_PER_CUBIC_METER, decoded.groups())
					.validateAgainst(registry, BuiltinChemicalDefinitions.REGISTRY);
			throw new AssertionError("Expected foreign granular registry rejection");
		} catch (IllegalArgumentException expected) {
			// expected
		}
	}

	private static void testArithmetic() {
		check(ContainerFluidLedger.cubicMetersToNanos(0.0) == 0L, "empty volume converted");
		check(ContainerFluidLedger.cubicMetersToNanos(1.0) == BUCKET, "one block volume is one bucket");
		check(ContainerFluidLedger.cubicMetersToNanos(0.5) == BUCKET / 2L, "half bucket converted");
		check(ContainerFluidLedger.nanosToCubicMeters(BUCKET) == 1.0, "bucket converted back");
		check((float) ContainerFluidLedger.nanosToCubicMeters(BUCKET / 4L) == 0.25F, "quarter converts to an exact float");

		check(ContainerFluidLedger.transferable(0L, BUCKET, BUCKET) == BUCKET, "empty bucket accepts a full bucket");
		check(ContainerFluidLedger.transferable(BUCKET / 2L, BUCKET, BUCKET) == BUCKET / 2L, "atomic fill leaves the remainder");
		check(ContainerFluidLedger.transferable(BUCKET, BUCKET, BUCKET) == 0L, "full bucket accepts nothing");
		check(ContainerFluidLedger.transferable(3L, 10L, 8L) == 5L, "partial source is fully consumed by capacity space");

		check(ContainerFluidLedger.checkedAdd(BUCKET, BUCKET) == 2L * BUCKET, "checked add");
		try {
			ContainerFluidLedger.checkedAdd(Long.MAX_VALUE, 1L);
			throw new AssertionError("Expected overflow rejection");
		} catch (ArithmeticException expected) {
			// expected
		}
		check(ContainerFluidLedger.checkedSubtract(0L, 1L) == -1L,
				"checked subtract only guards overflow; non-negativity is validated by the container");
		try {
			ContainerFluidLedger.checkedSubtract(Long.MIN_VALUE, 1L);
			throw new AssertionError("Expected underflow rejection");
		} catch (ArithmeticException expected) {
			// expected
		}
		for (Runnable invalid : new Runnable[] {
				() -> ContainerFluidLedger.cubicMetersToNanos(-0.1),
				() -> ContainerFluidLedger.cubicMetersToNanos(Double.NaN),
				() -> ContainerFluidLedger.cubicMetersToNanos(Double.POSITIVE_INFINITY),
				() -> ContainerFluidLedger.cubicMetersToNanos(1.0E19),
				() -> ContainerFluidLedger.nanosToCubicMeters(-1L),
				() -> ContainerFluidLedger.transferable(-1L, 0L, BUCKET),
				() -> ContainerFluidLedger.transferable(0L, -1L, BUCKET),
				() -> ContainerFluidLedger.transferable(0L, 0L, 0L),
				() -> ContainerFluidLedger.transferable(BUCKET + 1L, 0L, BUCKET)
		}) {
			try {
				invalid.run();
				throw new AssertionError("Expected IllegalArgumentException");
			} catch (IllegalArgumentException expected) {
				// expected
			}
		}
	}

	private static void testConversions() {
		FluidDomain domain = new FluidDomain(6, 4, 6, CONFIG);
		domain.setVolume(1, 1, 1, 0.25F);
		domain.setVolume(2, 1, 2, 1.0F);
		domain.setVolume(4, 2, 4, 0.625F);
		domain.setVolume(3, 2, 1, 1.0F / 3.0F);
		domain.setVolume(5, 1, 3, 0.1F);
		FlipParticleState state = domain.exportParticleState();
		check(state.particleCount() > 0, "the solver produced no markers to convert");
		float[] markers = state.copyPositionVolume();
		var units = mmm.units.SolverUnitScale.forCellSize(CONFIG.cellSize());
		double exactSum = 0.0;
		long summedQuanta = 0L;
		for (int i = 0; i < state.particleCount(); i++) {
			float volume = markers[i * 4 + 3];
			check(volume > 0.0F && Float.isFinite(volume), "marker volume is not positive and finite");
			double cubicMeters = units.cubicMeters(volume);
			long quanta = ContainerFluidLedger.cubicMetersToNanos(cubicMeters);
			check(Math.abs(quanta - cubicMeters * 1_000_000_000.0) <= 0.5, "conversion exceeded half a quantum");
			float roundTrip = (float) (ContainerFluidLedger.nanosToCubicMeters(quanta) / units.cubicMetersPerNormalizedVolume());
			check(Math.abs(roundTrip - volume) <= Math.ulp(volume) * 2.0F, "float round-trip exceeded float precision");
			exactSum += cubicMeters;
			summedQuanta += quanta;
		}
		// Converting the exact sum vs summing conversions may differ by at most half a quantum per marker.
		long sumQuanta = ContainerFluidLedger.cubicMetersToNanos(exactSum);
		check(Math.abs(sumQuanta - summedQuanta) <= state.particleCount() / 2L + 1L,
				"quanta aggregation drifted beyond the documented bound");
	}

	private static void testCanonicalization() {
		check(ContainerFluidLedger.isFullBucket(BUCKET), "one bucket exactly is a full bucket");
		check(!ContainerFluidLedger.isFullBucket(BUCKET - 1L) && !ContainerFluidLedger.isFullBucket(BUCKET + 1L),
				"only one bucket exactly is full");
		check(ContainerFluidLedger.isPartialBucket(1L), "one quantum is a partial bucket");
		check(ContainerFluidLedger.isPartialBucket(BUCKET - 1L), "one quantum short is a partial bucket");
		check(!ContainerFluidLedger.isPartialBucket(0L) && !ContainerFluidLedger.isPartialBucket(BUCKET),
				"empty and full are not partial");
		check(!ContainerFluidLedger.isPartialBucket(-1L) && !ContainerFluidLedger.isPartialBucket(BUCKET + 1L),
				"invalid component values are not partial buckets");

		check(ContainerFluidLedger.mergeOutput(BUCKET / 2L, BUCKET / 2L) == BUCKET,
				"two halves merge into one full bucket");
		check(ContainerFluidLedger.mergeRemainder(BUCKET / 2L, BUCKET / 2L) == 0L,
				"exact full merge leaves no remainder");
		check(ContainerFluidLedger.mergeOutput(BUCKET / 2L, BUCKET * 3L / 4L) == BUCKET,
				"overfull merge still caps at one full bucket");
		check(ContainerFluidLedger.mergeRemainder(BUCKET / 2L, BUCKET * 3L / 4L) == BUCKET / 4L,
				"overflow stays as the recipe remainder");
		check(ContainerFluidLedger.mergeOutput(BUCKET / 4L, BUCKET / 4L) == BUCKET / 2L,
				"two quarter buckets merge into one half bucket");
		check(ContainerFluidLedger.mergeRemainder(BUCKET / 4L, BUCKET / 4L) == 0L,
				"underfull merge leaves no remainder");


		check(ContainerFluidLedger.barWidth(0L) == 1, "empty volume shows the minimum bar");
		check(ContainerFluidLedger.barWidth(BUCKET) == 13, "a full volume shows the maximum bar");
		check(ContainerFluidLedger.barWidth(BUCKET / 2L) == 7, "half volume rounds to a 7-segment bar");
		for (long nanos : new long[] {-1L, 1L, BUCKET / 4L, BUCKET - 1L}) {
			int width = ContainerFluidLedger.barWidth(nanos);
			check(width >= 1 && width <= 13, "bar width left the display range for " + nanos);
		}
	}

	private static void testExactInsert() {
		var domain=new FluidDomain(6,4,6,CONFIG);domain.setSolid(0,0,0,true);
		var inserted=domain.tryInsertExactVolume(3,2,3,.375F,2);
		check(inserted.transferred()&&inserted.volume()==.375
				&&inventoryVolume(domain).equals(SignedDyadic.of(inserted.volume()))&&domain.totalMassUnits()==inserted.massUnits(),
				"Exact insertion did not publish the reported volume and integer mass");
		var before=domain.captureAuthoritativeState();
		check(!domain.tryInsertExactVolume(3,2,3,100,3).transferred(),"Insertion exceeded actual open capacity");
		FluidDomainStateAssertions.assertSameState(before,domain.captureAuthoritativeState(),"Capacity-limited insertion");
		var compact=new FluidDomain(6,4,6,CONFIG);
		var addition=compact.tryInsertExactVolume(3,2,3,1,2);
		check(addition.transferred()&&inventoryVolume(compact).equals(SignedDyadic.of(1))&&compact.totalMassUnits()==addition.massUnits(),
				"Insertion depended on the old display sampling budget");
		var restored=FluidDomain.restoreAuthoritativeState(compact.captureAuthoritativeState());
		check(restored.exportLiquidCellState().stocks().equals(compact.exportLiquidCellState().stocks()),"Inserted stock did not survive recovery");
		before=compact.captureAuthoritativeState();
		check(compact.tryInsertExactVolume(3,2,3,0,2).transferred(),"Zero-volume insertion failed");
		FluidDomainStateAssertions.assertSameState(before,compact.captureAuthoritativeState(),"Zero-volume insertion");
	}

	private static void testExactExtract() {
		var domain=new FluidDomain(6,4,6,CONFIG);
		check(domain.tryInsertExactVolume(3,2,3,.375F,2).transferred(),"Extraction setup failed");
		var source=domain.captureAuthoritativeState();var volume=inventoryVolume(domain);long mass=domain.totalMassUnits();
		check(!domain.tryExtractExactVolume(3,2,3,.5F,2).transferred(),"Extraction exceeded available stock");
		FluidDomainStateAssertions.assertSameState(source,domain.captureAuthoritativeState(),"Insufficient extraction");
		var extracted=domain.tryExtractExactVolume(3,2,3,.25F,2);
		check(extracted.transferred()&&extracted.volume()==.25
				&&inventoryVolume(domain).add(SignedDyadic.of(extracted.volume())).equals(volume)
				&&Math.addExact(domain.totalMassUnits(),extracted.massUnits())==mass,"Extraction failed its exact V/M ledger");
		source=domain.captureAuthoritativeState();
		check(domain.tryExtractExactVolume(3,2,3,0,2).transferred(),"Zero-volume extraction failed");
		FluidDomainStateAssertions.assertSameState(source,domain.captureAuthoritativeState(),"Zero-volume extraction");

		var coarse=new FluidDomain(4,4,4,CONFIG);
		check(coarse.tryInsertExactVolume(1,1,1,.125F,0).transferred(),"Small-transfer setup failed");
		var original=coarse.captureAuthoritativeState();
		// Display positions and float volume samples can stay unchanged while exact stock moves.
		float request=.75F*(.125F-Math.nextDown(.125F));
		for(boolean mixture:new boolean[]{false,true}){
			var candidate=FluidDomain.restoreAuthoritativeState(original);
			var before=inventoryVolume(candidate);long beforeMass=candidate.totalMassUnits();
			SignedDyadic removedVolume;long removedMass;
			if(mixture){
				var transfer=candidate.tryExtractExactMixture(1,1,1,request,0);
				check(transfer.transferred(),"Fine mixture extraction failed");
				removedVolume=SignedDyadic.ZERO;removedMass=0;
				for(var portion:transfer.portions()){removedVolume=removedVolume.add(SignedDyadic.of(portion.volume()));removedMass=Math.addExact(removedMass,portion.massUnits());}
			}else{
				var transfer=candidate.tryExtractExactVolume(1,1,1,request,0);
				check(transfer.transferred(),"Fine single-material extraction failed");
				removedVolume=SignedDyadic.of(transfer.volume());removedMass=transfer.massUnits();
			}
			check(removedVolume.equals(SignedDyadic.of(request))&&removedMass>0
					&&inventoryVolume(candidate).add(removedVolume).equals(before)
					&&Math.addExact(candidate.totalMassUnits(),removedMass)==beforeMass,"Fine transfer lost exact stock behind its display sample");
			var restored=FluidDomain.restoreAuthoritativeState(candidate.captureAuthoritativeState());
			check(restored.exportLiquidCellState().stocks().equals(candidate.exportLiquidCellState().stocks()),"Fine transfer did not survive recovery");
		}
		float fineVolume=Math.scalb(1F,-30);
		check(coarse.tryInsertExactVolume(2,1,1,fineVolume,0).transferred(),"Fine stock setup failed");
		var mixedScale=coarse.captureAuthoritativeState();var before=inventoryVolume(coarse);long beforeMass=coarse.totalMassUnits();
		var fine=coarse.tryExtractExactVolume(1,1,1,fineVolume,1);
		check(fine.transferred()&&fine.volume()==fineVolume&&fine.massUnits()>0
				&&inventoryVolume(coarse).add(SignedDyadic.of(fine.volume())).equals(before)
				&&Math.addExact(coarse.totalMassUnits(),fine.massUnits())==beforeMass,"Fine transfer next to a larger stock did not close V/M");
		var mixture=FluidDomain.restoreAuthoritativeState(mixedScale);
		var fineMixture=mixture.tryExtractExactMixture(2,1,1,fineVolume,0);
		var removedVolume=SignedDyadic.ZERO;long removedMass=0;
		for(var portion:fineMixture.portions()){removedVolume=removedVolume.add(SignedDyadic.of(portion.volume()));removedMass=Math.addExact(removedMass,portion.massUnits());}
		check(fineMixture.transferred()&&removedVolume.equals(SignedDyadic.of(fineVolume))
				&&inventoryVolume(mixture).add(removedVolume).equals(before)
				&&Math.addExact(mixture.totalMassUnits(),removedMass)==beforeMass,"Fine mixture transfer lost a stock delta beside a larger inventory");
	}

	private static void testExactMaterialMass() {
		FluidDomain residual = new FluidDomain(4, 4, 4, CONFIG);
		FluidDomain.ExactVolumeTransfer seeded = residual.tryInsertExactVolume(2, 2, 2, 5.0E-7F, 0);
		check(seeded.transferred(), "could not seed a small inventory");
		FluidDomain.ExactVolumeTransfer tiny = residual.tryExtractExactVolume(2, 2, 2, 1.0E-7F, 0);
		check(tiny.transferred() && residual.totalVolumeExact() > 0.0
				&& residual.totalVolumeExact() < 5.0E-7,
				"exact extraction zeroed a positive sub-micro residual: transferred=" + tiny.transferred()
						+ ", removed=" + tiny.volume() + ", residual=" + residual.totalVolumeExact());

		FluidDomain domain = new FluidDomain(4, 4, 4, CONFIG);
		domain.bindMaterialRegistry(new mmm.fluid.FluidMaterialRegistry(List.of(
				new FluidMaterial("mmm:water",1000,"mmm:aqueous",0,Math.PI/2),
				new FluidMaterial("mmm:oil",800,"mmm:oil",0,Math.PI/2))));

		double insertedMass = 123.456789012345;
		FluidDomain.ExactVolumeTransfer inserted = domain.tryInsertExactMaterial(
				2, 2, 2, 0.375F, mmm.units.SolverUnitScale.forCellSize(domain.config().cellSize()).massUnitsForNormalized(insertedMass), 1, 1);
		check(inserted.transferred()
				&& inserted.massUnits() == domain.totalMassUnits(),
				"material insert did not preserve its independent mass ledger");
		check(Math.abs(domain.totalMass() - 0.375 * 800.0) > 100.0,
				"material insert silently reconstructed mass from reference density");
		double massBefore = domain.totalMass();
		var materialBefore = domain.captureAuthoritativeState();
		check(!domain.tryExtractExactVolume(2, 2, 2, 0.125F, 1).transferred(),
				"default-carrier extraction removed another material");
		FluidDomainStateAssertions.assertSameState(materialBefore, domain.captureAuthoritativeState(),
				"default-carrier extraction preserved non-default material");
		FluidDomain.ExactVolumeTransfer extracted = domain.tryExtractExactVolume(2, 2, 2, 0.125F, 1, 1);
		check(extracted.transferred() && mmm.units.SolverUnitScale.forCellSize(CONFIG.cellSize()).normalizedMass(extracted.massUnits()) > 0.0,
				"material extraction did not return removed mass");
		check(Math.abs(massBefore - (domain.totalMass() + mmm.units.SolverUnitScale.forCellSize(CONFIG.cellSize()).normalizedMass(extracted.massUnits()))) <= 1.0E-12,
				"material extraction failed to close the mass ledger");
		// This call exercises the strict double volume sum used by the material region handoff.
		domain.exportMaterialParticleState();
	}

	private static void testMixtureTransfer() {
		FluidMixture expected = new FluidMixture(List.of(
				new FluidMixture.Component("mmm:oil", 400_000_000L, mmm.units.Mass.fromKilograms(320.0)),
				new FluidMixture.Component("mmm:water", 600_000_000L, mmm.units.Mass.fromKilograms(600.0))));
		check(FluidMixture.decode(expected.encode()).equals(expected)
				&& expected.totalVolumeNanos() == BUCKET && expected.totalMass() == 920.0,
				"canonical fluid-mixture handle did not round-trip exact ledgers");
		try {
			FluidMixture.decode("mmm:fluid-mixture;" + "x".repeat(FluidMixture.MAX_ENCODED_LENGTH));
			throw new AssertionError("Expected oversized mixture rejection");
		} catch (IllegalArgumentException expectedFailure) {
			// expected: persisted strings are bounded before splitting or allocation
		}

		FluidDomain domain = new FluidDomain(4, 4, 4, CONFIG);
		domain.bindMaterialRegistry(new mmm.fluid.FluidMaterialRegistry(List.of(
				new FluidMaterial("mmm:water",1000,"mmm:aqueous",0,Math.PI/2),
				new FluidMaterial("mmm:oil",800,"mmm:oil",0,Math.PI/2))));

		List<FluidDomain.MaterialPortion> portions = List.of(
				new FluidDomain.MaterialPortion(1, 3.2, mmm.units.SolverUnitScale.forCellSize(domain.config().cellSize()).massUnitsForNormalized(256.125)),
				new FluidDomain.MaterialPortion(0, 4.8, mmm.units.SolverUnitScale.forCellSize(domain.config().cellSize()).massUnitsForNormalized(480.875)));
		FluidDomain.ExactMixtureTransfer inserted = domain.tryInsertExactMixture(2, 2, 2, portions, 2);
		double insertedVolume = inserted.portions().stream().mapToDouble(FluidDomain.MaterialPortion::volume).sum();
		double insertedMass = inserted.portions().stream().mapToDouble((FluidDomain.MaterialPortion p) -> mmm.units.SolverUnitScale.forCellSize(CONFIG.cellSize()).normalizedMass(p.massUnits())).sum();
		check(inserted.transferred() && inserted.portions().size() == 2
				&& Math.abs(domain.totalVolumeExact() - insertedVolume) <= 1.0E-15
				&& Math.abs(domain.totalMass() - insertedMass) <= 1.0E-12
				&& Math.abs(insertedVolume - 8.0) <= 1.0E-8
				&& Math.abs(insertedMass - 737.0) <= 1.0E-12,
				"atomic mixture insertion changed a component ledger: transferred=" + inserted.transferred()
						+ ", portions=" + inserted.portions() + ", volume=" + domain.totalVolumeExact()
						+ ", mass=" + domain.totalMass());
		double volumeBeforeRefusal = domain.totalVolumeExact(), massBeforeRefusal = domain.totalMass();
		check(!domain.tryInsertExactMixture(2, 2, 2,
				List.of(new FluidDomain.MaterialPortion(0, 100.0, mmm.units.SolverUnitScale.forCellSize(domain.config().cellSize()).massUnitsForNormalized(100.0))), 1).transferred()
				&& domain.totalVolumeExact() == volumeBeforeRefusal && domain.totalMass() == massBeforeRefusal,
				"refused mixture insert left partial inventory");

		FluidDomain.ExactMixtureTransfer extracted = domain.tryExtractExactMixture(2, 2, 2, 8.0F, 2);
		double extractedVolume = extracted.portions().stream().mapToDouble(FluidDomain.MaterialPortion::volume).sum();
		double extractedMass = extracted.portions().stream().mapToDouble((FluidDomain.MaterialPortion p) -> mmm.units.SolverUnitScale.forCellSize(CONFIG.cellSize()).normalizedMass(p.massUnits())).sum();
		check(extracted.transferred() && extracted.portions().size() == 2
				&& Math.abs(extractedVolume - 8.0) <= 1.0E-12
				&& Math.abs(insertedVolume - (domain.totalVolumeExact() + extractedVolume)) <= 1.0E-15
				&& Math.abs(insertedMass - (domain.totalMass() + extractedMass)) <= 1.0E-12
				&& domain.totalVolumeExact() <= 1.0E-8 && domain.totalMass() <= 1.0E-6,
				"mixture extraction did not return every material volume/mass ledger: portions="
						+ extracted.portions() + ", insertedV=" + insertedVolume + ", beforeV="
						+ volumeBeforeRefusal + ", extractedV=" + extractedVolume + ", residualV="
						+ domain.totalVolumeExact() + ", extractedM=" + extractedMass + ", residualM="
						+ domain.totalMass());
	}

	private static void testMixtureHardening() {
		try {
			new FluidMixture(List.of(
					new FluidMixture.Component("mmm:oil", 1L, Long.MAX_VALUE),
					new FluidMixture.Component("mmm:water", 1L, Long.MAX_VALUE)));
			throw new AssertionError("Expected integer mixture total-mass overflow rejection");
		} catch (IllegalArgumentException | ArithmeticException expected) {
			// A malformed item must be rejected during construction, before tooltip/WAL callers.
		}

		FluidDomain domain = new FluidDomain(4, 4, 4, CONFIG);
		try {
			domain.tryInsertExactMixture(2, 2, 2,
					List.of(new FluidDomain.MaterialPortion(0, Double.NaN, mmm.units.SolverUnitScale.forCellSize(domain.config().cellSize()).massUnitsForNormalized(1.0))), 1);
			throw new AssertionError("Expected non-finite material volume rejection");
		} catch (IllegalArgumentException expected) {
			check(inventoryVolume(domain).signum()==0&&domain.totalMassUnits()==0,
					"Invalid volume published inventory");
		}
	}

	/**
	 * Exercises the service-level credit rules over the real primitives: extraction credits at
	 * most the container's free space (overshoot quanta are dropped and reported), insertion
	 * debits at most the container's balance. The combined ledger drift stays tiny and bounded.
	 */
	private static void testBucketRoundTrip() {
		FluidDomain domain = new FluidDomain(6, 4, 6, CONFIG);
		// Initial pour: a full bucket empties into the region.
		var units = mmm.units.SolverUnitScale.forCellSize(CONFIG.cellSize());
		long containerNanos = BUCKET;
		FluidDomain.ExactVolumeTransfer filled = domain.tryInsertExactVolume(3, 2, 3,
				(float) (ContainerFluidLedger.nanosToCubicMeters(containerNanos) / units.cubicMetersPerNormalizedVolume()), 3);
		check(filled.transferred(), "could not insert one bucket volume");
		containerNanos = ContainerFluidLedger.checkedSubtract(containerNanos,
				Math.min(ContainerFluidLedger.cubicMetersToNanos(units.cubicMeters(filled.volume())), containerNanos));
		long initialNanos = ContainerFluidLedger.cubicMetersToNanos(units.cubicMeters(inventoryVolumeAsDouble(domain)));
		long worstDrift = 0L;
		for (int cycle = 0; cycle < 100; cycle++) {
			FluidDomain.ExactVolumeTransfer extracted = domain.tryExtractExactVolume(3, 2, 3,
					(float) (ContainerFluidLedger.nanosToCubicMeters(BUCKET - containerNanos) / units.cubicMetersPerNormalizedVolume()), 3);
			check(extracted.transferred(), "full-bucket extraction failed");
			long taken = ContainerFluidLedger.cubicMetersToNanos(units.cubicMeters(extracted.volume()));
			containerNanos = ContainerFluidLedger.checkedAdd(containerNanos,
					Math.min(taken, BUCKET - containerNanos));

			FluidDomain.ExactVolumeTransfer reinserted = domain.tryInsertExactVolume(3, 2, 3,
					(float) (ContainerFluidLedger.nanosToCubicMeters(containerNanos) / units.cubicMetersPerNormalizedVolume()), 3);
			check(reinserted.transferred(), "re-insertion failed");
			long given = ContainerFluidLedger.cubicMetersToNanos(units.cubicMeters(reinserted.volume()));
			containerNanos = ContainerFluidLedger.checkedSubtract(containerNanos, Math.min(given, containerNanos));

			long regionNanos = ContainerFluidLedger.cubicMetersToNanos(units.cubicMeters(inventoryVolumeAsDouble(domain)));
			worstDrift = Math.max(worstDrift, Math.abs(regionNanos + containerNanos - initialNanos));
			check(containerNanos >= 0L && containerNanos <= BUCKET, "container balance left its valid range");
		}
		check(worstDrift <= 200L, "primitive round-trip drift exceeded the bound: " + worstDrift);
		System.out.println("  worst bucket round-trip drift over 100 cycles: " + worstDrift + " quanta");
	}

	private static SignedDyadic inventoryVolume(FluidDomain domain){
		var volume=SignedDyadic.ZERO;
		for(var stock:domain.exportLiquidCellState().stocks())volume=volume.add(stock.volume());
		return volume;
	}
	private static double inventoryVolumeAsDouble(FluidDomain domain){return inventoryVolume(domain).toDouble();}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static void run(String name, Runnable test) {
		test.run();
		passed++;
		System.out.println("PASS " + name);
	}
}
