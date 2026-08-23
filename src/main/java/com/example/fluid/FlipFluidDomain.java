package com.example.fluid;

import com.example.chemistry.ChemicalMixtureRecord;
import com.example.chemistry.ChemicalParcelStore;
import com.example.chemistry.ChemicalDisplaySnapshot;
import com.example.chemistry.ChemicalRegistry;
import com.example.chemistry.ChemicalTransferLedger;
import com.example.chemistry.ChemicalLimits;
import com.example.fluid.air.AirNetwork;
import com.example.granular.GranularCarrier;
import com.example.granular.GranularAuditLedger;
import com.example.granular.GranularCouplingFields;
import com.example.granular.GranularDepositDynamics;
import com.example.granular.GranularDepositStore;
import com.example.granular.GranularDisplaySnapshot;
import com.example.granular.GranularInventory;
import com.example.granular.GranularLimits;
import com.example.granular.GranularMechanics;
import com.example.granular.GranularParcelStore;
import com.example.granular.GranularPhaseTransferEngine;
import com.example.granular.GranularRegistry;
import com.example.granular.GranularStateSnapshot;
import com.example.granular.GranularTransferLedger;
import com.example.granular.SolidCompositionPool;
import com.example.granular.SolidCompositionRecord;
import com.example.granular.SolidLiquidReaction;
import com.example.granular.SolidVolume;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.math.BigInteger;

/**
 * A bounded three-dimensional PIC/FLIP liquid domain.
 *
 * <p>The update order mirrors the user's Godot compute implementation: P2G,
 * grid forces, pressure projection, G2P, then particle advection with solid
 * collision. Eight marker particles represent one full grid cell. Particles
	 * carry exact volume and mass inventories, so liquid cannot diffuse into a low-density VOF haze.</p>
 */
public final class FlipFluidDomain {
	private static final int PARTICLES_PER_FULL_CELL = 8;
	private static final float PARTICLE_VOLUME = 1.0F / PARTICLES_PER_FULL_CELL;
	private static final float FLIP_RATIO = 0.90F;
	private static final int DEFAULT_MAX_PARTICLES = 262_144;
	private static final int NO_PHASE = Integer.MIN_VALUE;
	private static final int INDICATOR_AIM_SNAP_CELLS = 2;

	private final int sizeX;
	private final int sizeY;
	private final int sizeZ;
	private final int cellCount;
	private final FluidSolverConfig config;
	private final int maximumParticleCount;
	private final boolean[] solid;
	private final FlipCutCellGeometry cutCells;
	private final int[] markerCount;
	private final FlipFreeSurfaceGeometry freeSurfaceGeometry;
	private final FlipParticleReseeder particleReseeder;
	private final FlipParticleCollision particleCollision;
	private final FlipVelocityExtrapolator velocityExtrapolator;
	private final float[] volume;
	private final float[] pressure;
	private final float[] divergence;
	private final double[] exactCellMarkerVolume;
	private final double[] exactCellMarkerMass;
	private final double[] cellDensity;
	private final float[] surfaceTensionPressure;
	private final double[] surfaceTensionColor;
	private final double[] surfaceTensionSmoothed;
	private final double[] surfaceTensionPairCurvature;
	private final double[] surfaceTensionNormalX;
	private final double[] surfaceTensionNormalY;
	private final double[] surfaceTensionNormalZ;
	private boolean surfaceTensionActive;
	private boolean liquidPairTensionActive;
	private static final int[][] SURFACE_TENSION_DIRECTIONS = {{-1,0,0}, {1,0,0}, {0,-1,0}, {0,1,0}, {0,0,-1}, {0,0,1}};
	private final int[] cellPhaseHead;
	private int[] phaseEntryLabel;
	private int[] phaseEntryNext;
	private double[] phaseEntryVolume;
	private int phaseEntryCount;

	private final float[] velocityX;
	private final float[] velocityY;
	private final float[] velocityZ;
	private final float[] p2gVelocityX;
	private final float[] p2gVelocityY;
	private final float[] p2gVelocityZ;
	private final float[] velocityWeightX;
	private final float[] velocityWeightY;
	private final float[] velocityWeightZ;
	private final boolean[] velocityValidX;
	private final boolean[] velocityValidY;
	private final boolean[] velocityValidZ;

	private final float[] rhs;
	private final float[] residual;
	private final float[] preconditioned;
	private final float[] direction;
	private final float[] matrixDirection;

	private float[] particleX;
	private float[] particleY;
	private float[] particleZ;
	private float[] particleVelocityX;
	private float[] particleVelocityY;
	private float[] particleVelocityZ;
	private float[] particleVolume;
	private double[] particleMass;
	private int[] particleMaterialHandle;
	private int[] particlePhaseLabel;
	private int particleCount;
	private FluidMaterialRegistry materialRegistry;
	private ChemicalParcelStore chemicalParcels;
	private int[] chemicalPreviousCells;
	private int[] chemicalMovingMarkers;
	private final AirNetwork airNetwork;
	private final AirCavityScanner airScanner;
	private int[] airCellNodeId;
	private int previousSealedNodeCount;
	private boolean gasPressureActive;
	private GranularRegistry granularRegistry;
	private GranularParcelStore granularParcels;
	private GranularDepositStore granularDeposits;
	private GranularInventory granularInventory;
	private GranularAuditLedger granularExternalSource = GranularAuditLedger.EMPTY;
	private GranularAuditLedger granularEscapedSink = GranularAuditLedger.EMPTY;
	private SolidCompositionPool solidCompositions;
	private GranularPhaseTransferEngine granularPhaseTransfer;
	private boolean precipitationEnabled;
	private PhaseTransferStats lastPhaseTransferStats = new PhaseTransferStats(0, 0, 0, 0L);
	private GranularDepositDynamics granularDepositDynamics;
	private GranularDepositDynamics.Stats lastGranularDepositStats =
			new GranularDepositDynamics.Stats(0, 0, 0, 0, 0);
	private int[] granularReactivePreviousCells;
	private GranularCouplingFields granularCoupling;
	private GranularCouplingFields granularNextCoupling;
	private GranularCouplingFields pressureGranularCoupling;
	private FlipGranularCollision granularCollision;
	private int[] granularCarrierMaterialByCell;
	private double[] granularCarrierVolumeByCell;
	private boolean gridDirty = true;
	private int pressureAnchor = -1;
	private int lastPressureIterations;
	private float lastPressureResidual;
	private boolean variableDensityActive;

	public FlipFluidDomain(int sizeX, int sizeY, int sizeZ, FluidSolverConfig config) {
		this(sizeX, sizeY, sizeZ, config, DEFAULT_MAX_PARTICLES);
	}

	public FlipFluidDomain(int sizeX, int sizeY, int sizeZ, FluidSolverConfig config, int maximumParticleCount) {
		if (sizeX < 2 || sizeY < 2 || sizeZ < 2) throw new IllegalArgumentException("All dimensions must be at least two cells");
		if (maximumParticleCount <= 0) throw new IllegalArgumentException("Maximum particle count must be positive");
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.cellCount = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		this.config = config;
		this.maximumParticleCount = maximumParticleCount;
		int initialParticleCapacity = Math.min(16_384, maximumParticleCount);
		this.particleX = new float[initialParticleCapacity];
		this.particleY = new float[initialParticleCapacity];
		this.particleZ = new float[initialParticleCapacity];
		this.particleVelocityX = new float[initialParticleCapacity];
		this.particleVelocityY = new float[initialParticleCapacity];
		this.particleVelocityZ = new float[initialParticleCapacity];
		this.particleVolume = new float[initialParticleCapacity];
		this.particleMass = new double[initialParticleCapacity];
		this.particleMaterialHandle = new int[initialParticleCapacity];
		this.particlePhaseLabel = new int[initialParticleCapacity];
		this.solid = new boolean[cellCount];
		this.cutCells = new FlipCutCellGeometry(sizeX, sizeY, sizeZ);
		this.markerCount = new int[cellCount];
		this.freeSurfaceGeometry = new FlipFreeSurfaceGeometry(sizeX, sizeY, sizeZ);
		this.particleReseeder = new FlipParticleReseeder(sizeX, sizeY, sizeZ, config.cellSize(), maximumParticleCount);
		this.particleCollision = new FlipParticleCollision(sizeX, sizeY, sizeZ, config.cellSize(), solid, cutCells);
		this.velocityExtrapolator = new FlipVelocityExtrapolator(sizeX, sizeY, sizeZ, cutCells);
		this.volume = new float[cellCount];
		this.pressure = new float[cellCount];
		this.divergence = new float[cellCount];
		this.exactCellMarkerVolume = new double[cellCount];
		this.exactCellMarkerMass = new double[cellCount];
		this.cellDensity = new double[cellCount];
		this.surfaceTensionPressure = new float[cellCount];
		this.surfaceTensionColor = new double[cellCount];
		this.surfaceTensionSmoothed = new double[cellCount];
		this.surfaceTensionPairCurvature = new double[cellCount];
		this.surfaceTensionNormalX = new double[cellCount];
		this.surfaceTensionNormalY = new double[cellCount];
		this.surfaceTensionNormalZ = new double[cellCount];
		this.cellPhaseHead = new int[cellCount];
		this.phaseEntryLabel = new int[initialParticleCapacity];
		this.phaseEntryNext = new int[initialParticleCapacity];
		this.phaseEntryVolume = new double[initialParticleCapacity];
		this.velocityX = new float[(sizeX + 1) * sizeY * sizeZ];
		this.velocityY = new float[sizeX * (sizeY + 1) * sizeZ];
		this.velocityZ = new float[sizeX * sizeY * (sizeZ + 1)];
		this.p2gVelocityX = new float[velocityX.length];
		this.p2gVelocityY = new float[velocityY.length];
		this.p2gVelocityZ = new float[velocityZ.length];
		this.velocityWeightX = new float[velocityX.length];
		this.velocityWeightY = new float[velocityY.length];
		this.velocityWeightZ = new float[velocityZ.length];
		this.velocityValidX = new boolean[velocityX.length];
		this.velocityValidY = new boolean[velocityY.length];
		this.velocityValidZ = new boolean[velocityZ.length];
		this.rhs = new float[cellCount];
		this.residual = new float[cellCount];
		this.preconditioned = new float[cellCount];
		this.direction = new float[cellCount];
		this.matrixDirection = new float[cellCount];
		this.airNetwork = new AirNetwork();
		this.airScanner = new AirCavityScanner(sizeX, sizeY, sizeZ, config.cellSize(), cellCount);
		this.airCellNodeId = null;
	}

	public int sizeX() { return sizeX; }
	public int sizeY() { return sizeY; }
	public int sizeZ() { return sizeZ; }
	public int particleCount() { return particleCount; }

	/** Runtime-only gas network of this domain; it is not a wire or save format. */
	public AirNetwork airNetwork() { return airNetwork; }

	/** True when the current grid contains at least one sealed gas cavity with finite inventory. */
	public boolean gasPressureActive() { return gasPressureActive; }

	/**
	 * Gas node owning the air cell at a grid position: 0 ambient, at least 1 sealed, -1 when the
	 * cell is not air or no scan has run yet.
	 */
	public int airNodeAt(int x, int y, int z) {
		if (airCellNodeId == null) return -1;
		return airCellNodeId[cellIndexChecked(x, y, z)];
	}

	/**
	 * Read-only gas node mask for debug snapshots: one unsigned byte per cell holding the sealed
	 * cavity id (clamped to 255; 0 for ambient air, non-air cells and before the first scan).
	 * Display data only; it never feeds the solver.
	 */
	public byte[] copyGasNodeMask() {
		byte[] mask = new byte[cellCount];
		if (airCellNodeId == null) return mask;
		for (int cell = 0; cell < cellCount; cell++) {
			int node = airCellNodeId[cell];
			mask[cell] = node >= 1 ? (byte) Math.min(255, node) : 0;
		}
		return mask;
	}
	public int maximumParticleCount() { return maximumParticleCount; }
	public int markerCellCount() {
		ensureGrid();
		int result = 0;
		for (int count : markerCount) if (count > 0) result++;
		return result;
	}
	public FluidSolverConfig config() { return config; }
	public boolean hasMaterialState() { return materialRegistry != null; }
	public boolean hasChemistryState() { return chemicalParcels != null; }
	public boolean hasGranularState() { return granularParcels != null; }
	public int granularParcelCount() { return granularParcels == null ? 0 : granularParcels.count(); }
	public int granularDepositCellCount() { return granularDeposits == null ? 0 : granularDeposits.occupiedCellCount(); }
	public int granularSleepingColumnCount() {
		return granularDepositDynamics == null ? 0 : granularDepositDynamics.sleepingColumnCount();
	}
	public GranularDepositDynamics.Stats lastGranularDepositStats() { return lastGranularDepositStats; }
	public GranularStateSnapshot exportGranularState() {
		if (granularRegistry == null) throw new IllegalStateException("Granular state is not bound");
		return GranularStateSnapshot.capture(sizeX, sizeY, sizeZ, granularRegistry, granularParcels,
				granularDeposits, solidCompositions, granularExternalSource, granularEscapedSink);
	}
	public GranularDisplaySnapshot copyGranularDisplaySnapshot() {
		if (granularRegistry == null) throw new IllegalStateException("Granular state is not bound");
		return GranularDisplaySnapshot.build(sizeX, sizeY, sizeZ, config.cellSize(), granularRegistry,
				granularParcels, granularDeposits);
	}
	public GranularAuditLedger granularExternalSource() { return granularExternalSource; }
	public GranularAuditLedger granularEscapedSink() { return granularEscapedSink; }
	public long totalGranularSolidVolumeSvq() { return granularInventory == null ? 0L : granularInventory.totalSolidVolumeSvq(); }
	public double totalGranularMass() { return granularInventory == null ? 0.0 : granularInventory.totalMass(); }
	private boolean hasGranularInventory() {
		return granularParcels != null && (granularParcels.count() != 0 || granularDeposits.groupCount() != 0);
	}
	/** Read-only cell inventory used to aim the dry-container scoop at particle/deposit truth. */
	public long granularSolidVolumeSvq(int cellX, int cellY, int cellZ) {
		if (granularCoupling == null) return 0L;
		int cell = cellIndexChecked(cellX, cellY, cellZ);
		ensureGrid();
		return granularCoupling.solidSvq(cell);
	}
	public SolidCompositionPool solidCompositions() {
		if (solidCompositions == null) throw new IllegalStateException("Solid composition state is not bound");
		return solidCompositions;
	}
	public boolean precipitationEnabled() { return precipitationEnabled; }
	public PhaseTransferStats lastPhaseTransferStats() { return lastPhaseTransferStats; }
	public void setPrecipitationEnabled(boolean enabled) {
		if (enabled && (chemicalParcels == null || granularParcels == null || solidCompositions == null
				|| !granularRegistry.hasReactiveRules())) {
			throw new IllegalStateException("Precipitation requires bound chemistry, granular state and reactive rules");
		}
		precipitationEnabled = enabled;
	}

	/** Binds the runtime granular registry without changing any v1/v2/v3 handoff or persistence bytes. */
	public void bindGranular(GranularRegistry registry, FluidMaterialRegistry liquidRegistry) {
		if (registry == null || liquidRegistry == null) throw new IllegalArgumentException("Granular/liquid registry is missing");
		if (Float.floatToRawIntBits(config.cellSize()) != Float.floatToRawIntBits(0.5F)) {
			throw new IllegalArgumentException("M8 granular SVQ currently requires a 0.5 m solver cell");
		}
		bindMaterialRegistry(liquidRegistry);
		if (granularRegistry != null) {
			if (!granularRegistry.fingerprint().equals(registry.fingerprint())) {
				throw new IllegalArgumentException("A domain cannot change its granular registry");
			}
			return;
		}
		for (FluidMaterial material : liquidRegistry.materials()) {
			if (!registry.hasCarrier(material.id())) {
				throw new IllegalArgumentException("No granular carrier definition for liquid " + material.id());
			}
		}
		if (chemicalParcels != null && registry.hasReactiveRules()
				&& !registry.chemicalRegistryFingerprint().equals(chemicalParcels.registry().fingerprint())) {
			throw new IllegalArgumentException("Granular registry chemical dependency mismatch");
		}
		granularRegistry = registry;
		granularParcels = new GranularParcelStore(registry, GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION);
		granularDeposits = new GranularDepositStore(registry, cellCount,
				Math.min(GranularLimits.MAX_DEPOSIT_GROUPS_PER_REGION,
						Math.multiplyExact(cellCount, GranularLimits.MAX_DEPOSIT_GROUPS_PER_CELL)));
		granularInventory = new GranularInventory(granularParcels, granularDeposits);
		granularDepositDynamics = new GranularDepositDynamics(registry, granularParcels, granularDeposits,
				granularInventory, sizeX, sizeY, sizeZ, config.cellSize());
		granularCoupling = new GranularCouplingFields(sizeX, sizeY, sizeZ, config.cellSize());
		granularNextCoupling = new GranularCouplingFields(sizeX, sizeY, sizeZ, config.cellSize());
		granularCollision = new FlipGranularCollision(sizeX, sizeY, sizeZ, config.cellSize(), cutCells, granularDeposits,
				GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION);
		granularCarrierMaterialByCell = new int[cellCount];
		granularCarrierVolumeByCell = new double[cellCount];
		granularReactivePreviousCells = new int[GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION];
		bindSolidCompositionPoolIfReady();
		gridDirty = true;
	}

	public void restoreGranularState(
			GranularStateSnapshot snapshot, GranularRegistry registry, FluidMaterialRegistry liquidRegistry
	) {
		if (snapshot == null) throw new IllegalArgumentException("Granular snapshot is missing");
		if (snapshot.sizeX() != sizeX || snapshot.sizeY() != sizeY || snapshot.sizeZ() != sizeZ) {
			throw new IllegalArgumentException("Granular snapshot dimensions changed");
		}
		if (granularRegistry != null) throw new IllegalStateException("Granular state is already bound");
		if (materialRegistry != null) {
			if (!materialRegistry.materials().equals(liquidRegistry.materials())) {
				throw new IllegalArgumentException("Granular snapshot liquid palette changed");
			}
			liquidRegistry = materialRegistry;
		}
		bindGranular(registry, liquidRegistry);
		snapshot.restoreInto(registry, granularParcels, granularDeposits, solidCompositions,
				cell -> SolidVolume.fromCellVolumeExact(cutCells.openCellVolume(cell)));
		snapshot.externalSource().validateAgainst(registry, solidCompositions == null ? null : solidCompositions.registry());
		snapshot.escapedSink().validateAgainst(registry, solidCompositions == null ? null : solidCompositions.registry());
		granularExternalSource = snapshot.externalSource(); granularEscapedSink = snapshot.escapedSink();
		granularDeposits.forEachCell((cell, ignoredVolume, ignoredPorosity) -> granularDepositDynamics.disturbCell(cell));
		gridDirty = true;
	}

	/** Inventory-only exact insertion used by M8 fixtures and later transactional service adapters. */
	public GranularInsertion tryInsertExactGranular(
			int cellX, int cellY, int cellZ, int materialHandle, int compositionHandle,
			long solidVolumeSvq, double mass
	) {
		if (granularParcels == null) return GranularInsertion.rejected("Granular state is not bound");
		int cell = cellIndexChecked(cellX, cellY, cellZ);
		if (solidVolumeSvq <= 0L || !(Double.isFinite(mass) && mass > 0.0)) {
			throw new IllegalArgumentException("Granular insertion inventory is invalid");
		}
		granularRegistry.material(materialHandle);
		if (compositionHandle != 0 && (solidCompositions == null || !solidCompositions.isLive(compositionHandle))) {
			throw new IllegalArgumentException("Granular insertion has no live solid composition");
		}
		ensureGrid();
		granularCoupling.rebuild(granularParcels, granularDeposits);
		long geometricSvq = SolidVolume.fromCellVolumeExact(cutCells.openCellVolume(cell));
		if (Math.addExact(granularCoupling.solidSvq(cell), solidVolumeSvq) > geometricSvq) {
			return GranularInsertion.rejected("Target cell has no solid skeleton capacity");
		}
		double insertedCellVolume = SolidVolume.toCellVolume(solidVolumeSvq);
		if (totalVolumeExact() + SolidVolume.toCellVolume(totalGranularSolidVolumeSvq()) + insertedCellVolume
				> totalGeometricOpenVolumeExact() + 1.0e-9) {
			return GranularInsertion.rejected("Sealed region has no combined fluid/solid capacity");
		}
		GranularParcelStore.Parcel parcel = new GranularParcelStore.Parcel(
				(cellX + 0.5F) * config.cellSize(), (cellY + 0.5F) * config.cellSize(),
				(cellZ + 0.5F) * config.cellSize(), 0.0F, 0.0F, 0.0F,
				solidVolumeSvq, mass, materialHandle, compositionHandle, 0, (byte) 0);
		if (!granularParcels.canAdd(parcel)) return GranularInsertion.rejected("Active granular parcel budget exceeded");
		granularParcels.add(parcel);
		gridDirty = true;
		return new GranularInsertion(true, solidVolumeSvq, mass, null);
	}

	/** All-or-nothing dry-container insertion. The ledger is already stable-ID validated by the item adapter. */
	public GranularInsertion tryInsertExactGranular(
			int cellX, int cellY, int cellZ, GranularTransferLedger ledger
	) {
		return tryInsertExactGranular(cellX, cellY, cellZ, ledger, 3);
	}

	public GranularInsertion tryInsertExactGranular(
			int cellX, int cellY, int cellZ, GranularTransferLedger ledger, int maxRadius
	) {
		return tryInsertExactGranular(cellX, cellY, cellZ, ledger, maxRadius, false);
	}

	public GranularInsertion tryInsertExactGranular(
			int cellX, int cellY, int cellZ, GranularTransferLedger ledger, int maxRadius, boolean externalSource
	) {
		if (granularParcels == null) return GranularInsertion.rejected("Granular state is not bound");
		if (ledger == null) throw new IllegalArgumentException("Granular insertion ledger is missing");
		if (maxRadius < 0) throw new IllegalArgumentException("Granular insertion radius is invalid");
		cellIndexChecked(cellX, cellY, cellZ); ensureGrid();
		granularCoupling.rebuild(granularParcels, granularDeposits);
		long insertedSvq = ledger.totalSolidVolumeSvq(); double insertedMass = ledger.totalMass();
		if (totalVolumeExact() + SolidVolume.toCellVolume(totalGranularSolidVolumeSvq())
				+ SolidVolume.toCellVolume(insertedSvq) > totalGeometricOpenVolumeExact() + 1.0e-9) {
			return GranularInsertion.rejected("Sealed region has no combined fluid/solid capacity");
		}
		ArrayList<GranularParcelDraft> drafts = new ArrayList<>(); int reactiveDrafts = 0;
		for (GranularTransferLedger.Group group : ledger.groups()) {
			int materialHandle = granularRegistry.materialHandle(group.materialId());
			var material = granularRegistry.material(materialHandle);
			if (material.reactive() != (group.composition() != null)) {
				throw new IllegalArgumentException("Granular transfer material/composition kind mismatch");
			}
			if (group.composition() != null) {
				if (solidCompositions == null || !massClose(group.composition().totalMass(solidCompositions.registry()), group.mass())) {
					throw new IllegalArgumentException("Granular transfer composition/mass mismatch");
				}
			}
			long remainingSvq = group.solidVolumeSvq(); double remainingMass = group.mass();
			while (remainingSvq > 0L) {
				long pieceSvq = Math.min(remainingSvq, material.maximumActiveParcelSvq());
				double pieceMass = pieceSvq == remainingSvq ? remainingMass
						: group.mass() * ((double) pieceSvq / group.solidVolumeSvq());
				if (!(Double.isFinite(pieceMass) && pieceMass > 0.0 && pieceMass <= remainingMass)) {
					return GranularInsertion.rejected("Granular transfer mass split is not representable");
				}
				SolidCompositionRecord pieceComposition = group.composition() == null ? null
						: splitSolidComposition(group.composition(), group.solidVolumeSvq(), pieceSvq, pieceMass);
				drafts.add(new GranularParcelDraft(materialHandle, pieceSvq, pieceMass, pieceComposition));
				if (pieceComposition != null) reactiveDrafts++;
				remainingSvq -= pieceSvq; remainingMass -= pieceMass;
			}
		}
		List<CellCandidate> insertionCells = nearbyCells(cellX, cellY, cellZ, maxRadius);
		long[] plannedSolid = granularCoupling.copySolidSvq(); ArrayList<GranularParcelDraft> positioned = new ArrayList<>(drafts.size());
		for (GranularParcelDraft draft : drafts) {
			int selected = -1;
			for (CellCandidate candidate : insertionCells) {
				long geometric = SolidVolume.fromCellVolumeExact(cutCells.openCellVolume(candidate.index()));
				if (Math.addExact(plannedSolid[candidate.index()], draft.solidVolumeSvq()) <= geometric) {
					selected = candidate.index(); break;
				}
			}
			if (selected < 0) return GranularInsertion.rejected("Nearby cells have no solid skeleton capacity");
			plannedSolid[selected] = Math.addExact(plannedSolid[selected], draft.solidVolumeSvq());
			int selectedX = selected % sizeX, selectedYz = selected / sizeX;
			positioned.add(draft.at(selectedX, selectedYz % sizeY, selectedYz / sizeY));
		}
		drafts = positioned;
		if (!granularParcels.hasCapacity(drafts.size())
				|| reactiveDrafts > 0 && !solidCompositions.hasCapacity(reactiveDrafts)) {
			return GranularInsertion.rejected("Active granular parcel/composition budget exceeded");
		}
		GranularAuditLedger sourceAfter = externalSource ? granularExternalSource.plus(
				ledger, granularRegistry, solidCompositions == null ? null : solidCompositions.registry()) : granularExternalSource;
		int originalCount = granularParcels.count(); ArrayList<Integer> createdCompositions = new ArrayList<>(reactiveDrafts);
		try {
			for (GranularParcelDraft draft : drafts) {
				int compositionHandle = draft.composition() == null ? 0 : solidCompositions.create(draft.composition());
				if (compositionHandle != 0) createdCompositions.add(compositionHandle);
				granularParcels.add(new GranularParcelStore.Parcel(
						(draft.cellX() + 0.5F) * config.cellSize(), (draft.cellY() + 0.5F) * config.cellSize(),
						(draft.cellZ() + 0.5F) * config.cellSize(), 0.0F, 0.0F, 0.0F,
						draft.solidVolumeSvq(), draft.mass(), draft.materialHandle(), compositionHandle, 0, (byte) 0));
			}
		} catch (RuntimeException failure) {
			while (granularParcels.count() > originalCount) granularParcels.removeSwap(granularParcels.count() - 1);
			for (int handle : createdCompositions) if (solidCompositions.isLive(handle)) solidCompositions.release(handle);
			throw failure;
		}
		boolean[] changedCells = new boolean[cellCount];
		for (GranularParcelDraft draft : drafts) {
			int changed = cellIndex(draft.cellX(), draft.cellY(), draft.cellZ());
			if (!changedCells[changed]) { markGranularExternalChange(changed); changedCells[changed] = true; }
		}
		granularExternalSource = sourceAfter;
		return new GranularInsertion(true, insertedSvq, insertedMass, null);
	}

	/** Extracts only same-cell active parcels and the one exposed deposit group, never deep inventory. */
	public GranularExtraction tryExtractExactGranular(
			int cellX, int cellY, int cellZ, long maximumSolidVolumeSvq
	) {
		return tryExtractExactGranular(cellX, cellY, cellZ, maximumSolidVolumeSvq, 3);
	}

	public GranularExtraction tryExtractExactGranular(
			int cellX, int cellY, int cellZ, long maximumSolidVolumeSvq, int maxRadius
	) {
		return tryExtractExactGranular(cellX, cellY, cellZ, maximumSolidVolumeSvq, maxRadius, false);
	}

	public GranularExtraction tryExtractExactGranular(
			int cellX, int cellY, int cellZ, long maximumSolidVolumeSvq, int maxRadius, boolean escapedSink
	) {
		return tryExtractExactGranular(cellX, cellY, cellZ, maximumSolidVolumeSvq, maxRadius,
				escapedSink, ignored -> true);
	}

	public GranularExtraction tryExtractExactGranular(
			int cellX, int cellY, int cellZ, long maximumSolidVolumeSvq, int maxRadius, boolean escapedSink,
			java.util.function.Predicate<GranularTransferLedger> itemAcceptance
	) {
		if (granularParcels == null) return GranularExtraction.rejected("Granular state is not bound");
		if (itemAcceptance == null) throw new IllegalArgumentException("Granular item acceptance is missing");
		if (maximumSolidVolumeSvq <= 0L || maximumSolidVolumeSvq > SolidVolume.SVQ_PER_BV) {
			throw new IllegalArgumentException("Granular extraction capacity is invalid");
		}
		if (maxRadius < 0) throw new IllegalArgumentException("Granular extraction radius is invalid");
		cellIndexChecked(cellX, cellY, cellZ); long remaining = maximumSolidVolumeSvq;
		List<CellCandidate> extractionCells = nearbyCells(cellX, cellY, cellZ, maxRadius);
		boolean[] near = new boolean[cellCount]; for (CellCandidate candidate : extractionCells) near[candidate.index()] = true;
		ArrayList<GranularRemoval> removals = new ArrayList<>();
		GranularLedgerBuilder ledger = new GranularLedgerBuilder();
		for (int parcelIndex = 0; parcelIndex < granularParcels.count() && remaining > 0L; parcelIndex++) {
			GranularParcelStore.Parcel parcel = granularParcels.parcel(parcelIndex);
			int parcelCell = granularParcelCell(parcel);
			if (!near[parcelCell] || !ledger.canAccept(granularRegistry.material(parcel.materialHandle()).id())) continue;
			long taken = Math.min(remaining, parcel.solidVolumeSvq());
			if (parcel.solidCompositionHandle() != 0 && taken != parcel.solidVolumeSvq()) continue;
			double mass = taken == parcel.solidVolumeSvq() ? parcel.mass()
					: parcel.mass() * ((double) taken / parcel.solidVolumeSvq());
			SolidCompositionRecord composition = parcel.solidCompositionHandle() == 0 ? null
					: solidCompositions.record(parcel.solidCompositionHandle());
			ledger.add(granularRegistry.material(parcel.materialHandle()).id(), taken, mass, composition);
			removals.add(GranularRemoval.parcel(parcelIndex, parcelCell, parcel, taken, mass)); remaining -= taken;
		}
		for (CellCandidate candidate : extractionCells) if (remaining > 0L) {
			int cell = candidate.index(); GranularDepositStore.Group exposed = granularDeposits.dominantGroup(cell);
			if (exposed != null && ledger.canAccept(granularRegistry.material(exposed.materialHandle()).id())) {
				long taken = Math.min(remaining, exposed.solidVolumeSvq());
				if (exposed.solidCompositionHandle() == 0 || taken == exposed.solidVolumeSvq()) {
					GranularDepositStore.SolidPortion preview = granularDeposits.peekTake(cell, exposed.materialHandle(),
							exposed.solidCompositionHandle(), taken);
					SolidCompositionRecord composition = exposed.solidCompositionHandle() == 0 ? null
							: solidCompositions.record(exposed.solidCompositionHandle());
					ledger.add(granularRegistry.material(exposed.materialHandle()).id(), taken, preview.mass(), composition);
					removals.add(GranularRemoval.deposit(cell, exposed, taken, preview.mass()));
					remaining -= taken;
				}
			}
		}
		if (removals.isEmpty()) return GranularExtraction.rejected("Target cell has no accessible granular inventory");
		GranularTransferLedger transfer = ledger.build();
		if (!itemAcceptance.test(transfer)) {
			return GranularExtraction.rejected("Accessible granular inventory cannot be represented by the dry container");
		}
		GranularAuditLedger sinkAfter = escapedSink ? granularEscapedSink.plus(
				transfer, granularRegistry, solidCompositions == null ? null : solidCompositions.registry()) : granularEscapedSink;
		for (GranularRemoval removal : removals) if (removal.deposit()) {
			granularDeposits.take(removal.cell(), removal.materialHandle(), removal.compositionHandle(), removal.solidVolumeSvq());
			if (removal.compositionHandle() != 0) solidCompositions.release(removal.compositionHandle());
		}
		for (GranularRemoval removal : removals) if (!removal.deposit()
				&& removal.solidVolumeSvq() != removal.originalSolidVolumeSvq()) {
			GranularParcelStore.Parcel parcel = granularParcels.parcel(removal.index());
			granularParcels.overwrite(removal.index(), new GranularParcelStore.Parcel(parcel.x(), parcel.y(), parcel.z(),
					parcel.velocityX(), parcel.velocityY(), parcel.velocityZ(),
					parcel.solidVolumeSvq() - removal.solidVolumeSvq(), parcel.mass() - removal.mass(),
					parcel.materialHandle(), 0, parcel.contactAge(), parcel.flags()));
		}
		removals.stream().filter(removal -> !removal.deposit()
				&& removal.solidVolumeSvq() == removal.originalSolidVolumeSvq())
				.sorted(Comparator.comparingInt(GranularRemoval::index).reversed()).forEach(removal -> {
					GranularParcelStore.Parcel removed = granularParcels.removeSwap(removal.index());
					if (removed.solidCompositionHandle() != 0) solidCompositions.release(removed.solidCompositionHandle());
				});
		boolean[] changedCells = new boolean[cellCount];
		for (GranularRemoval removal : removals) if (!changedCells[removal.cell()]) {
			markGranularExternalChange(removal.cell()); changedCells[removal.cell()] = true;
		}
		granularEscapedSink = sinkAfter;
		return new GranularExtraction(true, transfer, null);
	}

	private SolidCompositionRecord splitSolidComposition(
			SolidCompositionRecord source, long sourceSvq, long pieceSvq, double pieceMass
	) {
		int[] species = source.copySpeciesHandles(); long[] amounts = source.copyAmounts();
		for (int entry = 0; entry < amounts.length; entry++) {
			BigInteger[] division = BigInteger.valueOf(amounts[entry]).multiply(BigInteger.valueOf(pieceSvq))
					.divideAndRemainder(BigInteger.valueOf(sourceSvq));
			if (division[1].signum() != 0 || division[0].signum() <= 0) {
				throw new IllegalArgumentException("Reactive granular container split is below composition resolution");
			}
			amounts[entry] = division[0].longValueExact();
		}
		SolidCompositionRecord exact = new SolidCompositionRecord(species, amounts, Double.doubleToRawLongBits(0.0));
		double residual = pieceMass - exact.exactSpeciesMass(solidCompositions.registry()).toDouble();
		return new SolidCompositionRecord(species, amounts, Double.doubleToRawLongBits(residual));
	}

	private void markGranularExternalChange(int cell) {
		granularDepositDynamics.disturbCell(cell);
		if (precipitationEnabled) chemicalParcels.markDirtyCell(cell);
		gridDirty = true;
	}

	private record GranularParcelDraft(
			int materialHandle, long solidVolumeSvq, double mass, SolidCompositionRecord composition,
			int cellX, int cellY, int cellZ
	) {
		GranularParcelDraft(int materialHandle, long solidVolumeSvq, double mass, SolidCompositionRecord composition) {
			this(materialHandle, solidVolumeSvq, mass, composition, -1, -1, -1);
		}
		GranularParcelDraft at(int x, int y, int z) {
			return new GranularParcelDraft(materialHandle, solidVolumeSvq, mass, composition, x, y, z);
		}
	}

	private static final class GranularLedgerBuilder {
		private final TreeMap<String, MutableGranularGroup> groups = new TreeMap<>();
		boolean canAccept(String materialId) {
			return groups.containsKey(materialId) || groups.size() < GranularTransferLedger.MAX_GROUPS;
		}
		void add(String materialId, long svq, double mass, SolidCompositionRecord composition) {
			groups.computeIfAbsent(materialId, ignored -> new MutableGranularGroup()).add(svq, mass, composition);
		}
		GranularTransferLedger build() {
			ArrayList<GranularTransferLedger.Group> result = new ArrayList<>(groups.size());
			for (Map.Entry<String, MutableGranularGroup> entry : groups.entrySet()) {
				result.add(entry.getValue().build(entry.getKey()));
			}
			return new GranularTransferLedger(result);
		}
	}

	private static final class MutableGranularGroup {
		private long svq; private double mass, residual; private TreeMap<Integer, Long> species;
		void add(long addedSvq, double addedMass, SolidCompositionRecord composition) {
			svq = Math.addExact(svq, addedSvq); mass += addedMass;
			if (composition == null) {
				if (species != null) throw new IllegalArgumentException("Mixed inert/reactive granular material group");
				return;
			}
			if (species == null) species = new TreeMap<>(); residual += composition.massResidual();
			for (int entry = 0; entry < composition.entryCount(); entry++) species.merge(
					composition.speciesHandle(entry), composition.amountAt(entry), Math::addExact);
		}
		GranularTransferLedger.Group build(String materialId) {
			SolidCompositionRecord composition = null;
			if (species != null) {
				int[] handles = new int[species.size()]; long[] amounts = new long[species.size()]; int index = 0;
				for (Map.Entry<Integer, Long> entry : species.entrySet()) {
					handles[index] = entry.getKey(); amounts[index++] = entry.getValue();
				}
				composition = new SolidCompositionRecord(handles, amounts, Double.doubleToRawLongBits(residual));
			}
			return new GranularTransferLedger.Group(materialId, svq, mass, composition);
		}
	}

	private record GranularRemoval(
			boolean deposit, int index, int cell, int materialHandle, int compositionHandle,
			long solidVolumeSvq, long originalSolidVolumeSvq, double mass
	) {
		static GranularRemoval parcel(int index, int cell, GranularParcelStore.Parcel parcel, long svq, double mass) {
			return new GranularRemoval(false, index, cell, parcel.materialHandle(), parcel.solidCompositionHandle(),
					svq, parcel.solidVolumeSvq(), mass);
		}
		static GranularRemoval deposit(int cell, GranularDepositStore.Group group, long svq, double mass) {
			return new GranularRemoval(true, -1, cell, group.materialHandle(), group.solidCompositionHandle(),
					svq, group.solidVolumeSvq(), mass);
		}
	}

	public record GranularInsertion(boolean inserted, long solidVolumeSvq, double mass, String failure) {
		private static GranularInsertion rejected(String reason) { return new GranularInsertion(false, 0L, 0.0, reason); }
	}
	public record GranularExtraction(boolean transferred, GranularTransferLedger ledger, String failure) {
		private static GranularExtraction rejected(String reason) { return new GranularExtraction(false, null, reason); }
	}

	/** Returns the bound material registry, or null for a pure-water domain. */
	public FluidMaterialRegistry materialRegistry() { return materialRegistry; }

	public ChemicalParcelStore chemicalParcels() {
		if (chemicalParcels == null) throw new IllegalStateException("Chemistry is not bound");
		return chemicalParcels;
	}

	public ChemicalDisplaySnapshot copyChemicalDisplaySnapshot() {
		if (chemicalParcels == null) throw new IllegalStateException("Chemistry is not bound");
		return ChemicalDisplaySnapshot.build(chemicalParcels.registry(), chemicalParcels,
				sizeX, sizeY, sizeZ, config.cellSize(), particleX, particleY, particleZ, particleVolume, particleCount);
	}

	/** Auditable exact ledger for a stable ordinal range, used immediately after planned external insertion. */
	public ChemicalTransferLedger chemicalLedgerForMarkerRange(int fromInclusive, int toExclusive) {
		if (chemicalParcels == null) throw new IllegalStateException("Chemistry is not bound");
		if (fromInclusive < 0 || toExclusive < fromInclusive || toExclusive > particleCount) {
			throw new IndexOutOfBoundsException("Invalid chemical marker range");
		}
		ChemicalTransferLedger.Builder result = ChemicalTransferLedger.builder(chemicalParcels.registry());
		for (int marker = fromInclusive; marker < toExclusive; marker++) {
			result.add(materialRegistry.material(particleMaterialHandle[marker]).id(), chemicalParcels.indicator(marker),
					particleVolume[marker], particleMass[marker], chemicalParcels.record(marker));
		}
		return result.build();
	}

	/**
	 * Plans and commits an indicator/eraser flood fill through positive-area cut faces. The fill is
	 * isolated by chemical phase, while the indicator bit itself never isolates reactions.
	 */
	public IndicatorEditResult editIndicatorFloodFill(
			int startX, int startY, int startZ, boolean indicated, int maxScannedCells, double maxAddedBlockVolume
	) {
		if (chemicalParcels == null || materialRegistry == null) {
			return IndicatorEditResult.rejected("Chemistry is not bound");
		}
		if (maxScannedCells <= 0 || maxScannedCells > cellCount) {
			throw new IllegalArgumentException("Indicator scan budget must be within the region cell budget");
		}
		if (indicated && !(Double.isFinite(maxAddedBlockVolume) && maxAddedBlockVolume > 0.0)) {
			throw new IllegalArgumentException("Indicator volume budget must be finite and positive");
		}
		int requestedStart = cellIndexChecked(startX, startY, startZ);
		int[] dominantPhase = new int[cellCount];
		float[] dominantVolume = new float[cellCount];
		int[] dominantMarker = new int[cellCount];
		Arrays.fill(dominantPhase, -1);
		Arrays.fill(dominantVolume, -1.0F);
		Arrays.fill(dominantMarker, Integer.MAX_VALUE);
		for (int marker = 0; marker < particleCount; marker++) {
			int cell = particleCellIndex(marker);
			float candidate = particleVolume[marker];
			if (candidate > dominantVolume[cell]
					|| candidate == dominantVolume[cell] && marker < dominantMarker[cell]) {
				dominantVolume[cell] = candidate;
				dominantMarker[cell] = marker;
				dominantPhase[cell] = chemicalParcels.registry().phaseHandleForMaterial(
						materialRegistry.material(particleMaterialHandle[marker]).id());
			}
		}
		IndicatorAimSeed seed = resolveIndicatorAimSeed(requestedStart, dominantPhase, maxScannedCells);
		if (seed.cell() < 0) return IndicatorEditResult.rejected(seed.failure());
		int start = seed.cell();
		int targetPhase = dominantPhase[start];
		if (indicated && !chemicalParcels.registry().phase(targetPhase).acceptsIndicator()) {
			return IndicatorEditResult.rejected("Target chemical phase rejects indicator");
		}

		int[] cellHead = new int[cellCount];
		int[] markerNext = new int[particleCount];
		boolean[] compatible = new boolean[cellCount];
		Arrays.fill(cellHead, -1);
		for (int marker = particleCount - 1; marker >= 0; marker--) {
			String materialId = materialRegistry.material(particleMaterialHandle[marker]).id();
			if (chemicalParcels.registry().phaseHandleForMaterial(materialId) != targetPhase) continue;
			int cell = particleCellIndex(marker);
			markerNext[marker] = cellHead[cell];
			cellHead[cell] = marker;
			compatible[cell] = true;
		}

		boolean[] visited = new boolean[cellCount];
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		ArrayList<Integer> affected = new ArrayList<>();
		visited[start] = true;
		queue.add(start);
		int scanned = seed.scannedCells() - 1;
		double cellVolume = 0.0;
		while (!queue.isEmpty()) {
			int cell = queue.removeFirst();
			if (++scanned > maxScannedCells) return IndicatorEditResult.rejected("Indicator scan budget exceeded");
			for (int marker = cellHead[cell]; marker >= 0; marker = markerNext[marker]) {
				affected.add(marker);
				cellVolume += particleVolume[marker];
			}
			double blockVolume = cellVolume * config.cellSize() * config.cellSize() * config.cellSize();
			if (indicated && blockVolume > maxAddedBlockVolume) {
				return IndicatorEditResult.rejected("Indicator volume exceeds " + maxAddedBlockVolume + " BV");
			}
			int x = cell % sizeX;
			int yz = cell / sizeX;
			int y = yz % sizeY;
			int z = yz / sizeY;
			if (x > 0) enqueueIndicatorNeighbor(cell, cellIndex(x - 1, y, z), -1, 0, 0, compatible, visited, queue);
			if (x + 1 < sizeX) enqueueIndicatorNeighbor(cell, cellIndex(x + 1, y, z), 1, 0, 0, compatible, visited, queue);
			if (y > 0) enqueueIndicatorNeighbor(cell, cellIndex(x, y - 1, z), 0, -1, 0, compatible, visited, queue);
			if (y + 1 < sizeY) enqueueIndicatorNeighbor(cell, cellIndex(x, y + 1, z), 0, 1, 0, compatible, visited, queue);
			if (z > 0) enqueueIndicatorNeighbor(cell, cellIndex(x, y, z - 1), 0, 0, -1, compatible, visited, queue);
			if (z + 1 < sizeZ) enqueueIndicatorNeighbor(cell, cellIndex(x, y, z + 1), 0, 0, 1, compatible, visited, queue);
		}
		int changed = 0;
		for (int marker : affected) if (chemicalParcels.updateIndicator(marker, indicated)) changed++;
		double blockVolume = cellVolume * config.cellSize() * config.cellSize() * config.cellSize();
		return new IndicatorEditResult(true, scanned, changed, blockVolume, null);
	}

	/**
	 * Display density may put the first ray-hit cell one or two cells away from the marker truth in
	 * shallow water. Snap only through open cut faces and only within that bounded stencil; the
	 * subsequent phase flood still begins from an actual chemical marker cell.
	 */
	private IndicatorAimSeed resolveIndicatorAimSeed(int requested, int[] dominantPhase, int maxScannedCells) {
		int[] distance = new int[cellCount];
		Arrays.fill(distance, -1);
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		distance[requested] = 0;
		queue.add(requested);
		int scanned = 0;
		while (!queue.isEmpty()) {
			int cell = queue.removeFirst();
			if (++scanned > maxScannedCells) {
				return new IndicatorAimSeed(-1, scanned, "Indicator aim scan budget exceeded");
			}
			if (dominantPhase[cell] >= 0) return new IndicatorAimSeed(cell, scanned, null);
			if (distance[cell] >= INDICATOR_AIM_SNAP_CELLS) continue;
			int x = cell % sizeX;
			int yz = cell / sizeX;
			int y = yz % sizeY;
			int z = yz / sizeY;
			if (x > 0) enqueueIndicatorAimNeighbor(cell, cellIndex(x - 1, y, z), -1, 0, 0, distance, queue);
			if (x + 1 < sizeX) enqueueIndicatorAimNeighbor(cell, cellIndex(x + 1, y, z), 1, 0, 0, distance, queue);
			if (y > 0) enqueueIndicatorAimNeighbor(cell, cellIndex(x, y - 1, z), 0, -1, 0, distance, queue);
			if (y + 1 < sizeY) enqueueIndicatorAimNeighbor(cell, cellIndex(x, y + 1, z), 0, 1, 0, distance, queue);
			if (z > 0) enqueueIndicatorAimNeighbor(cell, cellIndex(x, y, z - 1), 0, 0, -1, distance, queue);
			if (z + 1 < sizeZ) enqueueIndicatorAimNeighbor(cell, cellIndex(x, y, z + 1), 0, 0, 1, distance, queue);
		}
		return new IndicatorAimSeed(-1, scanned, "Target surface has no nearby chemical liquid marker");
	}

	private void enqueueIndicatorAimNeighbor(
			int from, int neighbor, int dx, int dy, int dz, int[] distance, ArrayDeque<Integer> queue
	) {
		if (distance[neighbor] < 0 && openFaceFromCell(from, dx, dy, dz) > 0.0F) {
			distance[neighbor] = distance[from] + 1;
			queue.addLast(neighbor);
		}
	}

	private record IndicatorAimSeed(int cell, int scannedCells, String failure) { }

	private void enqueueIndicatorNeighbor(
			int from, int neighbor, int dx, int dy, int dz, boolean[] compatible,
			boolean[] visited, ArrayDeque<Integer> queue
	) {
		if (!visited[neighbor] && compatible[neighbor] && openFaceFromCell(from, dx, dy, dz) > 0.0F) {
			visited[neighbor] = true;
			queue.addLast(neighbor);
		}
	}

	public record IndicatorEditResult(
			boolean accepted, int scannedCells, int changedMarkers, double affectedBlockVolume, String failure
	) {
		private static IndicatorEditResult rejected(String failure) {
			return new IndicatorEditResult(false, 0, 0, 0.0, failure);
		}
	}

	/** Converts every current material marker to the registry-defined default carrier. */
	public void bindChemistry(ChemicalRegistry registry) {
		if (materialRegistry == null) throw new IllegalStateException("Chemistry requires a material registry");
		if (chemicalParcels != null) {
			if (!chemicalParcels.registry().fingerprint().equals(registry.fingerprint())) {
				throw new IllegalArgumentException("A domain cannot change its chemical registry");
			}
			return;
		}
		if (granularRegistry != null && granularRegistry.hasReactiveRules()
				&& !granularRegistry.chemicalRegistryFingerprint().equals(registry.fingerprint())) {
			throw new IllegalArgumentException("Granular registry chemical dependency mismatch");
		}
		ChemicalParcelStore candidate = new ChemicalParcelStore(registry, maximumParticleCount, cellCount);
		for (FluidMaterial material : materialRegistry.materials()) registry.phaseHandleForMaterial(material.id());
		for (int marker = 0; marker < particleCount; marker++) {
			candidate.assignDefault(marker, materialRegistry.material(particleMaterialHandle[marker]).id(),
					particleVolume[marker], config.cellSize(), particleMass[marker], false);
		}
		chemicalParcels = candidate;
		chemicalPreviousCells = new int[maximumParticleCount];
		chemicalMovingMarkers = new int[maximumParticleCount];
		bindSolidCompositionPoolIfReady();
	}

	private void bindSolidCompositionPoolIfReady() {
		if (solidCompositions != null || granularRegistry == null || chemicalParcels == null
				|| !granularRegistry.hasReactiveRules()) return;
		if (!granularRegistry.chemicalRegistryFingerprint().equals(chemicalParcels.registry().fingerprint())) {
			throw new IllegalArgumentException("Granular registry chemical dependency mismatch");
		}
		int depositCapacity = Math.min(GranularLimits.MAX_DEPOSIT_GROUPS_PER_REGION,
				Math.multiplyExact(cellCount, GranularLimits.MAX_DEPOSIT_GROUPS_PER_CELL));
		solidCompositions = new SolidCompositionPool(chemicalParcels.registry(),
				Math.addExact(GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION, depositCapacity));
		granularPhaseTransfer = new GranularPhaseTransferEngine(chemicalParcels.registry());
	}

	/** Test/command reagent injection on one marker using absolute species quanta. */
	public void setMarkerChemical(int marker, Map<String, Long> stableAmounts, boolean indicator) {
		if (marker < 0 || marker >= particleCount) throw new IllegalArgumentException("Unknown marker " + marker);
		ChemicalMixtureRecord zeroResidual = ChemicalMixtureRecord.fromStableAmounts(
				chemicalParcels().registry(), stableAmounts, 0.0);
		ChemicalMixtureRecord record = ChemicalMixtureRecord.fromStableAmounts(
				chemicalParcels.registry(), stableAmounts,
				particleMass[marker] - zeroResidual.exactSpeciesMass(chemicalParcels.registry()).toDouble());
		chemicalParcels.requireCompatible(record,
				materialRegistry.material(particleMaterialHandle[marker]).id(), indicator);
		chemicalParcels.assign(marker, record, indicator);
		chemicalParcels.markDirtyCell(particleCellIndex(marker));
	}

	/** Resolves a stable material id to the runtime handle of the bound registry. */
	public int materialHandle(String materialId) {
		if (materialRegistry == null) throw new IllegalStateException("Material registry is not bound");
		return materialRegistry.handle(materialId);
	}


	/** Replaces this domain from one validated, immutable in-memory marker state. */
	public void restoreParticleState(FlipParticleState state) {
		if (state == null) throw new IllegalArgumentException("Particle state must not be null");
		state.requireCompatible(sizeX, sizeY, sizeZ, config.cellSize());
		int restoredCount = state.particleCount();
		if (restoredCount > maximumParticleCount) throw new IllegalArgumentException("Particle state exceeds domain marker budget");
		ensureParticleCapacity(restoredCount);
		float[] positionVolume = state.positionVolume();
		float[] particleVelocities = state.particleVelocities();
		byte[] solidMask = state.solidMask();
		cutCells.restoreBinaryMask(solidMask);
		for (int i = 0; i < cellCount; i++) solid[i] = solidMask[i] != 0;
		for (int particle = 0; particle < restoredCount; particle++) {
			int positionBase = particle * 4, velocityBase = particle * 3;
			particleX[particle] = positionVolume[positionBase];
			particleY[particle] = positionVolume[positionBase + 1];
			particleZ[particle] = positionVolume[positionBase + 2];
			particleVolume[particle] = positionVolume[positionBase + 3];
			particleVelocityX[particle] = particleVelocities[velocityBase];
			particleVelocityY[particle] = particleVelocities[velocityBase + 1];
			particleVelocityZ[particle] = particleVelocities[velocityBase + 2];
			setDefaultMaterialState(particle);
		}
		particleCount = restoredCount;
		materialRegistry = null;
		chemicalParcels = null;
		chemicalPreviousCells = null;
		chemicalMovingMarkers = null;
		Arrays.fill(pressure, 0.0F);
		Arrays.fill(divergence, 0.0F);
		pressureAnchor = -1;
		lastPressureIterations = 0;
		lastPressureResidual = 0.0F;
		gridDirty = true;
	}

	/** Replaces this domain from a validated multi-material CPU handoff without changing world state. */
	public void restoreMaterialParticleState(FlipMaterialParticleState state) {
		if (state == null) throw new IllegalArgumentException("Material particle state must not be null");
		state.requireCompatible(sizeX, sizeY, sizeZ, config.cellSize());
		if (state.particleCount() > maximumParticleCount) {
			throw new IllegalArgumentException("Material particle state exceeds domain marker budget");
		}
		FluidMaterialRegistry registry = new FluidMaterialRegistry(state.materials());
		double defaultDensity = registry.material(0).referenceDensity();
		double tolerance = Math.max(1.0E-9, Math.abs(config.density()) * 1.0E-12);
		if (Math.abs(defaultDensity - config.density()) > tolerance) {
			throw new IllegalArgumentException("Material handle 0 must match the domain default density");
		}
		restoreParticleState(state.particles());
		double[] restoredMass = state.particleMass();
		int[] restoredMaterial = state.materialPaletteIndex();
		for (int particle = 0; particle < particleCount; particle++) {
			particleMass[particle] = restoredMass[particle];
			particleMaterialHandle[particle] = restoredMaterial[particle];
			particlePhaseLabel[particle] = registry.phaseHandle(restoredMaterial[particle]);
		}
		materialRegistry = registry;
		gridDirty = true;
	}

	/** Restores a validated v3 chemical handoff against the authoritative world registry. */
	public void restoreChemicalParticleState(FlipChemicalParticleState state, ChemicalRegistry registry) {
		if (state == null || registry == null) throw new IllegalArgumentException("Chemical restore state/registry is missing");
		if (!state.registryFingerprint().equals(registry.fingerprint())
				|| !state.speciesPalette().equals(registry.species().stream()
				.map(com.example.chemistry.ChemicalSpecies::id).toList())) {
			throw new IllegalArgumentException("Chemical handoff registry identity mismatch");
		}
		restoreMaterialParticleState(state.materials());
		for (FluidMaterial material : materialRegistry.materials()) registry.phaseHandleForMaterial(material.id());
		ChemicalParcelStore restored = new ChemicalParcelStore(registry, maximumParticleCount, cellCount);
		int[] tableIndex = state.copyMarkerMixtureIndex();
		byte[] indicator = state.copyIndicator();
		for (int marker = 0; marker < particleCount; marker++) {
			ChemicalMixtureRecord mixture = state.mixtures().get(tableIndex[marker]).toRuntime(registry);
			restored.requireCompatible(mixture, materialRegistry.material(particleMaterialHandle[marker]).id(), indicator[marker] != 0);
			restored.assign(marker, mixture, indicator[marker] != 0);
		}
		if (!Arrays.equals(restored.speciesTotals(particleCount), state.copySpeciesTotals())) {
			throw new IllegalArgumentException("Restored chemical species ledger mismatch");
		}
		chemicalParcels = restored;
		chemicalParcels.restoreAuditLedgers(state.externalSource(), state.escapedSink());
		chemicalPreviousCells = new int[maximumParticleCount];
		chemicalMovingMarkers = new int[maximumParticleCount];
	}

	/** Exports an immutable CPU marker/velocity/solid handoff without serializing reconstructed density. */
	public FlipParticleState exportParticleState() {
		if (hasNonDefaultMaterialState()) {
			throw new IllegalStateException("Multi-material markers cannot use the pure-water particle handoff");
		}
		float[] positionVolume = new float[particleCount * 4];
		float[] velocities = new float[particleCount * 3];
		byte[] solidMask = cutCells.copyBinaryFullMask();
		for (int particle = 0; particle < particleCount; particle++) {
			int positionBase = particle * 4;
			int velocityBase = particle * 3;
			positionVolume[positionBase] = particleX[particle];
			positionVolume[positionBase + 1] = particleY[particle];
			positionVolume[positionBase + 2] = particleZ[particle];
			positionVolume[positionBase + 3] = particleVolume[particle];
			velocities[velocityBase] = particleVelocityX[particle];
			velocities[velocityBase + 1] = particleVelocityY[particle];
			velocities[velocityBase + 2] = particleVelocityZ[particle];
		}
		return new FlipParticleState(
				sizeX, sizeY, sizeZ, config.cellSize(), particleCount,
				totalVolume(), positionVolume, velocities, solidMask
		);
	}

	/** Exports exact multi-material marker state; runtime handles are replaced by the stable ordered palette. */
	public FlipMaterialParticleState exportMaterialParticleState() {
		if (materialRegistry == null) throw new IllegalStateException("Material particle handoff requires a bound registry");
		float[] positionVolume = new float[particleCount * 4];
		float[] velocities = new float[particleCount * 3];
		for (int particle = 0; particle < particleCount; particle++) {
			int positionBase = particle * 4, velocityBase = particle * 3;
			positionVolume[positionBase] = particleX[particle];
			positionVolume[positionBase + 1] = particleY[particle];
			positionVolume[positionBase + 2] = particleZ[particle];
			positionVolume[positionBase + 3] = particleVolume[particle];
			velocities[velocityBase] = particleVelocityX[particle];
			velocities[velocityBase + 1] = particleVelocityY[particle];
			velocities[velocityBase + 2] = particleVelocityZ[particle];
		}
		return new FlipMaterialParticleState(
				sizeX, sizeY, sizeZ, config.cellSize(), particleCount, totalVolumeExact(), totalMass(), materialRegistry.materials(),
				positionVolume, velocities, copySolidMask(),
				Arrays.copyOf(particleMass, particleCount), Arrays.copyOf(particleMaterialHandle, particleCount)
		);
	}

	/** Sets collision occupancy and returns the exact particle volume displaced. */
	public float setSolid(int x, int y, int z, boolean value) {
		return setSolidMicroMask(x, y, z, value ? FlipCutCellGeometry.FULL_MASK : 0);
	}

	/**
	 * Replaces one runtime 2x2x2 cut-cell mask and returns exact marker volume
	 * whose centers became occupied or exceeds the new open-cell capacity. This
	 * geometry is deliberately not persisted.
	 */
	public float setSolidMicroMask(int x, int y, int z, int mask) {
		ChemicalTransferLedger.Builder displaced = chemicalParcels == null ? null
				: ChemicalTransferLedger.builder(chemicalParcels.registry());
		float volume = setSolidMicroMaskInternal(x, y, z, mask, displaced);
		if (displaced != null) chemicalParcels.recordEscapedSink(displaced.build());
		return volume;
	}

	/** Exports canonical stable-ID chemical state; runtime mixture handles never cross this boundary. */
	public FlipChemicalParticleState exportChemicalParticleState() {
		if (chemicalParcels == null) throw new IllegalStateException("Chemical particle handoff requires chemistry");
		ArrayList<ChemicalMixtureRecord> mixtures = new ArrayList<>(particleCount);
		byte[] indicator = new byte[particleCount];
		for (int marker = 0; marker < particleCount; marker++) {
			mixtures.add(chemicalParcels.record(marker));
			indicator[marker] = chemicalParcels.indicator(marker) ? (byte) 1 : 0;
		}
		return FlipChemicalParticleState.capture(
				exportMaterialParticleState(), chemicalParcels.registry(), mixtures, indicator,
				chemicalParcels.externalSource(), chemicalParcels.escapedSink());
	}

	/** Collision edit that returns the complete parcel ledger for conservative nearby restoration. */
	public ChemicalDisplacement setSolidMicroMaskChemical(int x, int y, int z, int mask) {
		if (chemicalParcels == null) throw new IllegalStateException("Chemical collision transfer requires chemistry");
		ChemicalTransferLedger.Builder displaced = ChemicalTransferLedger.builder(chemicalParcels.registry());
		float volume = setSolidMicroMaskInternal(x, y, z, mask, displaced);
		return new ChemicalDisplacement(volume, displaced.build());
	}

	private float setSolidMicroMaskInternal(
			int x, int y, int z, int mask, ChemicalTransferLedger.Builder chemicalDisplacement
	) {
		int index = cellIndexChecked(x, y, z);
		int previous = cutCells.mask(index);
		if (previous == mask) return 0.0F;
		int newlyOccupied = mask & ~previous;
		if (newlyOccupied != 0 && granularCoupling != null) {
			ensureGrid();
			if (granularCoupling.solidSvq(index) > 0L || granularDeposits.cellSolidVolumeSvq(index) > 0L) {
				throw new IllegalStateException("Cannot overwrite granular inventory with runtime collision geometry");
			}
		}
		cutCells.replaceMask(index, mask);
		solid[index] = mask == FlipCutCellGeometry.FULL_MASK;
		pressure[index] = 0.0F;
		float displaced = newlyOccupied == 0 ? 0.0F
				: removeParticlesInMicroMask(x, y, z, newlyOccupied, chemicalDisplacement);
		float excess = Math.max(0.0F, exactVolumeInCell(x, y, z) - effectiveOpenCellVolume(index));
		if (excess > 1.0E-6F) displaced += removeParticlesInCell(x, y, z, excess, chemicalDisplacement);
		volume[index] = 0.0F;
		if (granularDepositDynamics != null) granularDepositDynamics.disturbCell(index);
		gridDirty = true;
		return displaced;
	}

	public boolean isSolid(int x, int y, int z) { return solid[cellIndexChecked(x, y, z)]; }
	public int solidMicroMask(int x, int y, int z) { return cutCells.mask(cellIndexChecked(x, y, z)); }
	public float solidVolumeFraction(int x, int y, int z) {
		return 1.0F - cutCells.openCellVolume(cellIndexChecked(x, y, z));
	}
	public float openFaceFractionX(int x, int y, int z) { return cutCells.xFaceOpen(x, y, z); }
	public float openFaceFractionY(int x, int y, int z) { return cutCells.yFaceOpen(x, y, z); }
	public float openFaceFractionZ(int x, int y, int z) { return cutCells.zFaceOpen(x, y, z); }

	public void setVolume(int x, int y, int z, float value) {
		if (granularCoupling != null) ensureGrid();
		int index = cellIndexChecked(x, y, z);
		float requested = clamp01(value);
		if (solid[index] && requested > 0.0F) throw new IllegalStateException("Cannot place fluid in a solid cell");
		float clamped = Math.min(requested, effectiveOpenCellVolume(index));
		int existingParticles = particleCountInCell(x, y, z);
		int requiredParticles = particlesRequired(clamped);
		if ((long) particleCount - existingParticles + requiredParticles > maximumParticleCount) {
			throw new ParticleBudgetExceededException(requiredParticles, maximumParticleCount - particleCount + existingParticles);
		}
		removeParticlesInCell(x, y, z, Float.POSITIVE_INFINITY);
		spawnCellVolume(x, y, z, clamped);
		gridDirty = true;
	}

	public float volume(int x, int y, int z) {
		ensureGrid();
		return volume[cellIndexChecked(x, y, z)];
	}

	public float pressure(int x, int y, int z) { return pressure[cellIndexChecked(x, y, z)]; }

	/** Package-private test accessor for the free-surface pressure jump {@code sigma * kappa} at a cell. */
	float surfaceTensionPressureAt(int x, int y, int z) { return surfaceTensionPressure[cellIndexChecked(x, y, z)]; }

	public float totalVolume() {
		return (float) totalVolumeExact();
	}

	/** Exact double sum of the float marker-volume truth, in stable marker ordinal order. */
	public double totalVolumeExact() {
		double result = 0.0;
		for (int i = 0; i < particleCount; i++) result += particleVolume[i];
		return result;
	}

	/** Total open cell volume across the domain; global capacity for exact bucket insertion. */
	public float totalOpenVolume() {
		return (float) totalOpenVolumeExact();
	}

	public double totalOpenVolumeExact() {
		if (granularCoupling != null && gridDirty) ensureGrid();
		double result = 0.0;
		for (int cell = 0; cell < cellCount; cell++) result += effectiveOpenCellVolume(cell);
		return result;
	}

	private double totalGeometricOpenVolumeExact() {
		double result = 0.0;
		for (int cell = 0; cell < cellCount; cell++) result += cutCells.openCellVolume(cell);
		return result;
	}

	private float effectiveOpenCellVolume(int cell) {
		float geometric = cutCells.openCellVolume(cell);
		return granularCoupling == null ? geometric : granularCoupling.effectiveOpenCellVolume(cell, geometric);
	}

	private float pressureOpenCellVolume(int cell) {
		float geometric = cutCells.openCellVolume(cell);
		return pressureGranularCoupling == null ? geometric
				: pressureGranularCoupling.effectiveOpenCellVolume(cell, geometric);
	}


	/** Exact marker-mass inventory; reconstructed grid density is never used for this ledger. */
	public double totalMass() {
		double result = 0.0;
		for (int particle = 0; particle < particleCount; particle++) result += particleMass[particle];
		return result;
	}

	/** Returns the material handle with the largest exact marker volume in the given cell. */
	public int dominantMaterialInCell(int x, int y, int z) {
		int cell = cellIndexChecked(x, y, z);
		if (materialRegistry == null) return 0;
		float[] volumes = new float[materialRegistry.size()];
		float h = config.cellSize();
		for (int particle = 0; particle < particleCount; particle++) {
			if (particleCellIndex(particle) != cell) continue;
			volumes[particleMaterialHandle[particle]] += particleVolume[particle];
		}
		int best = 0;
		for (int handle = 1; handle < volumes.length; handle++) {
			if (volumes[handle] > volumes[best]) best = handle;
		}
		return best;
	}

	/** Exact nearby marker volume for planning a partial container extraction; -1 selects all materials. */
	public double exactVolumeNear(int centerX, int centerY, int centerZ, int maxRadius, int materialHandle) {
		if (maxRadius < 0) throw new IllegalArgumentException("maxRadius cannot be negative");
		boolean[] near = new boolean[cellCount];
		for (CellCandidate candidate : nearbyCells(centerX, centerY, centerZ, maxRadius)) near[candidate.index()] = true;
		double result = 0.0;
		for (int particle = 0; particle < particleCount; particle++) {
			if (materialHandle >= 0 && particleMaterialHandle[particle] != materialHandle) continue;
			if (near[particleCellIndex(particle)]) result += particleVolume[particle];
		}
		return result;
	}


	MaterialInventory materialInventory(int materialHandle) {
		double volume = 0.0, mass = 0.0, weightedY = 0.0;
		for (int particle = 0; particle < particleCount; particle++) {
			if (particleMaterialHandle[particle] != materialHandle) continue;
			volume += particleVolume[particle];
			mass += particleMass[particle];
			weightedY += particleY[particle] / config.cellSize() * particleVolume[particle];
		}
		return new MaterialInventory(volume, mass, volume == 0.0 ? Double.NaN : weightedY / volume);
	}

	int phaseCountInCell(int x, int y, int z) {
		ensureGrid();
		int result = 0;
		for (int entry = cellPhaseHead[cellIndexChecked(x, y, z)]; entry >= 0; entry = phaseEntryNext[entry]) result++;
		return result;
	}

	/** In-memory M3 assignment only; callers outside the fluid package cannot create world material state yet. */
	void setMaterialInCell(int x, int y, int z, FluidMaterialRegistry registry, int materialHandle) {
		if (registry == null) throw new IllegalArgumentException("Material registry must not be null");
		registry.material(materialHandle);
		int cell = cellIndexChecked(x, y, z);
		boolean found = false;
		for (int particle = 0; particle < particleCount; particle++) {
			if (particleCellIndex(particle) == cell) found = true;
		}
		if (!found) throw new IllegalArgumentException("Cannot assign material to an empty pressure cell");
		bindMaterialRegistry(registry);
		for (int particle = 0; particle < particleCount; particle++) {
			if (particleCellIndex(particle) == cell) assignMarkerMaterial(particle, registry, materialHandle);
		}
		gridDirty = true;
	}

	/** Marker-granular package test injection; indices are intentionally not exposed as a public state contract. */
	void setMarkerMaterial(int particle, FluidMaterialRegistry registry, int materialHandle) {
		if (particle < 0 || particle >= particleCount) throw new IllegalArgumentException("Unknown marker index " + particle);
		if (registry == null) throw new IllegalArgumentException("Material registry must not be null");
		registry.material(materialHandle);
		bindMaterialRegistry(registry);
		assignMarkerMaterial(particle, registry, materialHandle);
		gridDirty = true;
	}

	private void bindMaterialRegistry(FluidMaterialRegistry registry) {
		if (registry == null) throw new IllegalArgumentException("Material registry must not be null");
		if (materialRegistry != null && materialRegistry != registry) {
			throw new IllegalArgumentException("A domain cannot mix runtime handles from different material registries");
		}
		if (materialRegistry != null) return;
		double defaultDensity = registry.material(0).referenceDensity();
		double tolerance = Math.max(1.0E-9, Math.abs(config.density()) * 1.0E-12);
		if (Math.abs(defaultDensity - config.density()) > tolerance) {
			throw new IllegalArgumentException("Material handle 0 must match the domain default density");
		}
		materialRegistry = registry;
	}

	private void assignMarkerMaterial(int particle, FluidMaterialRegistry registry, int materialHandle) {
		if (chemicalParcels != null) {
			throw new IllegalStateException("Material relabeling requires an exact chemical transfer");
		}
		FluidMaterial material = registry.material(materialHandle);
		double mass = (double) particleVolume[particle] * material.referenceDensity();
		if (!(mass <= Float.MAX_VALUE)) throw new IllegalArgumentException("Material mass exceeds P2G range");
		particleMass[particle] = mass;
		particleMaterialHandle[particle] = materialHandle;
		particlePhaseLabel[particle] = registry.phaseHandle(materialHandle);
	}

	private boolean hasNonDefaultMaterialState() {
		if (materialRegistry != null) return true;
		for (int particle = 0; particle < particleCount; particle++) {
			if (particleMaterialHandle[particle] != 0 || particlePhaseLabel[particle] != 0) return true;
			float referenceMass = particleVolume[particle] * config.density();
			float tolerance = Math.max(1.0E-5F, Math.ulp(referenceMass) * 8.0F);
			if (Math.abs(particleMass[particle] - referenceMass) > tolerance) return true;
		}
		return false;
	}

	boolean usesMassWeightedP2G() {
		for (int particle = 0; particle < particleCount; particle++) {
			double referenceMass = (double) particleVolume[particle] * config.density();
			double tolerance = Math.max(1.0E-9, Math.abs(referenceMass) * 1.0E-12);
			if (Math.abs(particleMass[particle] - referenceMass) > tolerance) return true;
		}
		return false;
	}

	/** Maximum marker speed used only as a scheduling observation; it is not a water-volume estimate. */
	public float maximumParticleSpeed() {
		float maximumSquared = 0.0F;
		for (int i = 0; i < particleCount; i++) {
			float squared = particleVelocityX[i] * particleVelocityX[i]
					+ particleVelocityY[i] * particleVelocityY[i]
					+ particleVelocityZ[i] * particleVelocityZ[i];
			maximumSquared = Math.max(maximumSquared, squared);
		}
		return (float) Math.sqrt(maximumSquared);
	}

	/**
	 * Commits a validated hydrostatic scheduling transition by removing residual marker momentum.
	 * Marker positions, counts, particleVolume and particleMass are unchanged, so both exact ledgers are unchanged.
	 */
	public void commitHydrostaticSleep() {
		Arrays.fill(particleVelocityX, 0, particleCount, 0.0F);
		Arrays.fill(particleVelocityY, 0, particleCount, 0.0F);
		Arrays.fill(particleVelocityZ, 0, particleCount, 0.0F);
		gridDirty = true;
	}

	public float maximumPressure() {
		float maximum = 0.0F;
		for (float value : pressure) maximum = Math.max(maximum, value);
		return maximum;
	}

	public byte[] copyQuantizedVolume() {
		ensureGrid();
		byte[] result = new byte[cellCount];
		for (int i = 0; i < cellCount; i++) result[i] = (byte) Math.round(clamp01(volume[i]) * 255.0F);
		return result;
	}

	public byte[] copySolidMask() {
		return cutCells.copyBinaryFullMask();
	}

	/** Actual pressure-phase occupancy, separate from the smooth P2G density. */
	public byte[] copyMarkerMask() {
		ensureGrid();
		byte[] result = new byte[cellCount];
		for (int i = 0; i < cellCount; i++) result[i] = markerCount[i] > 0 ? (byte) 1 : (byte) 0;
		return result;
	}

	/** Read-only M3 rendering reconstruction; it contains no exact inventory and is not a wire format. */
	public FluidMaterialRenderSnapshot copyMaterialRenderSnapshot() {
		ensureGrid();
		if (materialRegistry == null) throw new IllegalStateException("Material render snapshot requires a bound registry");
		return FluidMaterialRenderSnapshot.capture(
				sizeX, sizeY, sizeZ, config.cellSize(), materialRegistry,
				copyQuantizedVolume(), copyMarkerMask(), copySolidMask(),
				particleX, particleY, particleZ, particleVolume, particleMaterialHandle, particleCount,
				exactCellMarkerVolume
		);
	}

	public VolumeDistribution volumeDistribution(float visibleThreshold) {
		ensureGrid();
		int active = 0, visible = 0, dense = 0;
		for (float value : volume) {
			if (value > config.fluidEpsilon()) active++;
			if (value >= visibleThreshold) visible++;
			if (value >= 0.90F) dense++;
		}
		return new VolumeDistribution(active, visible, dense);
	}

	public SolidDistribution solidDistribution() {
		int partial = 0, full = 0;
		for (int index = 0; index < cellCount; index++) {
			int mask = cutCells.mask(index);
			if (mask == FlipCutCellGeometry.FULL_MASK) full++;
			else if (mask != 0) partial++;
		}
		return new SolidDistribution(partial, full);
	}

	public boolean containsFluidAt(float localX, float localY, float localZ) {
		ensureGrid();
		float h = config.cellSize();
		int x = (int) Math.floor(localX / h), y = (int) Math.floor(localY / h), z = (int) Math.floor(localZ / h);
		if (!inside(x, y, z)) return false;
		int index = cellIndex(x, y, z);
		if (solid[index] || cutCells.pointInsideSolid(localX, localY, localZ, h)
				|| volume[index] <= config.fluidEpsilon()) return false;
		float openVolume = effectiveOpenCellVolume(index);
		float reconstructedFill = openVolume <= 0.0F ? 0.0F : clamp01(volume[index] / openVolume);
		return localY - y * h < reconstructedFill * h;
	}

	public VelocitySample sampleVelocity(float localX, float localY, float localZ) {
		ensureGrid();
		if (localX < 0.0F || localX > sizeX * config.cellSize()
				|| localY < 0.0F || localY > sizeY * config.cellSize()
				|| localZ < 0.0F || localZ > sizeZ * config.cellSize()) return VelocitySample.ZERO;
		return new VelocitySample(
				sampleX(velocityX, localX, localY, localZ),
				sampleY(velocityY, localX, localY, localZ),
				sampleZ(velocityZ, localX, localY, localZ)
		);
	}

	public float centerOfMassX() {
		double weighted = 0.0, mass = 0.0;
		for (int i = 0; i < particleCount; i++) {
			weighted += particleX[i] / config.cellSize() * particleVolume[i];
			mass += particleVolume[i];
		}
		return mass == 0.0 ? Float.NaN : (float) (weighted / mass);
	}

	public float addVolumeNear(int centerX, int centerY, int centerZ, float amount, int maxRadius) {
		return addVolumeNearBudgeted(centerX, centerY, centerZ, amount, maxRadius).addedVolume();
	}

	public VolumeAddition addVolumeNearBudgeted(int centerX, int centerY, int centerZ, float amount, int maxRadius) {
		validateVolumeEdit(amount, maxRadius);
		double[] exactCellVolume = exactCellVolumes();
		float remaining = amount;
		boolean markerBudgetLimited = false;
		for (CellCandidate candidate : nearbyCells(centerX, centerY, centerZ, maxRadius)) {
			int index = candidate.index();
			int x = index % sizeX, yz = index / sizeX, y = yz % sizeY, z = yz / sizeY;
			float requestedForCell = (float) Math.min(remaining,
					Math.max(0.0, effectiveOpenCellVolume(index) - exactCellVolume[index]));
			int availableMarkers = maximumParticleCount - particleCount;
			float markerCapacity = availableMarkers * PARTICLE_VOLUME;
			float added = Math.min(requestedForCell, markerCapacity);
			if (added <= 1.0E-6F) {
				if (requestedForCell > 1.0E-6F && availableMarkers == 0) markerBudgetLimited = true;
				if (markerBudgetLimited) break;
				continue;
			}
			spawnCellVolume(x, y, z, added);
			exactCellVolume[index] += added;
			remaining -= added;
			if (added + 1.0E-6F < requestedForCell) {
				markerBudgetLimited = true;
				break;
			}
			if (remaining <= 1.0E-6F) break;
		}
		gridDirty = true;
		return new VolumeAddition(amount - remaining, markerBudgetLimited);
	}

	public float removeVolumeNear(int centerX, int centerY, int centerZ, float amount, int maxRadius) {
		validateVolumeEdit(amount, maxRadius);
		float remaining = amount;
		for (CellCandidate candidate : nearbyCells(centerX, centerY, centerZ, maxRadius)) {
			int index = candidate.index();
			int x = index % sizeX, yz = index / sizeX, y = yz % sizeY, z = yz / sizeY;
			remaining -= removeParticlesInCell(x, y, z, remaining);
			if (remaining <= 1.0E-6F) break;
		}
		gridDirty = true;
		return amount - remaining;
	}

	/** Removes every marker; the exact marker-volume ledger becomes zero immediately. */
	public void removeAllWaterExact() {
		if (chemicalParcels != null) {
			ChemicalTransferLedger.Builder drained = ChemicalTransferLedger.builder(chemicalParcels.registry());
			for (int marker = 0; marker < particleCount; marker++) drained.add(
					materialRegistry.material(particleMaterialHandle[marker]).id(), chemicalParcels.indicator(marker),
					particleVolume[marker], particleMass[marker], chemicalParcels.record(marker));
			chemicalParcels.recordEscapedSink(drained.build());
			chemicalParcels.clear(particleCount);
		}
		particleCount = 0;
		gridDirty = true;
	}


	/**
	 * M6 container transfer primitive: all-or-nothing insertion of an exact volume near the
	 * target. Cells, pieces and the marker budget are planned before any state changes, so a
	 * refusal leaves the domain untouched. Pieces are {@code PARTICLE_VOLUME} chunks plus one
	 * float remainder; the returned volume is the exact double sum of the stored pieces.
	 */
	public ExactVolumeTransfer tryInsertExactVolume(int centerX, int centerY, int centerZ, float volume, int maxRadius) {
		return tryInsertExactVolume(centerX, centerY, centerZ, volume, maxRadius, 0);
	}

	public ExactVolumeTransfer tryInsertExactVolume(int centerX, int centerY, int centerZ, float volume, int maxRadius, int materialHandle) {
		return tryInsertExactVolume(centerX, centerY, centerZ, volume, maxRadius, materialHandle, false);
	}

	public ExactVolumeTransfer tryInsertExactVolume(
			int centerX, int centerY, int centerZ, float volume, int maxRadius, int materialHandle,
			boolean externalSource
	) {
		validateVolumeEdit(volume, maxRadius);
		if (volume <= 0.0F) return new ExactVolumeTransfer(volume == 0.0F, 0.0, 0.0);
		double mass = materialRegistry == null
				? (double) volume * config.density()
				: (double) volume * materialRegistry.material(materialHandle).referenceDensity();
		return tryInsertExactMaterial(centerX, centerY, centerZ, volume, mass, maxRadius, materialHandle, externalSource);
	}

	/** Inserts independent material volume and mass ledgers without deriving either from the other. */
	public ExactVolumeTransfer tryInsertExactMaterial(
			int centerX, int centerY, int centerZ, float volume, double mass, int maxRadius, int materialHandle
	) {
		return tryInsertExactMaterial(centerX, centerY, centerZ, volume, mass, maxRadius, materialHandle, false);
	}

	private ExactVolumeTransfer tryInsertExactMaterial(
			int centerX, int centerY, int centerZ, float volume, double mass, int maxRadius, int materialHandle,
			boolean externalSource
	) {
		if (volume == 0.0F && mass == 0.0) return new ExactVolumeTransfer(true, 0.0, 0.0);
		ExactMixtureTransfer transfer = tryInsertExactMixture(centerX, centerY, centerZ,
				List.of(new MaterialPortion(materialHandle, volume, mass)), maxRadius, externalSource);
		return transfer.transferred()
				? new ExactVolumeTransfer(true, transfer.portions().getFirst().volume(),
						transfer.portions().getFirst().mass())
				: new ExactVolumeTransfer(false, 0.0, 0.0);
	}

	/** Plans every mixture component against shared capacity before inserting any marker. */
	public ExactMixtureTransfer tryInsertExactMixture(
			int centerX, int centerY, int centerZ, List<MaterialPortion> portions, int maxRadius
	) {
		return tryInsertExactMixture(centerX, centerY, centerZ, portions, maxRadius, false);
	}

	public ExactMixtureTransfer tryInsertExactMixture(
			int centerX, int centerY, int centerZ, List<MaterialPortion> portions, int maxRadius,
			boolean externalSource
	) {
		if (portions == null || portions.isEmpty()) throw new IllegalArgumentException("Mixture insertion is empty");
		if (maxRadius < 0) throw new IllegalArgumentException("maxRadius cannot be negative");
		java.util.Set<Integer> materialHandles = new java.util.HashSet<>();
		for (MaterialPortion portion : portions) {
			validateMaterialPortion(portion);
			if (!materialHandles.add(portion.materialHandle())) {
				throw new IllegalArgumentException("Mixture insertion contains duplicate material handle "
						+ portion.materialHandle());
			}
		}
		double[] exactCellVolume = exactCellVolumes();
		java.util.List<InsertAllocation> plan = new java.util.ArrayList<>();
		long requiredMarkers = 0L;
		List<CellCandidate> nearby = nearbyCells(centerX, centerY, centerZ, maxRadius);
		for (int portionIndex = 0; portionIndex < portions.size(); portionIndex++) {
			MaterialPortion portion = portions.get(portionIndex);
			double remaining = portion.volume();
			for (CellCandidate candidate : nearby) {
				if (remaining <= 0.0) break;
				int index = candidate.index();
				double capacity = Math.max(0.0, effectiveOpenCellVolume(index) - exactCellVolume[index]);
				double allocate = Math.min(capacity, remaining);
				if (allocate <= 0.0) continue;
				requiredMarkers += (long) Math.ceil(allocate / PARTICLE_VOLUME);
				if (particleCount + requiredMarkers > maximumParticleCount) {
					return new ExactMixtureTransfer(false, List.of());
				}
				int x = index % sizeX, yz = index / sizeX, y = yz % sizeY, z = yz / sizeY;
				plan.add(new InsertAllocation(x, y, z, allocate, portion.materialHandle(), portionIndex));
				exactCellVolume[index] += allocate;
				remaining -= allocate;
			}
			if (remaining > 0.0) return new ExactMixtureTransfer(false, List.of());
		}

		double[] inserted = new double[portions.size()];
		double[] insertedMass = new double[portions.size()];
		double[] volumeLeft = new double[portions.size()];
		double[] massLeft = new double[portions.size()];
		for (int i = 0; i < portions.size(); i++) {
			volumeLeft[i] = portions.get(i).volume();
			massLeft[i] = portions.get(i).mass();
		}
		List<MaterialInsertPiece> insertPieces = new ArrayList<>();
		long plannedChemicalQuanta = 0L;
		for (InsertAllocation allocation : plan) {
			int portionIndex = allocation.portionIndex();
			double left = allocation.amount();
			int slot = firstOpenSlot(cellIndex(allocation.x(), allocation.y(), allocation.z()), 0);
			while (left > 0.0) {
				boolean allocationRemainder = left < PARTICLE_VOLUME;
				float piece = allocationRemainder ? (float) left : PARTICLE_VOLUME;
				if (slot < 0) throw new IllegalStateException("Cut-cell open volume was exhausted while spawning markers");
				boolean finalPortionPiece = volumeLeft[portionIndex] - piece
						<= Math.ulp((float) volumeLeft[portionIndex]);
				double pieceMass = finalPortionPiece ? massLeft[portionIndex]
						: massLeft[portionIndex] * piece / volumeLeft[portionIndex];
				ChemicalMixtureRecord chemical = chemicalParcels == null ? null : chemicalParcels.defaultRecord(
						materialRegistry.material(allocation.materialHandle()).id(), piece, config.cellSize(), pieceMass, false);
				if (chemical != null) plannedChemicalQuanta = Math.addExact(plannedChemicalQuanta, chemical.totalQuanta());
				insertPieces.add(new MaterialInsertPiece(allocation.x(), allocation.y(), allocation.z(), slot, piece,
						allocation.materialHandle(), pieceMass, chemical));
				inserted[portionIndex] += piece;
				insertedMass[portionIndex] += pieceMass;
				left = allocationRemainder ? 0.0 : left - piece;
				if (finalPortionPiece) {
					volumeLeft[portionIndex] = 0.0;
					massLeft[portionIndex] = 0.0;
				} else {
					volumeLeft[portionIndex] -= piece;
					massLeft[portionIndex] -= pieceMass;
				}
				slot = firstOpenSlot(cellIndex(allocation.x(), allocation.y(), allocation.z()), slot + 1);
			}
		}
		if (chemicalParcels != null && Math.addExact(chemicalParcels.totalQuanta(), plannedChemicalQuanta)
				> ChemicalLimits.MAX_REGION_TOTAL_QUANTA) {
			return new ExactMixtureTransfer(false, List.of());
		}
		ChemicalTransferLedger externalAddition = ChemicalTransferLedger.EMPTY;
		if (externalSource && chemicalParcels != null) {
			try {
				ChemicalTransferLedger.Builder source = ChemicalTransferLedger.builder(chemicalParcels.registry());
				for (MaterialInsertPiece piece : insertPieces) source.add(
						materialRegistry.material(piece.materialHandle()).id(), false,
						piece.volume(), piece.mass(), piece.chemical());
				externalAddition = source.build();
				chemicalParcels.requireCanRecordExternalSource(externalAddition);
			} catch (IllegalArgumentException | ArithmeticException refusedAudit) {
				return new ExactMixtureTransfer(false, List.of());
			}
		}
		for (MaterialInsertPiece piece : insertPieces) {
			if (piece.chemical() == null) spawnParticle(piece.cellX(), piece.cellY(), piece.cellZ(), piece.slot(),
					piece.volume(), piece.materialHandle(), piece.mass());
			else spawnChemicalParticle(piece.cellX(), piece.cellY(), piece.cellZ(), piece.slot(), piece.volume(),
					piece.materialHandle(), piece.mass(), piece.chemical(), false);
		}
		if (!externalAddition.isEmpty()) chemicalParcels.recordExternalSource(externalAddition);
		gridDirty = true;
		List<MaterialPortion> result = new java.util.ArrayList<>(portions.size());
		for (int i = 0; i < portions.size(); i++) {
			result.add(new MaterialPortion(portions.get(i).materialHandle(), inserted[i], insertedMass[i]));
		}
		return new ExactMixtureTransfer(true, List.copyOf(result));
	}

	/** Plans and restores an exact chemical ledger without substituting a default carrier. */
	public ExactChemicalTransfer tryInsertExactChemical(
			int centerX, int centerY, int centerZ, ChemicalTransferLedger ledger, int maxRadius
	) {
		return tryInsertExactChemical(centerX, centerY, centerZ, ledger, maxRadius, false);
	}

	public ExactChemicalTransfer tryInsertExactChemical(
			int centerX, int centerY, int centerZ, ChemicalTransferLedger ledger, int maxRadius,
			boolean externalSource
	) {
		if (chemicalParcels == null) throw new IllegalStateException("Chemical insertion requires chemistry");
		if (ledger == null || ledger.isEmpty()) return new ExactChemicalTransfer(ledger != null, ChemicalTransferLedger.EMPTY);
		if (maxRadius < 0) throw new IllegalArgumentException("maxRadius cannot be negative");
		try {
			if (Math.addExact(chemicalParcels.totalQuanta(), ledger.totalQuanta())
					> com.example.chemistry.ChemicalLimits.MAX_REGION_TOTAL_QUANTA) {
				return new ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
			}
		} catch (ArithmeticException overflow) {
			return new ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
		}
		double[] exactCellVolume = exactCellVolumes();
		List<CellCandidate> nearby = nearbyCells(centerX, centerY, centerZ, maxRadius);
		List<ChemicalInsertPiece> pieces = new ArrayList<>();
		try {
		for (ChemicalTransferLedger.Group group : ledger.groups()) {
			int material = materialRegistry.handle(group.materialId());
			double remaining = group.volume();
			List<ChemicalPieceDraft> groupPieces = new ArrayList<>();
			for (CellCandidate candidate : nearby) {
				if (remaining <= 0.0) break;
				double capacity = Math.max(0.0,
						effectiveOpenCellVolume(candidate.index()) - exactCellVolume[candidate.index()]);
				double allocation = Math.min(capacity, remaining);
				if (allocation <= 0.0) continue;
				int cellX = candidate.index() % sizeX;
				int yz = candidate.index() / sizeX;
				int cellY = yz % sizeY, cellZ = yz / sizeY;
				double left = allocation;
				while (left > 0.0) {
					float piece = (float) Math.min(PARTICLE_VOLUME, left);
					while (piece > left) piece = Math.nextDown(piece);
					if (!(piece > 0.0F)) return new ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
					groupPieces.add(new ChemicalPieceDraft(cellX, cellY, cellZ, piece, material));
					if ((long) particleCount + pieces.size() + groupPieces.size() > maximumParticleCount) {
						return new ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
					}
					left -= piece;
				}
				exactCellVolume[candidate.index()] += allocation;
				remaining -= allocation;
			}
			if (remaining > 0.0) return new ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
			float[] weights = new float[groupPieces.size()];
			int[] ordinals = new int[groupPieces.size()];
			for (int piece = 0; piece < groupPieces.size(); piece++) {
				weights[piece] = groupPieces.get(piece).volume();
				ordinals[piece] = particleCount + pieces.size() + piece;
			}
			@SuppressWarnings("unchecked")
			TreeMap<String, Long>[] distributed = new TreeMap[groupPieces.size()];
			for (int piece = 0; piece < distributed.length; piece++) distributed[piece] = new TreeMap<>();
			for (Map.Entry<String, Long> species : group.speciesQuanta().entrySet()) {
				long[] allocation = com.example.chemistry.BinaryVolumeAllocator.allocate(species.getValue(), weights, ordinals);
				for (int piece = 0; piece < allocation.length; piece++) if (allocation[piece] > 0L) {
					distributed[piece].put(species.getKey(), allocation[piece]);
				}
			}
			double volumeLeft = group.volume(), massLeft = group.mass();
			for (int piece = 0; piece < groupPieces.size(); piece++) {
				if (distributed[piece].isEmpty()) return new ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
				ChemicalPieceDraft draft = groupPieces.get(piece);
				boolean last = piece + 1 == groupPieces.size();
				double mass = last ? massLeft : massLeft * draft.volume() / volumeLeft;
				ChemicalMixtureRecord withoutResidual = ChemicalMixtureRecord.fromStableAmounts(
						chemicalParcels.registry(), distributed[piece], 0.0);
				ChemicalMixtureRecord record = ChemicalMixtureRecord.fromStableAmounts(
						chemicalParcels.registry(), distributed[piece],
						mass - withoutResidual.exactSpeciesMass(chemicalParcels.registry()).toDouble());
				chemicalParcels.requireCompatible(record, group.materialId(), group.indicator());
				pieces.add(new ChemicalInsertPiece(draft.cellX(), draft.cellY(), draft.cellZ(),
						draft.volume(), draft.materialHandle(), mass, record, group.indicator()));
				volumeLeft -= draft.volume();
				massLeft -= mass;
			}
		}
		} catch (IllegalArgumentException | ArithmeticException
				| com.example.chemistry.ChemicalReactionEngine.BudgetExceededException refusedPlan) {
			return new ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
		}
		if (externalSource) try {
			chemicalParcels.requireCanRecordExternalSource(ledger);
		} catch (IllegalArgumentException | ArithmeticException refusedAudit) {
			return new ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
		}
		for (ChemicalInsertPiece piece : pieces) {
			int slot = firstOpenSlot(cellIndex(piece.cellX(), piece.cellY(), piece.cellZ()), 0);
			if (slot < 0) throw new IllegalStateException("Chemical insertion lost its planned open slot");
			spawnChemicalParticle(piece.cellX(), piece.cellY(), piece.cellZ(), slot, piece.volume(),
					piece.materialHandle(), piece.mass(), piece.mixture(), piece.indicator());
		}
		if (externalSource) chemicalParcels.recordExternalSource(ledger);
		gridDirty = true;
		return new ExactChemicalTransfer(true, ledger);
	}

	/** All-or-nothing extraction of full species/residual/indicator truth for a chemical container. */
	public ExactChemicalTransfer tryExtractExactChemical(
			int centerX, int centerY, int centerZ, float volume, int maxRadius
	) {
		return tryExtractExactChemical(centerX, centerY, centerZ, volume, maxRadius, ignored -> true);
	}

	/** Applies the caller's bounded representation check to the complete ledger before committing removal. */
	public ExactChemicalTransfer tryExtractExactChemical(
			int centerX, int centerY, int centerZ, float volume, int maxRadius,
			java.util.function.Predicate<ChemicalTransferLedger> acceptsLedger
	) {
		if (chemicalParcels == null) throw new IllegalStateException("Chemical extraction requires chemistry");
		if (acceptsLedger == null) throw new IllegalArgumentException("Chemical extraction validator is missing");
		validateVolumeEdit(volume, maxRadius);
		if (volume <= 0.0F) return new ExactChemicalTransfer(volume == 0.0F, ChemicalTransferLedger.EMPTY);
		List<CellCandidate> cells = nearbyCells(centerX, centerY, centerZ, maxRadius);
		boolean[] near = new boolean[cellCount]; for (CellCandidate candidate : cells) near[candidate.index()] = true;
		double available = 0.0;
		for (int marker = 0; marker < particleCount; marker++) if (near[particleCellIndex(marker)]) {
			available += particleVolume[marker];
		}
		if (available < volume) return new ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);

		double remaining = volume, settlementTolerance = Math.ulp(volume);
		List<ExtractionAllocation> plan = new ArrayList<>();
		ChemicalTransferLedger.Builder ledger = ChemicalTransferLedger.builder(chemicalParcels.registry());
		for (CellCandidate candidate : cells) {
			if (remaining <= 0.0) break;
			for (int marker = particleCount - 1; marker >= 0 && remaining > 0.0; marker--) {
				if (particleCellIndex(marker) != candidate.index()) continue;
				float previousVolume = particleVolume[marker];
				double take = Math.min(previousVolume, remaining);
				float newVolume = (float) (previousVolume - take);
				double actual = previousVolume - newVolume;
				if (actual > remaining && newVolume < previousVolume) {
					newVolume = Math.nextUp(newVolume); actual = previousVolume - newVolume;
					settlementTolerance = Math.max(settlementTolerance, Math.ulp(previousVolume));
				}
				if (actual <= 0.0) continue;
				double previousMass = particleMass[marker];
				double newMass = newVolume == 0.0F ? 0.0 : previousMass * newVolume / previousVolume;
				double removedMass = previousMass - newMass;
				ChemicalParcelStore.SplitPlan chemicalPlan = chemicalParcels.planTruncate(
						marker, newVolume, previousVolume - newVolume, newMass, removedMass);
				ledger.add(materialRegistry.material(particleMaterialHandle[marker]).id(), chemicalParcels.indicator(marker),
						actual, removedMass, chemicalPlan.removed());
				plan.add(new ExtractionAllocation(marker, newVolume, newMass, chemicalPlan));
				remaining -= actual;
			}
		}
		if (remaining > settlementTolerance) return new ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
		ChemicalTransferLedger removed = ledger.build();
		if (removed.totalQuanta() > ChemicalLimits.MAX_BUCKET_TOTAL_QUANTA) {
			return new ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
		}
		if (!acceptsLedger.test(removed)) return new ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
		for (ExtractionAllocation allocation : plan) {
			if (allocation.chemicalPlan().kept() != null) {
				chemicalParcels.commitTruncate(allocation.particle(), allocation.chemicalPlan());
			}
			particleVolume[allocation.particle()] = allocation.newVolume();
			particleMass[allocation.particle()] = allocation.newMass();
		}
		for (int marker = particleCount - 1; marker >= 0; marker--) if (particleVolume[marker] == 0.0F) removeParticle(marker);
		gridDirty = true;
		return new ExactChemicalTransfer(true, removed);
	}

	/**
	 * M6 container transfer primitive: all-or-nothing extraction of an exact volume near the
	 * target. Availability is verified in double precision first; removal takes whole markers and
	 * splits the last one, so the returned volume is the exact double sum of real marker
	 * decreases and exceeds the request by at most one ulp of the split marker.
	 */
public ExactVolumeTransfer tryExtractExactVolume(int centerX, int centerY, int centerZ, float volume, int maxRadius) {
return tryExtractExactVolume(centerX, centerY, centerZ, volume, maxRadius, 0);
}

public ExactVolumeTransfer tryExtractExactVolume(int centerX, int centerY, int centerZ, float volume, int maxRadius, int materialHandle) {
	validateVolumeEdit(volume, maxRadius);
	if (volume <= 0.0F) return new ExactVolumeTransfer(volume == 0.0F, 0.0, 0.0);
	ExactMixtureTransfer transfer = tryExtractExactMixtureInternal(
			centerX, centerY, centerZ, volume, maxRadius, materialHandle);
	if (!transfer.transferred()) return new ExactVolumeTransfer(false, 0.0, 0.0);
	MaterialPortion portion = transfer.portions().getFirst();
	return new ExactVolumeTransfer(true, portion.volume(), portion.mass());
}

	/** Extracts nearby materials in deterministic cell/marker order and returns every component. */
	public ExactMixtureTransfer tryExtractExactMixture(
			int centerX, int centerY, int centerZ, float volume, int maxRadius
	) {
		validateVolumeEdit(volume, maxRadius);
		if (volume <= 0.0F) return new ExactMixtureTransfer(volume == 0.0F, List.of());
		return tryExtractExactMixtureInternal(centerX, centerY, centerZ, volume, maxRadius, -1);
	}

	private ExactMixtureTransfer tryExtractExactMixtureInternal(
			int centerX, int centerY, int centerZ, float volume, int maxRadius, int requiredMaterialHandle
	) {
		java.util.List<CellCandidate> cells = nearbyCells(centerX, centerY, centerZ, maxRadius);
		boolean[] near = new boolean[sizeX * sizeY * sizeZ];
		for (CellCandidate candidate : cells) near[candidate.index()] = true;
		float h = config.cellSize();
		double available = 0.0;
		for (int i = 0; i < particleCount; i++) {
			if (requiredMaterialHandle >= 0 && particleMaterialHandle[i] != requiredMaterialHandle) continue;
			if (chemicalParcels != null && !chemicalParcels.isCanonicalDefaultCarrier(
					i, materialRegistry.material(particleMaterialHandle[i]).id())) continue;
			if (!near[cellIndex((int) Math.floor(particleX[i] / h), (int) Math.floor(particleY[i] / h),
					(int) Math.floor(particleZ[i] / h))]) continue;
			available += particleVolume[i];
		}
		if (available < volume) return new ExactMixtureTransfer(false, List.of());

		double remaining = volume;
		double settlementTolerance = Math.ulp(volume);
		java.util.List<ExtractionAllocation> plan = new java.util.ArrayList<>();
		int ledgerSize = materialRegistry == null ? 1 : materialRegistry.size();
		double[] volumeBefore = new double[ledgerSize];
		double[] massBefore = new double[ledgerSize];
		for (int particle = 0; particle < particleCount; particle++) {
			volumeBefore[particleMaterialHandle[particle]] += particleVolume[particle];
			massBefore[particleMaterialHandle[particle]] += particleMass[particle];
		}
		java.util.Set<Integer> changedHandles = new java.util.TreeSet<>();
		for (CellCandidate candidate : cells) {
			if (remaining <= 0.0) break;
			int cell = candidate.index();
			for (int i = particleCount - 1; i >= 0; i--) {
				if (remaining <= 0.0) break;
				if (particleCellIndex(i) != cell
						|| requiredMaterialHandle >= 0 && particleMaterialHandle[i] != requiredMaterialHandle) continue;
				if (chemicalParcels != null && !chemicalParcels.isCanonicalDefaultCarrier(
						i, materialRegistry.material(particleMaterialHandle[i]).id())) continue;
				float previousVolume = particleVolume[i];
				double take = Math.min(previousVolume, remaining);
				float newVolume = (float) (previousVolume - take);
				double actual = previousVolume - newVolume;
				// A nearest-float remainder may round downward and remove more than requested.
				// Keep the authoritative marker delta at or below the caller's remaining cap;
				// a later marker can settle any still-representable remainder.
				if (actual > remaining && newVolume < previousVolume) {
					newVolume = Math.nextUp(newVolume);
					actual = previousVolume - newVolume;
					settlementTolerance = Math.max(settlementTolerance, Math.ulp(previousVolume));
				}
				if (actual <= 0.0) continue;
				double previousMass = particleMass[i];
				double newMass = newVolume == 0.0F ? 0.0 : previousMass * newVolume / previousVolume;
				int handle = particleMaterialHandle[i];
				ChemicalParcelStore.SplitPlan chemicalPlan = chemicalParcels == null ? null
						: chemicalParcels.planTruncate(i, newVolume, previousVolume - newVolume,
						newMass, previousMass - newMass);
				plan.add(new ExtractionAllocation(i, newVolume, newMass, chemicalPlan));
				changedHandles.add(handle);
				remaining -= actual;
			}
		}
		if (remaining > settlementTolerance) return new ExactMixtureTransfer(false, List.of());
		for (ExtractionAllocation allocation : plan) {
			if (chemicalParcels != null && allocation.chemicalPlan().kept() != null) {
				chemicalParcels.commitTruncate(allocation.particle(), allocation.chemicalPlan());
			}
			particleVolume[allocation.particle()] = allocation.newVolume();
			particleMass[allocation.particle()] = allocation.newMass();
		}
		// Do not compact during indexed writes: removeParticle swaps the last marker into the current
		// index and could invalidate a later planned ordinal. Compact only after every ledger edit.
		for (int particle = particleCount - 1; particle >= 0; particle--) {
			if (particleVolume[particle] == 0.0F) removeParticle(particle);
		}
		gridDirty = true;
		double[] volumeAfter = new double[ledgerSize];
		double[] massAfter = new double[ledgerSize];
		for (int particle = 0; particle < particleCount; particle++) {
			volumeAfter[particleMaterialHandle[particle]] += particleVolume[particle];
			massAfter[particleMaterialHandle[particle]] += particleMass[particle];
		}
		List<MaterialPortion> result = new java.util.ArrayList<>(changedHandles.size());
		for (int handle : changedHandles) {
			double removedVolume = volumeBefore[handle] - volumeAfter[handle];
			double removedMass = massBefore[handle] - massAfter[handle];
			if (removedVolume > 0.0 && removedMass > 0.0) {
				result.add(new MaterialPortion(handle, removedVolume, removedMass));
			}
		}
		return new ExactMixtureTransfer(true, List.copyOf(result));
}
	private record InsertAllocation(
			int x, int y, int z, double amount, int materialHandle, int portionIndex
	) { }
	private record ChemicalPieceDraft(int cellX, int cellY, int cellZ, float volume, int materialHandle) { }
	private record ChemicalInsertPiece(
			int cellX, int cellY, int cellZ, float volume, int materialHandle, double mass,
			ChemicalMixtureRecord mixture, boolean indicator
	) { }
	private record MaterialInsertPiece(
			int cellX, int cellY, int cellZ, int slot, float volume, int materialHandle, double mass,
			ChemicalMixtureRecord chemical
	) { }
	private record ExtractionAllocation(
			int particle, float newVolume, double newMass, ChemicalParcelStore.SplitPlan chemicalPlan
	) { }

	public record ExactVolumeTransfer(boolean transferred, double volume, double mass) { }
	public record ExactMixtureTransfer(boolean transferred, List<MaterialPortion> portions) { }
	public record ExactChemicalTransfer(boolean transferred, ChemicalTransferLedger ledger) { }
	public record ChemicalDisplacement(float volume, ChemicalTransferLedger ledger) { }
	public record MaterialPortion(int materialHandle, double volume, double mass) { }

	public StepStats step() {
		float before = totalVolume();
		double massBefore = totalMass();
		int substeps = calculateSubsteps(config.timeStep());
		float dt = config.timeStep() / substeps;
		int granularParcelCountBeforeAdvection = precipitationEnabled ? captureReactiveGranularCells() : 0;
		int pressureIterations = 0;
		for (int substep = 0; substep < substeps; substep++) {
			rebuildGridFromParticles();
			updateGasNetwork(dt);
			System.arraycopy(velocityX, 0, p2gVelocityX, 0, velocityX.length);
			System.arraycopy(velocityY, 0, p2gVelocityY, 0, velocityY.length);
			System.arraycopy(velocityZ, 0, p2gVelocityZ, 0, velocityZ.length);
			if (hasGranularInventory()) {
				GranularMechanics.stepVelocities(granularParcels, granularRegistry, granularEnvironment(),
						config.cellSize(), config.gravity(), dt);
				granularCollision.preview(granularParcels, granularRegistry, dt);
				granularNextCoupling.rebuild(granularParcels, granularDeposits, granularCollision::previewPosition);
				if (!granularOccupancyFitsGeometry(granularNextCoupling)) {
					granularCollision.holdPosition(granularParcels);
					granularNextCoupling.rebuild(granularParcels, granularDeposits, granularCollision::previewPosition);
				}
				pressureGranularCoupling = granularNextCoupling;
			}
			// Dampen previously committed flow before body forces so a zero-velocity porous bed retains
			// the full hydrostatic gravity/pressure balance in the shared projection.
			applyGranularPorousDamping(dt);
			applyGravity(dt);
			applyLiquidPairSurfaceTensionForce(dt);
			enforceSolidFaceVelocities();
			solvePressure(dt);
			pressureIterations += lastPressureIterations;
			projectVelocity(dt);
			enforceSolidFaceVelocities();
			extrapolateVelocityIntoAir();
			gridToParticles();
			advectParticles(dt);
			if (hasGranularInventory()) granularCollision.commit(granularParcels);
		}
		if (precipitationEnabled) markMovedReactiveGranularCells(granularParcelCountBeforeAdvection);
		int[] phaseTransferCells = precipitationEnabled ? chemicalParcels.dirtyCells() : new int[0];
		ChemicalParcelStore.ReactionStats chemistry = chemicalParcels == null
				? new ChemicalParcelStore.ReactionStats(0, 0, 0)
				: chemicalParcels.reactDirtyCells(
						sizeX, sizeY, sizeZ, config.cellSize(), particleX, particleY, particleZ,
						particleMass, particleMaterialHandle,
						handle -> materialRegistry.material(handle).id(), particleCount);
		lastPhaseTransferStats = precipitationEnabled
				? transferGranularPhases(phaseTransferCells) : new PhaseTransferStats(0, 0, 0, 0L);
		if (granularDepositDynamics != null) {
			if (hasGranularInventory() || granularDepositDynamics.dirtyColumnCount() != 0) {
				lastGranularDepositStats = granularDepositDynamics.step(granularDepositEnvironment(), config.timeStep());
			} else {
				lastGranularDepositStats = new GranularDepositDynamics.Stats(0, 0, 0, 0, 0);
			}
			if (hasGranularInventory()) {
				granularCoupling.rebuild(granularParcels, granularDeposits);
				pressureGranularCoupling = granularCoupling;
			} else {
				granularCoupling.clear(); pressureGranularCoupling = null;
			}
		}
		ensureParticleCapacity(particleReseeder.capacityUpperBound(particleCount));
		FlipParticleReseeder.Result reseeding = particleReseeder.reseed(
				solid,
				particleX, particleY, particleZ,
				particleVelocityX, particleVelocityY, particleVelocityZ,
				particleVolume, particleMass, particleMaterialHandle, particlePhaseLabel, chemicalParcels,
				particleCount, usesMassWeightedP2G(),
				this::markerPositionAllowed
		);
		particleCount = reseeding.particleCount();
		gridDirty = true;
		rebuildGridFromParticles();
		return new StepStats(
				substeps, pressureIterations, lastPressureResidual, before, totalVolume(), massBefore, totalMass(),
				reseeding.merged(), reseeding.split(), reseeding.budgetSkipped(), reseeding.geometrySkipped(),
				reseeding.ledgerError(), reseeding.massLedgerError(),
				chemistry.dirtyCells(), chemistry.evaluations(), chemistry.changedMarkers()
		);
	}

	private void rebuildGridFromParticles() {
		Arrays.fill(volume, 0.0F);
		Arrays.fill(markerCount, 0);
		Arrays.fill(exactCellMarkerVolume, 0.0);
		Arrays.fill(exactCellMarkerMass, 0.0);
		Arrays.fill(cellDensity, config.density());
		Arrays.fill(cellPhaseHead, -1);
		phaseEntryCount = 0;
		freeSurfaceGeometry.clearExactVolumes();
		Arrays.fill(velocityX, 0.0F);
		Arrays.fill(velocityY, 0.0F);
		Arrays.fill(velocityZ, 0.0F);
		Arrays.fill(velocityWeightX, 0.0F);
		Arrays.fill(velocityWeightY, 0.0F);
		Arrays.fill(velocityWeightZ, 0.0F);
		variableDensityActive = usesMassWeightedP2G();
		surfaceTensionActive = hasAnySurfaceTension();
		liquidPairTensionActive = hasAnyLiquidPairTension();
		boolean granularInventoryPresent = hasGranularInventory();
		if (granularCarrierMaterialByCell != null && granularInventoryPresent) {
			Arrays.fill(granularCarrierMaterialByCell, -1);
			Arrays.fill(granularCarrierVolumeByCell, -1.0);
		}
		for (int i = 0; i < particleCount; i++) {
			int particleCell = particleCellIndex(i);
			markerCount[particleCell]++;
			exactCellMarkerVolume[particleCell] += particleVolume[i];
			exactCellMarkerMass[particleCell] += particleMass[i];
			addCellPhaseVolume(particleCell, particlePhaseLabel[i], particleVolume[i]);
			if (granularCarrierMaterialByCell != null && granularInventoryPresent
					&& (particleVolume[i] > granularCarrierVolumeByCell[particleCell]
					|| particleVolume[i] == granularCarrierVolumeByCell[particleCell]
					&& particleMaterialHandle[i] < granularCarrierMaterialByCell[particleCell])) {
				granularCarrierVolumeByCell[particleCell] = particleVolume[i];
				granularCarrierMaterialByCell[particleCell] = particleMaterialHandle[i];
			}
			freeSurfaceGeometry.addExactVolume(particleCell, particleVolume[i]);
			splatCenter(particleX[i], particleY[i], particleZ[i], particleVolume[i]);
			float transferWeight = variableDensityActive ? (float) particleMass[i] : particleVolume[i];
			splatFaceField(velocityX, velocityWeightX, 0, particleX[i], particleY[i], particleZ[i], particleVelocityX[i], transferWeight);
			splatFaceField(velocityY, velocityWeightY, 1, particleX[i], particleY[i], particleZ[i], particleVelocityY[i], transferWeight);
			splatFaceField(velocityZ, velocityWeightZ, 2, particleX[i], particleY[i], particleZ[i], particleVelocityZ[i], transferWeight);
		}
		for (int cell = 0; cell < cellCount; cell++) if (markerCount[cell] > 0) {
			cellDensity[cell] = exactCellMarkerMass[cell] / exactCellMarkerVolume[cell];
			if (!(Double.isFinite(cellDensity[cell]) && cellDensity[cell] > 0.0)) {
				throw new IllegalStateException("Marker inventory produced invalid cell density");
			}
		}
		normalize(velocityX, velocityWeightX);
		normalize(velocityY, velocityWeightY);
		normalize(velocityZ, velocityWeightZ);
		if (granularCoupling == null || !granularInventoryPresent) {
			if (granularCoupling != null) granularCoupling.clear();
			freeSurfaceGeometry.rebuild(solid, markerCount, cutCells);
			pressureGranularCoupling = null;
		} else {
			granularCoupling.rebuild(granularParcels, granularDeposits);
			pressureGranularCoupling = granularCoupling;
			freeSurfaceGeometry.rebuild(solid, markerCount, cutCells, granularCoupling);
		}
		rebuildSurfaceTensionPressure();
		enforceSolidFaceVelocities();
		extrapolateVelocityIntoAir();
		gridDirty = false;
	}

	private void addCellPhaseVolume(int cell, int phase, float amount) {
		for (int entry = cellPhaseHead[cell]; entry >= 0; entry = phaseEntryNext[entry]) {
			if (phaseEntryLabel[entry] != phase) continue;
			phaseEntryVolume[entry] += amount;
			return;
		}
		if (phaseEntryCount >= phaseEntryLabel.length) {
			throw new IllegalStateException("Phase entries exceeded the marker capacity");
		}
		int entry = phaseEntryCount++;
		phaseEntryLabel[entry] = phase;
		phaseEntryVolume[entry] = amount;
		phaseEntryNext[entry] = cellPhaseHead[cell];
		cellPhaseHead[cell] = entry;
	}

	private void extrapolateVelocityIntoAir() {
		velocityExtrapolator.rebuildAndExtrapolate(
				solid, markerCount,
				velocityX, velocityY, velocityZ,
				velocityValidX, velocityValidY, velocityValidZ,
				velocityWeightX, velocityWeightY, velocityWeightZ
		);
	}

	/**
	 * Refreshes the gas network from the current grid: rescans cavity topology, migrates
	 * sealed inventories onto the new slots (majority mapping, proportional split with exact
	 * remainder on a genuine pinch), refreshes available volumes and geometric orifice edges,
	 * then advances finite-rate orifice transport. New cavities start at ambient equilibrium;
	 * a cavity that opens to ambient or vanishes under liquid vents through the remap ledger.
	 */
	private void updateGasNetwork(float dt) {
		AirCavityScanner.Result result = pressureGranularCoupling == null
				? airScanner.scan(solid, markerCount, volume, cutCells)
				: airScanner.scan(solid, markerCount, volume, cutCells, pressureGranularCoupling);
		int sealed = result.sealedNodeCount();
		double[] availableVolumes = result.sealedAvailableVolume();
		long[] inventoriesNanos = new long[sealed];
		if (previousSealedNodeCount > 0 && sealed > 0) {
			int previous = previousSealedNodeCount;
			int[] ambientOverlap = new int[previous + 1];
			TreeMap<Long, Integer> overlap = new TreeMap<>();
			int[] newLabels = result.cellNodeId();
			for (int cell = 0; cell < cellCount; cell++) {
				int old = airCellNodeId[cell];
				if (old < 1) continue;
				int now = newLabels[cell];
				if (now >= 1) {
					long key = ((long) old << 32) | (now & 0xffffffffL);
					overlap.merge(key, 1, Integer::sum);
				} else if (now == 0) {
					ambientOverlap[old]++;
				}
			}
			boolean[] contributed = new boolean[sealed + 1];
			for (int old = 1; old <= previous; old++) {
				long inventory = airNetwork.standardVolumeNanos(old);
				if (inventory <= 0L || ambientOverlap[old] > 0) continue;
				Map<Long, Integer> oldOverlap = overlap.subMap(
						(long) old << 32, (long) (old + 1) << 32);
				int totalOverlap = 0;
				int destinations = 0;
				for (int count : oldOverlap.values()) {
					totalOverlap += count;
					destinations++;
				}
				if (destinations == 0) continue;
				long assigned = 0L;
				int remaining = destinations;
				for (Map.Entry<Long, Integer> entry : oldOverlap.entrySet()) {
					int node = (int) (entry.getKey() & 0xffffffffL);
					remaining--;
					long share = remaining == 0 ? inventory - assigned
							: (long) Math.floor((double) inventory * entry.getValue() / totalOverlap);
					assigned += share;
					inventoriesNanos[node - 1] += share;
					contributed[node] = true;
				}
			}
			for (int node = 1; node <= sealed; node++) {
				if (!contributed[node]) {
					inventoriesNanos[node - 1] = Math.max(1L, AirNetwork.nanos(availableVolumes[node - 1]));
				}
			}
		} else {
			for (int node = 1; node <= sealed; node++) {
				inventoriesNanos[node - 1] = Math.max(1L, AirNetwork.nanos(availableVolumes[node - 1]));
			}
		}
		airNetwork.remapNodes(availableVolumes, inventoriesNanos);
		for (AirCavityScanner.GeometricEdge edge : result.geometricEdges()) {
			airNetwork.addOrMergeEdge(edge.firstNode(), edge.secondNode(), edge.areaSquareMeters(), 0.65);
		}
		airCellNodeId = result.cellNodeId();
		previousSealedNodeCount = sealed;
		gasPressureActive = sealed > 0;
		if (gasPressureActive) airNetwork.stepEdges(dt);
	}

	/** Gauge pressure of the sealed cavity owning an air cell; zero for ambient and inactive gas. */
	private float gasPressureOfAirCell(int airCell) {
		if (!gasPressureActive) return 0.0F;
		int node = airCellNodeId[airCell];
		if (node <= 0) return 0.0F;
		return (float) airNetwork.gaugePressure(node);
	}

	private int particleCellIndex(int particle) {
		return particleCellIndex(particleX[particle], particleY[particle], particleZ[particle]);
	}

	private int particleCellIndex(float positionX, float positionY, float positionZ) {
		float h = config.cellSize();
		int x = Math.min(sizeX - 1, Math.max(0, (int) Math.floor(positionX / h)));
		int y = Math.min(sizeY - 1, Math.max(0, (int) Math.floor(positionY / h)));
		int z = Math.min(sizeZ - 1, Math.max(0, (int) Math.floor(positionZ / h)));
		return cellIndex(x, y, z);
	}

	private GranularMechanics.Environment granularEnvironment() {
		return new GranularMechanics.Environment() {
			@Override public GranularMechanics.FluidSample sample(float x, float y, float z) {
				int cell = particleCellIndex(x, y, z);
				int liquidMaterial = granularCarrierMaterialByCell[cell];
				if (markerCount[cell] <= 0 || liquidMaterial < 0) {
					return new GranularMechanics.FluidSample(false, 0.0, 0.0, 0.0,
							1.225, 1.81e-5, 100_000.0, 0.0);
				}
				String materialId = materialRegistry.material(liquidMaterial).id();
				GranularCarrier carrier = granularRegistry.carrier(granularRegistry.carrierHandle(materialId));
				float capacity = effectiveOpenCellVolume(cell);
				double submerged = capacity <= 0.0F ? 0.0
						: Math.max(0.0, Math.min(1.0, exactCellMarkerVolume[cell] / capacity));
				return new GranularMechanics.FluidSample(true,
						sampleX(velocityX, x, y, z), sampleY(velocityY, x, y, z), sampleZ(velocityZ, x, y, z),
						cellDensity[cell], carrier.dynamicViscosity(), carrier.maximumReynolds(), submerged);
			}

			@Override public void addFluidImpulse(float x, float y, float z,
			                                      double impulseX, double impulseY, double impulseZ) {
				addFaceImpulse(velocityX, velocityWeightX, 0, x, y, z, impulseX);
				addFaceImpulse(velocityY, velocityWeightY, 1, x, y, z, impulseY);
				addFaceImpulse(velocityZ, velocityWeightZ, 2, x, y, z, impulseZ);
			}
		};
	}

	private GranularDepositDynamics.Environment granularDepositEnvironment() {
		return new GranularDepositDynamics.Environment() {
			@Override public long geometricOpenSvq(int cell) {
				return SolidVolume.fromCellVolumeExact(cutCells.openCellVolume(cell));
			}

			@Override public boolean horizontalPathOpen(int fromCell, int directionX, int directionZ) {
				if (directionX == 0 && directionZ == 0 || Math.abs(directionX) > 1 || Math.abs(directionZ) > 1) {
					throw new IllegalArgumentException("Granular slope direction is invalid");
				}
				int x = fromCell % sizeX, yz = fromCell / sizeX, y = yz % sizeY, z = yz / sizeY;
				int targetX = x + directionX, targetZ = z + directionZ;
				if (!inside(targetX, y, targetZ)) return false;
				if (directionX == 0 || directionZ == 0) {
					return openFaceFromCell(fromCell, directionX, 0, directionZ) > 0.0F;
				}
				boolean xThenZ = openFaceFromCell(fromCell, directionX, 0, 0) > 0.0F;
				int afterX = cellIndex(targetX, y, z);
				xThenZ &= openFaceFromCell(afterX, 0, 0, directionZ) > 0.0F;
				boolean zThenX = openFaceFromCell(fromCell, 0, 0, directionZ) > 0.0F;
				int afterZ = cellIndex(x, y, targetZ);
				zThenX &= openFaceFromCell(afterZ, directionX, 0, 0) > 0.0F;
				return xThenZ || zThenX;
			}

			@Override public double shields(int surfaceCell, com.example.granular.ParticulateMaterial material) {
				int x = surfaceCell % sizeX, yz = surfaceCell / sizeX, y = yz % sizeY, z = yz / sizeY;
				int fluidCell = y + 1 < sizeY ? cellIndex(x, y + 1, z) : surfaceCell;
				float px = (x + 0.5F) * config.cellSize();
				float py = (Math.min(sizeY - 1, y + 1) + 0.5F) * config.cellSize();
				float pz = (z + 0.5F) * config.cellSize();
				double speed = Math.hypot(sampleX(velocityX, px, py, pz), sampleZ(velocityZ, px, py, pz));
				CarrierProperties carrier = granularCarrierProperties(fluidCell);
				double denominator = Math.abs(material.referenceDensity() - carrier.density())
						* config.gravity() * material.primaryGrainDiameterMeters();
				if (!(denominator > 1.0e-18)) return speed > 0.0 ? Double.POSITIVE_INFINITY : 0.0;
				double bedShear = carrier.viscosity() * speed / config.cellSize();
				return bedShear / denominator;
			}

			@Override public GranularDepositDynamics.Velocity wakeVelocity(int surfaceCell) {
				int x = surfaceCell % sizeX, yz = surfaceCell / sizeX, y = yz % sizeY, z = yz / sizeY;
				float px = (x + 0.5F) * config.cellSize(), py = (y + 0.5F) * config.cellSize();
				float pz = (z + 0.5F) * config.cellSize();
				return new GranularDepositDynamics.Velocity(sampleX(velocityX, px, py, pz),
						sampleY(velocityY, px, py, pz), sampleZ(velocityZ, px, py, pz));
			}

			@Override public void onSolidChanged(int cell) {
				if (precipitationEnabled) chemicalParcels.markDirtyCell(cell);
			}
		};
	}

	private int captureReactiveGranularCells() {
		int count = granularParcels.count();
		for (int parcel = 0; parcel < count; parcel++) {
			GranularParcelStore.Parcel state = granularParcels.parcel(parcel);
			granularReactivePreviousCells[parcel] = state.solidCompositionHandle() == 0
					? -1 : granularParcelCell(state);
		}
		return count;
	}

	private void markMovedReactiveGranularCells(int expectedCount) {
		if (granularParcels.count() != expectedCount) {
			throw new IllegalStateException("Granular advection changed the exact active parcel inventory");
		}
		for (int parcel = 0; parcel < expectedCount; parcel++) {
			int previous = granularReactivePreviousCells[parcel];
			if (previous < 0) continue;
			int current = granularParcelCell(granularParcels.parcel(parcel));
			if (current != previous) {
				chemicalParcels.markDirtyCell(previous);
				chemicalParcels.markDirtyCell(current);
			}
		}
	}

	private record CarrierProperties(double density, double viscosity) { }

	private CarrierProperties granularCarrierProperties(int cell) {
		int liquidMaterial = granularCarrierMaterialByCell == null ? -1 : granularCarrierMaterialByCell[cell];
		if (markerCount[cell] <= 0 || liquidMaterial < 0) return new CarrierProperties(1.225, 1.81e-5);
		String materialId = materialRegistry.material(liquidMaterial).id();
		GranularCarrier carrier = granularRegistry.carrier(granularRegistry.carrierHandle(materialId));
		return new CarrierProperties(cellDensity[cell], carrier.dynamicViscosity());
	}

	private void applyGranularPorousDamping(float dt) {
		if (granularDeposits == null || granularDeposits.occupiedCellCount() == 0) return;
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 1; x < sizeX; x++) {
			int left = cellIndex(x - 1, y, z), right = cellIndex(x, y, z);
			velocityX[xFaceIndex(x, y, z)] *= granularPorousFaceDamping(left, right, dt);
		}
		for (int z = 0; z < sizeZ; z++) for (int y = 1; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int below = cellIndex(x, y - 1, z), above = cellIndex(x, y, z);
			velocityY[yFaceIndex(x, y, z)] *= granularPorousFaceDamping(below, above, dt);
		}
		for (int z = 1; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int back = cellIndex(x, y, z - 1), front = cellIndex(x, y, z);
			velocityZ[zFaceIndex(x, y, z)] *= granularPorousFaceDamping(back, front, dt);
		}
	}

	private float granularPorousFaceDamping(int first, int second, float dt) {
		double firstK = granularPermeability(first), secondK = granularPermeability(second);
		if (Double.isInfinite(firstK) && Double.isInfinite(secondK)) return 1.0F;
		double permeability;
		if (Double.isInfinite(firstK)) permeability = secondK;
		else if (Double.isInfinite(secondK)) permeability = firstK;
		else permeability = 2.0 * firstK * secondK / (firstK + secondK);
		CarrierProperties firstCarrier = granularCarrierProperties(first), secondCarrier = granularCarrierProperties(second);
		double viscosity = 0.5 * (firstCarrier.viscosity() + secondCarrier.viscosity());
		double density = 0.5 * (firstCarrier.density() + secondCarrier.density());
		return (float) (1.0 / (1.0 + dt * viscosity / (density * permeability)));
	}

	private double granularPermeability(int cell) {
		GranularDepositStore.Group dominant = granularDeposits.dominantGroup(cell);
		if (dominant == null) return Double.POSITIVE_INFINITY;
		double porosity = depositsPorosity(cell);
		double solid = 1.0 - porosity;
		double diameter = granularRegistry.material(dominant.materialHandle()).primaryGrainDiameterMeters();
		return diameter * diameter * porosity * porosity * porosity / (180.0 * solid * solid);
	}

	private double depositsPorosity(int cell) {
		return granularDeposits.porosityQ(cell) / (double) GranularLimits.POROSITY_SCALE;
	}

	private void addFaceImpulse(
			float[] field, float[] faceMass, int axis, float px, float py, float pz, double impulse
	) {
		if (impulse == 0.0) return;
		float h = config.cellSize();
		int nx = axis == 0 ? sizeX + 1 : sizeX;
		int ny = axis == 1 ? sizeY + 1 : sizeY;
		int nz = axis == 2 ? sizeZ + 1 : sizeZ;
		float gx = px / h - (axis == 0 ? 0.0F : 0.5F);
		float gy = py / h - (axis == 1 ? 0.0F : 0.5F);
		float gz = pz / h - (axis == 2 ? 0.0F : 0.5F);
		int x0 = (int) Math.floor(gx), y0 = (int) Math.floor(gy), z0 = (int) Math.floor(gz);
		float fx = gx - x0, fy = gy - y0, fz = gz - z0;
		double validWeight = 0.0;
		for (int oz = 0; oz <= 1; oz++) for (int oy = 0; oy <= 1; oy++) for (int ox = 0; ox <= 1; ox++) {
			int x = x0 + ox, y = y0 + oy, z = z0 + oz;
			if (x < 0 || x >= nx || y < 0 || y >= ny || z < 0 || z >= nz) continue;
			int face = (z * ny + y) * nx + x;
			if (faceMass[face] <= 1.0e-8F) continue;
			validWeight += trilinearWeight(ox, oy, oz, fx, fy, fz);
		}
		if (!(validWeight > 0.0)) return;
		for (int oz = 0; oz <= 1; oz++) for (int oy = 0; oy <= 1; oy++) for (int ox = 0; ox <= 1; ox++) {
			int x = x0 + ox, y = y0 + oy, z = z0 + oz;
			if (x < 0 || x >= nx || y < 0 || y >= ny || z < 0 || z >= nz) continue;
			int face = (z * ny + y) * nx + x;
			if (faceMass[face] <= 1.0e-8F) continue;
			double share = impulse * trilinearWeight(ox, oy, oz, fx, fy, fz) / validWeight;
			field[face] += (float) (share / faceMass[face]);
		}
	}

	private void splatCenter(float px, float py, float pz, float particleAmount) {
		float h = config.cellSize();
		float gx = px / h - 0.5F, gy = py / h - 0.5F, gz = pz / h - 0.5F;
		int x0 = (int) Math.floor(gx), y0 = (int) Math.floor(gy), z0 = (int) Math.floor(gz);
		float fx = gx - x0, fy = gy - y0, fz = gz - z0;
		float validWeight = 0.0F;
		for (int oz = 0; oz <= 1; oz++) for (int oy = 0; oy <= 1; oy++) for (int ox = 0; ox <= 1; ox++) {
			int x = x0 + ox, y = y0 + oy, z = z0 + oz;
			if (!inside(x, y, z) || solid[cellIndex(x, y, z)]) continue;
			validWeight += trilinearWeight(ox, oy, oz, fx, fy, fz);
		}
		if (validWeight <= 1.0E-8F) return;
		float scale = particleAmount / validWeight;
		for (int oz = 0; oz <= 1; oz++) for (int oy = 0; oy <= 1; oy++) for (int ox = 0; ox <= 1; ox++) {
			int x = x0 + ox, y = y0 + oy, z = z0 + oz;
			if (!inside(x, y, z)) continue;
			int index = cellIndex(x, y, z);
			if (!solid[index]) volume[index] += scale * trilinearWeight(ox, oy, oz, fx, fy, fz);
		}
	}

	private static float trilinearWeight(int ox, int oy, int oz, float fx, float fy, float fz) {
		return (ox == 0 ? 1.0F - fx : fx) * (oy == 0 ? 1.0F - fy : fy) * (oz == 0 ? 1.0F - fz : fz);
	}

	private void splatFaceField(float[] sums, float[] weights, int axis, float px, float py, float pz, float value, float particleAmount) {
		float h = config.cellSize();
		int nx = axis == 0 ? sizeX + 1 : sizeX;
		int ny = axis == 1 ? sizeY + 1 : sizeY;
		int nz = axis == 2 ? sizeZ + 1 : sizeZ;
		float gx = px / h - (axis == 0 ? 0.0F : 0.5F);
		float gy = py / h - (axis == 1 ? 0.0F : 0.5F);
		float gz = pz / h - (axis == 2 ? 0.0F : 0.5F);
		splatTrilinear(gx, gy, gz, nx, ny, nz, (index, weight) -> {
			float weighted = weight * particleAmount;
			sums[index] += value * weighted;
			weights[index] += weighted;
		});
	}

	private static void splatTrilinear(float gx, float gy, float gz, int nx, int ny, int nz, SplatConsumer consumer) {
		int x0 = (int) Math.floor(gx), y0 = (int) Math.floor(gy), z0 = (int) Math.floor(gz);
		float fx = gx - x0, fy = gy - y0, fz = gz - z0;
		for (int oz = 0; oz <= 1; oz++) for (int oy = 0; oy <= 1; oy++) for (int ox = 0; ox <= 1; ox++) {
			int x = x0 + ox, y = y0 + oy, z = z0 + oz;
			if (x < 0 || x >= nx || y < 0 || y >= ny || z < 0 || z >= nz) continue;
			float weight = (ox == 0 ? 1.0F - fx : fx) * (oy == 0 ? 1.0F - fy : fy) * (oz == 0 ? 1.0F - fz : fz);
			consumer.accept((z * ny + y) * nx + x, weight);
		}
	}

	private static void normalize(float[] values, float[] weights) {
		for (int i = 0; i < values.length; i++) values[i] = weights[i] > 1.0E-8F ? values[i] / weights[i] : 0.0F;
	}

	private int calculateSubsteps(float dt) {
		float maximum = 0.0F;
		for (int i = 0; i < particleCount; i++) {
			maximum = Math.max(maximum, Math.abs(particleVelocityX[i]) + Math.abs(particleVelocityY[i]) + Math.abs(particleVelocityZ[i]));
		}
		if (granularParcels != null) for (int index = 0; index < granularParcels.count(); index++) {
			GranularParcelStore.Parcel parcel = granularParcels.parcel(index);
			maximum = Math.max(maximum, Math.abs(parcel.velocityX())
					+ Math.abs(parcel.velocityY()) + Math.abs(parcel.velocityZ()));
		}
		maximum += config.gravity() * dt;
		return Math.max(1, (int) Math.ceil(maximum * dt / (config.cellSize() * config.maxCourantNumber())));
	}

	private void applyGravity(float dt) {
		float impulse = config.gravity() * dt;
		for (int z = 0; z < sizeZ; z++) for (int y = 1; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int below = cellIndex(x, y - 1, z), above = cellIndex(x, y, z);
			if (faceActive(below, above, pressureYFaceOpen(x, y, z))) velocityY[yFaceIndex(x, y, z)] -= impulse;
		}
	}

	private void solvePressure(float dt) {
		Arrays.fill(pressure, 0.0F);
		Arrays.fill(rhs, 0.0F);
		pressureAnchor = findPressureAnchor();
		float inverseCell = 1.0F / config.cellSize();
		float rhsScale = -config.density() / dt;
		int fluidCells = 0;
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int index = cellIndex(x, y, z);
			if (!isFluid(index) || index == pressureAnchor) { divergence[index] = 0.0F; continue; }
			float div = (pressureXFaceOpen(x + 1, y, z) * velocityX[xFaceIndex(x + 1, y, z)]
					- pressureXFaceOpen(x, y, z) * velocityX[xFaceIndex(x, y, z)]
					+ pressureYFaceOpen(x, y + 1, z) * velocityY[yFaceIndex(x, y + 1, z)]
					- pressureYFaceOpen(x, y, z) * velocityY[yFaceIndex(x, y, z)]
					+ pressureZFaceOpen(x, y, z + 1) * velocityZ[zFaceIndex(x, y, z + 1)]
					- pressureZFaceOpen(x, y, z) * velocityZ[zFaceIndex(x, y, z)]) * inverseCell;
			// Solve toward the drift target: div(u_projected) = targetDivergence.
			float correctedDivergence = div + surfaceTensionBoundaryDivergence(index, dt)
					- volumeDriftDivergence(index, dt) - granularMovingSolidDivergence(index, dt);
			divergence[index] = correctedDivergence;
			rhs[index] = rhsScale * correctedDivergence;
			fluidCells++;
		}
		if (fluidCells == 0) { lastPressureIterations = 0; lastPressureResidual = 0.0F; return; }
		System.arraycopy(rhs, 0, residual, 0, cellCount);
		applyPreconditioner(residual, preconditioned);
		System.arraycopy(preconditioned, 0, direction, 0, cellCount);
		double rz = dot(residual, preconditioned);
		double target = config.pressureTolerance() * Math.max(1.0, Math.sqrt(dot(rhs, rhs) / fluidCells));
		lastPressureIterations = 0;
		for (int iteration = 0; iteration < config.maxPressureIterations(); iteration++) {
			applyPressureMatrix(direction, matrixDirection);
			double denominator = dot(direction, matrixDirection);
			if (!(denominator > 1.0E-20)) break;
			float alpha = (float) (rz / denominator);
			for (int i = 0; i < cellCount; i++) { pressure[i] += alpha * direction[i]; residual[i] -= alpha * matrixDirection[i]; }
			lastPressureIterations = iteration + 1;
			if (Math.sqrt(dot(residual, residual) / fluidCells) <= target) break;
			applyPreconditioner(residual, preconditioned);
			double nextRz = dot(residual, preconditioned);
			if (!(Math.abs(rz) > 1.0E-30)) break;
			float beta = (float) (nextRz / rz);
			for (int i = 0; i < cellCount; i++) direction[i] = preconditioned[i] + beta * direction[i];
			rz = nextRz;
		}
		lastPressureResidual = (float) Math.sqrt(dot(residual, residual) / fluidCells);
	}

	private float volumeDriftDivergence(int index, float dt) {
		float openVolume = pressureOpenCellVolume(index);
		int x = index % sizeX, yz = index / sizeX;
		int y = yz % sizeY, z = yz / sizeY;
		return FlipVolumeDriftCorrection.targetDivergence(
				volume[index], freeSurfaceGeometry.exactCellVolume(index), openVolume,
				hasAirNeighbor(x, y, z), dt
		);
	}

	private PhaseTransferStats transferGranularPhases(int[] dirtyCells) {
		if (dirtyCells.length == 0) return new PhaseTransferStats(0, 0, 0, 0L);
		boolean[] dirty = new boolean[cellCount];
		for (int cell : dirtyCells) {
			if (cell < 0 || cell >= cellCount) throw new IllegalStateException("Chemical dirty cell is outside the domain");
			dirty[cell] = true;
		}
		@SuppressWarnings("unchecked")
		TreeMap<Integer, List<Integer>>[] liquidByPhase = new TreeMap[cellCount];
		for (int marker = 0; marker < particleCount; marker++) {
			int cell = particleCellIndex(marker); if (!dirty[cell]) continue;
			int phase = chemicalParcels.registry().phaseHandleForMaterial(
					materialRegistry.material(particleMaterialHandle[marker]).id());
			if (liquidByPhase[cell] == null) liquidByPhase[cell] = new TreeMap<>();
			liquidByPhase[cell].computeIfAbsent(phase, ignored -> new ArrayList<>()).add(marker);
		}
		int transfers = 0, precipitated = 0, dissolved = 0, evaluations = 0; long signedSvq = 0L;
		for (int cell : dirtyCells) {
			if (liquidByPhase[cell] == null) continue;
			for (SolidLiquidReaction rule : granularRegistry.reactions()) {
				if (++evaluations > GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP) {
					throw new GranularPhaseTransferEngine.BlockedException("Phase-transfer operation budget exceeded");
				}
				int phase = chemicalParcels.registry().phaseHandle(rule.liquidPhaseId());
				List<Integer> markers = liquidByPhase[cell].get(phase); if (markers == null || markers.isEmpty()) continue;
				AccessibleSolid accessible = accessibleSolid(cell, rule);
				long existingExtent = accessible == null ? 0L : accessible.totalExtent();
				long perStepExtent = Math.min(rule.accessibleSurfaceSvqPerCellStep(),
						granularRegistry.material(granularRegistry.materialHandle(rule.solidMaterialId())).maximumActiveParcelSvq())
						/ rule.solidVolumeSvqPerExtent();
				if (perStepExtent <= 0L) throw new GranularPhaseTransferEngine.BlockedException(
						"Solid-liquid rule is below one representable transfer extent");
				List<GranularPhaseTransferEngine.LiquidParcel> liquid = new ArrayList<>(markers.size());
				for (int marker : markers) liquid.add(new GranularPhaseTransferEngine.LiquidParcel(marker,
						particleVolume[marker], particleMass[marker], chemicalParcels.record(marker)));
				GranularPhaseTransferEngine.Plan plan = granularPhaseTransfer.plan(rule, liquid, existingExtent, perStepExtent);
				if (plan.changed() && !plan.precipitation() && accessible != null
						&& -plan.signedExtent() > accessible.selected().extent()) {
					plan = granularPhaseTransfer.plan(rule, liquid, existingExtent,
							Math.min(perStepExtent, accessible.selected().extent()));
				}
				if (!plan.changed()) continue;
				if (plan.precipitation()) commitPrecipitation(cell, rule, plan);
				else {
					if (accessible == null || accessible.selected() == null) throw new GranularPhaseTransferEngine.BlockedException(
							"Dissolution target has no accessible solid inventory");
					commitDissolution(cell, rule, plan, accessible.selected());
				}
				transfers++; if (plan.precipitation()) precipitated++; else dissolved++;
				signedSvq = Math.addExact(signedSvq, plan.signedSolidSvq());
			}
		}
		if (transfers > 0) gridDirty = true;
		return new PhaseTransferStats(transfers, precipitated, dissolved, signedSvq);
	}

	private AccessibleSolid accessibleSolid(int cell, SolidLiquidReaction rule) {
		long total = 0L; SolidEntity selected = null;
		int material = granularRegistry.materialHandle(rule.solidMaterialId());
		for (int parcelIndex = 0; parcelIndex < granularParcels.count(); parcelIndex++) {
			GranularParcelStore.Parcel parcel = granularParcels.parcel(parcelIndex);
			if (parcel.materialHandle() != material || granularParcelCell(parcel) != cell) continue;
			long extent = matchingSolidExtent(parcel.solidCompositionHandle(), parcel.solidVolumeSvq(), parcel.mass(), rule);
			if (extent <= 0L) continue;
			total = Math.addExact(total, extent);
			if (selected == null) selected = SolidEntity.parcel(parcelIndex, parcel, extent);
		}
		GranularDepositStore.Group exposed = granularDeposits.dominantGroup(cell);
		if (exposed != null && exposed.materialHandle() == material) {
			long extent = matchingSolidExtent(exposed.solidCompositionHandle(), exposed.solidVolumeSvq(), exposed.mass(), rule);
			if (extent > 0L) {
				total = Math.addExact(total, extent);
				if (selected == null) selected = SolidEntity.deposit(cell, exposed, extent);
			}
		}
		return total == 0L ? null : new AccessibleSolid(total, selected);
	}

	private long matchingSolidExtent(int compositionHandle, long solidSvq, double mass, SolidLiquidReaction rule) {
		if (compositionHandle == 0 || !solidCompositions.isLive(compositionHandle)) return 0L;
		SolidCompositionRecord composition = solidCompositions.record(compositionHandle);
		if (composition.massResidual() != 0.0 || composition.entryCount() != rule.solidComposition().size()
				|| !massClose(composition.totalMass(chemicalParcels.registry()), mass)) return 0L;
		long extent = -1L;
		for (Map.Entry<String, Integer> term : rule.solidComposition().entrySet()) {
			long amount = composition.amount(chemicalParcels.registry().speciesHandle(term.getKey()));
			if (amount <= 0L || amount % term.getValue() != 0L) return 0L;
			long candidate = amount / term.getValue();
			if (extent < 0L) extent = candidate; else if (extent != candidate) return 0L;
		}
		return extent > 0L && Math.multiplyExact(extent, rule.solidVolumeSvqPerExtent()) == solidSvq ? extent : 0L;
	}

	private void commitPrecipitation(
			int cell, SolidLiquidReaction rule, GranularPhaseTransferEngine.Plan plan
	) {
		if (!solidCompositions.hasCapacity(1) || !granularParcels.hasCapacity(1)) {
			throw new GranularPhaseTransferEngine.BlockedException("Precipitation parcel/composition budget exceeded");
		}
		int[] markers = updateMarkers(plan); ChemicalMixtureRecord[] mixtures = updateMixtures(plan);
		chemicalParcels.requireCanCommitPlannedReplacements(markers, mixtures);
		int composition = solidCompositions.create(plan.transferredComposition());
		int x = cell % sizeX, yz = cell / sizeX, y = yz % sizeY, z = yz / sizeY;
		GranularParcelStore.Parcel parcel = new GranularParcelStore.Parcel((x + 0.5F) * config.cellSize(),
				(y + 0.5F) * config.cellSize(), (z + 0.5F) * config.cellSize(), 0.0F, 0.0F, 0.0F,
				plan.signedSolidSvq(), plan.signedSolidMass(), granularRegistry.materialHandle(rule.solidMaterialId()),
				composition, 0, (byte) 0);
		if (!granularParcels.canAdd(parcel)) {
			solidCompositions.release(composition);
			throw new GranularPhaseTransferEngine.BlockedException("Precipitation active parcel budget exceeded");
		}
		try { chemicalParcels.commitPlannedReplacements(markers, mixtures); }
		catch (RuntimeException failure) { solidCompositions.release(composition); throw failure; }
		applyMarkerUpdates(plan); granularParcels.add(parcel);
		markPhaseTransferDirty(cell);
	}

	private void commitDissolution(
			int cell, SolidLiquidReaction rule, GranularPhaseTransferEngine.Plan plan, SolidEntity solid
	) {
		if (!rule.reversible()) throw new GranularPhaseTransferEngine.BlockedException("Solid-liquid rule is not reversible");
		long removedSvq = -plan.signedSolidSvq(); double removedMass = -plan.signedSolidMass();
		if (removedSvq <= 0L || removedSvq > solid.solidSvq()) {
			throw new GranularPhaseTransferEngine.BlockedException("Dissolution exceeds accessible solid inventory");
		}
		SolidCompositionRecord original = solidCompositions.record(solid.compositionHandle());
		boolean full = removedSvq == solid.solidSvq();
		SolidCompositionRecord remaining = full ? null : subtractComposition(original, plan.transferredComposition());
		double remainingMass = solid.mass() - removedMass;
		if (!full && (!(remainingMass > 0.0) || !massClose(remaining.totalMass(chemicalParcels.registry()), remainingMass))) {
			throw new GranularPhaseTransferEngine.BlockedException("Dissolution solid mass split is not representable");
		}
		if (solid.deposit()) {
			GranularDepositStore.SolidPortion preview = granularDeposits.peekTakeCompositionAware(cell,
					solid.materialHandle(), solid.compositionHandle(), removedSvq);
			if (!massClose(preview.mass(), removedMass)) throw new GranularPhaseTransferEngine.BlockedException(
					"Dissolution deposit mass split is not representable");
		}
		int[] markers = updateMarkers(plan); ChemicalMixtureRecord[] mixtures = updateMixtures(plan);
		chemicalParcels.requireCanCommitPlannedReplacements(markers, mixtures);
		chemicalParcels.commitPlannedReplacements(markers, mixtures); applyMarkerUpdates(plan);
		if (solid.deposit()) granularDeposits.takeCompositionAware(cell, solid.materialHandle(),
				solid.compositionHandle(), removedSvq);
		else if (full) granularParcels.removeSwap(solid.index());
		else {
			GranularParcelStore.Parcel parcel = granularParcels.parcel(solid.index());
			granularParcels.overwrite(solid.index(), new GranularParcelStore.Parcel(parcel.x(), parcel.y(), parcel.z(),
					parcel.velocityX(), parcel.velocityY(), parcel.velocityZ(), parcel.solidVolumeSvq() - removedSvq,
					remainingMass, parcel.materialHandle(), parcel.solidCompositionHandle(), parcel.contactAge(), parcel.flags()));
		}
		if (full) solidCompositions.release(solid.compositionHandle()); else solidCompositions.overwrite(
				solid.compositionHandle(), remaining);
		markPhaseTransferDirty(cell);
	}

	private SolidCompositionRecord subtractComposition(
			SolidCompositionRecord original, SolidCompositionRecord removed
	) {
		TreeMap<String, Long> remaining = new TreeMap<>();
		for (int entry = 0; entry < original.entryCount(); entry++) {
			int species = original.speciesHandle(entry);
			long amount = original.amountAt(entry) - removed.amount(species);
			if (amount < 0L) throw new GranularPhaseTransferEngine.BlockedException("Dissolution composition underflow");
			if (amount > 0L) remaining.put(chemicalParcels.registry().species(species).id(), amount);
		}
		if (remaining.isEmpty()) throw new GranularPhaseTransferEngine.BlockedException("Partial dissolution emptied solid composition");
		return SolidCompositionRecord.fromStableAmounts(chemicalParcels.registry(), remaining, original.massResidual());
	}

	private int[] updateMarkers(GranularPhaseTransferEngine.Plan plan) {
		int[] result = new int[plan.markerUpdates().size()];
		for (int index = 0; index < result.length; index++) result[index] = plan.markerUpdates().get(index).marker();
		return result;
	}

	private ChemicalMixtureRecord[] updateMixtures(GranularPhaseTransferEngine.Plan plan) {
		ChemicalMixtureRecord[] result = new ChemicalMixtureRecord[plan.markerUpdates().size()];
		for (int index = 0; index < result.length; index++) result[index] = plan.markerUpdates().get(index).mixture();
		return result;
	}

	private void applyMarkerUpdates(GranularPhaseTransferEngine.Plan plan) {
		for (GranularPhaseTransferEngine.MarkerUpdate update : plan.markerUpdates()) {
			particleVolume[update.marker()] = update.cellVolume();
			particleMass[update.marker()] = update.mass();
		}
	}

	private void markPhaseTransferDirty(int cell) {
		chemicalParcels.markDirtyCell(cell); granularDepositDynamics.disturbCell(cell); gridDirty = true;
	}

	private int granularParcelCell(GranularParcelStore.Parcel parcel) {
		int x = Math.min(sizeX - 1, Math.max(0, (int) Math.floor(parcel.x() / config.cellSize())));
		int y = Math.min(sizeY - 1, Math.max(0, (int) Math.floor(parcel.y() / config.cellSize())));
		int z = Math.min(sizeZ - 1, Math.max(0, (int) Math.floor(parcel.z() / config.cellSize())));
		return cellIndex(x, y, z);
	}

	private static boolean massClose(double left, double right) {
		return Math.abs(left - right) <= Math.max(1.0E-9, Math.max(Math.ulp(left), Math.ulp(right)) * 8.0);
	}

	private record AccessibleSolid(long totalExtent, SolidEntity selected) { }
	private record SolidEntity(
			boolean deposit, int index, int materialHandle, int compositionHandle,
			long solidSvq, double mass, long extent
	) {
		static SolidEntity parcel(int index, GranularParcelStore.Parcel parcel, long extent) {
			return new SolidEntity(false, index, parcel.materialHandle(), parcel.solidCompositionHandle(),
					parcel.solidVolumeSvq(), parcel.mass(), extent);
		}
		static SolidEntity deposit(int cell, GranularDepositStore.Group group, long extent) {
			return new SolidEntity(true, cell, group.materialHandle(), group.solidCompositionHandle(),
					group.solidVolumeSvq(), group.mass(), extent);
		}
	}
	public record PhaseTransferStats(int transfers, int precipitations, int dissolutions, long signedSolidSvq) { }

	private float granularMovingSolidDivergence(int index, float dt) {
		return granularNextCoupling == null || pressureGranularCoupling != granularNextCoupling
				? 0.0F : granularCoupling.movingSolidTargetDivergence(index,
						granularNextCoupling.solidSvq(index), dt);
	}

	private boolean granularOccupancyFitsGeometry(GranularCouplingFields coupling) {
		for (int cell = 0; cell < cellCount; cell++) {
			if (coupling.solidSvq(cell) > SolidVolume.fromCellVolumeExact(cutCells.openCellVolume(cell))) return false;
		}
		return true;
	}

	private boolean hasAnySurfaceTension() {
		if (materialRegistry == null) return false;
		for (FluidMaterial material : materialRegistry.materials()) {
			if (material.surfaceTension() > 0.0) return true;
		}
		return false;
	}

	private boolean hasAnyLiquidPairTension() {
		if (materialRegistry == null) return false;
		for (double tension : materialRegistry.liquidPairTensions().values()) {
			if (tension > 0.0) return true;
		}
		return false;
	}

	/**
	 * Adds balanced-force CSF momentum for every immiscible liquid pair with a positive {@code sigma12}.
	 * The force uses the same face density scaling as the variable-density pressure projection, and the
	 * pressure RHS is built from the resulting velocity, so the pressure solve cancels the gradient part.
	 */
	private void applyLiquidPairSurfaceTensionForce(float dt) {
		if (!liquidPairTensionActive) return;
		float scale = dt / (config.density() * config.cellSize());
		for (Map.Entry<Long, Double> entry : materialRegistry.liquidPairTensions().entrySet()) {
			if (entry.getValue() <= 0.0) continue;
			int secondPhase = (int) (entry.getKey() & 0xffffffffL);
			applyLiquidPairSurfaceTensionPair(secondPhase, entry.getValue(), scale);
		}
	}

	private void applyLiquidPairSurfaceTensionPair(int phaseB, double tension, float scale) {
		for (int index = 0; index < cellCount; index++) {
			if (solid[index]) {
				surfaceTensionColor[index] = 0.0;
				continue;
			}
			surfaceTensionColor[index] = phaseFraction(index, phaseB);
		}
		smoothSurfaceTensionColor();
		rebuildSurfaceTensionNormals();
		Arrays.fill(surfaceTensionPairCurvature, 0.0);
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int index = cellIndex(x, y, z);
			if (solid[index] || markerCount[index] == 0) continue;
			surfaceTensionPairCurvature[index] = surfaceTensionMeanCurvature(x, y, z);
		}
		float inverseCell = 1.0F / config.cellSize();
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 1; x < sizeX; x++) {
			int left = cellIndex(x - 1, y, z), right = cellIndex(x, y, z), face = xFaceIndex(x, y, z);
			applyLiquidPairFaceForce(left, right, face, tension, scale, inverseCell, pressureXFaceOpen(x, y, z), velocityX);
		}
		for (int z = 0; z < sizeZ; z++) for (int y = 1; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int below = cellIndex(x, y - 1, z), above = cellIndex(x, y, z), face = yFaceIndex(x, y, z);
			applyLiquidPairFaceForce(below, above, face, tension, scale, inverseCell, pressureYFaceOpen(x, y, z), velocityY);
		}
		for (int z = 1; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int back = cellIndex(x, y, z - 1), front = cellIndex(x, y, z), face = zFaceIndex(x, y, z);
			applyLiquidPairFaceForce(back, front, face, tension, scale, inverseCell, pressureZFaceOpen(x, y, z), velocityZ);
		}
	}

	private void applyLiquidPairFaceForce(int first, int second, int face, double tension, float scale,
	                                      float inverseCell, float openArea, float[] velocity) {
		if (openArea <= 0.0F || !isFluid(first) || !isFluid(second)) return;
		double delta = surfaceTensionSmoothed[second] - surfaceTensionSmoothed[first];
		if (Math.abs(delta) <= 1.0E-12) return;
		float densityScale = variableDensityActive ? pressureDensityScale(first, second) : 1.0F;
		double kappa = 0.5 * (surfaceTensionPairCurvature[first] + surfaceTensionPairCurvature[second]);
		float forceDifference = (float) (tension * kappa * delta);
		velocity[face] += scale * densityScale * forceDifference;
	}

	/**
	 * Liquid-gas surface tension for a pressure cell. Immiscible phases use the first registry material
	 * of the dominant phase; miscible composition-dependent tension is not modelled yet.
	 */
	private double surfaceTensionAtCell(int cell) {
		if (materialRegistry == null) return 0.0;
		int phase = dominantPhase(cell);
		if (phase == NO_PHASE) return 0.0;
		for (int handle = 0; handle < materialRegistry.size(); handle++) {
			if (materialRegistry.phaseHandle(handle) == phase) {
				return materialRegistry.material(handle).surfaceTension();
			}
		}
		return 0.0;
	}

	/** Rebuilds the cell-centred surface-tension pressure {@code sigma * kappa} used as the free-surface jump. */
	private void rebuildSurfaceTensionPressure() {
		Arrays.fill(surfaceTensionPressure, 0.0F);
		if (!surfaceTensionActive) return;
		for (int index = 0; index < cellCount; index++) {
			if (solid[index]) {
				surfaceTensionColor[index] = 0.0;
				continue;
			}
			double openVolume = effectiveOpenCellVolume(index);
			surfaceTensionColor[index] = openVolume > 0.0
					? clamp((float) (exactCellMarkerVolume[index] / openVolume), 0.0F, 1.0F)
					: 0.0;
		}
		smoothSurfaceTensionColor();
		rebuildSurfaceTensionNormals();
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int index = cellIndex(x, y, z);
			if (solid[index] || markerCount[index] == 0) continue;
			double sigma = surfaceTensionAtCell(index);
			if (sigma <= 0.0) continue;
			double kappa = surfaceTensionMeanCurvature(x, y, z);
			surfaceTensionPressure[index] = (float) (sigma * kappa);
		}
	}

	private void smoothSurfaceTensionColor() {
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int index = cellIndex(x, y, z);
			double sum = 0.0;
			int count = 0;
			for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
				int sx = x + dx, sy = y + dy, sz = z + dz;
				if (!inside(sx, sy, sz)) continue;
				int neighbor = cellIndex(sx, sy, sz);
				if (solid[neighbor]) continue;
				sum += surfaceTensionColor[neighbor];
				count++;
			}
			surfaceTensionSmoothed[index] = count == 0 ? surfaceTensionColor[index] : sum / count;
		}
	}

	private double surfaceTensionCentralDifference(int x, int y, int z, int dx, int dy, int dz) {
		int xPlus = Math.min(sizeX - 1, Math.max(0, x + dx));
		int xMinus = Math.min(sizeX - 1, Math.max(0, x - dx));
		int yPlus = Math.min(sizeY - 1, Math.max(0, y + dy));
		int yMinus = Math.min(sizeY - 1, Math.max(0, y - dy));
		int zPlus = Math.min(sizeZ - 1, Math.max(0, z + dz));
		int zMinus = Math.min(sizeZ - 1, Math.max(0, z - dz));
		return 0.5 * (surfaceTensionSmoothed[cellIndex(xPlus, yPlus, zPlus)]
				- surfaceTensionSmoothed[cellIndex(xMinus, yMinus, zMinus)]);
	}

	/** Unit interface normal at a cell centre, pointing from gas into liquid, computed once per cell into the cache. */
	private void rebuildSurfaceTensionNormals() {
		double epsilon = 1.0E-6 / config.cellSize();
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int index = cellIndex(x, y, z);
			if (solid[index]) {
				surfaceTensionNormalX[index] = 0.0;
				surfaceTensionNormalY[index] = 0.0;
				surfaceTensionNormalZ[index] = 1.0;
				continue;
			}
			double gradientX = surfaceTensionCentralDifference(x, y, z, 1, 0, 0);
			double gradientY = surfaceTensionCentralDifference(x, y, z, 0, 1, 0);
			double gradientZ = surfaceTensionCentralDifference(x, y, z, 0, 0, 1);
			double length = Math.sqrt(gradientX * gradientX + gradientY * gradientY + gradientZ * gradientZ);
			if (length <= epsilon) {
				surfaceTensionNormalX[index] = 0.0;
				surfaceTensionNormalY[index] = 0.0;
				surfaceTensionNormalZ[index] = 1.0;
				continue;
			}
			double normalX = gradientX / length;
			double normalY = gradientY / length;
			double normalZ = gradientZ / length;
			double[] solidNormal = surfaceTensionSolidNormal(x, y, z);
			if (solidNormal == null) {
				surfaceTensionNormalX[index] = normalX;
				surfaceTensionNormalY[index] = normalY;
				surfaceTensionNormalZ[index] = normalZ;
				continue;
			}
			double contactAngle = surfaceTensionContactAngleAtCell(index);
			double cosTheta = Math.cos(contactAngle);
			double sinTheta = Math.sin(contactAngle);
			double nsx = solidNormal[0], nsy = solidNormal[1], nsz = solidNormal[2];
			double dot = normalX * nsx + normalY * nsy + normalZ * nsz;
			double tangentX = normalX - dot * nsx;
			double tangentY = normalY - dot * nsy;
			double tangentZ = normalZ - dot * nsz;
			double tangentLength = Math.sqrt(tangentX * tangentX + tangentY * tangentY + tangentZ * tangentZ);
			if (tangentLength <= 1.0E-9) {
				tangentX = Math.abs(nsx) < 0.9 ? 1.0 : 0.0;
				tangentY = Math.abs(nsx) < 0.9 ? 0.0 : 1.0;
				tangentZ = 0.0;
				dot = tangentX * nsx + tangentY * nsy + tangentZ * nsz;
				tangentX -= dot * nsx;
				tangentY -= dot * nsy;
				tangentZ -= dot * nsz;
				tangentLength = Math.sqrt(tangentX * tangentX + tangentY * tangentY + tangentZ * tangentZ);
			}
			surfaceTensionNormalX[index] = -cosTheta * nsx + sinTheta * tangentX / tangentLength;
			surfaceTensionNormalY[index] = -cosTheta * nsy + sinTheta * tangentY / tangentLength;
			surfaceTensionNormalZ[index] = -cosTheta * nsz + sinTheta * tangentZ / tangentLength;
		}
	}

	private double[] surfaceTensionSolidNormal(int x, int y, int z) {
		double normalX = 0.0, normalY = 0.0, normalZ = 0.0;
		int count = 0;
		for (int[] direction : SURFACE_TENSION_DIRECTIONS) {
			int dx = direction[0], dy = direction[1], dz = direction[2];
			int sx = x + dx, sy = y + dy, sz = z + dz;
			if (!inside(sx, sy, sz) || !solid[cellIndex(sx, sy, sz)]) continue;
			normalX -= dx;
			normalY -= dy;
			normalZ -= dz;
			count++;
		}
		if (count == 0) return null;
		double length = Math.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ);
		if (length <= 1.0E-9) return null;
		return new double[] {normalX / length, normalY / length, normalZ / length};
	}

	private double surfaceTensionContactAngleAtCell(int cell) {
		if (materialRegistry == null) return Math.PI / 2.0;
		int phase = dominantPhase(cell);
		if (phase == NO_PHASE) return Math.PI / 2.0;
		for (int handle = 0; handle < materialRegistry.size(); handle++) {
			if (materialRegistry.phaseHandle(handle) == phase) {
				return materialRegistry.material(handle).contactAngleRadians();
			}
		}
		return Math.PI / 2.0;
	}

	/** Mean curvature {@code kappa = -div(n)}; positive for a liquid sphere surrounded by gas. */
	private double surfaceTensionMeanCurvature(int x, int y, int z) {
		double inverseTwoCell = 0.5 / config.cellSize();
		double nxPlus = surfaceTensionNeighborNormal(x, y, z, 1, 0, 0, 0);
		double nxMinus = surfaceTensionNeighborNormal(x, y, z, -1, 0, 0, 0);
		double nyPlus = surfaceTensionNeighborNormal(x, y, z, 0, 1, 0, 1);
		double nyMinus = surfaceTensionNeighborNormal(x, y, z, 0, -1, 0, 1);
		double nzPlus = surfaceTensionNeighborNormal(x, y, z, 0, 0, 1, 2);
		double nzMinus = surfaceTensionNeighborNormal(x, y, z, 0, 0, -1, 2);
		return -((nxPlus - nxMinus) + (nyPlus - nyMinus) + (nzPlus - nzMinus)) * inverseTwoCell;
	}

	/** Neighbor normal used in the divergence; a solid neighbour reuses the cell's own Young-adjusted normal. */
	private double surfaceTensionNeighborNormal(int x, int y, int z, int dx, int dy, int dz, int component) {
		int nx = x + dx, ny = y + dy, nz = z + dz;
		if (inside(nx, ny, nz) && solid[cellIndex(nx, ny, nz)]) {
			return cachedSurfaceTensionNormal(cellIndex(x, y, z), component);
		}
		return cachedSurfaceTensionNormal(cellIndex(Math.min(sizeX - 1, Math.max(0, nx)),
				Math.min(sizeY - 1, Math.max(0, ny)),
				Math.min(sizeZ - 1, Math.max(0, nz))), component);
	}

	private double cachedSurfaceTensionNormal(int cell, int component) {
		return switch (component) {
			case 0 -> surfaceTensionNormalX[cell];
			case 1 -> surfaceTensionNormalY[cell];
			default -> surfaceTensionNormalZ[cell];
		};
	}

	/**
	 * Divergence of the free-surface pressure-jump boundary velocity. This is the RHS source that makes
	 * the ghost-fluid projection consistent with {@code p_interface = sigma * kappa + p_gas} at every
	 * open air face; the gas term reads the sealed cavity that owns the air cell and stays exactly
	 * zero for ambient air, so the M4 path is bit-identical whenever no sealed cavity exists.
	 */
	private float surfaceTensionBoundaryDivergence(int index, float dt) {
		if (!surfaceTensionActive && !gasPressureActive) return 0.0F;
		int x = index % sizeX;
		int yz = index / sizeX;
		int y = yz % sizeY;
		int z = yz / sizeY;
		float inverseCell = 1.0F / config.cellSize();
		float scale = dt / (config.density() * config.cellSize());
		float result = 0.0F;
		if (x > 0) result += surfaceTensionBoundaryFace(index, cellIndex(x - 1, y, z), -1, 0, 0, scale, inverseCell);
		if (x + 1 < sizeX) result += surfaceTensionBoundaryFace(index, cellIndex(x + 1, y, z), 1, 0, 0, scale, inverseCell);
		if (y > 0) result += surfaceTensionBoundaryFace(index, cellIndex(x, y - 1, z), 0, -1, 0, scale, inverseCell);
		if (y + 1 < sizeY) result += surfaceTensionBoundaryFace(index, cellIndex(x, y + 1, z), 0, 1, 0, scale, inverseCell);
		if (z > 0) result += surfaceTensionBoundaryFace(index, cellIndex(x, y, z - 1), 0, 0, -1, scale, inverseCell);
		if (z + 1 < sizeZ) result += surfaceTensionBoundaryFace(index, cellIndex(x, y, z + 1), 0, 0, 1, scale, inverseCell);
		return result;
	}

	private float surfaceTensionBoundaryFace(int fluid, int neighbor, int directionX, int directionY, int directionZ,
	                                         float scale, float inverseCell) {
		if (!isAir(neighbor)) return 0.0F;
		float openArea = pressureOpenFaceFromCell(fluid, neighbor, directionX, directionY, directionZ);
		if (openArea <= 0.0F) return 0.0F;
		float theta = freeSurfaceGeometry.pressureTheta(fluid, directionX, directionY, directionZ);
		if (theta <= 0.0F) return 0.0F;
		float gas = gasPressureOfAirCell(neighbor);
		float jump = gas == 0.0F ? surfaceTensionPressure[fluid] : surfaceTensionPressure[fluid] + gas;
		float densityScale = variableDensityActive ? pressureDensityScale(fluid, neighbor) : 1.0F;
		return -openArea * scale * densityScale * jump / theta * inverseCell;
	}

	private void applyPressureMatrix(float[] source, float[] destination) {
		Arrays.fill(destination, 0.0F);
		float inverseCellSquared = 1.0F / (config.cellSize() * config.cellSize());
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int index = cellIndex(x, y, z);
			if (!isFluid(index) || index == pressureAnchor) continue;
			float value = 0.0F;
			float diagonal = 0.0F;
			if (x > 0) {
				int neighbor = cellIndex(x - 1, y, z);
				float coefficient = pressureFaceCoefficient(index, neighbor, -1, 0, 0);
				diagonal += coefficient;
				value -= coefficient * neighborValue(neighbor, source);
			}
			if (x + 1 < sizeX) {
				int neighbor = cellIndex(x + 1, y, z);
				float coefficient = pressureFaceCoefficient(index, neighbor, 1, 0, 0);
				diagonal += coefficient;
				value -= coefficient * neighborValue(neighbor, source);
			}
			if (y > 0) {
				int neighbor = cellIndex(x, y - 1, z);
				float coefficient = pressureFaceCoefficient(index, neighbor, 0, -1, 0);
				diagonal += coefficient;
				value -= coefficient * neighborValue(neighbor, source);
			}
			if (y + 1 < sizeY) {
				int neighbor = cellIndex(x, y + 1, z);
				float coefficient = pressureFaceCoefficient(index, neighbor, 0, 1, 0);
				diagonal += coefficient;
				value -= coefficient * neighborValue(neighbor, source);
			}
			if (z > 0) {
				int neighbor = cellIndex(x, y, z - 1);
				float coefficient = pressureFaceCoefficient(index, neighbor, 0, 0, -1);
				diagonal += coefficient;
				value -= coefficient * neighborValue(neighbor, source);
			}
			if (z + 1 < sizeZ) {
				int neighbor = cellIndex(x, y, z + 1);
				float coefficient = pressureFaceCoefficient(index, neighbor, 0, 0, 1);
				diagonal += coefficient;
				value -= coefficient * neighborValue(neighbor, source);
			}
			destination[index] = (diagonal * source[index] + value) * inverseCellSquared;
		}
	}

	private float neighborValue(int neighbor, float[] source) { return isFluid(neighbor) && neighbor != pressureAnchor ? source[neighbor] : 0.0F; }
	private float pressureFaceCoefficient(int fluid, int neighbor, int directionX, int directionY, int directionZ) {
		float openArea = pressureOpenFaceFromCell(fluid, neighbor, directionX, directionY, directionZ);
		if (openArea <= 0.0F || solid[neighbor]) return 0.0F;
		float densityScale = variableDensityActive ? pressureDensityScale(fluid, neighbor) : 1.0F;
		if (isFluid(neighbor)) return openArea * densityScale;
		return openArea * densityScale / freeSurfaceGeometry.pressureTheta(fluid, directionX, directionY, directionZ);
	}

	private float pressureDensityScale(int first, int second) {
		if (isFluid(first) && isFluid(second)) {
			return (float) (config.density() / materialFaceEffectiveDensity(first, second));
		}
		int fluid = isFluid(first) ? first : second;
		return (float) (config.density() / cellDensity[fluid]);
	}

	private double materialFaceEffectiveDensity(int one, int two) {
		int first = Math.min(one, two), second = Math.max(one, two);
		double fraction = 0.5;
		int firstPhase = dominantPhase(first), secondPhase = dominantPhase(second);
		if (firstPhase != NO_PHASE && secondPhase != NO_PHASE && firstPhase != secondPhase) {
			double firstSigned = phaseFraction(first, firstPhase) - 0.5;
			double secondSigned = phaseFraction(second, firstPhase) - 0.5;
			double denominator = firstSigned - secondSigned;
			if (firstSigned > 0.0 && secondSigned < 0.0 && denominator > 1.0E-12) {
				fraction = Math.max(0.05, Math.min(0.95, firstSigned / denominator));
			}
		}
		return fraction * cellDensity[first] + (1.0 - fraction) * cellDensity[second];
	}

	private int dominantPhase(int cell) {
		int dominant = NO_PHASE;
		double dominantVolume = -1.0;
		for (int entry = cellPhaseHead[cell]; entry >= 0; entry = phaseEntryNext[entry]) {
			double candidateVolume = phaseEntryVolume[entry];
			int candidate = phaseEntryLabel[entry];
			if (candidateVolume > dominantVolume || (candidateVolume == dominantVolume && candidate < dominant)) {
				dominant = candidate;
				dominantVolume = candidateVolume;
			}
		}
		return dominant;
	}

	private double phaseFraction(int cell, int phase) {
		if (exactCellMarkerVolume[cell] <= 0.0) return 0.0;
		for (int entry = cellPhaseHead[cell]; entry >= 0; entry = phaseEntryNext[entry]) {
			if (phaseEntryLabel[entry] == phase) return phaseEntryVolume[entry] / exactCellMarkerVolume[cell];
		}
		return 0.0;
	}

	private float openFaceFromCell(int cell, int directionX, int directionY, int directionZ) {
		int x = cell % sizeX;
		int yz = cell / sizeX;
		int y = yz % sizeY;
		int z = yz / sizeY;
		if (directionX < 0) return cutCells.xFaceOpen(x, y, z);
		if (directionX > 0) return cutCells.xFaceOpen(x + 1, y, z);
		if (directionY < 0) return cutCells.yFaceOpen(x, y, z);
		if (directionY > 0) return cutCells.yFaceOpen(x, y + 1, z);
		if (directionZ < 0) return cutCells.zFaceOpen(x, y, z);
		if (directionZ > 0) return cutCells.zFaceOpen(x, y, z + 1);
		throw new IllegalArgumentException("Pressure face direction must be axial");
	}

	private float pressureOpenFaceFromCell(
			int cell, int neighbor, int directionX, int directionY, int directionZ
	) {
		float geometric = openFaceFromCell(cell, directionX, directionY, directionZ);
		if (pressureGranularCoupling == null || geometric <= 0.0F) return geometric;
		return geometric * pressureGranularCoupling.faceVoidScale(
				cell, cutCells.openCellVolume(cell), neighbor, cutCells.openCellVolume(neighbor));
	}

	private float pressureXFaceOpen(int x, int y, int z) {
		float geometric = cutCells.xFaceOpen(x, y, z);
		if (pressureGranularCoupling == null || geometric <= 0.0F || x <= 0 || x >= sizeX) return geometric;
		int left = cellIndex(x - 1, y, z), right = cellIndex(x, y, z);
		return geometric * pressureGranularCoupling.faceVoidScale(
				left, cutCells.openCellVolume(left), right, cutCells.openCellVolume(right));
	}

	private float pressureYFaceOpen(int x, int y, int z) {
		float geometric = cutCells.yFaceOpen(x, y, z);
		if (pressureGranularCoupling == null || geometric <= 0.0F || y <= 0 || y >= sizeY) return geometric;
		int below = cellIndex(x, y - 1, z), above = cellIndex(x, y, z);
		return geometric * pressureGranularCoupling.faceVoidScale(
				below, cutCells.openCellVolume(below), above, cutCells.openCellVolume(above));
	}

	private float pressureZFaceOpen(int x, int y, int z) {
		float geometric = cutCells.zFaceOpen(x, y, z);
		if (pressureGranularCoupling == null || geometric <= 0.0F || z <= 0 || z >= sizeZ) return geometric;
		int back = cellIndex(x, y, z - 1), front = cellIndex(x, y, z);
		return geometric * pressureGranularCoupling.faceVoidScale(
				back, cutCells.openCellVolume(back), front, cutCells.openCellVolume(front));
	}

	private void applyPreconditioner(float[] source, float[] destination) {
		float cellSquared = config.cellSize() * config.cellSize();
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int index = cellIndex(x, y, z);
			if (!isFluid(index) || index == pressureAnchor) { destination[index] = 0.0F; continue; }
			float diagonal = 0.0F;
			if (x > 0) diagonal += pressureFaceCoefficient(index, cellIndex(x - 1, y, z), -1, 0, 0);
			if (x + 1 < sizeX) diagonal += pressureFaceCoefficient(index, cellIndex(x + 1, y, z), 1, 0, 0);
			if (y > 0) diagonal += pressureFaceCoefficient(index, cellIndex(x, y - 1, z), 0, -1, 0);
			if (y + 1 < sizeY) diagonal += pressureFaceCoefficient(index, cellIndex(x, y + 1, z), 0, 1, 0);
			if (z > 0) diagonal += pressureFaceCoefficient(index, cellIndex(x, y, z - 1), 0, 0, -1);
			if (z + 1 < sizeZ) diagonal += pressureFaceCoefficient(index, cellIndex(x, y, z + 1), 0, 0, 1);
			destination[index] = diagonal == 0.0F ? 0.0F : source[index] * cellSquared / diagonal;
		}
	}

	private int findPressureAnchor() {
		int firstFluid = -1;
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int index = cellIndex(x, y, z);
			if (!isFluid(index)) continue;
			if (firstFluid < 0) firstFluid = index;
			if (hasAirNeighbor(x, y, z)) return -1;
		}
		return firstFluid;
	}

	private boolean hasAirNeighbor(int x, int y, int z) {
		return (x > 0 && cutCells.xFaceOpen(x, y, z) > 0.0F && isAir(cellIndex(x - 1, y, z)))
				|| (x + 1 < sizeX && cutCells.xFaceOpen(x + 1, y, z) > 0.0F && isAir(cellIndex(x + 1, y, z)))
				|| (y > 0 && cutCells.yFaceOpen(x, y, z) > 0.0F && isAir(cellIndex(x, y - 1, z)))
				|| (y + 1 < sizeY && cutCells.yFaceOpen(x, y + 1, z) > 0.0F && isAir(cellIndex(x, y + 1, z)))
				|| (z > 0 && cutCells.zFaceOpen(x, y, z) > 0.0F && isAir(cellIndex(x, y, z - 1)))
				|| (z + 1 < sizeZ && cutCells.zFaceOpen(x, y, z + 1) > 0.0F && isAir(cellIndex(x, y, z + 1)));
	}

	private void projectVelocity(float dt) {
		float scale = dt / (config.density() * config.cellSize());
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 1; x < sizeX; x++) {
			int left = cellIndex(x - 1, y, z), right = cellIndex(x, y, z), face = xFaceIndex(x, y, z);
			if (!faceActive(left, right, pressureXFaceOpen(x, y, z))) velocityX[face] = 0.0F;
			else {
				float difference = pressureDifference(left, right, 1, 0, 0);
				if (variableDensityActive) difference *= pressureDensityScale(left, right);
				velocityX[face] -= scale * difference;
			}
		}
		for (int z = 0; z < sizeZ; z++) for (int y = 1; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int below = cellIndex(x, y - 1, z), above = cellIndex(x, y, z), face = yFaceIndex(x, y, z);
			if (!faceActive(below, above, pressureYFaceOpen(x, y, z))) velocityY[face] = 0.0F;
			else {
				float difference = pressureDifference(below, above, 0, 1, 0);
				if (variableDensityActive) difference *= pressureDensityScale(below, above);
				velocityY[face] -= scale * difference;
			}
		}
		for (int z = 1; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int back = cellIndex(x, y, z - 1), front = cellIndex(x, y, z), face = zFaceIndex(x, y, z);
			if (!faceActive(back, front, pressureZFaceOpen(x, y, z))) velocityZ[face] = 0.0F;
			else {
				float difference = pressureDifference(back, front, 0, 0, 1);
				if (variableDensityActive) difference *= pressureDensityScale(back, front);
				velocityZ[face] -= scale * difference;
			}
		}
	}

	private float pressureDifference(int first, int second, int directionX, int directionY, int directionZ) {
		boolean firstFluid = isFluid(first);
		boolean secondFluid = isFluid(second);
		if (firstFluid && secondFluid) return pressure[second] - pressure[first];
		if (firstFluid) {
			float theta = freeSurfaceGeometry.pressureTheta(first, directionX, directionY, directionZ);
			float gas = gasPressureOfAirCell(second);
			float jump = gas == 0.0F ? surfaceTensionPressure[first] : surfaceTensionPressure[first] + gas;
			return (jump - pressure[first]) / theta;
		}
		float theta = freeSurfaceGeometry.pressureTheta(second, -directionX, -directionY, -directionZ);
		float gas = gasPressureOfAirCell(first);
		float jump = gas == 0.0F ? surfaceTensionPressure[second] : surfaceTensionPressure[second] + gas;
		return (pressure[second] - jump) / theta;
	}

	private void gridToParticles() {
		for (int i = 0; i < particleCount; i++) {
			float x = particleX[i], y = particleY[i], z = particleZ[i];
			float picX = sampleX(velocityX, x, y, z), picY = sampleY(velocityY, x, y, z), picZ = sampleZ(velocityZ, x, y, z);
			float deltaX = picX - sampleX(p2gVelocityX, x, y, z);
			float deltaY = picY - sampleY(p2gVelocityY, x, y, z);
			float deltaZ = picZ - sampleZ(p2gVelocityZ, x, y, z);
			particleVelocityX[i] = lerp(picX, particleVelocityX[i] + deltaX, FLIP_RATIO);
			particleVelocityY[i] = lerp(picY, particleVelocityY[i] + deltaY, FLIP_RATIO);
			particleVelocityZ[i] = lerp(picZ, particleVelocityZ[i] + deltaZ, FLIP_RATIO);
		}
	}

	private void advectParticles(float dt) {
		int chemicalMovingCount = 0;
		if (chemicalParcels != null) {
			for (int marker = 0; marker < particleCount; marker++) {
				if (particleVelocityX[marker] == 0.0F && particleVelocityY[marker] == 0.0F
						&& particleVelocityZ[marker] == 0.0F) continue;
				chemicalPreviousCells[marker] = particleCellIndex(marker);
				chemicalMovingMarkers[chemicalMovingCount++] = marker;
			}
		}
		particleCollision.advect(
				particleX, particleY, particleZ,
				particleVelocityX, particleVelocityY, particleVelocityZ,
				particleCount, dt
		);
		for (int moving = 0; moving < chemicalMovingCount; moving++) {
			int marker = chemicalMovingMarkers[moving];
			int previous = chemicalPreviousCells[marker], current = particleCellIndex(marker);
			if (previous != current) {
				chemicalParcels.markDirtyCell(previous);
				chemicalParcels.markDirtyCell(current);
			}
		}
		gridDirty = true;
	}

	private void enforceSolidFaceVelocities() {
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) {
			velocityX[xFaceIndex(0, y, z)] = 0.0F; velocityX[xFaceIndex(sizeX, y, z)] = 0.0F;
			for (int x = 1; x < sizeX; x++) if (cutCells.xFaceOpen(x, y, z) <= 0.0F) velocityX[xFaceIndex(x, y, z)] = 0.0F;
		}
		for (int z = 0; z < sizeZ; z++) for (int x = 0; x < sizeX; x++) {
			velocityY[yFaceIndex(x, 0, z)] = 0.0F; velocityY[yFaceIndex(x, sizeY, z)] = 0.0F;
			for (int y = 1; y < sizeY; y++) if (cutCells.yFaceOpen(x, y, z) <= 0.0F) velocityY[yFaceIndex(x, y, z)] = 0.0F;
		}
		for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			velocityZ[zFaceIndex(x, y, 0)] = 0.0F; velocityZ[zFaceIndex(x, y, sizeZ)] = 0.0F;
			for (int z = 1; z < sizeZ; z++) if (cutCells.zFaceOpen(x, y, z) <= 0.0F) velocityZ[zFaceIndex(x, y, z)] = 0.0F;
		}
	}

	private void spawnCellVolume(int x, int y, int z, float amount) {
		int requiredParticles = particlesRequired(amount);
		if ((long) particleCount + requiredParticles > maximumParticleCount) {
			throw new ParticleBudgetExceededException(requiredParticles, maximumParticleCount - particleCount);
		}
		float remaining = amount;
		int slot = firstOpenSlot(cellIndex(x, y, z), 0);
		while (remaining > 1.0E-6F) {
			if (slot < 0) throw new IllegalStateException("Cut-cell open volume was exhausted while spawning markers");
			float piece = Math.min(PARTICLE_VOLUME, remaining);
			spawnParticle(x, y, z, slot, piece);
			remaining -= piece;
			slot = firstOpenSlot(cellIndex(x, y, z), slot + 1);
		}
	}

	private int firstOpenSlot(int cell, int first) {
		int mask = cutCells.mask(cell);
		for (int slot = first; slot < 8; slot++) if ((mask & 1 << slot) == 0) return slot;
		return -1;
	}

private void spawnParticle(int cellX, int cellY, int cellZ, int slot, float amount) {
spawnParticle(cellX, cellY, cellZ, slot, amount, 0);
}

private void spawnParticle(int cellX, int cellY, int cellZ, int slot, float amount, int materialHandle) {
double mass = materialRegistry == null
? (double) amount * config.density()
: (double) amount * materialRegistry.material(materialHandle).referenceDensity();
spawnParticle(cellX, cellY, cellZ, slot, amount, materialHandle, mass);
}

private void spawnParticle(
int cellX, int cellY, int cellZ, int slot, float amount, int materialHandle, double mass
) {
spawnParticle(cellX, cellY, cellZ, slot, amount, materialHandle, mass, null, false);
}

private void spawnParticle(
int cellX, int cellY, int cellZ, int slot, float amount, int materialHandle, double mass,
ChemicalMixtureRecord chemicalMixture, boolean indicator
) {
if (particleCount >= maximumParticleCount) throw new ParticleBudgetExceededException(1, 0);
if (!(Float.isFinite(amount) && amount > 0.0F)) throw new IllegalArgumentException("Marker volume must be finite and positive");
if (!(Double.isFinite(mass) && mass > 0.0)) throw new IllegalArgumentException("Marker mass must be finite and positive");
ensureParticleCapacity(particleCount + 1);
float h = config.cellSize();
float radiusFraction = 0.45F;
float ox = (slot & 1) == 0 ? 0.25F : 0.75F;
float oy = (slot & 2) == 0 ? 0.25F : 0.75F;
float oz = (slot & 4) == 0 ? 0.25F : 0.75F;
if (cellX == 0 || solid[cellIndex(cellX - 1, cellY, cellZ)]) ox = Math.max(ox, radiusFraction);
if (cellX + 1 == sizeX || solid[cellIndex(cellX + 1, cellY, cellZ)]) ox = Math.min(ox, 1.0F - radiusFraction);
if (cellY == 0 || solid[cellIndex(cellX, cellY - 1, cellZ)]) oy = Math.max(oy, radiusFraction);
if (cellY + 1 == sizeY || solid[cellIndex(cellX, cellY + 1, cellZ)]) oy = Math.min(oy, 1.0F - radiusFraction);
if (cellZ == 0 || solid[cellIndex(cellX, cellY, cellZ - 1)]) oz = Math.max(oz, radiusFraction);
if (cellZ + 1 == sizeZ || solid[cellIndex(cellX, cellY, cellZ + 1)]) oz = Math.min(oz, 1.0F - radiusFraction);
particleX[particleCount] = (cellX + ox) * h;
particleY[particleCount] = (cellY + oy) * h;
particleZ[particleCount] = (cellZ + oz) * h;
particleVelocityX[particleCount] = 0.0F;
particleVelocityY[particleCount] = 0.0F;
particleVelocityZ[particleCount] = 0.0F;
particleVolume[particleCount] = amount;
if (materialRegistry != null) materialRegistry.material(materialHandle);
else if (materialHandle != 0) throw new IllegalArgumentException("Material handle requires a bound material registry");
particleMass[particleCount] = mass;
particleMaterialHandle[particleCount] = materialHandle;
particlePhaseLabel[particleCount] = materialRegistry == null ? 0 : materialRegistry.phaseHandle(materialHandle);
if (chemicalParcels != null) {
if (chemicalMixture == null) chemicalParcels.assignDefault(
particleCount, materialRegistry.material(materialHandle).id(), amount, config.cellSize(), mass, false);
else {
chemicalParcels.requireCompatible(chemicalMixture, materialRegistry.material(materialHandle).id(), indicator);
chemicalParcels.assign(particleCount, chemicalMixture, indicator);
}
chemicalParcels.markDirtyCell(cellIndex(cellX, cellY, cellZ));
}
particleCount++;
}

private void spawnChemicalParticle(
int cellX, int cellY, int cellZ, int slot, float amount, int materialHandle, double mass,
ChemicalMixtureRecord mixture, boolean indicator
) {
spawnParticle(cellX, cellY, cellZ, slot, amount, materialHandle, mass, mixture, indicator);
}

	private float removeParticlesInCell(int cellX, int cellY, int cellZ, float maximum) {
		ChemicalTransferLedger.Builder removedChemistry = chemicalParcels == null ? null
				: ChemicalTransferLedger.builder(chemicalParcels.registry());
		float removed = removeParticlesInCell(cellX, cellY, cellZ, maximum, removedChemistry);
		if (removedChemistry != null) chemicalParcels.recordEscapedSink(removedChemistry.build());
		return removed;
	}

	private float removeParticlesInCell(
			int cellX, int cellY, int cellZ, float maximum,
			ChemicalTransferLedger.Builder removedChemistry
	) {
		float h = config.cellSize(), removed = 0.0F;
		for (int i = particleCount - 1; i >= 0 && removed < maximum; i--) {
			if ((int) Math.floor(particleX[i] / h) != cellX || (int) Math.floor(particleY[i] / h) != cellY || (int) Math.floor(particleZ[i] / h) != cellZ) continue;
			float previousVolume = particleVolume[i];
			float take = Math.min(previousVolume, maximum - removed);
			double previousMass = particleMass[i];
			float newVolume = previousVolume - take;
			if (chemicalParcels != null && newVolume <= 1.0E-6F) {
				take = previousVolume;
				newVolume = 0.0F;
			}
			double newMass = previousMass * newVolume / previousVolume;
			if (chemicalParcels != null) {
				chemicalParcels.markDirtyCell(cellIndex(cellX, cellY, cellZ));
				ChemicalParcelStore.SplitPlan plan = chemicalParcels.planTruncate(
						i, newVolume, take, newMass, previousMass - newMass);
				if (removedChemistry != null) removedChemistry.add(
						materialRegistry.material(particleMaterialHandle[i]).id(), chemicalParcels.indicator(i),
						take, previousMass - newMass, plan.removed());
				if (newVolume > 0.0F) {
					chemicalParcels.commitTruncate(i, plan);
				}
			}
			particleVolume[i] = newVolume;
			particleMass[i] = newMass;
			removed += take;
			if (particleVolume[i] <= 1.0E-6F) removeParticle(i);
		}
		return removed;
	}

	private float removeParticlesInMicroMask(
			int cellX, int cellY, int cellZ, int microMask,
			ChemicalTransferLedger.Builder removedChemistry
	) {
		float h = config.cellSize(), removed = 0.0F;
		for (int particle = particleCount - 1; particle >= 0; particle--) {
			if ((int) Math.floor(particleX[particle] / h) != cellX
					|| (int) Math.floor(particleY[particle] / h) != cellY
					|| (int) Math.floor(particleZ[particle] / h) != cellZ) continue;
			float localX = particleX[particle] / h - cellX;
			float localY = particleY[particle] / h - cellY;
			float localZ = particleZ[particle] / h - cellZ;
			int microX = Math.min(1, Math.max(0, (int) Math.floor(localX * 2.0F)));
			int microY = Math.min(1, Math.max(0, (int) Math.floor(localY * 2.0F)));
			int microZ = Math.min(1, Math.max(0, (int) Math.floor(localZ * 2.0F)));
			int bit = 1 << (microX + 2 * (microY + 2 * microZ));
			if ((microMask & bit) == 0) continue;
			if (chemicalParcels != null && removedChemistry != null) removedChemistry.add(
					materialRegistry.material(particleMaterialHandle[particle]).id(), chemicalParcels.indicator(particle),
					particleVolume[particle], particleMass[particle], chemicalParcels.record(particle));
			removed += particleVolume[particle];
			removeParticle(particle);
		}
		return removed;
	}

	private int particleCountInCell(int cellX, int cellY, int cellZ) {
		float h = config.cellSize();
		int count = 0;
		for (int particle = 0; particle < particleCount; particle++) {
			if ((int) Math.floor(particleX[particle] / h) == cellX
					&& (int) Math.floor(particleY[particle] / h) == cellY
					&& (int) Math.floor(particleZ[particle] / h) == cellZ) count++;
		}
		return count;
	}

	private float exactVolumeInCell(int cellX, int cellY, int cellZ) {
		float h = config.cellSize(), result = 0.0F;
		for (int particle = 0; particle < particleCount; particle++) {
			if ((int) Math.floor(particleX[particle] / h) == cellX
					&& (int) Math.floor(particleY[particle] / h) == cellY
					&& (int) Math.floor(particleZ[particle] / h) == cellZ) result += particleVolume[particle];
		}
		return result;
	}

	private double[] exactCellVolumes() {
		double[] exact = new double[cellCount];
		float h = config.cellSize();
		for (int particle = 0; particle < particleCount; particle++) {
			int x = (int) Math.floor(particleX[particle] / h);
			int y = (int) Math.floor(particleY[particle] / h);
			int z = (int) Math.floor(particleZ[particle] / h);
			if (inside(x, y, z)) exact[cellIndex(x, y, z)] += particleVolume[particle];
		}
		return exact;
	}

	private void removeParticle(int index) {
		if (chemicalParcels != null) chemicalParcels.markDirtyCell(particleCellIndex(index));
		int last = particleCount - 1;
		if (chemicalParcels != null) chemicalParcels.swapRemove(index, last);
		particleCount = last;
		if (index == last) return;
		particleX[index] = particleX[last]; particleY[index] = particleY[last]; particleZ[index] = particleZ[last];
		particleVelocityX[index] = particleVelocityX[last]; particleVelocityY[index] = particleVelocityY[last]; particleVelocityZ[index] = particleVelocityZ[last];
		particleVolume[index] = particleVolume[last];
		particleMass[index] = particleMass[last];
		particleMaterialHandle[index] = particleMaterialHandle[last];
		particlePhaseLabel[index] = particlePhaseLabel[last];
	}

	private void ensureParticleCapacity(int required) {
		if (required > maximumParticleCount) {
			throw new ParticleBudgetExceededException(required - particleCount, maximumParticleCount - particleCount);
		}
		if (required <= particleX.length) return;
		int capacity = Math.min(maximumParticleCount, Math.max(required, Math.max(1, particleX.length * 2)));
		particleX = Arrays.copyOf(particleX, capacity); particleY = Arrays.copyOf(particleY, capacity); particleZ = Arrays.copyOf(particleZ, capacity);
		particleVelocityX = Arrays.copyOf(particleVelocityX, capacity); particleVelocityY = Arrays.copyOf(particleVelocityY, capacity); particleVelocityZ = Arrays.copyOf(particleVelocityZ, capacity);
		particleVolume = Arrays.copyOf(particleVolume, capacity);
		particleMass = Arrays.copyOf(particleMass, capacity);
		particleMaterialHandle = Arrays.copyOf(particleMaterialHandle, capacity);
		particlePhaseLabel = Arrays.copyOf(particlePhaseLabel, capacity);
		phaseEntryLabel = Arrays.copyOf(phaseEntryLabel, capacity);
		phaseEntryNext = Arrays.copyOf(phaseEntryNext, capacity);
		phaseEntryVolume = Arrays.copyOf(phaseEntryVolume, capacity);
	}

	private void setDefaultMaterialState(int particle) {
		particleMass[particle] = (double) particleVolume[particle] * config.density();
		particleMaterialHandle[particle] = 0;
		particlePhaseLabel[particle] = 0;
	}

	private List<CellCandidate> nearbyCells(int centerX, int centerY, int centerZ, int maxRadius) {
		ensureGrid();
		List<CellCandidate> result = new ArrayList<>();
		int radiusSquared = maxRadius * maxRadius;
		for (int z = Math.max(0, centerZ - maxRadius); z <= Math.min(sizeZ - 1, centerZ + maxRadius); z++)
			for (int y = Math.max(0, centerY - maxRadius); y <= Math.min(sizeY - 1, centerY + maxRadius); y++)
				for (int x = Math.max(0, centerX - maxRadius); x <= Math.min(sizeX - 1, centerX + maxRadius); x++) {
					int dx = x - centerX, dy = y - centerY, dz = z - centerZ, index = cellIndex(x, y, z);
					if (dx * dx + dy * dy + dz * dz <= radiusSquared && !solid[index]) result.add(new CellCandidate(index, dx * dx + dy * dy + dz * dz));
				}
		result.sort(Comparator.comparingInt(CellCandidate::distanceSquared));
		return result;
	}

	private void ensureGrid() { if (gridDirty) rebuildGridFromParticles(); }
	private boolean markerPositionAllowed(float x, float y, float z) {
		return !particleCollision.centerInSolid(x, y, z);
	}
	private boolean isFluid(int index) { return !solid[index] && markerCount[index] > 0; }
	private boolean isAir(int index) { return !solid[index] && !isFluid(index); }
	private boolean faceActive(int first, int second, float openArea) {
		return openArea > 0.0F && !solid[first] && !solid[second] && (isFluid(first) || isFluid(second));
	}
	private boolean inside(int x, int y, int z) { return x >= 0 && x < sizeX && y >= 0 && y < sizeY && z >= 0 && z < sizeZ; }
	private int cellIndex(int x, int y, int z) { return (z * sizeY + y) * sizeX + x; }
	private int xFaceIndex(int x, int y, int z) { return (z * sizeY + y) * (sizeX + 1) + x; }
	private int yFaceIndex(int x, int y, int z) { return (z * (sizeY + 1) + y) * sizeX + x; }
	private int zFaceIndex(int x, int y, int z) { return (z * sizeY + y) * sizeX + x; }
	private int cellIndexChecked(int x, int y, int z) { if (!inside(x, y, z)) throw new IndexOutOfBoundsException(); return cellIndex(x, y, z); }

	private float sampleX(float[] field, float x, float y, float z) {
		float h = config.cellSize();
		return trilinearValid(field, velocityValidX, sizeX + 1, sizeY, sizeZ,
				x / h, y / h - 0.5F, z / h - 0.5F);
	}
	private float sampleY(float[] field, float x, float y, float z) {
		float h = config.cellSize();
		return trilinearValid(field, velocityValidY, sizeX, sizeY + 1, sizeZ,
				x / h - 0.5F, y / h, z / h - 0.5F);
	}
	private float sampleZ(float[] field, float x, float y, float z) {
		float h = config.cellSize();
		return trilinearValid(field, velocityValidZ, sizeX, sizeY, sizeZ + 1,
				x / h - 0.5F, y / h - 0.5F, z / h);
	}

	private static float trilinearValid(
			float[] values, boolean[] valid, int nx, int ny, int nz, float x, float y, float z
	) {
		float cx = clamp(x, 0.0F, nx - 1.0F), cy = clamp(y, 0.0F, ny - 1.0F), cz = clamp(z, 0.0F, nz - 1.0F);
		int x0 = (int) Math.floor(cx), y0 = (int) Math.floor(cy), z0 = (int) Math.floor(cz);
		int x1 = Math.min(x0 + 1, nx - 1), y1 = Math.min(y0 + 1, ny - 1), z1 = Math.min(z0 + 1, nz - 1);
		float fx = cx - x0, fy = cy - y0, fz = cz - z0;
		float weighted = 0.0F, weightSum = 0.0F;
		for (int offsetZ = 0; offsetZ <= 1; offsetZ++) {
			int sampleZ = offsetZ == 0 ? z0 : z1;
			float weightZ = offsetZ == 0 ? 1.0F - fz : fz;
			for (int offsetY = 0; offsetY <= 1; offsetY++) {
				int sampleY = offsetY == 0 ? y0 : y1;
				float weightY = offsetY == 0 ? 1.0F - fy : fy;
				for (int offsetX = 0; offsetX <= 1; offsetX++) {
					int sampleX = offsetX == 0 ? x0 : x1;
					float weight = (offsetX == 0 ? 1.0F - fx : fx) * weightY * weightZ;
					int index = (sampleZ * ny + sampleY) * nx + sampleX;
					if (!valid[index] || weight <= 0.0F) continue;
					weighted += values[index] * weight;
					weightSum += weight;
				}
			}
		}
		return weightSum <= 1.0E-8F ? 0.0F : weighted / weightSum;
	}

	private void validateVolumeEdit(float amount, int maxRadius) {
		if (!Float.isFinite(amount) || amount < 0.0F) throw new IllegalArgumentException("amount must be finite and non-negative");
		if (maxRadius < 0) throw new IllegalArgumentException("maxRadius cannot be negative");
	}
	private void validateMaterialPortion(MaterialPortion portion) {
		if (portion == null || !(Double.isFinite(portion.volume()) && portion.volume() > 0.0
				&& portion.volume() <= Float.MAX_VALUE && (float) portion.volume() > 0.0F)
				|| !(Double.isFinite(portion.mass()) && portion.mass() > 0.0)) {
			throw new IllegalArgumentException("Material portion ledgers must be finite, positive and float-representable");
		}
		if (materialRegistry == null) {
			if (portion.materialHandle() != 0) {
				throw new IllegalArgumentException("Material handle requires a bound registry");
			}
		} else {
			materialRegistry.material(portion.materialHandle());
		}
	}
	private static int particlesRequired(float amount) {
		if (amount <= 1.0E-6F) return 0;
		return (int) Math.ceil((amount - 1.0E-6F) / PARTICLE_VOLUME);
	}
	private static double dot(float[] first, float[] second) { double sum = 0.0; for (int i = 0; i < first.length; i++) sum += (double) first[i] * second[i]; return sum; }
	private static float clamp01(float value) { return clamp(value, 0.0F, 1.0F); }
	private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
	private static float lerp(float first, float second, float t) { return first + (second - first) * t; }

	@FunctionalInterface private interface SplatConsumer { void accept(int index, float weight); }
	private record CellCandidate(int index, int distanceSquared) { }
	public record VelocitySample(float x, float y, float z) { private static final VelocitySample ZERO = new VelocitySample(0.0F, 0.0F, 0.0F); }
	public record VolumeAddition(float addedVolume, boolean markerBudgetLimited) { }
	public record VolumeDistribution(int activeCells, int visibleCells, int denseCells) { }
	public record SolidDistribution(int partialCells, int fullCells) { }
	record MaterialInventory(double volume, double mass, double centerY) { }
	public record StepStats(
			int substeps,
			int pressureIterations,
			float pressureResidual,
			float volumeBefore,
			float volumeAfter,
			double massBefore,
			double massAfter,
			int reseededMerges,
			int reseededSplits,
			int reseedBudgetSkipped,
			int reseedGeometrySkipped,
			double reseedLedgerError,
			double reseedMassLedgerError,
			int chemistryDirtyCells,
			int chemistryEvaluations,
			int chemistryChangedMarkers
	) {
		public float relativeVolumeError() { return volumeBefore == 0.0F ? 0.0F : Math.abs(volumeAfter - volumeBefore) / volumeBefore; }
		public double relativeMassError() { return massBefore == 0.0 ? 0.0 : Math.abs(massAfter - massBefore) / massBefore; }
	}

	public static final class ParticleBudgetExceededException extends IllegalStateException {
		private final int requiredMarkers;
		private final int availableMarkers;

		private ParticleBudgetExceededException(int requiredMarkers, int availableMarkers) {
			super("FLIP marker budget exceeded: requires " + requiredMarkers + ", available " + Math.max(0, availableMarkers));
			this.requiredMarkers = requiredMarkers;
			this.availableMarkers = Math.max(0, availableMarkers);
		}

		public int requiredMarkers() { return requiredMarkers; }
		public int availableMarkers() { return availableMarkers; }
	}
}
