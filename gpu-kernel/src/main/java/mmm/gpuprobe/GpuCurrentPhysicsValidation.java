package mmm.gpuprobe;

import mmm.fluid.ComputeExecution;
import java.util.Arrays;

/** Runs existing complete-physics scenarios with device pressure solves and actual liquid quantities. */
public final class GpuCurrentPhysicsValidation {
    public static void main(String[] args) throws Exception {
        int device=args.length==0?0:Integer.parseInt(args[0]);
        String[] selection=args.length<2?new String[]{"activity-groups,controller-gas-lineage"}:Arrays.copyOfRange(args,1,args.length);
        try(var compute=new VulkanSparseMatrixCompute(device);var scope=ComputeExecution.install(compute)) {
            try {
                Class.forName("mmm.validation.CpuValidationSuite").getMethod("main",String[].class).invoke(null,(Object)selection);
            } catch(java.lang.reflect.InvocationTargetException failed) {
                var cause=failed.getCause();
                if(cause instanceof Exception error)throw error;
                if(cause instanceof Error error)throw error;
                throw failed;
            } finally {
                System.out.println("Hybrid physics device="+compute.deviceName()+" pressure="+compute.measurements()
                        +" quantities="+compute.quantityMeasurements());
            }
            if(compute.measurements().products()+compute.measurements().solves()==0)
                throw new AssertionError("Physics selection did not dispatch GPU pressure computation");
            if(compute.quantityMeasurements().batches()==0||compute.quantityMeasurements().donors()==0)
                throw new AssertionError("Physics selection did not dispatch actual GPU liquid quantities");
        }
    }
}
