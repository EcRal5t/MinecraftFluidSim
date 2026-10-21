package mmm.fluid;

import java.util.Arrays;

/** Common double-precision PCG and incomplete Cholesky for liquid, gas and coupled solid pressure. */
final class PressureLinearSolver {
	interface RightHandSide {
		void buildRhs(double[] destination);
	}
	private final FluidDomain domain;
	private final RightHandSide rhsBuilder;
	private final PressureSparseMatrix matrix;
	private final PressurePreconditioner preconditioner;
	private double[] pressure = new double[0], rhs = new double[0], residual = new double[0], direction = new double[0];
	private double[] product = new double[0], preconditioned = new double[0];
	private int[] unknowns = new int[0], freeUnknowns = new int[0];
	private boolean[] bounded = new boolean[0], free = new boolean[0];
	private int count, freeCount;
	private double[] gasLower = new double[0];

	PressureLinearSolver(FluidDomain domain, PressureSparseMatrix matrix, RightHandSide rhsBuilder) {
		this.domain = domain;
		this.matrix = matrix;
		this.rhsBuilder = rhsBuilder;
		preconditioner = new PressurePreconditioner();
		ensureCapacity();
	}
	private void ensureCapacity() {
		int size = Math.addExact(domain.cellCount, Math.addExact(domain.projection.gasNodeCount, 1));
		if (pressure.length >= size) return;
		pressure = Arrays.copyOf(pressure, size); rhs = Arrays.copyOf(rhs, size); residual = Arrays.copyOf(residual, size);
		direction = Arrays.copyOf(direction, size); product = Arrays.copyOf(product, size);
		gasLower=Arrays.copyOf(gasLower,size);
		preconditioned = Arrays.copyOf(preconditioned, size); unknowns = Arrays.copyOf(unknowns, size);
		freeUnknowns=Arrays.copyOf(freeUnknowns,size);bounded=Arrays.copyOf(bounded,size);free=Arrays.copyOf(free,size);
	}

	double[] pressure() { return pressure; }
	void reset() {
		ensureCapacity();
		Arrays.fill(pressure, 0); Arrays.fill(direction, 0);
	}

	PressureProjectionResult solve(double target,double pressureDatum,double[] reference) {
		ensureCapacity();count=0;Arrays.fill(bounded,false);
		for(int ordinal=0;ordinal<domain.projection.fluidUnknownCount;ordinal++) {
			int cell=domain.projection.fluidUnknownCells[ordinal];unknowns[count++]=cell;
		}
		for(int ordinal=0;ordinal<domain.projection.gasUnknownCount;ordinal++) {
			int node=domain.projection.gasUnknownNodes[ordinal],row=domain.cellCount+node;unknowns[count++]=row;
			bounded[row]=true;
			gasLower[row]=-mmm.fluid.air.AirNetwork.REFERENCE_PRESSURE-pressureDatum-reference[row];
			pressure[row]=Math.max(gasLower[row],pressure[row]);
		}
		rhsBuilder.buildRhs(rhs);refreshResidual();
		Arrays.fill(free,false);int iterations=0;
		if(projectedNorm()>target){updateFreeSet(target);restartDirection();iterations=iterate(target);}
		refreshResidual();double rms=projectedNorm();
		for(int ordinal=0;ordinal<domain.projection.fluidUnknownCount;ordinal++) {
			int cell=domain.projection.fluidUnknownCells[ordinal];domain.projection.pressure[cell]=(float)(pressure[cell]+reference[cell]+pressureDatum);
		}
		for(int ordinal=0;ordinal<domain.projection.gasUnknownCount;ordinal++) {
			int node=domain.projection.gasUnknownNodes[ordinal];domain.projection.gasPressure[node]=(float)(pressure[domain.cellCount+node]+reference[domain.cellCount+node]+pressureDatum);
		}
		return new PressureProjectionResult(iterations,rms,Double.isFinite(rms)&&rms<=attainableTolerance(target));
	}
	/** Advances the active-set PCG independently of row preparation and result publication. */
	private int iterate(double target) {
		double rz=dotFree(residual,direction),rms=projectedNorm();int iterations=0;
		for(;rms>target&&iterations<domain.config.maxPressureIterations();iterations++) {
			matrix.multiply(direction,product);double denominator=dotFree(direction,product);
			if(!(denominator>0&&Double.isFinite(denominator)&&rz>0&&Double.isFinite(rz)))break;
			double alpha=rz/denominator;boolean blocked=false;
			for(int ordinal=0;ordinal<freeCount;ordinal++) {
				int row=freeUnknowns[ordinal];if(bounded[row]&&direction[row]<0) {
					double distance=(pressure[row]-gasLower[row])/-direction[row];
					if(distance<=alpha){alpha=distance;blocked=true;}
				}
			}
			for(int ordinal=0;ordinal<count;ordinal++) {
				int row=unknowns[ordinal];boolean hits=bounded[row]&&direction[row]<0
						&&(pressure[row]-gasLower[row])/-direction[row]<=alpha;
				pressure[row]=hits?gasLower[row]:pressure[row]+alpha*direction[row];residual[row]-=alpha*product[row];
				if(bounded[row]&&pressure[row]<gasLower[row])pressure[row]=gasLower[row];
			}
			rms=projectedNorm();
			boolean restart=blocked||updateFreeSet(target);
			if(rms<=target) {
				refreshResidual();rms=projectedNorm();
				if(rms<=attainableTolerance(target)){iterations++;break;}
				restart=true;
			}
			if(restart){refreshResidual();updateFreeSet(target);restartDirection();rz=dotFree(residual,direction);continue;}
			Arrays.fill(preconditioned,0);preconditioner.apply(freeUnknowns,freeCount,residual,preconditioned);
			double nextRz=dotFree(residual,preconditioned),beta=nextRz/rz;
			for(int ordinal=0;ordinal<freeCount;ordinal++){int row=freeUnknowns[ordinal];direction[row]=preconditioned[row]+beta*direction[row];}
			rz=nextRz;
		}
		return iterations;
	}
	private void refreshResidual(){
		matrix.multiply(pressure,product);
		for(int ordinal=0;ordinal<count;ordinal++){int row=unknowns[ordinal];residual[row]=rhs[row]-product[row];}
	}
	/** Pressure at its lower limit permits expansion at zero predicted absolute pressure. */
	private boolean updateFreeSet(double tolerance){
		boolean changed=false;freeCount=0;
		for(int ordinal=0;ordinal<count;ordinal++) {
			int row=unknowns[ordinal];boolean active=!bounded[row]||pressure[row]>gasLower[row]||residual[row]>tolerance;
			changed|=active!=free[row];free[row]=active;if(active)freeUnknowns[freeCount++]=row;
		}
		return changed;
	}
	private void restartDirection(){
		Arrays.fill(direction,0);if(freeCount==0)return;
		preconditioner.prepare(matrix, freeUnknowns, freeCount);
		preconditioner.apply(freeUnknowns, freeCount, residual, direction);
		// A coupled preconditioner can point out of a released lower bound. The
		// diagonal projected-gradient restart is feasible and strictly descending.
		for(int ordinal=0;ordinal<freeCount;ordinal++) {
			int row=freeUnknowns[ordinal];
			if(bounded[row]&&pressure[row]<=gasLower[row]&&direction[row]<0) {
				for(int j=0;j<freeCount;j++){int freeRow=freeUnknowns[j];direction[freeRow]=residual[freeRow]/matrix.diagonal(freeRow);}
				break;
			}
		}
	}
	private double projectedNorm(){
		double sum=0;int equations=0;
		for(int ordinal=0;ordinal<count;ordinal++) {
			int row=unknowns[ordinal];double value=residual[row];
			if(bounded[row]&&pressure[row]<=gasLower[row]&&value<=0)continue;
			equations++;
			sum+=value*value;
		}
		return Math.sqrt(sum/Math.max(1,equations));
	}
	private double dotFree(double[] a,double[] b){
		double sum=0;for(int ordinal=0;ordinal<freeCount;ordinal++){int row=freeUnknowns[ordinal];sum+=a[row]*b[row];}return sum;
	}

	/** Backward-error floor for the rounded Schur matrix-vector evaluation. */
	private double attainableTolerance(double target) {
		double maximumPressure = 0, scale = 0;int equations=0;
		for (int ordinal = 0; ordinal < count; ordinal++) maximumPressure = Math.max(
				maximumPressure, Math.abs(pressure[unknowns[ordinal]]));
		for (int ordinal = 0; ordinal < count; ordinal++) {
			int row = unknowns[ordinal];
			if(bounded[row]&&pressure[row]<=gasLower[row]&&residual[row]<=0)continue;
			equations++;
			double bound = Math.abs(rhs[row]) + 2 * matrix.diagonal(row) * maximumPressure;
			scale = Math.hypot(scale, bound);
		}
		double floor = (equations + 8.0) * Math.ulp(1.0) * 0.5 * scale / Math.sqrt(Math.max(1, equations));
		return Double.isFinite(floor) ? Math.max(target, floor) : target;
	}

}
