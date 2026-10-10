package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.fluid.air.GasStateSnapshot;
import cc.ecisr.mmm.granular.GranularRegistry;
import cc.ecisr.mmm.granular.GranularDepositDynamics;
import cc.ecisr.mmm.granular.GranularStateSnapshot;
import cc.ecisr.mmm.thermal.ThermalLimits;
import cc.ecisr.mmm.thermal.ThermalRegistry;

import java.util.List;
import java.util.Objects;

/**
 * Complete immutable state for one loader-neutral solver handoff. Inventory columns remain
 * authoritative; explicitly named derived/runtime columns only continue a private candidate and
 * can never reconstruct inventory. Density and display meshes are deliberately absent.
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
	/**
	 * World material properties used by liquid stress and granular drag at the shared cell temperature.
	 * The private candidate must evaluate against the same immutable definitions.
	 */
	private final MaterialRegistry materialDefinitions;
	private final int[] chemicalDirtyCells;
	private final int chemicalReactionCursor;
	private final GranularRegistry granularRegistry;
	private final GranularStateSnapshot granular;
	private final GranularDepositDynamics.State granularDynamics;
	private final boolean precipitationEnabled;
	private final GasRegistry gasRegistry;
	private final GasStateSnapshot gas;
	private final ThermalRegistry thermalRegistry;
	private final FlipThermalParticleState thermal;
	private final int maximumThermalTransportSubsteps;
	private final PendingPhaseVolumeState pendingPhaseVolume;
	private final double[] thermalBoundaryTemperaturesK;
	private final CondensedPhasePressureField condensedPhasePressure;

	FluidDomainState(
			FluidSolverConfig config, int maximumParticleCount, boolean sealedDomainBoundary,
			FlipParticleState particles, FlipMaterialParticleState materials,
			FlipChemicalParticleState chemicals, ChemicalRegistry chemicalRegistry, int[] chemicalDirtyCells, int chemicalReactionCursor,
			GranularRegistry granularRegistry, GranularStateSnapshot granular,
			GranularDepositDynamics.State granularDynamics,
			boolean precipitationEnabled, GasRegistry gasRegistry, GasStateSnapshot gas,
			ThermalRegistry thermalRegistry, FlipThermalParticleState thermal,
			int maximumThermalTransportSubsteps, PendingPhaseVolumeState pendingPhaseVolume,
			double[] thermalBoundaryTemperaturesK, CondensedPhasePressureField condensedPhasePressure,
			MaterialRegistry materialDefinitions
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
		this.chemicalDirtyCells = chemicalDirtyCells == null ? null : chemicalDirtyCells.clone();
		this.chemicalReactionCursor = chemicalReactionCursor;
		if (chemicalReactionCursor < 0 || chemicalReactionCursor >= baseParticles().sizeX()
				* baseParticles().sizeY() * baseParticles().sizeZ()) throw new IllegalArgumentException("Invalid chemical reaction cursor");
		if ((chemicals == null) != (chemicalRegistry == null)
				|| (chemicals == null) != (chemicalDirtyCells == null)
				|| chemicals != null && !chemicals.registryFingerprint().equals(chemicalRegistry.fingerprint())) {
			throw new IllegalArgumentException("Chemical carrier/registry identity mismatch");
		}
		if (this.chemicalDirtyCells != null) {
			int previous = -1;
			int cellCount = Math.multiplyExact(Math.multiplyExact(baseParticles().sizeX(), baseParticles().sizeY()),
					baseParticles().sizeZ());
			for (int cell : this.chemicalDirtyCells) {
				if (cell <= previous || cell < 0 || cell >= cellCount) {
					throw new IllegalArgumentException("Chemical dirty-cell handoff is not canonical");
				}
				previous = cell;
			}
		}
		this.granularRegistry = granularRegistry;
		this.granular = granular;
		this.granularDynamics = granularDynamics;
		if ((granular == null) != (granularRegistry == null)
				|| (granular == null) != (granularDynamics == null)
				|| granular != null && !granular.registryFingerprint().equals(granularRegistry.fingerprint())) {
			throw new IllegalArgumentException("Granular state/registry identity mismatch");
		}
		this.materialDefinitions = materialDefinitions;
		if (granular != null && materialDefinitions == null) {
			throw new IllegalArgumentException("Granular handoff/material definitions presence mismatch");
		}
		if (materialDefinitions != null) {
			List<FluidMaterial> palette = chemicals != null ? chemicals.materials().materials()
					: materials != null ? materials.materials() : List.of();
			if (palette.isEmpty()) throw new IllegalArgumentException("Material definitions require a liquid palette");
			for (FluidMaterial material : palette) {
				MaterialRegistry.MaterialEntry definition = materialDefinitions.entry(material.id());
				if (!definition.material().equals(material) || !definition.declaresViscosity()
						|| granularRegistry != null && !granularRegistry.hasCarrier(material.id())) {
					throw new IllegalArgumentException("Invalid liquid definition for " + material.id());
				}
			}
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
		this.thermalBoundaryTemperaturesK = thermalBoundaryTemperaturesK == null
				? null : thermalBoundaryTemperaturesK.clone();
		this.condensedPhasePressure = condensedPhasePressure;
		if ((thermal == null) != (thermalRegistry == null)
				|| thermal != null && !thermal.thermalRegistryFingerprint().equals(thermalRegistry.fingerprint())) {
			throw new IllegalArgumentException("Thermal state/registry identity mismatch");
		}
		if (thermal == null) {
			if (maximumThermalTransportSubsteps != 0 || pendingPhaseVolume != null
					|| thermalBoundaryTemperaturesK != null || condensedPhasePressure != null) {
				throw new IllegalArgumentException("Inactive thermal state has thermal runtime data");
			}
		} else if (materials == null && chemicals == null || gas == null
				|| maximumThermalTransportSubsteps <= 0 || pendingPhaseVolume == null
				|| condensedPhasePressure == null) {
			throw new IllegalArgumentException("Thermal state is missing carrier, gas or phase-work state");
		}
		if (this.condensedPhasePressure != null
				&& this.condensedPhasePressure.cellCount() != Math.multiplyExact(
						Math.multiplyExact(baseParticles().sizeX(), baseParticles().sizeY()), baseParticles().sizeZ())) {
			throw new IllegalArgumentException("Condensed-phase pressure field dimensions changed");
		}
		if (this.thermalBoundaryTemperaturesK != null) {
			int expectedBoundaryEntries = Math.multiplyExact(
					Math.multiplyExact(Math.multiplyExact(baseParticles().sizeX(), baseParticles().sizeY()),
							baseParticles().sizeZ()), 6);
			if (this.thermalBoundaryTemperaturesK.length != expectedBoundaryEntries) {
				throw new IllegalArgumentException("Frozen thermal boundary field dimensions changed");
			}
			for (double temperature : this.thermalBoundaryTemperaturesK) if (!Double.isNaN(temperature)
					&& (!Double.isFinite(temperature) || temperature < ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K)) {
				throw new IllegalArgumentException("Frozen thermal boundary temperature is invalid");
			}
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
	public int[] copyChemicalDirtyCells() {
		return chemicalDirtyCells == null ? null : chemicalDirtyCells.clone();
	}
	public int chemicalReactionCursor() { return chemicalReactionCursor; }
	public GranularRegistry granularRegistry() { return granularRegistry; }

	/** World material definitions this handoff was captured against; null for explicitly inviscid test domains. */
	public MaterialRegistry materialDefinitions() { return materialDefinitions; }
	public GranularStateSnapshot granular() { return granular; }
	public GranularDepositDynamics.State granularDynamics() { return granularDynamics; }
	public boolean precipitationEnabled() { return precipitationEnabled; }
	public GasRegistry gasRegistry() { return gasRegistry; }
	public GasStateSnapshot gas() { return gas; }
	public ThermalRegistry thermalRegistry() { return thermalRegistry; }
	public FlipThermalParticleState thermal() { return thermal; }
	public int maximumThermalTransportSubsteps() { return maximumThermalTransportSubsteps; }
	public PendingPhaseVolumeState pendingPhaseVolume() { return pendingPhaseVolume; }
	public double[] copyThermalBoundaryTemperaturesK() {
		return thermalBoundaryTemperaturesK == null ? null : thermalBoundaryTemperaturesK.clone();
	}
	public CondensedPhasePressureField condensedPhasePressure() { return condensedPhasePressure; }

	/** Immutable feature/registry identity which a backend step is not allowed to replace. */
	public Layout layout() {
		List<FluidMaterial> materials = switch (carrierKind) {
			case PURE -> List.of();
			case MATERIAL -> this.materials.materials();
			case CHEMICAL -> chemicals.materials().materials();
		};
		return new Layout(config, sizeX(), sizeY(), sizeZ(), maximumParticleCount, sealedDomainBoundary,
				carrierKind, materials,
				chemicalRegistry == null ? "" : chemicalRegistry.fingerprint(),
				granularRegistry == null ? "" : granularRegistry.fingerprint(), precipitationEnabled,
				gasRegistry.fingerprint(), thermalRegistry == null ? "" : thermalRegistry.fingerprint(),
				maximumThermalTransportSubsteps,
				materialDefinitions == null ? "" : materialDefinitions.fingerprint());
	}

	public record Layout(
			FluidSolverConfig config,
			int sizeX,
			int sizeY,
			int sizeZ,
			int maximumParticleCount,
			boolean sealedDomainBoundary,
			CarrierKind carrierKind,
			List<FluidMaterial> materials,
			String chemicalRegistryFingerprint,
			String granularRegistryFingerprint,
			boolean precipitationEnabled,
			String gasRegistryFingerprint,
			String thermalRegistryFingerprint,
			int maximumThermalTransportSubsteps,
			String materialDefinitionsFingerprint
	) {
		public Layout {
			Objects.requireNonNull(config, "config");
			Objects.requireNonNull(carrierKind, "carrierKind");
			materials = List.copyOf(materials);
			Objects.requireNonNull(chemicalRegistryFingerprint, "chemicalRegistryFingerprint");
			Objects.requireNonNull(granularRegistryFingerprint, "granularRegistryFingerprint");
			Objects.requireNonNull(gasRegistryFingerprint, "gasRegistryFingerprint");
			Objects.requireNonNull(thermalRegistryFingerprint, "thermalRegistryFingerprint");
			Objects.requireNonNull(materialDefinitionsFingerprint, "materialDefinitionsFingerprint");
		}
	}

	private FlipParticleState baseParticles() {
		return switch (carrierKind) {
			case PURE -> particles;
			case MATERIAL -> materials.particles();
			case CHEMICAL -> chemicals.materials().particles();
		};
	}
}
