package mmm.fluid;

import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Arrays;

/** Joint destination volumes for uniform translation of homogeneous positive liquid boxes. */
final class CellTranslationRemap implements CellRemapCandidate {
	private static final SignedDyadic ONE = SignedDyadic.of(1), HALF = SignedDyadic.of(1, -1);
	private record Box(SignedDyadic volume, SignedDyadic[] centre, SignedDyadic[] width) { }
	private static final class Target {
		SignedDyadic volume = SignedDyadic.ZERO;
		final SignedDyadic[] lower = new SignedDyadic[3], upper = new SignedDyadic[3], first = zeros(3);
		final ArrayList<Box> boxes = new ArrayList<>();
		void add(LiquidTranslationPartition.Piece piece) {
			var centre = new SignedDyadic[3]; var width = new SignedDyadic[3];
			for (int a = 0; a < 3; a++) {
				centre[a] = piece.centroid(a); width[a] = piece.width(a);
				var low = centre[a].subtract(width[a].multiply(HALF)); var high = centre[a].add(width[a].multiply(HALF));
				if (lower[a] == null || low.compareTo(lower[a]) < 0) lower[a] = low;
				if (upper[a] == null || high.compareTo(upper[a]) > 0) upper[a] = high;
				first[a] = first[a].add(piece.volume().multiply(centre[a]));
			}
			volume = volume.add(piece.volume()); boxes.add(new Box(piece.volume(), centre, width));
		}
		void compact() {
			var centre = new SignedDyadic[3]; var width = new SignedDyadic[3]; var enclosingVolume = ONE;
			for (int a = 0; a < 3; a++) {
				width[a] = upper[a].subtract(lower[a]); centre[a] = upper[a].add(lower[a]).multiply(HALF);
				enclosingVolume = enclosingVolume.multiply(width[a]);
			}
			// A common translation keeps source cells disjoint; equal volume proves complete box coverage.
			if (enclosingVolume.equals(volume)) { boxes.clear(); boxes.add(new Box(volume, centre, width)); }
		}
	}
	private final CellRemapMarkerColumns markers;
	private final GasTopologyCoordinator.GasTopologyPlan gasTopology;
	private final CellRemapMoments.Audit momentAudit;
	private final boolean[] changedSpeciesCells;
	private final java.util.List<LiquidCellShape> liquidShapes;
	private CellTranslationRemap(FluidDomain domain, CellRemapMarkerColumns markers, SignedDyadic[] volumes, Target[] targets,
	                            BallisticLiquidRemap ballistic, CellRemapMoments.Audit audit) {
		CellRemapInventoryAudit.validate(domain, markers, volumes, ballistic);
		this.markers = markers; momentAudit = audit; changedSpeciesCells = new boolean[domain.cellCount];
		var edits = new ArrayList<>(PhaseLiquidGeometryEdit.afterCellRemap(domain, markers));
		var shapes = new ArrayList<LiquidCellShape>();
		for (int cell = 0; cell < targets.length; cell++) if (targets[cell] != null) {
			var target = targets[cell];
			if (target.boxes.size() != 1) throw new GasTopologyBlockedException("Joint translation requires positive multi-piece successor geometry at cell " + cell);
			var box = new LiquidCellShape.Box(target.lower[0].add(HALF), target.lower[1].add(HALF), target.lower[2].add(HALF),
					target.upper[0].add(HALF), target.upper[1].add(HALF), target.upper[2].add(HALF));
			var edit = edits.get(cell);
			var shape = new LiquidCellShape(cell, java.util.List.of(box), edit.firstMomentX(), edit.firstMomentY(), edit.firstMomentZ());
			shapes.add(shape); edits.set(cell, edit.withShape(shape));
		}
		liquidShapes = java.util.List.copyOf(shapes);
		var positionVolume = new float[Math.multiplyExact(markers.size(), 4)];
		for (int ordinal = 0; ordinal < markers.size(); ordinal++) {
			var coordinate = coordinate(domain, markers.cell[ordinal]);
			for (int axis = 0; axis < 3; axis++) positionVolume[4 * ordinal + axis] =
					(float) ((coordinate[axis] + .5 + markers.offset[axis][ordinal]) * domain.config.cellSize());
			positionVolume[4 * ordinal + 3] = markers.volume[ordinal];
		}
		LiquidCellShape.requireMatches(liquidShapes, domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(), positionVolume,
				cell -> domain.geometry.cutCells.mask(cell));
		gasTopology = domain.gasState.frame == null ? null : domain.gasTopologyCoordinator.planAfterLiquidEdits(
				edits, new long[domain.cellCount], true);
		if (gasTopology != null) for (int cell = 0; cell < targets.length; cell++) if (targets[cell] != null) {
			for (int a = 0; a < 3; a++) for (int side = 0; side < 2; side++) {
				boolean dry = side == 0 ? targets[cell].lower[a].compareTo(HALF.negate()) > 0
						: targets[cell].upper[a].compareTo(HALF) < 0;
				if (dry && !gasTopology.frame().fullDryFace(cell, a, side))
					throw new GasTopologyBlockedException("Joint liquid translation lost a completely dry face at cell "
							+ cell + ", axis=" + a + ", side=" + side);
			}
		}
	}
	public CellRemapMarkerColumns markers() { return markers; }
	public SignedDyadic energyRoundoff() { return SignedDyadic.ZERO; }
	public boolean[] changedSpeciesCells() { return changedSpeciesCells; }
	public GasTopologyCoordinator.GasTopologyPlan gasTopology() { return gasTopology; }
	public boolean commitGranularCollision() { return false; }
	public CellRemapMoments.Audit momentAudit() { return momentAudit; }
	public java.util.List<LiquidCellShape> liquidShapes() { return liquidShapes; }

	/** Null leaves other source measures with the general transport planner. All checks precede publication. */
	static CellTranslationRemap prepare(FluidDomain domain, CellAffineVelocityField field, float dt, BallisticLiquidRemap ballistic) {
		if (!(Float.isFinite(dt) && dt > 0) || field == null || domain.chemicalParcels != null || domain.thermalState != null || !ballistic.markers.isEmpty()
				|| domain.granularState != null || domain.granularCoupling != null || domain.particleCount() == 0) return null;
		var sourceVolume = zeros(domain.cellCount); var sourceFirst = new SignedDyadic[domain.cellCount][];
		var sourceMassFirst = new SignedDyadic[domain.cellCount][];
		var originalFirst = zeros(3); var originalMass = SignedDyadic.ZERO;
		var sourceShapes = new LiquidCellShape[domain.cellCount];
		for (var edit : PhaseLiquidGeometryEdit.shapeEdits(domain)) sourceShapes[edit.cell()] = edit.shape();
		double[] velocity = null; double density = Double.NaN; int material = -1; double h = domain.config.cellSize();
		var scale = SignedDyadic.of(h); var shape = new double[3];
		for (int p = 0; p < domain.particleCount(); p++) {
			int cell = domain.particleCellIndex(p);
			if (!domain.gridToParticleStage.advectionCells()[cell] && sourceShapes[cell] == null || domain.geometry.cutCells.mask(cell) != 0) return null;
			var v = new double[]{domain.particles.velocityX(p), domain.particles.velocityY(p), domain.particles.velocityZ(p)};
			if (velocity == null) velocity = v;
			else if (!Arrays.equals(v, velocity)) return null;
			if (material < 0) { material = domain.particles.materialHandle(p); density = domain.particles.mass(p) / domain.particles.volume(p); }
			if (material != domain.particles.materialHandle(p) || !(Double.isFinite(density) && density > 0)
					|| domain.particles.mass(p) != density * domain.particles.volume(p)) return null;
			var volume = SignedDyadic.of(domain.particles.volume(p)); sourceVolume[cell] = sourceVolume[cell].add(volume);
			var mass = SignedDyadic.of(domain.particles.mass(p)); originalMass = originalMass.add(mass);
			if (sourceFirst[cell] == null) {
				if (!Arrays.equals(field.constantVelocity(cell), v)) return null;
				sourceFirst[cell] = zeros(3); sourceMassFirst[cell] = zeros(3);
			}
			int[] coordinate = coordinate(domain, cell);
			for (int a = 0; a < 3; a++) {
				double x = switch (a) { case 0 -> domain.particles.positionX(p); case 1 -> domain.particles.positionY(p); default -> domain.particles.positionZ(p); };
				var offset = SignedDyadic.of(x).subtract(scale.multiply(SignedDyadic.of(2L * coordinate[a] + 1, -1)));
				originalFirst[a] = originalFirst[a].add(mass.multiply(SignedDyadic.of(x)));
				sourceFirst[cell][a] = sourceFirst[cell][a].add(volume.multiply(offset));
				sourceMassFirst[cell][a] = sourceMassFirst[cell][a].add(SignedDyadic.of(domain.particles.mass(p)).multiply(offset));
				shape[a] += domain.particles.mass(p) * Math.pow(offset.toDouble() / h, 2);
			}
		}
		var geometricSecond = new double[3];
		if (velocity[0] == 0 && velocity[1] == 0 && velocity[2] == 0) return null;
		for (int cell = 0; cell < domain.cellCount; cell++) if (sourceVolume[cell].signum() > 0) {
			if (sourceShapes[cell] == null) {
				if (!sourceVolume[cell].equals(ONE)) return null;
				for (var first : sourceFirst[cell]) if (first.signum() != 0) return null;
				for (var first : sourceMassFirst[cell]) if (first.signum() != 0) return null;
				for (int a = 0; a < 3; a++) geometricSecond[a] += density / 12;
			} else for (var box : sourceShapes[cell].boxes()) for (int a = 0; a < 3; a++) {
				double width = box.upper(a).subtract(box.lower(a)).toDouble();
				double centre = box.upper(a).add(box.lower(a)).multiply(HALF).subtract(HALF).toDouble();
				geometricSecond[a] += density * box.volume().toDouble() * (width * width / 12 + centre * centre);
			}
		}
		var displacement = new SignedDyadic[3];
		for (int a = 0; a < 3; a++) {
			var delta = PlicComponentGeometry.ratio(SignedDyadic.of(dt).multiply(SignedDyadic.of(velocity[a])), scale);
			int power = delta.denominator().getLowestSetBit();
			displacement[a] = power == delta.denominator().bitLength() - 1
					? new SignedDyadic(delta.numerator(), -power) : SignedDyadic.of(delta.toDouble());
			if ((displacement[a].signum() < 0 ? displacement[a].negate() : displacement[a]).compareTo(ONE) > 0) return null;
			shape[a] = domain.unitScale.kilograms(h * h * Math.abs(geometricSecond[a] - shape[a]));
		}
		var targets = new Target[domain.cellCount];
		for (int cell = 0; cell < domain.cellCount; cell++) if (sourceVolume[cell].signum() > 0) {
			var from = coordinate(domain, cell);
			var pieces = new ArrayList<LiquidTranslationPartition.Piece>();
			if (sourceShapes[cell] == null) pieces.addAll(LiquidTranslationPartition.fullCell(displacement));
			else for (var box : sourceShapes[cell].boxes()) pieces.addAll(LiquidTranslationPartition.box(box, displacement));
			for (var piece : pieces) {
				int x = from[0] + piece.dx(), y = from[1] + piece.dy(), z = from[2] + piece.dz();
				if (x < 0 || x >= domain.sizeX || y < 0 || y >= domain.sizeY || z < 0 || z >= domain.sizeZ) return null;
				int receiver = domain.cellIndex(x, y, z); if (domain.geometry.cutCells.mask(receiver) != 0) return null;
				if (targets[receiver] == null) targets[receiver] = new Target(); targets[receiver].add(piece);
			}
		}
		var volumes = zeros(domain.cellCount); int count = 0, groups = 0;
		for (int cell = 0; cell < targets.length; cell++) if (targets[cell] != null) {
			var target = targets[cell]; volumes[cell] = target.volume;
			if (target.volume.compareTo(ONE) > 0) throw new IllegalStateException("Translated liquid exceeds receiver capacity");
			target.compact(); groups++;
			for (var box : target.boxes) count = Math.addExact(count, 8 * LiquidCellQuadrature.volumes(box.volume, 1).size());
		}
		if (count > domain.maximumParticleCount) throw new IllegalStateException("Joint translation exceeds the marker budget");
		var markers = new CellRemapMarkerColumns(count); var storage = new double[3]; var residual = new double[3];
		var signed = new double[3]; var publishedFirst = zeros(3); int ordinal = 0;
		for (int cell = 0; cell < targets.length; cell++) if (targets[cell] != null) {
			var target = targets[cell]; var storedMassFirst = zeros(3); var storedVolumeFirst = zeros(3); var bound = new double[3];
			var coordinate = coordinate(domain, cell);
			for (var box : target.boxes) for (float chunk : LiquidCellQuadrature.volumes(box.volume, 1)) {
				float volume = chunk / 8;
				if (!(volume > 0) || !SignedDyadic.of(volume).multiply(SignedDyadic.of(8)).equals(SignedDyadic.of(chunk)))
					throw new IllegalStateException("Positive translated volume cannot be stored on eight nodes");
				for (int corner = 0; corner < 8; corner++) {
					var offset = new double[3]; double mass = density * volume;
					if (!(Double.isFinite(mass) && mass > 0)) throw new IllegalStateException("Translated liquid mass is unrepresentable");
					for (int a = 0; a < 3; a++) {
						double node = box.centre[a].toDouble() + ((corner & 1 << a) == 0 ? -1 : 1) * box.width[a].toDouble() / Math.sqrt(12);
						float position = (float) ((coordinate[a] + .5 + node) * h);
						offset[a] = position / h - coordinate[a] - .5;
						position = (float) ((coordinate[a] + .5 + offset[a]) * h);
						var actual = SignedDyadic.of(position); var centre = scale.multiply(SignedDyadic.of(2L * coordinate[a] + 1, -1));
						var low = centre.add(scale.multiply(box.centre[a].subtract(box.width[a].multiply(HALF))));
						var high = centre.add(scale.multiply(box.centre[a].add(box.width[a].multiply(HALF))));
						if (!Float.isFinite(position) || Math.floor(position / domain.config.cellSize()) != coordinate[a]
								|| actual.compareTo(low) < 0 || actual.compareTo(high) > 0)
							throw new MechanicalTimeRefinement(2, "Translated liquid has no stored quadrature in its positive receiving box");
						var local = actual.subtract(centre);
						storedVolumeFirst[a] = storedVolumeFirst[a].add(SignedDyadic.of(volume).multiply(local));
						storedMassFirst[a] = storedMassFirst[a].add(SignedDyadic.of(mass).multiply(local));
						publishedFirst[a] = publishedFirst[a].add(SignedDyadic.of(mass).multiply(actual));
						bound[a] += mass * Math.ulp(position) + Math.ulp(mass) * Math.abs(local.toDouble());
					}
					markers.set(ordinal++, cell, material, false, volume, mass, offset[0], offset[1], offset[2], velocity[0], velocity[1], velocity[2], null, 0);
				}
			}
			for (int a = 0; a < 3; a++) {
				var expected = target.first[a].multiply(scale);
				double error = storedMassFirst[a].subtract(expected.multiply(SignedDyadic.of(density))).toDouble();
				if (Math.abs(error) > bound[a] + Math.ulp(expected.toDouble() * density))
					throw new IllegalStateException("Translated mass first moment exceeds coordinate and mass storage error");
				storage[a] += domain.unitScale.kilograms(Math.abs(error));
				residual[a] += Math.abs(storedVolumeFirst[a].subtract(expected).toDouble());
			}
		}
		for (int a = 0; a < 3; a++) signed[a] = domain.unitScale.signedKilograms(publishedFirst[a].subtract(originalFirst[a])
				.subtract(originalMass.multiply(SignedDyadic.of(dt)).multiply(SignedDyadic.of(velocity[a]))).toDouble());
		return new CellTranslationRemap(domain, markers, volumes, targets, ballistic,
				new CellRemapMoments.Audit(new double[3], storage, residual, 0, 0, new double[3], signed, groups, shape));
	}
	private static SignedDyadic[] zeros(int count) { var result = new SignedDyadic[count]; Arrays.fill(result, SignedDyadic.ZERO); return result; }
	private static int[] coordinate(FluidDomain domain, int cell) {
		return new int[]{cell % domain.sizeX, cell / domain.sizeX % domain.sizeY, cell / (domain.sizeX * domain.sizeY)};
	}
}
