package mmm.fluid;

import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static mmm.fluid.LiquidPressureGeometry.*;

/** On-demand immutable pairing of current carrier measures and the current gas frame's PLIC pieces. */
final class LiquidSpatialInput {
	record ExactVector(SignedDyadic x, SignedDyadic y, SignedDyadic z) {
		static final ExactVector ZERO = new ExactVector(SignedDyadic.ZERO, SignedDyadic.ZERO, SignedDyadic.ZERO);
		ExactVector add(ExactVector value) { return new ExactVector(x.add(value.x), y.add(value.y), z.add(value.z)); }
		ExactVector scale(SignedDyadic value) { return new ExactVector(x.multiply(value), y.multiply(value), z.multiply(value)); }
	}
	/** XX, XY, XZ, YY, YZ, ZZ about the cell centre, with physical metre-squared coordinates. */
	record QuadraticMoments(List<ExactRational> entries) {
		static final QuadraticMoments ZERO = new QuadraticMoments(java.util.Collections.nCopies(6, ExactRational.ZERO));
		QuadraticMoments {
			entries = List.copyOf(entries);
			if (entries.size() != 6) throw new IllegalArgumentException("Quadratic moment shape mismatch");
		}
		static QuadraticMoments of(SignedDyadic[] values) {
			return new QuadraticMoments(Arrays.stream(values).map(LiquidSpatialInput::rational).toList());
		}
		QuadraticMoments add(QuadraticMoments value) {
			if (this == ZERO) return value; if (value == ZERO) return this;
			var result = new ArrayList<ExactRational>(6); for (int i = 0; i < 6; i++) result.add(entries.get(i).add(value.entries.get(i))); return new QuadraticMoments(result);
		}
		QuadraticMoments subtract(QuadraticMoments value) {
			var result = new ArrayList<ExactRational>(6); for (int i = 0; i < 6; i++) result.add(entries.get(i).subtract(value.entries.get(i))); return new QuadraticMoments(result);
		}
		QuadraticMoments scale(ExactRational value) {
			if (this == ZERO || value.equals(ExactRational.ONE)) return this;
			return new QuadraticMoments(entries.stream().map(x -> x.multiply(value)).toList());
		}
		SecondMoment observe() {
			return new SecondMoment(entries.get(0).toDouble(), entries.get(1).toDouble(), entries.get(2).toDouble(),
					entries.get(3).toDouble(), entries.get(4).toDouble(), entries.get(5).toDouble());
		}
	}
	/** Exact SI volume integrals about the cell centre; first/second positions use metres/metres squared. */
	record GeometricMoments(ExactRational volume, List<ExactRational> firstMoment, QuadraticMoments secondMoment) {
		static final GeometricMoments ZERO = new GeometricMoments(ExactRational.ZERO, java.util.Collections.nCopies(3, ExactRational.ZERO), QuadraticMoments.ZERO);
		GeometricMoments {
			firstMoment = List.copyOf(firstMoment);
			if (volume.signum() < 0 || firstMoment.size() != 3) throw new IllegalArgumentException("Invalid geometric moment measure");
		}
		GeometricMoments add(GeometricMoments value) {
			if (this == ZERO) return value; if (value == ZERO) return this;
			var first = new ArrayList<ExactRational>(3); for (int a = 0; a < 3; a++) first.add(firstMoment.get(a).add(value.firstMoment.get(a)));
			return new GeometricMoments(volume.add(value.volume), first, secondMoment.add(value.secondMoment));
		}
		GeometricMoments scale(ExactRational value) {
			if (this == ZERO || value.equals(ExactRational.ONE)) return this;
			return new GeometricMoments(volume.multiply(value), firstMoment.stream().map(x -> x.multiply(value)).toList(), secondMoment.scale(value));
		}
		List<ExactRational> globalFirstMoment(ExactVector origin) {
			return List.of(firstMoment.get(0).add(rational(origin.x).multiply(volume)), firstMoment.get(1).add(rational(origin.y).multiply(volume)),
					firstMoment.get(2).add(rational(origin.z).multiply(volume)));
		}
		VolumeMoments observe(ExactVector origin) {
			var first = globalFirstMoment(origin); return new VolumeMoments(volume.toDouble(), new Vec3(first.get(0).toDouble(), first.get(1).toDouble(), first.get(2).toDouble()));
		}
		QuadraticMoments covariance() {
			if (volume.isZero()) return QuadraticMoments.ZERO;
			var inverse = new ExactRational(volume.denominator(), volume.numerator()); var entries = new ArrayList<ExactRational>(6);
			for (int a = 0, index = 0; a < 3; a++) for (int b = a; b < 3; b++) entries.add(secondMoment.entries.get(index++).multiply(inverse)
					.subtract(firstMoment.get(a).multiply(inverse).multiply(firstMoment.get(b).multiply(inverse))));
			return new QuadraticMoments(entries);
		}
	}
	/** Raw volume and mass retain solver units; first/second positions use metres/metres squared. */
	record CarrierMoments(int count, SignedDyadic volume, ExactVector volumeFirstMoment, SignedDyadic mass, ExactVector massFirstMoment,
	                      QuadraticMoments volumeSecondMoment, QuadraticMoments massSecondMoment) {
		static final CarrierMoments EMPTY = new CarrierMoments(0, SignedDyadic.ZERO, ExactVector.ZERO, SignedDyadic.ZERO, ExactVector.ZERO,
				QuadraticMoments.ZERO, QuadraticMoments.ZERO);
		CarrierMoments add(CarrierMoments value) {
			return new CarrierMoments(Math.addExact(count, value.count), volume.add(value.volume), volumeFirstMoment.add(value.volumeFirstMoment),
					mass.add(value.mass), massFirstMoment.add(value.massFirstMoment),
					volumeSecondMoment.add(value.volumeSecondMoment), massSecondMoment.add(value.massSecondMoment));
		}
	}
	/** represented uses the captured component pore scale; wetEnvelope is the geometric PLIC coverage. */
	record Comparison(CarrierMoments carriers, SignedDyadic cubicMetersPerNormalizedVolume,
	                  GeometricMoments representedLocal, GeometricMoments wetEnvelopeLocal, ExactVector origin) {
		VolumeMoments represented() { return representedLocal.observe(origin); }
		VolumeMoments wetEnvelope() { return wetEnvelopeLocal.observe(origin); }
		double observedMassKg() { return carriers.mass.multiply(cubicMetersPerNormalizedVolume).toDouble(); }
		double volumeDifference() { return rational(carriers.volume.multiply(cubicMetersPerNormalizedVolume)).subtract(representedLocal.volume).toDouble(); }
		Vec3 volumeFirstMomentDifference() { return firstDifference(carriers.volumeFirstMoment, ExactRational.ONE); }
		double uniformMassDifference(double density) { return rational(carriers.mass.multiply(cubicMetersPerNormalizedVolume)).subtract(rational(density(density)).multiply(representedLocal.volume)).toDouble(); }
		Vec3 uniformMassFirstMomentDifference(double density) { return firstDifference(carriers.massFirstMoment, rational(density(density))); }
		QuadraticMoments representedSecondMoment() { return representedLocal.secondMoment; }
		QuadraticMoments wetEnvelopeSecondMoment() { return wetEnvelopeLocal.secondMoment; }
		private Vec3 firstDifference(ExactVector source, ExactRational density) {
			var first = representedLocal.globalFirstMoment(origin); var actual = source.scale(cubicMetersPerNormalizedVolume);
			return new Vec3(rational(actual.x).subtract(first.get(0).multiply(density)).toDouble(), rational(actual.y).subtract(first.get(1).multiply(density)).toDouble(),
					rational(actual.z).subtract(first.get(2).multiply(density)).toDouble());
		}
		SecondMoment volumeSecondMomentDifference() {
			return carriers.volumeSecondMoment.scale(rational(cubicMetersPerNormalizedVolume)).subtract(representedSecondMoment()).observe();
		}
		SecondMoment uniformMassSecondMomentDifference(double density) {
			return carriers.massSecondMoment.scale(rational(cubicMetersPerNormalizedVolume))
					.subtract(representedSecondMoment().scale(rational(density(density)))).observe();
		}
		private static SignedDyadic density(double value) {
			if (!(Double.isFinite(value) && value > 0)) throw new IllegalArgumentException("Reference density must be finite and positive");
			return SignedDyadic.of(value);
		}
	}
	record Piece(int key, int supportMask, double poreScale, Comparison comparison) { }
	record Component(int cell, int supportMask, double poreScale, Comparison comparison) { }
	private final LiquidPressureGeometry geometry;
	private final GasRuntimeGeometry frame;
	private final Piece[] pieces;
	private final List<Component>[] components;

	private LiquidSpatialInput(LiquidPressureGeometry geometry, GasRuntimeGeometry frame, Piece[] pieces, List<Component>[] components) {
		this.geometry = geometry; this.frame = frame; this.pieces = pieces; this.components = components;
	}
	static LiquidSpatialInput capture(FluidDomain domain) {
		if (domain.gasState.frame == null) throw new GasTopologyBlockedException("Liquid spatial input requires a current gas frame");
		return domain.gasState.frame.liquidSpatialInput(domain);
	}
	@SuppressWarnings("unchecked")
	static LiquidSpatialInput capture(FluidDomain domain, FlipPressurePhaseGeometry phase, GasRuntimeGeometry frame) {
		var source = new Accumulator[Math.multiplyExact(8, domain.cellCount)];
		for (int marker = 0; marker < domain.particleCount(); marker++) {
			int cell = domain.particleCellIndex(marker), micro = CellRemapMicroTopology.positionMicro(domain.config.cellSize(),
					domain.particles.positionX(marker), domain.particles.positionY(marker), domain.particles.positionZ(marker));
			if (CellRemapMicroTopology.support(domain.geometry.cutCells.mask(cell), micro) == 0)
				throw new IllegalStateException("Liquid spatial source lies inside fixed collision geometry");
			int key = 8 * cell + micro; if (source[key] == null) source[key] = new Accumulator();
			source[key].add(domain, marker);
		}
		var geometry = LiquidPressureGeometry.capture(domain, phase, frame.liquidStock()); var pieces = new Piece[source.length];
		var components = (List<Component>[]) new List<?>[domain.cellCount];
		var scale = SignedDyadic.of(domain.unitScale.cubicMetersPerNormalizedVolume());
		var h = SignedDyadic.of(domain.config.cellSize()); var firstScale = rational(scale.multiply(h));
		var secondScale = firstScale.multiply(rational(h));
		var empty = new Comparison(CarrierMoments.EMPTY, scale, GeometricMoments.ZERO, GeometricMoments.ZERO, ExactVector.ZERO);
		for (int cell = 0; cell < domain.cellCount; cell++) {
			var origin = new ExactVector(h.multiply(SignedDyadic.of(cell % domain.sizeX + .5)), h.multiply(SignedDyadic.of(cell / domain.sizeX % domain.sizeY + .5)),
					h.multiply(SignedDyadic.of(cell / (domain.sizeX * domain.sizeY) + .5)));
			var selected = new ArrayList<Component>();
			int mask = domain.geometry.cutCells.mask(cell);
			int[] supports = CellRemapMicroTopology.components(mask);
			SignedDyadic[] capacities = CellRemapMicroTopology.capacities(mask, phase.poreVolume(cell));
			for (int componentIndex = 0; componentIndex < supports.length; componentIndex++) {
				int support = supports[componentIndex];
				var raw = CarrierMoments.EMPTY; var represented = GeometricMoments.ZERO; var envelope = GeometricMoments.ZERO;
				double poreScale = phase.componentPoreScale(cell, Integer.numberOfTrailingZeros(support),
						capacities[componentIndex].toDouble() * 8 / Integer.bitCount(support));
				for (int micro = 0; micro < 8; micro++) if ((support & 1 << micro) != 0) {
					int key = 8 * cell + micro; var value = source[key] == null ? CarrierMoments.EMPTY : source[key].snapshot();
					var quadratic = LiquidPressureGeometry.pieceSecondMoments(domain, phase, cell, micro);
					var coveredSecond = quadratic[0].isZero() ? QuadraticMoments.ZERO
							: new QuadraticMoments(Arrays.asList(Arrays.copyOfRange(quadratic, 4, 10))).scale(secondScale);
					var integrated = quadratic[0].isZero() ? GeometricMoments.ZERO : new GeometricMoments(quadratic[0].multiply(rational(scale)),
							List.of(quadratic[1].multiply(firstScale), quadratic[2].multiply(firstScale), quadratic[3].multiply(firstScale)), coveredSecond);
					var liquid = integrated.scale(rational(SignedDyadic.of(poreScale)));
					pieces[key] = new Piece(key, support, poreScale, value.equals(CarrierMoments.EMPTY) && integrated == GeometricMoments.ZERO ? empty
							: new Comparison(value, scale, liquid, integrated, origin));
					raw = raw.add(value); represented = represented.add(liquid); envelope = envelope.add(integrated);
				}
				selected.add(new Component(cell, support, poreScale, raw.equals(CarrierMoments.EMPTY) && envelope == GeometricMoments.ZERO ? empty
						: new Comparison(raw, scale, represented, envelope, origin)));
			}
			components[cell] = List.copyOf(selected);
		}
		return new LiquidSpatialInput(geometry, frame, pieces, components);
	}
	LiquidPressureGeometry geometry() { return geometry; }
	GasRuntimeGeometry frame() { return frame; }
	Piece piece(int key) {
		if (key < 0 || key >= pieces.length) throw new IllegalArgumentException("Invalid liquid spatial piece");
		return pieces[key];
	}
	List<Component> components(int cell) { return components[cell]; }
	Component component(int cell, int supportMask) {
		for (var component : components[cell]) if (component.supportMask == supportMask) return component;
		throw new IllegalArgumentException("Missing liquid spatial component");
	}
	private static ExactRational rational(SignedDyadic value) { return PlicComponentGeometry.ratio(value, SignedDyadic.of(1)); }
	private static final class Accumulator {
		int count;
		SignedDyadic volume = SignedDyadic.ZERO, mass = SignedDyadic.ZERO;
		final SignedDyadic[] volumeFirst = new SignedDyadic[3], massFirst = new SignedDyadic[3];
		final SignedDyadic[] volumeSecond = new SignedDyadic[6], massSecond = new SignedDyadic[6];
		Accumulator() {
			Arrays.fill(volumeFirst, SignedDyadic.ZERO); Arrays.fill(massFirst, SignedDyadic.ZERO);
			Arrays.fill(volumeSecond, SignedDyadic.ZERO); Arrays.fill(massSecond, SignedDyadic.ZERO);
		}
		void add(FluidDomain domain, int marker) {
			if (!(Float.isFinite(domain.particles.volume(marker)) && domain.particles.volume(marker) > 0)
					|| !(Double.isFinite(domain.particles.mass(marker)) && domain.particles.mass(marker) >= 0))
				throw new IllegalStateException("Liquid spatial source requires positive volume and nonnegative finite mass");
			var v = SignedDyadic.of(domain.particles.volume(marker)); var m = SignedDyadic.of(domain.particles.mass(marker));
			volume = volume.add(v); mass = mass.add(m); count = Math.addExact(count, 1);
			float[] position = {domain.particles.positionX(marker), domain.particles.positionY(marker), domain.particles.positionZ(marker)};
			int cell = domain.particleCellIndex(marker);
			int[] cellCoordinate = {cell % domain.sizeX, cell / domain.sizeX % domain.sizeY, cell / (domain.sizeX * domain.sizeY)};
			var centered = new SignedDyadic[3];
			for (int axis = 0; axis < 3; axis++) {
				var coordinate = SignedDyadic.of(position[axis]); volumeFirst[axis] = volumeFirst[axis].add(v.multiply(coordinate));
				massFirst[axis] = massFirst[axis].add(m.multiply(coordinate));
				centered[axis] = coordinate.subtract(SignedDyadic.of(domain.config.cellSize()).multiply(SignedDyadic.of(cellCoordinate[axis] + .5)));
			}
			for (int a = 0, index = 0; a < 3; a++) for (int b = a; b < 3; b++) {
				var product = centered[a].multiply(centered[b]);
				volumeSecond[index] = volumeSecond[index].add(v.multiply(product)); massSecond[index] = massSecond[index].add(m.multiply(product)); index++;
			}
		}
		CarrierMoments snapshot() {
			return new CarrierMoments(count, volume, new ExactVector(volumeFirst[0], volumeFirst[1], volumeFirst[2]),
					mass, new ExactVector(massFirst[0], massFirst[1], massFirst[2]), QuadraticMoments.of(volumeSecond), QuadraticMoments.of(massSecond));
		}
	}
}
