package cc.ecisr.mmm.simulation.persistence;

/** Stable selector for exact codec, generation publication, recovery and quarantine scenarios. */
public final class RegionRepositoryAdapterValidation {
	private RegionRepositoryAdapterValidation() { }

	public static void main(String[] args) throws Exception {
		RegionGenerationValidationScenarios.validateExactRoundTripAndMetadataUnload();
		RegionCodecValidationScenarios.validateMaterialCodec();
		RegionCodecValidationScenarios.validateChemicalCodec();
		RegionCodecValidationScenarios.validateGranularCodec();
		RegionCodecValidationScenarios.validateThermalCodec();
		RegionGenerationValidationScenarios.validateUnknownExtensions();
		RegionGenerationValidationScenarios.validateFaultMatrix();
		RegionGenerationValidationScenarios.validateStagedGeneration();
		RegionGenerationValidationScenarios.validateRemovedHeadAndQuarantine();
		RegionGenerationValidationScenarios.validateExplicitPreviousGenerationActivation();
		RegionGenerationValidationScenarios.validateCorruptionAndAtomicMoveFailure();
		RegionGenerationValidationScenarios.validateWorldConfig();
		System.out.println("Fluid region persistence validation passed");
	}
}
