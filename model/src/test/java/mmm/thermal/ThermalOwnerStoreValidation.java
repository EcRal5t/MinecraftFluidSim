package mmm.thermal;

import mmm.units.SignedDyadic;
import mmm.chemistry.*;

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
		referenceCoefficientsPreserveThermalScale();
		referenceEnergyUsesCanonicalSpeciesOrder();
		exactAggregateEnergySurvivesCaloricHandoffs();
		caloricKnotsRetainTheirOwnEnergyScale();

		caloricEndpointsAndPortionsPreserveFiniteStates();
		coolingInversionRetainsEnergyScale();
		finiteDevicesRetainActualOwnerTemperatures();
	}

	private static void exactAggregateEnergySurvivesCaloricHandoffs() {

		phaseSnapshotsKeepOriginalOwnerRevision();
		finiteCellExchangeRetainsExactBaseline();
		cellEnergyPreflightPreservesDerivedState();
		for (double reference : new double[] {3e6, -3e6}) for (double smallCv : new double[] {1, 1.0001})
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
				double expected = 300 + smallEnergy / (1 + smallCv), tolerance = 1e-4;
				require(Math.abs(function.stateForInventory(inventory, before).temperatureK() - expected) <= tolerance,
						"Exact LTE input rounded before reference removal");
				state.preparePhaseScan(context);
				require(Math.abs(state.phaseScanTemperatureInCell(0) - expected) <= tolerance
						&& Math.abs(state.observeTemperatureInCell(0, context) - expected) <= tolerance
						&& Math.abs(state.trialPhaseCellEnergy(state.preparePhaseCandidateCell(0, context), inventory).temperatureK() - expected) <= tolerance
						&& Math.abs(state.trialPhaseCellEnergy(state.preparePhaseCandidateCell(0, context), inventory, 0, 0).temperatureK() - expected) <= tolerance,
						"Actual cell, observation and phase candidates used different sensible energy");
				require(state.exactEnergy(2).equals(before) && state.auditLedger().equals(audit) && state.thermalRevision().equals(revision),
						"Read-only caloric handoff changed owner energy or audit");
				state.repartitionCellEnergyConserving(0, context, before);
				double expectedSmall = function.internalEnergyAtTemperature(1, 1, Map.of(), expected);
				require(Math.abs(state.liquidInternalEnergy(reverse ? 0 : 1) - expectedSmall) <= 1e-4 * smallCv
						&& state.exactEnergy(2).equals(state.auditLedger().expectedInventory())
						&& state.auditLedger().roundoffResidual().equals(state.exactEnergy(2).subtract(before)),
						"Conservative LTE commit lost the small energy or failed to find a valid remainder carrier");
				double actualTemperature = function.stateForInventory(inventory, state.exactEnergy(2)).temperatureK();
				require(Math.abs(state.phaseScanTemperatureInCell(0) - actualTemperature) <= 1e-4
						&& Math.abs(state.observeTemperatureInCell(0, context)-actualTemperature)<1e-4,
						"Committed cell retained the prospective temperature instead of deriving actual owner energy");
			}

	}

	private static ThermalCarrierContext thermalHandoffContext() {
		return new ThermalCarrierContext() {
			private final double[] liquidEnergy = new double[2];
			private long liquidRevision;
			@Override public double liquidInternalEnergy(int ordinal) { return liquidEnergy[ordinal]; }
			@Override public void setLiquidInternalEnergy(int ordinal, double energy) { liquidEnergy[ordinal] = energy; liquidRevision++; }
			@Override public long liquidInventoryRevision() { return liquidRevision; }
			public int markerCount() { return 2; } public int cell(int marker) { return 0; }
			public int fluidMaterialHandle(int marker) { return marker; } public double mass(int marker) { return 8; }
			public double volume(int marker) { return .5; } public ChemicalMixtureRecord mixture(int marker) { return null; }
			public ChemicalRegistry chemicalRegistry() { return null; }
		};
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
		var inventory = List.of(new ThermalStateFunction.InventoryPortion(0, 1, Map.of()),
				new ThermalStateFunction.InventoryPortion(1, 1, Map.of()));
		var staleTrial = state.trialPhaseCellEnergy(stale, inventory);
		var foreignTrial = foreign.trialPhaseCellEnergy(other, inventory);
		state.setTemperatureInCell(0, 301, context);
		SignedDyadic energy = state.exactEnergy(2); var audit = state.auditLedger(); var revision = state.thermalRevision();
		for (var snapshot : List.of(stale, other)) for (Runnable request : new Runnable[] {
				() -> state.trialPhaseCellEnergy(snapshot, inventory),
				() -> state.trialPhaseCellEnergy(snapshot, inventory, 0, 0)}) {
			try { request.run(); throw new AssertionError("A stale or foreign phase-cell snapshot accepted"); }
			catch (IllegalStateException expected) {
				require(state.exactEnergy(2).equals(energy) && state.auditLedger().equals(audit)
						&& state.thermalRevision().equals(revision) && state.observeTemperatureInCell(0, context) == 301,
						"Rejected phase-cell snapshot changed its current owner energy or audit");
			}
		}
		for (var trial : List.of(staleTrial, foreignTrial)) {
			try { state.planPhaseCellEnergy(trial); throw new AssertionError("A stale or foreign thermal trial accepted"); }
			catch (IllegalStateException expected) {
				require(state.exactEnergy(2).equals(energy) && state.auditLedger().equals(audit)
						&& state.thermalRevision().equals(revision), "Rejected thermal trial changed its current owners");
			}
		}
		var current = state.preparePhaseCandidateCell(0, context);
		for (var trial : List.of(state.trialPhaseCellEnergy(current, inventory), state.trialPhaseCellEnergy(current, inventory, 0, 0))) {
			var plan = state.planPhaseCellEnergy(trial);
			state.requirePhaseCellEnergyCurrent(plan);
			require(Math.abs(plan.temperatureK() - trial.temperatureK()) <= 1e-4,
					"Stored phase energy changed the macroscopic trial temperature");
		}
	}

	private static void finiteCellExchangeRetainsExactBaseline() {
		for (double reference : new double[] {1e6, -1e6}) for (boolean reverse : new boolean[] {false, true})
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
				state.preparePhaseScan(context);
				require(plan.requestedDelta().equals(SignedDyadic.of(delta))
						&& state.phaseScanTemperatureInCell(0) == 400 && state.exactEnergy(2).equals(before)
						&& state.thermalRevision().equals(revision), "Finite cell preflight rounded or edited its actual baseline");
				state.commitFiniteDeviceCellExchange(plan, context);
				require(state.liquidInternalEnergy(reverse ? 0 : 1) == (200 + delta / 2)
						&& state.auditLedger().finiteDeviceExchange().equals(SignedDyadic.of(delta))
						&& state.auditLedger().roundoffResidual().equals(state.exactEnergy(2).subtract(before).subtract(SignedDyadic.of(delta)))
						&& state.exactEnergy(2).equals(state.auditLedger().expectedInventory()),
						"Finite cell exchange lost a resolvable transfer or solved a rounded target");
				state.preparePhaseScan(context);
				double actualTemperature = 300 + state.exactEnergy(2).subtract(SignedDyadic.of(reference)).toDouble() / 2;
				require(state.phaseScanTemperatureInCell(0) == actualTemperature
						&& Math.abs(state.observeTemperatureInCell(0, context)-actualTemperature)<1e-4,
						"Finite commit cached a requested temperature different from actual owner energy");
			}
		var positive = new ThermalMaterialDefinition("test:remote/a", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"test:remote/a", 300, 1e6, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(10, 1000, 1)));
		var negative = new ThermalMaterialDefinition("test:remote/b", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"test:remote/b", 300, -1e6, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(10, 1000, 1)));
		var small = new ThermalMaterialDefinition("test:remote/c", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"test:remote/c", 300, 0, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(10, 1000, 1)));
		var context = new ThermalCarrierContext() {
			private final double[] liquidEnergy = new double[3];
			private long liquidRevision;
			@Override public double liquidInternalEnergy(int ordinal) { return liquidEnergy[ordinal]; }
			@Override public void setLiquidInternalEnergy(int ordinal, double energy) { liquidEnergy[ordinal] = energy; liquidRevision++; }
			@Override public long liquidInventoryRevision() { return liquidRevision; }
			public int markerCount() { return 3; } public int cell(int marker) { return marker == 2 ? 1 : 0; }
			public int fluidMaterialHandle(int marker) { return marker; } public double mass(int marker) { return 8; }
			public double volume(int marker) { return .5; } public ChemicalMixtureRecord mixture(int marker) { return null; }
			public ChemicalRegistry chemicalRegistry() { return null; }
		};
		var state = ThermalDomainState.restoreTarget(new ThermalRegistry(List.of(positive, negative, small)),
				new int[] {0, 1, 2}, new int[0], 300, 3, 2, 1, 1, .5F, context, null, 1,
				new double[] {1e6 + 256, -1e6 - 128, 100});
		var before = state.exactEnergy(3); state.restoreAuditLedger(ThermalAuditLedger.initialized(before));
		var plan = state.planFiniteDeviceCellExchange(List.of(new FiniteDeviceExchangeCoordinator.CellEnergyDelta(0, 0),
				new FiniteDeviceExchangeCoordinator.CellEnergyDelta(1, 1)), context);
		state.commitFiniteDeviceCellExchange(plan, context);
		require(state.liquidInternalEnergy(0) == 1e6 + 256 && state.liquidInternalEnergy(1) == -1e6 - 128
				&& state.liquidInternalEnergy(2) == 101 && state.auditLedger().roundoffResidual().equals(SignedDyadic.ZERO)
				&& state.exactEnergy(3).equals(state.auditLedger().expectedInventory()),
				"Local finite exchange tried to redistribute an untouched, unrepresentable remote LTE cell");
	}

	private static void cellEnergyPreflightPreservesDerivedState() {
		var ordinary = new ThermalMaterialDefinition("test:bulk/a", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"test:bulk/a", 300, 0, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(10, 1000, 1)));
		var energies = new double[] {100, 1e6 + 256, -1e6 - 128};
		var grid = new ThermalCellGrid(2);
		grid.rebuild(new ThermalCellGrid.CarrierAccess() {
			public int carrierCount() { return 3; } public int cell(int carrier) { return carrier == 0 ? 0 : 1; }
			public ThermalMaterialDefinition material(int carrier) { return ordinary; } public double mass(int carrier) { return 1; }
			public double volume(int carrier) { return .25; }
			public double baseEnergy(int carrier) { return carrier == 0 ? 0 : carrier == 1 ? 1e6 : -1e6; }
			public double internalEnergy(int carrier) { return energies[carrier]; }
			public void setInternalEnergy(int carrier, double value) { energies[carrier] = value; }
		});
		var range = grid.observedTemperatureRange();
		for (boolean apply : new boolean[] {false, true}) {
			try {
				if (apply) grid.setCellInternalEnergies(new double[] {101, -1000});
				else grid.validateCellInternalEnergies(new double[] {101, -1000});
				throw new AssertionError("Nonpositive-temperature bulk target accepted");
			} catch (IllegalArgumentException expected) {
				require(energies[0] == 100 && energies[1] == 1e6 + 256 && energies[2] == -1e6 - 128
						&& grid.temperature(0) == 400 && grid.temperature(1) == 364
						&& grid.internalEnergy(0) == 100 && grid.internalEnergy(1) == 128
						&& grid.observedTemperatureRange().equals(range), "Failed bulk preflight edited inventory or derived cells");
			}
		}
		grid.validateCellInternalEnergies(new double[] {101, 130});
		require(energies[0] == 100 && energies[1] == 1e6 + 256 && energies[2] == -1e6 - 128
				&& grid.temperature(0) == 400 && grid.temperature(1) == 364 && grid.observedTemperatureRange().equals(range),
				"Successful bulk preflight published prospective owner energies or temperatures");
		grid.setCellInternalEnergies(new double[] {101, 130});
		require(energies[0] == 101 && SignedDyadic.of(energies[1]).add(SignedDyadic.of(energies[2])).equals(SignedDyadic.of(130))
				&& grid.internalEnergy(1) == 130 && grid.temperature(0) == 401 && grid.temperature(1) == 365,
				"Bulk commit lost the preflight temperature or retained a prospective energy cache");
		var cold = new ThermalMaterialDefinition("test:positive/raw", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"test:positive/raw", 363, 0, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(1, 1000, 1)));
		var coldContext = new ThermalCarrierContext() {
			private final double[] liquidEnergy = new double[1];
			private long liquidRevision;
			@Override public double liquidInternalEnergy(int ordinal) { return liquidEnergy[ordinal]; }
			@Override public void setLiquidInternalEnergy(int ordinal, double energy) { liquidEnergy[ordinal] = energy; liquidRevision++; }
			@Override public long liquidInventoryRevision() { return liquidRevision; }
			public int markerCount() { return 1; } public int cell(int marker) { return 0; }
			public int fluidMaterialHandle(int marker) { return 0; } public double mass(int marker) { return 8; }
			public double volume(int marker) { return .5; } public ChemicalMixtureRecord mixture(int marker) { return null; }
			public ChemicalRegistry chemicalRegistry() { return null; }
		};
		var state = ThermalDomainState.restoreTarget(new ThermalRegistry(List.of(cold)), new int[] {0}, new int[0],
				363, 1, 1, 1, 1, .5F, coldContext, null, 1, new double[] {0});
		var before = state.exactEnergy(1); state.restoreAuditLedger(ThermalAuditLedger.initialized(before));
		state.preparePhaseScan(coldContext);
		var audit = state.auditLedger(); var revision = state.thermalRevision();
		try {
			state.planFiniteDeviceCellExchange(List.of(new FiniteDeviceExchangeCoordinator.CellEnergyDelta(0, -364)), coldContext);
			throw new AssertionError("Finite device exchange accepted a nonpositive-temperature owner");
		} catch (IllegalArgumentException expected) {
			require(state.liquidInternalEnergy(0) == 0 && state.exactEnergy(1).equals(before)
					&& state.phaseScanTemperatureInCell(0) == 363 && state.auditLedger().equals(audit)
					&& state.thermalRevision().equals(revision), "Invalid caloric owner failed after a partial publication");
		}
	}

	private static void caloricKnotsRetainTheirOwnEnergyScale() {
		for(boolean reverse:new boolean[]{false,true})for(double reference:new double[]{0,-3e6,3e6}) {
			var material=new ThermalMaterialDefinition("test:knot/a",ThermalMaterialDefinition.CarrierKind.LIQUID,
					"test:knot/a",300,reference,0,List.of(new ThermalMaterialDefinition.CaloricSegment(250,300,reverse?4184:2108),
					new ThermalMaterialDefinition.CaloricSegment(300,600,reverse?2108:4184)));
			var function=new ThermalStateFunction(new ThermalRegistry(List.of(material)));
			for(double temperature:new double[]{1,250,299,300,301,600,1500,3000}) {
				double energy=function.internalEnergyAtTemperature(0,125,Map.of(),temperature);
				var before=SignedDyadic.of(energy);
				require(Math.abs(function.stateForInternalEnergy(0,125,Map.of(),energy).temperatureK()-temperature)<1e-4,
						"Piecewise caloric inversion changed macroscopic temperature");
				require(Math.abs(function.stateForInventory(List.of(new ThermalStateFunction.InventoryPortion(0,125,Map.of())),before).temperatureK()-temperature)<1e-4,
						"Cell caloric inversion changed its piecewise response");
			}
		}
	}

	private static void caloricEndpointsAndPortionsPreserveFiniteStates() {
		for(double mass:new double[]{.01,1,125})for(double reference:new double[]{0,-3e6,3e6}) {
			var material=new ThermalMaterialDefinition("test:handoff/a",ThermalMaterialDefinition.CarrierKind.LIQUID,
					"test:handoff/a",300,reference,0,List.of(new ThermalMaterialDefinition.CaloricSegment(100,1000,2000),
					new ThermalMaterialDefinition.CaloricSegment(1000,1500,4000)));
			var function=new ThermalStateFunction(new ThermalRegistry(List.of(material)));
			for(double temperature:new double[]{1,250,500,1500,3000})
				requireCaloricHandoff(material,mass,function.internalEnergyAtTemperature(0,mass,Map.of(),temperature),temperature);
		}
	}

	private static void requireCaloricHandoff(ThermalMaterialDefinition material, double mass, double raw, double expected) {
		var device = new ThermalMaterialDefinition("test:handoff/b", ThermalMaterialDefinition.CarrierKind.DEVICE,
				"test:handoff/b", material.referenceTemperatureK(), material.referenceSpecificEnergyJPerKg(), 0, material.caloricSegments());
		var registry = new ThermalRegistry(List.of(material, device)); var function = new ThermalStateFunction(registry);
		var context = new ThermalCarrierContext() {
			private final double[] liquidEnergy = new double[1];
			private long liquidRevision;
			@Override public double liquidInternalEnergy(int ordinal) { return liquidEnergy[ordinal]; }
			@Override public void setLiquidInternalEnergy(int ordinal, double energy) { liquidEnergy[ordinal] = energy; liquidRevision++; }
			@Override public long liquidInventoryRevision() { return liquidRevision; }
			public int markerCount() { return 1; } public int cell(int p) { return 0; } public int fluidMaterialHandle(int p) { return 0; }
			public double mass(int p) { return mass * 8; } public double volume(int p) { return .5; }
			public ChemicalMixtureRecord mixture(int p) { return null; } public ChemicalRegistry chemicalRegistry() { return null; }
			public void observationSupport(int p, SupportConsumer consumer) { consumer.accept(0, .1); consumer.accept(1, .9); }
		};
		var state = ThermalDomainState.restoreTarget(registry, new int[] {0}, new int[0], material.referenceTemperatureK(),
				2, 1, 1, 2, .5F, context, null, 1, new double[] {raw});
		state.preparePhaseScan(context); state.prepareSpatialObservation(context); double tolerance = 1e-4;
		require(Math.abs(state.temperature(0, context) - expected) <= tolerance
				&& Math.abs(state.phaseScanTemperatureInCell(0) - expected) <= tolerance
				&& Math.abs(state.observeTemperatureInCell(0, context) - expected) <= tolerance
				&& Math.abs(state.spatialTemperatureInCell(0) - expected) <= tolerance
				&& Math.abs(state.spatialTemperatureInCell(1) - expected) <= tolerance,
				"Exact caloric endpoint or shared spatial mass/energy coefficient lost a finite owner temperature");
		double actual = state.temperature(0, context), forward = function.internalEnergyAtTemperature(0, mass, Map.of(), actual);
		require(Double.isFinite(forward) && Math.abs(forward - raw) <= 1e-4 * mass * material.specificHeatAt(expected), "Finite final owner energy was lost to an intermediate overflow");
		var caloric = state.liquid().caloricInventory(0, mass * 8, null, null);
		require(Math.abs(caloric.temperature(SignedDyadic.of(raw)) - actual) <= tolerance
				&& Double.isFinite(caloric.energy(actual)) && Double.isFinite(caloric.roundoff(actual)),
				"Prospective liquid caloric state or roundoff retained an overflowing specific intermediate");
		long bodyMass;
		try { bodyMass = mmm.units.Mass.fromKilograms(mass); }
		catch (ArithmeticException outsideInventoryRange) { return; }
		if (bodyMass == 0L || mmm.units.Mass.kilograms(bodyMass) != mass) return;
		var contacts = state.finiteDeviceContacts(List.of(new FiniteDeviceExchangeCoordinator.CellContactRequest(0, 0, 1)), context);
		var body = new FiniteThermalBodyState(0, registry.fingerprint(), device.id(), bodyMass, raw);
		var exchange = FiniteThermalExchange.plan(registry, body, contacts, .01, 8);
		require(!exchange.changed() && exchange.contacts().getFirst().internalEnergyDeltaJ() == 0, "Equivalent caloric paths created equal-temperature device heat flow");
	}

	private static void finiteDevicesRetainActualOwnerTemperatures(){
		var cold=new ThermalMaterialDefinition("test:device/a",ThermalMaterialDefinition.CarrierKind.LIQUID,"test:device/a",300,0,0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(1,1000,1000)));
		var hot=new ThermalMaterialDefinition("test:device/b",ThermalMaterialDefinition.CarrierKind.LIQUID,"test:device/b",300,0,1,cold.caloricSegments());
		var device=new ThermalMaterialDefinition("test:device/wall",ThermalMaterialDefinition.CarrierKind.DEVICE,"test:device/wall",300,0,1,cold.caloricSegments());
		var registry=new ThermalRegistry(List.of(cold,hot,device));var context=thermalHandoffContext();
		var state=ThermalDomainState.restoreTarget(registry,new int[]{0,1},new int[0],300,2,1,1,1,.5F,context,null,8,new double[]{0,200000});
		state.restoreAuditLedger(ThermalAuditLedger.initialized(state.exactEnergy(2)));
		state.preparePhaseScan(context);var revision=state.thermalRevision();
		var contacts=state.finiteDeviceContacts(List.of(new FiniteDeviceExchangeCoordinator.CellContactRequest(0,0,1)),context);
		require(state.matchesThermalRevision(revision),"Device observation mutated a phase cache or owner revision");
		state.preparePhaseScan(context);state.preparePhaseCandidateCell(0,context);
		var body=new FiniteThermalBodyState(0,registry.fingerprint(),device.id(),mmm.units.Mass.fromKilograms(1),300000);
		var exchange=FiniteThermalExchange.plan(registry,body,contacts,1e-8,8);var delta=exchange.contacts().getFirst();
		state.commitFiniteDeviceCellExchange(state.planFiniteDeviceCellExchange(List.of(
				new FiniteDeviceExchangeCoordinator.CellEnergyDelta(0,delta.internalEnergyDeltaJ(),delta.carriers())),context),context);
		require(state.liquidInternalEnergy(0)==0&&state.liquidInternalEnergy(1)>200000&&state.liquidInternalEnergy(1)-200000<.001
				&&state.exactEnergy(2).equals(state.auditLedger().expectedInventory()),"Minute device heat equilibrated isolated hot/cold owners");
		var segmented=new ThermalMaterialDefinition("test:device/segmented",ThermalMaterialDefinition.CarrierKind.LIQUID,"test:device/segmented",300,0,1,
				List.of(new ThermalMaterialDefinition.CaloricSegment(1,300,10),new ThermalMaterialDefinition.CaloricSegment(300,1000,1000)));
		var r=new ThermalRegistry(List.of(device,segmented));
		var coldBody=new FiniteThermalBodyState(0,r.fingerprint(),device.id(),mmm.units.Mass.fromKilograms(1),-50000);
		for(double dt:new double[]{.001,1000000}){
			var contact=new FiniteThermalExchange.Contact(0,1000,List.of(new FiniteThermalExchange.Carrier(0,segmented,1,0,0,1)));
			var plan=FiniteThermalExchange.plan(r,coldBody,List.of(contact),dt,8);
			require(plan.contacts().getFirst().finalTemperatureK()<300&&plan.contacts().getFirst().finalTemperatureK()>=250
					&&plan.after().internalEnergyJ()>coldBody.internalEnergyJ(),"Finite contact could not cool from a caloric knot across its lower segment");
		}
	}

	private static void coolingInversionRetainsEnergyScale() {
		for(double cv:new double[]{1,850,4184,10000}) {
			var material=new ThermalMaterialDefinition("test:cooling",ThermalMaterialDefinition.CarrierKind.DEVICE,
					"test:cooling",300,0,1,List.of(new ThermalMaterialDefinition.CaloricSegment(1,3000,cv)));
			var function=new ThermalStateFunction(new ThermalRegistry(List.of(material)));
			for(double temperature:new double[]{1,299,300,1500,3000}) {
				double energy=function.internalEnergyAtTemperature(0,1,Map.of(),temperature);
				require(Math.abs(function.stateForInternalEnergy(0,1,Map.of(),energy).temperatureK()-temperature)<1e-4,
						"Heating or cooling lost its ordinary caloric response");
			}
		}
	}

	private static void referenceEnergyUsesCanonicalSpeciesOrder() {
		var chemistry = BuiltinChemicalDefinitions.REGISTRY;
		var material = new ThermalMaterialDefinition("test:ordered", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"test:liquid", 300, 0, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(1, 1000, 4)));
		var offsets = new java.util.ArrayList<ThermalSpeciesOffset>();
		String[] ids = new String[3];
		long[] energies = {100_000L, -100_000L, 1L};
		for (int i = 0; i < ids.length; i++) {
			ids[i] = chemistry.species().get(i).id();
			offsets.add(new ThermalSpeciesOffset("test:offset/" + i, ids[i], material.id(), ExactRational.of(energies[i])));
		}
		var function = new ThermalStateFunction(new ThermalRegistry(List.of(material), offsets, List.of(),
				chemistry.fingerprint(), ""));
		var canonical = new java.util.TreeMap<String, Long>();
		for (String id : ids) canonical.put(id, ChemicalLimits.QUANTA_PER_MOL);
		var mixture = ChemicalMixtureRecord.fromStableAmounts(chemistry, canonical, 0L);
		double expected = function.baseEnergy(0, 1, mixture, chemistry);
		for (int[] order : new int[][]{{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}}) {
			var map = new java.util.LinkedHashMap<String, Long>();
			for (int i : order) map.put(ids[i], ChemicalLimits.QUANTA_PER_MOL);
			require(Math.abs(function.baseEnergy(0, 1, map) - expected) <= 4E-4,
					"Reference energy changed with map iteration order or chemical representation");
			require(Math.abs(function.internalEnergyAtTemperature(0, 1, map, 320)
					- function.internalEnergyAtTemperature(0, 1, mixture, chemistry, 320)) <= 4E-4,
					"Caloric state depends on species map iteration order");
		}
	}

	private static void referenceCoefficientsPreserveThermalScale() {
		for(long numerator:new long[]{0,-57300,-1950,19400,1000000})for(long denominator:new long[]{1,3,1000}) {
			var rational = new ExactRational(java.math.BigInteger.valueOf(numerator), java.math.BigInteger.valueOf(denominator));
			var offset = new ThermalSpeciesOffset("test:offset", "test:species", "test:material", rational);
			var cache=new ThermalReferenceEnergyCache(List.of(offset));
			for(long quanta:new long[]{0,1,1_000_000,4_000_000_000L}) {
				double expected = rational.multiply(quanta).divide(ChemicalLimits.QUANTA_PER_MOL).toDouble();
				double energy=cache.energy(offset,quanta),capacity=quanta/(double)ChemicalLimits.QUANTA_PER_MOL*.018*4184;
				require(quanta==0?energy==0:Math.abs(energy-expected)/capacity<1e-4,
						"Registered reference coefficient changed macroscopic caloric temperature");
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
			double internalEnergy;
			long liquidRevision;
			@Override public double liquidInternalEnergy(int ordinal) { if (ordinal != 0) throw new IndexOutOfBoundsException(ordinal); return internalEnergy; }
			@Override public void setLiquidInternalEnergy(int ordinal, double energy) { if (ordinal != 0) throw new IndexOutOfBoundsException(ordinal); internalEnergy = energy; liquidRevision++; }
			@Override public long liquidInventoryRevision() { return liquidRevision; }
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
		var column = new LiquidEnergyColumn(registry, function, new mmm.units.SolverUnitScale(1),
				new int[] {0}, 300, new ThermalAuditState(ThermalAuditLedger.initialized(SignedDyadic.ZERO)), 1, markers);
		for (int iteration = 0; iteration < 12; iteration++) {
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
		for (long amount : new long[] {1_000_000L, 2_000_000L, 1_000_000L}) {
			markers.composition = ChemicalMixtureRecord.fromStableAmounts(chemistry,
					Map.of(BuiltinChemicalDefinitions.H2O, amount), 0L);
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
				new ChemicalSpecies("test:a", Map.of("A", 1), 0, ExactRational.of(1_000_000L), 0),
				new ChemicalSpecies("test:b", Map.of("A", 2), 0, ExactRational.of(2_000_000L), 0)),
				List.of(new ChemicalPhaseDefinition("test:phase", "test:liquid", List.of("test:a", "test:b"),
						Map.of("test:a", ExactRational.ONE))), List.of(new ChemicalReaction(
				"test:combine", ChemicalReaction.Kind.INSTANTANEOUS, "test:phase",
				Map.of("test:a", 2), Map.of("test:b", 1), ExactRational.ZERO)));
		CoherentPhaseStore store = new CoherentPhaseStore(8, 8);
		CoherentPhaseStore.Composition first = new CoherentPhaseStore.Composition(List.of(
				new CoherentPhaseStore.Component("test:a", 8L),
				new CoherentPhaseStore.Component("test:b", 4L)), mmm.units.Mass.fromKilograms(-12.0));
		CoherentPhaseStore.Composition second = new CoherentPhaseStore.Composition(List.of(
				new CoherentPhaseStore.Component("test:a", 8L),
				new CoherentPhaseStore.Component("test:b", 4L)), mmm.units.Mass.fromKilograms(-12.0));
		store.add(new CoherentPhaseStore.Group(3, "test:thermal/solid", 8L, mmm.units.Mass.fromKilograms(4.0), first, 32.0));
		store.add(new CoherentPhaseStore.Group(3, "test:thermal/solid", 8L, mmm.units.Mass.fromKilograms(4.0), second, 32.0));
		CoherentPhaseStore.Group removed = store.take(3, "test:thermal/solid", 5L, chemistry);
		for (CoherentPhaseStore.Group part : List.of(removed, store.group(0))) {
			double represented = ChemicalMixtureRecord.fromStableAmounts(chemistry,
					part.composition().stableAmounts(), part.composition().massResidualUnits()).massKilograms(chemistry);
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
					mmm.units.Mass.fromKilograms(residual));
			var source = new CoherentPhaseStore.Group(0, "test:thermal/solid", total, mmm.units.Mass.fromKilograms(quanta + residual), composition, 1.0);
			var portions = new CoherentPhaseStore(1, 1); portions.add(source);
			long take = tinyRemoved ? 1 : total - 1;
			var previewRemaining = portions.previewSplit(0, source.phaseId(), take, chemistry).remaining();
			var piece = portions.take(0, source.phaseId(), take, chemistry);
			require(portions.group(0).equals(previewRemaining), "Chemical remaining preview differs from committed columns");
			var tiny = tinyRemoved ? piece : portions.group(0);
			var large = tinyRemoved ? portions.group(0) : piece;
			require(tiny.composition().stableAmounts().equals(Map.of("test:a", 1L))
					&& large.composition().stableAmounts().equals(Map.of("test:a", quanta - 1)), "Chemical split changed integer species totals");
			require(Math.addExact(mmm.units.Mass.fromKilograms(1.0), tiny.composition().massResidualUnits()) == tiny.massUnits(),
					"Tiny coherent composition does not close its integer mass");
			require(Math.addExact(piece.composition().massResidualUnits(), portions.group(0).composition().massResidualUnits())
					== mmm.units.Mass.fromKilograms(residual), "Coherent split changed integer mass residual");
		}
		var limited = new CoherentPhaseStore(2, 1);
		var inert = new CoherentPhaseStore.Group(0, "test:thermal/solid", 1, mmm.units.Mass.fromKilograms(1), null, 1);
		limited.add(inert);
		try {
			limited.previewAdd(new CoherentPhaseStore.Group(1, inert.phaseId(), 1, mmm.units.Mass.fromKilograms(1), null, 1));
			throw new AssertionError("Coherent group capacity accepted a new group");
		} catch (CoherentPhaseStore.InventoryUnavailableException expected) {
			require(limited.snapshot().equals(List.of(inert)), "Unavailable coherent addition changed inventory");
		}
		try {
			limited.previewAdd(new CoherentPhaseStore.Group(0, inert.phaseId(), 1, mmm.units.Mass.fromKilograms(1), first, 1));
			throw new AssertionError("Inert and chemical coherent inventories merged");
		} catch (CoherentPhaseStore.InventoryUnavailableException swallowedBug) {
			throw new AssertionError("Invalid coherent capability was classified as a smaller physical candidate", swallowedBug);
		} catch (IllegalArgumentException expected) {
			require(limited.snapshot().equals(List.of(inert)), "Invalid coherent addition changed inventory");
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
		for (var material : List.of(sand, ice)) for (double temperature : new double[] {1, 50, 300, 900}) {
			long total = 10_401_873;
			double density = material == sand ? 2600 : 916.7, mass = mmm.units.Mass.kilograms(mmm.units.Mass.fromKilograms(density * Math.scalb((double) total, -27)));
			int handle = registry.materialHandle(material.id());
			double energy = function.internalEnergyAtTemperature(handle, mass, Map.of(), temperature);
			double sourceK = function.stateForInternalEnergy(handle, mass, Map.of(), energy).temperatureK();
			for (long taken : new long[] {1, total - 1, total}) {
				var store = new CoherentPhaseStore(1, 1);
				store.add(new CoherentPhaseStore.Group(0, material.id(), total, mmm.units.Mass.fromKilograms(mass), null, energy));
				var split = store.previewSplit(0, material.id(), taken, null);
				var preview = split.removed(); var expectedRemainingGroup = split.remaining();
				var removed = store.take(0, material.id(), taken, null);
				require(removed.equals(preview), "Coherent preview and commit split differ");
				SignedDyadic sum = SignedDyadic.of(removed.internalEnergy());
				if (taken != total) {
					require(store.group(0).equals(expectedRemainingGroup), "Coherent remaining preview and commit differ");
					var smaller = taken == 1 ? removed : store.group(0);
					double restoredK = function.stateForInternalEnergy(handle, smaller.mass(), Map.of(), smaller.internalEnergy()).temperatureK();
					require(Math.abs(restoredK - sourceK) <= 1E-4, "Coherent small share changed its macroscopic temperature");
					sum = sum.add(store.exactEnergy());
				} else require(store.groupCount() == 0 && removed.internalEnergy() == energy, "Whole coherent take changed raw energy");
				SignedDyadic error = sum.subtract(SignedDyadic.of(energy)); if (error.signum() < 0) error = error.negate();
				require(error.compareTo(SignedDyadic.of(Math.ulp(energy) * .5)) <= 0, "Coherent energy exceeded single large-share rounding");
				require(Math.addExact(removed.massUnits(), taken == total ? 0L : store.group(0).massUnits()) == mmm.units.Mass.fromKilograms(mass),
						"Coherent take changed integer mass inventory");
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
		var original = new CoherentPhaseStore.Group(0, ice.id(), 2, 1L, null, 1);
		subnormal.add(original);
		try { subnormal.take(0, ice.id(), 1, null); throw new AssertionError("Unrepresentable positive coherent share accepted"); }
		catch (CoherentPhaseStore.InventoryUnavailableException expected) { require(subnormal.group(0).equals(original), "Rejected coherent split changed original columns"); }
	}

	private static void granularBatchDepositPreflightIsAtomic() {
		var first = new GranularEnergyColumn.DepositKey(0, 0, 0);
		var second = new GranularEnergyColumn.DepositKey(0, 1, 0);
		GranularEnergyColumn budget = new GranularEnergyColumn(4, 1);
		budget.addActive(1); budget.addActive(2);
		double[] before = budget.copyActiveEnergy();
		var separate = List.of(new mmm.granular.GranularInventory.DepositTransfer(1, 0, 0, 0),
				new mmm.granular.GranularInventory.DepositTransfer(0, 0, 1, 0));
		require(budget.planActiveToDeposits(separate) == null && java.util.Arrays.equals(before, budget.copyActiveEnergy())
				&& budget.depositCount() == 0, "batch must preflight all new deposit keys together");
		GranularEnergyColumn overflow = new GranularEnergyColumn(4, 4);
		overflow.addDeposit(first, Double.MAX_VALUE / 2); overflow.addActive(Double.MAX_VALUE / 2);
		overflow.addActive(Double.MAX_VALUE / 2);
		var same = List.of(new mmm.granular.GranularInventory.DepositTransfer(1, 0, 0, 0),
				new mmm.granular.GranularInventory.DepositTransfer(0, 0, 0, 0));
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
		var swapTransfers = List.of(new mmm.granular.GranularInventory.DepositTransfer(3, 0, 0, 0),
				new mmm.granular.GranularInventory.DepositTransfer(1, 0, 0, 0));
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
		GranularEnergyColumn heated = new GranularEnergyColumn(4, 4); heated.addActive(.1); heated.addActive(.2);
		ThermalAuditState heatAudit = new ThermalAuditState(ThermalAuditLedger.initialized(heated.exactEnergy()));
		GranularEnergyState heatState = new GranularEnergyState(null, heated, null, null, null, 300, heatAudit);
		var heatPlan = heatState.planActiveToDeposits(List.of(
				new mmm.granular.GranularInventory.DepositTransfer(1, 0, 0, 0, SignedDyadic.of(3)),
				new mmm.granular.GranularInventory.DepositTransfer(0, 0, 0, 0, SignedDyadic.of(2))));
		heatPlan.commit();
		require(heatAudit.ledger().mechanicalWork().equals(SignedDyadic.of(5))
				&& heated.exactEnergy().equals(heatAudit.ledger().expectedInventory()),
				"Settlement must publish mechanical heat separately from U storage roundoff");
		var movedKey = new GranularEnergyColumn.DepositKey(1, 0, 0);
		var bedPlan = heatState.planDepositMove(first, movedKey, 1, 3, SignedDyadic.of(.7));
		bedPlan.commit();
		require(heatAudit.ledger().mechanicalWork().equals(SignedDyadic.of(5).add(SignedDyadic.of(.7)))
				&& heated.exactEnergy().equals(heatAudit.ledger().expectedInventory()),
				"Bed relaxation must book local gravitational heat and proportional-split roundoff");
		var staleBed = heatState.planDepositMove(first, movedKey, 1, 2, SignedDyadic.of(.2));
		var bedBefore = heated.depositSnapshot();
		heatAudit.update(ledger -> ledger.withRoundoff(SignedDyadic.of(1)));
		rejected = false; try { staleBed.commit(); } catch (IllegalStateException expected) { rejected = true; }
		require(rejected && bedBefore.equals(heated.depositSnapshot()), "Stale relaxation audit changed either bed group");
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
