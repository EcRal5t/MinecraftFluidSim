package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Finite phase quantities retain exact margins and the existing shared-face geometric support. */
final class LiquidFinitePhaseVolumeProjection {
	private LiquidFinitePhaseVolumeProjection() { }
	static LiquidJointInterfaceVolume.PartitionResult project(List<LiquidMomentReconstruction.Leaf> leaves,
			Map<Integer, Fraction> targets, List<Map<Integer, Fraction>> reference, List<Fraction> quantum,
			int maximumRegions, LiquidSupportVolumeFit.Work work) {
		var certified = new HashSet<Integer>(); int bits = 1;
		for (;;) {
			try {
				var local = LiquidPhaseVolumeRounding.round(reference, targets, bits, quantum);
				return LiquidJointInterfaceVolume.project(leaves, targets, local, maximumRegions);
			} catch (LiquidInterfaceResolutionRequired unresolved) {
				// Exact original local volumes distinguish quantity rounding from a lack of geometric support.
				for (int cell : unresolved.cells()) if (!certified.contains(cell)) {
					work.context = ", localPhaseVolumeBits=" + bits + ", referenceLeaf=" + cell;
					work.charge(); var leaf = leaves.get(cell);
					try {
						LiquidJointInterfaceVolume.project(leaf.open(), leaf.phases(),
								leaf.phases().stream().map(phase -> reference.get(cell).get(phase.control())).toList(), maximumRegions);
					} catch (LiquidInterfaceResolutionRequired spatial) { throw unresolved; }
					certified.add(cell);
				}
				if (bits == 1074) throw new IllegalStateException("Finite phase quantities exceed shared-face coordinate resolution", unresolved);
				work.context = ", localPhaseVolumeBits=" + bits; work.charge();
				bits = Math.min(1074, 2 * bits);
			}
		}
	}
}
