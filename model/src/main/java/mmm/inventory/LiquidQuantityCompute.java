package mmm.inventory;

import java.util.List;

/** Batched donor quantity partition; exact spatial volumes remain with the inventory planner. */
public interface LiquidQuantityCompute {
	/** Fractions exclude the remainder slot; species follow the resulting actual mass shares.
	 * Arrays are borrowed read-only until partition returns. Output arrays transfer to the planner. */
	record Source(double[] fractions,int remainder,long massUnits,long[] speciesAmounts) { }
	/** Species rows retain their source order; columns follow the mass shares. */
	record Partition(long[] masses,long[][] species) { }
	List<Partition> partition(List<Source> sources);
}
