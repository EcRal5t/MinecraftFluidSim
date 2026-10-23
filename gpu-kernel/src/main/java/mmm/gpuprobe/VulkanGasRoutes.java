package mmm.gpuprobe;

/** Resident gas routing graph shared by topology changes and finite-aperture flow.
 * Source ids are 0 vacuum, 1 ambient, 2+i inventory row; targets are 0 ambient, 1+i finite row.
 * The source and target CSR columns contain route ids. All offsets are absolute candidate-arena words.
 * Ambient volumes are actual cubic metres at the configured reservoir temperature.
 */
record VulkanGasRoutes(VulkanGasStateLayout gas,VulkanGasGeometry.Layout futureGeometry,int capacity,
                       int countWordOffset,int sourcesWordOffset,int targetsWordOffset,int weightsWordOffset,
                       int sourceOffsetsWordOffset,int sourceRoutesWordOffset,int targetOffsetsWordOffset,
                       int targetRoutesWordOffset,int ambientVolumesWordOffset,long endOffset) {
    static VulkanGasRoutes from(VulkanGasLineage.Layout l){
        return new VulkanGasRoutes(l.gas,l.futureGeometry,l.groupCapacity,Math.toIntExact(l.requiredGroupsOffset()/4),
                l.word(VulkanGasLineage.Field.GROUP_SOURCE),l.word(VulkanGasLineage.Field.GROUP_TARGET),
                l.word(VulkanGasLineage.Field.GROUP_WEIGHT),l.word(VulkanGasLineage.Field.SOURCE_OFFSETS),
                l.word(VulkanGasLineage.Field.SOURCE_GROUPS),l.word(VulkanGasLineage.Field.TARGET_OFFSETS),
                l.word(VulkanGasLineage.Field.TARGET_GROUPS),l.word(VulkanGasLineage.Field.GROUP_FUTURE_CONTROL),l.endOffset());
    }
    int[] words(){return new int[]{countWordOffset,sourcesWordOffset,targetsWordOffset,weightsWordOffset,
            sourceOffsetsWordOffset,sourceRoutesWordOffset,targetOffsetsWordOffset,targetRoutesWordOffset,
            ambientVolumesWordOffset,Math.toIntExact(futureGeometry.componentCount().offset()/4)};}
}
