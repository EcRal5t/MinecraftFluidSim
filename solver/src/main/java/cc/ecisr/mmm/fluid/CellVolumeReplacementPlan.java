package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.ChemicalLimits;
import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalParcelStore;
import cc.ecisr.mmm.chemistry.ChemicalTransferLedger;
import cc.ecisr.mmm.thermal.LiquidEnergyColumn;
import cc.ecisr.mmm.thermal.ThermalDomainState;
import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.fluid.air.AirNetwork;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Cell-local external replacement, with all storage and side-column rejections before publication. */
final class CellVolumeReplacementPlan {
	record Piece(float x, float y, float z, float volume, double mass, ChemicalMixtureRecord mixture) { }
	private record SlotVolume(int slot, float volume) { }
	final CarrierMutationPlan mutation;
	final int cell;
	final int[] removed;
	final List<Piece> pieces;
	final ChemicalParcelStore.AuditExchange chemistryAudit;
	final ThermalDomainState.LiquidReplacementPlan thermal;
	final GasTopologyCoordinator.GasTopologyPlan gasTopology;
	private final long chemicalRevision;
	private final ChemicalParcelStore chemicalOwner;
	private final AirNetwork gasNetwork;
	private final long gasRevision, granularRevision;
	private final long inventoryRevision;
	private final boolean sealed;
	private final int collisionMask;
	private final SignedDyadic pore;
	private final ThermalDomainState thermalOwner;
	private final ThermalDomainState.Revision thermalRevision;

	private CellVolumeReplacementPlan(FluidDomain domain, int cell, int[] removed, List<Piece> pieces,
			ChemicalParcelStore.AuditExchange chemistryAudit, ThermalDomainState.LiquidReplacementPlan thermal,
			GasTopologyCoordinator.GasTopologyPlan gasTopology) {
		this.mutation = CarrierMutationPlan.wholesaleCount(domain.carrierMutationRevision(), domain.particles.count());
		this.cell = cell; this.removed = removed; this.pieces = List.copyOf(pieces);
		this.chemistryAudit = chemistryAudit; this.thermal = thermal;
		this.gasTopology = gasTopology;
		if (domain.gasState.frame != null) domain.ensureInventoryGrid();
		inventoryRevision = domain.gasState.frame == null ? -1 : domain.markerGrid.inventoryRevision;
		chemicalRevision = domain.chemicalParcels == null ? 0 : domain.chemicalParcels.mutationRevision();
		chemicalOwner = domain.chemicalParcels;
		gasNetwork = domain.gasState.network; gasRevision = gasNetwork.revision();
		sealed = domain.gasState.sealedDomainBoundary;
		collisionMask = domain.geometry.cutCells.mask(cell);
		pore = pore(domain, cell);
		granularRevision = domain.granularState == null ? 0 : domain.granularState.mutationRevision();
		thermalOwner = domain.thermalState;
		thermalRevision = thermalOwner == null ? null : thermalOwner.thermalRevision();
	}

	static CellVolumeReplacementPlan build(FluidDomain domain, int x, int y, int z, float amount, boolean full) {
		int cell = domain.cellIndex(x, y, z);
		ArrayList<Integer> selected = new ArrayList<>();
		for (int p = domain.particles.count() - 1; p >= 0; p--)
			if (domain.particleCellIndex(p) == cell) selected.add(p);
		int[] removed = selected.stream().mapToInt(Integer::intValue).toArray();
		var volumes = quadrature(domain, cell, amount, full);
		int finalCount = Math.addExact(domain.particles.count() - removed.length, volumes.size());
		if (finalCount > domain.maximumParticleCount)
			throw new FluidDomain.ParticleBudgetExceededException(volumes.size(), domain.maximumParticleCount - domain.particles.count() + removed.length);

		var chemistry = domain.chemicalParcels;
		var sourceBuilder = chemistry == null ? null : ChemicalTransferLedger.builder(chemistry.registry());
		var sinkBuilder = chemistry == null ? null : ChemicalTransferLedger.builder(chemistry.registry());
		long quanta = chemistry == null ? 0 : chemistry.totalQuanta();
		if (chemistry != null) {
			if (chemistry.pool().liveCount() != domain.particles.count())
				throw new IllegalStateException("Chemical marker ownership is inconsistent");
			for (int p : removed) {
				var record = chemistry.record(p);
				quanta = Math.subtractExact(quanta, record.totalQuanta());
				sinkBuilder.add(domain.materialRegistry.material(domain.particles.materialHandle(p)).id(),
						chemistry.indicator(p), domain.particles.volume(p), chemistry.unitScale().kilograms(domain.particles.mass(p)), record);
			}
		}
		ArrayList<Piece> pieces = new ArrayList<>(volumes.size());
		ArrayList<LiquidEnergyColumn.LiquidInsertion> thermalInsertions = new ArrayList<>(volumes.size());
		double density = domain.materialRegistry == null ? domain.config.density() : domain.materialRegistry.material(0).referenceDensity();
		for (var entry : volumes) {
			float volume = entry.volume();
			double mass = volume * density;
			if (!(Float.isFinite(volume) && volume > 0 && Double.isFinite(mass) && mass > 0))
				throw new IllegalArgumentException("Cell marker volume and mass must be finite and positive");
			ChemicalMixtureRecord mixture = null;
			if (chemistry != null) {
				String material = domain.materialRegistry.material(0).id();
				mixture = chemistry.defaultRecord(material, volume, domain.config.cellSize(), mass, false);
				chemistry.requireCompatible(mixture, material, false);
				quanta = Math.addExact(quanta, mixture.totalQuanta());
				sourceBuilder.add(material, false, volume, chemistry.unitScale().kilograms(mass), mixture);
			}
			float[] position = domain.markerSlotPosition(x, y, z, entry.slot());
			pieces.add(new Piece(position[0], position[1], position[2], volume, mass, mixture));
			thermalInsertions.add(new LiquidEnergyColumn.LiquidInsertion(0, mass, mixture));
		}
		if (quanta > ChemicalLimits.MAX_REGION_TOTAL_QUANTA)
			throw new IllegalArgumentException("Region chemical inventory is over budget");
		ChemicalTransferLedger source = sourceBuilder == null ? null : sourceBuilder.build();
		ChemicalTransferLedger sink = sinkBuilder == null ? null : sinkBuilder.build();
		var audit = chemistry == null ? null : chemistry.planAuditExchange(source, sink);
		var thermal = domain.thermalState == null || removed.length == 0 && pieces.isEmpty() ? null : domain.thermalState.planLiquidReplacement(
				removed, thermalInsertions, domain.thermalMarkerContext());
		var gasTopology = domain.gasTopologyCoordinator.planAfterLiquidEdit(futureEdit(domain, cell, pieces));
		if (thermal != null && gasTopology != null && gasTopology.thermalPlan() != null)
			domain.thermalState.requireGasRemapAfterLiquidReplacement(thermal, gasTopology.thermalPlan());
		// Reserve only storage. Ownership, inventory, audit objects and revisions remain unchanged.
		domain.ensureParticleCapacity(finalCount);
		if (domain.thermalState != null) domain.thermalState.ensureCapacity(Math.max(finalCount, domain.particles.capacity()));
		if (chemistry != null) chemistry.pool().reserveRecords(finalCount);
		return new CellVolumeReplacementPlan(domain, cell, removed, pieces, audit, thermal, gasTopology);
	}

	void requireCurrent(FluidDomain domain) {
		if (inventoryRevision >= 0) domain.ensureInventoryGrid();
		if (inventoryRevision >= 0 && inventoryRevision != domain.markerGrid.inventoryRevision
				|| domain.gasState.network != gasNetwork || gasNetwork.revision() != gasRevision
				|| sealed != domain.gasState.sealedDomainBoundary
				|| collisionMask != domain.geometry.cutCells.mask(cell)
				|| (gasTopology == null) != (domain.gasState.frame == null)
				|| !pore.equals(pore(domain, cell))
				|| granularRevision != (domain.granularState == null ? 0 : domain.granularState.mutationRevision())
				|| thermalOwner != domain.thermalState
				|| thermalOwner != null && !thermalOwner.matchesThermalRevision(thermalRevision))
			throw new IllegalStateException("Cell replacement gas, geometry or thermal state changed after planning");
		if (domain.chemicalParcels != chemicalOwner
				|| chemicalOwner != null && chemicalOwner.mutationRevision() != chemicalRevision)
			throw new IllegalStateException("Cell replacement chemical inventory or audit changed after planning");
		if (chemistryAudit != null) domain.chemicalParcels.requireCurrentAuditExchange(chemistryAudit);
		if (thermal != null) domain.thermalState.requireCurrentLiquidReplacement(thermal);
		if (gasTopology != null) domain.gasTopologyCoordinator.requireBeforeCarrierCommit(gasTopology);
	}

	private static SignedDyadic pore(FluidDomain domain, int cell) {
		SignedDyadic open = SignedDyadic.of(Integer.bitCount(~domain.geometry.cutCells.mask(cell) & 255), -3);
		return domain.granularCoupling == null ? open : open.subtract(SignedDyadic.of(domain.granularCoupling.solidSvq(cell), -24));
	}

	private static List<SlotVolume> quadrature(FluidDomain domain, int cell, float amount, boolean full) {
		if (!Float.isFinite(amount) || amount < 0) throw new IllegalArgumentException("Cell replacement volume must be finite and non-negative");
		int mask = domain.geometry.cutCells.mask(cell);
		int[] supports = CellRemapMicroTopology.components(mask);
		SignedDyadic total = pore(domain, cell), remaining = SignedDyadic.of(amount);
		SignedDyadic[] capacities = CellRemapMicroTopology.capacities(mask, total);
		if (remaining.compareTo(total) > 0 || full && !remaining.equals(total))
			throw new IllegalArgumentException("Cell replacement differs from its available pore capacity");
		var result = new ArrayList<SlotVolume>(8);
		if (full) {
			for (int i = 0; i < supports.length; i++) {
				if (capacities[i].signum() == 0) continue;
				var values = LiquidCellQuadrature.volumes(capacities[i], Integer.bitCount(supports[i]));
				int slot = 0;
				for (float value : values) {
					while (slot < 8 && (supports[i] & 1 << slot) == 0) slot++;
					if (slot == 8) throw new IllegalArgumentException("Component fill exceeds its marker slots");
					result.add(new SlotVolume(slot++, value));
				}
			}
		} else for (int slot = 0; slot < 8 && remaining.signum() > 0; slot++) {
			int i = 0; while (i < supports.length && (supports[i] & 1 << slot) == 0) i++;
			if (i == supports.length || capacities[i].signum() == 0) continue;
			SignedDyadic limit = remaining.compareTo(capacities[i]) < 0 ? remaining : capacities[i];
			float value = Math.min(FluidDomainMechanicalFields.PARTICLE_VOLUME, (float) limit.toDouble());
			while (SignedDyadic.of(value).compareTo(limit) > 0) value = Math.nextDown(value);
			if (!(value > 0)) throw new IllegalArgumentException("Component fill is below binary32 storage");
			result.add(new SlotVolume(slot, value));
			SignedDyadic stored = SignedDyadic.of(value);
			remaining = remaining.subtract(stored); capacities[i] = capacities[i].subtract(stored);
		}
		if (!full && remaining.signum() != 0) throw new IllegalArgumentException("Cell fill cannot fit its component marker slots");
		return result;
	}

	private static PhaseLiquidGeometryEdit futureEdit(FluidDomain domain, int cell, List<Piece> pieces) {
		int mask = domain.geometry.cutCells.mask(cell);
		int[] support = CellRemapMicroTopology.components(mask), count = new int[support.length];
		SignedDyadic[] volume = new SignedDyadic[support.length];
		SignedDyadic[][] first = new SignedDyadic[support.length][3];
		Arrays.fill(volume, SignedDyadic.ZERO);
		for (var moments : first) Arrays.fill(moments, SignedDyadic.ZERO);
		for (var piece : pieces) {
			int selected = CellRemapMicroTopology.positionSupport(mask, domain.config.cellSize(), piece.x(), piece.y(), piece.z()), i = 0;
			while (support[i] != selected) i++;
			SignedDyadic weight = SignedDyadic.of(piece.volume()); volume[i] = volume[i].add(weight); count[i]++;
			first[i][0] = first[i][0].add(weight.multiply(SignedDyadic.of(piece.x())));
			first[i][1] = first[i][1].add(weight.multiply(SignedDyadic.of(piece.y())));
			first[i][2] = first[i][2].add(weight.multiply(SignedDyadic.of(piece.z())));
		}
		SignedDyadic[] capacities = CellRemapMicroTopology.capacities(mask, pore(domain, cell));
		var components = new ArrayList<LiquidComponentMoments>(support.length);
		for (int i = 0; i < support.length; i++) {
			if (volume[i].compareTo(capacities[i]) > 0) throw new IllegalArgumentException("Replacement exceeds an open component capacity");
			components.add(new LiquidComponentMoments(cell, support[i], count[i], volume[i], first[i][0], first[i][1], first[i][2]));
		}
		return new PhaseLiquidGeometryEdit(cell, mask, components);
	}
}
