package mmm.fluid;

import mmm.chemistry.ExactRational;
import mmm.fluid.air.ExactGasPartitionWeights;
import java.util.List;

/** Observes physical gas control volumes from the same exact geometric measures as inventory. */
final class GasTopologyVolumePartition {
	private GasTopologyVolumePartition() { }

	/** Uses actual polyhedron overlaps without quantizing their weights to dyadic observations. */
	static double[] partitionRational(double total, List<ExactRational> weights) {
		return partitionRational(total, ExactGasPartitionWeights.ofRationals(weights));
	}
	static double[] partitionRational(double total, ExactGasPartitionWeights weights) {
		try { return weights.partitionControlVolume(total); }
		catch (IllegalArgumentException | ArithmeticException unavailable) {
			throw new GasTopologyBlockedException("Gas topology volume partition unavailable: " + unavailable.getMessage());
		}
	}
	static double absoluteRational(List<ExactRational> leaves) {
		try { return ExactGasPartitionWeights.ofTerms(List.of(leaves)).weightVolume(0); }
		catch (IllegalArgumentException | ArithmeticException unavailable) {
			throw new GasTopologyBlockedException("Ambient capture volume unavailable: " + unavailable.getMessage());
		}
	}

}
