package cc.ecisr.mmm.fluid;

import java.util.Arrays;
import java.util.List;

/** Separates transport, wall sampling and resampling errors from global inventory conservation. */
public final class MarkerDistributionExperiment {
	private MarkerDistributionExperiment() { }

	public static void main(String[] args) {
		for (String scenario : args.length == 0 ? new String[] {"wall", "transport", "analytic", "free-analytic", "reseed", "dam"} : args) {
			switch (scenario) {
				case "wall" -> wallNormalProfile();
				case "transport" -> { reversibleTransport(0.05F); reversibleTransport(0.025F); }
				case "dam" -> damDistribution();
				case "reseed" -> reseedMoment();
				case "analytic" -> { analyticDeformation(12, 2); analyticDeformation(24, 2); analyticDeformation(12, 4); }
				case "free-analytic" -> freeSurfaceConvergence();
				default -> throw new IllegalArgumentException("Unknown marker experiment: " + scenario);
			}
		}
	}

	private static void wallNormalProfile() {
		var domain = new FluidDomain(4, 4, 2, config(1, 0, 0.025F), 256);
		for (int z = 0; z < 2; z++) domain.setSolid(2, 1, z, true);
		// A discrete stream function supported above a solid cell: all closed-face
		// averages and every cell's MAC divergence vanish.
		for (int z = 0; z < 2; z++) {
			for (int y = 0; y < 4; y++) for (int x = 0; x <= 4; x++)
				domain.mac.velocityX[domain.xFaceIndex(x, y, z)] = node(x, y + 1) - node(x, y);
			for (int y = 0; y <= 4; y++) for (int x = 0; x < 4; x++)
				domain.mac.velocityY[domain.yFaceIndex(x, y, z)] = node(x, y) - node(x + 1, y);
		}
		var sample = new DivergenceConformingVelocityField.Sample();
		DivergenceConformingVelocityField.sample(domain.mac, domain.geometry.cutCells, 4, 4, 2, 1, 2, 1.75F, 0.5F, sample);
		System.out.printf("wall closedFaceAverage=%g reconstructedNormal=%g m/s%n",
				domain.mac.velocityX[domain.xFaceIndex(2, 1, 0)], sample.x);
	}

	private static float node(int x, int y) { return x == 2 && y == 3 ? 1 : 0; }

	private static void reversibleTransport(float dt) {
		int n = 12;
		var domain = new FluidDomain(n, n, 2, config(0.5F, 0, dt), n * n * 2 * 8);
		for (int z = 0; z < 2; z++) for (int y = 0; y < n; y++) for (int x = 0; x < n; x++)
			domain.setVolume(x, y, z, 1);
		domain.ensureGrid();
		float[] before = domain.exportParticleState().copyPositionVolume();
		for (int z = 0; z < 2; z++) {
			for (int y = 0; y < n; y++) for (int x = 0; x <= n; x++)
				domain.mac.velocityX[domain.xFaceIndex(x, y, z)] = (float) ((stream(x, y + 1, n) - stream(x, y, n)) / 0.5);
			for (int y = 0; y <= n; y++) for (int x = 0; x < n; x++)
				domain.mac.velocityY[domain.yFaceIndex(x, y, z)] = (float) ((stream(x, y, n) - stream(x + 1, y, n)) / 0.5);
		}
		int steps = Math.round(20 / dt);
		for (int s = 0; s < steps; s++) domain.particleAdvectionStage.advect(dt);
		measure(domain, "transport dt=" + dt + " t=20");
		for (int f = 0; f < domain.mac.velocityX.length; f++) domain.mac.velocityX[f] *= -1;
		for (int f = 0; f < domain.mac.velocityY.length; f++) domain.mac.velocityY[f] *= -1;
		for (int s = 0; s < steps; s++) domain.particleAdvectionStage.advect(dt);
		double squared = 0, maximum = 0;
		for (int p = 0; p < domain.particleCount(); p++) {
			double dx = domain.particles.positionX(p) - before[4 * p], dy = domain.particles.positionY(p) - before[4 * p + 1];
			squared += dx * dx + dy * dy; maximum = Math.max(maximum, Math.hypot(dx, dy));
		}
		measure(domain, "transport dt=" + dt + " reversed");
		System.out.printf("  reverse RMS/h=%g max/h=%g%n", Math.sqrt(squared / domain.particleCount()) / 0.5, maximum / 0.5);
	}

	private static double stream(int x, int y, int n) {
		return 0.5 * Math.pow(Math.sin(Math.PI * x / n) * Math.sin(Math.PI * y / n), 2);
	}

	private static void analyticDeformation(int n, int samples) {
		float h = 6.0F / n;
		int cells = n * n * 2, count = cells * samples * samples * samples;
		var domain = new FluidDomain(n, n, 2, config(h, 0, 0.025F), count);
		float[] positions = new float[count * 4];
		int ordinal = 0;
		for (int z = 0; z < 2; z++) for (int y = 0; y < n; y++) for (int x = 0; x < n; x++)
			for (int sz = 0; sz < samples; sz++) for (int sy = 0; sy < samples; sy++) for (int sx = 0; sx < samples; sx++) {
				positions[ordinal++] = (x + (sx + 0.5F) / samples) * h;
				positions[ordinal++] = (y + (sy + 0.5F) / samples) * h;
				positions[ordinal++] = (z + (sz + 0.5F) / samples) * h;
				positions[ordinal++] = 1.0F / (samples * samples * samples);
			}
		domain.restoreParticleState(new FlipParticleState(n, n, 2, h, count, cells,
				positions, new float[count * 3], new byte[cells]));
		// r'=r, theta'=theta+omega(r)*t has determinant one analytically. Compact
		// support leaves every outer wall fixed; no velocity interpolation or integration runs.
		for (int p = 0; p < count; p++) {
			double x = positions[4 * p] - 3, y = positions[4 * p + 1] - 3;
			double angle = deformationAngle(x, y);
			domain.particles.setPositionX(p, (float) (3 + x * Math.cos(angle) - y * Math.sin(angle)));
			domain.particles.setPositionY(p, (float) (3 + x * Math.sin(angle) + y * Math.cos(angle)));
		}
		measure(domain, "analytic map N=" + n + " samples/axis=" + samples);
	}

	static void damDistribution() {
		var domain = new FluidDomain(12, 8, 6, config(0.5F, 9.81F, 0.025F), 12 * 8 * 6 * 8);
		for (int z = 0; z < 6; z++) for (int x = 0; x < 12; x++) domain.setSolid(x, 0, z, true);
		for (int z = 0; z < 6; z++) for (int y = 1; y <= 6; y++) for (int x = 0; x < 6; x++)
			domain.setVolume(x, y, z, 1);
		double inventory = domain.totalVolumeExact();
		double mass = domain.totalMass(), massRoundoff = 0, previousMassRoundoff = 0;
		boolean conservative = CellLiquidVolumeTransport.enabled();
		if (conservative) previousMassRoundoff = auditConservativeDistribution(domain);
		measure(domain, "dam step=0");
		var backend = new CpuFluidSolverBackend();
		for (int s = 1; s <= 5000; s++) {
			domain = backend.evaluate(new FluidStepRequest(s, domain.captureAuthoritativeState(), List.of(), false, 0)).claimPreparedCpuDomain();
			if (domain.totalVolumeExact() != inventory) throw new AssertionError("Marker experiment changed volume inventory");
			if (conservative) {
				double roundoff = auditConservativeDistribution(domain);
				massRoundoff += previousMassRoundoff + roundoff; previousMassRoundoff = roundoff;
				if (Math.abs(domain.totalMass() - mass) > massRoundoff)
					throw new AssertionError("Sustained liquid transport exceeded its accumulated mass roundoff");
			}
			if (s == 100 || s == 500 || s == 1000 || s == 5000) {
				measure(domain, "dam step=" + s);
				if (conservative && domain.cellLiquidVolumeTransport != null) {
					var moment = domain.cellLiquidVolumeTransport.momentAudit;
					System.out.printf("  last remap: projectedGroups=%d maxCentroidProjection=%g m massProjection=%s kg*m massStorage=%s kg*m volumeResidual=%s fraction*m unadvancedMassMoment=%s kg*m%n",
							moment.projectedGroups(), moment.maximumCentroidProjection(), java.util.Arrays.toString(moment.massProjection()),
							java.util.Arrays.toString(moment.massStorage()), java.util.Arrays.toString(moment.volumeResidual()), java.util.Arrays.toString(moment.unadvancedMassMoment()));
				}
			}
		}
		if (conservative) System.out.printf("  conservative dam: 5000 steps, exact cell capacities and pressure inventory matched; massError=%g bound=%g%n",
				domain.totalMass() - mass, massRoundoff);
	}

	private static double auditConservativeDistribution(FluidDomain domain) {
		domain.ensureGrid();
		for (int cell = 0; cell < domain.cellCount; cell++) {
			var volume = domain.markerGrid.exactCellVolumeInventory[cell];
			if (volume.signum() < 0 || volume.compareTo(cc.ecisr.mmm.units.SignedDyadic.of(domain.geometry.cutCells.openCellVolume(cell))) > 0)
				throw new AssertionError("Sustained liquid transport exceeded cell capacity at " + cell);
			if (domain.projection.pressureVolume[cell] != volume.toDouble())
				throw new AssertionError("Pressure geometry diverged from transported inventory at " + cell);
			if (!volume.equals(cc.ecisr.mmm.units.SignedDyadic.of(1))) continue;
			if (domain.markerGrid.markerCount[cell] != 8)
				throw new AssertionError("Full liquid cell lost its spatial integration rule");
			int slots = 0;
			for (int p = domain.markerGrid.cellMarkerHead[cell]; p >= 0; p = domain.markerGrid.particleCellNext[p]) {
				int slot = 0;
				for (int axis = 0; axis < 3; axis++) {
					double position = switch (axis) { case 0 -> domain.particles.positionX(p); case 1 -> domain.particles.positionY(p); default -> domain.particles.positionZ(p); };
					double offset = position / domain.config.cellSize() - Math.floor(position / domain.config.cellSize()) - 0.5;
					if (Math.abs(offset) != 0.25) throw new AssertionError("Saturated-cell quadrature left its geometric rule");
					if (offset > 0) slot |= 1 << axis;
				}
				if ((slots & 1 << slot) != 0) throw new AssertionError("Full-cell integration point was duplicated");
				slots |= 1 << slot;
			}
		}
		double roundoff = 0;
		for (int p = 0; p < domain.particleCount(); p++) roundoff += Math.ulp(domain.particles.mass(p));
		return roundoff;
	}

	private static void freeSurfaceConvergence() {
		double coarse = freeSurfaceDeformation(12, 2), medium = freeSurfaceDeformation(12, 4);
		double fine = freeSurfaceDeformation(12, 8), finerGrid = freeSurfaceDeformation(24, 8);
		if (!(medium < coarse && fine < medium && finerGrid < fine))
			throw new AssertionError("Free-surface spatial integration did not converge under independent quadrature/grid refinement");
	}

	/** Cell coverage comes from the inverse analytic map, independently of marker splatting. */
	private static double freeSurfaceDeformation(int n, int samples) {
		float h = 6.0F / n;
		int cells = n * n * 2, count = cells * samples * samples;
		var domain = new FluidDomain(n, n, 2, config(h, 0, 0.025F), count);
		float[] positions = new float[count * 4]; int ordinal = 0;
		for (int z = 0; z < 2; z++) for (int y = 0; y < n; y++) for (int x = 0; x < n / 2; x++)
			for (int sz = 0; sz < 2; sz++) for (int sy = 0; sy < samples; sy++) for (int sx = 0; sx < samples; sx++) {
				double dx = (x + (sx + 0.5) / samples) * h - 3, dy = (y + (sy + 0.5) / samples) * h - 3;
				double angle = deformationAngle(dx, dy);
				positions[ordinal++] = (float) (3 + dx * Math.cos(angle) - dy * Math.sin(angle));
				positions[ordinal++] = (float) (3 + dx * Math.sin(angle) + dy * Math.cos(angle));
				positions[ordinal++] = (z + (sz + 0.5F) / 2) * h;
				positions[ordinal++] = 1.0F / (2 * samples * samples);
			}
		domain.restoreParticleState(new FlipParticleState(n, n, 2, h, count, cells * 0.5F,
				positions, new float[count * 3], new byte[cells]));
		domain.closedLiquidVolume.prepare(); Arrays.fill(domain.projection.pressureVolume, 0);
		for (int p = 0; p < count; p++) domain.splatPressureVolume(positions[4 * p], positions[4 * p + 1], positions[4 * p + 2], positions[4 * p + 3]);
		double error = 0, maximum = 0, total = 0, referenceDifference = 0; int referenceSamples = 128;
		for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
			double reference = inverseMapCoverage(x, y, h, referenceSamples);
			referenceDifference += 2 * Math.abs(reference - inverseMapCoverage(x, y, h, referenceSamples / 2));
			for (int z = 0; z < 2; z++) {
				double reconstructed = domain.projection.pressureVolume[domain.cellIndex(x, y, z)];
				error += Math.abs(reconstructed - reference); maximum = Math.max(maximum, reconstructed); total += reconstructed;
			}
		}
		if (Math.abs(total - cells * 0.5) > count * Math.ulp(cells * 0.5))
			throw new AssertionError("Free-surface footprint changed total volume beyond reduction roundoff");
		System.out.printf("free analytic N=%d samples/xy=%d particles=%d L1/V=%g peak/capacity=%g volumeError=%g reference/xy=%d referenceDelta/V=%g%n",
				n, samples, count, error / (cells * 0.5), maximum, total - cells * 0.5, referenceSamples, referenceDifference / (cells * 0.5));
		return error / (cells * 0.5);
	}

	private static double inverseMapCoverage(int x, int y, float h, int samples) {
		int inside = 0;
		for (int sy = 0; sy < samples; sy++) for (int sx = 0; sx < samples; sx++) {
			double dx = (x + (sx + 0.5) / samples) * h - 3, dy = (y + (sy + 0.5) / samples) * h - 3;
			double angle = deformationAngle(dx, dy);
			if (dx * Math.cos(angle) + dy * Math.sin(angle) < 0) inside++;
		}
		return (double) inside / (samples * samples);
	}

	private static double deformationAngle(double x, double y) {
		double r2 = x * x + y * y;
		return r2 < 6.25 ? 6 * Math.pow(1 - r2 / 6.25, 2) : 0;
	}

	private static FluidSolverConfig config(float h, float gravity, float dt) {
		return new FluidSolverConfig(h, 1000, gravity, dt, 160, 1e-5F, 0.4F, 0.002F, 0.8F);
	}

	private static void measure(FluidDomain domain, String label) {
		domain.closedLiquidVolume.prepare();
		double[] volume = new double[domain.cellCount], x = new double[volume.length], y = new double[volume.length], z = new double[volume.length];
		double[] previousFootprint = domain.projection.pressureVolume.clone();
		Arrays.fill(domain.projection.pressureVolume, 0);
		for (int p = 0; p < domain.particleCount(); p++) {
			int c = domain.particleCellIndex(p); double v = domain.particles.volume(p);
			volume[c] += v; x[c] += v * domain.particles.positionX(p); y[c] += v * domain.particles.positionY(p); z[c] += v * domain.particles.positionZ(p);
			domain.splatPressureVolume(domain.particles.positionX(p), domain.particles.positionY(p), domain.particles.positionZ(p), (float) v);
		}
		double rawMaximum = 0, footprintMaximum = 0, footprintMinimum = Double.POSITIVE_INFINITY, rawExcess = 0, footprintExcess = 0, moment = 0;
		int occupied = 0;
		int subcentre = 0;
		for (int c = 0; c < volume.length; c++) {
			if (volume[c] > 0) occupied++;
			double capacity = domain.geometry.cutCells.openCellVolume(c);
			if (capacity > 0) {
				footprintMinimum = Math.min(footprintMinimum, domain.projection.pressureVolume[c] / capacity);
				if (domain.projection.pressureVolume[c] <= 0.5 * capacity) subcentre++;
			}
			rawMaximum = Math.max(rawMaximum, volume[c]); footprintMaximum = Math.max(footprintMaximum, domain.projection.pressureVolume[c]);
			rawExcess += Math.max(0, volume[c] - capacity); footprintExcess += Math.max(0, domain.projection.pressureVolume[c] - capacity);
		}
		for (int p = 0; p < domain.particleCount(); p++) {
			int c = domain.particleCellIndex(p); double v = domain.particles.volume(p);
			double dx = domain.particles.positionX(p) - x[c] / volume[c], dy = domain.particles.positionY(p) - y[c] / volume[c], dz = domain.particles.positionZ(p) - z[c] / volume[c];
			moment += v * (dx * dx + dy * dy + dz * dz);
		}
		double total = domain.totalVolumeExact(), h = domain.config.cellSize();
		domain.closedLiquidVolume.apply(domain.projection.pressureVolume, domain.particles, domain.pressureGranularCoupling, domain.markerGrid);
		double constrainedMin = Double.POSITIVE_INFINITY, constrainedMax = 0, constrainedTotal = 0;
		int constrainedSubcentre = 0;
		for (int c = 0; c < volume.length; c++) {
			double capacity = domain.geometry.cutCells.openCellVolume(c), liquid = domain.projection.pressureVolume[c];
			constrainedTotal += liquid;
			if (capacity <= 0) continue;
			constrainedMin = Math.min(constrainedMin, liquid / capacity); constrainedMax = Math.max(constrainedMax, liquid / capacity);
			if (liquid <= 0.5 * capacity) constrainedSubcentre++;
		}
		System.arraycopy(previousFootprint, 0, domain.projection.pressureVolume, 0, previousFootprint.length);
		System.out.printf("%s particles=%d occupied=%d maxRaw=%g footprintMin/Max=%g/%g excessRaw=%g%% excessFootprint=%g%% subcentre=%d moment/(Vh2)=%g K=%g J%n",
				label, domain.particleCount(), occupied, rawMaximum, footprintMinimum, footprintMaximum, 100 * rawExcess / total, 100 * footprintExcess / total, subcentre, moment / (total * h * h), domain.particleKineticEnergyJoules());
		System.out.printf("  closed-volume geometry min/max=%g/%g subcentre=%d volumeError=%g%n",
				constrainedMin, constrainedMax, constrainedSubcentre, constrainedTotal - total);
	}

	private static void reseedMoment() {
		int count = 16, capacity = 32;
		float[] x = new float[capacity], y = new float[capacity], z = new float[capacity], volume = new float[capacity];
		double[] mass = new double[capacity];
		for (int p = 0; p < count; p++) {
			x[p] = 1.125F + 0.25F * (p & 3); y[p] = 1.25F + 0.5F * (p >> 2 & 1); z[p] = 1.25F + 0.5F * (p >> 3 & 1);
			volume[p] = 0.0625F; mass[p] = 1000 * volume[p];
		}
		double before = secondMoment(x, y, z, volume, count);
		var result = new FlipParticleReseeder(4, 4, 4, 1, capacity).reseed(new boolean[64], x, y, z,
				new float[capacity], new float[capacity], new float[capacity], volume, mass, new int[capacity], new int[capacity],
				null, count, false, (px, py, pz) -> true);
		double after = secondMoment(x, y, z, volume, result.particleCount());
		System.out.printf("reseed markers=%d->%d moment=%g->%g loss=%g%% volumeError=%g%n", count, result.particleCount(), before, after, 100 * (1 - after / before), result.ledgerError());
	}

	private static double secondMoment(float[] x, float[] y, float[] z, float[] volume, int count) {
		double total = 0, cx = 0, cy = 0, cz = 0;
		for (int p = 0; p < count; p++) { total += volume[p]; cx += volume[p] * x[p]; cy += volume[p] * y[p]; cz += volume[p] * z[p]; }
		double moment = 0;
		for (int p = 0; p < count; p++) moment += volume[p] * (Math.pow(x[p] - cx / total, 2) + Math.pow(y[p] - cy / total, 2) + Math.pow(z[p] - cz / total, 2));
		return moment;
	}
}
