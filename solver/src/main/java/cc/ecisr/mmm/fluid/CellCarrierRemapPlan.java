package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.compute.CpuWorkBatch;
import cc.ecisr.mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/** Preflights a common volume, material, momentum, species and energy remap before publication. */
final class CellCarrierRemapPlan {
	private static final double[][][] OFFSETS = new double[4][][];
	static {
		for (int level = 0; level < OFFSETS.length; level++) {
			int count = 1 << level; OFFSETS[level] = new double[count][3];
			for (int slot = 0; slot < count; slot++) {
				OFFSETS[level][slot][0] = count >= 2 ? (slot % 2 == 0 ? -0.25 : 0.25) : 0;
				OFFSETS[level][slot][1] = count == 8 ? ((slot >>> 1 & 1) == 0 ? -0.25 : 0.25) : 0;
				OFFSETS[level][slot][2] = count >= 4 ? ((slot >>> (count == 8 ? 2 : 1) & 1) == 0 ? -0.25 : 0.25) : 0;
			}
		}
	}
	final ArrayList<Marker> markers = new ArrayList<>();
	final SignedDyadic energyRoundoff;
	final boolean[] changedSpeciesCells;
	final CellRemapMoments.Audit momentAudit;
	private final FluidDomain domain;
	private final ArrayList<Group>[] input, output;
	private final int[] outgoingHead, outgoingNext;
	private final boolean[] remappedCells;
	private final ArrayList<Marker> retainedMarkers = new ArrayList<>();
	private final PhaseVolumeTransportGeometry phaseGeometry;
	private final float dt;
	private final boolean geometryRemap;
	private static final ThreadLocal<CellPolynomialField.Workspace> FITTING =
			ThreadLocal.withInitial(CellPolynomialField.Workspace::new);

	CellCarrierRemapPlan(FluidDomain domain, ConservativeLiquidFlux flux, float dt, BallisticLiquidRemap ballistic) {
		this(domain, flux, dt, ballistic, false);
	}

	@SuppressWarnings("unchecked")
	CellCarrierRemapPlan(FluidDomain domain, ConservativeLiquidFlux flux, float dt, BallisticLiquidRemap ballistic, boolean geometryRemap) {
		this.domain = domain;
		this.dt = dt;
		this.geometryRemap = geometryRemap;
		changedSpeciesCells = new boolean[domain.cellCount];
		markers.ensureCapacity(domain.particles.count());
		input = (ArrayList<Group>[]) new ArrayList<?>[domain.cellCount];
		output = (ArrayList<Group>[]) new ArrayList<?>[domain.cellCount];
		outgoingHead = new int[domain.cellCount]; outgoingNext = new int[flux.flux.length]; Arrays.fill(outgoingHead, -1);
		remappedCells = new boolean[domain.cellCount];
		if (geometryRemap) Arrays.fill(remappedCells, true);
		else {
			for (int f = 0; f < flux.exactFlux.length; f++) if (flux.exactFlux[f].signum() > 0) {
				remappedCells[flux.left[f]] = true; remappedCells[flux.right[f]] = true;
			}
			retainUnchangedCells();
		}
		gatherInput();
		phaseGeometry = phaseGeometry();
		for (int f = flux.flux.length - 1; f >= 0; f--) if (flux.exactFlux[f].signum() > 0) {
			int c = flux.donor(f); outgoingNext[f] = outgoingHead[c]; outgoingHead[c] = f;
		}
		if (domain.thermalState == null && domain.chemicalParcels == null) {
			for (int cell = 0; cell < domain.cellCount; cell++) if (remappedCells[cell] && input[cell] != null) {
				distribute(cell, flux);
				for (Group group : input[cell]) if (group.shares != null) mergeShares(group.shares);
			}
		} else {
			CpuWorkBatch.run(domain.cellCount, (from, until) -> { for (int cell = from; cell < until; cell++) { if (remappedCells[cell] && input[cell] != null) distribute(cell, flux); } });
			var incoming = (ArrayList<Share>[]) new ArrayList<?>[domain.cellCount];
			for (ArrayList<Group> groups : input) if (groups != null) for (Group group : groups) if (group.shares != null)
				for (Share share : group.shares) {
					if (incoming[share.destination] == null) incoming[share.destination] = new ArrayList<>();
					incoming[share.destination].add(share);
				}
			CpuWorkBatch.run(domain.cellCount, (from, until) -> {
				for (int cell = from; cell < until; cell++) if (incoming[cell] != null) {
					mergeShares(incoming[cell]);
					if (domain.thermalState != null) for (Group group : output[cell]) group.temperature = temperature(group);
				}
			});
		}

		int sampledCount = 0;
		for (int cell = 0; cell < domain.cellCount; cell++) if (output[cell] != null) for (Group group : output[cell]) {
			group.sampleStart = sampledCount;
			group.sampleVolumes = quadratureVolumes(group);
			sampledCount = Math.addExact(sampledCount, group.sampleVolumes.size());
		}
		if ((long) sampledCount + retainedMarkers.size() + ballistic.markers.size() > domain.maximumParticleCount)
			throw new IllegalStateException("Cell carrier remap exceeds the marker budget");
		markers.addAll(java.util.Collections.nCopies(sampledCount, null));
		CpuWorkBatch.run(domain.cellCount, (from, until) -> {
			for (int cell = from; cell < until; cell++) {
				if (output[cell] != null) for (Group group : output[cell]) sample(group);
			}
		});
		markers.addAll(retainedMarkers);
		markers.addAll(ballistic.markers);
		energyRoundoff = validateInventories(flux, ballistic);
		momentAudit = momentAudit();
	}

	/** A cell without any shared transfer keeps its existing spatial integration rule. */
	private void retainUnchangedCells() {
		double h = domain.config.cellSize();
		for (int p = 0; p < domain.particles.count(); p++) {
			int cell = domain.particleCellIndex(p);
			if (remappedCells[cell] || !domain.gridToParticleStage.advectionCells()[cell]) continue;
			var chemical = domain.chemicalParcels;
			retainedMarkers.add(new Marker(cell, domain.particles.materialHandle(p), chemical != null && chemical.indicator(p),
					domain.particles.volume(p), domain.particles.mass(p),
					domain.particles.positionX(p) / h - cell % domain.sizeX - 0.5,
					domain.particles.positionY(p) / h - cell / domain.sizeX % domain.sizeY - 0.5,
					domain.particles.positionZ(p) / h - cell / (domain.sizeX * domain.sizeY) - 0.5,
					domain.particles.velocityX(p), domain.particles.velocityY(p), domain.particles.velocityZ(p),
					chemical == null ? null : chemical.record(p),
					domain.thermalState == null ? 0 : domain.thermalState.liquidInternalEnergy(p)));
		}
	}

	private void gatherInput() {
		// Bind the ordinal reference-energy cache before workers write disjoint marker entries.
		if (domain.thermalState != null && domain.particles.count() > 0)
			domain.thermalState.temperature(0, domain.thermalMarkerContext());
		CpuWorkBatch.run(domain.cellCount, (from, until) -> {
			for (int cell = from; cell < until; cell++) {
				if (!geometryRemap && !domain.gridToParticleStage.advectionCells()[cell]) continue;
				for (int p = domain.markerGrid.cellMarkerHead[cell]; p >= 0; p = domain.markerGrid.particleCellNext[p]) {
					int material = domain.particles.materialHandle(p);
					boolean indicator = domain.chemicalParcels != null && domain.chemicalParcels.indicator(p);
					ChemicalMixtureRecord mixture = domain.chemicalParcels == null ? null : domain.chemicalParcels.record(p);
					Group group = find(input, cell, material, indicator, mixture);
					group.original.add(p);
					group.kinetic += domain.particles.mass(p) * (square(domain.particles.velocityX(p)) + square(domain.particles.velocityY(p)) + square(domain.particles.velocityZ(p)));
					group.momentum[0] += domain.particles.mass(p) * domain.particles.velocityX(p);
					group.momentum[1] += domain.particles.mass(p) * domain.particles.velocityY(p);
					group.momentum[2] += domain.particles.mass(p) * domain.particles.velocityZ(p);
					for (int a = 0; a < 3; a++) {
						int coordinate = a == 0 ? cell % domain.sizeX : a == 1 ? cell / domain.sizeX % domain.sizeY : cell / (domain.sizeX * domain.sizeY);
						double position = a == 0 ? domain.particles.positionX(p) : a == 1 ? domain.particles.positionY(p) : domain.particles.positionZ(p);
						double velocity = a == 0 ? domain.particles.velocityX(p) : a == 1 ? domain.particles.velocityY(p) : domain.particles.velocityZ(p);
						double offset = position / domain.config.cellSize() - coordinate - 0.5;
						group.massMoment[a] += domain.particles.mass(p) * offset;
						group.volumeMoment[a] += domain.particles.volume(p) * offset;
						group.volumeMotion[a] += domain.particles.volume(p) * velocity;
					}
					if (mixture != null) group.species.add(mixture);
					if (domain.thermalState != null) {
						double energy = domain.thermalState.liquidInternalEnergy(p);
						group.energy = group.energy.add(SignedDyadic.of(energy)); group.energyRounding += Math.ulp(energy);
					}
				}
				ArrayList<Group> groups = input[cell];
				if (groups != null) for (Group group : groups) {
					if (groups.size() == 1) group.volume = domain.markerGrid.exactCellVolumeInventory[group.cell];
					else for (int p : group.original) group.volume = group.volume.add(SignedDyadic.of(domain.particles.volume(p)));
					group.mass = CellRemapMassAllocation.inventory(domain, group.original);
					fitFields(group, dt);
				}
			}
		});
		if (domain.thermalState != null) for (ArrayList<Group> groups : input) if (groups != null)
			for (Group group : groups) temperatureBounds(group);
	}

	private PhaseVolumeTransportGeometry phaseGeometry() {
		if (geometryRemap || domain.materialRegistry == null || domain.materialRegistry.phaseCount() <= 1) return null;
		boolean mixedCell = false;
		for (ArrayList<Group> groups : input) if (groups != null && groups.size() > 1) {
			int firstPhase = phase(groups.getFirst().material);
			for (Group group : groups) mixedCell |= phase(group.material) != firstPhase;
		}
		if (!mixedCell) return null;
		double[][] volumes = new double[domain.materialRegistry.phaseCount()][domain.cellCount];
		for (ArrayList<Group> groups : input) if (groups != null) for (Group group : groups)
			volumes[phase(group.material)][group.cell] += group.volume.toDouble();
		return new PhaseVolumeTransportGeometry(domain, volumes);
	}

	private void distribute(int cell, ConservativeLiquidFlux flux) {
		if (domain.thermalState == null && domain.chemicalParcels == null && input[cell].size() == 1
				&& input[cell].getFirst().volume.exponent() >= Math.getExponent(ConservativeMarkerSampling.VOLUME_FLUX_RESOLUTION)) {
			distributeHomogeneous(input[cell].getFirst(), flux); return;
		}
		ArrayList<Group> groups = input[cell]; int shares = 1;
		for (int f = outgoingHead[cell]; f >= 0; f = outgoingNext[f]) shares++;
		int[] destinations = new int[shares], faces = new int[shares];
		SignedDyadic[] amounts = new SignedDyadic[shares]; SignedDyadic total = SignedDyadic.ZERO, sent = SignedDyadic.ZERO;
		for (Group group : groups) total = total.add(group.volume);
		int share = 0;
		for (int f = outgoingHead[cell]; f >= 0; f = outgoingNext[f]) {
			destinations[share] = flux.receiver(f); amounts[share] = flux.exactFlux[f];
			sent = sent.add(flux.exactFlux[f]); faces[share++] = f;
		}
		destinations[share] = cell; amounts[share] = total.subtract(sent); faces[share] = -1;
		SignedDyadic[][] pieces = new SignedDyadic[groups.size()][shares];
		if (groups.size() == 1) pieces[0] = amounts;
		else {
			SignedDyadic[] weights = new SignedDyadic[groups.size()];
			for (int g = 0; g < groups.size(); g++) weights[g] = groups.get(g).volume;
			for (int d = 0; d < shares; d++) {
				SignedDyadic[] allocation;
				if (phaseGeometry == null || faces[d] < 0) allocation = ExactVolumeAllocation.allocate(amounts[d], weights);
				else {
					int face = faces[d]; double width = Math.abs(flux.velocity[face]) * dt / domain.config.cellSize();
					double[] preference = new double[groups.size()];
					for (int g = 0; g < groups.size(); g++) {
						int phase = phase(groups.get(g).material);
						preference[g] = phaseGeometry.swept(phase, cell, flux.receiver(face), flux.axis[face], width, flux.velocity[face] > 0)
								* weights[g].toDouble() / phaseGeometry.volume(phase, cell);
					}
					allocation = ExactVolumeAllocation.allocateCapped(amounts[d], weights, preference);
				}
				for (int g = 0; g < groups.size(); g++) { pieces[g][d] = allocation[g]; weights[g] = weights[g].subtract(allocation[g]); }
			}
		}
		for (int g = 0; g < groups.size(); g++) {
			Group source = groups.get(g); ChemicalMixtureRecord[] species = allocateSpecies(source.species, pieces[g]);
			prepareMoments(source);
			source.shares = new ArrayList<>(shares);
			double[] masses = CellRemapMassAllocation.allocate(source.mass, source.volume, pieces[g]);
			int pivot = CellRemapMassAllocation.largest(pieces[g]);
			SignedDyadic[] energyParts = domain.thermalState == null ? null : energyParts(source, pieces[g], masses, species, faces, flux);
			double remainingKinetic = source.kinetic;
			double[] remainingMomentum = source.momentum.clone();
			int last = -1; for (int d = 0; d < destinations.length; d++) if (pieces[g][d].signum() > 0) last = d;
			for (int ordinal = 0; ordinal <= last; ordinal++) {
				int d = ordinal == last ? pivot : ordinal < pivot ? ordinal : ordinal + 1;
				if (pieces[g][d].signum() == 0) continue;
				double fraction = pieces[g][d].toDouble() / source.volume.toDouble();
				double m = masses[d];
				double k = d == pivot ? remainingKinetic : source.kinetic * fraction;
				SignedDyadic energy = domain.thermalState == null ? SignedDyadic.ZERO : energyParts[d];
				// The residual destination inherits the complete source reduction error;
				// its bound cannot shrink with the (possibly tiny) remaining volume.
				double rounding = source.energyRounding * (d == pivot ? 1 : fraction) + Math.ulp(energy.toDouble());
				double[] momentum = new double[3];
				for (int a = 0; a < 3; a++) {
					double p = d == pivot ? remainingMomentum[a] : source.momentum[a] * fraction;
					momentum[a] = p; remainingMomentum[a] -= p;
				}
				double[] facePoint = faces[d] < 0 ? null : CellRemapMoments.face(domain, flux, faces[d]);
				if (facePoint != null) subtractFaceMoment(source, m, pieces[g][d].toDouble(), facePoint);
				source.shares.add(new Share(source, destinations[d], pieces[g][d], m, k, energy, species[d], rounding, momentum, facePoint));
				remainingKinetic -= k;
			}
			// The retained share can precede outgoing shares in residual-pivot order.
			// Its moment is read only after all outgoing face contributions have been subtracted.
			if (pieces[g][shares - 1].signum() == 0) source.discardedMoment = true;
		}
	}

	/** Receiver reductions retain the donor and share order independently of worker scheduling. */
	private void mergeShares(ArrayList<Share> shares) {
		for (Share share : shares) {
			Group source = share.source;
			Group target = find(output, share.destination, source.material, source.indicator, share.species);
			target.minimumTemperature = Math.min(target.minimumTemperature, source.minimumTemperature);
			target.maximumTemperature = Math.max(target.maximumTemperature, source.maximumTemperature);
			target.volume = target.volume.add(share.volume); target.mass += share.mass; target.kinetic += share.kinetic;
			target.energy = target.energy.add(share.energy); target.energyRounding += share.rounding;
			if (share.species != null) target.species.add(share.species);
			for (int a = 0; a < 3; a++) target.momentum[a] += share.momentum[a];
			addMoments(target, source, share.mass, share.volume.toDouble(), share.facePoint);
			target.addFields(source, share.mass, 4);
		}
	}

	/** A homogeneous cold cell needs no chemical or phase allocation. */
	private void distributeHomogeneous(Group source, ConservativeLiquidFlux flux) {
		prepareMoments(source);
		SignedDyadic remainingVolume = source.volume; double remainingMass = source.mass, remainingKinetic = source.kinetic;
		double[] remainingMomentum = source.momentum.clone();
		for (int f = outgoingHead[source.cell]; f >= 0; f = outgoingNext[f]) {
			SignedDyadic amount = flux.exactFlux[f]; remainingVolume = remainingVolume.subtract(amount);
			boolean last = remainingVolume.signum() == 0;
			double fraction = amount.toDouble() / source.volume.toDouble();
			double mass = last ? remainingMass : source.mass * fraction;
			double kinetic = last ? remainingKinetic : source.kinetic * fraction;
			Group target = find(output, flux.receiver(f), source.material, source.indicator, (ChemicalMixtureRecord) null);
			double[] facePoint = CellRemapMoments.face(domain, flux, f);
			subtractFaceMoment(source, mass, amount.toDouble(), facePoint);
			addMoments(target, source, mass, amount.toDouble(), facePoint);
			target.volume = target.volume.add(amount); target.mass += mass; target.kinetic += kinetic;
			for (int a = 0; a < 3; a++) {
				double momentum = last ? remainingMomentum[a] : source.momentum[a] * fraction;
				target.momentum[a] += momentum; remainingMomentum[a] -= momentum;
			}
			target.addFields(source, mass, 3);
			remainingMass -= mass; remainingKinetic -= kinetic;
		}
		if (remainingVolume.signum() > 0) {
			Group target = find(output, source.cell, source.material, source.indicator, (ChemicalMixtureRecord) null);
			addMoments(target, source, remainingMass, remainingVolume.toDouble(), null);
			target.volume = target.volume.add(remainingVolume); target.mass += remainingMass; target.kinetic += remainingKinetic;
			for (int a = 0; a < 3; a++) {
				target.momentum[a] += remainingMomentum[a];
			}
			target.addFields(source, remainingMass, 3);
		} else source.discardedMoment = true;
	}

	private void prepareMoments(Group source) {
		for (int a = 0; a < 3; a++) {
			source.massMoment[a] += 0.5 * dt / domain.config.cellSize() * source.momentum[a];
			source.volumeMoment[a] += 0.5 * dt / domain.config.cellSize() * source.volumeMotion[a];
		}
	}

	private static void subtractFaceMoment(Group source, double mass, double volume, double[] facePoint) {
		for (int a = 0; a < 3; a++) { source.massMoment[a] -= mass * facePoint[a]; source.volumeMoment[a] -= volume * facePoint[a]; }
	}

	private void addMoments(Group target, Group source, double mass, double volume, double[] facePoint) {
		for (int a = 0; a < 3; a++) {
			if (facePoint == null) {
				target.massMoment[a] += source.massMoment[a]; target.volumeMoment[a] += source.volumeMoment[a];
			} else {
				int stride = a == 0 ? 1 : a == 1 ? domain.sizeX : domain.sizeX * domain.sizeY;
				double point = facePoint[a] - (a == 0 ? target.cell % domain.sizeX - source.cell % domain.sizeX
						: a == 1 ? target.cell / stride % domain.sizeY - source.cell / stride % domain.sizeY : target.cell / stride - source.cell / stride);
				target.massMoment[a] += mass * point; target.volumeMoment[a] += volume * point;
			}
			target.volumeMotion[a] += volume / source.volume.toDouble() * source.volumeMotion[a];
		}
	}

	/** Limits the high-order heat flux against neighboring temperatures, retaining exact total U. */
	private SignedDyadic[] energyParts(Group source, SignedDyadic[] volumes, double[] masses, ChemicalMixtureRecord[] species,
			int[] faces, ConservativeLiquidFlux flux) {
		int last = -1;
		for (int d = 0; d < volumes.length; d++) if (volumes[d].signum() > 0) last = d;
		SignedDyadic[] baseline = new SignedDyadic[volumes.length]; Arrays.fill(baseline, SignedDyadic.ZERO);
		double meanTemperature = temperature(source), correctionSum = 0;
		int pivot = CellRemapMassAllocation.largest(volumes);
		boolean flat = true;
		for (int a = 1; a < source.transportTemperature.length; a++) flat &= source.transportTemperature[a] == 0;
		double[] correction = flat ? null : new double[volumes.length];
		double[] lower = flat ? null : new double[volumes.length], upper = flat ? null : new double[volumes.length];
		source.energyRounding += caloric(source).roundoff(meanTemperature);
		SignedDyadic remainingEnergy = source.energy;
		for (int d = 0; d <= last; d++) {
			if (volumes[d].signum() == 0) continue;
			double mass = masses[d];
			ChemicalMixtureRecord composition = species[d];
			var caloric = domain.thermalState.liquid().caloricInventory(source.material, mass, composition,
					domain.chemicalParcels == null ? null : domain.chemicalParcels.registry());
			baseline[d] = SignedDyadic.of(caloric.energy(meanTemperature));
			remainingEnergy = remainingEnergy.subtract(baseline[d]);
			if (flat) continue;
			lower[d] = caloric.energy(source.minimumTemperature);
			upper[d] = caloric.energy(source.maximumTemperature);
			if (d == last) correction[d] = -correctionSum;
			else if (!geometryRemap && faces[d] >= 0) {
				int face = faces[d]; double[] point = new double[3];
				point[flux.axis[face]] = Math.copySign(0.5 - 0.5 * Math.abs(flux.velocity[face]) * dt / domain.config.cellSize(), flux.velocity[face]);
				double t = CellPolynomialField.evaluate(source.transportTemperature, point[0], point[1], point[2]);
				t = Math.max(source.minimumTemperature, Math.min(source.maximumTemperature, t));
				correction[d] = caloric.energy(t) - baseline[d].toDouble();
				correctionSum += correction[d];
			}
		}
		baseline[pivot] = baseline[pivot].add(remainingEnergy);
		if (flat) return baseline;
		double scale = 1;
		for (int d = 0; d <= last; d++) {
			if (correction[d] > 0) scale = Math.min(scale, Math.max(0, upper[d] - baseline[d].toDouble()) / correction[d]);
			if (correction[d] < 0) scale = Math.min(scale, Math.max(0, baseline[d].toDouble() - lower[d]) / -correction[d]);
		}
		remainingEnergy = source.energy;
		for (int d = 0; d <= last; d++) if (volumes[d].signum() > 0) {
			baseline[d] = baseline[d].add(SignedDyadic.of(scale * correction[d]));
			if (d != pivot) remainingEnergy = remainingEnergy.subtract(baseline[d]);
		}
		baseline[pivot] = remainingEnergy;
		return baseline;
	}

	private void temperatureBounds(Group group) {
		group.minimumTemperature = group.localMinimum; group.maximumTemperature = group.localMaximum;
		int c = group.cell, x = c % domain.sizeX, y = c / domain.sizeX % domain.sizeY, z = c / (domain.sizeX * domain.sizeY);
		int[] neighbors = {x > 0 ? c - 1 : -1, x + 1 < domain.sizeX ? c + 1 : -1,
				y > 0 ? c - domain.sizeX : -1, y + 1 < domain.sizeY ? c + domain.sizeX : -1,
				z > 0 ? c - domain.sizeX * domain.sizeY : -1, z + 1 < domain.sizeZ ? c + domain.sizeX * domain.sizeY : -1};
		for (int neighbor : neighbors) if (neighbor >= 0 && input[neighbor] != null)
			for (Group other : input[neighbor]) if (other.material == group.material) {
				group.minimumTemperature = Math.min(group.minimumTemperature, other.localMinimum);
				group.maximumTemperature = Math.max(group.maximumTemperature, other.localMaximum);
			}
	}

	private void fitFields(Group group, float dt) {
		CellPolynomialField.Workspace fitting = FITTING.get();
		int count = group.original.size(); fitting.ensureCapacity(count);
		double[][] positions = fitting.positions, values = fitting.values; double[] weights = fitting.weights;
		float h = domain.config.cellSize();
		int x = group.cell % domain.sizeX, y = group.cell / domain.sizeX % domain.sizeY, z = group.cell / (domain.sizeX * domain.sizeY);
		for (int local = 0; local < count; local++) {
			int p = group.original.get(local); weights[local] = domain.particles.mass(p);
			positions[0][local] = domain.particles.positionX(p) / (double) h - x - 0.5;
			positions[1][local] = domain.particles.positionY(p) / (double) h - y - 0.5;
			positions[2][local] = domain.particles.positionZ(p) / (double) h - z - 0.5;
			values[0][local] = domain.particles.velocityX(p); values[1][local] = domain.particles.velocityY(p); values[2][local] = domain.particles.velocityZ(p);
			if (domain.thermalState != null) {
				values[3][local] = domain.thermalState.temperature(p, domain.thermalMarkerContext());
				group.localMinimum = Math.min(group.localMinimum, values[3][local]);
				group.localMaximum = Math.max(group.localMaximum, values[3][local]);
			} else values[3][local] = 0;
		}
		group.field = CellPolynomialField.fit(positions, weights, values, count);
		if (domain.thermalState != null) {
			group.transportTemperature = group.field[3].clone();
			group.field[3] = CellPolynomialField.deform(group.field[3], domain, group.cell, dt);
		}
	}

	private ArrayList<Float> quadratureVolumes(Group group) {
		double amount = group.volume.toDouble();
		int requested = ConservativeMarkerSampling.targetMarkers((float) amount);
		int count = requested <= 1 ? 1 : requested <= 2 ? 2 : requested <= 4 ? 4 : 8;
		if (output[group.cell].size() > 1) count = Integer.highestOneBit(Math.min(8,
				Math.max(1, (int) Math.floor(amount / ConservativeMarkerSampling.NOMINAL_MARKER_VOLUME))));
		int mask = domain.geometry.cutCells.mask(group.cell);
		if (mask != 0) count = 8 - Integer.bitCount(mask);
		if (domain.chemicalParcels != null) {
			long quanta = group.species.totalQuanta();
			count = (int) Math.min(count, quanta);
			if (mask == 0) count = Integer.highestOneBit(count);
		}
		group.quadratureCount = count;
		return LiquidCellQuadrature.volumes(group.volume, count);
	}

	private void sample(Group group) {
		int count = group.quadratureCount, mask = domain.geometry.cutCells.mask(group.cell);
		ArrayList<Float> volumes = group.sampleVolumes;
		ArrayList<double[]> offsets = new ArrayList<>();
		for (int slot = 0; slot < volumes.size(); slot++) offsets.add(slot < count
				? mask == 0 ? offset(slot, count) : openOffset(mask, slot)
				: mask == 0 ? offset(0, 1) : openOffset(mask, 0));
		SignedDyadic[] weights = new SignedDyadic[volumes.size()];
		for (int p = 0; p < weights.length; p++) weights[p] = SignedDyadic.of(volumes.get(p));
		double[] masses = CellRemapMassAllocation.allocate(group.mass, group.volume, weights);
		for (int a = 0; a < 3; a++) {
			group.massMoment[a] += 0.5 * dt / domain.config.cellSize() * group.momentum[a];
			group.volumeMoment[a] += 0.5 * dt / domain.config.cellSize() * group.volumeMotion[a];
		}
		boolean moved = CellRemapMoments.fit(domain, group.cell, offsets, masses, group.massMoment, group.projection, group.storage);
		for (int a = 0; a < 3; a++) {
			double volumeMoment = 0;
			int coordinate = a == 0 ? group.cell % domain.sizeX : a == 1 ? group.cell / domain.sizeX % domain.sizeY : group.cell / (domain.sizeX * domain.sizeY);
			double h = domain.config.cellSize();
			for (int p = 0; p < volumes.size(); p++) {
				float stored = (float) ((coordinate + 0.5 + offsets.get(p)[a]) * h);
				volumeMoment += volumes.get(p) * (stored / h - coordinate - 0.5);
			}
			group.volumeResidual[a] = volumeMoment - group.volumeMoment[a];
		}
		ChemicalMixtureRecord[] species = domain.chemicalParcels == null ? null : allocateSpecies(group.species, weights);
		double[] mean = new double[3], fieldMean = new double[4];
		for (int a = 0; a < 3; a++) mean[a] = group.momentum[a] / group.mass;
		double[][] fieldValues = evaluateQuadrature(group, offsets, volumes, fieldMean, !moved && mask == 0 && count == 8 && volumes.size() == 8);
		double scale = velocityScale(group, volumes, mean, fieldMean, fieldValues);
		double temperatureScale = domain.thermalState == null ? 1 : temperatureScale(group, fieldMean[3], fieldValues[3]);
		SignedDyadic energyRemaining = group.energy;
		int start = group.sampleStart;
		var caloric = domain.thermalState == null ? null
				: new cc.ecisr.mmm.thermal.LiquidEnergyColumn.CaloricInventory[volumes.size()];
		for (int p = 0; p < volumes.size(); p++) {
			double m = masses[p];
			if (!(Double.isFinite(m) && m > 0)) throw new IllegalStateException("Carrier remap produced invalid mass");
			ChemicalMixtureRecord mixture = domain.chemicalParcels == null ? null : mixture(species[p], m);
			double[] delta = offsets.get(p);
			double vx = mean[0] + scale * (fieldValues[0][p] - fieldMean[0]);
			double vy = mean[1] + scale * (fieldValues[1][p] - fieldMean[1]);
			double vz = mean[2] + scale * (fieldValues[2][p] - fieldMean[2]);
			double energy = 0;
			if (domain.thermalState != null) {
				double t = group.temperature + temperatureScale * (fieldValues[3][p] - fieldMean[3]);
				t = Math.max(group.minimumTemperature, Math.min(group.maximumTemperature, t));
				for (int earlier = 0; earlier < p; earlier++) {
					var previous = markers.get(start + earlier);
					if (Double.doubleToRawLongBits(previous.mass()) == Double.doubleToRawLongBits(m)
							&& java.util.Objects.equals(previous.mixture(), mixture)) { caloric[p] = caloric[earlier]; break; }
				}
				if (caloric[p] == null) caloric[p] = domain.thermalState.liquid().caloricInventory(group.material, m, mixture,
						domain.chemicalParcels == null ? null : domain.chemicalParcels.registry());
				energy = caloric[p].energy(t);
				energyRemaining = energyRemaining.subtract(SignedDyadic.of(energy));
			}
			markers.set(start + p, new Marker(group.cell, group.material, group.indicator, volumes.get(p), m,
					delta[0], delta[1], delta[2], vx, vy, vz, mixture, energy));
		}
		if (domain.thermalState != null) {
			CellRemapHeatBalance.complete(markers, start, caloric, group.minimumTemperature, group.maximumTemperature, energyRemaining, group.energyRounding);
		}
	}

	private double[][] evaluateQuadrature(Group group, ArrayList<double[]> offsets, ArrayList<Float> volumes, double[] fieldMean, boolean tensor) {
		int fields = domain.thermalState == null ? 3 : 4;
		double amount = group.volume.toDouble(); double[][] fieldValues = new double[fields][];
		for (int a = 0; a < fields; a++) fieldValues[a] = tensor ? CellPolynomialField.tensorValues(group.field[a]) : new double[offsets.size()];
		for (int p = 0; p < offsets.size(); p++) for (int a = 0; a < fields; a++) {
			double[] delta = offsets.get(p);
			fieldValues[a][p] = (tensor ? fieldValues[a][p] : CellPolynomialField.evaluate(group.field[a], delta[0], delta[1], delta[2])) / group.mass;
			fieldMean[a] += volumes.get(p) / amount * fieldValues[a][p];
		}
		for (int a = 0; a < fields; a++) {
			boolean constant = true;
			for (int p = 1; p < offsets.size(); p++) if (fieldValues[a][p] != fieldValues[a][0]) constant = false;
			if (constant) fieldMean[a] = fieldValues[a][0];
		}
		return fieldValues;
	}

	private static double velocityScale(Group group, ArrayList<Float> volumes, double[] mean, double[] fieldMean, double[][] values) {
		double resolved = 0, meanSquare = 0, amount = group.volume.toDouble();
		for (int a = 0; a < 3; a++) meanSquare += square(mean[a]);
		for (int p = 0; p < volumes.size(); p++) for (int a = 0; a < 3; a++)
			resolved += volumes.get(p) / amount * square(values[a][p] - fieldMean[a]);
		double budget = Math.max(0, group.kinetic / group.mass - meanSquare);
		return resolved > budget ? Math.sqrt(budget / resolved) : 1;
	}

	private static double temperatureScale(Group group, double mean, double[] values) {
		double scale = 1;
		for (double value : values) {
			double variation = value - mean;
			if (variation > 0) scale = Math.min(scale, Math.max(0, group.maximumTemperature - group.temperature) / variation);
			if (variation < 0) scale = Math.min(scale, Math.max(0, group.temperature - group.minimumTemperature) / -variation);
		}
		return scale;
	}

	private double temperature(Group group) {
		return caloric(group).temperature(group.energy.toDouble());
	}

	private cc.ecisr.mmm.thermal.LiquidEnergyColumn.CaloricInventory caloric(Group group) {
		if (group.caloric == null) group.caloric = domain.thermalState.liquid().caloricInventory(group.material, group.mass,
				domain.chemicalParcels == null ? null : speciesRecord(group),
				domain.chemicalParcels == null ? null : domain.chemicalParcels.registry());
		return group.caloric;
	}


	private ChemicalMixtureRecord mixture(ChemicalMixtureRecord species, double mass) {
		double residual = domain.unitScale.kilograms(mass) - species.exactSpeciesMassKilograms(domain.chemicalParcels.registry()).toDouble();
		return species.withMassResidualKilograms(residual);
	}

	private ChemicalMixtureRecord speciesRecord(Group group) { return group.species.record(); }

	private SignedDyadic validateInventories(ConservativeLiquidFlux flux, BallisticLiquidRemap ballistic) {
		SignedDyadic energyRoundoff = CellRemapInventoryAudit.validate(domain, markers, flux, ballistic);
		if (domain.chemicalParcels == null) return energyRoundoff;
		int speciesCount = domain.chemicalParcels.registry().species().size();
		long[] before = new long[speciesCount], after = new long[speciesCount];
		for (ArrayList<Group> groups : input) if (groups != null) for (Group group : groups) add(before, group.species);
		for (Marker marker : ballistic.markers) if (marker.mixture != null) add(before, marker.mixture);
		for (Marker marker : markers) if (marker.mixture != null) add(after, marker.mixture);
		if (!Arrays.equals(before, after)) throw new IllegalStateException("Cell remap changed exact species inventory");
		if (domain.chemicalParcels != null) {
			for (ArrayList<Group> groups : output) if (groups != null) for (Group group : groups)
				domain.chemicalParcels.requireCompatible(speciesRecord(group), domain.materialRegistry.material(group.material).id(), group.indicator);
			@SuppressWarnings("unchecked") var isolated = (TreeMap<Integer, Long>[]) new TreeMap<?, ?>[domain.cellCount];
			for (var preserved : java.util.List.of(ballistic.markers, retainedMarkers)) for (Marker marker : preserved) {
				if (isolated[marker.cell] == null) isolated[marker.cell] = new TreeMap<>();
				add(isolated[marker.cell], marker.mixture);
			}
			for (int c = 0; c < domain.cellCount; c++) {
				if (isolated[c] == null && (geometryRemap || domain.gridToParticleStage.advectionCells()[c])
						&& input[c] != null && output[c] != null && input[c].size() == 1 && output[c].size() == 1) {
					changedSpeciesCells[c] = !input[c].getFirst().species.sameAmounts(output[c].getFirst().species);
					continue;
				}
				Map<Integer, Long> original = cellSpecies(input[c]), replacement = cellSpecies(output[c]);
				if (!geometryRemap && !domain.gridToParticleStage.advectionCells()[c] && domain.markerGrid.markerCount[c] > 0) {
					var gathered = new TreeMap<Integer, Long>();
					for (int p = domain.markerGrid.cellMarkerHead[c]; p >= 0; p = domain.markerGrid.particleCellNext[p]) add(gathered, domain.chemicalParcels.record(p));
					original = gathered;
				}
				if (isolated[c] != null) { var combined = new TreeMap<>(replacement); add(combined, isolated[c]); replacement = combined; }
				changedSpeciesCells[c] = !original.equals(replacement);
			}
		}
		return energyRoundoff;
	}

	private CellRemapMoments.Audit momentAudit() {
		double[] projection = new double[3], storage = new double[3], volume = new double[3], unadvanced = new double[3];
		double maximum = 0, h = domain.config.cellSize(); int projected = 0;
		for (ArrayList<Group> groups : output) if (groups != null) for (Group group : groups) {
			double worst = 0;
			for (int a = 0; a < 3; a++) {
				projection[a] += domain.unitScale.kilograms(h * Math.abs(group.projection[a])); storage[a] += domain.unitScale.kilograms(h * Math.abs(group.storage[a]));
				volume[a] += h * Math.abs(group.volumeResidual[a]); worst = Math.max(worst, h * Math.abs(group.projection[a]) / group.mass);
			}
			maximum = Math.max(maximum, worst); if (worst > 0) projected++;
		}
		for (ArrayList<Group> groups : input) if (groups != null) for (Group group : groups) {
			double worst = 0;
			for (int a = 0; a < 3; a++) {
				if (group.discardedMoment) {
					projection[a] += domain.unitScale.kilograms(h * Math.abs(group.massMoment[a])); volume[a] += h * Math.abs(group.volumeMoment[a]);
					worst = Math.max(worst, h * Math.abs(group.massMoment[a]) / group.mass);
				}
				if (!remappedCells[group.cell]) unadvanced[a] += domain.unitScale.kilograms(Math.abs(dt * group.momentum[a]));
			}
			maximum = Math.max(maximum, worst); if (worst > 0) projected++;
		}
		return new CellRemapMoments.Audit(projection, storage, volume, maximum, projected, unadvanced);
	}

	private static Map<Integer, Long> cellSpecies(ArrayList<Group> groups) {
		if (groups == null) return Map.of();
		var result = new TreeMap<Integer, Long>();
		for (Group group : groups) add(result, group.species);
		return result;
	}

	private static Group find(ArrayList<Group>[] field, int cell, int material, boolean indicator, ChemicalMixtureRecord mixture) {
		if (field[cell] == null) field[cell] = new ArrayList<>(1);
		for (Group group : field[cell]) if (group.material == material && group.indicator == indicator) {
			if (group.species.compatible(mixture)) return group;
		}
		Group group = new Group(cell, material, indicator); field[cell].add(group); return group;
	}

	/** Uses the same bounded species unions as the carrier planner for larger registries. */
	@SuppressWarnings("unchecked")
	static int[] chemicalShares(FluidDomain domain) {
		var groups = (ArrayList<Group>[]) new ArrayList<?>[domain.cellCount];
		for (int p = 0; p < domain.particles.count(); p++) {
			var mixture = domain.chemicalParcels.record(p);
			Group group = find(groups, domain.particleCellIndex(p), domain.particles.materialHandle(p),
					domain.chemicalParcels.indicator(p), mixture);
			group.species.add(mixture);
		}
		int[] shares = new int[domain.cellCount]; Arrays.fill(shares, 7);
		for (int cell = 0; cell < groups.length; cell++) if (groups[cell] != null) for (Group group : groups[cell]) {
			long total = group.species.totalQuanta();
			shares[cell] = (int) Math.min(shares[cell], total);
		}
		return shares;
	}

	private static ChemicalMixtureRecord[] allocateSpecies(CellSpeciesInventory species, SignedDyadic[] weights) {
		ChemicalMixtureRecord[] result = new ChemicalMixtureRecord[weights.length];
		if (species.isEmpty()) return result;
		int[] handles = new int[species.size()]; long[][] quantities = new long[species.size()][];
		long[] totals = new long[weights.length]; int slot = 0;
		for (; slot < species.size(); slot++) {
			handles[slot] = species.handleAt(slot); quantities[slot] = ExactVolumeAllocation.allocate(species.amountAt(slot), weights);
			for (int p = 0; p < result.length; p++) totals[p] = Math.addExact(totals[p], quantities[slot][p]);
		}
		// Each live chemical carrier holds at least one integer quantum. Repair empty
		// positive-volume shares by moving one existing quantum from the largest share.
		for (int p = 0; p < result.length; p++) if (weights[p].signum() > 0 && totals[p] == 0) {
			int donor = -1; long largest = 1;
			for (int d = 0; d < result.length; d++) {
				if (totals[d] > largest) { donor = d; largest = totals[d]; }
			}
			if (donor < 0) throw new IllegalStateException("Liquid flux requires more chemical carriers than available quanta");
			for (long[] values : quantities) if (values[donor] > 0) { values[donor]--; values[p]++; break; }
			totals[donor]--; totals[p]++;
		}
		for (int p = 0; p < result.length; p++) if (totals[p] > 0) {
			// Compare the planned quantities before allocating a new immutable record.
			for (int earlier = 0; earlier < p; earlier++) if (result[earlier] != null) {
				boolean same = true;
				for (long[] values : quantities) if (values[earlier] != values[p]) { same = false; break; }
				if (same) { result[p] = result[earlier]; break; }
			}
			if (result[p] != null) continue;
			int count = 0; for (long[] values : quantities) if (values[p] > 0) count++;
			int[] parcelHandles = new int[count]; long[] amounts = new long[count]; int entry = 0;
			for (int i = 0; i < handles.length; i++) if (quantities[i][p] > 0) {
				parcelHandles[entry] = handles[i]; amounts[entry++] = quantities[i][p];
			}
			result[p] = new ChemicalMixtureRecord(parcelHandles, amounts, 0);
		}
		return result;
	}

	private static void add(long[] destination, CellSpeciesInventory source) {
		for (int entry = 0; entry < source.size(); entry++) {
			int handle = source.handleAt(entry); destination[handle] = Math.addExact(destination[handle], source.amountAt(entry));
		}
	}
	private static void add(long[] destination, ChemicalMixtureRecord source) {
		for (int entry = 0; entry < source.entryCount(); entry++) {
			int handle = source.speciesHandle(entry); destination[handle] = Math.addExact(destination[handle], source.amountAt(entry));
		}
	}

	private static void add(TreeMap<Integer, Long> destination, Map<Integer, Long> source) { source.forEach((handle, amount) -> destination.merge(handle, amount, Math::addExact)); }
	private static void add(TreeMap<Integer, Long> destination, CellSpeciesInventory source) {
		for (int entry = 0; entry < source.size(); entry++) destination.merge(source.handleAt(entry), source.amountAt(entry), Math::addExact);
	}
	private static void add(TreeMap<Integer, Long> destination, ChemicalMixtureRecord mixture) {
		for (int i = 0; i < mixture.entryCount(); i++) destination.merge(mixture.speciesHandle(i), mixture.amountAt(i), Math::addExact);
	}
	private static double square(double value) { return value * value; }
	private int phase(int material) { return domain.materialRegistry == null ? 0 : domain.materialRegistry.phaseHandle(material); }
	private static double[] offset(int slot, int count) {
		return OFFSETS[Integer.numberOfTrailingZeros(count)][slot];
	}

	private static double[] openOffset(int mask, int ordinal) {
		for (int slot = 0; slot < 8; slot++) if ((mask & 1 << slot) == 0 && ordinal-- == 0) return offset(slot, 8);
		throw new IllegalStateException("Cell quadrature has no open micro box");
	}

	record Marker(int cell, int material, boolean indicator, float volume, double mass,
			double offsetX, double offsetY, double offsetZ, double velocityX, double velocityY, double velocityZ,
			ChemicalMixtureRecord mixture, double energy) {
		double offset(int axis) { return switch (axis) { case 0 -> offsetX; case 1 -> offsetY; case 2 -> offsetZ; default -> throw new IllegalArgumentException("Unknown axis"); }; }
		double velocity(int axis) { return switch (axis) { case 0 -> velocityX; case 1 -> velocityY; case 2 -> velocityZ; default -> throw new IllegalArgumentException("Unknown axis"); }; }
		Marker withEnergy(double value) { return new Marker(cell, material, indicator, volume, mass, offsetX, offsetY, offsetZ, velocityX, velocityY, velocityZ, mixture, value); }
	}
	private record Share(Group source, int destination, SignedDyadic volume, double mass, double kinetic,
			SignedDyadic energy, ChemicalMixtureRecord species, double rounding, double[] momentum, double[] facePoint) { }
	private static final class Group {
		final int cell, material; final boolean indicator;
		int sampleStart, quadratureCount; ArrayList<Float> sampleVolumes;
		ArrayList<Share> shares;
		final ArrayList<Integer> original = new ArrayList<>(); final CellSpeciesInventory species = new CellSpeciesInventory();
		SignedDyadic volume = SignedDyadic.ZERO, energy = SignedDyadic.ZERO;
		cc.ecisr.mmm.thermal.LiquidEnergyColumn.CaloricInventory caloric;
		double mass, kinetic, temperature, energyRounding; double[] transportTemperature; final double[] momentum = new double[3]; double[][] field;
		final double[] massMoment = new double[3], volumeMoment = new double[3], volumeMotion = new double[3];
		final double[] projection = new double[3], storage = new double[3], volumeResidual = new double[3]; boolean discardedMoment;
		double localMinimum = Double.POSITIVE_INFINITY, localMaximum = Double.NEGATIVE_INFINITY;
		double minimumTemperature = Double.POSITIVE_INFINITY, maximumTemperature = Double.NEGATIVE_INFINITY;
		Group(int cell, int material, boolean indicator) { this.cell = cell; this.material = material; this.indicator = indicator; }
		void addFields(Group source, double weight, int count) {
			if (field == null) field = new double[4][8];
			for (int a = 0; a < count; a++) for (int b = 0; b < 8; b++) field[a][b] += weight * source.field[a][b];
		}
	}
}
