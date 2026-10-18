package mmm.fluid;

import mmm.fluid.air.GasPartitionWeights;
import java.util.List;

/** Splits physical gas control volumes with the same bounded geometric shares as inventory. */
final class GasTopologyVolumePartition {
	private GasTopologyVolumePartition() { }

	/** Uses floating-point overlap weights and a shared conservative remainder. */
	static double[] partition(double total, List<Double> weights) {
		return partition(total, GasPartitionWeights.of(weights.stream().mapToDouble(Double::doubleValue).toArray()));
	}
	static double[] partition(double total, GasPartitionWeights weights) {
		try { return weights.partitionControlVolume(total); }
		catch (GasPartitionWeights.UnrepresentableVolumeException unavailable) {
			throw new GasTopologyBlockedException("Gas topology volume partition unavailable: " + unavailable.getMessage(), unavailable);
		}
	}
    static double absoluteVolume(List<Double> leaves) { return leaves.stream().mapToDouble(Double::doubleValue).sum(); }
}
