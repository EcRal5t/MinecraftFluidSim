package cc.ecisr.mmm.application.checkpoint;

import cc.ecisr.mmm.fluid.FlipChemicalParticleState;
import cc.ecisr.mmm.fluid.FlipMaterialParticleState;
import cc.ecisr.mmm.fluid.FlipParticleState;
import cc.ecisr.mmm.fluid.FlipThermalParticleState;
import cc.ecisr.mmm.fluid.PendingPhaseVolumeState;
import cc.ecisr.mmm.fluid.air.GasStateSnapshot;
import cc.ecisr.mmm.granular.GranularStateSnapshot;
import cc.ecisr.mmm.model.region.RegionGeometry;
import cc.ecisr.mmm.model.region.RegionIdentity;
import cc.ecisr.mmm.model.region.RegionRuntimeIntent;
import cc.ecisr.mmm.thermal.FiniteThermalDeviceInventory;

import java.io.IOException;

/** Immutable-export boundary used by checkpoint assembly; it exposes no live aggregate or loader type. */
public interface RegionCheckpointSource {
	RegionIdentity identity(long checkpointGeneration) throws IOException;
	RegionGeometry geometry();
	RegionRuntimeIntent runtimeIntent();
	FlipParticleState pureParticles();
	FlipMaterialParticleState materialParticles();
	FlipChemicalParticleState chemicalParticles();
	GranularStateSnapshot granularState();
	GasStateSnapshot gasState(String chemicalRegistryFingerprint);
	FlipThermalParticleState thermalState();
	PendingPhaseVolumeState pendingPhaseVolume();
	FiniteThermalDeviceInventory finiteThermalDevices(String thermalRegistryFingerprint);
	boolean hasMaterialState();
	boolean hasChemicalState();
	boolean hasGranularState();
	boolean hasThermalState();
}
