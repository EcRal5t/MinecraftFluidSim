package mmm.application.checkpoint;

import mmm.fluid.FlipChemicalParticleState;
import mmm.fluid.FlipMaterialParticleState;
import mmm.fluid.FlipParticleState;
import mmm.fluid.FlipThermalParticleState;
import mmm.fluid.PendingPhaseVolumeState;
import mmm.fluid.air.GasStateSnapshot;
import mmm.granular.GranularStateSnapshot;
import mmm.model.region.RegionGeometry;
import mmm.model.region.RegionIdentity;
import mmm.model.region.RegionRuntimeIntent;
import mmm.thermal.FiniteThermalDeviceInventory;

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
