package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Vertex;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared space-time sweeps preserve geometric conservation under certified coordinate representation. */
final class LiquidConformingTransport {
	record Image(int cell, int face, LiquidConvexRegion geometry, List<Fraction> affineMap) { }
	record Surface(int cell, int face, LiquidExactPolygon geometry) { }
	private record Partition(List<Image> images, List<Surface> surfaces) { }
	private static final int MAXIMUM_REGIONS = 65_536;
	final LiquidControlVolumeMesh mesh;
	final LiquidConformingVelocity field;
	final int[] liquid;
	final Map<Vertex, Vertex> positions;
	final List<Image> images;
	final List<Surface> surfaces;
	final Fraction[] sweptVolume;
	final double maximumPositionRoundoffMetres;
	final double maximumCoordinateErrorMetres, maximumRateRepresentationErrorPerSecond;
	private final Fraction time;

	LiquidConformingTransport(LiquidControlVolumeMesh mesh, LiquidConformingVelocity field, Fraction[] rate, double dt) {
		this(mesh, field, rate, dt, new int[mesh.cells.size()]);
	}
	LiquidConformingTransport(LiquidControlVolumeMesh mesh, LiquidConformingVelocity field, Fraction[] rate, double dt, int[] liquid) {
		this(mesh, field, rate, dt, liquid, true);
	}
	/** The final finite-coordinate partition is certified before any unneeded reference-volume integration. */
	static LiquidConformingTransport prepareReference(LiquidControlVolumeMesh mesh, LiquidConformingVelocity field, Fraction[] rate, double dt, int[] liquid) {
		return new LiquidConformingTransport(mesh, field, rate, dt, liquid, false);
	}
	private LiquidConformingTransport(LiquidControlVolumeMesh mesh, LiquidConformingVelocity field, Fraction[] rate, double dt, int[] liquid, boolean partitionRequired) {
		if (field.mesh != mesh || rate.length != mesh.faces.size() || liquid.length != mesh.cells.size() || !(dt > 0) || !Double.isFinite(dt)) throw new IllegalArgumentException("Invalid continuous transport input");
		this.mesh = mesh; this.field = field; this.liquid = liquid.clone();
		time = Fraction.of(dt); var moved = new LinkedHashMap<Vertex, Vertex>();
		var interior = new java.util.HashSet<>(field.cellCenters);
		double roundoff = 0;
		for (var node : field.velocity.entrySet()) {
			if (interior.contains(node.getKey())) continue;
			var position = field.advectedPosition(node.getKey(), time); moved.put(node.getKey(), position.point());
			if (position.roundoffSquared().signum() != 0) roundoff = Math.max(roundoff, Math.nextUp(Math.sqrt(Math.nextUp(position.roundoffSquared().rounded()))));
		}
		maximumPositionRoundoffMetres = roundoff;
		maximumCoordinateErrorMetres = 0; maximumRateRepresentationErrorPerSecond = 0;
		sweptVolume = new Fraction[rate.length];
		for (int f = 0; f < rate.length; f++) {
			if (rate[f] == null) throw new IllegalArgumentException("Absent continuous transport rate");
			var face = mesh.faces.get(f);
			if (liquid[face.first()] < 0 && (face.second() < 0 || liquid[face.second()] < 0)) { sweptVolume[f] = Fraction.ZERO; continue; }
			var polygon = mesh.exactPolygons.get(f); var center = field.faceCenters.get(f); var area = area(polygon.vertices().stream().map(moved::get).toList());
			var norm = dot(area, area); if (norm.signum() == 0) throw new LiquidCharacteristicTimeResolutionRequired("Continuous face collapsed during transport");
			var target = rate[f].multiply(time); var delta = target.subtract(sweep(polygon.vertices(), center, moved)).multiply(Fraction.of(3)).divide(norm);
			var x = moved.get(center).coordinates(); add(x, scale(area, delta)); moved.put(center, LiquidExactPolygon.vertex(x));
			sweptVolume[f] = sweep(polygon.vertices(), center, moved);
			if (!sweptVolume[f].equals(target)) throw new IllegalStateException("Continuous face sweep lost exact integrated volume");
		}
		// The endpoint fan apex is an auxiliary partition point. Closed-surface volume and moments are independent of it.
		// Momentum is carried by the original velocity-field mean in LiquidGeometryMotionPlan.
		for (int c = 0; c < mesh.cells.size(); c++) if (liquid[c] >= 0)
			moved.put(field.cellCenters.get(c), LiquidExactPolygon.vertex(field.meanPosition(c, time)));
		positions = java.util.Collections.unmodifiableMap(moved);
		if (partitionRequired) { var partition = images(moved); images = partition.images; surfaces = partition.surfaces; }
		else {
			var reference = new java.util.function.Supplier<Partition>() {
				private Partition partition;
				@Override public Partition get() { if (partition == null) partition = images(moved); return partition; }
			};
			images = delayed(() -> reference.get().images); surfaces = delayed(() -> reference.get().surfaces);
		}
	}
	private static <T> List<T> delayed(java.util.function.Supplier<List<T>> values) {
		return new java.util.AbstractList<>() {
			@Override public int size() { return values.get().size(); }
			@Override public T get(int index) { return values.get().get(index); }
		};
	}
	/** Coordinate rounding is constrained by exact cell volumes; actual rates are certified by the original pressure operator. */
	LiquidConformingTransport representCoordinates(int fractionalBits) { return new LiquidConformingTransport(this, fractionalBits); }
	private LiquidConformingTransport(LiquidConformingTransport reference, int fractionalBits) {
		mesh = reference.mesh; field = reference.field; liquid = reference.liquid.clone(); time = reference.time;
		var volume = new Fraction[mesh.cells.size()];
		for (int c = 0; c < volume.length; c++) if (liquid[c] >= 0) {
			volume[c] = mesh.cells.get(c).geometry().measure().volume();
			for (int f : mesh.cellFaces[c]) volume[c] = volume[c].add(mesh.faces.get(f).first() == c ? reference.sweptVolume[f] : reference.sweptVolume[f].negate());
		}
		var representation = new LiquidCoordinateProjection(mesh, field, reference.positions, volume, fractionalBits);
		positions = representation.positions; maximumCoordinateErrorMetres = representation.maximumPositionErrorMetres;
		maximumPositionRoundoffMetres = reference.maximumPositionRoundoffMetres;
		sweptVolume = new Fraction[reference.sweptVolume.length];
		for (int f = 0; f < sweptVolume.length; f++) {
			var face = mesh.faces.get(f);
			sweptVolume[f] = liquid[face.first()] < 0 && (face.second() < 0 || liquid[face.second()] < 0) ? Fraction.ZERO
					: sweep(mesh.exactPolygons.get(f).vertices(), field.faceCenters.get(f), positions);
		}
		double error = 0;
		for (int c = 0; c < volume.length; c++) if (liquid[c] >= 0) {
			var total = Fraction.ZERO;
			for (int f : mesh.cellFaces[c]) total = total.add(sweptVolume[f].subtract(reference.sweptVolume[f]).absolute());
			if (total.signum() != 0) error = Math.max(error, Math.nextUp(total.divide(time).divide(volume[c]).rounded()));
		}
		if (!Double.isFinite(error)) throw new LiquidCoordinateResolutionRequired("Coordinate representation has no finite rate error bound");
		maximumRateRepresentationErrorPerSecond = error;
		try { var partition = images(positions); images = partition.images; surfaces = partition.surfaces; }
		catch (LiquidCharacteristicTimeResolutionRequired invalid) {
			reference.images.size(); // An invalid reference propagates its time-refinement request; a valid reference needs finer coordinates.
			throw new LiquidCoordinateResolutionRequired(invalid.getMessage());
		}
	}
	Fraction[] volumeRate() {
		var rates = new Fraction[sweptVolume.length]; for (int f = 0; f < rates.length; f++) rates[f] = sweptVolume[f].divide(time); return rates;
	}
	/** The certified rates supply the transported mean momentum on the same source fan. */
	LiquidConformingTransport withField(LiquidConformingVelocity represented) { return new LiquidConformingTransport(this, represented); }
	private LiquidConformingTransport(LiquidConformingTransport source, LiquidConformingVelocity represented) {
		represented.requireTransportSupport(source.field, source.volumeRate());
		if (represented.mesh != source.mesh || !represented.tetrahedra.equals(source.field.tetrahedra))
			throw new IllegalArgumentException("Represented velocity changed the source transport fan");
		mesh = source.mesh; field = represented; liquid = source.liquid.clone(); time = source.time;
		positions = source.positions; sweptVolume = source.sweptVolume.clone(); images = source.images; surfaces = source.surfaces;
		maximumPositionRoundoffMetres = source.maximumPositionRoundoffMetres;
		maximumCoordinateErrorMetres = source.maximumCoordinateErrorMetres;
		maximumRateRepresentationErrorPerSecond = source.maximumRateRepresentationErrorPerSecond;
	}
	private Partition images(Map<Vertex, Vertex> moved) {
		var positive = new LinkedHashMap<Integer, ArrayList<Image>>(); var negative = new LinkedHashMap<Integer, ArrayList<LiquidConvexRegion>>();
		var boundary = new LinkedHashMap<Integer, ArrayList<Surface>>();
		for (var tet : field.tetrahedra) if (liquid[tet.cell()] >= 0) {
			var source = tet.vertices(); var target = source.stream().map(moved::get).toList();
			var oldDet = LiquidExactPolygon.homogeneousTetrahedronDeterminant(source); var newDet = LiquidExactPolygon.homogeneousTetrahedronDeterminant(target);
			var surface = LiquidExactPolygon.triangle(target.get(1), target.get(2), target.get(3));
			if (!surface.hasArea()) throw new LiquidCharacteristicTimeResolutionRequired("Continuous transport collapsed a material surface");
			boundary.computeIfAbsent(tet.cell(), ignored -> new ArrayList<>()).add(new Surface(tet.cell(), tet.face(), surface));
			if (newDet.signum() == 0) continue;
			var geometry = LiquidConvexRegion.ofTetrahedron(target);
			if (newDet.signum() == oldDet.signum()) positive.computeIfAbsent(tet.cell(), ignored -> new ArrayList<>()).add(new Image(tet.cell(), tet.face(), geometry, affine(source, target)));
			else negative.computeIfAbsent(tet.cell(), ignored -> new ArrayList<>()).add(geometry);
		}
		var result = new ArrayList<Image>(); var surfaces = new ArrayList<Surface>();
		for (int c = 0; c < mesh.cells.size(); c++) if (liquid[c] >= 0) {
			int cell = c;
			var original = positive.getOrDefault(c, new ArrayList<>()); var removals = negative.getOrDefault(c, new ArrayList<>());
			var parts = removals.isEmpty() ? original : LiquidSignedVolumePartition.cancel(original.stream().map(Image::geometry).toList(), removals, MAXIMUM_REGIONS)
					.stream().map(geometry -> new Image(cell, -1, geometry, List.<Fraction>of())).toList();
			var measured = LiquidConvexRegion.EMPTY.measure(); for (var part : parts) measured = measured.add(part.geometry.measure());
			var exterior = boundary.get(c); if (exterior == null) throw new LiquidCharacteristicTimeResolutionRequired("Continuous transport lost a material surface");
			if (!measured.same(LiquidExactPolygon.measure(exterior.stream().map(Surface::geometry).toList())))
				throw new IllegalStateException("Continuous partition differs from its actual surface volume or first moments");
			var expected = mesh.cells.get(c).geometry().measure().volume();
			for (int f : mesh.cellFaces[c]) expected = expected.add(mesh.faces.get(f).first() == c ? sweptVolume[f] : sweptVolume[f].negate());
			if (!measured.volume().equals(expected)) throw new IllegalStateException("Continuous transport lost cell geometric conservation");
			if ((long) result.size() + parts.size() > MAXIMUM_REGIONS) throw new IllegalStateException("Continuous partition exceeds its region budget");
			result.addAll(parts); surfaces.addAll(exterior);
		}
		return new Partition(List.copyOf(result), List.copyOf(surfaces));
	}
	private static Fraction sweep(List<Vertex> points, Vertex center, Map<Vertex, Vertex> moved) {
		var value = Fraction.ZERO; var oldCenter = center.coordinates(); var newCenter = moved.get(center).coordinates();
		for (int i = 0; i < points.size(); i++) {
			var a = points.get(i); var b = points.get((i + 1) % points.size()); var x = a.coordinates(); var y = b.coordinates();
			var nextX = moved.get(a).coordinates(); var nextY = moved.get(b).coordinates();
			value = value.add(cone(newCenter, nextX, nextY)).subtract(cone(oldCenter, x, y));
			// Both incident polygons triangulate the temporal edge quad along the same diagonal.
			if (compare(a, b) < 0) value = value.add(cone(x, y, nextY)).add(cone(x, nextY, nextX));
			else value = value.add(cone(x, y, nextX)).add(cone(y, nextY, nextX));
		}
		return value;
	}
	private static int compare(Vertex a, Vertex b) {
		for (int i = 0; i < 3; i++) { var d = new Fraction(a.coordinate(i), a.w()).subtract(new Fraction(b.coordinate(i), b.w())); if (d.signum() != 0) return d.signum(); }
		return 0;
	}
	private static Fraction cone(Fraction[] a, Fraction[] b, Fraction[] c) { return dot(a, cross(b, c)).divide(Fraction.of(6)); }
	private static Fraction[] area(List<Vertex> points) {
		var value = zero(); for (int i = 0; i < points.size(); i++) add(value, cross(points.get(i).coordinates(), points.get((i + 1) % points.size()).coordinates()));
		return scale(value, Fraction.of(0.5));
	}
	private static List<Fraction> affine(List<Vertex> source, List<Vertex> target) {
		// Clipping uses the endpoint vertices. The affine map is only needed for an intact-cell image.
		return new java.util.AbstractList<>() {
			private List<Fraction> coordinates;
			@Override public int size() { return 12; }
			@Override public Fraction get(int index) {
				java.util.Objects.checkIndex(index, 12);
				if (coordinates == null) coordinates = affineCoordinates(source, target);
				return coordinates.get(index);
			}
		};
	}
	private static List<Fraction> affineCoordinates(List<Vertex> source, List<Vertex> target) {
		var origin = source.getFirst().coordinates(); var next = target.getFirst().coordinates();
		var columns = new Fraction[3][]; var moved = new Fraction[3][];
		for (int i = 0; i < 3; i++) { columns[i] = subtract(source.get(i + 1).coordinates(), origin); moved[i] = subtract(target.get(i + 1).coordinates(), next); }
		var det = dot(columns[0], cross(columns[1], columns[2])); var inverse = new Fraction[3][];
		for (int i = 0; i < 3; i++) inverse[i] = scale(cross(columns[(i + 1) % 3], columns[(i + 2) % 3]), Fraction.of(1).divide(det));
		var result = new ArrayList<Fraction>();
		for (int a = 0; a < 3; a++) {
			var offset = next[a];
			for (int b = 0; b < 3; b++) {
				var entry = Fraction.ZERO; for (int i = 0; i < 3; i++) entry = entry.add(moved[i][a].multiply(inverse[i][b]));
				result.add(entry); offset = offset.subtract(entry.multiply(origin[b]));
			}
			result.add(offset);
		}
		return List.copyOf(result);
	}
	private static Fraction[] zero() { return new Fraction[]{Fraction.ZERO, Fraction.ZERO, Fraction.ZERO}; }
	private static void add(Fraction[] a, Fraction[] b) { for (int i = 0; i < 3; i++) a[i] = a[i].add(b[i]); }
	private static Fraction[] subtract(Fraction[] a, Fraction[] b) { return new Fraction[]{a[0].subtract(b[0]), a[1].subtract(b[1]), a[2].subtract(b[2])}; }
	private static Fraction[] scale(Fraction[] a, Fraction b) { return new Fraction[]{a[0].multiply(b), a[1].multiply(b), a[2].multiply(b)}; }
	private static Fraction dot(Fraction[] a, Fraction[] b) { var value = Fraction.ZERO; for (int i = 0; i < 3; i++) value = value.add(a[i].multiply(b[i])); return value; }
	private static Fraction[] cross(Fraction[] a, Fraction[] b) { return new Fraction[]{a[1].multiply(b[2]).subtract(a[2].multiply(b[1])), a[2].multiply(b[0]).subtract(a[0].multiply(b[2])), a[0].multiply(b[1]).subtract(a[1].multiply(b[0]))}; }
}
