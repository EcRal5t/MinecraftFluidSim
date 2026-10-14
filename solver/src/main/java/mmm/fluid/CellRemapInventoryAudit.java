package mmm.fluid;

import mmm.units.SignedDyadic;
import mmm.compute.CpuWorkBatch;
import java.util.Arrays;

/** Checks the prospective carrier columns against the untouched source before publication. */
final class CellRemapInventoryAudit {
	private CellRemapInventoryAudit() { }

	static SignedDyadic validate(FluidDomain domain, CellRemapMarkerColumns markers,
			ConservativeLiquidFlux flux, BallisticLiquidRemap ballistic) {
		return validate(domain, markers, flux.resultingVolumes(), ballistic);
	}
	static SignedDyadic validate(FluidDomain domain, CellRemapMarkerColumns markers,
			SignedDyadic[] plannedVolumes, BallisticLiquidRemap ballistic) {
		if (markers.size() > domain.maximumParticleCount) throw new IllegalStateException("Cell remap exceeds its marker budget");
		SignedDyadic[] energyRoundoff = new SignedDyadic[1];
		CpuWorkBatch.run(3, markers.size() >= 2_048 ? 1 : 3, (from, until) -> {
			for (int audit = from; audit < until; audit++) {
				switch (audit) {
					case 0 -> volume(domain, markers, plannedVolumes, ballistic);
					case 1 -> energyRoundoff[0] = massAndEnergy(domain, markers);
					case 2 -> momentumAndKineticEnergy(domain, markers, ballistic);
					default -> throw new AssertionError("Unknown remap audit");
				}
			}
		});
		return energyRoundoff[0];
	}

	private static void volume(FluidDomain domain, CellRemapMarkerColumns markers,
			SignedDyadic[] plannedVolumes, BallisticLiquidRemap ballistic) {
		int[] heads = new int[domain.cellCount], next = new int[markers.size()]; Arrays.fill(heads, -1);
		for (int p = 0; p < markers.size(); p++) {
			next[p] = heads[markers.cell[p]]; heads[markers.cell[p]] = p;
		}
		SignedDyadic[] volume = ExactCellVolumeSums.sum(markers.volume, markers.size(), heads, next);
		for (int c = 0; c < volume.length; c++) if (!volume[c].equals(plannedVolumes[c].add(ballistic.reserved[c])))
			throw new IllegalStateException("Cell remap changed its shared volume flux");
	}

	private static SignedDyadic massAndEnergy(FluidDomain domain, CellRemapMarkerColumns markers) {
		int materialCount = domain.materialRegistry == null ? 1 : domain.materialRegistry.materials().size();
		double[][] before = new double[materialCount][], after = new double[materialCount][];
		int[] beforeCount = new int[materialCount], afterCount = new int[materialCount];
		double[] rounding = new double[materialCount];
		double[] energyBefore = domain.thermalState == null ? null : new double[domain.particles.count()];
		double[] energyAfter = domain.thermalState == null ? null : markers.energy;
		double energyRounding = 0;
		for (int p = 0; p < domain.particles.count(); p++) {
			int material = domain.particles.materialHandle(p); double mass = domain.particles.mass(p);
			if (before[material] == null) before[material] = new double[domain.particles.count()];
			before[material][beforeCount[material]++] = mass; rounding[material] += Math.ulp(mass);
			if (energyBefore != null) energyBefore[p] = domain.thermalState.liquidInternalEnergy(p);
		}
		for (int p = 0; p < markers.size(); p++) {
			int material = markers.material[p];
			if (after[material] == null) after[material] = new double[markers.size()];
			after[material][afterCount[material]++] = markers.mass[p]; rounding[material] += Math.ulp(markers.mass[p]);
			if (energyAfter != null) energyRounding += Math.ulp(markers.energy[p]);
		}
		for (int material = 0; material < materialCount; material++) {
			if ((before[material] == null) != (after[material] == null)) throw new IllegalStateException("Cell remap changed its material inventory");
			if (before[material] == null) continue;
			SignedDyadic difference = SignedDyadic.sum(after[material], afterCount[material])
					.subtract(SignedDyadic.sum(before[material], beforeCount[material]));
			if (Math.abs(difference.toDouble()) > rounding[material])
				throw new IllegalStateException("Cell remap changed material mass beyond binary64 storage roundoff");
		}
		SignedDyadic difference = energyBefore == null ? SignedDyadic.ZERO : SignedDyadic.sum(energyAfter).subtract(SignedDyadic.sum(energyBefore));
		if (Math.abs(difference.toDouble()) > energyRounding)
			throw new IllegalStateException("Cell remap changed heat inventory beyond binary64 storage roundoff");
		return difference;
	}

	private static void momentumAndKineticEnergy(FluidDomain domain, CellRemapMarkerColumns markers,
			BallisticLiquidRemap ballistic) {
		double[] before = new double[3], after = new double[3], rounding = new double[3];
		double kineticBefore = 0, kineticAfter = 0, kineticRounding = 0;
		for (int p = 0; p < domain.particles.count(); p++) {
			double mass = domain.particles.mass(p);
			for (int a = 0; a < 3; a++) {
				double v = switch (a) { case 0 -> domain.particles.velocityX(p); case 1 -> domain.particles.velocityY(p); default -> domain.particles.velocityZ(p); };
				before[a] += mass * v; rounding[a] += Math.ulp(mass * v) + Math.ulp(mass) * Math.abs(v);
				kineticBefore += 0.5 * domain.unitScale.kilograms(mass) * v * v;
			}
		}
		for (int p = 0; p < markers.size(); p++) {
			if (!(Double.isFinite(markers.velocity[0][p]) && Double.isFinite(markers.velocity[1][p]) && Double.isFinite(markers.velocity[2][p])))
				throw new IllegalStateException("Invalid remapped velocity");
			for (int a = 0; a < 3; a++) {
				double v = (float) markers.velocity[a][p], ulp = Math.ulp((float) markers.velocity[a][p]);
				after[a] += markers.mass[p] * v;
				rounding[a] += markers.mass[p] * ulp + Math.ulp(markers.mass[p] * v) + Math.ulp(markers.mass[p]) * Math.abs(v);
				kineticAfter += 0.5 * domain.unitScale.kilograms(markers.mass[p]) * v * v;
				kineticRounding += domain.unitScale.kilograms(markers.mass[p]) * (Math.abs(v) * ulp + ulp * ulp);
			}
		}
		for (int a = 0; a < 3; a++) if (Math.abs(after[a] - before[a] - ballistic.collisionMomentum[a])
				> rounding[a] + markers.size() * Math.ulp(before[a]))
			throw new IllegalStateException("Cell remap changed momentum beyond storage roundoff");
		if (kineticAfter > kineticBefore + ballistic.collisionWorkJoules + kineticRounding + markers.size() * Math.ulp(kineticBefore))
			throw new IllegalStateException("Cell remap created kinetic energy beyond storage roundoff");
	}
}
