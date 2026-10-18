package mmm.simulation.persistence;

/** Stable selector for exact codec, generation publication, recovery and quarantine scenarios. */
public final class RegionRepositoryAdapterValidation {
	private RegionRepositoryAdapterValidation() { }

	public static void main(String[] args) throws Exception {
		RegionCodecValidationScenarios.validateCellMacCodec();
		if(java.util.Arrays.asList(args).contains("--cell-mac")){System.out.println("Cell/MAC persistence validation passed");return;}
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
