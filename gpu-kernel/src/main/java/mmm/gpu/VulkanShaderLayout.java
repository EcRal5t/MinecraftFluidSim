package mmm.gpu;

/** Emits shader constants from the enums that also allocate host-side columns. */
final class VulkanShaderLayout {
    private VulkanShaderLayout() {}

    static String fields(String prefix, Enum<?>[] fields) {
        var result = new StringBuilder();
        for (var field : fields)
            result.append("#define ").append(prefix).append(field.name()).append(' ').append(field.ordinal()).append('\n');
        return result.toString();
    }
}
