package com.example.fluid;

import com.example.chemistry.ChemicalMixtureRecord;
import com.example.chemistry.ChemicalRegistry;
import com.example.granular.SolidVolume;
import com.example.thermal.CoherentPhaseStore;
import com.example.thermal.SignedDyadic;
import com.example.thermal.ThermalAuditLedger;
import com.example.thermal.ThermalCellGrid;
import com.example.thermal.ThermalLimits;
import com.example.thermal.ThermalMaterialDefinition;
import com.example.thermal.ThermalPhaseChangeEngine;
import com.example.thermal.ThermalRegistry;
import com.example.thermal.ThermalStateFunction;
import com.example.thermal.ThermalTransport;

import java.util.Arrays;
import java.util.Map;

/** Owns the optional liquid-marker M9 energy column; FlipFluidDomain only supplies carrier/cell orchestration. */
final class FlipThermalState {
	interface MarkerContext {
		int markerCount();
		int cell(int marker);
		int fluidMaterialHandle(int marker);
		double mass(int marker);
		double volume(int marker);
		ChemicalMixtureRecord mixture(int marker);
		ChemicalRegistry chemicalRegistry();
	}

	record Truncation(double newEnergy, double removedEnergy) {
		Truncation {
			if (!Double.isFinite(newEnergy) || !Double.isFinite(removedEnergy)) {
				throw new IllegalArgumentException("Thermal truncation ledger is invalid");
			}
		}
	}

	private final ThermalRegistry registry;
	private final ThermalStateFunction stateFunction;
	private final int[] materialByFluidHandle;
	private final ThermalCellGrid cellGrid;
	private final ThermalTransport transport;
	private final CoherentPhaseStore coherentPhases;
	private final ThermalPhaseChangeEngine phaseChange;
	private final double insertionTemperatureK;
	private double[] internalEnergy;
	private ThermalAuditLedger audit;
	private ThermalTransport.Stats lastTransport = new ThermalTransport.Stats(
			0, 0, 0.0, SignedDyadic.ZERO, SignedDyadic.ZERO);

	FlipThermalState(
			ThermalRegistry registry, FluidMaterialRegistry liquids, double initialTemperatureK,
			int initialCapacity, int sizeX, int sizeY, int sizeZ, float cellSize,
			MarkerContext markers
	) {
		if (registry == null || liquids == null || markers == null) {
			throw new IllegalArgumentException("Thermal binding input is missing");
		}
		if (!Double.isFinite(initialTemperatureK)) throw new IllegalArgumentException("Initial temperature must be finite");
		this.registry = registry;
		this.stateFunction = new ThermalStateFunction(registry);
		this.materialByFluidHandle = new int[liquids.size()];
		for (int handle = 0; handle < liquids.size(); handle++) {
			int thermalHandle = registry.materialHandle(ThermalMaterialDefinition.CarrierKind.LIQUID,
					liquids.material(handle).id());
			ThermalMaterialDefinition material = registry.material(thermalHandle);
			if (initialTemperatureK < material.minimumTemperatureK()
					|| initialTemperatureK > material.maximumTemperatureK()) {
				throw new IllegalArgumentException("Initial temperature is outside " + material.id());
			}
			materialByFluidHandle[handle] = thermalHandle;
		}
		this.cellGrid = new ThermalCellGrid(Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ));
		this.transport = new ThermalTransport(sizeX, sizeY, sizeZ, cellSize, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		this.coherentPhases = new CoherentPhaseStore(
				Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ),
				Math.min(ThermalLimits.MAX_COHERENT_GROUPS_PER_REGION,
						Math.multiplyExact(Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ), 8)));
		this.phaseChange = new ThermalPhaseChangeEngine(registry);
		this.insertionTemperatureK = initialTemperatureK;
		this.internalEnergy = new double[initialCapacity];
		for (int marker = 0; marker < markers.markerCount(); marker++) {
			internalEnergy[marker] = modeledEnergy(marker, markers.mass(marker), markers.mixture(marker),
					initialTemperatureK, markers);
		}
		this.audit = ThermalAuditLedger.initialized(exactEnergy(markers.markerCount()));
	}

	String fingerprint() { return registry.fingerprint(); }
	ThermalAuditLedger audit() { return audit; }
	CoherentPhaseStore coherentPhases() { return coherentPhases; }
	ThermalRegistry registry() { return registry; }
	ThermalPhaseChangeEngine phaseChange() { return phaseChange; }
	double liquidInternalEnergy(int marker) { return internalEnergy[marker]; }
	ThermalTransport.Stats lastTransport() { return lastTransport; }
	double totalEnergy(int count) {
		double total = 0.0;
		for (int marker = 0; marker < count; marker++) total += internalEnergy[marker];
		for (CoherentPhaseStore.Group group : coherentPhases.snapshot()) total += group.internalEnergy();
		return total;
	}
	SignedDyadic exactEnergy(int count) {
		return SignedDyadic.sum(Arrays.copyOf(internalEnergy, count)).add(coherentPhases.exactEnergy());
	}

	void ensureCapacity(int capacity) {
		if (capacity > internalEnergy.length) internalEnergy = Arrays.copyOf(internalEnergy, capacity);
	}

	FlipThermalParticleState capture(int sizeX, int sizeY, int sizeZ, float cellSize, int count) {
		return new FlipThermalParticleState(sizeX, sizeY, sizeZ, cellSize, count, registry.fingerprint(),
				Arrays.copyOf(internalEnergy, count), coherentPhases.snapshot(), audit);
	}

	void restore(FlipThermalParticleState snapshot, MarkerContext markers) {
		if (coherentPhases.groupCount() != 0) throw new IllegalStateException("Thermal restore target is not empty");
		double[] restored = snapshot.copyInternalEnergy();
		ensureCapacity(restored.length);
		System.arraycopy(restored, 0, internalEnergy, 0, restored.length);
		for (CoherentPhaseStore.Group group : snapshot.coherentGroups()) {
			int material = registry.materialHandle(group.phaseId());
			if (registry.material(material).carrierKind() != ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID) {
				throw new IllegalArgumentException("Restored coherent phase has the wrong thermal carrier kind");
			}
			stateFunction.stateForInternalEnergy(material, group.mass(),
					group.composition() == null ? Map.of() : group.composition().stableAmounts(),
					group.internalEnergy());
		}
		coherentPhases.restore(snapshot.coherentGroups());
		for (int marker = 0; marker < markers.markerCount(); marker++) temperature(marker, markers);
		audit = snapshot.audit();
		if (!exactEnergy(markers.markerCount()).equals(audit.expectedInventory())) {
			throw new IllegalArgumentException("Restored thermal audit does not close");
		}
	}

	double temperature(int marker, MarkerContext markers) {
		ThermalMaterialDefinition material = material(marker, markers);
		double sensible = (internalEnergy[marker] - baseEnergy(marker, markers)) / markers.mass(marker);
		return material.temperatureForSensibleSpecificEnergy(sensible);
	}

	void requireReactionState(
			int marker, ChemicalMixtureRecord candidateMixture, double candidateMass, MarkerContext markers
	) {
		ThermalMaterialDefinition material = material(marker, markers);
		int thermalHandle = materialByFluidHandle[markers.fluidMaterialHandle(marker)];
		double base = stateFunction.baseEnergy(
				thermalHandle, candidateMass, candidateMixture, markers.chemicalRegistry());
		double sensible = (internalEnergy[marker] - base) / candidateMass;
		material.temperatureForSensibleSpecificEnergy(sensible);
	}

	double temperatureInCell(int cell, MarkerContext markers) {
		cellGrid.rebuild(carrierAccess(markers));
		return cellGrid.occupied(cell) ? cellGrid.temperature(cell) : Double.NaN;
	}

	void setTemperatureInCell(int cell, double temperatureK, MarkerContext markers) {
		SignedDyadic before = exactEnergy(markers.markerCount());
		cellGrid.rebuild(carrierAccess(markers));
		if (!cellGrid.occupied(cell)) throw new IllegalArgumentException("Cannot set temperature in an empty cell");
		double[] target = cellGrid.copyInternalEnergy();
		target[cell] = modeledCellEnergy(cell, temperatureK, markers);
		cellGrid.setCellInternalEnergies(target);
		audit = audit.withHeatExchange(exactEnergy(markers.markerCount()).subtract(before));
	}

	void resetMarkerTemperature(int marker, double temperatureK, MarkerContext markers, SignedDyadic inventoryBefore) {
		internalEnergy[marker] = modeledEnergy(marker, markers.mass(marker), markers.mixture(marker), temperatureK, markers);
		audit = audit.withHeatExchange(exactEnergy(markers.markerCount()).subtract(inventoryBefore));
	}

	ThermalTransport.Stats step(
			MarkerContext markers, ThermalTransport.FaceArea faceArea, double dtSeconds
	) {
		return step(markers, faceArea, (x, y, z, direction) -> null, dtSeconds);
	}

	ThermalTransport.Stats step(
			MarkerContext markers, ThermalTransport.FaceArea faceArea,
			ThermalTransport.BoundaryFace boundaryFace, double dtSeconds
	) {
		SignedDyadic before = exactEnergy(markers.markerCount());
		cellGrid.rebuild(carrierAccess(markers));
		lastTransport = transport.step(cellGrid, faceArea, boundaryFace, dtSeconds);
		SignedDyadic physicalDelta = exactEnergy(markers.markerCount()).subtract(before);
		audit = audit.withHeatExchange(lastTransport.reservoirExchange())
				.withRoundoff(physicalDelta.subtract(lastTransport.reservoirExchange()));
		return lastTransport;
	}

	void addExternalMarker(int marker, MarkerContext markers) {
		ensureCapacity(marker + 1);
		internalEnergy[marker] = modeledEnergy(marker, markers.mass(marker), markers.mixture(marker),
				insertionTemperatureK, markers);
		audit = audit.withExternalSourceEnergy(SignedDyadic.of(internalEnergy[marker]));
	}

	void removeExternalAndSwap(int index, int last) {
		audit = audit.withExternalSinkEnergy(SignedDyadic.of(internalEnergy[index]));
		if (index != last) internalEnergy[index] = internalEnergy[last];
	}

	void recordExternalSink(SignedDyadic energy) { audit = audit.withExternalSinkEnergy(energy); }
	void recordRoundoff(SignedDyadic delta) { audit = audit.withRoundoff(delta); }

	void addCoherentExternal(CoherentPhaseStore.Group group) {
		int material = registry.materialHandle(group.phaseId());
		if (registry.material(material).carrierKind() != ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID) {
			throw new IllegalArgumentException("Coherent group phase is not a coherent-solid thermal material");
		}
		Map<String, Long> composition = group.composition() == null ? Map.of() : group.composition().stableAmounts();
		stateFunction.stateForInternalEnergy(material, group.mass(), composition, group.internalEnergy());
		coherentPhases.add(group);
		audit = audit.withExternalSourceEnergy(SignedDyadic.of(group.internalEnergy()));
	}

	void addCoherentInternal(CoherentPhaseStore.Group group) { coherentPhases.add(group); }
	CoherentPhaseStore.Group takeCoherentInternal(int cell, String phaseId, long occupiedSvq) {
		return coherentPhases.take(cell, phaseId, occupiedSvq);
	}
	void addInternalMarker(int marker, double energy) {
		ensureCapacity(marker + 1);
		if (!Double.isFinite(energy)) throw new IllegalArgumentException("Internal phase marker energy is not finite");
		internalEnergy[marker] = energy;
	}
	double removeInternalAndSwap(int index, int last) {
		double removed = internalEnergy[index];
		if (index != last) internalEnergy[index] = internalEnergy[last];
		return removed;
	}
	void addLiquidEnergy(int marker, double energy) {
		double result = internalEnergy[marker] + energy;
		if (!Double.isFinite(result)) throw new IllegalArgumentException("Internal phase energy transfer overflow");
		internalEnergy[marker] = result;
	}
	void repartitionCellEnergy(int cell, double targetEnergy, MarkerContext markers, SignedDyadic inventoryBefore) {
		cellGrid.rebuild(carrierAccess(markers));
		double[] target = cellGrid.copyInternalEnergy();
		target[cell] = targetEnergy;
		cellGrid.setCellInternalEnergies(target);
		audit = audit.withRoundoff(exactEnergy(markers.markerCount()).subtract(inventoryBefore));
	}

	Truncation planTruncate(
			int marker, double newMass, ChemicalMixtureRecord keptMixture,
			double previousTemperatureK, MarkerContext markers
	) {
		double previousEnergy = internalEnergy[marker];
		double newEnergy = newMass == 0.0 ? 0.0
				: modeledEnergy(marker, newMass, keptMixture, previousTemperatureK, markers);
		return new Truncation(newEnergy, previousEnergy - newEnergy);
	}

	void commitTruncate(int marker, Truncation plan) {
		if (plan == null) return;
		internalEnergy[marker] = plan.newEnergy();
		audit = audit.withExternalSinkEnergy(SignedDyadic.of(plan.removedEnergy()));
	}

	FlipParticleReseeder.ThermalAdapter reseedAdapter(MarkerContext markers) {
		return new FlipParticleReseeder.ThermalAdapter() {
			@Override public double totalEnergy(int count) { return FlipThermalState.this.totalEnergy(count); }
			@Override public void merge(int target, int source) {
				internalEnergy[target] += internalEnergy[source];
				if (!Double.isFinite(internalEnergy[target])) {
					throw new IllegalArgumentException("Reseeded thermal merge energy is invalid");
				}
			}
			@Override public FlipParticleReseeder.ThermalSplit beforeSplit(int source) {
				return new FlipParticleReseeder.ThermalSplit(internalEnergy[source], temperature(source, markers));
			}
			@Override public void afterSplit(FlipParticleReseeder.ThermalSplit split, int source, int destination) {
				internalEnergy[source] = modeledEnergy(source, markers.mass(source), markers.mixture(source),
						split.temperatureK(), markers);
				internalEnergy[destination] = split.parentEnergy() - internalEnergy[source];
				validateTemperature(destination, split.temperatureK(), markers);
			}
			@Override public void move(int source, int destination) { internalEnergy[destination] = internalEnergy[source]; }
		};
	}

	private ThermalCellGrid.CarrierAccess carrierAccess(MarkerContext markers) {
		int liquidCount = markers.markerCount();
		return new ThermalCellGrid.CarrierAccess() {
			@Override public int carrierCount() { return Math.addExact(liquidCount, coherentPhases.groupCount()); }
			@Override public int cell(int carrier) {
				return carrier < liquidCount ? markers.cell(carrier) : coherentPhases.group(carrier - liquidCount).cell();
			}
			@Override public ThermalMaterialDefinition material(int carrier) {
				return carrier < liquidCount ? FlipThermalState.this.material(carrier, markers)
						: registry.material(registry.materialHandle(coherentPhases.group(carrier - liquidCount).phaseId()));
			}
			@Override public double mass(int carrier) {
				return carrier < liquidCount ? markers.mass(carrier) : coherentPhases.group(carrier - liquidCount).mass();
			}
			@Override public double volume(int carrier) {
				return carrier < liquidCount ? markers.volume(carrier)
						: SolidVolume.toCellVolume(coherentPhases.group(carrier - liquidCount).occupiedVolumeSvq());
			}
			@Override public double baseEnergy(int carrier) {
				if (carrier < liquidCount) return FlipThermalState.this.baseEnergy(carrier, markers);
				CoherentPhaseStore.Group group = coherentPhases.group(carrier - liquidCount);
				return stateFunction.baseEnergy(registry.materialHandle(group.phaseId()), group.mass(),
						group.composition() == null ? Map.of() : group.composition().stableAmounts());
			}
			@Override public double internalEnergy(int carrier) {
				return carrier < liquidCount ? internalEnergy[carrier]
						: coherentPhases.group(carrier - liquidCount).internalEnergy();
			}
			@Override public void setInternalEnergy(int carrier, double energy) {
				if (!Double.isFinite(energy)) throw new IllegalArgumentException("Thermal carrier energy is not finite");
				if (carrier < liquidCount) internalEnergy[carrier] = energy;
				else coherentPhases.setInternalEnergy(carrier - liquidCount, energy);
			}
		};
	}

	private double modeledCellEnergy(int cell, double temperatureK, MarkerContext markers) {
		double total = 0.0;
		for (int marker = 0; marker < markers.markerCount(); marker++) if (markers.cell(marker) == cell) {
			total += modeledEnergy(marker, markers.mass(marker), markers.mixture(marker), temperatureK, markers);
		}
		for (CoherentPhaseStore.Group group : coherentPhases.snapshot()) if (group.cell() == cell) {
			int material = registry.materialHandle(group.phaseId());
			total += stateFunction.internalEnergyAtTemperature(material, group.mass(),
					group.composition() == null ? Map.of() : group.composition().stableAmounts(), temperatureK);
		}
		if (!Double.isFinite(total)) throw new IllegalArgumentException("Thermal cell modeled energy is invalid");
		return total;
	}

	private ThermalMaterialDefinition material(int marker, MarkerContext markers) {
		return registry.material(materialByFluidHandle[markers.fluidMaterialHandle(marker)]);
	}

	private double baseEnergy(int marker, MarkerContext markers) {
		return stateFunction.baseEnergy(materialByFluidHandle[markers.fluidMaterialHandle(marker)], markers.mass(marker),
				markers.mixture(marker), markers.chemicalRegistry());
	}

	private double modeledEnergy(
			int marker, double mass, ChemicalMixtureRecord mixture, double temperatureK, MarkerContext markers
	) {
		int thermalHandle = materialByFluidHandle[markers.fluidMaterialHandle(marker)];
		ThermalMaterialDefinition material = registry.material(thermalHandle);
		double base = stateFunction.baseEnergy(thermalHandle, mass, mixture, markers.chemicalRegistry());
		double energy = base + mass * material.sensibleSpecificEnergyAt(temperatureK);
		if (!Double.isFinite(energy)) throw new IllegalArgumentException("Thermal marker modeled energy is invalid");
		return energy;
	}

	private void validateTemperature(int marker, double expected, MarkerContext markers) {
		double restored = temperature(marker, markers);
		double tolerance = Math.max(1.0E-9, Math.max(Math.ulp(restored), Math.ulp(expected)) * 16.0);
		if (Math.abs(restored - expected) > tolerance) {
			throw new IllegalArgumentException("Reseeded thermal split left local equilibrium");
		}
	}
}
