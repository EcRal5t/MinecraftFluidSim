package mmm.fluid;

import mmm.chemistry.ExactRational;
import mmm.fluid.air.*;
import mmm.thermal.*;
import mmm.units.SignedDyadic;

import java.math.BigInteger;
import java.util.*;

/** Explicit offline comparison of shape removal and a unique-parent capacity transfer. */
public final class LiquidShapeRemovalDiagnostic {
	private static final String GAS_ID = "test:shape_gas";
	private static final GasRegistry GAS = new GasRegistry(
			List.of(new GasComponentDefinition(GAS_ID, 1.2, 0, List.of(), List.of())),
			List.of(new GasRegistry.AmbientFraction(GAS_ID, ExactRational.ONE)));
	private static final FluidMaterialRegistry LIQUIDS = new FluidMaterialRegistry(
			List.of(new FluidMaterial("mmm:water", 1000, "mmm:water", 0, Math.PI / 2)));
	private static final ThermalRegistry THERMAL = new ThermalRegistry(List.of(
			new ThermalMaterialDefinition("test:shape_water", ThermalMaterialDefinition.CarrierKind.LIQUID,
					"mmm:water", 300, 0, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(150, 1500, 4000))),
			new ThermalMaterialDefinition("test:shape_gas", ThermalMaterialDefinition.CarrierKind.GAS,
					GAS_ID, 300, 0, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(150, 1500, 718)))));
	private static final GasThermalModel GAS_MODEL = new GasThermalModel(THERMAL, GAS);

	private LiquidShapeRemovalDiagnostic() { }

	public static void main(String[] args) {
		int unresolved = 0;
		for (boolean lShape : new boolean[]{false, true}) for (boolean tiny : new boolean[]{false, true}) {
			var source = fixture(lShape, tiny).captureAuthoritativeState();
			for (boolean byCapacity : new boolean[]{false, true}) {
				String label = (lShape ? "L" : "single") + "/" + (tiny ? "1nanos" : "normal")
						+ "/" + (byCapacity ? "unique-parent-capacity" : "overlap");
				try { unresolved += compare(FluidDomain.restoreAuthoritativeState(source), byCapacity, label); }
				catch (RuntimeException failure) {
					unresolved++;
					System.out.println("SHAPE_REMOVAL " + label + " unresolved=" + failure);
					failure.printStackTrace(System.out);
				}
			}
		}
		System.out.println("SHAPE_REMOVAL unresolved=" + unresolved
				+ "; capacity transfer preserves supported node states; aperture geometry and tiny stock remain open");
		if (unresolved != 0) throw new AssertionError("Shape removal has unresolved physical conditions: " + unresolved);
	}

	private static FluidDomain fixture(boolean lShape, boolean tiny) {
		// h=.5, u=.5 has the original h=1, u=1 normalized translation and supports thermal state.
		var d = new FluidDomain(5, 5, 5,
				new FluidSolverConfig(.5F, 1000, 0, .125F, 200, 1e-5F, .45F, .002F, 0), 4096);
		d.setVolume(2, 2, 2, 1);
		if (lShape) { d.setVolume(1, 2, 2, 1); d.setVolume(2, 1, 2, 1); }
		for (int p = 0; p < d.particleCount(); p++) {
			d.particles.setVelocityX(p, .5F); d.particles.setVelocityY(p, .5F);
		}
		d.rebuildGridFromParticles();
		var basis = CellAffineVelocity.capture(d); var coefficients = new double[3][4 * basis.blockCount()];
		for (int b = 0; b < basis.blockCount(); b++) { coefficients[0][4 * b] = .5; coefficients[1][4 * b] = .5; }
		d.pressureSystem.updateMarkerVelocityField(new CellAffineVelocityField(basis, coefficients));
		d.gridToParticleStage.prepareAdvectionCells();
		d.cellLiquidVolumeTransport = new CellLiquidVolumeTransport(d);
		d.sealDomainBoundary(); d.bindGasRegistry(GAS); d.bootstrapGasState("");
		d.cellLiquidVolumeTransport.advect(.125F);
		// Install one quantum in the translated source; the original L translation itself needs a gas split.
		if (tiny) for (int n = 1; n <= d.gasState.network.nodeCount(); n++) d.setGasNodeStandardVolume(n, n == 1 ? 1e-9 : 0);
		d.bindThermal(THERMAL, LIQUIDS, 300);
		if (lShape && !tiny) {
			// The old finite aperture must keep these different source states separate during conversion.
			d.setGasNodeStandardVolume(2, 2 * d.gasState.network.standardVolume(2));
			var inventories = new ArrayList<GasInventory>(); var volume = new double[d.gasState.network.nodeCount()];
			var energy = volume.clone(); SignedDyadic added = SignedDyadic.ZERO;
			for (int n = 1; n <= volume.length; n++) {
				inventories.add(d.gasState.network.inventory(n)); volume[n - 1] = d.gasState.network.availableVolume(n);
				energy[n - 1] = GAS_MODEL.internalEnergyAtTemperature(inventories.getLast(), n == 2 ? 400 : 300);
				added = added.add(SignedDyadic.of(energy[n - 1]));
			}
			d.thermalState.commitGasRemap(d.thermalState.planGasRemap(d.gasState.network,
					inventories, volume, energy, volume, added, SignedDyadic.ZERO));
		}
		return d;
	}

	private static int compare(FluidDomain d, boolean byCapacity, String label) {
		var old = d.gasState.frame; var stock = d.gasState.network.sealedInventory();
		var source = d.gasState.network.externalSource(); var sink = d.gasState.network.escapedSink();
		var energy = d.thermalState.exactGasEnergy(); var work = d.thermalAudit().mechanicalWork();
		var carriers = d.exportMaterialParticleState(); double capacity = totalCapacity(old);
		double[] sourcePressure = new double[old.nodeCount() + 1], sourceTemperature = sourcePressure.clone();
		boolean[] sourceStock = new boolean[sourcePressure.length];
		for (int n = 1; n < sourcePressure.length; n++) {
			sourcePressure[n] = pressure(d, n); sourceStock[n] = !d.gasState.network.inventory(n).isEmpty();
			sourceTemperature[n] = sourceStock[n] ? d.gasNodeTemperatureK(n) : Double.NaN;
		}
		d.liquidShapes = List.of(); d.gridDirty = true; d.velocityGridDirty = true;
		long started = System.nanoTime();
		var overlapPlan = d.gasTopologyCoordinator.planAfterLiquidEdits(List.of());
		var next = overlapPlan.frame(); int[] parents = uniqueParents(old, next);
		if (!capacities(old).equals(capacities(next))) throw new AssertionError("Fixed-component gas capacity changed");
		var plan = byCapacity ? capacityPlan(d, old, next, parents) : overlapPlan;
		d.gasTopologyCoordinator.commit(plan);
		var deltaEnergy = d.thermalState.exactGasEnergy().subtract(energy);
		var deltaWork = d.thermalAudit().mechanicalWork().subtract(work);
		int violations = deltaWork.signum() == 0 ? 0 : 1;
		System.out.printf("SHAPE_REMOVAL %s nodes=%d->%d capacity=%.17g->%.17g deltaGasU=%.17g deltaWork=%.17g remapMs=%.3f%n",
				label, old.nodeCount(), next.nodeCount(), capacity, totalCapacity(next), deltaEnergy.toDouble(),
				deltaWork.toDouble(), (System.nanoTime() - started) / 1e6);
		if (capacity != totalCapacity(next) || !source.equals(d.gasState.network.externalSource())
				|| !sink.equals(d.gasState.network.escapedSink()) || stock.standardVolumeNanos() != d.gasState.network.sealedInventory().standardVolumeNanos()
				|| !d.gasState.network.auditClosed() || !d.exactInternalEnergy().equals(d.thermalAudit().expectedInventory())
				|| !Arrays.equals(carriers.copyPositionVolume(), d.exportMaterialParticleState().copyPositionVolume())
				|| !Arrays.equals(carriers.copyParticleMass(), d.exportMaterialParticleState().copyParticleMass()))
			throw new AssertionError("Shape conversion changed conserved carrier state or its audit");
		for (int n = 1; n <= next.nodeCount(); n++) {
			int parent = parents[n];
			double deltaP = pressure(d, n) - sourcePressure[parent];
			double temperature = d.gasState.network.inventory(n).isEmpty() ? Double.NaN : d.gasNodeTemperatureK(n);
			double quantumError = sourceStock[parent] ? GasRegistry.STANDARD_PRESSURE_PA * 1e-9 * sourceTemperature[parent]
					/ GasRegistry.STANDARD_TEMPERATURE_K / d.gasState.network.availableVolume(n) : 0;
			System.out.printf("SHAPE_NODE %s node=%d parent=%d V=%.17g nanos=%d U=%.17g p=%.17g deltaP=%g relativeP=%g deltaT=%g%n",
					label, n, parent, d.gasState.network.availableVolume(n), d.gasState.network.standardVolumeNanos(n),
					d.thermalState.gasNodeEnergy(n, d.gasState.network), pressure(d, n), deltaP,
					sourcePressure[parent] == 0 ? Double.NaN : deltaP / sourcePressure[parent], temperature - sourceTemperature[parent]);
			if (d.gasState.network.inventory(n).isEmpty() && sourceStock[parent]
					|| Math.abs(deltaP) > quantumError + 32 * Math.ulp(sourcePressure[parent])) violations++;
		}
		violations += reportPorts(old, next, parents, label);
		if (!connected(next)) violations++;
		var state = d.captureAuthoritativeState(); var restored = FluidDomain.restoreAuthoritativeState(state);
		if (!Arrays.equals(next.pieceNodes(), restored.gasState.frame.pieceNodes())
				|| !d.exportGasState("").nodes().equals(restored.exportGasState("").nodes())
				|| !d.thermalState.exactGasEnergy().equals(restored.thermalState.exactGasEnergy()))
			throw new AssertionError("Shape conversion failed capture/restore");
		System.out.printf("SHAPE_REMOVAL %s restored=true fixedComponentCapacities=unchanged connected=%s violations=%d%n", label, connected(next), violations);
		return violations;
	}

	private static int[] uniqueParents(GasRuntimeGeometry old, GasRuntimeGeometry next) {
		int[] parents = new int[next.nodeCount() + 1];
		for (var piece : old.capacity().pieces()) {
			int source = old.nodeOfPiece(piece.id()), target = next.nodeOfPiece(piece.id());
			if (source <= 0 || target <= 0 || GasGeometryTransition.intersectionVolume(
					old.geometry().piece(piece.id()), next.geometry().piece(piece.id())).isZero()) continue;
			if (parents[target] != 0 && parents[target] != source)
				throw new IllegalArgumentException("New gas node has multiple old sources: " + target);
			parents[target] = source;
		}
		for (int n = 1; n < parents.length; n++) if (parents[n] == 0) throw new IllegalArgumentException("Unowned new gas node " + n);
		return parents;
	}

	private static GasTopologyCoordinator.GasTopologyPlan capacityPlan(
			FluidDomain d, GasRuntimeGeometry old, GasRuntimeGeometry next, int[] parents) {
		var inventories = new ArrayList<GasInventory>(Collections.nCopies(next.nodeCount(), GasInventory.EMPTY));
		double[] available = new double[next.nodeCount()], inherited = available.clone(), energy = available.clone();
		for (int n = 1; n <= next.nodeCount(); n++) available[n - 1] = next.capacity().chambers().get(n - 1).cubicMeters();
		for (int parent = 1; parent <= old.nodeCount(); parent++) {
			var targets = new ArrayList<Integer>(); var weights = new ArrayList<ExactRational>(); SignedDyadic sum = SignedDyadic.ZERO;
			for (int n = 1; n < parents.length; n++) if (parents[n] == parent) {
				targets.add(n); var volume = SignedDyadic.of(available[n - 1]); sum = sum.add(volume); weights.add(rational(volume));
			}
			if (!sum.equals(SignedDyadic.of(d.gasState.network.availableVolume(parent))))
				throw new IllegalArgumentException("Capacity changed for source node " + parent);
			var measure = ExactGasPartitionWeights.ofRationals(weights); var original = d.gasState.network.inventory(parent);
			var parts = original.partition(measure); var control = GasTopologyVolumePartition.partitionRational(sum.toDouble(), measure);
			var energies = original.isEmpty() ? new double[parts.size()] : d.thermalState.partitionGasNodeEnergy(parent, parts, d.gasState.network);
			double sourceP = pressure(d, parent), sourceT = original.isEmpty() ? Double.NaN : d.gasNodeTemperatureK(parent);
			for (int i = 0; i < targets.size(); i++) {
				int n = targets.get(i); var part = parts.get(i);
				if (!original.isEmpty() && part.isEmpty()) throw new IllegalArgumentException("Positive target " + n
						+ " needs " + original.standardVolumeNanos() * available[n - 1] / sum.toDouble() + " nanos; integer stock creates vacuum");
				inventories.set(n - 1, part); inherited[n - 1] = control[i]; energy[n - 1] = energies[i];
				double p = GAS_MODEL.absolutePressure(part, available[n - 1], energies[i]);
				double t = part.isEmpty() ? Double.NaN : GAS_MODEL.state(part, energies[i]).temperatureK();
				double quantumError = part.isEmpty() ? 0 : GasRegistry.STANDARD_PRESSURE_PA * 1e-9 * sourceT / GasRegistry.STANDARD_TEMPERATURE_K / available[n - 1];
				System.out.printf("SHAPE_CAPACITY parent=%d target=%d deltaT=%g deltaP=%g oneNanoPressure=%g%n", parent, n, t - sourceT, p - sourceP, quantumError);
				if (control[i] != available[n - 1] || !part.isEmpty() && (Math.abs(p - sourceP) > quantumError + 32 * Math.ulp(sourceP)
						|| Math.abs(t - sourceT) > 32 * Math.ulp(sourceT)))
					throw new AssertionError("Capacity transfer changed source pressure beyond inventory/storage error");
			}
		}
		var edges = next.topology().connections().stream().map(e -> new AirEdge(e.firstNode(), e.secondNode(), e.area(), .65)).toList();
		return new GasTopologyCoordinator.GasTopologyPlan(next, old,
				d.gasState.network.planRemapNodes(available, inventories, GasAuditLedger.EMPTY, GasAuditLedger.EMPTY, edges),
				d.thermalState.planGasRemap(d.gasState.network, inventories, available, energy, inherited, SignedDyadic.ZERO, SignedDyadic.ZERO),
				new GasGeometryTransition(old, next));
	}

	private static int reportPorts(GasRuntimeGeometry old, GasRuntimeGeometry next, int[] parents, String label) {
		double oldCross = old.topology().connections().stream().mapToDouble(e -> e.area()).sum();
		double newCross = next.topology().connections().stream().filter(e -> parents[e.firstNode()] != parents[e.secondNode()]).mapToDouble(e -> e.area()).sum();
		System.out.printf("SHAPE_PORTS %s oldCrossArea=%.17g newCrossArea=%.17g ratio=%g connected=%s%n",
				label, oldCross, newCross, oldCross == 0 ? Double.NaN : oldCross / newCross, connected(next));
		return Math.abs(oldCross - newCross) > 8 * Math.ulp(oldCross) ? 1 : 0;
	}
	private static boolean connected(GasRuntimeGeometry frame) {
		var reached = new HashSet<Integer>(); reached.add(1);
		for (int i = 0; i < frame.nodeCount(); i++) for (var edge : frame.topology().connections())
			if (reached.contains(edge.firstNode()) || reached.contains(edge.secondNode())) { reached.add(edge.firstNode()); reached.add(edge.secondNode()); }
		return reached.size() == frame.nodeCount();
	}
	private static double pressure(FluidDomain d, int node) {
		return GAS_MODEL.absolutePressure(d.gasState.network.inventory(node), d.gasState.network.availableVolume(node), d.thermalState.gasNodeEnergy(node, d.gasState.network));
	}
	private static double totalCapacity(GasRuntimeGeometry frame) { return frame.capacity().chambers().stream().mapToDouble(c -> c.cubicMeters()).sum(); }
	private record Capacity(int cell, int support, SignedDyadic pore, SignedDyadic available) { }
	private static List<Capacity> capacities(GasRuntimeGeometry frame) {
		return frame.capacity().components().stream().map(c -> new Capacity(c.cell(), c.supportMask(),
				c.poreVolumeNormalized(), c.availableVolumeNormalized())).toList();
	}
	private static ExactRational rational(SignedDyadic value) {
		return value.exponent() >= 0 ? new ExactRational(value.significand().shiftLeft(value.exponent()), BigInteger.ONE)
				: new ExactRational(value.significand(), BigInteger.ONE.shiftLeft(-value.exponent()));
	}
}
