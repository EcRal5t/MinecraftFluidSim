package mmm.fluid;

import java.util.Arrays;

/** Analytical decay, momentum, constitutive coupling and cut-volume stability invariants. */
final class LiquidViscosityValidation {
	private LiquidViscosityValidation() { }

	public static void main(String[] args) { validate(); }

	static void validate() {
		scalarSpectralBound();
		harmonicInterfaceTraction();
		stressCouplesComponentsAndPreservesRotation();
		wallImpulseClosesMomentum();
		freeSurfaceHasNoWallDrag();
		sealedGasKeepsFreeSurfaceTraction();
		materialBindingSurvivesHandoff();
		highViscosityCommitsAndRestores();
		double shearCoarse = analyticalShearError(16), shearFine = analyticalShearError(32);
		require(shearFine < shearCoarse / 3, "shear layer diffusion must converge: " + shearCoarse + " -> " + shearFine);
		double coarse = analyticalVortexError(8), fine = analyticalVortexError(16);
		require(fine < coarse / 3, "Newtonian diffusion must converge at second order: " + coarse + " -> " + fine);
		System.out.println("Viscosity analytical relative RMS: shear " + shearCoarse + " -> " + shearFine
				+ "; coupled vortex " + coarse + " -> " + fine);
	}

	private static double analyticalShearError(int n) {
		double h = 1.0 / n, rho = 1000, mu = 1;
		var op = new ViscousStressOperator(n);
		for (int i = 0; i < n; i++) op.active(i, 0, rho * h, Math.cos(Math.PI * (i + .5) * h));
		for (int i = 1; i < n; i++) op.add(mu / h, i, 1, i - 1, -1, -1, 0, -1, 0);
		double time = 20, dt = .1 * op.stableTimeStep();
		int steps = (int) Math.ceil(time / dt); dt = time / steps;
		for (int i = 0; i < steps; i++) {
			var result = op.advance(dt);
			close(result.boundaryImpulseX(), 0, 0, "free-slip shear has no external impulse");
		}
		double attenuation = Math.exp(-mu / rho * Math.PI * Math.PI * time), error = 0, norm = 0, momentum = 0;
		for (int i = 0; i < n; i++) {
			double expected = attenuation * Math.cos(Math.PI * (i + .5) * h);
			error += Math.pow(op.velocity[i] - expected, 2); norm += expected * expected;
			momentum += op.mass[i] * op.velocity[i];
		}
		close(momentum, 0, 1e-12, "shear diffusion preserves total momentum");
		return Math.sqrt(error / norm);
	}

	private static void harmonicInterfaceTraction() {
		Grid grid = new Grid(4, 4, 4, .5); grid.upperViscosity = 9;
		var mesh = grid.mesh(FluidSolverConfig.WallCondition.FREE_SLIP);
		float[][] velocity = grid.velocity();
		for (int z = 0; z < 4; z++) for (int y = 0; y < 2; y++) for (int x = 0; x <= 4; x++)
			velocity[0][(z * 4 + y) * 5 + x] = 1;
		mesh.assemble(velocity); mesh.operator.advance(.001);
		int low = mesh.face(0, 2, 1, 2), high = mesh.face(0, 2, 2, 2);
		close(mesh.operator.force[low], -1.8 * grid.h, 1e-4, "shared-interface traction approaches harmonic viscosity at small dt");
		close(mesh.operator.force[high], 1.8 * grid.h, 1e-4, "opposite-interface traction");
		close(ViscousMacDiscretization.harmonic(1e-200, 1e200), 2e-200, 1e-214, "harmonic mean avoids product overflow");
	}

	private static void scalarSpectralBound() {
		for (double capacity : new double[]{1, 1e-9}) {
			ViscousStressOperator op = new ViscousStressOperator(2);
			op.active(0, 0, capacity, 1); op.active(1, 0, capacity, -1);
			op.add(3, 0, 1, 1, -1, -1, 0, -1, 0);
			double limit = op.stableTimeStep();
			close(limit, capacity / 3, 16 * Math.ulp(capacity / 3), "scalar spectral limit C/G");
			op.advance(limit);
			close(op.velocity[0], Math.exp(-2), 1e-14, "local diffusion follows exact exponential decay");
			close(op.velocity[0] + op.velocity[1], 0, 0, "paired momentum");
		}
		ViscousStressOperator closed = new ViscousStressOperator(2);
		closed.active(0, 0, 1e-12, 1); closed.active(1, 0, 1, 2);
		closed.add(0, 0, 1, 1, -1, -1, 0, -1, 0);
		require(closed.stableTimeStep() == Double.POSITIVE_INFINITY, "zero aperture must contribute no conductance");
		closed.advance(1);
		close(closed.velocity[0], 1, 0, "zero aperture does not exchange momentum");
		expectRejected(() -> closed.active(0, 0, 0, 1));
		expectRejected(() -> closed.active(0, 0, -1, 1));
		expectRejected(() -> closed.add(Double.POSITIVE_INFINITY, 0, 1, 1, -1, -1, 0, -1, 0));
	}

	private static void stressCouplesComponentsAndPreservesRotation() {
		ViscousStressOperator op = new ViscousStressOperator(4);
		for (int i = 0; i < 4; i++) op.active(i, i / 2, 1, i == 0 ? 1 : 0);
		op.add(1, 0, 1, 1, -1, 2, 1, 3, -1);
		op.advance(.1);
		double impulse = -Math.expm1(-.4) / 4;
		close(op.velocity[2], -impulse, 1e-15, "du/dy must transfer cross stress into v");
		close(op.velocity[3], impulse, 1e-15, "cross stress has opposite impulse");
		op.velocity[0] = 1; op.velocity[1] = 0; op.velocity[2] = -1; op.velocity[3] = 0;
		op.advance(.1);
		close(op.velocity[0], 1, 0, "rigid rotation has no shear stress");
	}

	private static void wallImpulseClosesMomentum() {
		Grid grid = new Grid(4, 4, 4, .5);
		ViscousMacDiscretization mesh = grid.mesh(FluidSolverConfig.WallCondition.NO_SLIP);
		float[][] velocity = grid.velocity();
		Arrays.fill(velocity[0], 1); Arrays.fill(velocity[1], -.3F);
		mesh.assemble(velocity);
		double[] before = momentum(mesh.operator);
		double beforeEnergy=0;for(int i=0;i<mesh.operator.mass.length;i++)
			beforeEnergy+=.5*mesh.operator.mass[i]*mesh.operator.velocity[i]*mesh.operator.velocity[i];
		double[] heat=new double[64];
		var stats = mesh.operator.advance(mesh.operator.stableTimeStep() / 2,(cell,joules)->heat[cell]+=joules);
		double afterEnergy=0;for(int i=0;i<mesh.operator.mass.length;i++)
			afterEnergy+=.5*mesh.operator.mass[i]*mesh.operator.velocity[i]*mesh.operator.velocity[i];
		close(Arrays.stream(heat).sum(),beforeEnergy-afterEnergy,1e-8*beforeEnergy,
				"local viscous row heat closes its actual kinetic-energy loss");
		double[] after = momentum(mesh.operator);
		close(after[0] - before[0], stats.boundaryImpulseX(), 1e-8, "wall X impulse");
		close(after[1] - before[1], stats.boundaryImpulseY(), 1e-8, "wall Y impulse");
		require(stats.dissipatedJoules() > 0, "fixed no-slip walls dissipate kinetic energy");
		var written = mesh.copyVelocityTo(velocity, stats);
		double[] actual = new double[3]; int block = mesh.operator.mass.length / 3;
		for (int i = 0; i < mesh.operator.mass.length; i++) actual[i / block] += mesh.operator.mass[i] * velocity[i / block][i % block];
		close(actual[0] - before[0], written.boundaryImpulseX() + written.roundoffImpulseX(), 1e-8,
				"float MAC write closes with the separate rounding impulse");
		// The spectral diagnostic reflects the smaller inertia at the same aperture.
		Grid sliver = new Grid(4, 4, 4, .5); sliver.volume = 1e-9;
		var thin = sliver.mesh(FluidSolverConfig.WallCondition.FREE_SLIP); thin.assemble(velocity);
		var full = grid.mesh(FluidSolverConfig.WallCondition.FREE_SLIP); full.assemble(velocity);
		require(thin.operator.stableTimeStep() < full.operator.stableTimeStep() * 2e-9,
				"large-aperture sliver must enter the actual stress inertia");
		grid.closed = true; var blocked = grid.mesh(FluidSolverConfig.WallCondition.FREE_SLIP); blocked.assemble(velocity);
		require(blocked.operator.stableTimeStep() == Double.POSITIVE_INFINITY, "all closed faces leave no moving DOF");
	}

	private static void freeSurfaceHasNoWallDrag() {
		Grid grid = new Grid(4, 4, 4, .5); grid.liquidLayers = 2;
		float[][] velocity = grid.velocity(); Arrays.fill(velocity[0], 1);
		var free = grid.mesh(FluidSolverConfig.WallCondition.FREE_SLIP); free.assemble(velocity);
		int face = free.face(0, 2, 1, 2);
		free.operator.advance(.001);
		close(free.operator.velocity[face], 1, 1e-8, "gas must not create tangential wall traction");
		var noSlip = grid.mesh(FluidSolverConfig.WallCondition.NO_SLIP); noSlip.assemble(velocity);
		noSlip.operator.advance(.001);
		close(noSlip.operator.velocity[face], 1, 1e-8, "remote wall diffusion remains negligible at the free surface");
	}

	private static void sealedGasKeepsFreeSurfaceTraction() {
		float[] reference = null;
		for (boolean sealed : new boolean[]{false, true}) {
			var config = new FluidSolverConfig(.5F, 1000, 0, .01F, 200, 1e-5F, .002F, .8F,
					FluidSolverConfig.WallCondition.NO_SLIP);
			var domain = new FluidDomain(4, 4, 4, config);
			domain.bindMaterialRegistry(new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE));
			domain.bindMaterialDefinitions(BuiltinFluidMaterials.createRegistry());
			if (sealed) domain.sealDomainBoundary();
			float[] filled = new float[domain.cellCount];
			for (int z = 1; z < 3; z++) for (int y = 1; y < 3; y++) for (int x = 1; x < 3; x++) filled[domain.domainFlowFields.cellIndex(x,y,z)] = 1;
			domain.setVolumes(filled);domain.domainFlowFields.ensureGrid();
			for (int face=0;face<domain.mac.velocityX.length;face++)
				if(domain.cellMacStage.massX[face]>0)domain.mac.velocityX[face]=1;
			domain.bodyForceStage.enforceSolidFaceVelocities();domain.cellMacStage.publishMomentum();
			domain.domainPhaseInventory.derivedCarrierState();
			if (sealed) require(domain.airNodeAt(2, 3, 2) > 0, "sealed gas fixture must own a finite cavity");
			new LiquidViscosityStage(domain).apply(.001F);
			close(domain.mac.velocityX[domain.domainFlowFields.xFaceIndex(2, 1, 2)], 1, 1e-6, "gas surface must not acquire wall drag");
			if (reference == null) reference = domain.mac.velocityX.clone();
			else for (int face = 0; face < reference.length; face++)
				close(domain.mac.velocityX[face], reference[face], 1e-6, "sealed and ambient gas retain the same viscous surface condition");
		}
	}

	private static void materialBindingSurvivesHandoff() {
		var config = new FluidSolverConfig(.5F, 1000, 0, .01F, 200, 1e-5F, .002F, .8F);
		FluidDomain domain = new FluidDomain(4, 4, 4, config);
		domain.bindMaterialRegistry(new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE));
		domain.bindMaterialDefinitions(BuiltinFluidMaterials.createRegistry());
		float[] filled=new float[domain.cellCount];Arrays.fill(filled,1);domain.setVolumes(filled);
		var restored = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		require(!restored.hasGranularState(), "liquid viscosity cannot require granular capability");
		require(restored.exportLiquidCellState().stocks().equals(domain.exportLiquidCellState().stocks()),
				"Material handoff must preserve authoritative cell inventory");
		require(restored.carrierViscosityPascalSeconds(restored.domainFlowFields.cellIndex(1, 1, 1)) != null,
				"material column restore must retain constitutive identity");
		require(restored.authoritativeLayout().equals(domain.authoritativeLayout()), "liquid definitions must survive private handoff");
		var stats = restored.stepWithMechanicalAudit(false, 0);
		require(stats.viscosity().stressRows() > 0 && stats.viscosity().spectralBoundPerSecond() > 0,
				"production pipeline must execute viscosity for a liquid-only domain");
	}

	private static void highViscosityCommitsAndRestores() {
        for (double mu : new double[]{1e4, 1e15}) {
            System.out.println("High-viscosity liquid mu="+mu+" Pa.s");
            var base = BuiltinFluidMaterials.createRegistry();
            var definitions = new MaterialRegistry(base.entries().stream().map(entry ->
                    new MaterialRegistry.MaterialEntry(entry.material(), java.util.List.of(
                            new MaterialRegistry.ViscositySegment(200, 400, mu, mu)))).toList(), base.pairTensions());
            var domain = new FluidDomain(2, 2, 2, new FluidSolverConfig(.5F, 1000, 0, .1F, 200, 1e-5F, .002F, .8F));
            domain.bindMaterialRegistry(new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE));
            domain.bindMaterialDefinitions(definitions);
            float[] filled=new float[domain.cellCount];Arrays.fill(filled,1);domain.setVolumes(filled);domain.domainFlowFields.ensureGrid();
            for(int z=0;z<2;z++)for(int y=0;y<=2;y++)for(int x=0;x<2;x++)
                domain.mac.velocityY[domain.domainFlowFields.yFaceIndex(x,y,z)]=.01F*((x+.5F)*domain.config.cellSize()-.5F);
            domain.bodyForceStage.enforceSolidFaceVelocities();domain.cellMacStage.publishMomentum();
            var state = domain.captureAuthoritativeState(); double volume=domain.totalVolume(),mass=domain.totalMass();
            var result = new CpuFluidSolverBackend().evaluate(new FluidStepRequest(0, state, java.util.List.of(), false, 0, true));
            var committed=result.claimPreparedCpuDomain();
            require(result.stats().viscosity().dissipatedJoules() >= 0, "High viscosity must dissipate actual kinetic energy");
            close(committed.totalVolume(),volume,0,"High viscosity preserves liquid volume");
            close(committed.totalMass(),mass,1e-10,"High viscosity preserves material mass");
            var restored=FluidDomain.restoreAuthoritativeState(committed.captureAuthoritativeState());
            restored.stepWithMechanicalAudit(false,0);
            close(restored.totalVolume(),volume,0,"Restored high-viscosity liquid advances normally");
            close(restored.totalMass(),mass,1e-10,"Restored high-viscosity liquid preserves material mass");
            require(domain.exportLiquidCellState().stocks().equals(state.liquid().stocks())
                    && Arrays.equals(domain.exportMacMomentum().copyY(),state.momentum().copyY()),
                    "Private viscosity leaves the source inventory and momentum unchanged");
        }
    }


	private static double analyticalVortexError(int n) {
		Grid grid = new Grid(n, 2 * n, 2, 1.0 / n);
		var mesh = grid.mesh(FluidSolverConfig.WallCondition.FREE_SLIP);
		float[][] velocity = grid.velocity();
		double kx = Math.PI, ky = Math.PI / 2;
		double dx = 2 * Math.sin(kx * grid.h / 2) / grid.h, dy = 2 * Math.sin(ky * grid.h / 2) / grid.h;
		for (int z = 0; z < 2; z++) for (int y = 0; y < 2 * n; y++) for (int x = 0; x <= n; x++)
			velocity[0][(z * 2 * n + y) * (n + 1) + x] = (float) (dy * Math.sin(kx * x * grid.h) * Math.cos(ky * (y + .5) * grid.h));
		for (int z = 0; z < 2; z++) for (int y = 0; y <= 2 * n; y++) for (int x = 0; x < n; x++)
			velocity[1][(z * (2 * n + 1) + y) * n + x] = (float) (-dx * Math.cos(kx * (x + .5) * grid.h) * Math.sin(ky * y * grid.h));
		mesh.assemble(velocity);
		double[] initial = mesh.operator.velocity.clone();
		// rho=1000 kg/m^3, mu=1 Pa.s, t=20 s. dt scales as h^2; spatial+temporal error is O(h^2).
		double time = 20, stable = mesh.operator.stableTimeStep();
		int steps = (int) Math.ceil(time / (stable * .1));
		double dt = time / steps;
		for (int i = 0; i < steps; i++) mesh.operator.advance(dt);
		double attenuation = Math.exp(-.001 * (kx * kx + ky * ky) * time), error = 0, norm = 0;
		for (int i = 0; i < initial.length; i++) if (mesh.operator.mass[i] > 0) {
			double expected = initial[i] * attenuation;
			error += Math.pow(mesh.operator.velocity[i] - expected, 2); norm += expected * expected;
		}
		return Math.sqrt(error / norm);
	}

	private static double[] momentum(ViscousStressOperator op) {
		double[] result = new double[3];
		// This fixture has equal extents, hence equally sized staggered component blocks.
		int block = op.mass.length / 3;
		for (int i = 0; i < op.mass.length; i++) result[i / block] += op.mass[i] * op.velocity[i];
		return result;
	}

	private static final class Grid implements ViscousMacDiscretization.Geometry {
		final int nx, ny, nz; final double h;
		double volume = 1, upperViscosity = 1; boolean closed; int liquidLayers;
		Grid(int nx, int ny, int nz, double h) { this.nx = nx; this.ny = ny; this.nz = nz; this.h = h; liquidLayers = ny; }
		ViscousMacDiscretization mesh(FluidSolverConfig.WallCondition wall) { return new ViscousMacDiscretization(nx, ny, nz, h, this, wall); }
		float[][] velocity() { return new float[][]{new float[(nx + 1) * ny * nz], new float[nx * (ny + 1) * nz], new float[nx * ny * (nz + 1)]}; }
		public boolean liquid(int cell) { return cell / nx % ny < liquidLayers; }
		public double volume(int cell) { return volume; }
		public double density(int cell) { return 1000; }
		public double viscosity(int cell) { return cell / nx % ny >= ny / 2 ? upperViscosity : 1; }
		public double aperture(int axis, int x, int y, int z) { return closed || (axis == 0 ? x == 0 || x == nx : axis == 1 ? y == 0 || y == ny : z == 0 || z == nz) ? 0 : 1; }
		public double wallArea(int cell, int axis) {
			int coordinate = axis == 0 ? cell % nx : axis == 1 ? cell / nx % ny : cell / (nx * ny);
			int size = axis == 0 ? nx : axis == 1 ? ny : nz;
			return coordinate == 0 || coordinate == size - 1 ? 1 : 0;
		}
	}
	private static void close(double actual, double expected, double tolerance, String message) { require(Math.abs(actual - expected) <= tolerance, message + ": " + actual + " != " + expected); }
	private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
	private static void expectRejected(Runnable operation) {
		try { operation.run(); } catch (IllegalStateException expected) { return; }
		throw new AssertionError("Invalid viscous matrix accepted");
	}
}
