package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.List;

/** Affine-consistent cell/face gradient and its geometric stabilization metric. */
final class ControlVolumeGradient {
	final int[] indices, sign;
	final LiquidPolyhedron.Point[] basis, displacement;
	final double[] weight;
	final double volume;
	final LiquidExactPolygon.Fraction exactVolume;
	final LiquidExactPolygon.Fraction[][] exactBasis, exactDisplacement;
	final LiquidExactPolygon.Fraction[] exactWeight;

	static ControlVolumeGradient[] build(List<LiquidControlVolumeProjection.Cell> cells, List<LiquidControlVolumeProjection.Face> faces) {
		var incidence = new ArrayList<ArrayList<Integer>>();
		for (int c = 0; c < cells.size(); c++) incidence.add(new ArrayList<>());
		for (int f = 0; f < faces.size(); f++) {
			var face = faces.get(f); incidence.get(face.first()).add(f);
			if (face.second() >= 0) incidence.get(face.second()).add(f);
		}
		var result = new ControlVolumeGradient[cells.size()];
		for (int c = 0; c < result.length; c++) result[c] = new ControlVolumeGradient(c, cells, faces, incidence.get(c));
		return result;
	}

	ControlVolumeGradient(int cell, List<LiquidControlVolumeProjection.Cell> cells,
			List<LiquidControlVolumeProjection.Face> faces, List<Integer> incidence) {
		indices = incidence.stream().mapToInt(Integer::intValue).toArray(); sign = new int[indices.length];
		basis = new LiquidPolyhedron.Point[indices.length]; displacement = basis.clone(); weight = new double[indices.length];
		var geometry = cells.get(cell).geometry(); var center = geometry.centroidCoordinates();
		exactVolume = LiquidConvexRegion.of(geometry).measure().volume(); volume = exactVolume.rounded();
		exactBasis = new LiquidExactPolygon.Fraction[indices.length][3]; exactDisplacement = new LiquidExactPolygon.Fraction[indices.length][3];
		exactWeight = new LiquidExactPolygon.Fraction[indices.length];
		double[] closure = new double[3], moment = new double[9], closureMagnitude = new double[3], momentMagnitude = new double[9];
		for (int i = 0; i < indices.length; i++) {
			var face = faces.get(indices[i]); var vector = face.surface().areaVector(); sign[i] = face.first() == cell ? 1 : -1;
			var areaSquared = LiquidExactPolygon.Fraction.of(0); var areaDistance = geometry.projectedHalfWidth(face.areaVector());
			for (int a = 0; a < 3; a++) {
				var area = sign[i] > 0 ? face.areaVector().get(a) : face.areaVector().get(a).negate();
				exactBasis[i][a] = area.divide(exactVolume); exactDisplacement[i][a] = face.centroid().get(a).subtract(center[a]);
				areaSquared = areaSquared.add(area.multiply(area));
			}
			if (areaDistance.numerator().signum() <= 0) throw new IllegalArgumentException("Control-volume diffusion requires a positive geometric length");
			displacement[i] = observed(exactDisplacement[i]); basis[i] = observed(exactBasis[i]);
			exactWeight[i] = areaSquared.divide(areaDistance); weight[i] = exactWeight[i].rounded();
			for (int a = 0; a < 3; a++) {
				double orientedArea = sign[i] * vector.coordinate(a); closure[a] += orientedArea; closureMagnitude[a] += Math.abs(orientedArea);
				for (int b = 0; b < 3; b++) {
					double value = orientedArea * displacement[i].coordinate(b);
					moment[3 * a + b] += value; momentMagnitude[3 * a + b] += Math.abs(value);
				}
			}
		}
		for (int a = 0; a < 3; a++) {
			if (Math.abs(closure[a]) > 64 * indices.length * Math.ulp(closureMagnitude[a]))
				throw new IllegalArgumentException("Control-volume face areas do not close");
			for (int b = 0; b < 3; b++) {
				double expected = a == b ? volume : 0;
				if (Math.abs(moment[3 * a + b] - expected) > 64 * indices.length * Math.ulp(Math.max(volume, momentMagnitude[3 * a + b])))
					throw new IllegalArgumentException("Control-volume faces do not reproduce affine gradients");
			}
		}
	}
	private static LiquidPolyhedron.Point observed(LiquidExactPolygon.Fraction[] point) {
		return new LiquidPolyhedron.Point(point[0].rounded(), point[1].rounded(), point[2].rounded());
	}

	/** Diagonal of the scalar gradient energy; selected == -1 denotes the cell unknown. */
	double basisEnergy(int selected) {
		double gx = 0, gy = 0, gz = 0;
		for (int i = 0; i < indices.length; i++) {
			double value = selected < 0 ? -1 : i == selected ? 1 : 0;
			gx += basis[i].x() * value; gy += basis[i].y() * value; gz += basis[i].z() * value;
		}
		double energy = volume * (gx * gx + gy * gy + gz * gz);
		for (int i = 0; i < indices.length; i++) {
			var d = displacement[i]; double value = selected < 0 ? -1 : i == selected ? 1 : 0;
			double error = value - (d.x() * gx + d.y() * gy + d.z() * gz);
			energy += weight[i] * error * error;
		}
		return energy;
	}
}
