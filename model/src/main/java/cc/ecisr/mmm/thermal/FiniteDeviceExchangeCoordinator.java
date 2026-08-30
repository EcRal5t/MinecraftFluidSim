package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.units.SignedDyadic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Coordinates finite thermal device exchange with the region's cell grid: contact
 * derivation, the joint body/cell plan (device U never returns to a BlockEntity) and the
 * rollback-safe cell commit with its audit booking. The device state and the exchange
 * audit stay with the region; this coordinator only plans and applies the joint edit.
 */
public final class FiniteDeviceExchangeCoordinator {
	public record CellEnergyDelta(int cell, double internalEnergyDeltaEu) {
		public CellEnergyDelta {
			if (cell < 0 || !Double.isFinite(internalEnergyDeltaEu)) {
				throw new IllegalArgumentException("Finite thermal cell delta is invalid");
			}
		}
	}
	public record CellContactRequest(int cell, int ordinal, double conductanceEuPerSecondK) {
		public CellContactRequest {
			if (cell < 0 || ordinal < 0
					|| !(Double.isFinite(conductanceEuPerSecondK) && conductanceEuPerSecondK > 0.0)) {
				throw new IllegalArgumentException("Finite thermal cell contact is invalid");
			}
		}
	}
	public record FiniteDeviceCellPlan(
			double[] targetCellEnergy,
			int[] carrierCells, double[] carrierEnergy,
			SignedDyadic inventoryBefore, SignedDyadic requestedDelta
	) {
		public FiniteDeviceCellPlan {
			if (targetCellEnergy == null || carrierCells == null || carrierEnergy == null
					|| inventoryBefore == null || requestedDelta == null
					|| carrierCells.length != carrierEnergy.length) {
				throw new IllegalArgumentException("Finite thermal cell plan is incomplete");
			}
			targetCellEnergy = targetCellEnergy.clone();
			carrierCells = carrierCells.clone();
			carrierEnergy = carrierEnergy.clone();
		}
		@Override public double[] targetCellEnergy() { return targetCellEnergy.clone(); }
		@Override public int[] carrierCells() { return carrierCells.clone(); }
		@Override public double[] carrierEnergy() { return carrierEnergy.clone(); }
	}

	private final ThermalDomainState domain;

	FiniteDeviceExchangeCoordinator(ThermalDomainState domain) {
		this.domain = domain;
	}

	public List<FiniteThermalExchange.Contact> finiteDeviceContacts(
			List<CellContactRequest> requests, ThermalCarrierContext markers
	) {
		if (requests == null || requests.isEmpty()
				|| requests.size() > ThermalLimits.MAX_FINITE_DEVICE_CONTACTS) {
			throw new IllegalArgumentException("Finite thermal contacts are missing or over budget");
		}
		ArrayList<CellContactRequest> canonical = new ArrayList<>(requests);
		if (canonical.stream().anyMatch(java.util.Objects::isNull)) {
			throw new IllegalArgumentException("Finite thermal contacts contain null");
		}
		canonical.sort(java.util.Comparator.comparingInt(CellContactRequest::ordinal));
		ThermalCellGrid cellGrid = domain.grid();
		cellGrid.rebuild(domain.carrierAccess(markers));
		ArrayList<FiniteThermalExchange.Contact> result = new ArrayList<>(canonical.size());
		java.util.HashSet<Integer> cells = new java.util.HashSet<>();
		int previousOrdinal = -1;
		for (CellContactRequest request : canonical) {
			if (request.ordinal() == previousOrdinal || request.cell() >= cellGrid.cellCount()
					|| !cells.add(request.cell()) || !cellGrid.occupied(request.cell())) {
				throw new IllegalArgumentException("Finite thermal contacts repeat or name an empty cell");
			}
			previousOrdinal = request.ordinal();
			result.add(new FiniteThermalExchange.Contact(request.ordinal(), cellGrid.temperature(request.cell()),
					cellGrid.heatCapacity(request.cell()), request.conductanceEuPerSecondK(),
					cellGrid.minimumTemperature(request.cell()), cellGrid.maximumTemperature(request.cell())));
		}
		return List.copyOf(result);
	}

	public FiniteDeviceCellPlan planFiniteDeviceCellExchange(
			List<CellEnergyDelta> deltas, ThermalCarrierContext markers
	) {
		if (deltas == null || deltas.isEmpty() || deltas.size() > ThermalLimits.MAX_FINITE_DEVICE_CONTACTS) {
			throw new IllegalArgumentException("Finite thermal cell deltas are missing or over budget");
		}
		ArrayList<CellEnergyDelta> canonical = new ArrayList<>(deltas);
		if (canonical.stream().anyMatch(java.util.Objects::isNull)) {
			throw new IllegalArgumentException("Finite thermal cell deltas contain null");
		}
		canonical.sort(java.util.Comparator.comparingInt(CellEnergyDelta::cell));
		ThermalCellGrid cellGrid = domain.grid();
		cellGrid.rebuild(domain.carrierAccess(markers));
		double[] target = cellGrid.copyInternalEnergy();
		SignedDyadic requested = SignedDyadic.ZERO;
		int previousCell = -1;
		for (CellEnergyDelta delta : canonical) {
			if (delta.cell() >= cellGrid.cellCount() || delta.cell() == previousCell
					|| !cellGrid.occupied(delta.cell())) {
				throw new IllegalArgumentException("Finite thermal cell deltas repeat or name an empty cell");
			}
			previousCell = delta.cell();
			target[delta.cell()] += delta.internalEnergyDeltaEu();
			if (!Double.isFinite(target[delta.cell()])) {
				throw new IllegalArgumentException("Finite thermal cell target energy is not finite");
			}
			requested = requested.add(SignedDyadic.of(delta.internalEnergyDeltaEu()));
		}
		cellGrid.validateCellInternalEnergies(target);
		return new FiniteDeviceCellPlan(target, cellGrid.copyCarrierCells(),
				cellGrid.copyCarrierInternalEnergy(), domain.exactEnergy(markers.markerCount()), requested);
	}

	public void commitFiniteDeviceCellExchange(FiniteDeviceCellPlan plan, ThermalCarrierContext markers) {
		if (plan == null) throw new IllegalArgumentException("Finite thermal cell plan is missing");
		ThermalCellGrid cellGrid = domain.grid();
		cellGrid.rebuild(domain.carrierAccess(markers));
		if (!plan.inventoryBefore().equals(domain.exactEnergy(markers.markerCount()))
				|| !Arrays.equals(plan.carrierCells(), cellGrid.copyCarrierCells())
				|| !Arrays.equals(plan.carrierEnergy(), cellGrid.copyCarrierInternalEnergy())) {
			throw new IllegalArgumentException("Finite thermal cell plan is stale");
		}
		double[] rollback = cellGrid.copyCarrierInternalEnergy();
		ThermalAuditLedger beforeAudit = domain.audit().ledger();
		try {
			cellGrid.setCellInternalEnergies(plan.targetCellEnergy());
			SignedDyadic actualDelta = domain.exactEnergy(markers.markerCount()).subtract(plan.inventoryBefore());
			domain.audit().update(ledger -> ledger.withFiniteDeviceExchange(plan.requestedDelta())
					.withRoundoff(actualDelta.subtract(plan.requestedDelta())));
		} catch (RuntimeException failure) {
			domain.audit().replace(beforeAudit);
			try { cellGrid.restoreCarrierInternalEnergy(rollback); }
			catch (RuntimeException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
			throw failure;
		}
	}
}
