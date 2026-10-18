package mmm.fluid;

import mmm.inventory.LiquidCellInventory;
import mmm.inventory.PhaseOccupancyState;

import mmm.chemistry.ChemicalRegistry;
import mmm.chemistry.ChemicalTransferLedger;
import mmm.fluid.air.GasRegistry;
import mmm.fluid.air.GasStateSnapshot;
import mmm.granular.GranularRegistry;
import mmm.granular.GranularDepositDynamics;
import mmm.granular.GranularStateSnapshot;
import mmm.thermal.ThermalOwnerSnapshot;
import mmm.thermal.ThermalRegistry;
import java.util.List;
import java.util.Objects;

/** Immutable cell inventories and SI face momentum shared with a private backend candidate. */
public final class FluidDomainState {
	public enum CarrierKind { PURE, MATERIAL, CHEMICAL }
	private final int sizeX, sizeY, sizeZ, maximumParticleCount;
	private final FluidSolverConfig config;
	private final boolean sealedDomainBoundary;
	private final LiquidCellInventory.Snapshot liquid;
	private final PhaseOccupancyState phaseOccupancy;
	private final MacMomentumState momentum;
	private final byte[] collisionMasks;
	private final List<FluidMaterial> palette;
	private final ChemicalRegistry chemicalRegistry;
	private final ChemicalTransferLedger chemicalSource, chemicalSink;
	private final int[] chemicalDirtyCells;
	private final int chemicalReactionCursor;
	private final GranularRegistry granularRegistry;
	private final GranularStateSnapshot granular;
	private final GranularDepositDynamics.State granularDynamics;
	private final boolean precipitationEnabled;
	private final GasRegistry gasRegistry;
	private final GasStateSnapshot gas;
	private final ThermalRegistry thermalRegistry;
	private final ThermalOwnerSnapshot thermalOwner;
	private final int maximumThermalTransportSubsteps;
	private final PendingPhaseVolumeState pendingPhaseVolume;
	private final double[] thermalBoundaryTemperaturesK;
	private final CondensedPhasePressureField condensedPhasePressure;
	private final MaterialRegistry materialDefinitions;
	private GasRuntimeGeometry gasRuntimeGeometry;
	private mmm.fluid.air.AirNetwork.RuntimeSnapshot gasRuntimeNetwork;
	void attachGasRuntime(GasRuntimeGeometry geometry, mmm.fluid.air.AirNetwork.RuntimeSnapshot network) {
		if (gasRuntimeGeometry != null || gasRuntimeNetwork != null || geometry == null || network == null)
			throw new IllegalStateException("Gas runtime continuation can be attached once before publication");
		gasRuntimeGeometry = geometry; gasRuntimeNetwork = network;
	}
	GasRuntimeGeometry gasRuntimeGeometry() { return gasRuntimeGeometry; }
	mmm.fluid.air.AirNetwork.RuntimeSnapshot gasRuntimeNetwork() { return gasRuntimeNetwork; }
	private volatile FlipParticleState debugParticles;
	private volatile FlipMaterialParticleState debugMaterials;
	private volatile FlipChemicalParticleState debugChemicals;
	private volatile FlipThermalParticleState debugThermal;

	public FluidDomainState(int sizeX, int sizeY, int sizeZ, FluidSolverConfig config, int maximumParticleCount,
			boolean sealedDomainBoundary, LiquidCellInventory.Snapshot liquid, PhaseOccupancyState phaseOccupancy, MacMomentumState momentum,
			byte[] collisionMasks, List<FluidMaterial> palette, ChemicalRegistry chemicalRegistry,
			ChemicalTransferLedger chemicalSource, ChemicalTransferLedger chemicalSink, int[] chemicalDirtyCells, int chemicalReactionCursor,
			GranularRegistry granularRegistry, GranularStateSnapshot granular, GranularDepositDynamics.State granularDynamics,
			boolean precipitationEnabled, GasRegistry gasRegistry, GasStateSnapshot gas,
			ThermalRegistry thermalRegistry, ThermalOwnerSnapshot thermalOwner, int maximumThermalTransportSubsteps,
			PendingPhaseVolumeState pendingPhaseVolume, double[] thermalBoundaryTemperaturesK,
			CondensedPhasePressureField condensedPhasePressure, MaterialRegistry materialDefinitions) {
		if (sizeX < 2 || sizeY < 2 || sizeZ < 2 || maximumParticleCount <= 0) throw new IllegalArgumentException("Invalid cell domain shape");
		int cells = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ; this.config = Objects.requireNonNull(config);
		this.maximumParticleCount = maximumParticleCount; this.sealedDomainBoundary = sealedDomainBoundary;
		this.liquid = Objects.requireNonNull(liquid); this.momentum = Objects.requireNonNull(momentum);
		this.phaseOccupancy = Objects.requireNonNull(phaseOccupancy);
		if (phaseOccupancy.cellCount() != cells) throw new IllegalArgumentException("Phase occupancy shape differs from domain");
		if (liquid.cellCount() != cells || collisionMasks.length != cells || momentum.sizeX() != sizeX || momentum.sizeY() != sizeY || momentum.sizeZ() != sizeZ)
			throw new IllegalArgumentException("Liquid inventory, collision and MAC shapes differ");
		this.collisionMasks = collisionMasks.clone(); this.palette = List.copyOf(palette);
		this.chemicalRegistry = chemicalRegistry; this.chemicalSource = Objects.requireNonNull(chemicalSource);
		this.chemicalSink = Objects.requireNonNull(chemicalSink);
		this.chemicalDirtyCells = chemicalDirtyCells == null ? null : chemicalDirtyCells.clone();
		this.chemicalReactionCursor = chemicalReactionCursor;
		if ((chemicalRegistry == null) != (chemicalDirtyCells == null) || chemicalRegistry != null && this.palette.isEmpty()
				|| chemicalReactionCursor < 0 || chemicalReactionCursor >= cells)
			throw new IllegalArgumentException("Invalid chemical registry or scheduling state");
		if (chemicalDirtyCells != null) { int previous = -1; for (int cell : chemicalDirtyCells) {
			if (cell <= previous || cell >= cells) throw new IllegalArgumentException("Invalid chemical dirty-cell order"); previous = cell;
		} }
		this.granularRegistry = granularRegistry; this.granular = granular; this.granularDynamics = granularDynamics;
		if ((granular == null) != (granularRegistry == null) || (granular == null) != (granularDynamics == null)
				|| granular != null && !granular.registryFingerprint().equals(granularRegistry.fingerprint()))
			throw new IllegalArgumentException("Granular state/registry identity mismatch");
		this.precipitationEnabled = precipitationEnabled;
		if (precipitationEnabled && (chemicalRegistry == null || granular == null)) throw new IllegalArgumentException("Precipitation requires chemistry and solids");
		this.materialDefinitions = materialDefinitions;
		if (granular != null && materialDefinitions == null) throw new IllegalArgumentException("Granular properties are missing");
		this.gasRegistry = Objects.requireNonNull(gasRegistry); this.gas = gas;
		if (gas != null && !gas.registryFingerprint().equals(gasRegistry.fingerprint())) throw new IllegalArgumentException("Gas registry identity mismatch");
		this.thermalRegistry = thermalRegistry; this.thermalOwner = thermalOwner;
		this.maximumThermalTransportSubsteps = maximumThermalTransportSubsteps; this.pendingPhaseVolume = pendingPhaseVolume;
		this.thermalBoundaryTemperaturesK = thermalBoundaryTemperaturesK == null ? null : thermalBoundaryTemperaturesK.clone();
		this.condensedPhasePressure = condensedPhasePressure;
		if ((thermalOwner == null) != (thermalRegistry == null) || thermalOwner != null && (!thermalOwner.registryFingerprint().equals(thermalRegistry.fingerprint())
				|| maximumThermalTransportSubsteps <= 0 || pendingPhaseVolume == null || condensedPhasePressure == null || gas == null || palette.isEmpty()))
			throw new IllegalArgumentException("Thermal owner/registry or runtime state mismatch");
		if (thermalOwner == null && (maximumThermalTransportSubsteps != 0 || pendingPhaseVolume != null || thermalBoundaryTemperaturesK != null || condensedPhasePressure != null))
			throw new IllegalArgumentException("Inactive thermal capability contains runtime state");
		if (condensedPhasePressure != null && condensedPhasePressure.cellCount() != cells) throw new IllegalArgumentException("Pressure field shape differs");
		if (this.thermalBoundaryTemperaturesK != null && this.thermalBoundaryTemperaturesK.length != 6 * cells) throw new IllegalArgumentException("Thermal boundary shape differs");
	}
	public int sizeX() { return sizeX; } public int sizeY() { return sizeY; } public int sizeZ() { return sizeZ; }
	public FluidSolverConfig config() { return config; }
	public int maximumParticleCount() { return maximumParticleCount; }
	public boolean sealedDomainBoundary() { return sealedDomainBoundary; }
	public LiquidCellInventory.Snapshot liquid() { return liquid; }
	public PhaseOccupancyState phaseOccupancy() { return phaseOccupancy; }
	public MacMomentumState momentum() { return momentum; }
	public byte[] copyCollisionMasks() { return collisionMasks.clone(); }
	public List<FluidMaterial> materialPalette() { return palette; }
	public CarrierKind carrierKind() { return chemicalRegistry != null ? CarrierKind.CHEMICAL : palette.isEmpty() ? CarrierKind.PURE : CarrierKind.MATERIAL; }
	public ChemicalRegistry chemicalRegistry() { return chemicalRegistry; }
	public ChemicalTransferLedger chemicalSource() { return chemicalSource; }
	public ChemicalTransferLedger chemicalSink() { return chemicalSink; }
	public int[] copyChemicalDirtyCells() { return chemicalDirtyCells == null ? null : chemicalDirtyCells.clone(); }
	public int chemicalReactionCursor() { return chemicalReactionCursor; }
	public GranularRegistry granularRegistry() { return granularRegistry; }
	public GranularStateSnapshot granular() { return granular; }
	public GranularDepositDynamics.State granularDynamics() { return granularDynamics; }
	public boolean precipitationEnabled() { return precipitationEnabled; }
	public GasRegistry gasRegistry() { return gasRegistry; } public GasStateSnapshot gas() { return gas; }
	public ThermalRegistry thermalRegistry() { return thermalRegistry; } public ThermalOwnerSnapshot thermalOwner() { return thermalOwner; }
	public int maximumThermalTransportSubsteps() { return maximumThermalTransportSubsteps; }
	public PendingPhaseVolumeState pendingPhaseVolume() { return pendingPhaseVolume; }
	public double[] copyThermalBoundaryTemperaturesK() { return thermalBoundaryTemperaturesK == null ? null : thermalBoundaryTemperaturesK.clone(); }
	public CondensedPhasePressureField condensedPhasePressure() { return condensedPhasePressure; }
	public MaterialRegistry materialDefinitions() { return materialDefinitions; }

	/** Disposable sampling exports for diagnostics; backend and persistence read liquid()/momentum(). */
	public FlipParticleState particles() {
		if (carrierKind() != CarrierKind.PURE) return null;
		var cached = debugParticles; if (cached == null) debugParticles = cached = FluidDomainStateMapper.restore(this).exportParticleState(); return cached;
	}
	public FlipMaterialParticleState materials() {
		if (carrierKind() != CarrierKind.MATERIAL) return null;
		var cached = debugMaterials; if (cached == null) debugMaterials = cached = FluidDomainStateMapper.restore(this).exportMaterialParticleState(); return cached;
	}
	public FlipChemicalParticleState chemicals() {
		if (carrierKind() != CarrierKind.CHEMICAL) return null;
		var cached = debugChemicals; if (cached == null) debugChemicals = cached = FluidDomainStateMapper.restore(this).exportChemicalParticleState(); return cached;
	}
	public FlipThermalParticleState thermal() {
		if (thermalOwner == null) return null;
		var cached = debugThermal;
		if (cached == null) {
			double[] energies = liquid.physicalStocks().stream().mapToDouble(LiquidCellInventory.Stock::internalEnergy).toArray();
			debugThermal = cached = new FlipThermalParticleState(sizeX,sizeY,sizeZ,config.cellSize(),energies.length,
					thermalOwner.registryFingerprint(),thermalOwner.insertionTemperatureK(),energies,thermalOwner.copyActiveParcelEnergy(),
					thermalOwner.copyDepositEnergy(),thermalOwner.coherentGroups(),thermalOwner.copyGasNodeEnergy(),thermalOwner.audit());
		}
		return cached;
	}
	public Layout layout() {
		return new Layout(config,sizeX,sizeY,sizeZ,maximumParticleCount,sealedDomainBoundary,carrierKind(),palette,
				chemicalRegistry == null ? "" : chemicalRegistry.fingerprint(), granularRegistry == null ? "" : granularRegistry.fingerprint(),
				precipitationEnabled,gasRegistry.fingerprint(),thermalRegistry == null ? "" : thermalRegistry.fingerprint(),
				thermalOwner == null ? 0 : thermalOwner.insertionTemperatureK(),maximumThermalTransportSubsteps,
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
			double insertionTemperatureK,
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

}
