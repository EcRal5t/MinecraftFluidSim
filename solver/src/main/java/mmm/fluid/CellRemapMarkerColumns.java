package mmm.fluid;

import mmm.chemistry.ChemicalMixtureRecord;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/** Fixed prospective marker columns, allocated after the complete count preflight. */
final class CellRemapMarkerColumns implements Iterable<CellCarrierRemapPlan.Marker> {
	final int[] cell, material;
	final boolean[] indicator;
	final float[] volume;
	final double[] mass, energy;
	final double[][] offset, velocity;
	final ChemicalMixtureRecord[] mixture;

	CellRemapMarkerColumns(int count) {
		cell = new int[count]; material = new int[count]; indicator = new boolean[count]; volume = new float[count];
		mass = new double[count]; energy = new double[count]; offset = new double[3][count]; velocity = new double[3][count];
		mixture = new ChemicalMixtureRecord[count];
	}

	int size() { return cell.length; }

	void set(int p, int c, int m, boolean i, float v, double rawMass,
			double x, double y, double z, double vx, double vy, double vz, ChemicalMixtureRecord composition, double u) {
		cell[p] = c; material[p] = m; indicator[p] = i; volume[p] = v; mass[p] = rawMass;
		offset[0][p] = x; offset[1][p] = y; offset[2][p] = z;
		velocity[0][p] = vx; velocity[1][p] = vy; velocity[2][p] = vz; mixture[p] = composition; energy[p] = u;
	}

	void copyFrom(List<CellCarrierRemapPlan.Marker> source, int start) {
		for (int p = 0; p < source.size(); p++) {
			var marker = source.get(p);
			set(start + p, marker.cell(), marker.material(), marker.indicator(), marker.volume(), marker.mass(),
					marker.offsetX(), marker.offsetY(), marker.offsetZ(), marker.velocityX(), marker.velocityY(), marker.velocityZ(), marker.mixture(), marker.energy());
		}
	}

	/** Materializes records for inspection; production reductions and publication read the columns directly. */
	@Override public Iterator<CellCarrierRemapPlan.Marker> iterator() {
		return new Iterator<>() {
			private int p;
			@Override public boolean hasNext() { return p < size(); }
			@Override public CellCarrierRemapPlan.Marker next() {
				if (!hasNext()) throw new NoSuchElementException();
				int n = p++;
				return new CellCarrierRemapPlan.Marker(cell[n], material[n], indicator[n], volume[n], mass[n],
						offset[0][n], offset[1][n], offset[2][n], velocity[0][n], velocity[1][n], velocity[2][n], mixture[n], energy[n]);
			}
		};
	}
}
