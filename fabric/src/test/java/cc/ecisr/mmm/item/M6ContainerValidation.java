package cc.ecisr.mmm.item;

import cc.ecisr.mmm.chemistry.BuiltinChemicalDefinitions;
import cc.ecisr.mmm.fluid.FlipFluidDomain;
import cc.ecisr.mmm.fluid.FlipMaterialParticleState;
import cc.ecisr.mmm.fluid.FlipParticleState;
import cc.ecisr.mmm.fluid.FluidMaterial;
import cc.ecisr.mmm.fluid.FluidSolverConfig;
import cc.ecisr.mmm.granular.BuiltinGranularDefinitions;
import cc.ecisr.mmm.granular.GranularRegistry;
import cc.ecisr.mmm.granular.SolidCompositionRecord;
import cc.ecisr.mmm.granular.SolidVolume;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Standalone M6 container numeric-representation gate. Answers the pre-implementation questions:
 * long-quanta container arithmetic is exact, conversions from real solver marker volumes round
 * within one half quantum, and simulated container↔region round-trips stay inside a documented
 * drift bound without ever producing negative volumes or non-finite values.
 */
public final class M6ContainerValidation {
	private static final FluidSolverConfig CONFIG = new FluidSolverConfig(
			0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F, 48, 1.0E-4F, 0.40F, 2.0E-3F, 0.80F);
	private static final long BUCKET = ContainerFluidLedger.NANOS_PER_BLOCK;
	private static int passed;

	private M6ContainerValidation() { }

	public static void main(String[] args) throws Exception {
		run("ledger arithmetic is exact and overflow-checked", M6ContainerValidation::testArithmetic);
		run("conversions round real marker volumes within half a quantum", M6ContainerValidation::testConversions);
		run("bucket canonicalization boundaries", M6ContainerValidation::testCanonicalization);
		run("round-trip drift stays bounded over ten thousand cycles", M6ContainerValidation::testRoundTrip);
		run("exact insert plans or refuses atomically", M6ContainerValidation::testExactInsert);
		run("exact extract removes exactly or refuses", M6ContainerValidation::testExactExtract);
		run("sub-micro residual and independent material mass survive exact transfer", M6ContainerValidation::testExactMaterialMass);
		run("multi-material container and solver transfer close every component ledger", M6ContainerValidation::testMixtureTransfer);
		run("malformed mixture mass and sub-float portions are rejected", M6ContainerValidation::testMixtureHardening);
		run("granular contents are canonical and registry-bound", M6ContainerValidation::testGranularContents);
		run("thermal contents bind raw energy to exact liquid truth", M6ContainerValidation::testThermalContents);
		run("full bucket round-trips through the primitives", M6ContainerValidation::testBucketRoundTrip);
		System.out.println("M6ContainerValidation passed " + passed + " scenarios");
	}

	private static void testThermalContents() {
		String fingerprint = "a".repeat(64);
		FluidMixture mixture = new FluidMixture(List.of(
				new FluidMixture.Component("mmm:water", 750_000_000L,
						Double.doubleToRawLongBits(750.0)),
				new FluidMixture.Component("mmm:oil", 250_000_000L,
						Double.doubleToRawLongBits(200.0))));
		ThermalContents contents = ThermalContents.forMixture(fingerprint, mixture, -12_345.25);
		check(ThermalContents.decode(contents.encode()).equals(contents),
				"thermal contents canonical round-trip changed identity or raw U");
		ThermalContents.EnergySplit split = contents.splitEnergy(333_333_333L, BUCKET);
		check(Double.doubleToRawLongBits(split.selectedEnergy() + split.remainingEnergy())
				== Double.doubleToRawLongBits(contents.internalEnergy()),
				"thermal contents split did not return the raw U remainder");
		FluidMixture changed = new FluidMixture(List.of(
				new FluidMixture.Component("mmm:water", 750_000_001L,
						Double.doubleToRawLongBits(750.0)),
				new FluidMixture.Component("mmm:oil", 249_999_999L,
						Double.doubleToRawLongBits(200.0))));
		check(!ThermalContents.forMixture(fingerprint, changed, contents.internalEnergy()).underlyingHash()
				.equals(contents.underlyingHash()), "thermal contents hash ignored an exact liquid-ledger change");
	}

	private static void testGranularContents() {
		GranularRegistry registry = BuiltinGranularDefinitions.createRegistry();
		SolidCompositionRecord solid = SolidCompositionRecord.fromStableAmounts(
				BuiltinChemicalDefinitions.REGISTRY, java.util.Map.of(
						BuiltinChemicalDefinitions.CL_MINUS, 640L,
						BuiltinChemicalDefinitions.NA_PLUS, 640L), 0.0);
		List<GranularContents.SpeciesAmount> species = java.util.Map.of(
				BuiltinChemicalDefinitions.CL_MINUS, 640L,
				BuiltinChemicalDefinitions.NA_PLUS, 640L).entrySet().stream()
				.sorted(java.util.Map.Entry.comparingByKey())
				.map(entry -> new GranularContents.SpeciesAmount(entry.getKey(), entry.getValue())).toList();
		GranularContents contents = new GranularContents(registry.fingerprint(), SolidVolume.SVQ_PER_BV, List.of(
				new GranularContents.Group(BuiltinGranularDefinitions.FINE_SAND, 65_536L, 127.5),
				new GranularContents.Group(BuiltinGranularDefinitions.PRECIPITATE, 10L,
						Double.doubleToRawLongBits(solid.totalMass(BuiltinChemicalDefinitions.REGISTRY)),
						Double.doubleToRawLongBits(0.0), species)));
		contents.validateAgainst(registry, BuiltinChemicalDefinitions.REGISTRY);
		GranularContents decoded = GranularContents.decode(contents.encode());
		check(contents.equals(decoded) && decoded.totalSolidVolumeSvq() == 65_546L,
				"granular contents canonical round-trip changed exact inventory");
		try {
			new GranularContents("0".repeat(64), SolidVolume.SVQ_PER_BV, decoded.groups())
					.validateAgainst(registry, BuiltinChemicalDefinitions.REGISTRY);
			throw new AssertionError("Expected foreign granular registry rejection");
		} catch (IllegalArgumentException expected) {
			// expected
		}
	}

	private static void testArithmetic() {
		check(ContainerFluidLedger.volumeToNanos(0.0) == 0L, "empty volume converted");
		check(ContainerFluidLedger.volumeToNanos(1.0) == BUCKET, "one block volume is one bucket");
		check(ContainerFluidLedger.volumeToNanos(0.5) == BUCKET / 2L, "half bucket converted");
		check(ContainerFluidLedger.nanosToVolume(BUCKET) == 1.0, "bucket converted back");
		check(ContainerFluidLedger.nanosToFloatVolume(BUCKET / 4L) == 0.25F, "quarter converts to an exact float");

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
				() -> ContainerFluidLedger.volumeToNanos(-0.1),
				() -> ContainerFluidLedger.volumeToNanos(Double.NaN),
				() -> ContainerFluidLedger.volumeToNanos(Double.POSITIVE_INFINITY),
				() -> ContainerFluidLedger.volumeToNanos(1.0E19),
				() -> ContainerFluidLedger.nanosToVolume(-1L),
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
		FlipFluidDomain domain = new FlipFluidDomain(6, 4, 6, CONFIG, 512);
		domain.setVolume(1, 1, 1, 0.25F);
		domain.setVolume(2, 1, 2, 1.0F);
		domain.setVolume(4, 2, 4, 0.625F);
		domain.setVolume(3, 2, 1, 1.0F / 3.0F);
		domain.setVolume(5, 1, 3, 0.1F);
		FlipParticleState state = domain.exportParticleState();
		check(state.particleCount() > 0, "the solver produced no markers to convert");
		float[] markers = state.copyPositionVolume();
		double exactSum = 0.0;
		long summedQuanta = 0L;
		for (int i = 0; i < state.particleCount(); i++) {
			float volume = markers[i * 4 + 3];
			check(volume > 0.0F && Float.isFinite(volume), "marker volume is not positive and finite");
			long quanta = ContainerFluidLedger.volumeToNanos(volume);
			check(Math.abs(quanta - volume * 1_000_000_000.0) <= 0.5, "conversion exceeded half a quantum");
			float roundTrip = ContainerFluidLedger.nanosToFloatVolume(quanta);
			check(Math.abs(roundTrip - volume) <= Math.ulp(volume) * 2.0F, "float round-trip exceeded float precision");
			exactSum += volume;
			summedQuanta += quanta;
		}
		// Converting the exact sum vs summing conversions may differ by at most half a quantum per marker.
		long sumQuanta = ContainerFluidLedger.volumeToNanos(exactSum);
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

	/**
	 * Simulates the planned transfer primitive: greedy full-marker removal with a float remainder
	 * split, quanta conversion at the container, and float re-creation on insert. The combined
	 * region+container ledger must stay within a small documented bound over 10,000 cycles.
	 */
	private static void testRoundTrip() {
		Random random = new Random(2_626L);
		List<Float> region = new ArrayList<>(512);
		for (int i = 0; i < 256; i++) region.add(0.015625F);
		double initialVolume = region.stream().mapToDouble(Float::doubleValue).sum();
		long containerNanos = 0L;
		long worstDriftNanos = 0L;
		for (int cycle = 0; cycle < 10_000; cycle++) {
			if (containerNanos == 0L || (random.nextBoolean() && !region.isEmpty())) {
				double availableVolume = region.stream().mapToDouble(Float::doubleValue).sum();
				long request = 1L + random.nextInt((int) Math.max(1L,
						Math.min(BUCKET, Math.round(availableVolume * 1_000_000_000.0))));
				double taken = 0.0;
				while (!region.isEmpty() && Math.round(taken * 1_000_000_000.0) < request) {
					float marker = region.getLast();
					double need = request / 1_000_000_000.0 - taken;
					float remainder = (float) (marker - need);
					if (remainder > 0.0F && remainder < marker) {
						region.set(region.size() - 1, remainder);
						taken += marker - remainder;
					} else {
						region.removeLast();
						taken += marker;
					}
				}
				containerNanos = ContainerFluidLedger.checkedAdd(containerNanos, ContainerFluidLedger.volumeToNanos(taken));
			} else {
				long give = Math.min(containerNanos, BUCKET);
				region.add(ContainerFluidLedger.nanosToFloatVolume(give));
				containerNanos = ContainerFluidLedger.checkedSubtract(containerNanos, give);
			}
			double regionVolume = region.stream().mapToDouble(Float::doubleValue).sum();
			check(regionVolume >= 0.0 && Double.isFinite(regionVolume) && containerNanos >= 0L,
					"ledger produced a negative or non-finite value");
			long drift = Math.abs(Math.round((regionVolume + containerNanos / 1_000_000_000.0 - initialVolume) * 1_000_000_000.0));
			worstDriftNanos = Math.max(worstDriftNanos, drift);
		}
		check(worstDriftNanos <= 2_000L,
				"round-trip drift exceeded the documented bound: " + worstDriftNanos + " quanta");
		System.out.println("  worst combined drift over 10000 cycles: " + worstDriftNanos + " quanta ("
				+ String.format("%.3e", worstDriftNanos / 1.0E9) + " m^3)");
	}

	private static void testExactInsert() {
		FlipFluidDomain domain = new FlipFluidDomain(6, 4, 6, CONFIG, 512);
		domain.setSolid(0, 0, 0, true);
		FlipFluidDomain.ExactVolumeTransfer inserted = domain.tryInsertExactVolume(3, 2, 3, 0.375F, 2);
		check(inserted.transferred() && Math.abs(inserted.volume() - 0.375) < 1.0E-9,
				"exact insert did not report the planned volume");
		check(Math.abs(sumMarkerVolumes(domain) - inserted.volume()) < 1.0E-9,
				"marker ledger did not gain the inserted volume");

		int particlesBefore = domain.particleCount();
		double volumesBefore = sumMarkerVolumes(domain);
		FlipFluidDomain.ExactVolumeTransfer oversized = domain.tryInsertExactVolume(3, 2, 3, 100.0F, 3);
		check(!oversized.transferred(), "insert beyond open capacity was accepted");
		check(domain.particleCount() == particlesBefore && Math.abs(sumMarkerVolumes(domain) - volumesBefore) < 1.0E-9,
				"refused insert left partial state");

		FlipFluidDomain tiny = new FlipFluidDomain(6, 4, 6, CONFIG, 4);
		FlipFluidDomain.ExactVolumeTransfer budget = tiny.tryInsertExactVolume(3, 2, 3, 1.0F, 2);
		check(!budget.transferred() && tiny.particleCount() == 0 && sumMarkerVolumes(tiny) == 0.0,
				"marker-budget refusal left partial state");
		check(tiny.tryInsertExactVolume(3, 2, 3, 0.0F, 2).transferred(),
				"a zero-volume insert is a successful no-op");
	}

	private static void testExactExtract() {
		FlipFluidDomain domain = new FlipFluidDomain(6, 4, 6, CONFIG, 512);
		domain.tryInsertExactVolume(3, 2, 3, 0.375F, 2);
		double before = sumMarkerVolumes(domain);
		int particlesBefore = domain.particleCount();

		FlipFluidDomain.ExactVolumeTransfer oversized = domain.tryExtractExactVolume(3, 2, 3, 0.5F, 2);
		check(!oversized.transferred(), "extract beyond availability was accepted");
		check(domain.particleCount() == particlesBefore && sumMarkerVolumes(domain) == before,
				"refused extract left partial state");

		FlipFluidDomain.ExactVolumeTransfer extracted = domain.tryExtractExactVolume(3, 2, 3, 0.25F, 2);
		check(extracted.transferred() && Math.abs(extracted.volume() - 0.25) < 1.0E-12,
				"whole-marker extraction was not exact");
		check(Math.abs(sumMarkerVolumes(domain) - (before - extracted.volume())) < 1.0E-12,
				"marker ledger did not lose the extracted volume");
		check(domain.tryExtractExactVolume(3, 2, 3, 0.0F, 2).transferred(),
				"a zero-volume extract is a successful no-op");
	}

	private static void testExactMaterialMass() {
		FlipFluidDomain residual = new FlipFluidDomain(4, 4, 4, CONFIG, 64);
		FlipFluidDomain.ExactVolumeTransfer seeded = residual.tryInsertExactVolume(2, 2, 2, 5.0E-7F, 0);
		check(seeded.transferred(), "could not seed a sub-micro marker");
		FlipFluidDomain.ExactVolumeTransfer tiny = residual.tryExtractExactVolume(2, 2, 2, 1.0E-7F, 0);
		check(tiny.transferred() && residual.totalVolumeExact() > 0.0
				&& residual.totalVolumeExact() < 5.0E-7,
				"exact extraction zeroed a positive sub-micro residual: transferred=" + tiny.transferred()
						+ ", removed=" + tiny.volume() + ", residual=" + residual.totalVolumeExact());

		FlipFluidDomain domain = new FlipFluidDomain(4, 4, 4, CONFIG, 80);
		domain.setVolume(1, 1, 1, 0.125F);
		FlipParticleState pure = domain.exportParticleState();
		List<FluidMaterial> materials = List.of(
				new FluidMaterial("mmm:water", 1_000.0, "mmm:aqueous", 0.0, Math.PI / 2.0),
				new FluidMaterial("mmm:oil", 800.0, "mmm:oil", 0.0, Math.PI / 2.0));
		double[] initialMass = new double[pure.particleCount()];
		int[] initialMaterial = new int[pure.particleCount()];
		for (int i = 0; i < pure.particleCount(); i++) initialMass[i] = 125.0;
		domain.restoreMaterialParticleState(new FlipMaterialParticleState(
				pure.sizeX(), pure.sizeY(), pure.sizeZ(), pure.cellSize(), pure.particleCount(),
				domain.totalVolumeExact(), 125.0, materials, pure.copyPositionVolume(),
				pure.copyParticleVelocities(), pure.copySolidMask(), initialMass, initialMaterial));
		domain.removeAllWaterExact();

		double insertedMass = 123.456789012345;
		FlipFluidDomain.ExactVolumeTransfer inserted = domain.tryInsertExactMaterial(
				2, 2, 2, 0.375F, insertedMass, 1, 1);
		check(inserted.transferred()
				&& Double.doubleToRawLongBits(inserted.mass()) == Double.doubleToRawLongBits(domain.totalMass()),
				"material insert did not preserve its independent mass ledger");
		check(Math.abs(domain.totalMass() - 0.375 * 800.0) > 100.0,
				"material insert silently reconstructed mass from reference density");
		double massBefore = domain.totalMass();
		FlipFluidDomain.ExactVolumeTransfer extracted = domain.tryExtractExactVolume(2, 2, 2, 0.125F, 1, 1);
		check(extracted.transferred() && extracted.mass() > 0.0,
				"material extraction did not return removed mass");
		check(Math.abs(massBefore - (domain.totalMass() + extracted.mass())) <= 1.0E-12,
				"material extraction failed to close the mass ledger");
		// This call exercises the strict double volume sum used by region-state v2 handoff.
		domain.exportMaterialParticleState();
	}

	private static void testMixtureTransfer() {
		FluidMixture expected = new FluidMixture(List.of(
				new FluidMixture.Component("mmm:oil", 400_000_000L, 320.0),
				new FluidMixture.Component("mmm:water", 600_000_000L, 600.0)));
		check(FluidMixture.decode(expected.encode()).equals(expected)
				&& expected.totalVolumeNanos() == BUCKET && expected.totalMass() == 920.0,
				"canonical fluid-mixture handle did not round-trip exact ledgers");
		try {
			FluidMixture.decode("mmm:fluid-mixture;" + "x".repeat(FluidMixture.MAX_ENCODED_LENGTH));
			throw new AssertionError("Expected oversized mixture rejection");
		} catch (IllegalArgumentException expectedFailure) {
			// expected: persisted strings are bounded before splitting or allocation
		}

		FlipFluidDomain domain = new FlipFluidDomain(4, 4, 4, CONFIG, 80);
		domain.setVolume(1, 1, 1, 0.125F);
		FlipParticleState pure = domain.exportParticleState();
		List<FluidMaterial> materials = List.of(
				new FluidMaterial("mmm:water", 1_000.0, "mmm:aqueous", 0.0, Math.PI / 2.0),
				new FluidMaterial("mmm:oil", 800.0, "mmm:oil", 0.0, Math.PI / 2.0));
		domain.restoreMaterialParticleState(new FlipMaterialParticleState(
				pure.sizeX(), pure.sizeY(), pure.sizeZ(), pure.cellSize(), pure.particleCount(),
				domain.totalVolumeExact(), 125.0, materials, pure.copyPositionVolume(),
				pure.copyParticleVelocities(), pure.copySolidMask(), new double[] {125.0}, new int[] {0}));
		domain.removeAllWaterExact();

		List<FlipFluidDomain.MaterialPortion> portions = List.of(
				new FlipFluidDomain.MaterialPortion(1, 3.2, 256.125),
				new FlipFluidDomain.MaterialPortion(0, 4.8, 480.875));
		FlipFluidDomain.ExactMixtureTransfer inserted = domain.tryInsertExactMixture(2, 2, 2, portions, 2);
		double insertedVolume = inserted.portions().stream().mapToDouble(FlipFluidDomain.MaterialPortion::volume).sum();
		double insertedMass = inserted.portions().stream().mapToDouble(FlipFluidDomain.MaterialPortion::mass).sum();
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
				List.of(new FlipFluidDomain.MaterialPortion(0, 100.0, 100.0)), 1).transferred()
				&& domain.totalVolumeExact() == volumeBeforeRefusal && domain.totalMass() == massBeforeRefusal,
				"refused mixture insert left partial markers");

		FlipFluidDomain.ExactMixtureTransfer extracted = domain.tryExtractExactMixture(2, 2, 2, 8.0F, 2);
		double extractedVolume = extracted.portions().stream().mapToDouble(FlipFluidDomain.MaterialPortion::volume).sum();
		double extractedMass = extracted.portions().stream().mapToDouble(FlipFluidDomain.MaterialPortion::mass).sum();
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
					new FluidMixture.Component("mmm:oil", 1L, Double.MAX_VALUE),
					new FluidMixture.Component("mmm:water", 1L, Double.MAX_VALUE)));
			throw new AssertionError("Expected non-finite mixture total-mass rejection");
		} catch (IllegalArgumentException expected) {
			// A malformed item must be rejected during construction, before tooltip/WAL callers.
		}

		FlipFluidDomain domain = new FlipFluidDomain(4, 4, 4, CONFIG, 64);
		try {
			domain.tryInsertExactMixture(2, 2, 2,
					List.of(new FlipFluidDomain.MaterialPortion(0, 1.0E-300, 1.0)), 1);
			throw new AssertionError("Expected sub-float material volume rejection");
		} catch (IllegalArgumentException expected) {
			check(domain.particleCount() == 0 && domain.totalVolumeExact() == 0.0,
					"sub-float rejection created a zero-volume marker");
		}
	}

	/**
	 * Exercises the service-level credit rules over the real primitives: extraction credits at
	 * most the container's free space (overshoot quanta are dropped and reported), insertion
	 * debits at most the container's balance. The combined ledger drift stays tiny and bounded.
	 */
	private static void testBucketRoundTrip() {
		FlipFluidDomain domain = new FlipFluidDomain(6, 4, 6, CONFIG, 512);
		// Initial pour: a full bucket empties into the region.
		long containerNanos = BUCKET;
		FlipFluidDomain.ExactVolumeTransfer filled = domain.tryInsertExactVolume(3, 2, 3,
				ContainerFluidLedger.nanosToFloatVolume(containerNanos), 3);
		check(filled.transferred(), "could not insert one bucket volume");
		containerNanos = ContainerFluidLedger.checkedSubtract(containerNanos,
				Math.min(ContainerFluidLedger.volumeToNanos(filled.volume()), containerNanos));
		long initialNanos = ContainerFluidLedger.volumeToNanos(sumMarkerVolumes(domain));
		long worstDrift = 0L;
		for (int cycle = 0; cycle < 100; cycle++) {
			FlipFluidDomain.ExactVolumeTransfer extracted = domain.tryExtractExactVolume(3, 2, 3,
					ContainerFluidLedger.nanosToFloatVolume(BUCKET - containerNanos), 3);
			check(extracted.transferred(), "full-bucket extraction failed");
			long taken = ContainerFluidLedger.volumeToNanos(extracted.volume());
			containerNanos = ContainerFluidLedger.checkedAdd(containerNanos,
					Math.min(taken, BUCKET - containerNanos));

			FlipFluidDomain.ExactVolumeTransfer reinserted = domain.tryInsertExactVolume(3, 2, 3,
					ContainerFluidLedger.nanosToFloatVolume(containerNanos), 3);
			check(reinserted.transferred(), "re-insertion failed");
			long given = ContainerFluidLedger.volumeToNanos(reinserted.volume());
			containerNanos = ContainerFluidLedger.checkedSubtract(containerNanos, Math.min(given, containerNanos));

			long regionNanos = ContainerFluidLedger.volumeToNanos(sumMarkerVolumes(domain));
			worstDrift = Math.max(worstDrift, Math.abs(regionNanos + containerNanos - initialNanos));
			check(containerNanos >= 0L && containerNanos <= BUCKET, "container balance left its valid range");
		}
		check(worstDrift <= 200L, "primitive round-trip drift exceeded the bound: " + worstDrift);
		System.out.println("  worst bucket round-trip drift over 100 cycles: " + worstDrift + " quanta");
	}

	private static double sumMarkerVolumes(FlipFluidDomain domain) {
		FlipParticleState state = domain.exportParticleState();
		float[] markers = state.copyPositionVolume();
		double sum = 0.0;
		for (int i = 0; i < state.particleCount(); i++) sum += markers[i * 4 + 3];
		return sum;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static void run(String name, Runnable test) {
		test.run();
		passed++;
		System.out.println("PASS " + name);
	}
}
