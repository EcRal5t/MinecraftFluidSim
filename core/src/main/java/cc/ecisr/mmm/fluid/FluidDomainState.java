package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.fluid.air.GasStateSnapshot;
import cc.ecisr.mmm.granular.GranularRegistry;
import cc.ecisr.mmm.granular.GranularStateSnapshot;
import cc.ecisr.mmm.thermal.ThermalRegistry;

import java.util.Objects;

/**
 * Complete immutable authoritative state for one loader-neutral solver handoff.
 * Derived pressure, density, display meshes and scheduling state are deliberately absent.
 */
public final class FluidDomainState {
	public enum CarrierKind { PURE, MATERIAL, CHEMICAL }

	private final FluidSolverConfig config;
	private final int maximumParticleCount;
	private final boolean sealedDomainBoundary;
	private final CarrierKind carrierKind;
	private final FlipParticleState particles;
	private final FlipMaterialParticleState materials;
	private final FlipChemicalParticleState chemicals;
	private final ChemicalRegistry chemicalRegistry;
	private final GranularRegistry granularRegistry;
	private final GranularStateSnapshot granular;
	private final boolean precipitationEnabled;
	private final GasRegistry gasRegistry;
	private final GasStateSnapshot gas;
	private final ThermalRegistry thermalRegistry;
	private final FlipThermalParticleState thermal;
	private final int maximumThermalTransportSubsteps;
	private final PendingPhaseVolumeState pendingPhaseVolume;

	FluidDomainState(
			FluidSolverConfig config, int maximumParticleCount, boolean sealedDomainBoundary,
			FlipParticleState particles, FlipMaterialParticleState materials,
			FlipChemicalParticleState chemicals, ChemicalRegistry chemicalRegistry,
			GranularRegistry granularRegistry, GranularStateSnapshot granular,
			boolean precipitationEnabled, GasRegistry gasRegistry, GasStateSnapshot gas,
			ThermalRegistry thermalRegistry, FlipThermalParticleState thermal,
			int maximumThermalTransportSubsteps, PendingPhaseVolumeState pendingPhaseVolume
	) {
		this.config = Objects.requireNonNull(config, "config");
		if (maximumParticleCount <= 0) throw new IllegalArgumentException("Marker budget must be positive");
		this.maximumParticleCount = maximumParticleCount;
		this.sealedDomainBoundary = sealedDomainBoundary;
		int carriers = (particles == null ? 0 : 1) + (materials == null ? 0 : 1) + (chemicals == null ? 0 : 1);
		if (carriers != 1) throw new IllegalArgumentException("Exactly one carrier state is required");
		this.carrierKind = chemicals != null ? CarrierKind.CHEMICAL
				: materials != null ? CarrierKind.MATERIAL : CarrierKind.PURE;
		this.particles = particles;
		this.materials = materials;
		this.chemicals = chemicals;
		this.chemicalRegistry = chemicalRegistry;
		if ((chemicals == null) != (chemicalRegistry == null)
				|| chemicals != null && !chemicals.registryFingerprint().equals(chemicalRegistry.fingerprint())) {
			throw new IllegalArgumentException("Chemical carrier/registry identity mismatch");
		}
		this.granularRegistry = granularRegistry;
		this.granular = granular;
		if ((granular == null) != (granularRegistry == null)
				|| granular != null && !granular.registryFingerprint().equals(granularRegistry.fingerprint())) {
			throw new IllegalArgumentException("Granular state/registry identity mismatch");
		}
		if (precipitationEnabled && (chemicals == null || granular == null)) {
			throw new IllegalArgumentException("Precipitation requires chemical and granular state");
		}
		this.precipitationEnabled = precipitationEnabled;
		this.gasRegistry = Objects.requireNonNull(gasRegistry, "gasRegistry");
		this.gas = gas;
		if (gas != null && !gas.registryFingerprint().equals(gasRegistry.fingerprint())) {
			throw new IllegalArgumentException("Gas state/registry identity mismatch");
		}
		String expectedChemical = chemicalRegistry == null ? "" : chemicalRegistry.fingerprint();
		if (gas != null && !gas.chemicalRegistryFingerprint().isEmpty()
				&& !gas.chemicalRegistryFingerprint().equals(expectedChemical)) {
			throw new IllegalArgumentException("Gas species chemical identity mismatch");
		}
		this.thermalRegistry = thermalRegistry;
		this.thermal = thermal;
		this.maximumThermalTransportSubsteps = maximumThermalTransportSubsteps;
		this.pendingPhaseVolume = pendingPhaseVolume;
		if ((thermal == null) != (thermalRegistry == null)
				|| thermal != null && !thermal.thermalRegistryFingerprint().equals(thermalRegistry.fingerprint())) {
			throw new IllegalArgumentException("Thermal state/registry identity mismatch");
		}
		if (thermal == null) {
			if (maximumThermalTransportSubsteps != 0 || pendingPhaseVolume != null) {
				throw new IllegalArgumentException("Inactive thermal state has thermal runtime data");
			}
		} else if (materials == null && chemicals == null || gas == null
				|| maximumThermalTransportSubsteps <= 0 || pendingPhaseVolume == null) {
			throw new IllegalArgumentException("Thermal state is missing carrier, gas or phase-work state");
		}
		baseParticles().requireCompatible(baseParticles().sizeX(), baseParticles().sizeY(),
				baseParticles().sizeZ(), config.cellSize());
		if (baseParticles().particleCount() > maximumParticleCount) {
			throw new IllegalArgumentException("Carrier state exceeds marker budget");
		}
	}

	public FluidSolverConfig config() { return config; }
	public int sizeX() { return baseParticles().sizeX(); }
	public int sizeY() { return baseParticles().sizeY(); }
	public int sizeZ() { return baseParticles().sizeZ(); }
	public int maximumParticleCount() { return maximumParticleCount; }
	public boolean sealedDomainBoundary() { return sealedDomainBoundary; }
	public CarrierKind carrierKind() { return carrierKind; }
	public FlipParticleState particles() { return particles; }
	public FlipMaterialParticleState materials() { return materials; }
	public FlipChemicalParticleState chemicals() { return chemicals; }
	public ChemicalRegistry chemicalRegistry() { return chemicalRegistry; }
	public GranularRegistry granularRegistry() { return granularRegistry; }
	public GranularStateSnapshot granular() { return granular; }
	public boolean precipitationEnabled() { return precipitationEnabled; }
	public GasRegistry gasRegistry() { return gasRegistry; }
	public GasStateSnapshot gas() { return gas; }
	public ThermalRegistry thermalRegistry() { return thermalRegistry; }
	public FlipThermalParticleState thermal() { return thermal; }
	public int maximumThermalTransportSubsteps() { return maximumThermalTransportSubsteps; }
	public PendingPhaseVolumeState pendingPhaseVolume() { return pendingPhaseVolume; }

	private FlipParticleState baseParticles() {
		return switch (carrierKind) {
			case PURE -> particles;
			case MATERIAL -> materials.particles();
			case CHEMICAL -> chemicals.materials().particles();
		};
	}
}
