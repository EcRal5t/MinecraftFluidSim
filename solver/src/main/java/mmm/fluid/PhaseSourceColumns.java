package mmm.fluid;

import java.util.Arrays;

/** Sparse primitive source quantities in local phase rows; one arena is reused per transport pass. */
final class PhaseSourceColumns {
	private int[] head,next=new int[32];
	private long[] keys=new long[32];
	private double[] amounts=new double[32];
	private int rows,entries;

	PhaseSourceColumns(int rows) { reset(rows); }

	void reset(int count) {
		if(count<0)throw new IllegalArgumentException("Negative phase source row count");
		if(head==null||head.length<count)head=new int[count];
		rows=count;entries=0;
		Arrays.fill(head,0,count,-1);
	}

	int first(int row) { return head[row]; }
	int next(int entry) { return next[entry]; }
	long key(int entry) { return keys[entry]; }
	double amount(int entry) { return amounts[entry]; }
	int entryCount() { return entries; }
	int rowCount() { return rows; }
	void clear(int row) { head[row]=-1; }

	/** Zero quantities retain legal expansion candidates without allocating a boxed map entry. */
	int add(int row,long key,double amount) {
		if(!(amount>=0&&Double.isFinite(amount)))throw new IllegalArgumentException("Invalid phase source quantity");
		for(int entry=head[row];entry>=0;entry=next[entry])if(keys[entry]==key){
			amounts[entry]+=amount;
			return entry;
		}
		if(entries==keys.length){
			int capacity=Math.multiplyExact(keys.length,2);
			next=Arrays.copyOf(next,capacity);keys=Arrays.copyOf(keys,capacity);amounts=Arrays.copyOf(amounts,capacity);
		}
		int entry=entries++;
		keys[entry]=key;amounts[entry]=amount;next[entry]=head[row];head[row]=entry;
		return entry;
	}

	double rowSum(int row) {
		double sum=0,error=0;
		for(int entry=head[row];entry>=0;entry=next[entry]){
			double corrected=amounts[entry]-error,nextSum=sum+corrected;
			error=(nextSum-sum)-corrected;sum=nextSum;
		}
		return sum;
	}

	/** Converts local geometric shares to the final phase volume; the largest source keeps roundoff. */
	void scale(int row,double target) {
		if(!(target>=0&&Double.isFinite(target)))throw new IllegalArgumentException("Invalid final phase volume");
		if(target==0){for(int entry=head[row];entry>=0;entry=next[entry])amounts[entry]=0;return;}
		double total=rowSum(row);int largest=-1;
		for(int entry=head[row];entry>=0;entry=next[entry])
			if(largest<0||amounts[entry]>amounts[largest])largest=entry;
		if(largest<0||!(total>0))throw new IllegalStateException("Positive phase volume has no transported source");
		double assigned=0,error=0;
		for(int entry=head[row];entry>=0;entry=next[entry])if(entry!=largest){
			amounts[entry]=(amounts[entry]/total)*target;
			double corrected=amounts[entry]-error,nextSum=assigned+corrected;
			error=(nextSum-assigned)-corrected;assigned=nextSum;
		}
		amounts[largest]=target-assigned;
		if(amounts[largest]<0)throw new IllegalStateException("Phase source normalization exceeded its total");
	}
}
