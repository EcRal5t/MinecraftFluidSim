package com.example.fluid;

import com.example.chemistry.ChemicalMixtureRecord;
import com.example.chemistry.ChemicalRegistry;
import com.example.thermal.SignedDyadic;
import com.example.thermal.ThermalAuditLedger;
import com.example.thermal.ThermalCellGrid;
import com.example.thermal.ThermalMaterialDefinition;
import com.example.thermal.ThermalRegistry;
import com.example.thermal.ThermalStateFunction;
import com.example.thermal.ThermalTransport;

import java.util.Arrays;

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
	ThermalTransport.Stats lastTransport() { return lastTransport; }
	double totalEnergy(int count) {
		double total = 0.0;
		for (int marker = 0; marker < count; marker++) total += internalEnergy[marker];
		return total;
	}
	SignedDyadic exactEnergy(int count) { return SignedDyadic.sum(Arrays.copyOf(internalEnergy, count)); }

	void ensureCapacity(int capacity) {
		if (capacity > internalEnergy.length) internalEnergy = Arrays.copyOf(internalEnergy, capacity);
	}

	FlipThermalParticleState capture(int sizeX, int sizeY, int sizeZ, float cellSize, int count) {
		return new FlipThermalParticleState(sizeX, sizeY, sizeZ, cellSize, count, registry.fingerprint(),
				Arrays.copyOf(internalEnergy, count), audit);
	}

	void restore(FlipThermalParticleState snapshot, MarkerContext markers) {
		double[] restored = snapshot.copyInternalEnergy();
		ensureCapacity(restored.length);
		System.arraycopy(restored, 0, internalEnergy, 0, restored.length);
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
		cellGrid.rebuild(markerAccess(markers));
		return cellGrid.occupied(cell) ? cellGrid.temperature(cell) : Double.NaN;
	}

	void setTemperatureInCell(int cell, double temperatureK, MarkerContext markers) {
		SignedDyadic before = exactEnergy(markers.markerCount());
		boolean found = false;
		for (int marker = 0; marker < markers.markerCount(); marker++) if (markers.cell(marker) == cell) {
			internalEnergy[marker] = modeledEnergy(marker, markers.mass(marker), markers.mixture(marker), temperatureK, markers);
			found = true;
		}
		if (!found) throw new IllegalArgumentException("Cannot set temperature in an empty cell");
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
		cellGrid.rebuild(markerAccess(markers));
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

	private ThermalCellGrid.MarkerAccess markerAccess(MarkerContext markers) {
		return new ThermalCellGrid.MarkerAccess() {
			@Override public int markerCount() { return markers.markerCount(); }
			@Override public int cell(int marker) { return markers.cell(marker); }
			@Override public ThermalMaterialDefinition material(int marker) {
				return FlipThermalState.this.material(marker, markers);
			}
			@Override public double mass(int marker) { return markers.mass(marker); }
			@Override public double volume(int marker) { return markers.volume(marker); }
			@Override public double baseEnergy(int marker) { return FlipThermalState.this.baseEnergy(marker, markers); }
			@Override public double internalEnergy(int marker) { return internalEnergy[marker]; }
			@Override public void setInternalEnergy(int marker, double energy) {
				if (!Double.isFinite(energy)) throw new IllegalArgumentException("Thermal marker energy is not finite");
				internalEnergy[marker] = energy;
			}
		};
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
