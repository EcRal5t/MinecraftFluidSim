package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.units.SignedDyadic;

import java.util.Arrays;

/** Conservative cell-volume transport over the pressure geometry. */
final class CellLiquidVolumeTransport {
	private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("mmm.cellVolumeTransport", "true"));
	private final FluidDomain domain;
	private final ConservativeLiquidFlux transport;
	private final LiquidFluxGeometry geometry;
	private final double[] volume;
	private final SignedDyadic[] exactVolume;
	private final int[] macFace;
	private final double[] outgoing;
	double collisionWorkJoules;
	boolean publishedQuadrature;

	CellLiquidVolumeTransport(FluidDomain domain) {
		this.domain = domain;
		transport = new ConservativeLiquidFlux(domain.sizeX, domain.sizeY, domain.sizeZ);
		geometry = new LiquidFluxGeometry(domain); transport.geometry(geometry);
		volume = new double[domain.cellCount];
		exactVolume = new SignedDyadic[domain.cellCount];
		outgoing = new double[domain.cellCount]; macFace = new int[transport.flux.length];
		for (int f = 0; f < macFace.length; f++) {
			int c = transport.right[f], x = c % domain.sizeX, y = c / domain.sizeX % domain.sizeY, z = c / (domain.sizeX * domain.sizeY);
			macFace[f] = switch (transport.axis[f]) {
				case 0 -> domain.xFaceIndex(x, y, z);
				case 1 -> domain.yFaceIndex(x, y, z);
				default -> domain.zFaceIndex(x, y, z);
			};
		}
	}

	static boolean enabled() {
		return ENABLED;
	}

	/** Eulerian donor outflow and partial-micro-box sweep rate; prediction is used only for step estimation. */
	double courantRate(double verticalPrediction) {
		double maximum = 0; Arrays.fill(outgoing, 0);
		for (int f = 0; f < transport.flux.length; f++) {
			double velocity = faceVelocity(f) + (transport.axis[f] == 1 ? verticalPrediction : 0);
			if (domain.geometry.solid[transport.left[f]] || domain.geometry.solid[transport.right[f]]) continue;
			int donor = velocity >= 0 ? transport.left[f] : transport.right[f];
			if (domain.markerGrid.exactCellVolumeInventory[donor].signum() == 0) continue;
			double open = geometry.aperture(transport.left[f], transport.right[f], transport.axis[f]);
			if (open == 0) continue;
			outgoing[donor] += Math.abs(velocity) / domain.config.cellSize() * open / geometry.capacity(donor);
			if (geometry.partialFace(transport.left[f], transport.right[f]))
				maximum = Math.max(maximum, 2 * Math.abs(velocity) / domain.config.cellSize());
		}
		for (double rate : outgoing) maximum = Math.max(maximum, rate);
		return maximum;
	}

	private float faceVelocity(int face) {
		return switch (transport.axis[face]) {
			case 0 -> domain.mac.velocityX[macFace[face]];
			case 1 -> domain.mac.velocityY[macFace[face]];
			default -> domain.mac.velocityZ[macFace[face]];
		};
	}

	void advect(float dt) {
		collisionWorkJoules = 0;
		publishedQuadrature = false;
		if (stationary()) return;
		var ballistic = new BallisticLiquidRemap(domain, dt);
		collisionWorkJoules = ballistic.collisionWorkJoules;
		transport.reserve(ballistic.reserved);
		transport.carrierShares(chemicalShares());
		LiquidParticleStore particles = domain.particles;
		for (int c = 0; c < exactVolume.length; c++) exactVolume[c] = domain.gridToParticleStage.advectionCells()[c]
				? domain.markerGrid.exactCellVolumeInventory[c] : SignedDyadic.ZERO;
		for (int c = 0; c < volume.length; c++) volume[c] = exactVolume[c].toDouble();
		for (int f = 0; f < transport.flux.length; f++) transport.velocity[f] = faceVelocity(f);
		double maximumOutgoing = courantRate(0) * dt;
		if (maximumOutgoing > 1 + 8 * Math.ulp(1.0F))
			throw new MechanicalTimeRefinement((int) Math.ceil(maximumOutgoing * domain.config.timeStep() / dt));
		try {
			transport.plan(volume, domain.geometry.solid, dt / domain.config.cellSize(), exactVolume);
		} catch (MechanicalTimeRefinement refinement) {
			throw new MechanicalTimeRefinement((int) Math.ceil(2 * domain.config.timeStep() / dt), refinement.getMessage());
		}
		var candidate = new CellCarrierRemapPlan(domain, transport, dt, ballistic);
		domain.coordinator.publishCellRemap(CarrierMutationPlan.wholesaleCount(domain.carrierMutationRevision(), particles.count()), candidate);
		publishedQuadrature = true;
	}

	private boolean stationary() {
		for (float[] field : new float[][] {domain.mac.velocityX, domain.mac.velocityY, domain.mac.velocityZ})
			for (float value : field) if (value != 0) return false;
		for (int p = 0; p < domain.particles.count(); p++)
			if (domain.particles.velocityX(p) != 0 || domain.particles.velocityY(p) != 0 || domain.particles.velocityZ(p) != 0) return false;
		if (domain.granularState != null) for (int p = 0; p < domain.granularState.parcels.count(); p++) {
			var parcel = domain.granularState.parcels.parcel(p);
			if (parcel.velocityX() != 0 || parcel.velocityY() != 0 || parcel.velocityZ() != 0) return false;
		}
		return true;
	}

	private int[] chemicalShares() {
		if (domain.chemicalParcels == null) return null;
		if (domain.chemicalParcels.registry().species().size() > cc.ecisr.mmm.chemistry.ChemicalLimits.MAX_SPECIES_PER_PARCEL)
			return CellCarrierRemapPlan.chemicalShares(domain);
		int[] shares = new int[domain.cellCount]; Arrays.fill(shares, 7);
		long[] firstKey = new long[domain.cellCount], firstAmount = new long[domain.cellCount]; Arrays.fill(firstKey, -1);
		var others = new java.util.HashMap<Long, Long>();
		for (int p = 0; p < domain.particles.count(); p++) {
			int c = domain.particleCellIndex(p);
			long key = ((long) domain.particles.materialHandle(p) << 1) | (domain.chemicalParcels.indicator(p) ? 1 : 0);
			long amount = domain.chemicalParcels.record(p).totalQuanta();
			if (firstKey[c] < 0) firstKey[c] = key;
			if (firstKey[c] == key) firstAmount[c] = Math.addExact(firstAmount[c], amount);
			else others.merge(((long) c << 33) | key, amount, Math::addExact);
		}
		for (int c = 0; c < domain.cellCount; c++) if (firstKey[c] >= 0) shares[c] = (int) Math.min(shares[c], firstAmount[c]);
		others.forEach((key, count) -> { int c = (int) (key >>> 33); shares[c] = (int) Math.min(shares[c], count); });
		return shares;
	}
}
