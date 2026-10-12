package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.granular.GranularCouplingFields;
import cc.ecisr.mmm.chemistry.ExactRational;
import cc.ecisr.mmm.units.SignedDyadic;
import java.math.BigInteger;

/** PLIC reconstructed from current liquid inventory, shared by pressure and cell transport. */
final class FlipPressurePhaseGeometry {

	private final int sizeX;
	private final int sizeY;
	private final int sizeZ;
	private final double[] fraction;
	private final float[] normalX;
	private final float[] normalY;
	private final float[] normalZ;
	private final double[] planeOffset;
	private final PlicPlaneOffset.Result[] offsetBrackets;
	private final SignedDyadic[] poreVolumes;
	private final PlicComponentGeometry[] components;
	private byte[] collisionMasks;
	private long revision;
	private final double[] faceX = new double[8], faceY = new double[8];
	private final double[] clippedX = new double[8], clippedY = new double[8];

	FlipPressurePhaseGeometry(int sizeX, int sizeY, int sizeZ) {
		if (sizeX < 2 || sizeY < 2 || sizeZ < 2) {
			throw new IllegalArgumentException("Pressure phase grid is too small");
		}
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		int cellCount = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		this.fraction = new double[cellCount];
		this.normalX = new float[cellCount];
		this.normalY = new float[cellCount];
		this.normalZ = new float[cellCount];
		this.planeOffset = new double[cellCount];
		this.offsetBrackets = new PlicPlaneOffset.Result[cellCount];
		this.poreVolumes = new SignedDyadic[cellCount];
		java.util.Arrays.fill(poreVolumes,SignedDyadic.ZERO);
		this.components = new PlicComponentGeometry[cellCount];
		this.collisionMasks = new byte[cellCount];
	}

	boolean matchesShape(int x, int y, int z) { return sizeX == x && sizeY == y && sizeZ == z; }
	long revision() { return revision; }
	int collisionMask(int cell) { return Byte.toUnsignedInt(collisionMasks[cell]); }
	SignedDyadic poreVolume(int cell) { return poreVolumes[cell]; }

	void rebuild(
			boolean[] solid, double[] reconstructedVolume, FlipCutCellGeometry cutCells,
			GranularCouplingFields granular, boolean[] pressureFluid
	) {
		rebuild(solid, reconstructedVolume, cutCells, granular, pressureFluid, null, null, 0);
	}

	void rebuild(
			boolean[] solid, double[] reconstructedVolume, FlipCutCellGeometry cutCells,
			GranularCouplingFields granular, boolean[] pressureFluid,
			MarkerGridIndex markers, LiquidParticleStore particles, float cellSize
	) {
		rebuild(solid, reconstructedVolume, cutCells, granular, pressureFluid, markers, particles, cellSize, null);
	}

	/** Reconstructs the same full grid from actual future volume and position moments in one edited cell. */
	void rebuild(
			boolean[] solid, double[] reconstructedVolume, FlipCutCellGeometry cutCells,
			GranularCouplingFields granular, boolean[] pressureFluid,
			MarkerGridIndex markers, LiquidParticleStore particles, float cellSize, PhaseLiquidGeometryEdit edit
	) {
		rebuildLiquidEdits(solid, reconstructedVolume, cutCells, granular, pressureFluid, markers, particles, cellSize,
				edit == null ? java.util.List.of() : java.util.List.of(edit));
	}

	/** Reconstructs all fraction gradients and component planes from a common future stock overlay. */
	void rebuildLiquidEdits(
			boolean[] solid, double[] reconstructedVolume, FlipCutCellGeometry cutCells,
			GranularCouplingFields granular, boolean[] pressureFluid,
			MarkerGridIndex markers, LiquidParticleStore particles, float cellSize, java.util.List<PhaseLiquidGeometryEdit> edits
	) {
		if (solid.length != fraction.length || reconstructedVolume.length != fraction.length
				|| pressureFluid.length != fraction.length || edits == null || !edits.isEmpty() && markers == null
				|| markers != null && (particles == null || !(cellSize > 0) || !Float.isFinite(cellSize))) {
			throw new IllegalArgumentException("Pressure phase arrays disagree with the cell count");
		}
		PhaseLiquidGeometryEdit[] overlay = edits.isEmpty() ? null : new PhaseLiquidGeometryEdit[fraction.length];
		for (var edit : edits) {
			if (edit == null || edit.cell() >= fraction.length || overlay[edit.cell()] != null
					|| edit.collisionMask() != cutCells.mask(edit.cell()))
				throw new IllegalArgumentException("Repeated PLIC liquid edit or mismatched collision mask");
			overlay[edit.cell()] = edit;
		}
		revision = Math.addExact(revision,1);
		collisionMasks = cutCells.copyMicroMasks();
		java.util.Arrays.fill(components,null);
		java.util.Arrays.fill(offsetBrackets,null);
		for (int cell = 0; cell < fraction.length; cell++) {
			float geometricOpen = cutCells.openCellVolume(cell);
			float openVolume = granular == null ? geometricOpen
					: granular.effectiveOpenCellVolume(cell, geometricOpen);
			poreVolumes[cell] = solid[cell] ? SignedDyadic.ZERO : SignedDyadic.of(openVolume);
			double liquid = overlay != null && overlay[cell] != null ? overlay[cell].volume().toDouble() : reconstructedVolume[cell];
			fraction[cell] = solid[cell] || openVolume <= 0.0F ? 0.0F : clamp(liquid / openVolume, 0.0, 1.0);
			pressureFluid[cell] = false;
			normalX[cell] = normalY[cell] = normalZ[cell] = 0.0F; planeOffset[cell] = 0;
		}
		var integration = new LiquidFluxGeometry(cutCells);
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int cell = cellIndex(x, y, z);
			PhaseLiquidGeometryEdit edit = overlay == null ? null : overlay[cell];
			if (solid[cell] || !(fraction[cell] > 0.0F) || fraction[cell] >= 1) continue;
			double center = fraction[cell];
			float nx = (float) -(sampleFraction(x + 1, y, z, center, solid)
					- sampleFraction(x - 1, y, z, center, solid));
			float ny = (float) -(sampleFraction(x, y + 1, z, center, solid)
					- sampleFraction(x, y - 1, z, center, solid));
			float nz = (float) -(sampleFraction(x, y, z + 1, center, solid)
					- sampleFraction(x, y, z - 1, center, solid));
			float gradientX = nx, gradientY = ny, gradientZ = nz;
			if (markers != null && momentNormal(cell, x, y, z, cutCells, markers, particles, cellSize, edit)) {
				nx = normalX[cell]; ny = normalY[cell]; nz = normalZ[cell];
			}
			double lengthSquared = (double) nx * nx + (double) ny * ny + (double) nz * nz;
			if (lengthSquared == 0) {
				nx = airDirection(x + 1, y, z, solid) - airDirection(x - 1, y, z, solid);
				ny = airDirection(x, y + 1, z, solid) - airDirection(x, y - 1, z, solid);
				nz = airDirection(x, y, z + 1, solid) - airDirection(x, y, z - 1, solid);
				lengthSquared = (double) nx * nx + (double) ny * ny + (double) nz * nz;
			}
			if (lengthSquared == 0) ny = 1.0F;
			else {
				double inverseLength = 1.0 / Math.sqrt(lengthSquared);
				nx = (float) (nx * inverseLength);
				ny = (float) (ny * inverseLength);
				nz = (float) (nz * inverseLength);
			}
			normalX[cell] = nx;
			normalY[cell] = ny;
			normalZ[cell] = nz;
			var stock = markers == null ? null : edit != null ? edit
					: PhaseLiquidGeometryEdit.current(cell,cutCells.mask(cell),cellSize,markers,particles);
			float open = granular == null ? cutCells.openCellVolume(cell)
					: granular.effectiveOpenCellVolume(cell,cutCells.openCellVolume(cell));
			offsetBrackets[cell] = stock == null ? null : PlicPlaneOffset.solve(~cutCells.mask(cell)&255,nx,ny,nz,
						PlicComponentGeometry.ratio(stock.volume(),SignedDyadic.of(open))
								.multiply(Integer.bitCount(~cutCells.mask(cell)&255)).divide(8));
			planeOffset[cell] = offsetBrackets[cell] == null ? integration.offset(cell,nx,ny,nz,center*cutCells.openCellVolume(cell))
					: offsetBrackets[cell].offset();
			if (markers != null && CellRemapMicroTopology.components(cutCells.mask(cell)).length > 1) {
				components[cell] = PlicComponentGeometry.capture(stock,
						CellRemapMicroTopology.capacities(cutCells.mask(cell),SignedDyadic.of(open)),
						x,y,z,cellSize,gradientX,gradientY,gradientZ);
			}
		}
		for (int cell = 0; cell < fraction.length; cell++) {
			if (solid[cell] || !(fraction[cell] > 0.5F)) continue;
			if (fraction[cell] >= 1) pressureFluid[cell] = true;
			else pressureFluid[cell] = planeOffset[cell]
					> 0.5 * ((double) normalX[cell] + normalY[cell] + normalZ[cell]);
		}
	}

	/** Monotone moment estimate: a centred phase retains the fraction-gradient direction. */
	private boolean momentNormal(
			int cell, int x, int y, int z, FlipCutCellGeometry cut,
			MarkerGridIndex markers, LiquidParticleStore particles, float cellSize, PhaseLiquidGeometryEdit edit
	) {
		SignedDyadic volume = edit == null ? SignedDyadic.ZERO : edit.volume();
		SignedDyadic[] first = edit == null ? new SignedDyadic[] {SignedDyadic.ZERO, SignedDyadic.ZERO, SignedDyadic.ZERO}
				: new SignedDyadic[] {edit.firstMomentX(), edit.firstMomentY(), edit.firstMomentZ()};
		if (edit == null) for (int p = markers.cellMarkerHead[cell]; p >= 0; p = markers.particleCellNext[p]) {
			SignedDyadic weight = SignedDyadic.of(particles.volume(p));
			volume = volume.add(weight);
			first[0] = first[0].add(weight.multiply(SignedDyadic.of(particles.positionX(p))));
			first[1] = first[1].add(weight.multiply(SignedDyadic.of(particles.positionY(p))));
			first[2] = first[2].add(weight.multiply(SignedDyadic.of(particles.positionZ(p))));
		}
		if (volume.signum() == 0) return false;
		SignedDyadic metricVolume = volume.multiply(SignedDyadic.of(cellSize));
		double mx = centeredMoment(first[0], metricVolume, x);
		double my = centeredMoment(first[1], metricVolume, y);
		double mz = centeredMoment(first[2], metricVolume, z);
		int count = 8, lowX = 4, lowY = 4, lowZ = 4;
		if (cut.mask(cell) != 0) {
			count = lowX = lowY = lowZ = 0;
			for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) for (int c = 0; c < 2; c++) {
				if (!cut.microCellOpen(cell, a, b, c)) continue;
				count++; lowX += 1 - a; lowY += 1 - b; lowZ += 1 - c;
			}
		}
		if (count == 0) return false;
		double nx = momentDirection(mx, lowX, count);
		double ny = momentDirection(my, lowY, count);
		double nz = momentDirection(mz, lowZ, count);
		double scale = Math.max(Math.abs(nx), Math.max(Math.abs(ny), Math.abs(nz)));
		if (scale == 0) return false;
		if (Double.isInfinite(scale)) {
			nx = Double.isInfinite(nx) ? Math.signum(nx) : 0;
			ny = Double.isInfinite(ny) ? Math.signum(ny) : 0;
			nz = Double.isInfinite(nz) ? Math.signum(nz) : 0;
		} else { nx /= scale; ny /= scale; nz /= scale; }
		normalX[cell] = (float) nx; normalY[cell] = (float) ny; normalZ[cell] = (float) nz;
		return true;
	}

	static double centeredMoment(SignedDyadic first, SignedDyadic metricVolume, int coordinate) {
		SignedDyadic centered = first.subtract(metricVolume.multiply(SignedDyadic.of(coordinate + .5)));
		int shift = centered.exponent() - metricVolume.exponent();
		BigInteger numerator = centered.significand(), denominator = metricVolume.significand();
		return (shift >= 0 ? new ExactRational(numerator.shiftLeft(shift), denominator)
				: new ExactRational(numerator, denominator.shiftLeft(-shift))).toDouble();
	}

	static double momentDirection(double centre, int lowCount, int openCount) {
		double lower = lowCount == 0 ? 0 : -.5, upper = lowCount == openCount ? 0 : .5;
		double openCentre = .25 * (openCount - 2.0 * lowCount) / openCount;
		if (centre <= lower) return Double.POSITIVE_INFINITY;
		if (centre >= upper) return Double.NEGATIVE_INFINITY;
		return (openCentre - centre) / ((upper - centre) * (centre - lower));
	}

	float liquidFraction(int cell) { return (float) fraction[cell]; }
	float planeNormal(int cell, int axis) { return axis == 0 ? normalX[cell] : axis == 1 ? normalY[cell] : normalZ[cell]; }
	double planeOffset(int cell) { return planeOffset[cell]; }
	boolean hasComponentPlanes(int cell) { return components[cell] != null; }
	float planeNormal(int cell, int micro, int axis) {
		var plane = components[cell] == null ? null : components[cell].plane(micro);
		return components[cell] == null ? planeNormal(cell,axis) : plane == null ? 0 : plane.normal(axis);
	}
	double planeOffset(int cell, int micro) {
		var plane = components[cell] == null ? null : components[cell].plane(micro);
		return components[cell] == null ? planeOffset[cell] : plane == null ? 0 : plane.offset();
	}
	PlicPlaneOffset.Result offsetBracket(int cell,int micro) {
		var plane = components[cell] == null ? null : components[cell].plane(micro);
		return components[cell] == null ? offsetBrackets[cell] : plane == null ? null : plane.bracket();
	}
	double liquidFractionExact(int cell, int micro) {
		var plane = components[cell] == null ? null : components[cell].plane(micro);
		return components[cell] == null ? fraction[cell] : plane == null ? 0 : plane.fraction();
	}
	double componentPoreScale(int cell,int micro,double fallback) {
		var plane = components[cell] == null ? null : components[cell].plane(micro);
		return components[cell] == null ? fallback : plane == null ? 0 : plane.porosity();
	}
	double geometricLiquidVolume(int cell,FlipCutCellGeometry cut) {
		if (components[cell] == null) return fraction[cell]*cut.openCellVolume(cell);
		double volume = 0;
		for (int micro=0;micro<8;micro++) if (components[cell].plane(micro)!=null) volume += liquidFractionExact(cell,micro)/8;
		return volume;
	}

	/** Area occupied by both PLICs on their shared open face; left is the negative-axis cell. */
	double sharedLiquidFaceArea(int left, int right, int axis, FlipCutCellGeometry cut) {
		if (!(fraction[left] > 0) || !(fraction[right] > 0)) return 0;
		if (cut.mask(left) == 0 && cut.mask(right) == 0) return wetRectangle(left, right, axis, 0, 0, 1);
		double area = 0;
		for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) {
			boolean open = switch (axis) {
				case 0 -> cut.microCellOpen(left, 1, a, b) && cut.microCellOpen(right, 0, a, b);
				case 1 -> cut.microCellOpen(left, b, 1, a) && cut.microCellOpen(right, b, 0, a);
				default -> cut.microCellOpen(left, a, b, 1) && cut.microCellOpen(right, a, b, 0);
			};
			if (open) area += wetRectangle(left, right, axis, .5 * a, .5 * b, .5);
		}
		return area;
	}

	private double wetRectangle(int left, int right, int axis, double x, double y, double span) {
		int minus = microAtFace(left,axis,1,x,y), plus = microAtFace(right,axis,0,x,y);
		if (liquidFractionExact(left,minus) == 0 || liquidFractionExact(right,plus) == 0) return 0;
		faceX[0] = faceX[3] = x; faceX[1] = faceX[2] = x + span;
		faceY[0] = faceY[1] = y; faceY[2] = faceY[3] = y + span;
		int tangent = (axis + 1) % 3, other = (axis + 2) % 3;
		int count = liquidFractionExact(left,minus) >= 1 ? 4 : clipFace(4, planeNormal(left,minus,tangent), planeNormal(left,minus,other),
				planeOffset(left,minus) - planeNormal(left,minus,axis));
		if (liquidFractionExact(right,plus) < 1) count = clipFace(count, planeNormal(right,plus,tangent), planeNormal(right,plus,other), planeOffset(right,plus));
		double twiceArea = 0;
		for (int i = 1; i + 1 < count; i++) twiceArea += (faceX[i] - faceX[0]) * (faceY[i + 1] - faceY[0])
				- (faceY[i] - faceY[0]) * (faceX[i + 1] - faceX[0]);
		return .5 * Math.abs(twiceArea);
	}

	private int clipFace(int count, double nx, double ny, double offset) {
		if (count == 0) return 0;
		int next = 0, previous = count - 1;
		double previousDistance = nx * faceX[previous] + ny * faceY[previous] - offset;
		for (int current = 0; current < count; current++) {
			double distance = nx * faceX[current] + ny * faceY[current] - offset;
			if ((distance <= 0) != (previousDistance <= 0)) {
				double t = previousDistance / (previousDistance - distance);
				clippedX[next] = faceX[previous] + t * (faceX[current] - faceX[previous]);
				clippedY[next++] = faceY[previous] + t * (faceY[current] - faceY[previous]);
			}
			if (distance <= 0) { clippedX[next] = faceX[current]; clippedY[next++] = faceY[current]; }
			previous = current; previousDistance = distance;
		}
		System.arraycopy(clippedX, 0, faceX, 0, next); System.arraycopy(clippedY, 0, faceY, 0, next);
		return next;
	}

	/** Copied local face polygon; tangential coordinates use cyclic axis order. */
	double[][] wetFacePolygon(int cell, int axis, double coordinate, double tangent, double other, double span) {
		int micro = microAtFace(cell,axis,coordinate,tangent,other);
		double wet = liquidFractionExact(cell,micro);
		if (!(wet > 0)) return new double[2][0];
		rectangle(tangent, other, span);
		int count = wet >= 1 ? 4 : clipFace(4, planeNormal(cell,micro,(axis+1)%3), planeNormal(cell,micro,(axis+2)%3),
				planeOffset(cell,micro) - coordinate * planeNormal(cell,micro,axis));
		return polygon(count);
	}

	/** Both positive liquid profiles on one shared open microface. */
	double[][] sharedWetPolygon(int left, int right, int axis, double tangent, double other, double span) {
		int minus = microAtFace(left,axis,1,tangent,other), plus = microAtFace(right,axis,0,tangent,other);
		if (!(liquidFractionExact(left,minus) > 0) || !(liquidFractionExact(right,plus) > 0)) return new double[2][0];
		rectangle(tangent, other, span);
		int count = liquidFractionExact(left,minus) >= 1 ? 4 : clipFace(4,planeNormal(left,minus,(axis+1)%3),planeNormal(left,minus,(axis+2)%3),planeOffset(left,minus)-planeNormal(left,minus,axis));
		if(liquidFractionExact(right,plus)<1)count=clipFace(count,planeNormal(right,plus,(axis+1)%3),planeNormal(right,plus,(axis+2)%3),planeOffset(right,plus));
		return polygon(count);
	}

	/** Owner liquid intersected with the peer's dry half-space, retaining actual gas-facing geometry. */
	double[][] exposedWetPolygon(int owner, int peer, int axis, int sign, double tangent, double other, double span) {
		int micro = microAtFace(owner,axis,sign>0?1:0,tangent,other);
		int peerMicro = peer<0 ? 0 : microAtFace(peer,axis,sign>0?0:1,tangent,other);
		if (!(liquidFractionExact(owner,micro) > 0) || peer>=0 && liquidFractionExact(peer,peerMicro)>=1) return new double[2][0];
		rectangle(tangent,other,span);
		int count=liquidFractionExact(owner,micro)>=1?4:clipFace(4,planeNormal(owner,micro,(axis+1)%3),planeNormal(owner,micro,(axis+2)%3),
				planeOffset(owner,micro)-(sign>0?1:0)*planeNormal(owner,micro,axis));
		if(peer>=0 && liquidFractionExact(peer,peerMicro)>0)count=clipFace(count,-planeNormal(peer,peerMicro,(axis+1)%3),-planeNormal(peer,peerMicro,(axis+2)%3),
				-planeOffset(peer,peerMicro)+(sign>0?0:1)*planeNormal(peer,peerMicro,axis));
		return polygon(count);
	}

	double liquidFractionExact(int cell) { return fraction[cell]; }
	int microAtFace(int cell,int axis,double coordinate,double tangent,double other) {
		int base = (tangent >= .5 ? 1 : 0) << ((axis+1)%3) | (other >= .5 ? 1 : 0) << ((axis+2)%3);
		if (coordinate >= 1) return base | 1 << axis;
		if (coordinate <= 0) return base;
		int mask = Byte.toUnsignedInt(collisionMasks[cell]);
		return (mask & 1 << base) == 0 ? base : base | 1 << axis;
	}
	private void rectangle(double x,double y,double span) {
		faceX[0]=faceX[3]=x;faceX[1]=faceX[2]=x+span;
		faceY[0]=faceY[1]=y;faceY[2]=faceY[3]=y+span;
	}
	private double[][] polygon(int count) {
		return new double[][]{java.util.Arrays.copyOf(faceX,count),java.util.Arrays.copyOf(faceY,count)};
	}

	float interfaceTheta(int fluid, int directionX, int directionY, int directionZ) {
		double distance;
		if (fraction[fluid] >= 1) distance = 0.5;
		else {
			float outward = normalX[fluid] * directionX + normalY[fluid] * directionY
					+ normalZ[fluid] * directionZ;
			if (!(outward > 0.0F)) return 1.0F;
			double centerProjection = 0.5 * ((double) normalX[fluid] + normalY[fluid] + normalZ[fluid]);
			distance = (planeOffset[fluid] - centerProjection) / outward;
		}
		int x = fluid % sizeX;
		int yz = fluid / sizeX;
		int y = yz % sizeY;
		int z = yz / sizeY;
		int nx = x + directionX, ny = y + directionY, nz = z + directionZ;
		if (inside(nx, ny, nz)) distance += fraction[cellIndex(nx, ny, nz)];
		if (!(distance > 0.0F)) {
			throw new IllegalStateException("Pressure-cell centre does not lie inside its reconstructed liquid phase");
		}
		return (float) Math.min(distance, 1);
	}

	private double sampleFraction(int x, int y, int z, double fallback, boolean[] solid) {
		if (!inside(x, y, z)) return fallback;
		int cell = cellIndex(x, y, z);
		return solid[cell] ? fallback : fraction[cell];
	}

	private int airDirection(int x, int y, int z, boolean[] solid) {
		if (!inside(x, y, z)) return 0;
		int cell = cellIndex(x, y, z);
		return !solid[cell] && fraction[cell] <= 0.5F ? 1 : 0;
	}

	private boolean inside(int x, int y, int z) {
		return x >= 0 && x < sizeX && y >= 0 && y < sizeY && z >= 0 && z < sizeZ;
	}

	private int cellIndex(int x, int y, int z) {
		return (z * sizeY + y) * sizeX + x;
	}

	private static double clamp(double value, double minimum, double maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}
}
