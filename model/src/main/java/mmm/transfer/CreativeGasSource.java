package mmm.transfer;

import mmm.fluid.air.BuiltinGasDefinitions;

/** Whitelisted creative gas templates; amounts are standard cubic-metre nanos. */
public final class CreativeGasSource {
	public static final long AMOUNT_NANOS = 1_000_000_000L;
	private CreativeGasSource() { }

	public static boolean isGas(CreativeSourceDescriptor descriptor) {
		return descriptor.templateStableId().startsWith("mmm:source/gas/");
	}

	/** Null denotes the committed world's ambient recipe. */
	public static String componentId(CreativeSourceDescriptor descriptor) {
		if (descriptor.requestedTypedAmount() != AMOUNT_NANOS) {
			throw new IllegalArgumentException("Gas source amount must be one standard cubic metre");
		}
		return switch (descriptor.templateStableId()) {
			case "mmm:source/gas/nitrogen" -> BuiltinGasDefinitions.NITROGEN;
			case "mmm:source/gas/oxygen" -> BuiltinGasDefinitions.OXYGEN;
			case "mmm:source/gas/co2" -> BuiltinGasDefinitions.CO2;
			case "mmm:source/gas/water_vapor" -> BuiltinGasDefinitions.WATER_VAPOR;
			case "mmm:source/gas/air" -> null;
			default -> throw new IllegalArgumentException("Unknown creative gas template");
		};
	}
}
