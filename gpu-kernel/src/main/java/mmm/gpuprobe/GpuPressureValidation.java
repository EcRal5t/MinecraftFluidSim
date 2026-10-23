package mmm.gpuprobe;

import mmm.fluid.*;
import java.util.Arrays;
import java.util.List;

/** Real device products followed by ordinary finite-volume candidates using the same device provider. */
public final class GpuPressureValidation {
    public static void main(String[] args) {
        int device=args.length==0?0:Integer.parseInt(args[0]);
        var provider=java.util.ServiceLoader.load(SparseMatrixCompute.Provider.class).stream()
                .map(java.util.ServiceLoader.Provider::get).filter(value->value.id().equals("vulkan")).findFirst().orElseThrow();
        try(var compute=(VulkanSparseMatrixCompute)provider.create(device)) {
            double[] output={17,17,17,17};
            try(var matrix=compute.prepare(4,new int[]{0,2,5,5,7},
                    new int[]{0,1,0,1,3,1,3},new double[]{4,-1,-1,4,-1,-1,4},new int[]{0,1,3},3)) {
                matrix.multiply(new double[]{1,2,3,4},output);
                require(Arrays.equals(output,new double[]{2,3,17,14}),"GPU CSR product or inactive row differs");
                matrix.multiply(new double[]{2,3,4,5},output);
                require(Arrays.equals(output,new double[]{5,5,17,17}),"Resident GPU matrix reuse differs");
            }
            try(var matrix=compute.prepare(2,new int[]{0,2,4},new int[]{0,1,0,1},
                    new double[]{2,-1,-1,2},new int[]{0,1},2)) {
                var solution=matrix.solve(new SparseMatrixCompute.PressureProblem(2,new int[]{0,1},2,
                        new boolean[]{true,false},new double[]{0,0},new double[]{-4,2},new double[]{0,0},1e-10,100));
                require(solution!=null && solution.converged(),"Resident bounded pressure failed");
                require(Math.abs(solution.pressure()[0])<1e-10 && Math.abs(solution.pressure()[1]-1)<1e-10,
                        "Resident pressure lower limit differs");
                var released=matrix.solve(new SparseMatrixCompute.PressureProblem(2,new int[]{0,1},2,
                        new boolean[]{true,false},new double[]{0,0},new double[]{1,0},new double[]{0,0},1e-10,100));
                require(released.converged() && Math.abs(released.pressure()[0]-2.0/3)<1e-10
                        && Math.abs(released.pressure()[1]-1.0/3)<1e-10,"Pressure lower limit did not release");
            }
            var initial=new FluidDomain(8,6,6,new FluidSolverConfig(.5F,1000,9.81F,.025F,160,1e-5F,.002F));
            initial.sealDomainBoundary();
            for(int z=1;z<5;z++)for(int y=1;y<4;y++)for(int x=1;x<4;x++)initial.setVolume(x,y,z,1);
            initial.bootstrapGasState(null);
            var cpu=initial.captureAuthoritativeState();var gpu=cpu;
            var backend=new CpuFluidSolverBackend();
            var hybrid=new CpuFluidSolverBackend(compute);
            long mass=initial.totalMassUnits();
            double maxDifference=0;
            for(int step=0;step<8;step++) {
                cpu=backend.evaluate(new FluidStepRequest(step,cpu,List.of(),false,0)).state();
                gpu=hybrid.evaluate(new FluidStepRequest(step,gpu,List.of(),false,0)).state();
                var a=FluidDomain.restoreAuthoritativeState(cpu);var b=FluidDomain.restoreAuthoritativeState(gpu);
                require(a.totalMassUnits()==mass && b.totalMassUnits()==mass,"Pressure device path changed mass");
                require(a.totalVolumeExact()==b.totalVolumeExact(),"Pressure device path changed volume");
                var am=cpu.momentum();var bm=gpu.momentum();
                double[][] av={am.copyX(),am.copyY(),am.copyZ()},bv={bm.copyX(),bm.copyY(),bm.copyZ()};
                for(int axis=0;axis<3;axis++)for(int face=0;face<av[axis].length;face++)
                    maxDifference=Math.max(maxDifference,Math.abs(av[axis][face]-bv[axis][face]));
            }
            require(maxDifference<1e-7,"GPU/CPU momentum disagreement: "+maxDifference);
            require(compute.measurements().products()+compute.measurements().solves()>2,"Complete candidates did not dispatch GPU pressure work");
            require(compute.quantityMeasurements().batches()>0&&compute.quantityMeasurements().donors()>0,
                    "Complete candidates did not dispatch actual GPU liquid quantities");
            System.out.println("GPU pressure PASS | "+compute.deviceName()+" | pressure="+compute.measurements()
                    +" | quantities="+compute.quantityMeasurements()+" | max momentum difference="+maxDifference);
        }
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
