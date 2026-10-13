package mmm.fluid;

import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static mmm.fluid.LiquidPressureGeometry.*;

/** On-demand immutable pairing of current carrier measures and the current gas frame's PLIC pieces. */
final class LiquidSpatialInput {
	record ExactVector(SignedDyadic x, SignedDyadic y, SignedDyadic z) {
		static final ExactVector ZERO = new ExactVector(SignedDyadic.ZERO, SignedDyadic.ZERO, SignedDyadic.ZERO);
		static ExactVector of(Vec3 value) { return new ExactVector(SignedDyadic.of(value.x()), SignedDyadic.of(value.y()), SignedDyadic.of(value.z())); }
		ExactVector add(ExactVector value) { return new ExactVector(x.add(value.x), y.add(value.y), z.add(value.z)); }
		ExactVector subtract(ExactVector value) { return new ExactVector(x.subtract(value.x), y.subtract(value.y), z.subtract(value.z)); }
		ExactVector scale(SignedDyadic value) { return new ExactVector(x.multiply(value), y.multiply(value), z.multiply(value)); }
		Vec3 observe() { return new Vec3(x.toDouble(), y.toDouble(), z.toDouble()); }
	}
	/** Raw volume and mass retain solver units; each first moment multiplies its measure by physical metres. */
	record CarrierMoments(int count, SignedDyadic volume, ExactVector volumeFirstMoment, SignedDyadic mass, ExactVector massFirstMoment) {
		static final CarrierMoments EMPTY = new CarrierMoments(0, SignedDyadic.ZERO, ExactVector.ZERO, SignedDyadic.ZERO, ExactVector.ZERO);
		CarrierMoments add(CarrierMoments value) {
			return new CarrierMoments(Math.addExact(count, value.count), volume.add(value.volume), volumeFirstMoment.add(value.volumeFirstMoment),
					mass.add(value.mass), massFirstMoment.add(value.massFirstMoment));
		}
	}
	/** represented uses the captured component pore scale; wetEnvelope is the geometric PLIC coverage. */
	record Comparison(CarrierMoments carriers, VolumeMoments represented, VolumeMoments wetEnvelope, SignedDyadic cubicMetersPerNormalizedVolume) {
		double observedMassKg() { return carriers.mass.multiply(cubicMetersPerNormalizedVolume).toDouble(); }
		double volumeDifference() { return carriers.volume.multiply(cubicMetersPerNormalizedVolume).subtract(SignedDyadic.of(represented.volume())).toDouble(); }
		Vec3 volumeFirstMomentDifference() { return carriers.volumeFirstMoment.scale(cubicMetersPerNormalizedVolume).subtract(ExactVector.of(represented.firstMoment())).observe(); }
		double uniformMassDifference(double density) { return carriers.mass.multiply(cubicMetersPerNormalizedVolume).subtract(density(density).multiply(SignedDyadic.of(represented.volume()))).toDouble(); }
		Vec3 uniformMassFirstMomentDifference(double density) { return carriers.massFirstMoment.scale(cubicMetersPerNormalizedVolume).subtract(ExactVector.of(represented.firstMoment()).scale(density(density))).observe(); }
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
		var empty = new Comparison(CarrierMoments.EMPTY, VolumeMoments.EMPTY, VolumeMoments.EMPTY, scale);
		for (int cell = 0; cell < domain.cellCount; cell++) {
			var selected = new ArrayList<Component>();
			for (int support : CellRemapMicroTopology.components(domain.geometry.cutCells.mask(cell))) {
				var raw = CarrierMoments.EMPTY; var represented = VolumeMoments.EMPTY; var envelope = VolumeMoments.EMPTY;
				double poreScale = phase.componentPoreScale(cell, Integer.numberOfTrailingZeros(support),
						phase.poreVolume(cell).toDouble() / domain.geometry.cutCells.openCellVolume(cell));
				for (int micro = 0; micro < 8; micro++) if ((support & 1 << micro) != 0) {
					int key = 8 * cell + micro; var value = source[key] == null ? CarrierMoments.EMPTY : source[key].snapshot();
					var integrated = phase.liquidFractionExact(cell) > 0 ? LiquidPressureGeometry.pieceMoments(domain, phase, cell, micro) : VolumeMoments.EMPTY;
					var liquid = integrated.scale(poreScale);
					pieces[key] = new Piece(key, support, poreScale, value.equals(CarrierMoments.EMPTY) && integrated.equals(VolumeMoments.EMPTY) ? empty : new Comparison(value, liquid, integrated, scale));
					raw = raw.add(value); represented = represented.add(liquid); envelope = envelope.add(integrated);
				}
				selected.add(new Component(cell, support, poreScale, raw.equals(CarrierMoments.EMPTY) && envelope.equals(VolumeMoments.EMPTY) ? empty : new Comparison(raw, represented, envelope, scale)));
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
	private static final class Accumulator {
		int count;
		SignedDyadic volume = SignedDyadic.ZERO, mass = SignedDyadic.ZERO;
		final SignedDyadic[] volumeFirst = new SignedDyadic[3], massFirst = new SignedDyadic[3];
		Accumulator() { Arrays.fill(volumeFirst, SignedDyadic.ZERO); Arrays.fill(massFirst, SignedDyadic.ZERO); }
		void add(FluidDomain domain, int marker) {
			if (!(Float.isFinite(domain.particles.volume(marker)) && domain.particles.volume(marker) > 0)
					|| !(Double.isFinite(domain.particles.mass(marker)) && domain.particles.mass(marker) >= 0))
				throw new IllegalStateException("Liquid spatial source requires positive volume and nonnegative finite mass");
			var v = SignedDyadic.of(domain.particles.volume(marker)); var m = SignedDyadic.of(domain.particles.mass(marker));
			volume = volume.add(v); mass = mass.add(m); count = Math.addExact(count, 1);
			float[] position = {domain.particles.positionX(marker), domain.particles.positionY(marker), domain.particles.positionZ(marker)};
			for (int axis = 0; axis < 3; axis++) {
				var coordinate = SignedDyadic.of(position[axis]); volumeFirst[axis] = volumeFirst[axis].add(v.multiply(coordinate));
				massFirst[axis] = massFirst[axis].add(m.multiply(coordinate));
			}
		}
		CarrierMoments snapshot() {
			return new CarrierMoments(count, volume, new ExactVector(volumeFirst[0], volumeFirst[1], volumeFirst[2]),
					mass, new ExactVector(massFirst[0], massFirst[1], massFirst[2]));
		}
	}
}
