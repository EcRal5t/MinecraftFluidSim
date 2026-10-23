package mmm.fluid;

import mmm.inventory.LiquidCellInventory;
import mmm.inventory.PhaseOccupancyState;

import mmm.chemistry.ChemicalRegistry;
import mmm.chemistry.ChemicalTransferLedger;
import mmm.chemistry.RegionChemistry;
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
	private final int sizeX, sizeY, sizeZ;
	private final FluidSolverConfig config;
	private final boolean sealedDomainBoundary;
	private final LiquidCellInventory.Snapshot liquid;
	private final PhaseOccupancyState phaseOccupancy;
	private final MacMomentumState momentum;
	private final FixedGeometrySnapshot fixedGeometry;
	private final EntityBodyState entityBodies;
	private final List<FluidMaterial> palette;
	private final ChemicalRegistry chemicalRegistry;
	private final ChemicalTransferLedger chemicalSource, chemicalSink;
	private final int[] chemicalDirtyCells;
	private final int chemicalReactionCursor;
	private final List<RegionChemistry.MassTransferPending> chemicalMassTransferPending;
	private final GranularRegistry granularRegistry;
	private final GranularStateSnapshot granular;
	private final GranularDepositDynamics.State granularDynamics;
	private final boolean precipitationEnabled;
	private final GasRegistry gasRegistry;
	private final GasContinuation gas;
	private final ThermalRegistry thermalRegistry;
	private final ThermalOwnerSnapshot thermalOwner;
	private final int maximumThermalTransportSubsteps;
	private final PendingPhaseMotionState pendingPhaseMotion;
	private final double[] thermalBoundaryTemperaturesK;
	private final CondensedPhasePressureField condensedPhasePressure;
	private final MaterialRegistry materialDefinitions;
	private final PhysicalActivityGroups.Snapshot activityState;
	PhysicalActivityGroups.Snapshot activityState() { return activityState; }
	/** Exactly one captured gas representation; the persistence projection is derived lazily. */
	public static final class GasContinuation {
		private final GasRuntimeGeometry geometry;
		private final mmm.fluid.air.AirNetwork.RuntimeSnapshot network;
		private final String registryFingerprint;
		private final GasStateSnapshot persisted;
		private GasContinuation(GasStateSnapshot state,GasRuntimeGeometry geometry,
				mmm.fluid.air.AirNetwork.RuntimeSnapshot network,String fingerprint) {
			this.persisted=state;this.geometry=geometry;this.network=network;this.registryFingerprint=fingerprint;
		}
		public static GasContinuation persisted(GasStateSnapshot state) {
			return state==null?null:new GasContinuation(state,null,null,state.registryFingerprint());
		}
		static GasContinuation runtime(GasRuntimeGeometry geometry,mmm.fluid.air.AirNetwork.RuntimeSnapshot network,String fingerprint) {
			return new GasContinuation(null,Objects.requireNonNull(geometry),Objects.requireNonNull(network),fingerprint);
		}
		private GasStateSnapshot snapshot(int x,int y,int z,String chemicalFingerprint) {
			var result=persisted;
			if(result==null){
				result=GasStateSnapshot.capture(x,y,z,geometry.pieceNodes(),
						mmm.fluid.air.AirNetwork.fromRuntimeSnapshot(network),chemicalFingerprint);
			}
			return result;
		}
	}
	GasRuntimeGeometry gasRuntimeGeometry() { return gas==null?null:gas.geometry; }
	mmm.fluid.air.AirNetwork.RuntimeSnapshot gasRuntimeNetwork() { return gas==null?null:gas.network; }
	private volatile FlipParticleState debugParticles;
	private volatile FlipMaterialParticleState debugMaterials;
	private volatile FlipChemicalParticleState debugChemicals;
	private volatile FlipThermalParticleState debugThermal;

	public FluidDomainState(int sizeX, int sizeY, int sizeZ, FluidSolverConfig config,
			boolean sealedDomainBoundary, LiquidCellInventory.Snapshot liquid, PhaseOccupancyState phaseOccupancy, MacMomentumState momentum,
			FixedGeometrySnapshot fixedGeometry, EntityBodyState entityBodies, List<FluidMaterial> palette, ChemicalRegistry chemicalRegistry,
			ChemicalTransferLedger chemicalSource, ChemicalTransferLedger chemicalSink, int[] chemicalDirtyCells, int chemicalReactionCursor,
			List<RegionChemistry.MassTransferPending> chemicalMassTransferPending,
			GranularRegistry granularRegistry, GranularStateSnapshot granular, GranularDepositDynamics.State granularDynamics,
			boolean precipitationEnabled, GasRegistry gasRegistry, GasContinuation gas,
			ThermalRegistry thermalRegistry, ThermalOwnerSnapshot thermalOwner, int maximumThermalTransportSubsteps,
			PendingPhaseMotionState pendingPhaseMotion, double[] thermalBoundaryTemperaturesK,
			CondensedPhasePressureField condensedPhasePressure, MaterialRegistry materialDefinitions) {
		this(sizeX, sizeY, sizeZ, config, sealedDomainBoundary, liquid, phaseOccupancy, momentum, fixedGeometry, entityBodies, palette, chemicalRegistry, chemicalSource, chemicalSink, chemicalDirtyCells, chemicalReactionCursor, chemicalMassTransferPending, granularRegistry, granular, granularDynamics, precipitationEnabled, gasRegistry, gas, thermalRegistry, thermalOwner, maximumThermalTransportSubsteps, pendingPhaseMotion, thermalBoundaryTemperaturesK, condensedPhasePressure, materialDefinitions, PhysicalActivityGroups.Snapshot.EMPTY);
	}

	public FluidDomainState(int sizeX, int sizeY, int sizeZ, FluidSolverConfig config,
			boolean sealedDomainBoundary, LiquidCellInventory.Snapshot liquid, PhaseOccupancyState phaseOccupancy, MacMomentumState momentum,
			FixedGeometrySnapshot fixedGeometry, EntityBodyState entityBodies, List<FluidMaterial> palette, ChemicalRegistry chemicalRegistry,
			ChemicalTransferLedger chemicalSource, ChemicalTransferLedger chemicalSink, int[] chemicalDirtyCells, int chemicalReactionCursor,
			List<RegionChemistry.MassTransferPending> chemicalMassTransferPending,
			GranularRegistry granularRegistry, GranularStateSnapshot granular, GranularDepositDynamics.State granularDynamics,
			boolean precipitationEnabled, GasRegistry gasRegistry, GasContinuation gas,
			ThermalRegistry thermalRegistry, ThermalOwnerSnapshot thermalOwner, int maximumThermalTransportSubsteps,
			PendingPhaseMotionState pendingPhaseMotion, double[] thermalBoundaryTemperaturesK,
			CondensedPhasePressureField condensedPhasePressure, MaterialRegistry materialDefinitions, PhysicalActivityGroups.Snapshot activityState) {
		this.activityState = Objects.requireNonNull(activityState);
		if (sizeX < 2 || sizeY < 2 || sizeZ < 2) throw new IllegalArgumentException("Invalid cell domain shape");
		int cells = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ; this.config = Objects.requireNonNull(config);
		this.sealedDomainBoundary = sealedDomainBoundary;
		this.liquid = Objects.requireNonNull(liquid); this.momentum = Objects.requireNonNull(momentum);
		this.phaseOccupancy = Objects.requireNonNull(phaseOccupancy);
		if (phaseOccupancy.cellCount() != cells) throw new IllegalArgumentException("Phase occupancy shape differs from domain");
		if (liquid.cellCount() != cells || fixedGeometry==null || !fixedGeometry.matchesShape(sizeX,sizeY,sizeZ) || momentum.sizeX() != sizeX || momentum.sizeY() != sizeY || momentum.sizeZ() != sizeZ)
			throw new IllegalArgumentException("Liquid inventory, collision and MAC shapes differ");
		this.fixedGeometry = fixedGeometry; this.palette = List.copyOf(palette);
		this.entityBodies = Objects.requireNonNull(entityBodies, "Entity body state is missing");
		this.chemicalRegistry = chemicalRegistry; this.chemicalSource = Objects.requireNonNull(chemicalSource);
		this.chemicalSink = Objects.requireNonNull(chemicalSink);
		this.chemicalDirtyCells = chemicalDirtyCells == null ? null : chemicalDirtyCells.clone();
		this.chemicalReactionCursor = chemicalReactionCursor;
		this.chemicalMassTransferPending = List.copyOf(chemicalMassTransferPending);
		if (chemicalRegistry == null && !this.chemicalMassTransferPending.isEmpty())
			throw new IllegalArgumentException("Inactive chemistry contains pending mass transfer");
		RegionChemistry.MassTransferPending previousPending = null;
		for (var pending : this.chemicalMassTransferPending) {
			if (previousPending != null && previousPending.compareTo(pending) >= 0)
				throw new IllegalArgumentException("Pending mass transfer identities are not canonical");
			previousPending = pending;
			if (pending.cell() >= cells || pending.material() >= this.palette.size())
				throw new IllegalArgumentException("Pending mass transfer is outside the domain or material palette");
			var rule = chemicalRegistry.gasLiquidMassTransfers().stream().filter(value -> value.id().equals(pending.definitionId()))
					.findFirst().orElseThrow(() -> new IllegalArgumentException("Pending mass transfer definition is unknown"));
			if (!chemicalRegistry.phase(chemicalRegistry.phaseHandle(rule.liquidPhaseId())).materialId()
					.equals(this.palette.get(pending.material()).id()))
				throw new IllegalArgumentException("Pending mass transfer material differs from its liquid phase");
		}
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
		if (gas != null && !gas.registryFingerprint.equals(gasRegistry.fingerprint())) throw new IllegalArgumentException("Gas registry identity mismatch");
		this.thermalRegistry = thermalRegistry; this.thermalOwner = thermalOwner;
		this.maximumThermalTransportSubsteps = maximumThermalTransportSubsteps; this.pendingPhaseMotion = pendingPhaseMotion;
		this.thermalBoundaryTemperaturesK = thermalBoundaryTemperaturesK == null ? null : thermalBoundaryTemperaturesK.clone();
		this.condensedPhasePressure = condensedPhasePressure;
		if ((thermalOwner == null) != (thermalRegistry == null) || thermalOwner != null && (!thermalOwner.registryFingerprint().equals(thermalRegistry.fingerprint())
				|| maximumThermalTransportSubsteps <= 0 || pendingPhaseMotion == null || condensedPhasePressure == null || gas == null || palette.isEmpty()))
			throw new IllegalArgumentException("Thermal owner/registry or runtime state mismatch");
		if (thermalOwner == null && (maximumThermalTransportSubsteps != 0 || pendingPhaseMotion != null || thermalBoundaryTemperaturesK != null || condensedPhasePressure != null))
			throw new IllegalArgumentException("Inactive thermal capability contains runtime state");
		if (condensedPhasePressure != null && condensedPhasePressure.cellCount() != cells) throw new IllegalArgumentException("Pressure field shape differs");
		if (this.thermalBoundaryTemperaturesK != null && this.thermalBoundaryTemperaturesK.length != 6 * cells) throw new IllegalArgumentException("Thermal boundary shape differs");
	}
	public int sizeX() { return sizeX; } public int sizeY() { return sizeY; } public int sizeZ() { return sizeZ; }
	public FluidSolverConfig config() { return config; }
	public boolean sealedDomainBoundary() { return sealedDomainBoundary; }
	public LiquidCellInventory.Snapshot liquid() { return liquid; }
	public PhaseOccupancyState phaseOccupancy() { return phaseOccupancy; }
	public MacMomentumState momentum() { return momentum; }
	public FixedGeometrySnapshot fixedGeometry() { return fixedGeometry; }
	public EntityBodyState entityBodyState() { return entityBodies; }
	public byte[] copyCollisionMasks() { return fixedGeometry.copyMasks(); }
	public List<FluidMaterial> materialPalette() { return palette; }
	public CarrierKind carrierKind() { return chemicalRegistry != null ? CarrierKind.CHEMICAL : palette.isEmpty() ? CarrierKind.PURE : CarrierKind.MATERIAL; }
	public ChemicalRegistry chemicalRegistry() { return chemicalRegistry; }
	public ChemicalTransferLedger chemicalSource() { return chemicalSource; }
	public ChemicalTransferLedger chemicalSink() { return chemicalSink; }
	public int[] copyChemicalDirtyCells() { return chemicalDirtyCells == null ? null : chemicalDirtyCells.clone(); }
	public int chemicalReactionCursor() { return chemicalReactionCursor; }
	public List<RegionChemistry.MassTransferPending> chemicalMassTransferPending() { return chemicalMassTransferPending; }
	public GranularRegistry granularRegistry() { return granularRegistry; }
	public GranularStateSnapshot granular() { return granular; }
	public GranularDepositDynamics.State granularDynamics() { return granularDynamics; }
	public boolean precipitationEnabled() { return precipitationEnabled; }
	public GasRegistry gasRegistry() { return gasRegistry; }
	public boolean hasGas() { return gas!=null; }
	public GasStateSnapshot gas() { return gas==null?null:gas.snapshot(sizeX,sizeY,sizeZ,chemicalRegistry==null?"":chemicalRegistry.fingerprint()); }
	public ThermalRegistry thermalRegistry() { return thermalRegistry; } public ThermalOwnerSnapshot thermalOwner() { return thermalOwner; }
	public int maximumThermalTransportSubsteps() { return maximumThermalTransportSubsteps; }
	public PendingPhaseMotionState pendingPhaseMotion() { return pendingPhaseMotion; }
	public double[] copyThermalBoundaryTemperaturesK() { return thermalBoundaryTemperaturesK == null ? null : thermalBoundaryTemperaturesK.clone(); }
	/** Physical export is evaluated at the boundary from this complete immutable state. */
	public CondensedPhasePressureField exportCondensedPhasePressure() {
		if(condensedPhasePressure==null||!condensedPhasePressure.isRuntimeResidual())return condensedPhasePressure;
		return FluidDomainStateMapper.restore(this).pressureSystem.captureLiquidPressure();
	}
	CondensedPhasePressureField pressureContinuation() { return condensedPhasePressure; }
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
		return new Layout(config,sizeX,sizeY,sizeZ,sealedDomainBoundary,carrierKind(),palette,
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
