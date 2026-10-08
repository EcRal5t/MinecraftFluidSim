package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.units.ExactVolume;
import java.util.Arrays;
import java.util.TreeMap;

/** Physical phase labels and exact mass densities of the original P0 inventory field. */
final class LiquidReconstructionInventories {
	final int[] phases;
	final Fraction[] densities;
	private final FluidDomain domain;
	private final LiquidGeometryState input;
	private final FluidMaterialRegistry materials;
	private final long revision;
	private final int[] markerMaterials;
	private final ExactVolume[] volumes; private final SignedDyadic[] masses;
	LiquidReconstructionInventories(FluidDomain domain) {
		this.domain = domain; input = domain.liquidGeometryState; materials = domain.materialRegistry; revision = domain.carrierMutationRevision();
		LiquidGeometryStateMapper.requireCompatible(input, domain);
		phases = new int[input.cells().size()]; Arrays.fill(phases, -1); densities = new Fraction[phases.length];
		markerMaterials = new int[domain.particles.count()]; volumes = new ExactVolume[markerMaterials.length]; masses = new SignedDyadic[markerMaterials.length];
		var ownerPhases = new TreeMap<Integer, Integer>(); var ownerMass = new TreeMap<Integer, Fraction>(); var ownerVolume = new TreeMap<Integer, Fraction>();
		var markers = input.copyMarkerCells(); var owners = input.copyFragmentOwners(); var cells = LiquidGeometryStateMapper.restore(input);
		for (int f = 0; f < cells.size(); f++) ownerVolume.merge(owners[f], cells.get(f).geometry().measure().volume(), Fraction::add);
		for (int p = 0; p < markers.length; p++) {
			int material = domain.particles.materialHandle(p), phase = materials == null ? 0 : materials.phaseHandle(material);
			markerMaterials[p] = material; volumes[p] = domain.particles.exactVolume(p); masses[p] = domain.particles.exactMass(p);
			var previous = ownerPhases.putIfAbsent(markers[p], phase);
			if (previous != null && previous != phase) throw new IllegalArgumentException("A liquid inventory group contains incompatible physical phases");
			ownerMass.merge(markers[p], Fraction.of(masses[p]), Fraction::add);
		}
		var identities = new TreeMap<Integer, Integer>();
		for (var entry : ownerPhases.entrySet()) {
			int owner = entry.getKey(); phases[owner] = identities.computeIfAbsent(entry.getValue(), ignored -> owner);
			densities[owner] = ownerMass.get(owner).divide(ownerVolume.get(owner));
		}
	}
	void requireSource(FluidDomain current) {
		if (current != domain || current.liquidGeometryState != input || current.materialRegistry != materials
				|| current.carrierMutationRevision() != revision || current.particles.count() != markerMaterials.length)
			throw new IllegalStateException("Physical phase reconstruction refers to an obsolete inventory field");
		for (int p = 0; p < markerMaterials.length; p++) if (current.particles.materialHandle(p) != markerMaterials[p]
				|| !current.particles.exactVolume(p).equals(volumes[p]) || !current.particles.exactMass(p).equals(masses[p]))
			throw new IllegalStateException("Physical phase reconstruction stock changed after planning");
		LiquidGeometryStateMapper.requireCompatible(input, current);
	}
}
