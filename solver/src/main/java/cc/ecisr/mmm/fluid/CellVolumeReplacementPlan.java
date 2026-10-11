package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.ChemicalLimits;
import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalParcelStore;
import cc.ecisr.mmm.chemistry.ChemicalTransferLedger;
import cc.ecisr.mmm.thermal.LiquidEnergyColumn;
import cc.ecisr.mmm.thermal.ThermalDomainState;
import cc.ecisr.mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.List;

/** Cell-local external replacement, with all storage and side-column rejections before publication. */
final class CellVolumeReplacementPlan {
	record Piece(float x, float y, float z, float volume, double mass, ChemicalMixtureRecord mixture) { }
	final CarrierMutationPlan mutation;
	final int cell;
	final int[] removed;
	final List<Piece> pieces;
	final ChemicalParcelStore.AuditExchange chemistryAudit;
	final ThermalDomainState.LiquidReplacementPlan thermal;
	private final long chemicalRevision;

	private CellVolumeReplacementPlan(FluidDomain domain, int cell, int[] removed, List<Piece> pieces,
			ChemicalParcelStore.AuditExchange chemistryAudit, ThermalDomainState.LiquidReplacementPlan thermal) {
		this.mutation = CarrierMutationPlan.wholesaleCount(domain.carrierMutationRevision(), domain.particles.count());
		this.cell = cell; this.removed = removed; this.pieces = List.copyOf(pieces);
		this.chemistryAudit = chemistryAudit; this.thermal = thermal;
		chemicalRevision = domain.chemicalParcels == null ? 0 : domain.chemicalParcels.mutationRevision();
	}

	static CellVolumeReplacementPlan build(FluidDomain domain, int x, int y, int z, float amount, boolean full) {
		int cell = domain.cellIndex(x, y, z);
		ArrayList<Integer> selected = new ArrayList<>();
		for (int p = domain.particles.count() - 1; p >= 0; p--)
			if (domain.particleCellIndex(p) == cell) selected.add(p);
		int[] removed = selected.stream().mapToInt(Integer::intValue).toArray();
		ArrayList<Float> volumes = new ArrayList<>();
		int openSlots = 8 - Integer.bitCount(domain.geometry.cutCells.mask(cell));
		if (amount > 0 && full) volumes = LiquidCellQuadrature.volumes(SignedDyadic.of(amount), openSlots);
		else for (float remaining = amount; remaining > 0;) {
			float piece = Math.min(FluidDomainMechanicalFields.PARTICLE_VOLUME, remaining);
			volumes.add(piece); remaining -= piece;
		}
		if (volumes.size() > openSlots)
			throw new IllegalArgumentException("Cell volume cannot be represented on its open marker slots");
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
		int slot = domain.firstOpenSlot(cell, 0);
		for (float volume : volumes) {
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
			float[] position = domain.markerSlotPosition(x, y, z, slot);
			pieces.add(new Piece(position[0], position[1], position[2], volume, mass, mixture));
			thermalInsertions.add(new LiquidEnergyColumn.LiquidInsertion(0, mass, mixture));
			slot = domain.firstOpenSlot(cell, slot + 1);
		}
		if (quanta > ChemicalLimits.MAX_REGION_TOTAL_QUANTA)
			throw new IllegalArgumentException("Region chemical inventory is over budget");
		ChemicalTransferLedger source = sourceBuilder == null ? null : sourceBuilder.build();
		ChemicalTransferLedger sink = sinkBuilder == null ? null : sinkBuilder.build();
		var audit = chemistry == null ? null : chemistry.planAuditExchange(source, sink);
		var thermal = domain.thermalState == null || removed.length == 0 && pieces.isEmpty() ? null : domain.thermalState.planLiquidReplacement(
				removed, thermalInsertions, domain.thermalMarkerContext());
		// Reserve only storage. Ownership, inventory, audit objects and revisions remain unchanged.
		domain.ensureParticleCapacity(finalCount);
		if (domain.thermalState != null) domain.thermalState.ensureCapacity(Math.max(finalCount, domain.particles.capacity()));
		if (chemistry != null) chemistry.pool().reserveRecords(finalCount);
		return new CellVolumeReplacementPlan(domain, cell, removed, pieces, audit, thermal);
	}

	void requireCurrent(FluidDomain domain) {
		if (domain.chemicalParcels != null && domain.chemicalParcels.mutationRevision() != chemicalRevision)
			throw new IllegalStateException("Cell replacement chemical inventory or audit changed after planning");
		if (chemistryAudit != null) domain.chemicalParcels.requireCurrentAuditExchange(chemistryAudit);
		if (thermal != null) domain.thermalState.requireCurrentLiquidReplacement(thermal);
	}
}
