package com.example.fluid;

import java.util.Arrays;
import java.util.Map;

/** Standalone M3 gate for marker material/phase labels, mass inventory and equal-density degeneration. */
public final class M3ConnectedVesselValidation {
	private M3ConnectedVesselValidation() { }

	public static void main(String[] args) {
		M3ConnectedVesselExperiment.Topology topology = M3ConnectedVesselExperiment.uTube();
		validateTopology(topology);

		M3ConnectedVesselExperiment.MarkerState pure = M3ConnectedVesselExperiment.singleMaterialState(topology);
		M3ConnectedVesselExperiment.MarkerState labeled = M3ConnectedVesselExperiment.equalDensityTwoMaterialState(topology);
		validateInventoryFreeze(topology, pure, labeled);

		M3ConnectedVesselExperiment.Result reference = M3ConnectedVesselExperiment.runPureWaterReference(topology);
		M3ConnectedVesselExperiment.Result singleMaterial = M3ConnectedVesselExperiment.runMarkerMassModel(topology, pure);
		M3ConnectedVesselExperiment.Result twoMaterial = M3ConnectedVesselExperiment.runMarkerMassModel(topology, labeled);
		validateNumerics(reference);
		validateStrictDegeneration(reference, singleMaterial, "single-material mass model");
		validateStrictDegeneration(reference, twoMaterial, "equal-density two-material model");
		validateVariableDensityScenarios();

		System.out.printf("M3 connected-vessel gate passed: cells=%d markers=%d volume=%.3f BV mass=%.3f MU "
				+ "PCG=%d residual=%.3g maxVelocity=%.3g%n",
				topology.cellCount(), labeled.size(), M3ConnectedVesselExperiment.ledger(labeled).totalVolume(),
				M3ConnectedVesselExperiment.ledger(labeled).totalMass(), reference.pcgIterations(),
				reference.pcgResidualRms(), reference.maximumVelocity());
	}

	private static void validateVariableDensityScenarios() {
		double interfaceFraction = 0.25;
		M3ConnectedVesselExperiment.Scenario[] scenarios = {
				M3ConnectedVesselExperiment.balancedScenario(
						"water-oil-1.25:1", 1_000.0, 800.0, interfaceFraction, 6, 7, 0.5),
				M3ConnectedVesselExperiment.balancedScenario(
						"mercury-water-13.6:1", 13_600.0, 1_000.0, interfaceFraction, 2, 7, 0.5)
		};
		for (M3ConnectedVesselExperiment.Scenario scenario : scenarios) {
			M3ConnectedVesselExperiment.Topology topology = M3ConnectedVesselExperiment.variableDensityTube(scenario);
			M3ConnectedVesselExperiment.MarkerState state = M3ConnectedVesselExperiment.variableDensityState(topology, scenario);
			M3ConnectedVesselExperiment.Result result = M3ConnectedVesselExperiment.runMarkerMassModel(
					topology, state, scenario.lowerMaterialDistanceFraction());
			validateNumerics(result);
			validateVariableDensityHydrostatics(topology, scenario, result);
			System.out.printf("  %-25s rhoInterface=%.3f pressureErr=%.3g PCG=%d residual=%.3g maxVelocity=%.3g%n",
					scenario.name(), scenario.interfaceEffectiveDensity(), pressureRelativeError(
							result.pressure(), M3ConnectedVesselExperiment.expectedHydrostaticPressure(topology, scenario)),
					result.pcgIterations(), result.pcgResidualRms(), result.maximumVelocity());
		}
	}

	private static void validateVariableDensityHydrostatics(M3ConnectedVesselExperiment.Topology topology,
	                                                        M3ConnectedVesselExperiment.Scenario scenario,
	                                                        M3ConnectedVesselExperiment.Result result) {
		double[] expectedPressure = M3ConnectedVesselExperiment.expectedHydrostaticPressure(topology, scenario);
		check(pressureRelativeError(result.pressure(), expectedPressure) <= 1.0E-10,
				scenario.name() + " missed analytic hydrostatic pressure");
		int variableFaces = 0;
		for (int faceIndex = 0; faceIndex < topology.faces().length; faceIndex++) {
			M3ConnectedVesselExperiment.Face face = topology.faces()[faceIndex];
			if (face.highCell() < 0
					|| result.cellDensity()[face.lowCell()] == result.cellDensity()[face.highCell()]) continue;
			variableFaces++;
			double actual = 1.0 / result.inverseDensityByFace()[faceIndex];
			check(Math.abs(actual - scenario.interfaceEffectiveDensity()) <= 1.0E-9,
					scenario.name() + " did not use the distance-weighted conservative interface density");
		}
		check(variableFaces == 4, scenario.name() + " must expose one 2x2 horizontal material interface");
	}

	private static double pressureRelativeError(double[] actual, double[] expected) {
		double maximumExpected = 0.0, maximumError = 0.0;
		for (int cell = 0; cell < actual.length; cell++) {
			maximumExpected = Math.max(maximumExpected, Math.abs(expected[cell]));
			maximumError = Math.max(maximumError, Math.abs(actual[cell] - expected[cell]));
		}
		return maximumError / maximumExpected;
	}

	private static void validateTopology(M3ConnectedVesselExperiment.Topology topology) {
		check(topology.isConnected(), "U-tube pressure phase is not a single connected component");
		check(topology.sizeZ() > 1, "connected-vessel topology collapsed to 2D");
		for (M3ConnectedVesselExperiment.Axis axis : M3ConnectedVesselExperiment.Axis.values()) {
			check(topology.faceCount(axis) > 0, "connected-vessel topology has no " + axis + " fluid faces");
		}
		check(topology.atmosphereFaceCount() == 8, "U-tube must expose only its two 2x2 free surfaces");
	}

	private static void validateInventoryFreeze(M3ConnectedVesselExperiment.Topology topology,
	                                            M3ConnectedVesselExperiment.MarkerState pure,
	                                            M3ConnectedVesselExperiment.MarkerState labeled) {
		check(Arrays.equals(pure.cell(), labeled.cell()), "relabeling changed marker placement");
		check(Arrays.equals(pure.particleVolume(), labeled.particleVolume()), "relabeling changed exact volume inventory");
		check(Arrays.equals(pure.particleMass(), labeled.particleMass()), "relabeling changed exact mass inventory");
		check(!Arrays.equals(pure.materialId(), labeled.materialId()), "two-material state did not change material labels");
		check(!Arrays.equals(pure.phaseLabel(), labeled.phaseLabel()), "two-material state did not change phase labels");
		check(interfaceFaceCount(labeled, true, topology) > 0,
				"two materials do not share an open pressure face");
		check(interfaceFaceCount(labeled, false, topology) > 0,
				"two phase labels do not share an open pressure face");

		M3ConnectedVesselExperiment.Ledger pureLedger = M3ConnectedVesselExperiment.ledger(pure);
		M3ConnectedVesselExperiment.Ledger labeledLedger = M3ConnectedVesselExperiment.ledger(labeled);
		check(sameBits(pureLedger.totalVolume(), labeledLedger.totalVolume()), "material split changed total volume");
		check(sameBits(pureLedger.totalMass(), labeledLedger.totalMass()), "material split changed total mass");
		check(pureLedger.byMaterial().size() == 1 && pureLedger.byPhase().size() == 1,
				"pure reference must have one material and one phase ledger entry");
		check(labeledLedger.byMaterial().size() == 2 && labeledLedger.byPhase().size() == 2,
				"labeled state must keep two material and phase ledger entries");
		check(labeledLedger.byMaterial().containsKey(M3ConnectedVesselExperiment.MATERIAL_A)
				&& labeledLedger.byMaterial().containsKey(M3ConnectedVesselExperiment.MATERIAL_B),
				"material ledger lost a logical material ID");
		check(labeledLedger.byPhase().containsKey(M3ConnectedVesselExperiment.PHASE_A)
				&& labeledLedger.byPhase().containsKey(M3ConnectedVesselExperiment.PHASE_B),
				"phase ledger lost a logical phase label");
		validateLedgerClosure(labeledLedger.totalVolume(), labeledLedger.totalMass(), labeledLedger.byMaterial(), "material");
		validateLedgerClosure(labeledLedger.totalVolume(), labeledLedger.totalMass(), labeledLedger.byPhase(), "phase");
	}

	private static long interfaceFaceCount(M3ConnectedVesselExperiment.MarkerState state, boolean material,
	                                       M3ConnectedVesselExperiment.Topology topology) {
		int[] labelByCell = new int[topology.cellCount()];
		for (int marker = 0; marker < state.size(); marker++) {
			labelByCell[state.cell()[marker]] = material ? state.materialId()[marker] : state.phaseLabel()[marker];
		}
		return Arrays.stream(topology.faces())
				.filter(face -> face.highCell() >= 0)
				.filter(face -> labelByCell[face.lowCell()] != labelByCell[face.highCell()])
				.count();
	}

	private static void validateLedgerClosure(double expectedVolume, double expectedMass,
	                                          Map<Integer, M3ConnectedVesselExperiment.Inventory> inventory,
	                                          String label) {
		double volume = 0.0, mass = 0.0;
		for (M3ConnectedVesselExperiment.Inventory entry : inventory.values()) {
			volume += entry.volume();
			mass += entry.mass();
		}
		check(sameBits(volume, expectedVolume), label + " volume ledger does not close exactly");
		check(sameBits(mass, expectedMass), label + " mass ledger does not close exactly");
	}

	private static void validateNumerics(M3ConnectedVesselExperiment.Result result) {
		check(result.symmetryError() == 0.0, "connected-vessel pressure matrix lost exact symmetry");
		check(result.minimumCholeskyPivot() > 0.0, "connected-vessel pressure matrix is not positive definite");
		check(result.pcgIterations() > 0 && result.pcgIterations() <= 64, "PCG iteration count escaped small-domain bound");
		check(result.pcgResidualRms() <= 1.0E-8, "PCG residual is too large");
		check(result.maximumVelocity() <= 1.0E-8, "equal-height connected vessel developed parasitic velocity");
		check(result.maximumDivergence() <= 1.0E-8, "connected-vessel projection left divergence");
	}

	private static void validateStrictDegeneration(M3ConnectedVesselExperiment.Result reference,
	                                               M3ConnectedVesselExperiment.Result candidate,
	                                               String label) {
		check(Arrays.equals(reference.markerCount(), candidate.markerCount()), label + " changed the pressure phase");
		check(Arrays.equals(reference.cellDensity(), candidate.cellDensity()), label + " changed constant cell density");
		check(Arrays.equals(reference.inverseDensityByFace(), candidate.inverseDensityByFace()),
				label + " changed face coefficients");
		check(Arrays.equals(reference.pressureMatrix(), candidate.pressureMatrix()), label + " changed the pressure matrix");
		check(Arrays.equals(reference.rightHandSide(), candidate.rightHandSide()), label + " changed the pressure RHS");
		check(Arrays.equals(reference.pressure(), candidate.pressure()), label + " changed the pressure solution");
		check(Arrays.equals(reference.projectedVelocity(), candidate.projectedVelocity()),
				label + " changed projected face velocities");
		check(reference.pcgIterations() == candidate.pcgIterations(), label + " changed PCG iterations");
		check(sameBits(reference.pcgResidualRms(), candidate.pcgResidualRms()), label + " changed PCG residual");
		check(sameBits(reference.maximumDivergence(), candidate.maximumDivergence()), label + " changed divergence");
	}

	private static boolean sameBits(double left, double right) {
		return Double.doubleToLongBits(left) == Double.doubleToLongBits(right);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
