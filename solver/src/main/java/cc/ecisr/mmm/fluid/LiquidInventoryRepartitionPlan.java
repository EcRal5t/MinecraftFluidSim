package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalParcelStore;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import cc.ecisr.mmm.thermal.ThermalDomainState;
import cc.ecisr.mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Binds independent extensive inventories to the submitted mechanical control volumes. */
final class LiquidInventoryRepartitionPlan {
	record Marker(int control, int material, SignedDyadic volume, SignedDyadic mass, ChemicalMixtureRecord mixture,
			boolean indicator, float x, float y, float z, float vx, float vy, float vz, double energy) {
	}
	final LiquidGeometryState input, geometry;
	final CarrierMutationPlan intent;
	final List<Marker> markers;
	final SignedDyadic energyRoundoff;
	final Fraction[] momentumChange;
	final Fraction[] momentumBounds;
	final Fraction kineticChange;
	final Fraction kineticBound;
	final double maximumRelativeMassError;
	final double maximumTemperatureChange;
	private final long chemicalRevision, liquidEnergyRevision;
	private final FluidDomain owner;
	private final FluidMaterialRegistry materials;
	private final ChemicalParcelStore chemistry;
	private final ThermalDomainState thermal;
	private final SignedDyadic[] originalMass, originalVolume;
	private final int[] originalMaterial;

	LiquidInventoryRepartitionPlan(FluidDomain domain) {
		this(domain, domain.liquidGeometryState, null);
	}
	/** Spatial regrids supply their positive source-inventory shares and extensive mechanical moments. */
	LiquidInventoryRepartitionPlan(FluidDomain domain, LiquidGeometryState target, Map<Integer, LiquidGeometryVelocityProjection.Value> fields) {
		owner = domain; materials = domain.materialRegistry; chemistry = domain.chemicalParcels; thermal = domain.thermalState;
		input = domain.liquidGeometryState;
		LiquidGeometryStateMapper.requireCompatible(input, domain);
		intent = CarrierMutationPlan.wholesaleCount(domain.carrierMutationRevision(), domain.particles.count());
		chemicalRevision = domain.chemicalParcels == null ? -1 : domain.chemicalParcels.mutationRevision();
		liquidEnergyRevision = domain.thermalState == null ? -1 : domain.thermalState.thermalRevision().liquid();
		originalMass = new SignedDyadic[domain.particles.count()]; originalVolume = new SignedDyadic[originalMass.length]; originalMaterial = new int[originalMass.length];
		for (int p = 0; p < originalMass.length; p++) { originalMass[p] = domain.particles.exactMass(p); originalVolume[p] = domain.particles.exactVolume(p); originalMaterial[p] = domain.particles.materialHandle(p); }
		int[] owners = input.copyFragmentOwners(), controls = target.copyControlCells(), oldMarkers = input.copyMarkerCells();
		momentumChange = new Fraction[]{Fraction.ZERO, Fraction.ZERO, Fraction.ZERO};
		momentumBounds = new Fraction[]{Fraction.ZERO, Fraction.ZERO, Fraction.ZERO};
		boolean sameLayout = target == input && fields == null && Arrays.equals(owners, controls);
		boolean empty = originalMass.length == 0 && target.cells().isEmpty() && (fields == null || fields.isEmpty());
		if (sameLayout || empty) {
			geometry = target; markers = List.of(); energyRoundoff = SignedDyadic.ZERO; kineticChange = Fraction.ZERO; kineticBound = Fraction.ZERO;
			maximumTemperatureChange = 0; maximumRelativeMassError = 0; return;
		}
		var restored = LiquidGeometryStateMapper.restore(target);
		var measures = new TreeMap<Integer, LiquidExactPolygon.Measure>();
		for (int f = 0; f < restored.size(); f++) measures.merge(controls[f], restored.get(f).geometry().measure(), LiquidExactPolygon.Measure::add);
		var stocks = new TreeMap<Integer, List<Integer>>();
		for (int p = 0; p < oldMarkers.length; p++) stocks.computeIfAbsent(oldMarkers[p], ignored -> new ArrayList<>()).add(p);
		var originalMeasures = new TreeMap<Integer, LiquidExactPolygon.Measure>(); var originalCells = LiquidGeometryStateMapper.restore(input);
		for (int f = 0; f < originalCells.size(); f++) originalMeasures.merge(owners[f], originalCells.get(f).geometry().measure(), LiquidExactPolygon.Measure::add);
		if (fields == null) {
			fields = new TreeMap<>();
			for (int c : measures.keySet()) {
				var mass = Fraction.ZERO; for (int p : stocks.get(owners[c])) mass = mass.add(Fraction.of(originalMass[p]));
				var volume = measures.get(c).volume(); mass = mass.multiply(volume).divide(originalMeasures.get(owners[c]).volume());
				var momentum = new Fraction[3]; var kinetic = Fraction.ZERO; var velocity = input.cells().get(c).copyVelocity();
				for (int a = 0; a < 3; a++) { var u = Fraction.of(velocity[a]); momentum[a] = mass.multiply(u); kinetic = kinetic.add(momentum[a].multiply(u).divide(Fraction.of(2))); }
				fields.put(c, LiquidGeometryVelocityProjection.observe(volume, mass, Map.of(owners[c], volume), List.of(momentum), kinetic));
			}
		}
		if (target.rootWidthMetres() != input.rootWidthMetres() || !fields.keySet().equals(measures.keySet()))
			throw new IllegalArgumentException("Spatial inventory mapping differs from reconstructed controls");
		var out = new ArrayList<Marker>(); var width = Fraction.of(target.rootWidthMetres());
		var massBounds = new TreeMap<Integer, Fraction>();
		int span = LiquidGeometryDomainPartition.rootSpan(domain.sizeX, domain.sizeY, domain.sizeZ);
		var scale = Fraction.of(span); scale = scale.multiply(scale).multiply(scale);
		var destinationsByRow = new ArrayList<>(measures.keySet()); var columns = new LinkedHashMap<Integer, Fraction>();
		for (int p = 0; p < originalVolume.length; p++) columns.put(p, Fraction.of(originalVolume[p]).divide(scale));
		var reference = new ArrayList<Map<Integer, Fraction>>(); var quantum = new ArrayList<Fraction>();
		for (int c : destinationsByRow) {
			var volume = measures.get(c).volume(); dyadic(volume); var field = fields.get(c);
			if (!volume.equals(field.volume())) throw new IllegalArgumentException("Spatial stock mapping changed control volume");
			if (!Arrays.equals(target.cells().get(c).copyVelocity(), field.velocity())) throw new IllegalArgumentException("Spatial stock and geometric velocity observations differ");
			var row = new TreeMap<Integer, Fraction>(); var expectedMass = Fraction.ZERO; int physicalPhase = -1;
			for (var entry : field.inventoryVolumes().entrySet()) {
				var source = stocks.get(entry.getKey()); if (source == null) throw new IllegalArgumentException("Spatial stock mapping has an unknown source");
				for (int p : source) {
					int phase = materials == null ? 0 : materials.phaseHandle(originalMaterial[p]);
					if (physicalPhase >= 0 && phase != physicalPhase) throw new IllegalArgumentException("Spatial inventory mapping crosses immiscible liquid phases");
					physicalPhase = phase;
					var share = entry.getValue().multiply(columns.get(p)).divide(originalMeasures.get(entry.getKey()).volume()); row.put(p, share);
					expectedMass = expectedMass.add(share.multiply(Fraction.of(originalMass[p])).divide(columns.get(p)));
				}
			}
			if (!expectedMass.equals(field.mass())) throw new IllegalArgumentException("Spatial stock and mechanical mass measures differ");
			reference.add(row); quantum.add(volume.multiply(Fraction.of(Math.scalb(1.0, -53))));
		}
		var shares = LiquidPhaseVolumeRounding.round(reference, columns, 53, quantum);
		var localStocks = new TreeMap<LiquidInventoryStock.Key, LiquidInventoryStock>();
		for (int p = 0; p < originalVolume.length; p++) {
			var destinations = new ArrayList<LiquidInventoryStock>(); var volumes = new ArrayList<SignedDyadic>();
			for (int r = 0; r < shares.size(); r++) {
				var share = shares.get(r).get(p); if (share == null) continue;
				var key = new LiquidInventoryStock.Key(destinationsByRow.get(r), originalMaterial[p], chemistry != null && chemistry.indicator(p));
				destinations.add(localStocks.computeIfAbsent(key, LiquidInventoryStock::new)); volumes.add(dyadic(share.multiply(scale)));
			}
			if (localStocks.size() > domain.maximumParticleCount) throw new IllegalStateException("Local liquid inventory exceeds its marker budget");
			var weights = volumes.toArray(SignedDyadic[]::new); var targets = destinations.toArray(LiquidInventoryStock[]::new);
			var mass = allocateMass(originalMass[p], weights); var massQuantum = Fraction.of(SignedDyadic.of(1, mass.quantumExponent()));
			LiquidInventorySpeciesShares.accumulate(domain, p, weights, targets); LiquidInventoryThermalShares.accumulate(domain, p, weights, targets);
			for (int q = 0; q < volumes.size(); q++) {
				var stock = targets[q]; stock.volume = stock.volume.add(weights[q]); stock.mass = stock.mass.add(mass.values()[q]);
				massBounds.merge(stock.key.control(), massQuantum, Fraction::add);
			}
			for (int r = 0; r < shares.size(); r++) if (shares.get(r).containsKey(p))
				massBounds.merge(destinationsByRow.get(r), Fraction.of(originalMass[p]).multiply(quantum.get(r)).divide(columns.get(p)), Fraction::add);
		}
		double temperatureChange = 0; var exactEnergy = Fraction.ZERO; var energyBound = Fraction.ZERO;
		for (var stock : localStocks.values()) {
			int c = stock.key.control(); var center = measures.get(c).centroidCoordinates(); var u = fields.get(c).velocity();
			double energy = stock.energy.rounded(); if (!Double.isFinite(energy)) throw new IllegalStateException("Local liquid energy exceeds observation range");
			var marker = new Marker(c, stock.key.material(), stock.volume, stock.mass, LiquidInventorySpeciesShares.mixture(domain, stock), stock.key.indicator(),
					position(center[0].multiply(width), domain.sizeX, domain.config.cellSize()), position(center[1].multiply(width), domain.sizeY, domain.config.cellSize()),
					position(center[2].multiply(width), domain.sizeZ, domain.config.cellSize()), (float) u[0], (float) u[1], (float) u[2], energy);
			out.add(marker); temperatureChange = Math.max(temperatureChange, LiquidInventoryThermalShares.temperatureChange(domain, stock, marker));
			exactEnergy = exactEnergy.add(stock.energy); energyBound = energyBound.add(Fraction.of(Math.ulp(energy)).divide(Fraction.of(2)));
		}
		maximumTemperatureChange = temperatureChange;
		new LiquidInventoryState(out.stream().map(Marker::volume).toArray(SignedDyadic[]::new), out.stream().map(Marker::mass).toArray(SignedDyadic[]::new));
		var energy = SignedDyadic.ZERO; for (var marker : out) energy = energy.add(SignedDyadic.of(marker.energy));
		energyRoundoff = domain.thermalState == null ? SignedDyadic.ZERO : energy.subtract(domain.thermalState.exactLiquidEnergy(originalMass.length));
		if (!exactEnergy.equals(Fraction.of(domain.thermalState == null ? SignedDyadic.ZERO : domain.thermalState.exactLiquidEnergy(originalMass.length)))
				|| Fraction.of(energyRoundoff).absolute().compareTo(energyBound) > 0) throw new IllegalStateException("Local liquid energy exceeds conservative observation certificate");
		markers = List.copyOf(out);
		geometry = new LiquidGeometryState(target.rootWidthMetres(), target.cells(), markers.stream().mapToInt(Marker::control).toArray(), controls, controls);
		LiquidGeometryStateMapper.requireCompatible(geometry, new LiquidGeometryInventoryView() {
			public int count() { return markers.size(); }
			public double volume(int p) { return markers.get(p).volume.toDouble(); }
			public double mass(int p) { return markers.get(p).mass.toDouble(); }
			public SignedDyadic exactVolume(int p) { return markers.get(p).volume; }
			public SignedDyadic exactMass(int p) { return markers.get(p).mass; }
			public double position(int p, int a) { var m = markers.get(p); return a == 0 ? m.x : a == 1 ? m.y : m.z; }
			public double velocity(int p, int a) { var m = markers.get(p); return a == 0 ? m.vx : a == 1 ? m.vy : m.vz; }
		}, domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(), domain.geometry.cutCells.copyMicroMasks());
		var localMass = new TreeMap<Integer, Fraction>(); for (var marker : markers) localMass.merge(marker.control, Fraction.of(marker.mass), Fraction::add);
		var work = Fraction.ZERO; var workBound = Fraction.ZERO; double maximumRelative = 0;
		var kgScale = Fraction.of(domain.unitScale.cubicMetersPerNormalizedVolume());
		for (int c : destinationsByRow) {
			var field = fields.get(c); var expected = field.mass(); var bound = massBounds.get(c);
			var massError = localMass.get(c).subtract(expected);
			if (massError.absolute().compareTo(bound) > 0) throw new IllegalStateException("Local liquid mass exceeds its allocation certificate");
			maximumRelative = Math.max(maximumRelative, massError.absolute().divide(expected).rounded());
			var delta = massError.multiply(kgScale); bound = bound.multiply(kgScale);
			var velocity = field.velocity(); work = work.add(field.kineticRoundingChange().subtract(field.kineticLoss()).multiply(kgScale));
			workBound = workBound.add(field.kineticRoundingChange().absolute().multiply(kgScale));
			for (int a = 0; a < 3; a++) {
				var u = Fraction.of(velocity[a]); momentumChange[a] = momentumChange[a].add(delta.multiply(u))
						.add(expected.multiply(u).subtract(field.momentum().get(a)).multiply(kgScale));
				momentumBounds[a] = momentumBounds[a].add(bound.multiply(u.absolute())).add(field.momentumRoundingBounds().get(a).multiply(kgScale));
				work = work.add(delta.multiply(u).multiply(u).divide(Fraction.of(2))); workBound = workBound.add(bound.multiply(u).multiply(u).divide(Fraction.of(2)));
			}
		}
		kineticChange = work; kineticBound = workBound; maximumRelativeMassError = maximumRelative;
	}
	boolean changed() { return geometry != input; }
	void requireSource(FluidDomain domain) {
		if (domain != owner || domain.materialRegistry != materials || domain.chemicalParcels != chemistry || domain.thermalState != thermal
				|| domain.liquidGeometryState != input || domain.particles.count() != intent.expectedCount() || domain.carrierMutationRevision() != intent.baseRevision()
				|| chemicalRevision != (domain.chemicalParcels == null ? -1 : domain.chemicalParcels.mutationRevision())
				|| liquidEnergyRevision != (domain.thermalState == null ? -1 : domain.thermalState.thermalRevision().liquid()))
			throw new IllegalStateException("Local liquid inventory refers to an obsolete input");
		for (int p = 0; p < originalMass.length; p++) if (!domain.particles.exactMass(p).equals(originalMass[p]) || !domain.particles.exactVolume(p).equals(originalVolume[p]) || domain.particles.materialHandle(p) != originalMaterial[p])
			throw new IllegalStateException("Local liquid inventory stock changed after planning");
		LiquidGeometryStateMapper.requireCompatible(input, domain);
	}
	private static SignedDyadic dyadic(Fraction value) {
		if (value.denominator().bitCount() != 1) throw new LiquidCoordinateResolutionRequired("Local inventory requires dyadic reconstructed control volumes");
		return new SignedDyadic(value.numerator(), -value.denominator().getLowestSetBit());
	}
	private static ExactVolumeAllocation.InventoryShares allocateMass(SignedDyadic total, SignedDyadic[] volumes) {
		var allocation = ExactVolumeAllocation.allocateContinuous(total, volumes); int quantum = allocation.quantumExponent();
		var volume = SignedDyadic.ZERO; for (var part : volumes) volume = volume.add(part);
		for (var part : volumes) {
			var ideal = Fraction.of(total).multiply(Fraction.of(part)).divide(Fraction.of(volume));
			int exponent = ideal.numerator().bitLength() - ideal.denominator().bitLength();
			if ((exponent >= 0 ? ideal.numerator().compareTo(ideal.denominator().shiftLeft(exponent)) : ideal.numerator().shiftLeft(-exponent).compareTo(ideal.denominator())) < 0) exponent--;
			quantum = Math.min(quantum, exponent - 52);
		}
		return quantum == allocation.quantumExponent() ? allocation : new ExactVolumeAllocation.InventoryShares(ExactVolumeAllocation.allocate(total, volumes, quantum), quantum);
	}
	private static float position(Fraction value, int extent, float cellSize) { return Math.min((float) value.rounded(), Math.nextDown(extent * cellSize)); }
}
