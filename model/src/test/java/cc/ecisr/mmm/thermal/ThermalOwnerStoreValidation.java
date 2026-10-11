package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.chemistry.*;

import java.util.List;
import java.util.Map;

/**
 * Component-level invariants of the thermal owner stores. The stores' incremental mutators
 * are package-private to the thermal state owners, so these checks live in the thermal test
 * package and drive scratch stores directly.
 */
public final class ThermalOwnerStoreValidation {
	private ThermalOwnerStoreValidation() { }

	public static void main(String[] args) {
		validate();
		System.out.println("Thermal owner-store validation passed");
	}

	public static void validate() {
		coherentPhaseMergeSplitConservesInventories();
		granularColumnTransactionsConserveEnergy();
		condensedTinyPortionsRetainTemperatureAndAudit();
		granularBatchDepositPreflightIsAtomic();
		dyadicConversionRoundsInFinalBinary64Unit();
		liquidTemperatureTracksMutableInventory();
		referenceTermsKeepCanonicalRationalRounding();
		referenceEnergyUsesCanonicalSpeciesOrder();
		exactAggregateEnergySurvivesCaloricHandoffs();
		caloricKnotsRetainTheirOwnEnergyScale();
		caloricBoundsKeepFiniteStates();
		caloricEndpointsAndPortionsPreserveFiniteStates();
		coolingInversionRetainsEnergyScale();
	}

	private static void exactAggregateEnergySurvivesCaloricHandoffs() {
		alternateRemainderKeepsEveryOwnerPositive();
		overflowModeledCarrierUsesConservationRemainder();
		phaseSnapshotsKeepOriginalOwnerRevision();
		finiteCellExchangeRetainsExactBaseline();
		cellEnergyPreflightPreservesDerivedState();
		for (double reference : new double[] {1e18, -1e18}) for (double smallCv : new double[] {1, 1.0001})
			for (boolean reverse : new boolean[] {false, true}) {
				var large = new ThermalMaterialDefinition("test:handoff/a", ThermalMaterialDefinition.CarrierKind.LIQUID,
						"test:handoff/a", 300, reference, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(250, 600, 1)));
				var small = new ThermalMaterialDefinition("test:handoff/b", ThermalMaterialDefinition.CarrierKind.LIQUID,
						"test:handoff/b", 300, 0, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(250, 600, smallCv)));
				var registry = new ThermalRegistry(List.of(large, small)); var function = new ThermalStateFunction(registry);
				double smallEnergy = function.internalEnergyAtTemperature(1, 1, Map.of(), 500);
				var context = thermalHandoffContext();
				var state = ThermalDomainState.restoreTarget(registry, reverse ? new int[] {1, 0} : new int[] {0, 1},
						new int[0], 300, 2, 1, 1, 1, .5F, context, null, 1,
						reverse ? new double[] {smallEnergy, reference} : new double[] {reference, smallEnergy});
				SignedDyadic before = state.exactEnergy(2);
				state.restoreAuditLedger(ThermalAuditLedger.initialized(before));
				var audit = state.auditLedger(); var revision = state.thermalRevision();
				var inventory = List.of(new ThermalStateFunction.InventoryPortion(0, 1, Map.of()),
						new ThermalStateFunction.InventoryPortion(1, 1, Map.of()));
				double expected = 300 + smallEnergy / (1 + smallCv), tolerance = 4 * Math.ulp(expected);
				require(Math.abs(function.stateForInventory(inventory, before).temperatureK() - expected) <= tolerance,
						"Exact LTE input rounded before reference removal");
				state.preparePhaseScan(context);
				require(Math.abs(state.phaseScanTemperatureInCell(0) - expected) <= tolerance
						&& Math.abs(state.observeTemperatureInCell(0, context) - expected) <= tolerance
						&& Math.abs(state.phaseCandidateTemperature(0, inventory, context) - expected) <= tolerance
						&& Math.abs(state.requirePhaseCandidateState(state.preparePhaseCandidateCell(0, context), inventory, 0, 0) - expected) <= tolerance,
						"Actual cell, observation and phase candidates used different sensible energy");
				require(state.exactEnergy(2).equals(before) && state.auditLedger().equals(audit) && state.thermalRevision().equals(revision),
						"Read-only caloric handoff changed owner energy or audit");
				state.repartitionCellEnergyConserving(0, context, before);
				double expectedSmall = function.internalEnergyAtTemperature(1, 1, Map.of(), expected);
				require(Math.abs(state.liquidInternalEnergy(reverse ? 0 : 1) - expectedSmall) <= 8 * Math.ulp(expectedSmall)
						&& state.exactEnergy(2).equals(state.auditLedger().expectedInventory())
						&& state.auditLedger().roundoffResidual().equals(state.exactEnergy(2).subtract(before)),
						"Conservative LTE commit lost the small energy or failed to find a valid remainder carrier");
				double actualTemperature = function.stateForInventory(inventory, state.exactEnergy(2)).temperatureK();
				require(Math.abs(state.phaseScanTemperatureInCell(0) - actualTemperature) <= 4 * Math.ulp(actualTemperature)
						&& state.observeTemperatureInCell(0, context) == actualTemperature,
						"Committed cell retained the prospective temperature instead of deriving actual owner energy");
			}
		var positive = new ThermalMaterialDefinition("test:reject/a", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"test:reject/a", 300, 1e18, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(10, 1000, 1)));
		var negative = new ThermalMaterialDefinition("test:reject/b", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"test:reject/b", 300, -1e18, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(10, 1000, 1)));
		var context = thermalHandoffContext();
		var state = ThermalDomainState.restoreTarget(new ThermalRegistry(List.of(positive, negative)), new int[] {0, 1}, new int[0],
				300, 2, 1, 1, 1, .5F, context, null, 1, new double[] {1e18 + 256, -1e18 - 128});
		SignedDyadic before = state.exactEnergy(2); state.restoreAuditLedger(ThermalAuditLedger.initialized(before));
		var audit = state.auditLedger(); var revision = state.thermalRevision(); state.preparePhaseScan(context);
		try { state.repartitionCellEnergyConserving(0, context, before); throw new AssertionError("Unrepresentable LTE remainder accepted"); }
		catch (IllegalArgumentException expected) {
			require(state.exactEnergy(2).equals(before) && state.liquidInternalEnergy(0) == 1e18 + 256
					&& state.liquidInternalEnergy(1) == -1e18 - 128 && state.auditLedger().equals(audit) && state.thermalRevision().equals(revision),
					"Unrepresentable LTE assignment changed an owner before full validation");
		}
		try { state.repartitionCellEnergy(0, 129, context, before); throw new AssertionError("Unrepresentable direct target accepted"); }
		catch (IllegalArgumentException expected) {
			require(state.phaseScanTemperatureInCell(0) == 364 && state.observeTemperatureInCell(0, context) == 364
					&& state.exactEnergy(2).equals(before) && state.auditLedger().equals(audit) && state.thermalRevision().equals(revision),
					"Rejected direct assignment leaked a prospective temperature");
		}
		var finite = state.planFiniteDeviceCellExchange(List.of(new FiniteDeviceExchangeCoordinator.CellEnergyDelta(0, -127)), context);
		state.commitFiniteDeviceCellExchange(finite, context);
		require(state.exactEnergy(2).equals(SignedDyadic.ZERO) && state.phaseScanTemperatureInCell(0) == 300
				&& state.observeTemperatureInCell(0, context) == 300
				&& state.auditLedger().roundoffResidual().equals(SignedDyadic.of(-1))
				&& state.exactEnergy(2).equals(state.auditLedger().expectedInventory()),
				"Finite commit derived temperature from its requested energy instead of actual rounded inventory");
	}

	private static ThermalCarrierContext thermalHandoffContext() {
		return new ThermalCarrierContext() {
			public int markerCount() { return 2; } public int cell(int marker) { return 0; }
			public int fluidMaterialHandle(int marker) { return marker; } public double mass(int marker) { return 8; }
			public double volume(int marker) { return .5; } public ChemicalMixtureRecord mixture(int marker) { return null; }
			public ChemicalRegistry chemicalRegistry() { return null; }
		};
	}

	private static void alternateRemainderKeepsEveryOwnerPositive() {
		double cv = 1e12 + 5;
		var a = new ThermalMaterialDefinition("test:pivot/a", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"test:pivot/a", 400, 1e18, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(
						ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K, 500, cv)));
		var b = new ThermalMaterialDefinition("test:pivot/b", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"test:pivot/b", 1e-12, -2e18, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(
						ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K, 500, 10 * cv)));
		double[] energy = {Math.nextUp(1e18 - 400 * cv), -2e18};
		SignedDyadic target = SignedDyadic.sum(energy);
		var grid = new ThermalCellGrid(1);
		grid.rebuild(new ThermalCellGrid.CarrierAccess() {
			public int carrierCount() { return 2; } public int cell(int item) { return 0; }
			public ThermalMaterialDefinition material(int item) { return item == 0 ? a : b; }
			public double mass(int item) { return 1; } public double volume(int item) { return 1; }
			public double baseEnergy(int item) { return item == 0 ? 1e18 : -2e18; }
			public double internalEnergy(int item) { return energy[item]; }
			public void setInternalEnergy(int item, double value) { energy[item] = value; }
		});
		grid.setCellInternalEnergy(0, target);
		require(SignedDyadic.sum(energy).equals(target) && grid.temperature(0) > ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K
				&& grid.carriersMatchSolvedTemperature(),
				"A failed preferred remainder prevented an alternate positive raw-owner assignment");
	}

	private static void overflowModeledCarrierUsesConservationRemainder() {
		for (int count : new int[] {2, 4}) {
			double cv = 1e308 / count, maximum = Double.MAX_VALUE;
			var positive = new ThermalMaterialDefinition("test:overflow/a", ThermalMaterialDefinition.CarrierKind.LIQUID,
					"test:overflow/a", 1, maximum, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(1, 2, cv)));
			var negative = new ThermalMaterialDefinition("test:overflow/b", ThermalMaterialDefinition.CarrierKind.LIQUID,
					"test:overflow/b", 1, -maximum, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(1, 2, cv)));
			double[] energy = new double[count];
			for (int item = 0; item < count; item++) energy[item] = item < count / 2 ? maximum : Math.nextUp(-maximum);
			double[] before = energy.clone(); SignedDyadic target = SignedDyadic.sum(energy);
			var grid = new ThermalCellGrid(1);
			grid.rebuild(new ThermalCellGrid.CarrierAccess() {
				public int carrierCount() { return count; } public int cell(int item) { return 0; }
				public ThermalMaterialDefinition material(int item) { return item < count / 2 ? positive : negative; }
				public double mass(int item) { return 1; } public double volume(int item) { return 1.0 / count; }
				public double baseEnergy(int item) { return item < count / 2 ? maximum : -maximum; }
				public double internalEnergy(int item) { return energy[item]; }
				public void setInternalEnergy(int item, double value) { energy[item] = value; }
			});
			double temperature = grid.temperature(0);
			if (count == 2) {
				grid.setCellInternalEnergy(0, target);
				require(SignedDyadic.sum(energy).equals(target) && java.util.Arrays.equals(energy, before)
						&& grid.temperature(0) == temperature && Double.isFinite(grid.heatCapacity(0)),
						"A single unrepresentable modeled owner prevented a valid conservation remainder");
			} else {
				try { grid.setCellInternalEnergy(0, target); throw new AssertionError("Two unrepresentable modeled owners accepted"); }
				catch (IllegalArgumentException expected) {
					require(java.util.Arrays.equals(energy, before) && grid.temperature(0) == temperature,
							"A multiple-overflow assignment wrote an owner or its derived temperature");
				}
			}
		}
	}

	private static void phaseSnapshotsKeepOriginalOwnerRevision() {
		var first = new ThermalMaterialDefinition("test:snapshot/a", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"test:snapshot/a", 300, 0, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(100, 500, 1)));
		var second = new ThermalMaterialDefinition("test:snapshot/b", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"test:snapshot/b", 300, 0, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(100, 500, 1)));
		var registry = new ThermalRegistry(List.of(first, second)); var context = thermalHandoffContext();
		var state = new ThermalDomainState(registry, new int[] {0, 1}, new int[0], 300, 2, 1, 1, 1, .5F, context, null, 1);
		var foreign = new ThermalDomainState(registry, new int[] {0, 1}, new int[0], 300, 2, 1, 1, 1, .5F, context, null, 1);
		state.preparePhaseScan(context); foreign.preparePhaseScan(context);
		var stale = state.preparePhaseCandidateCell(0, context); var other = foreign.preparePhaseCandidateCell(0, context);
		state.setTemperatureInCell(0, 301, context);
		SignedDyadic energy = state.exactEnergy(2); var audit = state.auditLedger(); var revision = state.thermalRevision();
		var inventory = List.of(new ThermalStateFunction.InventoryPortion(0, 1, Map.of()),
				new ThermalStateFunction.InventoryPortion(1, 1, Map.of()));
		for (var snapshot : List.of(stale, other)) for (Runnable request : new Runnable[] {
				() -> state.planPhaseCellEnergy(snapshot, inventory),
				() -> state.planPhaseCellEnergy(snapshot, inventory, 0, 0),
				() -> state.requirePhaseCandidateState(snapshot, inventory, 0, 0)}) {
			try { request.run(); throw new AssertionError("A stale or foreign phase-cell snapshot accepted"); }
			catch (IllegalStateException expected) {
				require(state.exactEnergy(2).equals(energy) && state.auditLedger().equals(audit)
						&& state.thermalRevision().equals(revision) && state.observeTemperatureInCell(0, context) == 301,
						"Rejected phase-cell snapshot changed its current owner energy or audit");
			}
		}
		var current = state.preparePhaseCandidateCell(0, context);
		state.requirePhaseCellEnergyCurrent(state.planPhaseCellEnergy(current, inventory));
		state.requirePhaseCellEnergyCurrent(state.planPhaseCellEnergy(current, inventory, 0, 0));
	}

	private static void finiteCellExchangeRetainsExactBaseline() {
		for (double reference : new double[] {1e18, -1e18}) for (boolean reverse : new boolean[] {false, true})
			for (double delta : new double[] {-128, -1, 0, 1, 128}) {
				var large = new ThermalMaterialDefinition("test:finite/a", ThermalMaterialDefinition.CarrierKind.LIQUID,
						"test:finite/a", 300, reference, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(250, 600, 1)));
				var small = new ThermalMaterialDefinition("test:finite/b", ThermalMaterialDefinition.CarrierKind.LIQUID,
						"test:finite/b", 300, 0, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(250, 600, 1)));
				var context = thermalHandoffContext();
				var state = ThermalDomainState.restoreTarget(new ThermalRegistry(List.of(large, small)),
						reverse ? new int[] {1, 0} : new int[] {0, 1}, new int[0], 300, 2, 1, 1, 1, .5F,
						context, null, 1, reverse ? new double[] {200, reference} : new double[] {reference, 200});
				SignedDyadic before = state.exactEnergy(2); state.restoreAuditLedger(ThermalAuditLedger.initialized(before));
				var revision = state.thermalRevision();
				var plan = state.planFiniteDeviceCellExchange(List.of(new FiniteDeviceExchangeCoordinator.CellEnergyDelta(0, delta)), context);
				require(plan.targetCellEnergy()[0].equals(before.add(SignedDyadic.of(delta)))
						&& state.phaseScanTemperatureInCell(0) == 400 && state.exactEnergy(2).equals(before)
						&& state.thermalRevision().equals(revision), "Finite cell preflight rounded or edited its actual baseline");
				state.commitFiniteDeviceCellExchange(plan, context);
				require(state.liquidInternalEnergy(reverse ? 0 : 1) == (delta == 0 ? 200 : 100 + delta / 2)
						&& state.auditLedger().finiteDeviceExchange().equals(SignedDyadic.of(delta))
						&& state.auditLedger().roundoffResidual().equals(state.exactEnergy(2).subtract(before).subtract(SignedDyadic.of(delta)))
						&& state.exactEnergy(2).equals(state.auditLedger().expectedInventory()),
						"Finite cell exchange lost a resolvable transfer or solved a rounded target");
				double actualTemperature = 300 + state.exactEnergy(2).subtract(SignedDyadic.of(reference)).toDouble() / 2;
				require(state.phaseScanTemperatureInCell(0) == actualTemperature
						&& state.observeTemperatureInCell(0, context) == actualTemperature,
						"Finite commit cached a requested temperature different from actual owner energy");
			}
		var positive = new ThermalMaterialDefinition("test:remote/a", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"test:remote/a", 300, 1e18, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(10, 1000, 1)));
		var negative = new ThermalMaterialDefinition("test:remote/b", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"test:remote/b", 300, -1e18, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(10, 1000, 1)));
		var small = new ThermalMaterialDefinition("test:remote/c", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"test:remote/c", 300, 0, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(10, 1000, 1)));
		var context = new ThermalCarrierContext() {
			public int markerCount() { return 3; } public int cell(int marker) { return marker == 2 ? 1 : 0; }
			public int fluidMaterialHandle(int marker) { return marker; } public double mass(int marker) { return 8; }
			public double volume(int marker) { return .5; } public ChemicalMixtureRecord mixture(int marker) { return null; }
			public ChemicalRegistry chemicalRegistry() { return null; }
		};
		var state = ThermalDomainState.restoreTarget(new ThermalRegistry(List.of(positive, negative, small)),
				new int[] {0, 1, 2}, new int[0], 300, 3, 2, 1, 1, .5F, context, null, 1,
				new double[] {1e18 + 256, -1e18 - 128, 100});
		var before = state.exactEnergy(3); state.restoreAuditLedger(ThermalAuditLedger.initialized(before));
		var plan = state.planFiniteDeviceCellExchange(List.of(new FiniteDeviceExchangeCoordinator.CellEnergyDelta(0, 0),
				new FiniteDeviceExchangeCoordinator.CellEnergyDelta(1, 1)), context);
		state.commitFiniteDeviceCellExchange(plan, context);
		require(state.liquidInternalEnergy(0) == 1e18 + 256 && state.liquidInternalEnergy(1) == -1e18 - 128
				&& state.liquidInternalEnergy(2) == 101 && state.auditLedger().roundoffResidual().equals(SignedDyadic.ZERO)
				&& state.exactEnergy(3).equals(state.auditLedger().expectedInventory()),
				"Local finite exchange tried to redistribute an untouched, unrepresentable remote LTE cell");
	}

	private static void cellEnergyPreflightPreservesDerivedState() {
		var ordinary = new ThermalMaterialDefinition("test:bulk/a", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"test:bulk/a", 300, 0, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(10, 1000, 1)));
		var energies = new double[] {100, 1e18 + 256, -1e18 - 128};
		var grid = new ThermalCellGrid(2);
		grid.rebuild(new ThermalCellGrid.CarrierAccess() {
			public int carrierCount() { return 3; } public int cell(int carrier) { return carrier == 0 ? 0 : 1; }
			public ThermalMaterialDefinition material(int carrier) { return ordinary; } public double mass(int carrier) { return 1; }
			public double volume(int carrier) { return .25; }
			public double baseEnergy(int carrier) { return carrier == 0 ? 0 : carrier == 1 ? 1e18 : -1e18; }
			public double internalEnergy(int carrier) { return energies[carrier]; }
			public void setInternalEnergy(int carrier, double value) { energies[carrier] = value; }
		});
		var range = grid.observedTemperatureRange();
		for (boolean apply : new boolean[] {false, true}) {
			try {
				if (apply) grid.setCellInternalEnergies(new double[] {101, 129});
				else grid.validateCellInternalEnergies(new double[] {101, 129});
				throw new AssertionError("Unrepresentable bulk target accepted");
			} catch (IllegalArgumentException expected) {
				require(energies[0] == 100 && energies[1] == 1e18 + 256 && energies[2] == -1e18 - 128
						&& grid.temperature(0) == 400 && grid.temperature(1) == 364
						&& grid.internalEnergy(0) == 100 && grid.internalEnergy(1) == 128
						&& grid.observedTemperatureRange().equals(range), "Failed bulk preflight edited inventory or derived cells");
			}
		}
		grid.setCellInternalEnergies(new double[] {101, 1});
		require(energies[0] == 101 && SignedDyadic.of(energies[1]).add(SignedDyadic.of(energies[2])).equals(SignedDyadic.ZERO)
				&& grid.internalEnergy(1) == 0 && grid.temperature(0) == 401 && grid.temperature(1) == 300,
				"Bulk commit lost the preflight temperature or retained a prospective energy cache");
		var cold = new ThermalMaterialDefinition("test:positive/raw", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"test:positive/raw", 363, 1e18, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(1, 1000, 1)));
		var coldContext = new ThermalCarrierContext() {
			public int markerCount() { return 1; } public int cell(int marker) { return 0; }
			public int fluidMaterialHandle(int marker) { return 0; } public double mass(int marker) { return 8; }
			public double volume(int marker) { return .5; } public ChemicalMixtureRecord mixture(int marker) { return null; }
			public ChemicalRegistry chemicalRegistry() { return null; }
		};
		var state = ThermalDomainState.restoreTarget(new ThermalRegistry(List.of(cold)), new int[] {0}, new int[0],
				363, 1, 1, 1, 1, .5F, coldContext, null, 1, new double[] {1e18});
		var before = state.exactEnergy(1); state.restoreAuditLedger(ThermalAuditLedger.initialized(before));
		var audit = state.auditLedger(); var revision = state.thermalRevision();
		try {
			state.planFiniteDeviceCellExchange(List.of(new FiniteDeviceExchangeCoordinator.CellEnergyDelta(0, -363 + 1e-6)), coldContext);
			throw new AssertionError("Positive modeled temperature accepted an invalid rounded owner energy");
		} catch (IllegalArgumentException expected) {
			require(state.liquidInternalEnergy(0) == 1e18 && state.exactEnergy(1).equals(before)
					&& state.phaseScanTemperatureInCell(0) == 363 && state.auditLedger().equals(audit)
					&& state.thermalRevision().equals(revision), "Invalid actual rounded owner failed after a partial publication");
		}
	}

	private static void caloricKnotsRetainTheirOwnEnergyScale() {
		var context = new ThermalCarrierContext() {
			public int markerCount() { return 1; } public int cell(int marker) { return 0; }
			public int fluidMaterialHandle(int marker) { return 0; } public double mass(int marker) { return 8; }
			public double volume(int marker) { return .5; } public ChemicalMixtureRecord mixture(int marker) { return null; }
			public ChemicalRegistry chemicalRegistry() { return null; }
		};
		for (boolean reverseCapacity : new boolean[] {false, true}) for (double reference : new double[] {0, -1e18, 1e18})
			for (double referenceTemperature : new double[] {300, 600}) for (double requestedTemperature : new double[] {
					250, Math.nextDown(300), 300, Math.nextUp(300), 500, Math.nextDown(600), 600, Math.nextUp(600)}) {
				var segments = List.of(new ThermalMaterialDefinition.CaloricSegment(250, 300, reverseCapacity ? 1 : 1e16),
						new ThermalMaterialDefinition.CaloricSegment(300, 600, reverseCapacity ? 1e16 : 1));
				var liquid = new ThermalMaterialDefinition("test:knot/a", ThermalMaterialDefinition.CarrierKind.LIQUID,
						"test:knot/a", referenceTemperature, reference, 0, segments);
				var device = new ThermalMaterialDefinition("test:knot/b", ThermalMaterialDefinition.CarrierKind.DEVICE,
						"test:knot/b", referenceTemperature, reference, 0, segments);
				var registry = new ThermalRegistry(List.of(liquid, device)); var function = new ThermalStateFunction(registry);
				double energy = function.internalEnergyAtTemperature(0, 1, Map.of(), requestedTemperature);
				double actualTemperature = function.stateForInternalEnergy(0, 1, Map.of(), energy).temperatureK();
				var state = ThermalDomainState.restoreTarget(registry, new int[] {0}, new int[0], referenceTemperature,
						1, 1, 1, 1, .5F, context, null, 1, new double[] {energy});
				state.preparePhaseScan(context);
				double tolerance = 4 * Math.ulp(actualTemperature);
				require(Math.abs(state.phaseScanTemperatureInCell(0) - actualTemperature) <= tolerance
						&& Math.abs(state.observeTemperatureInCell(0, context) - actualTemperature) <= tolerance,
						"Caloric knot used a preceding interval's energy magnitude or accumulated cancellation");
				var contacts = state.finiteDeviceContacts(List.of(new FiniteDeviceExchangeCoordinator.CellContactRequest(0, 0, 1)), context);
				var body = new FiniteThermalBodyState(0, registry.fingerprint(), device.id(), 1, energy);
				var exchange = FiniteThermalExchange.plan(registry, body, contacts, .01, 8);
				require(!exchange.changed() && exchange.contacts().getFirst().internalEnergyDeltaEu() == 0,
						"Caloric knot created a finite-device heat flow between equal actual temperatures");
			}
		for (double cv : new double[] {3 * Double.MIN_VALUE, 1e308}) {
			var liquid = new ThermalMaterialDefinition("test:endpoint", ThermalMaterialDefinition.CarrierKind.LIQUID,
					"test:endpoint", cv < 1 ? 300 : 1, 0, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(1, 1000, cv)));
			var registry = new ThermalRegistry(List.of(liquid));
			for (double energy : cv < 1 ? new double[] {0, Double.MIN_VALUE, 3 * Double.MIN_VALUE} : new double[] {0, 1e307}) {
				var state = ThermalDomainState.restoreTarget(registry, new int[] {0}, new int[0], liquid.referenceTemperatureK(),
						1, 1, 1, 1, .5F, context, null, 1, new double[] {energy});
				state.preparePhaseScan(context);
				double expected = liquid.referenceTemperatureK() + energy / cv;
				require(state.phaseScanTemperatureInCell(0) == expected && state.observeTemperatureInCell(0, context) == expected,
						"A subnormal caloric quantum or overflowing distant endpoint changed a finite temperature");
			}
		}
		var nearMinimum = new ThermalMaterialDefinition("test:minimum", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"test:minimum", 300, 0, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(250, 300, 1e16),
				new ThermalMaterialDefinition.CaloricSegment(300, 600, 1)));
		var function = new ThermalStateFunction(new ThermalRegistry(List.of(nearMinimum)));
		double minimumEnergy = -3e18, energy = Math.nextUp(minimumEnergy);
		require(function.stateForInventory(List.of(new ThermalStateFunction.InventoryPortion(0, 1, Map.of())), energy).temperatureK()
				== (energy - minimumEnergy) / 1e16, "Minimum-domain tolerance swallowed a representable positive temperature");
		require(function.stateForInternalEnergy(0, 1, Map.of(), energy).temperatureK() == (energy - minimumEnergy) / 1e16,
				"Single-owner cold extrapolation erased its positive temperature");
	}

	private static void caloricBoundsKeepFiniteStates() {
		for (double reference : new double[] {0, -1e18}) {
			var material = new ThermalMaterialDefinition("test:infinite", ThermalMaterialDefinition.CarrierKind.LIQUID,
					"test:infinite", 300, reference, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(250, 300, 1e308),
					new ThermalMaterialDefinition.CaloricSegment(300, 600, 1)));
			var registry = new ThermalRegistry(List.of(material)); double raw = reference == 0 ? 200 : reference + 256;
			var context = new ThermalCarrierContext() {
				public int markerCount() { return 1; } public int cell(int p) { return 0; } public int fluidMaterialHandle(int p) { return 0; }
				public double mass(int p) { return 8; } public double volume(int p) { return .5; }
				public ChemicalMixtureRecord mixture(int p) { return null; } public ChemicalRegistry chemicalRegistry() { return null; }
			};
			var state = ThermalDomainState.restoreTarget(registry, new int[] {0}, new int[0], 300,
					1, 1, 1, 1, .5F, context, null, 1, new double[] {raw}); state.preparePhaseScan(context);
			double expected = 300 + (raw - reference);
			require(state.temperature(0, context) == expected && state.phaseScanTemperatureInCell(0) == expected
					&& state.observeTemperatureInCell(0, context) == expected, "An infinite cold bound rejected a finite caloric state");
		}
		for (boolean cancellingSegments : new boolean[] {false, true}) for (boolean reverse : new boolean[] {false, true}) {
			var a = new ThermalMaterialDefinition("test:wide/a", ThermalMaterialDefinition.CarrierKind.LIQUID, "test:wide/a", 100, 0, 0,
					cancellingSegments ? List.of(new ThermalMaterialDefinition.CaloricSegment(1, 400, 1e308),
					new ThermalMaterialDefinition.CaloricSegment(400, 1000, 1)) : List.of(new ThermalMaterialDefinition.CaloricSegment(1, 1000, 5e307)));
			var b = new ThermalMaterialDefinition("test:wide/b", ThermalMaterialDefinition.CarrierKind.LIQUID, "test:wide/b", 900, 0, 0,
					cancellingSegments ? List.of(new ThermalMaterialDefinition.CaloricSegment(1, 600, 1),
					new ThermalMaterialDefinition.CaloricSegment(600, 1000, 1e308)) : a.caloricSegments());
			var registry = new ThermalRegistry(List.of(a, b)); var function = new ThermalStateFunction(registry);
			var portions = List.of(new ThermalStateFunction.InventoryPortion(reverse ? 1 : 0, 1, Map.of()),
					new ThermalStateFunction.InventoryPortion(reverse ? 0 : 1, 1, Map.of()));
			require(function.stateForInventory(portions, 0).temperatureK() == 500,
					"Wide opposite caloric bounds lost their finite common-temperature state or depended on portion order");
			var grid = new ThermalCellGrid(1);
			grid.rebuild(new ThermalCellGrid.CarrierAccess() {
				public int carrierCount() { return 2; } public int cell(int p) { return 0; }
				public ThermalMaterialDefinition material(int p) { return (p == 0) == !reverse ? a : b; }
				public double mass(int p) { return 1; } public double volume(int p) { return .25; }
				public double baseEnergy(int p) { return 0; } public double internalEnergy(int p) { return 0; }
				public void setInternalEnergy(int p, double u) { throw new AssertionError("Caloric observation wrote inventory"); }
			});
			require(grid.temperature(0) == 500, "Wide opposite caloric cell bounds lost their finite state");
		}
	}

	private static void caloricEndpointsAndPortionsPreserveFiniteStates() {
		for (double mass : new double[] {1, .7}) for (double reference : new double[] {0, -1e18, 1e18}) {
			var material = new ThermalMaterialDefinition("test:handoff/a", ThermalMaterialDefinition.CarrierKind.LIQUID,
					"test:handoff/a", 1200, reference, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(100, 1000, 1),
					new ThermalMaterialDefinition.CaloricSegment(1000, 1500, 1e16)));
			var function = new ThermalStateFunction(new ThermalRegistry(List.of(material)));
			double raw = function.internalEnergyAtTemperature(0, mass, Map.of(), 500), base = function.baseEnergy(0, mass, Map.of());
			var exactMass = new java.math.BigDecimal(mass);
			double expected = new java.math.BigDecimal(raw).subtract(new java.math.BigDecimal(base))
					.add(new java.math.BigDecimal(2e18).multiply(exactMass)).divide(exactMass, java.math.MathContext.DECIMAL128)
					.add(new java.math.BigDecimal(1000)).doubleValue();
			requireCaloricHandoff(material, mass, raw, expected);
		}
		for (double mass : new double[] {1, 1e-100}) {
			var material = new ThermalMaterialDefinition("test:handoff/a", ThermalMaterialDefinition.CarrierKind.LIQUID,
					"test:handoff/a", mass == 1 ? 1 : 300, mass == 1 ? -1.7e308 : 0, 0,
					List.of(new ThermalMaterialDefinition.CaloricSegment(1, 1000, 1e308)));
			requireCaloricHandoff(material, mass, mass == 1 ? 1.7e308 : 2e210, mass == 1 ? 4.4 : 500);
		}
	}

	private static void requireCaloricHandoff(ThermalMaterialDefinition material, double mass, double raw, double expected) {
		var device = new ThermalMaterialDefinition("test:handoff/b", ThermalMaterialDefinition.CarrierKind.DEVICE,
				"test:handoff/b", material.referenceTemperatureK(), material.referenceSpecificEnergyEuPerMu(), 0, material.caloricSegments());
		var registry = new ThermalRegistry(List.of(material, device)); var function = new ThermalStateFunction(registry);
		var context = new ThermalCarrierContext() {
			public int markerCount() { return 1; } public int cell(int p) { return 0; } public int fluidMaterialHandle(int p) { return 0; }
			public double mass(int p) { return mass * 8; } public double volume(int p) { return .5; }
			public ChemicalMixtureRecord mixture(int p) { return null; } public ChemicalRegistry chemicalRegistry() { return null; }
			public void observationSupport(int p, SupportConsumer consumer) { consumer.accept(0, .1); consumer.accept(1, .9); }
		};
		var state = ThermalDomainState.restoreTarget(registry, new int[] {0}, new int[0], material.referenceTemperatureK(),
				2, 1, 1, 2, .5F, context, null, 1, new double[] {raw});
		state.preparePhaseScan(context); state.prepareSpatialObservation(context); double tolerance = 4 * Math.ulp(expected);
		require(Math.abs(state.temperature(0, context) - expected) <= tolerance
				&& Math.abs(state.phaseScanTemperatureInCell(0) - expected) <= tolerance
				&& Math.abs(state.observeTemperatureInCell(0, context) - expected) <= tolerance
				&& Math.abs(state.spatialTemperatureInCell(0) - expected) <= tolerance
				&& Math.abs(state.spatialTemperatureInCell(1) - expected) <= tolerance,
				"Exact caloric endpoint or shared spatial mass/energy coefficient lost a finite owner temperature");
		double actual = state.temperature(0, context), forward = function.internalEnergyAtTemperature(0, mass, Map.of(), actual);
		require(Double.isFinite(forward) && Math.abs(forward - raw) <= 4 * Math.ulp(raw), "Finite final owner energy was lost to an intermediate overflow");
		var caloric = state.liquid().caloricInventory(0, mass * 8, null, null);
		require(Math.abs(caloric.temperature(SignedDyadic.of(raw)) - actual) <= tolerance
				&& Double.isFinite(caloric.energy(actual)) && Double.isFinite(caloric.roundoff(actual)),
				"Prospective liquid caloric state or roundoff retained an overflowing specific intermediate");
		var contacts = state.finiteDeviceContacts(List.of(new FiniteDeviceExchangeCoordinator.CellContactRequest(0, 0, 1)), context);
		var body = new FiniteThermalBodyState(0, registry.fingerprint(), device.id(), mass, raw);
		var exchange = FiniteThermalExchange.plan(registry, body, contacts, .01, 8);
		require(!exchange.changed() && exchange.contacts().getFirst().internalEnergyDeltaEu() == 0, "Equivalent caloric paths created equal-temperature device heat flow");
	}

	private static void coolingInversionRetainsEnergyScale() {
		for (double cv : new double[] {Math.scalb(1.0, -1000), 1, Math.scalb(1.0, 1000), 1e308}) {
			var material = new ThermalMaterialDefinition("test:cooling", ThermalMaterialDefinition.CarrierKind.DEVICE,
					"test:cooling", 300, 0, 1, List.of(new ThermalMaterialDefinition.CaloricSegment(1, 500, cv)));
			var state = new ThermalStateFunction(new ThermalRegistry(List.of(material)));
			double energy = state.internalEnergyAtTemperature(0, 1, Map.of(), 299);
			require(energy == -cv && state.stateForInternalEnergy(0, 1, Map.of(), energy).temperatureK() == 299,
					"cooling inversion lost a finite temperature under energy scaling");
		}
		var material = new ThermalMaterialDefinition("test:wide_cooling", ThermalMaterialDefinition.CarrierKind.DEVICE,
				"test:wide_cooling", Math.scalb(1.0, 600), 0, 1,
				List.of(new ThermalMaterialDefinition.CaloricSegment(1, Math.scalb(1.0, 600), Math.scalb(1.0, 424))));
		var state = new ThermalStateFunction(new ThermalRegistry(List.of(material)));
		double target = Math.scalb(1.0, 599), energy = state.internalEnergyAtTemperature(0, 1, Map.of(), target);
		require(energy == -Math.scalb(1.0, 1023)
				&& state.stateForInternalEnergy(0, 1, Map.of(), energy).temperatureK() == target,
				"an overflowing whole-segment capacity rejected a finite cooling state");
	}

	private static void referenceEnergyUsesCanonicalSpeciesOrder() {
		var chemistry = BuiltinChemicalDefinitions.REGISTRY;
		var material = new ThermalMaterialDefinition("test:ordered", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"test:liquid", 300, 0, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(1, 1000, 4)));
		var offsets = new java.util.ArrayList<ThermalSpeciesOffset>();
		String[] ids = new String[3];
		long[] energies = {10_000_000_000_000_000L, -10_000_000_000_000_000L, 1L};
		for (int i = 0; i < ids.length; i++) {
			ids[i] = chemistry.species().get(i).id();
			offsets.add(new ThermalSpeciesOffset("test:offset/" + i, ids[i], material.id(), ExactRational.of(energies[i])));
		}
		var function = new ThermalStateFunction(new ThermalRegistry(List.of(material), offsets, List.of(),
				chemistry.fingerprint(), ""));
		var canonical = new java.util.TreeMap<String, Long>();
		for (String id : ids) canonical.put(id, ChemicalLimits.QUANTA_PER_AU);
		var mixture = ChemicalMixtureRecord.fromStableAmounts(chemistry, canonical, 0);
		long expected = Double.doubleToRawLongBits(function.baseEnergy(0, 1, mixture, chemistry));
		for (int[] order : new int[][]{{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}}) {
			var map = new java.util.LinkedHashMap<String, Long>();
			for (int i : order) map.put(ids[i], ChemicalLimits.QUANTA_PER_AU);
			require(Double.doubleToRawLongBits(function.baseEnergy(0, 1, map)) == expected,
					"Reference energy changed with map iteration order or chemical representation");
			require(Double.doubleToRawLongBits(function.internalEnergyAtTemperature(0, 1, map, 320))
					== Double.doubleToRawLongBits(function.internalEnergyAtTemperature(0, 1, mixture, chemistry, 320)),
					"Caloric state depends on species map iteration order");
		}
	}

	private static void referenceTermsKeepCanonicalRationalRounding() {
		var cache = new ThermalReferenceEnergyCache(); var random = new java.util.Random(728173L);
		for (int i = 0; i < 2048; i++) {
			long numerator = i == 0 ? Long.MIN_VALUE : i == 1 ? -57300 : i == 2 ? 9_007_199_254_740_999L : random.nextInt();
			long denominator = i % 3 == 0 ? 1 : (random.nextLong() & 0xfffffL) + 1;
			var rational = new ExactRational(java.math.BigInteger.valueOf(numerator), java.math.BigInteger.valueOf(denominator));
			var offset = new ThermalSpeciesOffset("test:offset", "test:species", "test:material", rational);
			for (long quanta : new long[] {0, 1, 6_944_444_444_444L, random.nextLong() & Long.MAX_VALUE}) {
				double expected = rational.multiply(quanta).divide(ChemicalLimits.QUANTA_PER_AU).toDouble();
				require(Double.doubleToRawLongBits(cache.energy(offset, quanta)) == Double.doubleToRawLongBits(expected),
						"Reference term changed canonical exact-rational binary64 rounding");
			}
		}
	}

	private static void liquidTemperatureTracksMutableInventory() {
		var material = new ThermalMaterialDefinition("test:thermal", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"test:liquid", 300, 20, 0.5,
				List.of(new ThermalMaterialDefinition.CaloricSegment(1, 1000, 4)));
		var chemistry = BuiltinChemicalDefinitions.REGISTRY;
		var offsets = new java.util.ArrayList<ThermalSpeciesOffset>();
		for (var species : chemistry.species()) offsets.add(new ThermalSpeciesOffset("test:offset/" + species.id().replace(':', '/'),
				species.id(), material.id(), ExactRational.of(species.id().equals(BuiltinChemicalDefinitions.H2O) ? 1000 : 2000)));
		offsets.add(new ThermalSpeciesOffset("test:unused_offset", "aaa:unused", material.id(), ExactRational.of(3000)));
		var registry = new ThermalRegistry(List.of(material), offsets, List.of(), chemistry.fingerprint(), "");
		var function = new ThermalStateFunction(registry);
		class Markers implements ThermalCarrierContext {
			double mass = 2;
			ChemicalMixtureRecord composition;
			ChemicalRegistry currentChemistry = chemistry;
			public int markerCount() { return 1; }
			public int cell(int marker) { return 0; }
			public int fluidMaterialHandle(int marker) { return 0; }
			public double mass(int marker) { return mass; }
			public double volume(int marker) { return 1; }
			public ChemicalMixtureRecord mixture(int marker) { return composition; }
			public ChemicalRegistry chemicalRegistry() { return currentChemistry; }
		}
		var markers = new Markers();
		var column = new LiquidEnergyColumn(registry, function, new cc.ecisr.mmm.units.SolverUnitScale(1),
				new int[] {0}, 300, new ThermalAuditState(ThermalAuditLedger.initialized(SignedDyadic.ZERO)), 1, markers);
		for (int iteration = 0; iteration < 1100; iteration++) {
			markers.mass = iteration + 1;
			double temperature = 310 + iteration % 20;
			column.setEnergy(0, function.internalEnergyAtTemperature(0, markers.mass, Map.of(), temperature));
			require(close(column.temperature(0, markers), temperature), "Mass edits and cache eviction preserve temperature");
			column.setEnergy(0, function.internalEnergyAtTemperature(0, markers.mass, Map.of(), temperature + 5));
			require(close(column.temperature(0, markers), temperature + 5), "Energy edits remain visible on cache hits");
		}
		markers.mass = 2;
		column.resetMarkerTemperature(0, 315, markers);
		require(close(column.temperature(0, markers), 315), "An evicted input is recomputed exactly");
		for (long amount : new long[] {1_000_000_000L, 2_000_000_000L, 1_000_000_000L}) {
			markers.composition = ChemicalMixtureRecord.fromStableAmounts(chemistry,
					Map.of(BuiltinChemicalDefinitions.H2O, amount), 0);
			double energy = function.internalEnergyAtTemperature(0, markers.mass, markers.composition, chemistry, 330);
			var prospective = column.caloricInventory(0, markers.mass, markers.composition, chemistry);
			require(Double.doubleToRawLongBits(prospective.energy(330)) == Double.doubleToRawLongBits(energy)
					&& prospective.temperature(energy) == column.temperatureForInventory(0, markers.mass, markers.composition, energy, chemistry)
					&& prospective.roundoff(330) == column.modeledEnergyRoundoff(0, markers.mass, markers.composition, 330, chemistry),
					"prospective caloric inventory changed reference terms, inversion or roundoff");
			double previousMass = markers.mass; markers.mass *= 2;
			require(prospective.energy(330) == energy, "prospective caloric inventory retained mutable carrier input");
			markers.mass = previousMass;
			column.setEnergy(0, energy);
			require(close(column.temperature(0, markers), 330), "Composition edits select the matching reference energy");
		}
		var shiftedSpecies = new java.util.ArrayList<>(chemistry.species());
		shiftedSpecies.add(new ChemicalSpecies("aaa:unused", Map.of("X", 1), 0, ExactRational.ONE, 0xff000000));
		var shifted = new ChemicalRegistry(shiftedSpecies, chemistry.phases(), chemistry.reactions());
		for (var current : new ChemicalRegistry[] {shifted, chemistry, shifted}) {
			markers.currentChemistry = current;
			double expectedBase = function.baseEnergy(0, markers.mass, markers.composition, current);
			require(Double.doubleToRawLongBits(column.baseEnergy(0, markers)) == Double.doubleToRawLongBits(expectedBase),
					"liquid reference memo retained handles from another chemical registry");
			column.setEnergy(0, function.internalEnergyAtTemperature(0, markers.mass, markers.composition, current, 340));
			require(close(column.temperature(0, markers), 340), "registry replacement left a stale caloric reference");
		}
	}

	private static void coherentPhaseMergeSplitConservesInventories() {
		ChemicalRegistry chemistry = new ChemicalRegistry(List.of(
				new ChemicalSpecies("test:a", Map.of("A", 1), 0, ExactRational.of(1_000_000_000L), 0),
				new ChemicalSpecies("test:b", Map.of("A", 2), 0, ExactRational.of(2_000_000_000L), 0)),
				List.of(new ChemicalPhaseDefinition("test:phase", "test:liquid", List.of("test:a", "test:b"),
						Map.of("test:a", ExactRational.ONE), false)), List.of(new ChemicalReaction(
				"test:combine", ChemicalReaction.Kind.INSTANTANEOUS, "test:phase",
				Map.of("test:a", 2), Map.of("test:b", 1), ExactRational.ZERO)));
		CoherentPhaseStore store = new CoherentPhaseStore(8, 8);
		CoherentPhaseStore.Composition first = new CoherentPhaseStore.Composition(List.of(
				new CoherentPhaseStore.Component("test:a", 8L),
				new CoherentPhaseStore.Component("test:b", 4L)), Double.doubleToRawLongBits(-12.0));
		CoherentPhaseStore.Composition second = new CoherentPhaseStore.Composition(List.of(
				new CoherentPhaseStore.Component("test:a", 8L),
				new CoherentPhaseStore.Component("test:b", 4L)), Double.doubleToRawLongBits(-12.0));
		store.add(new CoherentPhaseStore.Group(3, "test:thermal/solid", 8L, 4.0, first, 32.0));
		store.add(new CoherentPhaseStore.Group(3, "test:thermal/solid", 8L, 4.0, second, 32.0));
		CoherentPhaseStore.Group removed = store.take(3, "test:thermal/solid", 5L, chemistry);
		for (CoherentPhaseStore.Group part : List.of(removed, store.group(0))) {
			double represented = ChemicalMixtureRecord.fromStableAmounts(chemistry,
					part.composition().stableAmounts(), part.composition().massResidual()).massKilograms(chemistry);
			require(close(represented, part.mass()), "coherent split closes each physical mass column");
		}
		require(store.groupCount() == 1 && store.group(0).occupiedVolumeSvq() == 11L
				&& removed.occupiedVolumeSvq() == 5L && close(store.totalMass() + removed.mass(), 8.0)
				&& store.exactEnergy().add(SignedDyadic.of(removed.internalEnergy())).equals(SignedDyadic.of(64.0))
				&& removed.composition().stableAmounts().equals(Map.of("test:a", 5L, "test:b", 2L))
				&& removed.composition().massResidual() + store.group(0).composition().massResidual() == -24.0,
				"coherent phase merge/split did not conserve its independent inventories");
		for (double residual : new double[] {0.0, .125, -.125}) for (boolean tinyRemoved : new boolean[] {false, true}) {
			long total = 10_401_812;
			long quanta = total - 1;
			var composition = new CoherentPhaseStore.Composition(List.of(new CoherentPhaseStore.Component("test:a", quanta)),
					Double.doubleToRawLongBits(residual));
			var source = new CoherentPhaseStore.Group(0, "test:thermal/solid", total, quanta + residual, composition, 1.0);
			var portions = new CoherentPhaseStore(1, 1); portions.add(source);
			long take = tinyRemoved ? 1 : total - 1;
			var previewRemaining = portions.previewRemaining(0, source.phaseId(), take, chemistry);
			var piece = portions.take(0, source.phaseId(), take, chemistry);
			require(portions.group(0).equals(previewRemaining), "Chemical remaining preview differs from committed columns");
			var tiny = tinyRemoved ? piece : portions.group(0);
			var large = tinyRemoved ? portions.group(0) : piece;
			require(tiny.composition().stableAmounts().equals(Map.of("test:a", 1L))
					&& large.composition().stableAmounts().equals(Map.of("test:a", quanta - 1)), "Chemical split changed integer species totals");
			SignedDyadic representedTiny = SignedDyadic.of(1.0).add(SignedDyadic.of(tiny.composition().massResidual()));
			require(representedTiny.equals(SignedDyadic.of(tiny.mass())), "Tiny chemical residual amplified the large mass ULP");
			SignedDyadic residualError = SignedDyadic.of(piece.composition().massResidual())
					.add(SignedDyadic.of(portions.group(0).composition().massResidual())).subtract(SignedDyadic.of(residual));
			if (residualError.signum() < 0) residualError = residualError.negate();
			require(residualError.compareTo(SignedDyadic.of(Math.ulp(large.composition().massResidual()) * .5)) <= 0,
					"Chemical residual exceeded one large-share rounding");
		}
	}

	private static void granularColumnTransactionsConserveEnergy() {
		GranularEnergyColumn column = new GranularEnergyColumn(8, 8);
		column.addActive(10.0); column.addActive(20.0); column.addActive(30.0);
		GranularEnergyColumn.DepositKey first = new GranularEnergyColumn.DepositKey(1, 2, 0);
		GranularEnergyColumn.DepositKey second = new GranularEnergyColumn.DepositKey(2, 2, 0);
		column.addDeposit(first, 5.0); column.addDeposit(first, 5.0);
		SignedDyadic initial = column.exactEnergy();
		column.mergeActive(2, 0);
		double removedActive = column.takeActivePortion(0, 1L, 4L);
		double removedDeposit = column.takeDeposit(first, 1L, 4L);
		column.moveDeposit(first, second, 1L, 3L);
		SignedDyadic removed = SignedDyadic.of(removedActive).add(SignedDyadic.of(removedDeposit));
		require(column.activeCount() == 2 && close(column.activeEnergy(0), 30.0)
				&& close(column.activeEnergy(1), 20.0)
				&& close(column.depositEnergy(first), 5.0)
				&& close(column.depositEnergy(second), 2.5)
				&& column.exactEnergy().add(removed).equals(initial),
				"granular thermal split/merge/move did not conserve raw energy");
		GranularEnergyColumn restored = new GranularEnergyColumn(8, 8);
		restored.restore(column.copyActiveEnergy(), column.depositSnapshot());
		require(restored.exactEnergy().equals(column.exactEnergy())
				&& restored.clear().equals(column.exactEnergy()),
				"granular thermal column handoff/clear did not preserve exact energy");
	}

	private static void condensedTinyPortionsRetainTemperatureAndAudit() {
		var sand = new ThermalMaterialDefinition("test:sand", ThermalMaterialDefinition.CarrierKind.GRANULAR,
				"test:sand", 293.15, 0, .27, List.of(new ThermalMaterialDefinition.CaloricSegment(250, 600, 830)));
		var ice = new ThermalMaterialDefinition("test:ice", ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID,
				"test:ice", 293.15, -375070, 2.22, List.of(new ThermalMaterialDefinition.CaloricSegment(250, 600, 2108)));
		var registry = new ThermalRegistry(List.of(sand, ice)); var function = new ThermalStateFunction(registry);
		for (var material : List.of(sand, ice)) for (double temperature : new double[] {1e-9, 1e-6, 300, 900}) {
			long total = 10_401_873;
			double density = material == sand ? 2600 : 916.7, mass = density * Math.scalb((double) total, -27);
			int handle = registry.materialHandle(material.id());
			double energy = function.internalEnergyAtTemperature(handle, mass, Map.of(), temperature);
			double sourceK = function.stateForInternalEnergy(handle, mass, Map.of(), energy).temperatureK();
			for (long taken : new long[] {1, total - 1, total}) {
				var store = new CoherentPhaseStore(1, 1);
				store.add(new CoherentPhaseStore.Group(0, material.id(), total, mass, null, energy));
				var preview = store.previewTake(0, material.id(), taken, null);
				var expectedRemainingGroup = store.previewRemaining(0, material.id(), taken, null);
				var removed = store.take(0, material.id(), taken, null);
				require(removed.equals(preview), "Coherent preview and commit split differ");
				SignedDyadic sum = SignedDyadic.of(removed.internalEnergy());
				if (taken != total) {
					require(store.group(0).equals(expectedRemainingGroup), "Coherent remaining preview and commit differ");
					var smaller = taken == 1 ? removed : store.group(0);
					double restoredK = function.stateForInternalEnergy(handle, smaller.mass(), Map.of(), smaller.internalEnergy()).temperatureK();
					double bound = 32 * Math.ulp(smaller.internalEnergy()) / (smaller.mass() * material.specificHeatAt(sourceK))
							+ 64 * Math.ulp(material.referenceTemperatureK());
					require(Math.abs(restoredK - sourceK) <= bound, "Coherent small share amplified subtraction error");
					sum = sum.add(store.exactEnergy());
				} else require(store.groupCount() == 0 && removed.internalEnergy() == energy, "Whole coherent take changed raw energy");
				SignedDyadic error = sum.subtract(SignedDyadic.of(energy)); if (error.signum() < 0) error = error.negate();
				require(error.compareTo(SignedDyadic.of(Math.ulp(energy) * .5)) <= 0, "Coherent energy exceeded single large-share rounding");
				SignedDyadic massSum = SignedDyadic.of(removed.mass());
				if (taken != total) massSum = massSum.add(SignedDyadic.of(store.group(0).mass()));
				SignedDyadic massError = massSum.subtract(SignedDyadic.of(mass)); if (massError.signum() < 0) massError = massError.negate();
				require(massError.compareTo(SignedDyadic.of(Math.ulp(mass) * .5)) <= 0, "Coherent mass exceeded single large-share rounding");
				if (material != sand) continue;
				var column = new GranularEnergyColumn(1, 1); var key = new GranularEnergyColumn.DepositKey(0, 0, 0);
				column.addActive(energy); column.addDeposit(key, energy);
				double expectedRemaining = column.previewDepositRemaining(key, taken, total);
				double expectedRemoved = column.previewDepositTake(key, taken, total);
				var audit = new ThermalAuditState(ThermalAuditLedger.initialized(column.exactEnergy()));
				var owner = new GranularEnergyState(registry, column, new CoherentPhaseStore(1, 1), function,
						new int[] {handle}, 293.15, audit);
				require(owner.commitExternalDepositTake(key, taken, total) == expectedRemoved, "Granular export differs from preview");
				require(column.exactEnergy().equals(audit.ledger().expectedInventory()),
						"Granular external take audit did not use actual remaining U");
				if (taken != total) {
					require(column.depositEnergy(key) == expectedRemaining, "Granular commit re-subtracted its small remaining energy");
					double smallerU = taken == 1 ? expectedRemoved : expectedRemaining;
					require(smallerU == energy * (1.0 / total), "Granular small share lost its direct proportional value");
					require(column.takeActivePortion(0, taken, total) == expectedRemoved && column.activeEnergy(0) == expectedRemaining,
							"Active and deposit energy splits differ");
					var refunded = new GranularEnergyColumn(1, 2); refunded.addDeposit(key, energy);
					var target = new GranularEnergyColumn.DepositKey(1, 0, 0);
					refunded.moveDeposit(key, target, taken, total);
					require(refunded.depositEnergy(key) == expectedRemaining && refunded.depositEnergy(target) == expectedRemoved,
							"Deposit move does not share take's two U columns");
					refunded.moveDeposit(target, key, taken, taken);
					require(refunded.depositCount() == 1 && close(refunded.depositEnergy(key), energy), "Returned deposit portion did not recombine energy");
				} else require(column.depositCount() == 0 && column.previewActiveRemaining(0, total, total) == 0
						&& column.removeActiveSwap(0) == energy, "Whole granular take changed raw energy");
			}
		}
		var subnormal = new CoherentPhaseStore(1, 1);
		var original = new CoherentPhaseStore.Group(0, ice.id(), 2, Double.MIN_VALUE, null, 1);
		subnormal.add(original);
		try { subnormal.take(0, ice.id(), 1, null); throw new AssertionError("Unrepresentable positive coherent share accepted"); }
		catch (IllegalArgumentException expected) { require(subnormal.group(0).equals(original), "Rejected coherent split changed original columns"); }
	}

	private static void granularBatchDepositPreflightIsAtomic() {
		var first = new GranularEnergyColumn.DepositKey(0, 0, 0);
		var second = new GranularEnergyColumn.DepositKey(0, 1, 0);
		GranularEnergyColumn budget = new GranularEnergyColumn(4, 1);
		budget.addActive(1); budget.addActive(2);
		double[] before = budget.copyActiveEnergy();
		var separate = List.of(new cc.ecisr.mmm.granular.GranularInventory.DepositTransfer(1, 0, 0, 0),
				new cc.ecisr.mmm.granular.GranularInventory.DepositTransfer(0, 0, 1, 0));
		require(budget.planActiveToDeposits(separate) == null && java.util.Arrays.equals(before, budget.copyActiveEnergy())
				&& budget.depositCount() == 0, "batch must preflight all new deposit keys together");
		GranularEnergyColumn overflow = new GranularEnergyColumn(4, 4);
		overflow.addDeposit(first, Double.MAX_VALUE / 2); overflow.addActive(Double.MAX_VALUE / 2);
		overflow.addActive(Double.MAX_VALUE / 2);
		var same = List.of(new cc.ecisr.mmm.granular.GranularInventory.DepositTransfer(1, 0, 0, 0),
				new cc.ecisr.mmm.granular.GranularInventory.DepositTransfer(0, 0, 0, 0));
		SignedDyadic exactBefore = overflow.exactEnergy();
		require(overflow.planActiveToDeposits(same) == null && overflow.activeCount() == 2
				&& overflow.exactEnergy().equals(exactBefore), "batch U overflow must retain all selected carriers");
		GranularEnergyColumn cancellation = new GranularEnergyColumn(4, 4);
		cancellation.addDeposit(first, Double.MAX_VALUE); cancellation.addActive(-Double.MAX_VALUE);
		cancellation.addActive(Double.MAX_VALUE);
		var cancellationPlan = cancellation.planActiveToDeposits(same);
		require(cancellationPlan != null, "signed U cancellation must use the final sum before testing overflow");
		cancellation.commitActiveToDeposits(cancellationPlan);
		require(cancellation.activeCount() == 0 && cancellation.depositEnergy(first) == Double.MAX_VALUE
				&& cancellationPlan.roundoff.equals(SignedDyadic.ZERO), "signed batch U was changed during deposition");
		GranularEnergyColumn rounding = new GranularEnergyColumn(4, 4);
		rounding.addDeposit(first, 1.0e16); rounding.addActive(1); rounding.addActive(1);
		var roundingPlan = rounding.planActiveToDeposits(same); rounding.commitActiveToDeposits(roundingPlan);
		require(rounding.depositEnergy(first) == 1.0e16 + 2 && roundingPlan.roundoff.equals(SignedDyadic.ZERO),
				"deposit U must round the complete sum once");
		GranularEnergyColumn swaps = new GranularEnergyColumn(8, 4);
		for (double energy : new double[] {11, 22, 33, 44, 55}) swaps.addActive(energy);
		var swapTransfers = List.of(new cc.ecisr.mmm.granular.GranularInventory.DepositTransfer(3, 0, 0, 0),
				new cc.ecisr.mmm.granular.GranularInventory.DepositTransfer(1, 0, 0, 0));
		var swapPlan = swaps.planActiveToDeposits(swapTransfers); swaps.commitActiveToDeposits(swapPlan);
		require(java.util.Arrays.equals(swaps.copyActiveEnergy(), new double[] {11, 55, 33})
				&& swaps.depositEnergy(first) == 66, "descending batch swaps must preserve unselected ordinal energy");
		GranularEnergyColumn stale = new GranularEnergyColumn(4, 4); stale.addActive(1); stale.addActive(2);
		var stalePlan = stale.planActiveToDeposits(same); stale.setActiveEnergy(1, 3);
		boolean rejected = false; try { stale.commitActiveToDeposits(stalePlan); } catch (IllegalStateException expected) { rejected = true; }
		require(rejected && stale.activeCount() == 2 && stale.depositCount() == 0, "stale batch must fail before swap removals");
		GranularEnergyColumn stateColumn = new GranularEnergyColumn(4, 4); stateColumn.addActive(0.1); stateColumn.addActive(0.2);
		ThermalAuditState audit = new ThermalAuditState(ThermalAuditLedger.initialized(stateColumn.exactEnergy()));
		GranularEnergyState state = new GranularEnergyState(null, stateColumn, null, null, null, 300, audit);
		var statePlan = state.planActiveToDeposits(same); var savedEnergy = stateColumn.exactEnergy();
		statePlan.commit();
		require(stateColumn.exactEnergy().subtract(savedEnergy).equals(audit.ledger().roundoffResidual())
				&& state.revision() == 1, "batch state must publish raw U and its exact roundoff together");
		GranularEnergyColumn auditColumn = new GranularEnergyColumn(4, 4); auditColumn.addActive(1); auditColumn.addActive(2);
		ThermalAuditState changedAudit = new ThermalAuditState(ThermalAuditLedger.initialized(auditColumn.exactEnergy()));
		GranularEnergyState auditState = new GranularEnergyState(null, auditColumn, null, null, null, 300, changedAudit);
		var auditPlan = auditState.planActiveToDeposits(same); changedAudit.update(ledger -> ledger.withRoundoff(SignedDyadic.of(1, 0)));
		rejected = false; try { auditPlan.commit(); } catch (IllegalStateException expected) { rejected = true; }
		require(rejected && auditColumn.activeCount() == 2 && auditColumn.depositCount() == 0 && auditState.revision() == 0,
				"changed audit must reject a complete batch before inventory mutation");
	}

	private static void dyadicAuditSumsRetainFinalBudget() {
		var huge = SignedDyadic.of(1, 40_000);
		var terms = new SignedDyadic[] {huge, SignedDyadic.of(10), huge.negate(), SignedDyadic.of(-3), SignedDyadic.ZERO, SignedDyadic.ZERO};
		requireDyadicPermutations(terms, 0, SignedDyadic.of(7));
		var max = SignedDyadic.of(1, Integer.MAX_VALUE);
		requireDyadicPermutations(new SignedDyadic[] {max, max, max.negate(), max.negate(), SignedDyadic.of(9), SignedDyadic.ZERO}, 0, SignedDyadic.of(9));
		int budget = SignedDyadic.MAX_SIGNIFICAND_BITS;
		var ones = new SignedDyadic(java.math.BigInteger.ONE.shiftLeft(budget).subtract(java.math.BigInteger.ONE), 0);
		require(SignedDyadic.of(1, budget).subtract(ones).equals(SignedDyadic.of(1))
				&& SignedDyadic.sum(SignedDyadic.of(1, budget), SignedDyadic.of(-1)).equals(ones),
				"dyadic budget endpoint lost exact borrowing cancellation");
		var min = SignedDyadic.of(1, Integer.MIN_VALUE);
		require(SignedDyadic.sum(min, max, max.negate()).equals(min), "remote cancellation allocated or lost the surviving extreme exponent");
		for (Runnable invalid : List.<Runnable>of(
				() -> min.add(max), () -> SignedDyadic.sum(min, max),
				() -> SignedDyadic.sum(SignedDyadic.of(1, budget + 1), SignedDyadic.of(-1)),
				() -> SignedDyadic.sum(max, max))) {
			try { invalid.run(); throw new AssertionError("unrepresentable final dyadic sum was accepted"); }
			catch (IllegalArgumentException | ArithmeticException expected) { }
		}
	}

	private static void requireDyadicPermutations(SignedDyadic[] values, int offset, SignedDyadic expected) {
		if (offset == values.length) {
			require(SignedDyadic.sum(values).equals(expected), "dyadic final sum depends on field order"); return;
		}
		for (int i = offset; i < values.length; i++) {
			var old = values[offset]; values[offset] = values[i]; values[i] = old;
			requireDyadicPermutations(values, offset + 1, expected);
			old = values[offset]; values[offset] = values[i]; values[i] = old;
		}
	}

	private static void dyadicConversionRoundsInFinalBinary64Unit() {
		dyadicAuditSumsRetainFinalBudget();
		require(SignedDyadic.of(0.1).multiply(SignedDyadic.of(-3.0)).equals(SignedDyadic.of(0.1).negate()
				.add(SignedDyadic.of(0.1).negate()).add(SignedDyadic.of(0.1).negate()))
				&& SignedDyadic.of(Double.MIN_VALUE).multiply(SignedDyadic.of(1, 1074)).equals(SignedDyadic.of(1.0))
				&& SignedDyadic.of(1, Integer.MAX_VALUE).multiply(SignedDyadic.ZERO).equals(SignedDyadic.ZERO),
				"dyadic products must keep exact signed values and normalize zero before exponent addition");
		SignedDyadic minimum = SignedDyadic.of(Double.MIN_VALUE);
		require(SignedDyadic.of(Double.MAX_VALUE).add(minimum).toDouble() == Double.MAX_VALUE
				&& SignedDyadic.of(0.1).add(minimum).toDouble() == 0.1, "wide finite dyadic sum overflowed its intermediate significand");
		SignedDyadic half = SignedDyadic.of(1, -1075);
		require(Double.doubleToRawLongBits(half.toDouble()) == 0
				&& Double.doubleToRawLongBits(half.negate().toDouble()) == Long.MIN_VALUE
				&& half.add(SignedDyadic.of(1, -2000)).toDouble() == Double.MIN_VALUE,
				"subnormal half ties and signed underflow must round once");
		require(SignedDyadic.of(1.0).add(SignedDyadic.of(1, -53)).toDouble() == 1.0
				&& SignedDyadic.of(Math.nextUp(1.0)).add(SignedDyadic.of(1, -53)).toDouble() == Math.nextUp(Math.nextUp(1.0)),
				"normal halfway cases must choose the even significand");
		SignedDyadic maximum = SignedDyadic.of(Double.MAX_VALUE), overflowHalf = SignedDyadic.of(1, 970);
		require(Double.isInfinite(maximum.add(overflowHalf).toDouble())
				&& maximum.add(overflowHalf).subtract(minimum).toDouble() == Double.MAX_VALUE,
				"overflow midpoint must use the final rounding bit");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static boolean close(double first, double second) {
		return Math.abs(first - second) <= Math.max(1.0E-10,
				Math.max(Math.ulp(first), Math.ulp(second)) * 8.0);
	}
}
