package cc.ecisr.mmm.fluid;

/** Validates world liquid definitions before publishing any domain binding. */
final class LiquidMaterialBinding {
	private LiquidMaterialBinding() { }

	static void validate(FluidDomain domain, FluidMaterialRegistry palette, MaterialRegistry definitions) {
		if (palette == null || definitions == null) throw new IllegalArgumentException("Liquid definitions are missing");
		if (domain.materialRegistry != null && !domain.materialRegistry.materials().equals(palette.materials())) {
			throw new IllegalArgumentException("A domain cannot change its liquid palette");
		}
		if (domain.materialDefinitions != null
				&& !domain.materialDefinitions.fingerprint().equals(definitions.fingerprint())) {
			throw new IllegalArgumentException("A domain cannot change its world material definitions");
		}
		for (FluidMaterial material : palette.materials()) {
			MaterialRegistry.MaterialEntry definition;
			try { definition = definitions.entry(material.id()); }
			catch (IllegalArgumentException missing) {
				throw new IllegalArgumentException("Liquid " + material.id() + " has no world material definition", missing);
			}
			if (!definition.declaresViscosity()) {
				throw new IllegalArgumentException("Liquid " + material.id() + " does not declare a dynamic viscosity domain");
			}
			if (!definition.material().equals(material)) {
				throw new IllegalArgumentException("Liquid " + material.id() + " disagrees with its world material definition");
			}
		}
	}

	static void bind(FluidDomain domain, MaterialRegistry definitions) {
		validate(domain, domain.materialRegistry, definitions);
		if (domain.materialDefinitions != null) return;
		domain.materialDefinitions = definitions;
		domain.liquidCarrierMaterialByCell = new int[domain.cellCount];
		domain.liquidCarrierVolumeByCell = new double[domain.cellCount];
		domain.gridDirty = true;
	}
}
