package mmm.fluid;

import mmm.granular.ParticulateMaterial;
import mmm.granular.SolidVolume;

/** Equilibrium smooth-wall closure for unresolved near-bed shear; no synthetic turbulent velocity. */
final class GranularBedShear {
	private GranularBedShear() { }

	/** Bed traction removes horizontal carrier energy to pay for the resolved parcel's lift. */
	static LiftWork planLift(FluidDomain domain, int cell, double joules) {
		if (!Double.isFinite(joules)) throw new IllegalArgumentException("Non-finite bed lift work");
		if (joules <= 0) return new LiftWork(new int[0], 1);
		int y = cell / domain.sizeX % domain.sizeY;
		var markers = new java.util.ArrayList<Integer>();
		double kinetic = 0;
		for (int c : new int[]{cell, y + 1 < domain.sizeY ? cell + domain.sizeX : -1}) {
			if (c < 0) continue;
			for (int p = domain.markerGrid.cellMarkerHead[c]; p >= 0; p = domain.markerGrid.particleCellNext[p]) {
				double u = domain.particles.velocityX(p), w = domain.particles.velocityZ(p);
				kinetic += .5 * domain.unitScale.kilograms(domain.particles.mass(p)) * (u * u + w * w);
				markers.add(p);
			}
		}
		if (joules > kinetic) return null;
		return new LiftWork(markers.stream().mapToInt(Integer::intValue).toArray(), Math.sqrt(1 - joules / kinetic));
	}

	record LiftWork(int[] markers, double scale) {
		double[] apply(FluidDomain domain) {
			var impulse = new double[3];
			for (int p : markers) {
				double mass = domain.unitScale.kilograms(domain.particles.mass(p));
				float x = domain.particles.velocityX(p), z = domain.particles.velocityZ(p);
				float nextX = (float) (scale * x), nextZ = (float) (scale * z);
				impulse[0] += mass * (x - nextX); impulse[2] += mass * (z - nextZ);
				domain.particles.setVelocityX(p, nextX); domain.particles.setVelocityZ(p, nextZ);
			}
			domain.gridDirty = true;
			return impulse;
		}
	}

	static double shields(FluidDomain domain, int cell, ParticulateMaterial material) {
		int x = cell % domain.sizeX, y = cell / domain.sizeX % domain.sizeY;
		int z = cell / (domain.sizeX * domain.sizeY);
		double h = domain.config.cellSize();
		double bed = (y + domain.granularState.deposits.cellBulkVolumeSvq(cell)
				/ (double) SolidVolume.SVQ_PER_CELL) * h;
		float px = (float) ((x + .5) * h), pz = (float) ((z + .5) * h);
		// The deposit cell carries Darcy pore flow. Sample the first free-flow cell above it.
		if (y + 1 >= domain.sizeY) return 0;
		float py = (float) ((y + 1.5) * h);
		if (!(py > bed)) return 0;
		int sampleCell = domain.cellIndex(x, (int) (py / h), z);
		// Extrapolated MAC velocities in dry cells cannot erode a bed. Check the actual carrier.
		if (domain.markerGrid.markerCount[sampleCell] == 0) return 0;
		for (double cy = bed; cy < py; cy += h / 4) {
			if (domain.markerGrid.markerCount[domain.cellIndex(x, (int) (cy / h), z)] == 0
					|| domain.geometry.cutCells.pointInsideSolid(px, (float) cy, pz, (float) h)) return 0;
		}
		if (domain.geometry.cutCells.pointInsideSolid(px, py, pz, (float) h)) return 0;
		var liquid = domain.granularMechanicalStage.sample(px, py, pz);
		if (!liquid.hasLiquid()) return 0;
		double speed = Math.hypot(liquid.velocityX(), liquid.velocityZ());
		if (speed == 0) return 0;
		double stress = shearStress(speed, py - bed, liquid.density(), liquid.dynamicViscosity())
				* liquid.submergedFraction();
		double weight = Math.max(0, material.referenceDensity() - liquid.density())
				* domain.config.gravity() * material.primaryGrainDiameterMeters();
		return weight > 0 ? Math.min(Double.MAX_VALUE, stress / weight) : Double.MAX_VALUE;
	}

	/** Spalding law: y+ = u+ + (exp(k*u+)-1-k*u+-(k*u+)²/2-(k*u+)³/6)/E.
	 * Solving Re_y = u+ y+ retains tau=mu*U/y as Re tends to zero and the logarithmic layer at high Re.
	 * Constants and formulation: OpenFOAM nutUSpaldingWallFunction (kappa=0.41, E=9.8).
	 */
	static double shearStress(double speed, double distance, double density, double viscosity) {
		if (!(Double.isFinite(speed) && speed >= 0 && Double.isFinite(distance) && distance > 0
				&& Double.isFinite(density) && density > 0 && Double.isFinite(viscosity) && viscosity > 0)) {
			throw new IllegalArgumentException("Invalid bed shear input");
		}
		if (speed == 0) return 0;
		double reynolds = speed * distance * density / viscosity;
		if (!Double.isFinite(reynolds)) throw new ArithmeticException("Bed shear Reynolds overflow");
		if (reynolds < 1e-8) return viscosity * speed / distance;
		double low = 0, high = Math.sqrt(reynolds);
		// The viscous solution bounds u+ from above. Tighten for large Reynolds numbers.
		if (reynolds > 1) high = Math.min(high, (Math.log(reynolds) + Math.log(9.8)) / .41 + 1);
		for (int iteration = 0; iteration < 56; iteration++) {
			double u = .5 * (low + high), a = .41 * u;
			double tail;
			if (a < 1) {
				double term = a * a * a * a / 24;
				tail = term;
				for (int n = 5; n <= 18; n++) { term *= a / n; tail += term; }
			} else tail = Math.expm1(a) - a - a * a / 2 - a * a * a / 6;
			if (u * (u + tail / 9.8) > reynolds) high = u;
			else low = u;
		}
		double frictionVelocity = speed / (.5 * (low + high));
		double stress = density * frictionVelocity * frictionVelocity;
		if (!Double.isFinite(stress)) throw new ArithmeticException("Bed shear stress overflow");
		return stress;
	}
}
