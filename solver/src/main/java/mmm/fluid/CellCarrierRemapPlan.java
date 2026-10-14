package mmm.fluid;

import mmm.chemistry.ChemicalMixtureRecord;
import mmm.compute.CpuWorkBatch;
import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.List;
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
	final CellRemapMarkerColumns markers;
	final SignedDyadic energyRoundoff;
	final boolean[] changedSpeciesCells;
	final CellRemapMoments.Audit momentAudit;
	final GasTopologyCoordinator.GasTopologyPlan gasTopology;
	final boolean commitGranularCollision;
	private final FluidDomain domain;
	private final ArrayList<Group>[] input, output;
	private final int[] outgoingHead, outgoingNext;
	private final boolean[] remappedCells;
	private final ArrayList<Marker> retainedMarkers = new ArrayList<>();
	private final PhaseVolumeTransportGeometry phaseGeometry;
	private final float dt;
	private final boolean geometryRemap;
	private final CellRemapPorts ports;
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
		commitGranularCollision = !geometryRemap && dt > 0
				&& domain.granularState != null && domain.granularState.parcels.count() > 0;
		changedSpeciesCells = new boolean[domain.cellCount];
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
		if(domain.chemicalParcels!=null)for(var groups:input)if(groups!=null)for(var group:groups)group.species.record();
		phaseGeometry = phaseGeometry();
		for (int f = flux.flux.length - 1; f >= 0; f--) if (flux.exactFlux[f].signum() > 0) {
			int c = flux.donor(f); outgoingNext[f] = outgoingHead[c]; outgoingHead[c] = f;
		}
		ports = preparePorts(flux, ballistic);
		if (ports != null) for (var groups : input) if (groups != null) for (var group : groups)
			if (!geometryRemap && remappedCells[group.cell] && !ports.changed(group.cell, group.support)) {
				group.retained = true;
				for (int p : group.original) retainedMarkers.add(originalMarker(p, group.cell));
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
				}
			});
		}
		for(var groups:output) if(groups!=null) for(var group:groups) completeArrivalShape(group,flux);
		assignShareInventories(flux);

		int sampledCount = 0;
		for (int cell = 0; cell < domain.cellCount; cell++) if (output[cell] != null) {
			SignedDyadic occupied = ballistic.reserved[cell];
			for (Group group : output[cell]) occupied = occupied.add(group.volume);
			SignedDyadic capacity = flux.finalCapacity(cell);
			if (capacity.signum() <= 0 || occupied.compareTo(capacity) > 0)
				throw new IllegalStateException("Liquid quadrature exceeds its final open capacity");
			for (Group group : output[cell]) {
				group.maximumTranslation = Double.isFinite(group.density)
						? .5 * capacity.subtract(group.volume).toDouble() / capacity.toDouble() : .5;
				if (ports != null) {
					SignedDyadic componentVolume = ports.reserved(cell, group.support);
					for (var other : output[cell]) if (other.support == group.support) componentVolume = componentVolume.add(other.volume);
					if (input[cell] != null) for (var other : input[cell]) if (other.support == group.support && other.retained)
						componentVolume = componentVolume.add(other.volume);
					SignedDyadic componentCapacity = ports.capacity(cell, group.support);
					if (componentCapacity.signum() <= 0 || componentVolume.compareTo(componentCapacity) > 0)
						throw new IllegalStateException("Liquid quadrature exceeds its connected micro capacity");
					group.maximumTranslation = Double.isFinite(group.density)
							? .5 * componentCapacity.subtract(group.volume).toDouble() / componentCapacity.toDouble() : .5;
				}
				group.sampleStart = sampledCount;
				group.sampleVolumes = quadratureVolumes(group);
				sampledCount = Math.addExact(sampledCount, group.sampleVolumes.size());
			}
		}
		if ((long) sampledCount + retainedMarkers.size() + ballistic.markers.size() > domain.maximumParticleCount)
			throw new IllegalStateException("Cell carrier remap exceeds the marker budget");
		markers = new CellRemapMarkerColumns(sampledCount + retainedMarkers.size() + ballistic.markers.size());
		CpuWorkBatch.run(domain.cellCount, (from, until) -> {
			for (int cell = from; cell < until; cell++) {
				if (output[cell] != null) for (Group group : output[cell]) sample(group);
			}
		});
		markers.copyFrom(retainedMarkers, sampledCount);
		markers.copyFrom(ballistic.markers, sampledCount + retainedMarkers.size());
		energyRoundoff = validateInventories(flux, ballistic);
		momentAudit = momentAudit();
		long[] occupied = geometryRemap || domain.granularState == null || domain.granularState.parcels.count() == 0
				? (domain.granularCoupling == null ? new long[domain.cellCount] : domain.granularCoupling.copySolidSvq())
				: domain.granularNextCoupling.copySolidSvq();
		gasTopology = domain.gasState.frame == null ? null : domain.gasTopologyCoordinator.planAfterLiquidEdits(
				PhaseLiquidGeometryEdit.afterCellRemap(domain, markers), occupied, !geometryRemap && dt > 0);
	}

	/** A cell without any shared transfer keeps its existing spatial integration rule. */
	private void retainUnchangedCells() {
		for (int p = 0; p < domain.particles.count(); p++) {
			int cell = domain.particleCellIndex(p);
			if (remappedCells[cell] || !domain.gridToParticleStage.advectionCells()[cell]) continue;
			retainedMarkers.add(originalMarker(p, cell));
		}
	}
	private Marker originalMarker(int p, int cell) {
		double h = domain.config.cellSize(); var chemical = domain.chemicalParcels;
		return new Marker(cell, domain.particles.materialHandle(p), chemical != null && chemical.indicator(p),
					domain.particles.volume(p), domain.particles.mass(p),
					domain.particles.positionX(p) / h - cell % domain.sizeX - 0.5,
					domain.particles.positionY(p) / h - cell / domain.sizeX % domain.sizeY - 0.5,
					domain.particles.positionZ(p) / h - cell / (domain.sizeX * domain.sizeY) - 0.5,
					domain.particles.velocityX(p), domain.particles.velocityY(p), domain.particles.velocityZ(p),
					chemical == null ? null : chemical.record(p),
					domain.thermalState == null ? 0 : domain.thermalState.liquidInternalEnergy(p));
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
					Group group = find(input, cell, material, indicator, mixture, CellRemapMicroTopology.markerSupport(domain, p));
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
						group.energyRounding += Math.ulp(energy);
					}
				}
				ArrayList<Group> groups = input[cell];
				if (groups != null) for (Group group : groups) {
					if (groups.size() == 1) group.volume = domain.markerGrid.exactCellVolumeInventory[group.cell];
					else for (int p : group.original) group.volume = group.volume.add(SignedDyadic.of(domain.particles.volume(p)));
					group.mass = CellRemapMassAllocation.inventory(domain, group.original);
					group.density = CellRemapMassAllocation.density(domain, group.original, group.volume, group.mass);
					if (domain.thermalState != null) {
						var fitting = FITTING.get(); fitting.ensureCapacity(group.original.size());
						for (int p = 0; p < group.original.size(); p++)
						fitting.values[0][p] = domain.thermalState.liquidInternalEnergy(group.original.get(p));
					group.energy = SignedDyadic.sum(fitting.values[0], group.original.size());
					}
					fitTemperatureField(group, dt);
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
		if (ports == null) {
			distribute(cell, flux, input[cell], transfers(cell, input[cell].getFirst().support, flux)); return;
		}
		for (int support : CellRemapMicroTopology.components(domain.geometry.cutCells.mask(cell))) {
			var groups = new ArrayList<Group>();
			for (var group : input[cell]) if (group.support == support && !group.retained) groups.add(group);
			if (!groups.isEmpty()) distribute(cell, flux, groups, transfers(cell, support, flux));
		}
	}
	private ArrayList<Transfer> transfers(int cell, int support, ConservativeLiquidFlux flux) {
		return transfers(cell, support, flux, ports);
	}
	private ArrayList<Transfer> transfers(int cell, int support, ConservativeLiquidFlux flux, CellRemapPorts selectedPorts) {
		var result = new ArrayList<Transfer>(6);
		if (selectedPorts != null) {
			for (var port : selectedPorts.outgoing(cell, support)) if (port.volume.signum() > 0)
				result.add(new Transfer(port.face, selectedPorts.cellOf(port.receiver), port.receiverSupport, port.volume));
		} else for (int face = outgoingHead[cell]; face >= 0; face = outgoingNext[face])
			result.add(new Transfer(face, flux.receiver(face), CellRemapMicroTopology.components(domain.geometry.cutCells.mask(flux.receiver(face)))[0], flux.exactFlux[face]));
		return result;
	}
	private CellRemapPorts preparePorts(ConservativeLiquidFlux flux, BallisticLiquidRemap ballistic) {
		CellRemapPorts selected = CellRemapMicroTopology.needed(domain) ? newPorts(flux, ballistic) : null;
		if (domain.chemicalParcels == null) return selected;
		var candidates = new ArrayList<ArrayList<Group>>();
		for (int cell = 0; cell < domain.cellCount; cell++) if (remappedCells[cell] && input[cell] != null) {
			boolean lowCell = false;
			for (var group : input[cell]) lowCell |= group.species.totalQuanta() < CellRemapChemicalSupport.MAX_EXPANSION * CellRemapChemicalSupport.MAX_COMPONENT_SHARES;
			if (!lowCell) continue;
			for (int support : CellRemapMicroTopology.components(domain.geometry.cutCells.mask(cell))) {
				var component = new ArrayList<Group>(); boolean low = false;
				for (var group : input[cell]) if (group.support == support) {
					component.add(group); low |= group.species.totalQuanta() < CellRemapChemicalSupport.MAX_EXPANSION * CellRemapChemicalSupport.MAX_COMPONENT_SHARES;
				}
				if (low) candidates.add(component);
			}
		}
		for (;;) {
			boolean complete = true;
			for (var component : candidates) {
				var first = component.getFirst();
				if (selected != null) selected.chemicalWork(1L + component.size() * 8L * CellRemapChemicalSupport.MAX_COMPONENT_SHARES);
				var pieces = volumePieces(first.cell, flux, component, transfers(first.cell, first.support, flux, selected));
				for (int g = 0; g < component.size(); g++) if (!CellRemapChemicalSupport.fits(component.get(g).species.totalQuanta(), pieces[g])) {
					if (selected == null) selected = newPorts(flux, ballistic);
					else selected.reduceChemical(first.cell, first.support, pieces[g][pieces[g].length - 1], component.size() == 1);
					complete = false; break;
				}
				if (!complete) break;
			}
			if (complete) return selected;
		}
	}
	private CellRemapPorts newPorts(ConservativeLiquidFlux flux, BallisticLiquidRemap ballistic) {
		return new CellRemapPorts(domain, flux, ballistic, geometryRemap, domain.chemicalParcels == null ? null : (cell, support) -> {
			int shares = CellRemapChemicalSupport.MAX_COMPONENT_SHARES;
			if (input[cell] != null) for (var group : input[cell]) if (group.support == support)
				shares = (int) Math.min(shares, group.species.totalQuanta());
			return shares;
		});
	}
	private void distribute(int cell, ConservativeLiquidFlux flux, ArrayList<Group> groups, ArrayList<Transfer> transfers) {
		int shares = transfers.size() + 1;
		int[] destinations = new int[shares], supports = new int[shares], faces = new int[shares];
		int share = 0;
		for (var transfer : transfers) {
			destinations[share] = transfer.destination; supports[share] = transfer.support; faces[share++] = transfer.face;
		}
		destinations[share] = cell; supports[share] = groups.getFirst().support; faces[share] = -1;
		SignedDyadic[][] pieces = volumePieces(cell, flux, groups, transfers);
		for (int g = 0; g < groups.size(); g++) {
			Group source = groups.get(g);
			prepareMoments(source);
			source.shares = new ArrayList<>(shares);
			var preferences = geometryRemap
					? CellRemapMicroPreferences.inventory(domain, source.original, shares - 1)
					: CellRemapMicroPreferences.create(domain, flux, source.original, source.support, faces, supports);
			CellRemapTransportMoments.Result transport;
			try { transport = new CellRemapTransportMoments(domain, source.cell, source.original).allocate(pieces[g], preferences); }
			catch (CellRemapTransportMoments.AllocationFailure infeasible) { throw new MechanicalTimeRefinement(2, infeasible.getMessage()); }
			double[] masses = transport.mass();
			for (int d = 0; d < transfers.size(); d++) if (pieces[g][d].signum() > 0) source.movingAxes |= 1 << flux.axis[faces[d]];
			for (int d = 0; d < shares; d++) {
				if (pieces[g][d].signum() == 0) continue;
				var spatial = new CellRemapSpatialMoments();
				for (int p = 0; p < transport.ordinals().length; p++)
					spatial.add(domain, transport.ordinals()[p], transport.markerWeights()[d][p]);
				double[] facePoint = faces[d] < 0 ? null : faceMoments(flux, faces[d], spatial,
						masses[d], pieces[g][d].toDouble(), transport.momentum()[d], transport.volumeMoment()[d], transport.volumeMotion()[d]);
				spatial.advancePositions(.5 * dt / domain.config.cellSize());
				if (facePoint != null) {
					subtractFaceMoment(source, masses[d], pieces[g][d].toDouble(), facePoint);
					spatial.projectPositionAxis(flux.axis[faces[d]]);
					spatial.spreadFaceArrival(flux.axis[faces[d]], dt / domain.config.cellSize());
					spatial.setCentroid(Arrays.copyOf(facePoint, 3));
				}
				source.shares.add(new Share(source, destinations[d], supports[d], faces[d], pieces[g][d], masses[d], transport.kinetic()[d],
						transport.momentum()[d], facePoint, spatial, transport.volumeMotion()[d]));
			}
			if (pieces[g][shares - 1].signum() == 0) source.discardedMoment = true;
		}
	}
	private SignedDyadic[][] volumePieces(int cell, ConservativeLiquidFlux flux, ArrayList<Group> groups, ArrayList<Transfer> transfers) {
		int shares = transfers.size() + 1; SignedDyadic[] amounts = new SignedDyadic[shares];
		int[] faces = new int[shares]; SignedDyadic total = SignedDyadic.ZERO, sent = SignedDyadic.ZERO;
		for (var group : groups) total = total.add(group.volume);
		for (int d = 0; d < transfers.size(); d++) {
			amounts[d] = transfers.get(d).volume; faces[d] = transfers.get(d).face; sent = sent.add(amounts[d]);
		}
		amounts[shares - 1] = total.subtract(sent); faces[shares - 1] = -1;
		SignedDyadic[][] pieces = new SignedDyadic[groups.size()][shares];
		if (groups.size() == 1) pieces[0] = amounts;
		else {
			SignedDyadic[] weights = new SignedDyadic[groups.size()];
			for (int g = 0; g < groups.size(); g++) weights[g] = groups.get(g).volume;
			for (int d = 0; d < shares; d++) {
				SignedDyadic[] allocation;
				if (phaseGeometry == null || faces[d] < 0) allocation = ExactVolumeAllocation.allocate(amounts[d], weights);
				else {
					int face = faces[d];
					double[] preference = new double[groups.size()];
					for (int g = 0; g < groups.size(); g++) {
						int phase = phase(groups.get(g).material);
						var moments = new double[4];
						flux.sweptPhaseMoments(phaseGeometry, phase, face, groups.get(g).support, transfers.get(d).support, moments);
						preference[g] = moments[0] * weights[g].toDouble() / phaseGeometry.volume(phase, cell);
					}
					double representation = flux.representationBlend(face);
					if (representation > 0) {
						double preferred = 0, inventory = 0;
						for (int g = 0; g < groups.size(); g++) { preferred += preference[g]; inventory += weights[g].toDouble(); }
						for (int g = 0; g < groups.size(); g++) {
							double uniform = weights[g].toDouble() / inventory;
							preference[g] = (1 - representation) * (preferred > 0 ? preference[g] / preferred : uniform)
									+ representation * uniform;
						}
					}
					allocation = ExactVolumeAllocation.allocateCapped(amounts[d], weights, preference);
				}
				for (int g = 0; g < groups.size(); g++) { pieces[g][d] = allocation[g]; weights[g] = weights[g].subtract(allocation[g]); }
			}
		}
		return pieces;
	}

	/** Receiver reductions retain the donor and share order independently of worker scheduling. */
	private void mergeShares(ArrayList<Share> shares) {
		for (Share share : shares) {
			Group source = share.source;
			Group target = find(output, share.destination, source.material, source.indicator,
					domain.chemicalParcels==null ? null : source.species.record(), share.support);
			share.target=target;
			target.movingAxes |= source.movingAxes;
			target.minimumTemperature = Math.min(target.minimumTemperature, source.minimumTemperature);
			target.maximumTemperature = Math.max(target.maximumTemperature, source.maximumTemperature);
			mergeMass(target, source, share.volume, share.mass); target.kinetic += share.kinetic;
			if (domain.chemicalParcels != null) target.species.includeHandles(source.species.record());
			for (int a = 0; a < 3; a++) target.momentum[a] += share.momentum[a];
			addMoments(target, share);
			if (share.face >= 0 && !geometryRemap) {
				if (target.arrivals == null) target.arrivals = new ArrayList<>();
				target.arrivals.add(share);
			}
			addTemperatureField(target, source, share.mass);
		}
	}

	/** Final merged moments determine the integer inventory needed by the published carriers. */
	private void assignShareInventories(ConservativeLiquidFlux flux) {
		if (domain.chemicalParcels == null && domain.thermalState == null) return;
		var sources = new ArrayList<Group>();
		for (var groups : input) if (groups != null) for (var group : groups) if (group.shares != null) sources.add(group);
		if (domain.chemicalParcels != null) {
			var targets = new ArrayList<Group>();
			for (var groups : output) if (groups != null) for (var group : groups) {
				group.speciesTarget = targets.size(); targets.add(group);
			}
			int[] required = new int[targets.size()];
			for (int i = 0; i < required.length; i++) {
				var group = targets.get(i);
				required[i] = CellRemapChemicalSupport.minimumCarriers(group.volume, minimumQuadratureCount(group));
			}
			var definitions = new ArrayList<CellRemapSpeciesAllocation.Source>();
			for (var source : sources) {
				var weights = new SignedDyadic[source.shares.size()]; var destinations = new int[weights.length];
				for (int i = 0; i < weights.length; i++) {
					var share = source.shares.get(i); weights[i] = share.volume; destinations[i] = share.target.speciesTarget;
				}
				definitions.add(new CellRemapSpeciesAllocation.Source(source.species, weights, destinations));
			}
			ChemicalMixtureRecord[][] allocated;
			try { allocated = CellRemapSpeciesAllocation.allocate(definitions, required); }
			catch (CellRemapSpeciesAllocation.Unavailable unavailable) {
				var group = targets.get(unavailable.target);
				throw new CellRemapSpeciesAllocation.Unavailable("Liquid remap species allocation at cell=" + group.cell
						+ ", support=" + group.support + ": " + unavailable.getMessage(), unavailable);
			}
			for (var target : targets) target.species.clear();
			for (int source = 0; source < sources.size(); source++) for (int i = 0; i < sources.get(source).shares.size(); i++) {
				var share = sources.get(source).shares.get(i); share.species = allocated[source][i];
				if (share.species != null) share.target.species.add(share.species);
			}
		}
		if (domain.thermalState == null) return;
		for (var source : sources) {
			int n = source.shares.size(); var weights = new SignedDyadic[n]; var masses = new double[n];
			var species = new ChemicalMixtureRecord[n]; var faces = new int[n];
			for (int i = 0; i < n; i++) {
				var share = source.shares.get(i);
				weights[i] = share.volume; masses[i] = share.mass; species[i] = share.species; faces[i] = share.face;
			}
			var energies = energyParts(source, weights, masses, species, faces, flux);
			int pivot = CellRemapMassAllocation.largest(weights);
			for (int i = 0; i < n; i++) {
				var share = source.shares.get(i); share.energy = energies[i];
				share.rounding = source.energyRounding * (i == pivot ? 1 : weights[i].toDouble() / source.volume.toDouble()) + Math.ulp(energies[i].toDouble());
				share.target.energy = share.target.energy.add(share.energy); share.target.energyRounding += share.rounding;
			}
		}
		for (var groups : output) if (groups != null) for (var group : groups) group.temperature = temperature(group);
	}
	/** Complete only unresolved arriving shapes; the independent position spread preserves velocity and cross moments. */
	private void completeArrivalShape(Group group, ConservativeLiquidFlux flux) {
		if (group.arrivals == null || group.spatial.spatialRank() == 3) return;
		var span = group.spatial.spatialSpan();
		for (Share share : group.arrivals) {
			var swept = flux.sweptSecondMoments(domain, share.face, share.source.support, share.support);
			var added = group.spatial.completeFaceTangents(flux.axis[share.face], swept, share.volume.toDouble(), share.mass / group.mass, span);
			for (int a = 0; a < 3; a++) group.shapeApproximation[a] += group.mass * added[a];
		}
	}

	private static void mergeMass(Group target, Group source, SignedDyadic volume, double mass) {
		if (target.volume.signum() == 0) target.density = source.density;
		else if (target.density != source.density) target.density = Double.NaN;
		if (CellRemapMassAllocation.exactMass(target.density, volume) != mass) target.density = Double.NaN;
		target.volume = target.volume.add(volume); target.mass += mass;
		double exact = CellRemapMassAllocation.exactMass(target.density, target.volume);
		if (Double.isFinite(exact)) target.mass = exact;
		else target.density = Double.NaN;
	}

	private void addTemperatureField(Group target, Group source, double weight) {
		if (domain.thermalState == null) return;
		if (target.temperatureField == null) target.temperatureField = new double[8];
		double[] coefficients = source.temperatureField;
		if (target.cell != source.cell) coefficients = CellPolynomialField.translate(coefficients,
				target.cell % domain.sizeX - source.cell % domain.sizeX,
				target.cell / domain.sizeX % domain.sizeY - source.cell / domain.sizeX % domain.sizeY,
				target.cell / (domain.sizeX * domain.sizeY) - source.cell / (domain.sizeX * domain.sizeY));
		for (int b = 0; b < 8; b++) target.temperatureField[b] += weight * coefficients[b];
	}

	private void prepareMoments(Group source) {
		source.lowMassX = source.massMoment[0] / source.mass; source.lowMassY = source.massMoment[1] / source.mass;
		source.lowMassZ = source.massMoment[2] / source.mass;
		double volume = source.volume.toDouble();
		source.lowVolumeX = source.volumeMoment[0] / volume; source.lowVolumeY = source.volumeMoment[1] / volume;
		source.lowVolumeZ = source.volumeMoment[2] / volume;
		for (int a = 0; a < 3; a++) {
			source.massMoment[a] += 0.5 * dt / domain.config.cellSize() * source.momentum[a];
			source.volumeMoment[a] += 0.5 * dt / domain.config.cellSize() * source.volumeMotion[a];
		}
	}

	/** Tangential transport uses this share's common-source position and velocity moments. */
	private double[] faceMoments(ConservativeLiquidFlux flux, int face, CellRemapSpatialMoments spatial,
	                            double mass, double volume, double[] momentum, double[] volumeMoment, double[] volumeMotion) {
		double[] point = new double[6], centroid = spatial.centroid();
		double halfDt = .5 * dt / domain.config.cellSize();
		for (int a = 0; a < 3; a++) {
			if (a == flux.axis[face]) { point[a] = point[a + 3] = Math.copySign(.5, flux.velocity[face]); continue; }
			point[a] = centroid[a] + halfDt * momentum[a] / mass;
			point[a + 3] = volumeMoment[a] / volume + halfDt * volumeMotion[a] / volume;
		}
		return point;
	}
	private static void subtractFaceMoment(Group source, double mass, double volume, double[] facePoint) {
		for (int a = 0; a < 3; a++) { source.massMoment[a] -= mass * facePoint[a]; source.volumeMoment[a] -= volume * facePoint[a + 3]; }
	}

	private void addMoments(Group target, Share share) {
		Group source = share.source;
		double mass = share.mass, volume = share.volume.toDouble();
		double[] facePoint = share.facePoint, centroid = new double[3];
		for (int a = 0; a < 3; a++) {
			if (facePoint == null) {
				target.massMoment[a] += source.massMoment[a]; target.volumeMoment[a] += source.volumeMoment[a];
				centroid[a] = source.massMoment[a] / mass;
			} else {
				int stride = a == 0 ? 1 : a == 1 ? domain.sizeX : domain.sizeX * domain.sizeY;
				double shift = (a == 0 ? target.cell % domain.sizeX - source.cell % domain.sizeX
						: a == 1 ? target.cell / stride % domain.sizeY - source.cell / stride % domain.sizeY : target.cell / stride - source.cell / stride);
				target.massMoment[a] += mass * (facePoint[a] - shift); target.volumeMoment[a] += volume * (facePoint[a + 3] - shift);
				centroid[a] = facePoint[a] - shift;
			}
			target.volumeMotion[a] += share.volumeMotion[a];
		}
		share.spatial.setCentroid(centroid);
		target.spatial.add(share.spatial);
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
		var fitting = FITTING.get(); fitting.ensureCapacity(volumes.length);
		double[] rawEnergy = fitting.values[0]; Arrays.fill(rawEnergy, 0, volumes.length, 0);
		for (int d = 0; d <= last; d++) {
			if (volumes[d].signum() == 0) continue;
			double mass = masses[d];
			ChemicalMixtureRecord composition = species[d];
			var caloric = domain.thermalState.liquid().caloricInventory(source.material, mass, composition,
					domain.chemicalParcels == null ? null : domain.chemicalParcels.registry());
			baseline[d] = SignedDyadic.of(rawEnergy[d] = caloric.energy(meanTemperature));
			if (flat) continue;
			lower[d] = caloric.energy(source.minimumTemperature);
			upper[d] = caloric.energy(source.maximumTemperature);
			if (d == last) correction[d] = -correctionSum;
			else if (!geometryRemap && faces[d] >= 0) {
				int face = faces[d]; double[] point = new double[3];
				point[flux.axis[face]] = Math.copySign(0.5 - 0.5 * Math.abs(flux.velocity[face]) * dt / domain.config.cellSize(), flux.velocity[face]);
				double t = CellPolynomialField.evaluate(source.transportTemperature, point[0], point[1], point[2]);
				t = Math.max(source.minimumTemperature, Math.min(source.maximumTemperature, t));
				correction[d] = (caloric.energy(t) - baseline[d].toDouble()) * (1 - flux.representationBlend(face));
				correctionSum += correction[d];
			}
		}
		SignedDyadic remainingEnergy = source.energy.subtract(SignedDyadic.sum(rawEnergy, volumes.length));
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

	private void fitTemperatureField(Group group, float dt) {
		if (domain.thermalState == null) return;
		if (!remappedCells[group.cell]) {
			// Retained carriers need their thermal range for adjacent transfers, but no reconstructed field.
			for (int p : group.original) {
				double temperature = domain.thermalState.temperature(p, domain.thermalMarkerContext());
				group.localMinimum = Math.min(group.localMinimum, temperature);
				group.localMaximum = Math.max(group.localMaximum, temperature);
			}
			return;
		}
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
			values[0][local] = domain.thermalState.temperature(p, domain.thermalMarkerContext());
			group.localMinimum = Math.min(group.localMinimum, values[0][local]);
			group.localMaximum = Math.max(group.localMaximum, values[0][local]);
		}
		group.transportTemperature = CellPolynomialField.fit(positions, weights, values, count)[0];
		group.temperatureField = CellPolynomialField.deform(group.transportTemperature, domain, group.cell, dt);
	}

	private ArrayList<Float> quadratureVolumes(Group group) {
		double amount = group.volume.toDouble();
		int requested = ConservativeMarkerSampling.targetMarkers((float) amount);
		int count = requested <= 1 ? 1 : requested <= 2 ? 2 : requested <= 4 ? 4 : 8;
		if (output[group.cell].size() > 1) count = Integer.highestOneBit(Math.min(8,
				Math.max(1, (int) Math.floor(amount / ConservativeMarkerSampling.NOMINAL_MARKER_VOLUME))));
		int mask = domain.geometry.cutCells.mask(group.cell);
		int requiredAxes = mask == 0 && domain.thermalState != null
				? CellRemapQuadrature.requiredAxes(new double[][]{group.temperatureField}) : 0;
		int minimumCount = minimumQuadratureCount(group);
		// The complete tensor rule preserves the original full-dimensional carrier geometry.
		if (mask == 0) {
			count = minimumCount == 1 ? 1 : Math.max(count, minimumCount <= 2 ? 2 : minimumCount <= 4 ? 4 : 8);
			if (group.spatial.spatialRank() == 3) count = 8;
		} else count = Math.max(Integer.bitCount(group.support), minimumCount);
		var volumes = domain.chemicalParcels==null ? LiquidCellQuadrature.volumes(group.volume,count)
				: CellRemapChemicalSupport.quadrature(group.volume,count,minimumCount,group.species.totalQuanta());
		if(domain.chemicalParcels!=null)count=volumes.size();
		group.quadratureCount = count;
		int spatialAxes = group.spatial.spatialAxes();
		boolean tensor=(count&(count-1))==0 && count<=8;
		if (mask == 0 && tensor && Integer.bitCount(requiredAxes | spatialAxes) <= Integer.numberOfTrailingZeros(count)) requiredAxes |= spatialAxes;
		group.quadratureAxes = mask == 0 && tensor ? CellRemapQuadrature.samplingAxes(count, requiredAxes) : -1;
		return volumes;
	}
	private int minimumQuadratureCount(Group group) {
		int axes=domain.geometry.cutCells.mask(group.cell)==0 && domain.thermalState!=null ? CellRemapQuadrature.requiredAxes(new double[][]{group.temperatureField}) : 0;
		return Math.max(group.spatial.minimumCount(),CellRemapQuadrature.minimumCount(axes));
	}

	private void sample(Group group) {
		int count = group.quadratureCount, mask = ~group.support & 255;
		ArrayList<Float> volumes = group.sampleVolumes;
		ArrayList<double[]> offsets = new ArrayList<>();
		for (int slot = 0; slot < volumes.size(); slot++) offsets.add(slot < count
				? mask == 0 ? group.quadratureAxes>=0 ? CellRemapQuadrature.offset(slot, group.quadratureAxes) : offset(slot%8,8) : openOffset(mask, slot % Integer.bitCount(group.support))
				: mask == 0 ? offset(0, 1) : openOffset(mask, 0));
		SignedDyadic[] weights = new SignedDyadic[volumes.size()];
		for (int p = 0; p < weights.length; p++) weights[p] = SignedDyadic.of(volumes.get(p));
		double[] masses = CellRemapMassAllocation.allocate(group.mass, group.volume, weights, group.density);
		for (int a = 0; a < 3; a++) {
			group.massMoment[a] += 0.5 * dt / domain.config.cellSize() * group.momentum[a];
			group.volumeMoment[a] += 0.5 * dt / domain.config.cellSize() * group.volumeMotion[a];
		}
		group.spatial.advancePositions(.5 * dt / domain.config.cellSize());
		double[] centroid = new double[3];
		for (int a = 0; a < 3; a++) centroid[a] = group.massMoment[a] / group.mass;
		group.spatial.setCentroid(centroid);
		var fitted = CellRemapSampling.fit(domain, group.cell, group.support, masses, group.spatial,
				offsets.toArray(double[][]::new), group.massMoment, group.storage,
				group.movingAxes, group.maximumTranslation, group.projection);
		offsets = new ArrayList<>(Arrays.asList(fitted.positions()));
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
		double[] temperatures = domain.thermalState == null ? null : new double[volumes.size()];
		double temperatureMean = domain.thermalState == null ? 0 : evaluateTemperature(group, offsets, volumes, temperatures);
		double temperatureScale = domain.thermalState == null ? 1 : temperatureScale(group, temperatureMean, temperatures);
		int start = group.sampleStart;
		var caloric = domain.thermalState == null ? null
				: new mmm.thermal.LiquidEnergyColumn.CaloricInventory[volumes.size()];
		for (int p = 0; p < volumes.size(); p++) {
			double m = masses[p];
			if (!(Double.isFinite(m) && m > 0)) throw new IllegalStateException("Carrier remap produced invalid mass");
			ChemicalMixtureRecord mixture = domain.chemicalParcels == null ? null : mixture(species[p], m);
			double[] delta = offsets.get(p);
			double vx = fitted.velocities()[p][0], vy = fitted.velocities()[p][1], vz = fitted.velocities()[p][2];
			double energy = 0;
			if (domain.thermalState != null) {
				double t = group.temperature + temperatureScale * (temperatures[p] - temperatureMean);
				t = Math.max(group.minimumTemperature, Math.min(group.maximumTemperature, t));
				for (int earlier = 0; earlier < p; earlier++) {
					int previous = start + earlier;
					if (Double.doubleToRawLongBits(markers.mass[previous]) == Double.doubleToRawLongBits(m)
							&& java.util.Objects.equals(markers.mixture[previous], mixture)) { caloric[p] = caloric[earlier]; break; }
				}
				if (caloric[p] == null) caloric[p] = domain.thermalState.liquid().caloricInventory(group.material, m, mixture,
						domain.chemicalParcels == null ? null : domain.chemicalParcels.registry());
				energy = caloric[p].energy(t);
				// Completed temperature samples become the private energy-reduction workspace.
				temperatures[p] = energy;
			}
			markers.set(start + p, group.cell, group.material, group.indicator, volumes.get(p), m,
					delta[0], delta[1], delta[2], vx, vy, vz, mixture, energy);
		}
		if (domain.thermalState != null) {
			SignedDyadic energyRemaining = group.energy.subtract(SignedDyadic.sum(temperatures, volumes.size()));
			CellRemapHeatBalance.complete(markers, start, caloric, group.minimumTemperature, group.maximumTemperature, energyRemaining, group.energyRounding);
		}
	}

	private double evaluateTemperature(Group group, ArrayList<double[]> offsets, ArrayList<Float> volumes, double[] temperatures) {
		double amount = group.volume.toDouble(), mean = 0;
		for (int p = 0; p < offsets.size(); p++) {
			double[] delta = offsets.get(p);
			temperatures[p] = CellPolynomialField.evaluate(group.temperatureField, delta[0], delta[1], delta[2]) / group.mass;
			mean += volumes.get(p) / amount * temperatures[p];
		}
		boolean constant = true;
		for (int p = 1; p < offsets.size(); p++) if (temperatures[p] != temperatures[0]) constant = false;
		return constant ? temperatures[0] : mean;
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
		return caloric(group).temperature(group.energy);
	}

	private mmm.thermal.LiquidEnergyColumn.CaloricInventory caloric(Group group) {
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
		for (int p = 0; p < markers.size(); p++) if (markers.mixture[p] != null) add(after, markers.mixture[p]);
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
		double[] projection = new double[3], storage = new double[3], volume = new double[3], unadvanced = new double[3], signed = new double[3];
		double maximum = 0, h = domain.config.cellSize(); var shape = new double[3]; int projected = 0, reconstructed = 0;
		for (ArrayList<Group> groups : output) if (groups != null) for (Group group : groups) {
			reconstructed++;
			double worst = 0;
			for (int a = 0; a < 3; a++) {
				shape[a] += domain.unitScale.kilograms(h * h * group.shapeApproximation[a]);
				projection[a] += domain.unitScale.kilograms(h * Math.abs(group.projection[a])); storage[a] += domain.unitScale.kilograms(h * Math.abs(group.storage[a]));
				signed[a] += domain.unitScale.signedKilograms(h * (group.projection[a] + group.storage[a]));
				volume[a] += h * Math.abs(group.volumeResidual[a]); worst = Math.max(worst, h * Math.abs(group.projection[a]) / group.mass);
			}
			maximum = Math.max(maximum, worst); if (worst > 0) projected++;
		}
		for (ArrayList<Group> groups : input) if (groups != null) for (Group group : groups) {
			double worst = 0;
			for (int a = 0; a < 3; a++) {
				if (group.discardedMoment) {
					projection[a] += domain.unitScale.kilograms(h * Math.abs(group.massMoment[a])); volume[a] += h * Math.abs(group.volumeMoment[a]);
					signed[a] -= domain.unitScale.signedKilograms(h * group.massMoment[a]);
					worst = Math.max(worst, h * Math.abs(group.massMoment[a]) / group.mass);
				}
				if (!remappedCells[group.cell] || group.retained) {
					unadvanced[a] += domain.unitScale.kilograms(Math.abs(dt * group.momentum[a]));
					signed[a] -= domain.unitScale.signedKilograms(dt * group.momentum[a]);
				}
			}
			maximum = Math.max(maximum, worst); if (worst > 0) projected++;
		}
		return new CellRemapMoments.Audit(projection, storage, volume, maximum, projected, unadvanced, signed, reconstructed, shape);
	}

	private static Map<Integer, Long> cellSpecies(ArrayList<Group> groups) {
		if (groups == null) return Map.of();
		var result = new TreeMap<Integer, Long>();
		for (Group group : groups) add(result, group.species);
		return result;
	}

	private static Group find(ArrayList<Group>[] field, int cell, int material, boolean indicator, ChemicalMixtureRecord mixture, int support) {
		if (field[cell] == null) field[cell] = new ArrayList<>(1);
		for (Group group : field[cell]) if (group.material == material && group.indicator == indicator && group.support == support) {
			if (group.species.compatible(mixture)) return group;
		}
		Group group = new Group(cell, material, indicator, support); field[cell].add(group); return group;
	}

	/** Uses the same bounded species unions as the carrier planner for larger registries. */
	@SuppressWarnings("unchecked")
	static int[] chemicalShares(FluidDomain domain) {
		var groups = (ArrayList<Group>[]) new ArrayList<?>[domain.cellCount];
		for (int p = 0; p < domain.particles.count(); p++) {
			var mixture = domain.chemicalParcels.record(p);
			Group group = find(groups, domain.particleCellIndex(p), domain.particles.materialHandle(p),
					domain.chemicalParcels.indicator(p), mixture, ~domain.geometry.cutCells.mask(domain.particleCellIndex(p)) & 255);
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
		if(species.isEmpty())return new ChemicalMixtureRecord[weights.length];
		int[] targets=new int[weights.length]; for(int i=0;i<targets.length;i++)targets[i]=i;
		return CellRemapSpeciesAllocation.allocate(List.of(new CellRemapSpeciesAllocation.Source(species,weights,targets)),CellRemapChemicalSupport.required(weights))[0];
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
	}
	private record Transfer(int face, int destination, int support, SignedDyadic volume) { }
	private static final class Share {
		final Group source; final int destination,support,face; final SignedDyadic volume; final double mass,kinetic;
		final double[] momentum,facePoint,volumeMotion; final CellRemapSpatialMoments spatial;
		Group target; SignedDyadic energy=SignedDyadic.ZERO; ChemicalMixtureRecord species; double rounding;
		Share(Group source,int destination,int support,int face,SignedDyadic volume,double mass,double kinetic,
				double[] momentum,double[] facePoint,CellRemapSpatialMoments spatial,double[] volumeMotion) {
			this.source=source; this.destination=destination; this.support=support; this.face=face; this.volume=volume;
			this.mass=mass; this.kinetic=kinetic; this.momentum=momentum; this.facePoint=facePoint; this.spatial=spatial; this.volumeMotion=volumeMotion;
		}
	}
	private static final class Group {
		final int cell, material, support; final boolean indicator;
		boolean retained;
		int sampleStart, quadratureCount, quadratureAxes, movingAxes, speciesTarget; ArrayList<Float> sampleVolumes;
		ArrayList<Share> shares, arrivals;
		final ArrayList<Integer> original = new ArrayList<>(); final CellSpeciesInventory species = new CellSpeciesInventory();
		final CellRemapSpatialMoments spatial = new CellRemapSpatialMoments();
		SignedDyadic volume = SignedDyadic.ZERO, energy = SignedDyadic.ZERO;
		double density = Double.NaN;
		mmm.thermal.LiquidEnergyColumn.CaloricInventory caloric;
		double mass, kinetic, temperature, energyRounding, maximumTranslation; double[] transportTemperature, temperatureField; final double[] momentum = new double[3];
		double lowMassX, lowMassY, lowMassZ, lowVolumeX, lowVolumeY, lowVolumeZ;
		final double[] massMoment = new double[3], volumeMoment = new double[3], volumeMotion = new double[3];
		final double[] projection = new double[3], storage = new double[3], volumeResidual = new double[3]; boolean discardedMoment;
		final double[] shapeApproximation = new double[3];
		double localMinimum = Double.POSITIVE_INFINITY, localMaximum = Double.NEGATIVE_INFINITY;
		double minimumTemperature = Double.POSITIVE_INFINITY, maximumTemperature = Double.NEGATIVE_INFINITY;
		Group(int cell, int material, boolean indicator, int support) { this.cell = cell; this.material = material; this.indicator = indicator; this.support = support; }
	}
}
