package mmm.gpuprobe;

import mmm.fluid.SparseMatrixCompute;

/** Optional pressure compute provider discovered by the server runtime. */
public final class VulkanSparseMatrixProvider implements SparseMatrixCompute.Provider {
    @Override public String id() { return "vulkan"; }
    @Override public SparseMatrixCompute create(int device) { return new VulkanSparseMatrixCompute(device); }
}
