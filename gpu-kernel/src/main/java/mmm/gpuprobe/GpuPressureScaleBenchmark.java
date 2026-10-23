package mmm.gpuprobe;

import mmm.fluid.FluidSolverConfig;
import mmm.fluid.SparseMatrixCompute;
import java.util.Locale;

/** Same-CSR manufactured pressure scaling; CPU reference is independent Jacobi-preconditioned CG. */
public final class GpuPressureScaleBenchmark {
    private GpuPressureScaleBenchmark() { }
    record Fixture(int size,int[] starts,int[] columns,double[] values,int[] rows,
                           double[] rhs,double[] exact,double tolerance,int maximumIterations) {
        SparseMatrixCompute.PressureProblem problem() {
            return new SparseMatrixCompute.PressureProblem(size,rows,size,new boolean[size],new double[size],
                    rhs,new double[size],tolerance,maximumIterations);
        }
        long csrBytes() { return 4L*starts.length+4L*columns.length+8L*values.length+4L*rows.length; }
    }
    private record CpuResult(double[] pressure,int iterations,double rms) { }
    public static void main(String[] args) {
        if(args.length>3 && args[3].contains(",")) {
            for(String limit:args[3].split(","))main(new String[]{args[0],args[1],args[2],limit});
            return;
        }
        Locale.setDefault(Locale.ROOT);
        int device=args.length>0?Integer.parseInt(args[0]):0;
        String grids=args.length>1?args[1]:"8,16,32,64";
        int samples=args.length>2?Integer.parseInt(args[2]):3;
        if(samples<1)throw new IllegalArgumentException("At least one sample required");
        var config=FluidSolverConfig.prototypeDefaults();
        System.out.println("Pressure scale benchmark: GPU=resident Jacobi active-set PCG; CPU=independent Jacobi-PCG; unbounded manufactured 7-point Dirichlet CSR");
        System.out.println("tolerance="+config.pressureTolerance()+" maxIterations="+config.maxPressureIterations()+" samples="+samples+"; hot end-to-end includes matrix upload, solve upload, dispatch, complete pressure readback and matrix release");
        System.out.println("memory reports logical buffers; driver allocation/pipeline overhead additional");
        long contextStart=System.nanoTime();
        int singleGroupRows=args.length>3?Integer.parseInt(args[3]):16_384;
        System.out.println("single_workgroup_rows="+singleGroupRows);
        try(var gpu=new VulkanSparseMatrixCompute(device,singleGroupRows)) {
            System.out.printf("device=%s context_ms=%.3f%n",gpu.deviceName(),ms(System.nanoTime()-contextStart));
            System.out.println("grid,rows,nonzeros,cold_gpu_e2e_ms,hot_gpu_e2e_mean_ms,hot_gpu_e2e_max_ms,gpu_timestamp_mean_ms,cpu_jacobi_pcg_mean_ms,gpu_iterations,cpu_iterations,gpu_rms,cpu_rms,gpu_relative_error,cpu_relative_error,gpu_converged,cpu_converged,upload_bytes_per_solve,readback_bytes_per_solve,device_storage_bytes,host_staging_bytes,csr_cpu_bytes");
            for(String sizeText:grids.split(",")) {
                int dimension=Integer.parseInt(sizeText.trim());
                var fixture=manufacture(dimension,config.pressureTolerance(),config.maxPressureIterations());
                var problem=fixture.problem();
                // The first call includes workspace allocation and shader compilation at this capacity.
                long cold=System.nanoTime();gpuSolve(gpu,fixture,problem);cold=System.nanoTime()-cold;
                cpuSolve(fixture);
                var before=gpu.measurements();long gpuHost=0,gpuMaximum=0,cpuHost=0;
                SparseMatrixCompute.PressureSolution solution=null;CpuResult reference=null;
                for(int sample=0;sample<samples;sample++) {
                    long start=System.nanoTime();solution=gpuSolve(gpu,fixture,problem);
                    long elapsed=System.nanoTime()-start;gpuHost+=elapsed;gpuMaximum=Math.max(gpuMaximum,elapsed);
                    start=System.nanoTime();reference=cpuSolve(fixture);cpuHost+=System.nanoTime()-start;
                }
                var after=gpu.measurements();var memory=gpu.workspaceMemory();
                double gpuRms=residualRms(fixture,solution.pressure()),cpuRms=residualRms(fixture,reference.pressure());
                System.out.printf("%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%d,%.9g,%.9g,%.9g,%.9g,%s,%s,%d,%d,%d,%d,%d%n",
                        dimension,fixture.size,fixture.values.length,ms(cold),ms(gpuHost)/(double)samples,ms(gpuMaximum),
                        ms(after.gpuNanos()-before.gpuNanos())/(double)samples,ms(cpuHost)/(double)samples,
                        solution.iterations(),reference.iterations,gpuRms,cpuRms,
                        relativeError(solution.pressure(),fixture.exact),relativeError(reference.pressure,fixture.exact),
                        solution.converged(),Double.isFinite(cpuRms)&&cpuRms<=fixture.tolerance,
                        (after.uploadedBytes()-before.uploadedBytes())/samples,(after.downloadedBytes()-before.downloadedBytes())/samples,
                        memory.deviceStorageBytes(),memory.hostStagingBytes(),fixture.csrBytes());
                System.out.flush();
                if(!Double.isFinite(gpuRms)||!Double.isFinite(cpuRms))throw new AssertionError("Nonfinite manufactured residual");
                if(Math.abs(gpuRms-solution.residualRms())>Math.max(fixture.tolerance*1e-4,Math.ulp(gpuRms)*64))
                    throw new AssertionError("GPU final residual differs from independently recomputed CSR residual");
            }
        }
    }
    private static SparseMatrixCompute.PressureSolution gpuSolve(VulkanSparseMatrixCompute gpu,Fixture f,SparseMatrixCompute.PressureProblem p) {
        try(var matrix=gpu.prepare(f.size,f.starts,f.columns,f.values,f.rows,f.size)) {return matrix.solve(p);}
    }
    static Fixture manufacture(int dimension,double tolerance,int maximumIterations) {
        if(dimension<2)throw new IllegalArgumentException("Grid side must be at least two");
        int size=Math.multiplyExact(Math.multiplyExact(dimension,dimension),dimension);
        int entries=Math.subtractExact(Math.multiplyExact(7,size),Math.multiplyExact(6,dimension*dimension));
        int[] starts=new int[size+1],columns=new int[entries],rows=new int[size];
        double[] values=new double[entries],exact=new double[size],rhs=new double[size];int cursor=0;
        for(int z=0;z<dimension;z++)for(int y=0;y<dimension;y++)for(int x=0;x<dimension;x++) {
            int row=x+dimension*(y+dimension*z);rows[row]=row;starts[row]=cursor;
            if(z>0){columns[cursor]=row-dimension*dimension;values[cursor++]=-1;}
            if(y>0){columns[cursor]=row-dimension;values[cursor++]=-1;}
            if(x>0){columns[cursor]=row-1;values[cursor++]=-1;}
            columns[cursor]=row;values[cursor++]=6;
            if(x+1<dimension){columns[cursor]=row+1;values[cursor++]=-1;}
            if(y+1<dimension){columns[cursor]=row+dimension;values[cursor++]=-1;}
            if(z+1<dimension){columns[cursor]=row+dimension*dimension;values[cursor++]=-1;}
            double px=Math.PI*(x+1)/(dimension+1),py=Math.PI*(y+1)/(dimension+1),pz=Math.PI*(z+1)/(dimension+1);
            long hash=(row+1L)*0x9e3779b97f4a7c15L;hash=(hash^(hash>>>30))*0xbf58476d1ce4e5b9L;
            double noise=(hash>>>11)*0x1.0p-53-.5;
            exact[row]=1000*(Math.sin(px)*Math.sin(py)*Math.sin(pz)+.3*Math.sin(3*px)*Math.sin(2*py)*Math.sin(4*pz)+.01*noise);
        }
        starts[size]=cursor;
        var fixture=new Fixture(size,starts,columns,values,rows,rhs,exact,tolerance,maximumIterations);
        multiply(fixture,exact,rhs);return fixture;
    }
    /** Standard Jacobi-PCG with an explicit true-residual check at convergence. */
    private static CpuResult cpuSolve(Fixture f) {
        int n=f.size;double[] x=new double[n],r=f.rhs.clone(),z=new double[n],d=new double[n],q=new double[n];
        for(int i=0;i<n;i++)d[i]=z[i]=r[i]/6;
        double rz=dot(r,z),rms=Math.sqrt(dot(r,r)/n);int iteration=0;
        for(;iteration<f.maximumIterations && rms>f.tolerance;iteration++) {
            multiply(f,d,q);double denominator=dot(d,q);
            if(!(denominator>0 && Double.isFinite(denominator)&&rz>0))break;
            double alpha=rz/denominator;
            for(int i=0;i<n;i++){x[i]+=alpha*d[i];r[i]-=alpha*q[i];}
            rms=Math.sqrt(dot(r,r)/n);
            if(rms<=f.tolerance) {
                multiply(f,x,q);for(int i=0;i<n;i++)r[i]=f.rhs[i]-q[i];rms=Math.sqrt(dot(r,r)/n);
                if(rms<=f.tolerance){iteration++;break;}
                for(int i=0;i<n;i++)d[i]=z[i]=r[i]/6;
                rz=dot(r,z);continue;
            }
            for(int i=0;i<n;i++)z[i]=r[i]/6;
            double next=dot(r,z),beta=next/rz;
            for(int i=0;i<n;i++)d[i]=z[i]+beta*d[i];rz=next;
        }
        return new CpuResult(x,iteration,residualRms(f,x));
    }
    private static void multiply(Fixture f,double[] x,double[] y) {
        for(int row=0;row<f.size;row++){double value=0;for(int edge=f.starts[row];edge<f.starts[row+1];edge++)value+=f.values[edge]*x[f.columns[edge]];y[row]=value;}
    }
    private static double residualRms(Fixture f,double[] pressure) {
        double[] product=new double[f.size];multiply(f,pressure,product);double sum=0;
        for(int i=0;i<f.size;i++){double delta=f.rhs[i]-product[i];sum+=delta*delta;}return Math.sqrt(sum/f.size);
    }
    private static double relativeError(double[] actual,double[] exact) {
        double error=0,total=0;for(int i=0;i<exact.length;i++){double delta=actual[i]-exact[i];error+=delta*delta;total+=exact[i]*exact[i];}return Math.sqrt(error/total);
    }
    private static double dot(double[] left,double[] right) {double sum=0;for(int i=0;i<left.length;i++)sum+=left[i]*right[i];return sum;}
    private static double ms(long nanos) {return nanos/1_000_000.0;}
}
