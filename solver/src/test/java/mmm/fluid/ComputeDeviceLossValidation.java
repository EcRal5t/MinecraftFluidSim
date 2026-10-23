package mmm.fluid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Device loss discards a private candidate and restarts exactly its original request on CPU. */
public final class ComputeDeviceLossValidation {
    public static void main(String[] args) {
        var domain = new FluidDomain(2, 2, 2,
                new FluidSolverConfig(.5F, 1000, 0, .025F, 200, 1e-5F, .002F));
        domain.setVolumes(new float[]{1, .75F, .5F, .25F, .5F, .25F, 0, 0});
        domain.domainFlowFields.preparePhysicalState();
        domain.mac.velocityX[domain.domainFlowFields.xFaceIndex(1, 0, 0)] = .02F;
        domain.cellMacStage.publishMomentum();
        var source = domain.captureAuthoritativeState();
        var request = new FluidStepRequest(0, source, List.of(), false, 0);
        var expected = new CpuFluidSolverBackend().evaluate(request).claimPreparedCpuDomain();
        var device = new FailingCompute(true);
        var notices = new ArrayList<String>();
        try (var backend = new CpuFluidSolverBackend(device, notices::add)) {
            var recovered = backend.evaluate(request).claimPreparedCpuDomain();
            same(expected, recovered);
            if (!backend.executionDescription().contains("CPU after Vulkan device loss") || notices.size() != 1)
                throw new AssertionError("Device isolation status or recovery notice missing");
            var next = new FluidStepRequest(1, recovered.captureAuthoritativeState(), List.of(), false, 0);
            same(new CpuFluidSolverBackend().evaluate(next).claimPreparedCpuDomain(),
                    backend.evaluate(next).claimPreparedCpuDomain());
            if (device.prepares != 1 || device.matrixCloses != 1 || device.closes != 1)
                throw new AssertionError("Lost device was reused or resources were released repeatedly");
        }
        if (device.closes != 1 || !source.liquid().stocks().equals(domain.exportLiquidCellState().stocks())
                || !Arrays.equals(source.momentum().copyX(), domain.exportMacMomentum().copyX()))
            throw new AssertionError("Recovery altered the committed source or repeated device destruction");
        var ordinaryFailure = new FailingCompute(false);
        try (var backend = new CpuFluidSolverBackend(ordinaryFailure)) {
            try {
                backend.evaluate(request);
                throw new AssertionError("An ordinary compute error silently retried on CPU");
            } catch (FluidSolverBackend.CandidateEvaluationRejectedException expectedFailure) {
                if (!expectedFailure.getMessage().contains("ordinary compute failure")) throw expectedFailure;
            }
            if (ordinaryFailure.closes != 0 || ordinaryFailure.prepares != 1
                    || !backend.executionDescription().contains("hybrid"))
                throw new AssertionError("An ordinary compute error quarantined the provider");
        }
        if (ordinaryFailure.closes != 1) throw new AssertionError("Provider lifetime leaked");
        System.out.println("Device-loss candidate restart, continued CPU execution and failure isolation passed");
    }

    private static void same(FluidDomain expected, FluidDomain actual) {
        if (!expected.exportLiquidCellState().stocks().equals(actual.exportLiquidCellState().stocks())
                || expected.totalMassUnits() != actual.totalMassUnits()
                || !Arrays.equals(expected.exportMacMomentum().copyX(), actual.exportMacMomentum().copyX())
                || !Arrays.equals(expected.exportMacMomentum().copyY(), actual.exportMacMomentum().copyY())
                || !Arrays.equals(expected.exportMacMomentum().copyZ(), actual.exportMacMomentum().copyZ()))
            throw new AssertionError("Recovered full candidate differs from the original request's CPU result");
    }

    private static final class FailingCompute implements SparseMatrixCompute {
        private final boolean lost;
        int prepares, matrixCloses, closes;
        FailingCompute(boolean lost) { this.lost = lost; }
        @Override public Matrix prepare(int size, int[] starts, int[] columns, double[] values, int[] rows, int count) {
            prepares++;
            return new Matrix() {
                @Override public PressureSolution solve(PressureProblem problem) {
                    if (lost) throw new DeviceLostException("fake vkQueueSubmit failed with VK_ERROR_DEVICE_LOST");
                    throw new IllegalStateException("ordinary compute failure");
                }
                @Override public void multiply(double[] source, double[] destination) {
                    throw new AssertionError("Resident solve unexpectedly reached per-product execution");
                }
                @Override public void close() { matrixCloses++; }
            };
        }
        @Override public void close() { closes++; }
    }
}
