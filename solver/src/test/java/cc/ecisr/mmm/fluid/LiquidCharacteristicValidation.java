package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.List;

/** Whole-volume characteristics preserve geometry, exact inventory and shared velocity traces. */
final class LiquidCharacteristicValidation {
	private static final LiquidPolyhedron.Point GRAVITY = new LiquidPolyhedron.Point(0, -9.81, 0);
	static void validate() {
		productionGeometryPipelinePreservesGravity();
		productionGeometryPipelineRefinesGasSupport();
		productionGeometryPipelinePreservesContinuousShear();
		repeatedGravityPreservesUniformCharacteristic();
		wholeVolumeCharacteristicsPreserveGeometry();
		mixedVelocitySupportSurvivesNextStep();
		thinLiquidLayerPreservesSeparateGasPressureNodes();
		movingLiquidWallTransportsItsGasContacts();
	}
	static void validateMaterialSurfaceEnergy() {
		productionGeometryPipelineUsesMaterialSurfaceEnergy(new double[]{60, 120});
	}
	private static void productionGeometryPipelineUsesMaterialSurfaceEnergy(double[] angles) {
		productionGeometryPipelineUsesMaterialSurfaceEnergy(angles, 1);
	}
	private static void productionGeometryPipelineUsesMaterialSurfaceEnergy(double[] angles, int steps) {
		if (steps < 1) throw new IllegalArgumentException("Material surface requires a positive step count");
		for (double angle : angles) {
			long started = System.nanoTime();
			System.out.println("material surface angle=" + angle + " start");
			var cells = LiquidGeometryRefinement.children(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0.25, 0, 0.25, 0.75, 0.625, 0.75)));
			var state = LiquidGeometryStateMapper.capture(cells, 1, new int[]{0}, new int[cells.size()]);
			var config = new FluidSolverConfig(0.5F, 1000, 0, 1.0F / 128, 512, 1e-7F, 0.45F, 0.002F, 0.8F);
			var domain = new FluidDomain(2, 2, 2, config);
			domain.restoreParticleState(new FlipParticleState(2, 2, 2, 0.5F, 1, 1.25F,
					new float[]{0.5F, 0.3125F, 0.5F, 1.25F}, new float[3], new byte[8], state));
			var registry = new FluidMaterialRegistry(List.of(FluidMaterial.withSurfaceTension("mmm:resolved", 1000, "mmm:resolved", 0.125, angle)));
			domain.setMarkerMaterial(0, registry, 0); domain.sealDomainBoundary(); domain.bootstrapGasState("");
			double before = surfaceEnergy(domain), mass = domain.totalMass(); var gas = domain.exportGasState("");
			for (int step = 1; step <= steps; step++) {
				System.out.println("material surface step=" + step + " start millis=" + (System.nanoTime() - started) / 1e6);
				long stepStarted = System.nanoTime();
				var candidate = new CpuFluidSolverBackend().evaluate(new FluidStepRequest(step - 1, domain.captureAuthoritativeState(), List.of(), false, 0, true));
				System.out.println("material surface angle=" + angle + " step=" + step + " fullBackendMillis=" + (System.nanoTime() - stepStarted) / 1e6);
				try (var ignored = LiquidGeometryStateMapper.openReadScope()) {
					var next = steps == 1 ? FluidDomain.restoreAuthoritativeState(candidate.state()) : candidate.claimPreparedCpuDomain();
					var output = next.liquidGeometryState;
					System.out.println("material surface liquid=" + output.cells().size() + " gas=" + next.gasState.geometry.cells().size()
							+ " maxBits=" + output.cells().stream().flatMap(cell -> java.util.Arrays.stream(cell.copyVertices())).mapToInt(java.math.BigInteger::bitLength).max().orElse(0));
					System.out.println("material surface angle=" + angle + " restored millis=" + (System.nanoTime() - started) / 1e6);
					double kinetic = next.particleKineticEnergyJoules();
					double after = kinetic + surfaceEnergy(next);
					if (!(kinetic > 0 && after < before) || next.cellLiquidVolumeTransport != null
							|| next.totalMass() != mass || next.totalVolume() != 1.25F || next.particles.count() != 1)
						throw new AssertionError("Production material surface energy lost geometry, inertia or inventory");
					if (!gas.nodes().stream().map(cc.ecisr.mmm.fluid.air.GasStateSnapshot.Node::inventory).toList()
							.equals(next.exportGasState("").nodes().stream().map(cc.ecisr.mmm.fluid.air.GasStateSnapshot.Node::inventory).toList()))
						throw new AssertionError("Production wetting changed sealed gas inventory");
					System.out.println("material surface angle=" + angle + " verified millis=" + (System.nanoTime() - started) / 1e6);
					domain = next; before = after;
				}
			}
		}
	}
	private static double surfaceEnergy(FluidDomain domain) {
		var state = domain.liquidGeometryState; var cells = new ArrayList<>(LiquidGeometryStateMapper.restore(state));
		cells.addAll(LiquidGeometryStateMapper.restoreCells(domain.gasState.geometry.cells()));
		var partition = new LiquidGeometryDomainPartition(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(), domain.geometry.cutCells, cells, 4096);
		var input = new LiquidGeometryPhaseInput(state, partition, domain.gasState.geometry, 4096);
		var properties = new LiquidGeometryCarrierProperties(domain, state, input);
		return new LiquidAreaEnergy(input.mesh.exactPolygons, properties.surfaceCoefficient).joules;
	}
	private static void productionGeometryPipelinePreservesContinuousShear() { productionGeometryPipelinePreservesContinuousShear(2, false); }
	private static void productionGeometryPipelinePreservesContinuousShear(int level, boolean timing) {
		var shape = LiquidPolyhedron.box(0.25, 0.25, 0.25, 0.75, 0.75, 0.75);
		List<LiquidGeometryRefinement.Cell> cells = List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, shape));
		for (int l = 0; l < level; l++) cells = cells.stream().flatMap(c -> LiquidGeometryRefinement.children(c).stream()).toList();
		var incoming = new double[3 * cells.size()]; for (int c = 0; c < cells.size(); c++) incoming[3 * c] = 0.125 * (cells.get(c).geometry().centroid().y() - 0.5);
		var state = LiquidGeometryStateMapper.capture(cells, 1, new int[]{0}, new int[cells.size()], incoming);
		var config = new FluidSolverConfig(0.5F, 1000, 0, 1.0F / 128, 512, 1e-7F, 0.45F, 0.002F, 0.8F);
		var domain = new FluidDomain(2, 2, 2, config);
		domain.restoreParticleState(new FlipParticleState(2, 2, 2, 0.5F, 1, 1,
				new float[]{0.5F, 0.5F, 0.5F, 1}, new float[3], new byte[8], state)); domain.bootstrapGasState("");
		double kinetic = domain.particleKineticEnergyJoules();
		long started = System.nanoTime();
		var candidate = new CpuFluidSolverBackend().evaluate(new FluidStepRequest(0, domain.captureAuthoritativeState(), List.of(), false, 0, true));
		double millis = (System.nanoTime() - started) / 1e6;
		var restored = timing ? candidate.claimPreparedCpuDomain() : FluidDomain.restoreAuthoritativeState(candidate.state());
		if (timing) System.out.println("resolved shear level=" + level + " inputLiquidFragments=" + cells.size() + " fullBackendMillis=" + millis + " outputLiquidFragments=" + restored.liquidGeometryState.cells().size());
		var expected = LiquidConvexRegion.of(shape.transformed(new double[]{1, 0.125 * config.timeStep(), 0, 0, 1, 0, 0, 0, 1},
				new LiquidPolyhedron.Point(0.5, 0.5, 0.5), new LiquidPolyhedron.Point(0, 0, 0)));
		for (var cell : LiquidGeometryStateMapper.restore(restored.liquidGeometryState)) {
			var actual = LiquidConvexRegion.of(cell.geometry()); var inside = actual;
			for (var face : expected.faces()) inside = inside.clip(face.plane());
			if (!inside.measure().isVolume(actual.measure())) throw new AssertionError("Production continuous shear created staircase exterior geometry");
		}
		close(restored.particleKineticEnergyJoules(), kinetic, 1e-17);
	}
	private static void productionGeometryPipelineRefinesGasSupport() {
		var sheet = new LiquidGeometryRefinement.Cell(1, 0, 0, 0,
				LiquidPolyhedron.box(0, 0.25 - 1.0 / 1024, 0, 0.5, 0.25 + 1.0 / 1024, 0.5));
		var state = LiquidGeometryStateMapper.capture(List.of(sheet), 1, new int[]{0}, new int[]{0}, new double[]{0, 0.125, 0});
		byte[] collision = new byte[8]; java.util.Arrays.fill(collision, (byte) 0xff); collision[0] = 0;
		var config = new FluidSolverConfig(0.5F, 1000, 0, 0.01F, 512, 1e-5F, 0.45F, 0.002F, 0.8F);
		var domain = new FluidDomain(2, 2, 2, config);
		domain.restoreParticleState(new FlipParticleState(2, 2, 2, 0.5F, 1, 1.0F / 256,
				new float[]{0.25F, 0.25F, 0.25F, 1.0F / 256}, new float[]{0, 0.125F, 0}, collision, state));
		domain.sealDomainBoundary(); var gasBefore = domain.bootstrapGasState("");
		var candidate = new CpuFluidSolverBackend().evaluate(new FluidStepRequest(0, domain.captureAuthoritativeState(), List.of(), false, 0, true));
		var next = FluidDomain.restoreAuthoritativeState(candidate.state()); var gasAfter = next.exportGasState("");
		if (gasAfter.geometry().cells().stream().noneMatch(c -> c.level() > 1)
				|| !gasBefore.nodes().stream().map(cc.ecisr.mmm.fluid.air.GasStateSnapshot.Node::inventory).toList()
					.equals(gasAfter.nodes().stream().map(cc.ecisr.mmm.fluid.air.GasStateSnapshot.Node::inventory).toList()))
			throw new AssertionError("Production refinement changed finite gas stock or omitted finer support");
		if (!(next.gasNodeGaugePressurePa(1) < 0 && next.gasNodeGaugePressurePa(2) > 0))
			throw new AssertionError("Production gas projection did not use the moving sheet capacities");
	}
	private static void productionGeometryPipelinePreservesGravity() {
		var liquid = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0.4375, 0.6875, 0.4375, 0.5625, 0.8125, 0.5625));
		var state = LiquidGeometryStateMapper.capture(List.of(liquid), 1, new int[]{0});
		var config = new FluidSolverConfig(0.5F, 1000, 9.81F, 0.01F, 512, 1e-5F, 0.45F, 0.002F, 0.8F);
		var domain = new FluidDomain(2, 2, 2, config);
		domain.restoreParticleState(new FlipParticleState(2, 2, 2, 0.5F, 1, 1.0F / 64,
				new float[]{0.5F, 0.75F, 0.5F, 1.0F / 64}, new float[3], new byte[8], state));
		domain.bootstrapGasState("");
		for (int step = 1; step <= 5; step++) {
			var before = domain.captureAuthoritativeState();
			var candidate = new CpuFluidSolverBackend().evaluate(new FluidStepRequest(step, before, List.of(), false, 0, true));
			domain = FluidDomain.restoreAuthoritativeState(candidate.state());
			var moments = LiquidGeometryStateMapper.restore(domain.liquidGeometryState).stream().map(LiquidGeometryRefinement.Cell::moments)
					.reduce(LiquidPolyhedron.Moments.ZERO, LiquidPolyhedron.Moments::add);
			double expectedSpeed = -(double) config.gravity() * config.timeStep() * step;
			close(moments.volume(), 1.0 / 512, 1e-17);
			close(moments.centroid(1), 0.75 - (double) config.gravity() * config.timeStep() * config.timeStep() * step * (step + 1) / 2, 1e-14);
			for (var cell : domain.liquidGeometryState.cells()) close(cell.copyVelocity()[1], expectedSpeed, 1e-14);
			if (domain.cellLiquidVolumeTransport != null || domain.particles.count() != 1)
				throw new AssertionError("Geometric production step entered legacy transport or changed inventory groups");
		}
	}
	private static void movingLiquidWallTransportsItsGasContacts() { movingLiquidWallTransportsItsGasContacts(ignored -> { }); }
	static void movingLiquidWallTransportsItsGasContacts(java.util.function.Consumer<FluidDomain> initialize) {
		var sheet = new LiquidGeometryRefinement.Cell(1, 0, 0, 0,
				LiquidPolyhedron.box(0, 0.25 - 1.0 / 1024, 0, 0.5, 0.25 + 1.0 / 1024, 0.5));
		var cells = LiquidGeometryRefinement.children(sheet).stream().flatMap(c -> LiquidGeometryRefinement.children(c).stream()).toList();
		double speed = 0.125, dt = 0.0625;
		var incoming = new double[3 * cells.size()]; for (int c = 0; c < cells.size(); c++) incoming[3 * c + 1] = speed;
		var state = LiquidGeometryStateMapper.capture(cells, 1, new int[]{0}, new int[cells.size()], incoming);
		byte[] collision = new byte[8]; java.util.Arrays.fill(collision, (byte) 0xff); collision[0] = 0;
		var domain = new LiquidGeometryDomainPartition(2, 2, 2, 0.5F, collision, cells, 4096);
		var input = new LiquidGeometryPhaseInput(state, domain, true, 4096); var previous = new ResolvedGasGeometry(input);
		var rates = new LiquidExactPolygon.Fraction[input.mesh.faces.size()]; java.util.Arrays.fill(rates, LiquidExactPolygon.Fraction.ZERO);
		for (int f = 0; f < rates.length; f++) {
			var face = input.mesh.faces.get(f);
			if (input.fragments[face.first()] >= 0 || face.second() >= 0 && input.fragments[face.second()] >= 0)
				rates[f] = face.areaVector().get(1).multiply(LiquidExactPolygon.Fraction.of(speed));
		}
		var extension = LiquidInterfaceVelocityExtension.extend(input.mesh, input.fragments, rates, input.cellVelocity, 1e-11, 512);
		var velocity = LiquidControlVolumeVelocity.reconstruct(input.mesh, extension.volumeRate(), extension.cellVelocity());
		var config = new FluidSolverConfig(0.5F, 1000, 0, 0.05F, 200, 1e-5F, 0.45F, 0.002F, 0.8F);
		var live = new FluidDomain(2, 2, 2, config);
		live.restoreParticleState(new FlipParticleState(2, 2, 2, 0.5F, 1, 1.0F / 256,
				new float[]{0.25F, 0.25F, 0.25F, 1.0F / 256}, new float[]{0, (float) speed, 0}, collision, state));
		live.sealDomainBoundary(); initialize.accept(live);
		String fingerprint = live.chemicalParcels == null ? "" : live.chemicalParcels.registry().fingerprint();
		var gasBefore = live.gasState.geometry == null ? live.bootstrapGasState(fingerprint) : live.exportGasState(fingerprint);
		double temperature = live.gasNodeTemperatureK(1);
		var motion = new LiquidGeometryMotionPlan(state, input.mesh, input.fragments, velocity, domain.leaves, dt, 4096, 128,
				new ResolvedGasInterfaceMotion.Builder(input, gasBefore.geometry(), 4096, 4096));
		var surfaces = motion.gasInterfaces;
		var next = new LiquidGeometryPhaseInput(motion.geometry, domain, true, 4096); var successor = new ResolvedGasGeometry(next);
		var contacts = surfaces.contacts(next, successor.phaseNodes, successor.state.nodeCount(), 4096);
		for (int c = 0; c < next.fragments.length; c++) if (next.fragments[c] < 0) {
			int expected = next.mesh.cells.get(c).centroid().y() < 0.25 + speed * dt ? 1 : 2;
			if (!contacts.get(successor.phaseNodes[c]).equals(java.util.Set.of(expected)))
				throw new AssertionError("Moving liquid wall mixed its two gas contact identities");
		}
		var overlap = new ResolvedGasOverlap(previous.state, successor.state, 4096);
		if (overlap.destinations(2).size() != 2) throw new AssertionError("Moving-wall fixture did not expose Eulerian gas lineage leakage");
		var gasMotion = new ResolvedGasMotionPlan(live, next, surfaces, 4096); gasMotion.publish(live, motion);
		var gasAfter = live.exportGasState(fingerprint);
		if (!gasBefore.nodes().stream().map(cc.ecisr.mmm.fluid.air.GasStateSnapshot.Node::inventory).toList()
				.equals(gasAfter.nodes().stream().map(cc.ecisr.mmm.fluid.air.GasStateSnapshot.Node::inventory).toList())
				|| !gasBefore.externalSource().equals(gasAfter.externalSource()) || !gasBefore.escapedSink().equals(gasAfter.escapedSink()))
			throw new AssertionError("Moving liquid wall transferred gas stock between its chambers");
		if (!(live.gasNodeGaugePressurePa(1) < 0 && live.gasNodeGaugePressurePa(2) > 0))
			throw new AssertionError("Gas EOS did not use the actual moved wall capacities");
		if (live.thermalState != null && (!(live.gasNodeTemperatureK(1) < temperature && live.gasNodeTemperatureK(2) > temperature)
				|| !live.exactInternalEnergy().equals(live.thermalAudit().expectedInventory())))
			throw new AssertionError("Gas motion lost adiabatic response or its exact energy ledger");
		var restored = FluidDomain.restoreAuthoritativeState(live.captureAuthoritativeState());
		if (!restored.exportGasState(fingerprint).nodes().equals(gasAfter.nodes()) || !restored.exactInternalEnergy().equals(live.exactInternalEnergy()))
			throw new AssertionError("Gas motion successor did not survive full candidate restore");
		try {
			surfaces.contacts(next, successor.phaseNodes, successor.state.nodeCount(), 0);
			throw new AssertionError("Gas contact matching exceeded its work budget");
		} catch (IllegalStateException expected) {
			if (!expected.getMessage().contains("pair budget")) throw expected;
		}
	}
	private static void mixedVelocitySupportSurvivesNextStep() {
		var left = new LiquidGeometryRefinement.Cell(1, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 0.5, 0.5, 0.5));
		var right = new LiquidGeometryRefinement.Cell(1, 1, 0, 0, LiquidPolyhedron.box(0.5, 0, 0, 1, 0.5, 0.5));
		var mesh = new LiquidControlVolumeMesh(List.of(left, right), 1, 1000, (c, s) -> new LiquidControlVolumeMesh.Boundary(false, 0));
		var rates = new LiquidExactPolygon.Fraction[mesh.faces.size()]; var velocity = new double[6]; velocity[3] = 0.125;
		for (int f = 0; f < rates.length; f++) {
			var face = mesh.faces.get(f); var normal = face.surface().areaVector(); rates[f] = LiquidExactPolygon.Fraction.ZERO;
			if (normal.x() > 0) rates[f] = LiquidExactPolygon.Fraction.of(normal.x() * 0.125);
			else if (face.first() == 0 && normal.y() > 0) rates[f] = LiquidExactPolygon.Fraction.of(-0.03125);
		}
		var trajectory = new LiquidVelocityTrajectory(mesh, LiquidControlVolumeVelocity.reconstruct(mesh, rates, velocity));
		var support = new LiquidVelocityRegions(trajectory);
		if (support.cells(0).size() <= 1 || support.cells(1).size() != 1) throw new AssertionError("Exact uniform velocity support coalescing changed a nonuniform field");
		var source = LiquidPolyhedron.box(0.49, 0.125, 0.125, 0.499, 0.375, 0.375); var inventory = cc.ecisr.mmm.units.SignedDyadic.of(source.moments().volume());
		var moved = new LiquidCharacteristicTransport(trajectory).advect(0, source, inventory, 0.1, 16384, 256);
		var sum = LiquidPolyhedron.Moments.ZERO; var amount = cc.ecisr.mmm.units.SignedDyadic.ZERO;
		for (var fragment : moved) { sum = sum.add(fragment.geometry().moments()); amount = amount.add(fragment.inventory()); }
		if (!amount.equals(inventory)) throw new AssertionError("Mixed velocity support lost exact liquid inventory");
		close(sum.volume(), source.moments().volume(), 1e-18); close(sum.centroid(0), source.moments().centroid(0) + 0.0125, 1e-15);
		var rebuilt = characteristicImageRebuildsSpace(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 0.5, 0.5)), moved);
		mesh = rebuilt.mesh(); int count = mesh.cells.size(); var density = new double[count]; var viscosity = new double[count]; var nodes = new int[count];
		java.util.Arrays.fill(density, 1000); java.util.Arrays.fill(viscosity, 0.001); java.util.Arrays.fill(nodes, -1);
		var primary = new double[3 * count]; var traces = new double[3 * mesh.faces.size()];
		for (int c = 0; c < count; c++) primary[3 * c] = 0.125;
		for (int f = 0; f < mesh.faces.size(); f++) traces[3 * f] = 0.125;
		var mechanics = new LiquidPhaseMechanics(mesh, rebuilt.labels(), density, viscosity, List.of(), nodes, (f, c) -> 0, FluidSolverConfig.WallCondition.FREE_SLIP);
		var next = mechanics.advance(primary, traces, new double[3 * count], 0.025, 1e-11, 1e-11, 512);
		var nextField = new LiquidVelocityTrajectory(mesh, next.velocity()); var nextSupport = new LiquidVelocityRegions(nextField);
		for (int c = 0; c < count; c++) {
			if (nextSupport.cells(c).size() != 1) throw new AssertionError("Uniform motion acquired artificial velocity variation after shear");
			var u = nextSupport.cells(c).getFirst().velocity();
			if (!u[0].equals(LiquidExactPolygon.Fraction.of(0.125)) || u[1].signum() != 0 || u[2].signum() != 0)
				throw new AssertionError("Transported phase geometry lost exact uniform motion");
		}
		var nextTransport = new LiquidCharacteristicTransport(nextField);
		for (int c = 0; c < moved.size(); c++) {
			var fragment = moved.get(c); var image = nextTransport.advect(c, fragment.geometry(), fragment.inventory(), 0.025, 16384, 256);
			if (image.size() != 1 || !image.getFirst().inventory().equals(fragment.inventory())) throw new AssertionError("Uniform motion changed transported phase inventory");
			if (!LiquidConvexRegion.of(image.getFirst().geometry()).measure().same(LiquidConvexRegion.of(fragment.geometry().translated(0.003125, 0, 0)).measure()))
				throw new AssertionError("Uniform motion changed transported phase spatial moments");
		}
	}
	private static void thinLiquidLayerPreservesSeparateGasPressureNodes() {
		var root = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 1, 1));
		var layer = root.geometry().clip(0, 1, 0, 0.501).clip(0, -1, 0, -0.499);
		var regions = LiquidPhasePartition.build(root, List.of(layer), 16); var labels = regions.stream().mapToInt(LiquidPhasePartition.Region::liquid).toArray();
		var mesh = new LiquidControlVolumeMesh(regions.stream().map(LiquidPhasePartition.Region::cell).toList(), 1, 1000,
				(c, s) -> new LiquidControlVolumeMesh.Boundary(true, 0));
		var density = new double[regions.size()]; var viscosity = new double[regions.size()]; var nodes = new int[regions.size()];
		java.util.Arrays.fill(density, 1000); java.util.Arrays.fill(viscosity, 0.001); java.util.Arrays.fill(nodes, -1);
		for (int c = 0; c < regions.size(); c++) if (labels[c] < 0) nodes[c] = mesh.cells.get(c).centroid().y() < 0.5 ? 0 : 1;
		var gas = List.of(new LiquidControlVolumeProjection.GasNode(100000, 1e-6), new LiquidControlVolumeProjection.GasNode(100000, 1e-6));
		var mechanics = new LiquidPhaseMechanics(mesh, labels, density, viscosity, gas, nodes, (f, c) -> 100000, FluidSolverConfig.WallCondition.FREE_SLIP);
		var result = mechanics.advance(new double[3 * regions.size()], new double[3 * mesh.faces.size()], new double[3 * regions.size()], 0.01, 1e-11, 1e-11, 512);
		close(result.pressure().pressure()[0], 100000, 0);
		for (double pressure : result.pressure().gasPressure()) close(pressure, 100000, 0);
		for (var cell : result.velocity()) for (var tetra : cell.tetrahedra()) for (var rate : tetra.outwardFlux())
			if (rate.signum() != 0) throw new AssertionError("Balanced thin layer acquired artificial gas or liquid motion");
		var vacuumNodes = nodes.clone();
		for (int c = 0; c < vacuumNodes.length; c++) if (labels[c] < 0) vacuumNodes[c] = LiquidPhaseMechanics.PRESCRIBED_GAS_PRESSURE;
		var vacuum = new LiquidPhaseMechanics(mesh, labels, density, viscosity, List.of(), vacuumNodes,
				(f, c) -> -cc.ecisr.mmm.fluid.air.AirNetwork.REFERENCE_PRESSURE, FluidSolverConfig.WallCondition.FREE_SLIP)
				.advance(new double[3 * regions.size()], new double[3 * mesh.faces.size()], new double[3 * regions.size()], 0.01, 1e-11, 1e-11, 512);
		if (vacuum.pressure().gasPressure().length != 0) throw new AssertionError("Vacuum support acquired a finite compliance row");
		for (var cell : vacuum.velocity()) for (var tetra : cell.tetrahedra()) for (var rate : tetra.outwardFlux())
			if (rate.signum() != 0) throw new AssertionError("Balanced finite vacuum chambers acquired artificial motion");
		for (int c = 0; c < nodes.length; c++) if (labels[c] < 0) nodes[c] = 0;
		try {
			new LiquidPhaseMechanics(mesh, labels, density, viscosity, gas, nodes, (f, c) -> 100000, FluidSolverConfig.WallCondition.FREE_SLIP);
			throw new AssertionError("Separated gas chambers accepted one pressure node");
		} catch (IllegalArgumentException expected) {
			if (!expected.getMessage().contains("Separate gas chambers")) throw expected;
		}
	}
	private static void repeatedGravityPreservesUniformCharacteristic() {
		var root = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 1, 1));
		var liquid = LiquidPolyhedron.box(0.4375, 0.6875, 0.4375, 0.5625, 0.8125, 0.5625);
		var inventory = cc.ecisr.mmm.units.SignedDyadic.of(liquid.moments().volume()); var velocity = new double[3]; double dt = 0.01;
		for (int step = 1; step <= 5; step++) {
			var regions = LiquidPhasePartition.build(root, List.of(liquid), 64); var labels = regions.stream().mapToInt(LiquidPhasePartition.Region::liquid).toArray();
			var mesh = new LiquidControlVolumeMesh(regions.stream().map(LiquidPhasePartition.Region::cell).toList(), 1, 1000,
					(c, s) -> new LiquidControlVolumeMesh.Boundary(false, 0));
			var density = new double[regions.size()]; var viscosity = new double[regions.size()]; var gasNodes = new int[regions.size()];
			java.util.Arrays.fill(density, 1000); java.util.Arrays.fill(viscosity, 0.001); java.util.Arrays.fill(gasNodes, -1);
			var primary = new double[3 * regions.size()]; System.arraycopy(velocity, 0, primary, 0, 3);
			var traces = new double[3 * mesh.faces.size()]; for (int f = 0; f < mesh.faces.size(); f++) System.arraycopy(velocity, 0, traces, 3 * f, 3);
			var acceleration = new double[primary.length]; acceleration[1] = GRAVITY.y();
			var mechanics = new LiquidPhaseMechanics(mesh, labels, density, viscosity, List.of(), gasNodes, (f, gas) -> 0, FluidSolverConfig.WallCondition.FREE_SLIP);
			var result = mechanics.advance(primary, traces, acceleration, dt, 1e-11, 1e-11, 512); velocity = result.pressure().cellVelocity(); var field = result.velocity();
			close(result.viscosity().dissipatedJoules(), 0, 1e-20);
			for (var cell : field) for (var tetra : cell.tetrahedra()) {
				var exact = LiquidCharacteristicVelocity.reconstruct(tetra.geometry(), tetra.outwardFlux());
				for (int a = 0; a < 3; a++) if (!exact[a].equals(LiquidExactPolygon.Fraction.of(velocity[a])))
					throw new AssertionError("Uniform acceleration gained artificial characteristic velocity variation");
			}
			var moved = new LiquidCharacteristicTransport(new LiquidVelocityTrajectory(mesh, field)).advect(0, liquid, inventory, dt, 4096, 128);
			if (moved.size() != 1 || !moved.getFirst().inventory().equals(inventory)) throw new AssertionError("Uniform acceleration changed characteristic inventory or fragmentation");
			liquid = moved.getFirst().geometry(); close(liquid.moments().volume(), inventory.toDouble(), 1e-15);
			close(velocity[1], -9.81 * dt * step, 1e-14); close(liquid.moments().centroid(1), 0.75 - 9.81 * dt * dt * step * (step + 1) / 2, 1e-14);
		}
	}
	private static void wholeVolumeCharacteristicsPreserveGeometry() {
		var root = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 1, 1));
		var drop = LiquidPolyhedron.box(0.25, 0.25, 0.25, 0.75, 0.75, 0.75);
		var regions = LiquidPhasePartition.build(root, List.of(drop), 64); var labels = regions.stream().mapToInt(LiquidPhasePartition.Region::liquid).toArray();
		var mesh = new LiquidControlVolumeMesh(regions.stream().map(LiquidPhasePartition.Region::cell).toList(), 1, 1000,
				(c, s) -> new LiquidControlVolumeMesh.Boundary(false, 0));
		var rates = new LiquidExactPolygon.Fraction[mesh.faces.size()]; var primary = new double[3 * regions.size()]; primary[0] = 1;
		for (int f = 0; f < rates.length; f++) {
			var face = mesh.faces.get(f); rates[f] = labels[face.first()] >= 0 || face.second() >= 0 && labels[face.second()] >= 0
					? LiquidExactPolygon.Fraction.of(face.surface().areaVector().x()) : LiquidExactPolygon.Fraction.ZERO;
		}
		var extension = LiquidInterfaceVelocityExtension.extend(mesh, labels, rates, primary, 1e-11, 512);
		var transport = new LiquidCharacteristicTransport(new LiquidVelocityTrajectory(mesh,
				LiquidControlVolumeVelocity.reconstruct(mesh, extension.volumeRate(), extension.cellVelocity())));
		for (double dt : new double[] {0, 0.2}) {
			var moved = transport.advect(0, drop, cc.ecisr.mmm.units.SignedDyadic.of(0.125), dt, 4096, 128);
			if (moved.size() != 1 || !moved.getFirst().inventory().equals(cc.ecisr.mmm.units.SignedDyadic.of(0.125)))
				throw new AssertionError("Auxiliary velocity faces changed a uniform liquid image");
			var moments = moved.getFirst().geometry().moments(); close(moments.volume(), 0.125, 1e-15); close(moments.centroid(0), 0.5 + dt, 1e-15);
			if (dt == 0 && !moved.getFirst().geometry().faces().equals(drop.faces())) throw new AssertionError("Zero-time transport changed liquid geometry");
			characteristicImageRebuildsSpace(root, moved);
		}
		var cells = LiquidGeometryRefinement.children(root);
		mesh = new LiquidControlVolumeMesh(cells, 1, 1000, (c, s) -> new LiquidControlVolumeMesh.Boundary(false, 0));
		rates = new LiquidExactPolygon.Fraction[mesh.faces.size()]; primary = new double[3 * cells.size()];
		for (int c = 0; c < cells.size(); c++) { primary[3 * c] = mesh.cells.get(c).centroid().y(); primary[3 * c + 1] = 0.1; }
		for (int f = 0; f < rates.length; f++) {
			var face = mesh.faces.get(f); var area = face.surface().areaVector();
			rates[f] = LiquidExactPolygon.Fraction.of(area.x() * mesh.cells.get(face.first()).centroid().y() + area.y() * 0.1);
		}
		transport = new LiquidCharacteristicTransport(new LiquidVelocityTrajectory(mesh, LiquidControlVolumeVelocity.reconstruct(mesh, rates, primary)));
		var source = LiquidPolyhedron.box(0.1, 0.1, 0.1, 0.3, 0.49, 0.3); var inventory = cc.ecisr.mmm.units.SignedDyadic.of(source.moments().volume());
		var moved = transport.advect(0, source, inventory, 0.2, 4096, 128); var moments = LiquidPolyhedron.Moments.ZERO; var total = cc.ecisr.mmm.units.SignedDyadic.ZERO;
		for (var fragment : moved) { moments = moments.add(fragment.geometry().moments()); total = total.add(fragment.inventory()); }
		if (!total.equals(inventory)) throw new AssertionError("Crossing a velocity change lost exact liquid inventory");
		close(moments.volume(), 0.0156, 1e-15); close(moments.centroid(0), 0.25064102564102564, 1e-14);
		close(moments.centroid(1), 0.315, 1e-14); close(moments.centroid(2), 0.2, 1e-14);
		characteristicImageRebuildsSpace(root, moved);
		var oblique = source.clip(1, 2, 3, 1.3);
		moved = transport.advect(0, oblique, cc.ecisr.mmm.units.SignedDyadic.of(oblique.moments().volume()), 0.2, 4096, 128);
		var expected = oblique.clip(0, 1, 0, 0.48).translated(0.05, 0.02, 0).moments().add(
				oblique.clip(0, -1, 0, -0.48).transformed(new double[] {1, 5, 0, 0, 1, 0, 0, 0, 1},
						new LiquidPolyhedron.Point(0, 0, 0), new LiquidPolyhedron.Point(-2.35, 0.02, 0)).moments());
		moments = LiquidPolyhedron.Moments.ZERO; for (var fragment : moved) moments = moments.add(fragment.geometry().moments());
		close(moments.volume(), expected.volume(), 1e-14); close(moments.x(), expected.x(), 1e-14);
		close(moments.y(), expected.y(), 1e-14); close(moments.z(), expected.z(), 1e-14);
		characteristicImageRebuildsSpace(root, moved);
	}
	private record RebuiltSpace(LiquidControlVolumeMesh mesh, int[] labels) { }
	private static RebuiltSpace characteristicImageRebuildsSpace(LiquidGeometryRefinement.Cell root, List<LiquidCharacteristicTransport.Fragment> moved) {
		var remap = new LiquidGeometryRemap(LiquidGeometryRefinement.children(root));
		for (var fragment : moved) {
			var partition = remap.partition(fragment.geometry(), fragment.inventory()); var inventory = cc.ecisr.mmm.units.SignedDyadic.ZERO;
			for (var piece : partition) inventory = inventory.add(piece.inventory());
			if (!inventory.equals(fragment.inventory())) throw new AssertionError("Characteristic image lost exact inventory across refinement");
		}
		var liquids = moved.stream().map(f -> new LiquidGeometryRefinement.Cell(0, 0, 0, 0, f.geometry())).toList();
		var state = LiquidGeometryStateMapper.capture(liquids, 1, java.util.stream.IntStream.range(0, liquids.size()).toArray());
		var restored = LiquidGeometryStateMapper.restore(state); var restoredState = LiquidGeometryStateMapper.capture(restored, 1, state.copyMarkerCells());
		for (int c = 0; c < state.cells().size(); c++) if (LiquidGeometryState.compare(state.cells().get(c), restoredState.cells().get(c)) != 0)
			throw new AssertionError("Characteristic image lost exact geometry across backend handoff");
		var regions = LiquidPhasePartition.build(root, moved.stream().map(LiquidCharacteristicTransport.Fragment::geometry).toList(), 256);
		var mesh = new LiquidControlVolumeMesh(regions.stream().map(LiquidPhasePartition.Region::cell).toList(), 1, 1000,
				(c, s) -> new LiquidControlVolumeMesh.Boundary(false, 0));
		var sum = LiquidPolyhedron.Moments.ZERO; for (var cell : mesh.cells) sum = sum.add(cell.geometry().moments());
		var expected = root.geometry().moments(); close(sum.volume(), expected.volume(), 1e-14);
		close(sum.x(), expected.x(), 1e-14); close(sum.y(), expected.y(), 1e-14); close(sum.z(), expected.z(), 1e-14);
		var result = new LiquidControlVolumeProjection(mesh.cells, mesh.faces).project(new double[3 * mesh.cells.size()],
				new LiquidPolyhedron.Point(0, 0, 0), 0.025, 1e-11, 512);
		for (var rate : result.volumeRate()) if (rate.signum() != 0) throw new AssertionError("Transported geometry lost zero-force equilibrium");
		var velocity = LiquidControlVolumeVelocity.reconstruct(mesh, result.volumeRate(), result.cellVelocity()); new LiquidVelocityTrajectory(mesh, velocity);
		return new RebuiltSpace(mesh, regions.stream().mapToInt(LiquidPhasePartition.Region::liquid).toArray());
	}

	private static void close(double actual, double expected, double tolerance) {
		if (!(Math.abs(actual - expected) <= tolerance)) throw new AssertionError(actual + " differs from " + expected);
	}
	public static void main(String[] args) {
		if (args.length >= 1 && args[0].equals("material-surface")) {
			productionGeometryPipelineUsesMaterialSurfaceEnergy(args.length == 1 ? new double[]{60, 120} : new double[]{Double.parseDouble(args[1])}, args.length > 2 ? Integer.parseInt(args[2]) : 1);
			System.out.println("Resolved material surface energy full backend validation passed");
		} else if (args.length == 1 && args[0].equals("conforming-timing")) {
			productionGeometryPipelinePreservesContinuousShear(2, true);
			productionGeometryPipelinePreservesContinuousShear(2, true);
			productionGeometryPipelinePreservesContinuousShear(3, true);
		} else validate();
	}
}
