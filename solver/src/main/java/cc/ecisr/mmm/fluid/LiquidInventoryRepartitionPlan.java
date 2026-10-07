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
	record Marker(int control, int source, int material, float volume, double mass, ChemicalMixtureRecord mixture,
			boolean indicator, float x, float y, float z, float vx, float vy, float vz, double energy) {
		Marker withEnergy(double value) { return new Marker(control, source, material, volume, mass, mixture, indicator, x, y, z, vx, vy, vz, value); }
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
	private final double[] originalMass;
	private final float[] originalVolume;
	private final int[] originalMaterial;

	LiquidInventoryRepartitionPlan(FluidDomain domain) {
		owner = domain; materials = domain.materialRegistry; chemistry = domain.chemicalParcels; thermal = domain.thermalState;
		input = domain.liquidGeometryState;
		LiquidGeometryStateMapper.requireCompatible(input, domain);
		intent = CarrierMutationPlan.wholesaleCount(domain.carrierMutationRevision(), domain.particles.count());
		chemicalRevision = domain.chemicalParcels == null ? -1 : domain.chemicalParcels.mutationRevision();
		liquidEnergyRevision = domain.thermalState == null ? -1 : domain.thermalState.thermalRevision().liquid();
		originalMass = new double[domain.particles.count()]; originalVolume = new float[originalMass.length]; originalMaterial = new int[originalMass.length];
		for (int p = 0; p < originalMass.length; p++) { originalMass[p] = domain.particles.mass(p); originalVolume[p] = domain.particles.volume(p); originalMaterial[p] = domain.particles.materialHandle(p); }
		int[] owners = input.copyFragmentOwners(), controls = input.copyControlCells(), oldMarkers = input.copyMarkerCells();
		momentumChange = new Fraction[]{Fraction.ZERO, Fraction.ZERO, Fraction.ZERO};
		momentumBounds = new Fraction[]{Fraction.ZERO, Fraction.ZERO, Fraction.ZERO};
		if (Arrays.equals(owners, controls)) {
			geometry = input; markers = List.of(); energyRoundoff = SignedDyadic.ZERO; kineticChange = Fraction.ZERO; kineticBound = Fraction.ZERO;
			maximumTemperatureChange = 0; maximumRelativeMassError = 0; return;
		}
		var restored = LiquidGeometryStateMapper.restore(input);
		var measures = new TreeMap<Integer, LiquidExactPolygon.Measure>();
		for (int f = 0; f < restored.size(); f++) measures.merge(controls[f], restored.get(f).geometry().measure(), LiquidExactPolygon.Measure::add);
		var groups = new TreeMap<Integer, List<Integer>>();
		for (int control : measures.keySet()) groups.computeIfAbsent(owners[control], ignored -> new ArrayList<>()).add(control);
		var stocks = new TreeMap<Integer, List<Integer>>();
		for (int p = 0; p < oldMarkers.length; p++) stocks.computeIfAbsent(oldMarkers[p], ignored -> new ArrayList<>()).add(p);
		var out = new ArrayList<Marker>(); var width = Fraction.of(input.rootWidthMetres());
		var massBounds = new TreeMap<Integer, Fraction>();
		int span = LiquidGeometryDomainPartition.rootSpan(domain.sizeX, domain.sizeY, domain.sizeZ);
		var scale = Fraction.of(span); scale = scale.multiply(scale).multiply(scale);
		for (var group : groups.entrySet()) {
			var total = Fraction.ZERO;
			for (int c : group.getValue()) { dyadic(measures.get(c).volume()); total = total.add(measures.get(c).volume()); }
			var source = stocks.get(group.getKey()); var columns = new LinkedHashMap<Integer, Fraction>();
			for (int p : source) columns.put(p, Fraction.of(originalVolume[p]).divide(scale));
			var reference = new ArrayList<Map<Integer, Fraction>>(); var quantum = new ArrayList<Fraction>();
			for (int c : group.getValue()) {
				var v = measures.get(c).volume(); var row = new LinkedHashMap<Integer, Fraction>();
				for (int p : source) row.put(p, v.multiply(columns.get(p)).divide(total));
				reference.add(row); quantum.add(v.multiply(Fraction.of(Math.scalb(1.0, -53))));
			}
			var shares = LiquidPhaseVolumeRounding.round(reference, columns, 53, quantum);
			for (int p : source) {
				var destinations = new ArrayList<Integer>(); var volumes = new ArrayList<Float>();
				for (int r = 0; r < shares.size(); r++) for (float v : LiquidCellQuadrature.volumes(dyadic(shares.get(r).get(p).multiply(scale)), 1)) {
					destinations.add(group.getValue().get(r)); volumes.add(v);
				}
				if ((long) out.size() + volumes.size() > domain.maximumParticleCount) throw new IllegalStateException("Local liquid inventory exceeds its marker budget");
				var weights = volumes.stream().map(SignedDyadic::of).toArray(SignedDyadic[]::new);
				var mass = ExactVolumeAllocation.allocate(SignedDyadic.of(originalMass[p]), weights, SignedDyadic.of(Math.ulp(originalMass[p])).exponent());
				var species = LiquidInventorySpeciesShares.allocate(domain, p, weights);
				for (int q = 0; q < volumes.size(); q++) {
					double m = mass[q].toDouble();
					if (!(m > 0) || !SignedDyadic.of(m).equals(mass[q])) throw new IllegalStateException("Local liquid mass exceeds binary64 inventory precision");
					var mixture = species == null ? null : species[q].withMassResidualKilograms(domain.unitScale.kilograms(m) - species[q].exactSpeciesMassKilograms(domain.chemicalParcels.registry()).toDouble());
					int c = destinations.get(q); var center = measures.get(c).centroidCoordinates(); var u = input.cells().get(c).copyVelocity();
					out.add(new Marker(c, p, originalMaterial[p], volumes.get(q), m, mixture, domain.chemicalParcels != null && domain.chemicalParcels.indicator(p),
							position(center[0].multiply(width), domain.sizeX, domain.config.cellSize()), position(center[1].multiply(width), domain.sizeY, domain.config.cellSize()),
							position(center[2].multiply(width), domain.sizeZ, domain.config.cellSize()), (float) u[0], (float) u[1], (float) u[2], 0));
					massBounds.merge(c, Fraction.of(Math.ulp(originalMass[p])), Fraction::add);
				}
				for (int r = 0; r < shares.size(); r++) massBounds.merge(group.getValue().get(r), Fraction.of(originalMass[p]).multiply(quantum.get(r)).divide(columns.get(p)), Fraction::add);
			}
		}
		maximumTemperatureChange = LiquidInventoryThermalShares.prepare(domain, out);
		var energy = SignedDyadic.ZERO; for (var marker : out) energy = energy.add(SignedDyadic.of(marker.energy));
		energyRoundoff = domain.thermalState == null ? SignedDyadic.ZERO : energy.subtract(domain.thermalState.exactLiquidEnergy(originalMass.length));
		markers = List.copyOf(out);
		geometry = new LiquidGeometryState(input.rootWidthMetres(), input.cells(), markers.stream().mapToInt(Marker::control).toArray(), controls, controls);
		LiquidGeometryStateMapper.requireCompatible(geometry, new LiquidGeometryInventoryView() {
			public int count() { return markers.size(); }
			public double volume(int p) { return markers.get(p).volume; }
			public double mass(int p) { return markers.get(p).mass; }
			public double position(int p, int a) { var m = markers.get(p); return a == 0 ? m.x : a == 1 ? m.y : m.z; }
			public double velocity(int p, int a) { var m = markers.get(p); return a == 0 ? m.vx : a == 1 ? m.vy : m.vz; }
		}, domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(), domain.geometry.cutCells.copyMicroMasks());
		var localMass = new TreeMap<Integer, Fraction>(); for (var marker : markers) localMass.merge(marker.control, Fraction.of(marker.mass), Fraction::add);
		var work = Fraction.ZERO; var workBound = Fraction.ZERO; double maximumRelative = 0;
		var kgScale = Fraction.of(domain.unitScale.cubicMetersPerNormalizedVolume());
		for (var group : groups.entrySet()) {
			var v = Fraction.ZERO; var m = Fraction.ZERO;
			for (int c : group.getValue()) v = v.add(measures.get(c).volume());
			for (int p : stocks.get(group.getKey())) m = m.add(Fraction.of(originalMass[p]));
			for (int c : group.getValue()) {
				var expected = m.multiply(measures.get(c).volume()).divide(v); var bound = massBounds.get(c);
				var massError = localMass.get(c).subtract(expected);
				if (massError.absolute().compareTo(bound) > 0) throw new IllegalStateException("Local liquid mass exceeds its allocation certificate");
				maximumRelative = Math.max(maximumRelative, massError.absolute().divide(expected).rounded());
				var delta = massError.multiply(kgScale); bound = bound.multiply(kgScale);
				var velocity = input.cells().get(c).copyVelocity();
				for (int a = 0; a < 3; a++) {
					var u = Fraction.of(velocity[a]); momentumChange[a] = momentumChange[a].add(delta.multiply(u));
					momentumBounds[a] = momentumBounds[a].add(bound.multiply(u.absolute()));
					work = work.add(delta.multiply(u).multiply(u).divide(Fraction.of(2))); workBound = workBound.add(bound.multiply(u).multiply(u).divide(Fraction.of(2)));
				}
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
		for (int p = 0; p < originalMass.length; p++) if (domain.particles.mass(p) != originalMass[p] || domain.particles.volume(p) != originalVolume[p] || domain.particles.materialHandle(p) != originalMaterial[p])
			throw new IllegalStateException("Local liquid inventory stock changed after planning");
		LiquidGeometryStateMapper.requireCompatible(input, domain);
	}
	private static SignedDyadic dyadic(Fraction value) {
		if (value.denominator().bitCount() != 1) throw new LiquidCoordinateResolutionRequired("Local inventory requires dyadic reconstructed control volumes");
		return new SignedDyadic(value.numerator(), -value.denominator().getLowestSetBit());
	}
	private static float position(Fraction value, int extent, float cellSize) { return Math.min((float) value.rounded(), Math.nextDown(extent * cellSize)); }
}
