package mmm.fluid;

import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Arrays;

/** Candidate motion of isolated liquid parcels outside the connected incompressible velocity field. */
final class BallisticLiquidRemap {
	final ArrayList<CellCarrierRemapPlan.Marker> markers = new ArrayList<>();
	final SignedDyadic[] reserved;
	final double collisionWorkJoules;
	final double[] collisionMomentum = new double[3];

	/** No independent point motion during a solid representation remap. */
	BallisticLiquidRemap(int cellCount) {
		reserved = new SignedDyadic[cellCount]; Arrays.fill(reserved, SignedDyadic.ZERO);
		collisionWorkJoules = 0;
	}

	BallisticLiquidRemap(FluidDomain domain, float dt) {
		reserved = new SignedDyadic[domain.cellCount]; Arrays.fill(reserved, SignedDyadic.ZERO);
		boolean[] active = domain.gridToParticleStage.advectionCells();
		var original = new ArrayList<Integer>();
		for (int p = 0; p < domain.particles.count(); p++) if (!active[domain.particleCellIndex(p)]) original.add(p);
		int count = original.size(); float[][] position = new float[3][count], velocity = new float[3][count], motion = new float[3][count];
		double before = 0, after = 0;
		double[] momentumBefore = new double[3];
		for (int i = 0; i < count; i++) {
			int p = original.get(i);
			position[0][i] = domain.particles.positionX(p); position[1][i] = domain.particles.positionY(p); position[2][i] = domain.particles.positionZ(p);
			motion[0][i] = velocity[0][i] = domain.particles.velocityX(p);
			motion[1][i] = velocity[1][i] = domain.particles.velocityY(p);
			motion[2][i] = velocity[2][i] = domain.particles.velocityZ(p);
			for (int a = 0; a < 3; a++) {
				before += 0.5 * domain.unitScale.kilograms(domain.particles.mass(p)) * velocity[a][i] * velocity[a][i];
				momentumBefore[a] += domain.particles.mass(p) * velocity[a][i];
			}
		}
		domain.particleCollision.advect(position[0], position[1], position[2], motion[0], motion[1], motion[2],
				velocity[0], velocity[1], velocity[2], count, dt);
		float h = domain.config.cellSize();
		for (int i = 0; i < count; i++) {
			int p = original.get(i), cell = domain.particleCellIndex(position[0][i], position[1][i], position[2][i]);
			var chemical = domain.chemicalParcels;
			markers.add(new CellCarrierRemapPlan.Marker(cell, domain.particles.materialHandle(p), chemical != null && chemical.indicator(p),
					domain.particles.volume(p), domain.particles.mass(p),
					position[0][i] / (double) h - cell % domain.sizeX - 0.5,
					position[1][i] / (double) h - cell / domain.sizeX % domain.sizeY - 0.5,
					position[2][i] / (double) h - cell / (domain.sizeX * domain.sizeY) - 0.5,
					velocity[0][i], velocity[1][i], velocity[2][i], chemical == null ? null : chemical.record(p),
					domain.thermalState == null ? 0 : domain.thermalState.liquidInternalEnergy(p)));
			reserved[cell] = reserved[cell].add(SignedDyadic.of(domain.particles.volume(p)));
			for (int a = 0; a < 3; a++) {
				after += 0.5 * domain.unitScale.kilograms(domain.particles.mass(p)) * velocity[a][i] * velocity[a][i];
				collisionMomentum[a] += domain.particles.mass(p) * velocity[a][i];
			}
		}
		collisionWorkJoules = after - before;
		for (int a = 0; a < 3; a++) collisionMomentum[a] -= momentumBefore[a];
	}
}
