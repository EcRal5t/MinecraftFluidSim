package mmm.simulation.persistence;

/** Stable selector for exact codec, generation publication, recovery and quarantine scenarios. */
public final class RegionRepositoryAdapterValidation {
	private RegionRepositoryAdapterValidation() { }

	public static void main(String[] args) throws Exception {
		if (java.util.Arrays.asList(args).contains("--section-loading")) {
			RegionGenerationValidationScenarios.validateSectionLoading();
			System.out.println("Manifest discovery and incremental section loading validation passed");
			return;
		}
		RegionGenerationValidationScenarios.validatePoolFootprint();
		RegionCodecValidationScenarios.validateFarmlandJournal();
		if(java.util.Arrays.asList(args).contains("--farmland")){System.out.println("Farmland water transaction persistence validation passed");return;}
		RegionCodecValidationScenarios.validateCellMacCodec();
		if(java.util.Arrays.asList(args).contains("--cell-mac")){System.out.println("Cell/MAC persistence validation passed");return;}
		RegionGenerationValidationScenarios.validateExactRoundTripAndMetadataUnload();
		RegionGenerationValidationScenarios.validateSectionLoading();
		RegionCodecValidationScenarios.validateMaterialCodec();
		RegionCodecValidationScenarios.validateChemicalCodec();
		RegionCodecValidationScenarios.validateGranularCodec();
		RegionCodecValidationScenarios.validateThermalCodec();
		RegionGenerationValidationScenarios.validateUnknownExtensions();
		RegionGenerationValidationScenarios.validateFaultMatrix();
		RegionGenerationValidationScenarios.validateStagedGeneration();
		RegionGenerationValidationScenarios.validateRemovedHeadAndQuarantine();
		RegionGenerationValidationScenarios.validateCorruptionAndAtomicMoveFailure();
		RegionGenerationValidationScenarios.validateWorldConfig();
		System.out.println("Fluid region persistence validation passed");
	}
}
