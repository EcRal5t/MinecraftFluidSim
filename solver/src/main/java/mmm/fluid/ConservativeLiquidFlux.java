package mmm.fluid;

import mmm.units.SignedDyadic;
import mmm.compute.CpuWorkBatch;
import java.util.Arrays;

/** Shared geometric liquid fluxes with conservative multidimensional capacity constraints. */
final class ConservativeLiquidFlux {
	@FunctionalInterface
	interface FaceSweep {
		void sweptMoments(int face, int donor, int receiver, int axis, boolean positive, double dtOverH,
		                  int donorSupport, int receiverSupport, double[] result);
		default mmm.chemistry.ExactRational[] sweptSecondMoments(int face, int donor, int receiver, int axis, boolean positive,
		                                                     double dtOverH, int donorSupport, int receiverSupport) {
			throw new IllegalStateException("Liquid face sweep has no quadratic spatial integration rule");
		}
		default void sweptMicroVolumes(int face, int donor, int receiver, int axis, boolean positive, double dtOverH,
		                               int donorSupport, int receiverSupport, double[] swept, double[] wet) {
			throw new IllegalStateException("Liquid face sweep has no common micro source integration rule");
		}
		default void sweptPhaseMoments(PhaseVolumeTransportGeometry phases, int phase, int face, int donor,
		                               int receiver, int axis, boolean positive, double width, double dtOverH,
		                               int donorSupport, int receiverSupport, double[] result) {
			phases.sweptMoments(phase, donor, receiver, axis, width, positive, donorSupport, receiverSupport, result);
		}
	}
	final int sizeX, sizeY, sizeZ, cellCount;
	final int physicalFaceCount;
	private final boolean bidirectional;
	final int[] left, right, axis;
	final double[] velocity, flux;
	final SignedDyadic[] exactFlux;
	private final double[] low, positive, negative, rPositive, rNegative, outgoing;
	private final float[] normalX, normalY, normalZ;
	private final double[] offset;
	private final int[] incomingHead, incomingNext, predecessor, queue;
	private final SignedDyadic[] after;
	private final double[] capacity, aperture, donorFlux;
	private final SignedDyadic[] exactCapacity;
	private final SignedDyadic[] upperFlux, remainingSupply;
	private final double[] finalCapacity;
	private final double[] geometricBlend;
	private final double[] representationBlend;
	private final SignedDyadic[] faceFloorLoss;
	private SignedDyadic[] quantizationBudget;
	private double dtOverH;
	private LiquidFluxGeometry geometry;
	private FaceSweep faceSweep;
	private SignedDyadic[] reserved;
	private int[] carrierShares;

	ConservativeLiquidFlux(int sizeX, int sizeY, int sizeZ) {
		this(sizeX, sizeY, sizeZ, false);
	}
	ConservativeLiquidFlux(int sizeX, int sizeY, int sizeZ, boolean bidirectional) {
		this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ;
		this.bidirectional = bidirectional;
		cellCount = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		physicalFaceCount = (sizeX - 1) * sizeY * sizeZ + sizeX * (sizeY - 1) * sizeZ + sizeX * sizeY * (sizeZ - 1);
		int faces = Math.multiplyExact(physicalFaceCount, bidirectional ? 2 : 1);
		left = new int[faces]; right = left.clone(); axis = left.clone();
		velocity = new double[faces]; flux = velocity.clone(); exactFlux = new SignedDyadic[faces];
		low = new double[cellCount]; positive = low.clone(); negative = low.clone();
		rPositive = low.clone(); rNegative = low.clone(); outgoing = low.clone();
		normalX = new float[cellCount]; normalY = normalX.clone(); normalZ = normalX.clone(); offset = new double[cellCount];
		incomingHead = new int[cellCount]; incomingNext = new int[faces]; predecessor = incomingHead.clone(); queue = incomingHead.clone();
		after = new SignedDyadic[cellCount];
		capacity = new double[cellCount]; aperture = new double[faces]; donorFlux = new double[faces];
		exactCapacity = new SignedDyadic[cellCount];
		upperFlux = new SignedDyadic[faces]; remainingSupply = new SignedDyadic[cellCount]; finalCapacity = new double[cellCount];
		geometricBlend = new double[faces];
		representationBlend = new double[faces];
		faceFloorLoss = new SignedDyadic[faces];
		int f = 0;
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 1; x < sizeX; x++) {
			left[f] = index(x - 1, y, z); right[f] = left[f] + 1; axis[f++] = 0;
		}
		for (int z = 0; z < sizeZ; z++) for (int y = 1; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			left[f] = index(x, y - 1, z); right[f] = left[f] + sizeX; axis[f++] = 1;
		}
		for (int z = 1; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			left[f] = index(x, y, z - 1); right[f] = left[f] + sizeX * sizeY; axis[f++] = 2;
		}
		if (bidirectional) {
			System.arraycopy(left, 0, left, physicalFaceCount, physicalFaceCount);
			System.arraycopy(right, 0, right, physicalFaceCount, physicalFaceCount);
			System.arraycopy(axis, 0, axis, physicalFaceCount, physicalFaceCount);
			Arrays.fill(velocity, physicalFaceCount, faces, -0.0);
		}
	}

	void geometry(LiquidFluxGeometry geometry) { this.geometry = geometry; }
	void faceSweep(FaceSweep faceSweep) { this.faceSweep = faceSweep; }
	LiquidAffineFaceFlow affineFaceFlow(FluidDomain domain, float dt) {
		return faceSweep instanceof LiquidAffineFaceFlow flow && flow.belongsTo(domain)
				&& Double.doubleToRawLongBits(dtOverH) == Double.doubleToRawLongBits(dt / domain.config.cellSize()) ? flow : null;
	}
	SignedDyadic openTransferBound(int face) { return upperFlux[face]; }
	void reserve(SignedDyadic[] reserved) { this.reserved = reserved; }
	void carrierShares(int[] carrierShares) { this.carrierShares = carrierShares; }
	mmm.chemistry.ExactRational[] sweptSecondMoments(FluidDomain domain, int face, int donorSupport, int receiverSupport) {
		return faceSweep == null ? LiquidAffineSweepGeometry.sweptExactSecondMoments(domain, donor(face), receiver(face), axis[face], positive(face),
				velocity[face], 0, 0, dtOverH, donorSupport, receiverSupport)
				: faceSweep.sweptSecondMoments(face % physicalFaceCount, donor(face), receiver(face), axis[face], positive(face), dtOverH,
						donorSupport, receiverSupport);
	}
	void sweptPhaseMoments(PhaseVolumeTransportGeometry phases, int phase, int face,
	                       int donorSupport, int receiverSupport, double[] result) {
		double width = Math.abs(velocity[face]) * dtOverH;
		if (faceSweep == null) phases.sweptMoments(phase, donor(face), receiver(face), axis[face], width,
				positive(face), donorSupport, receiverSupport, result);
		else faceSweep.sweptPhaseMoments(phases, phase, face % physicalFaceCount, donor(face), receiver(face),
				axis[face], positive(face), width, dtOverH, donorSupport, receiverSupport, result);
	}

	/** Volumes and capacities are in this grid's cell-volume units; velocities are m/s. */
	void plan(double[] volume, boolean[] solid, double dtOverH, SignedDyadic[] exactVolume) {
		this.dtOverH = dtOverH;
		quantizationBudget = null;
		Arrays.fill(representationBlend, 0);
		prepareGeometry(solid, dtOverH, volume);
		reconstructPlanes(volume, solid, exactVolume);
		provisionalFluxes(volume, dtOverH);
		limitAntidiffusion(volume, dtOverH);
		quantizeDonorSupply(volume, dtOverH);
		limitChemicalShares();
		repairSharedFluxes(exactVolume, solid);
	}

	private void prepareGeometry(boolean[] solid, double dtOverH, double[] volume) {
		for (int c = 0; c < cellCount; c++) {
			capacity[c] = solid[c] ? 0 : geometry == null ? 1 : geometry.capacity(c);
			exactCapacity[c] = SignedDyadic.of(solid[c] ? 0 : geometry == null ? 1 : geometry.finalCapacity(c));
			if (reserved != null) exactCapacity[c] = exactCapacity[c].subtract(reserved[c]);
			if (exactCapacity[c].signum() < 0) throw new MechanicalTimeRefinement(2,
					"Liquid transport reserved volume exceeds future capacity at cell " + c + ": remaining=" + exactCapacity[c].toDouble());
			finalCapacity[c] = exactCapacity[c].toDouble();
			if (SignedDyadic.of(finalCapacity[c]).compareTo(exactCapacity[c]) > 0) finalCapacity[c] = Math.nextDown(finalCapacity[c]);
		}
		for (int f = 0; f < flux.length; f++) aperture[f] = geometry == null ? 1 : geometry.aperture(left[f], right[f], axis[f]);
		Arrays.fill(outgoing, 0);
		for (int f = 0; f < flux.length; f++) {
			if (solid[left[f]] || solid[right[f]] || aperture[f] == 0) { velocity[f] = Math.copySign(0, velocity[f]); continue; }
			int donor = positive(f) ? left[f] : right[f];
			if (capacity[donor] > 0 && volume[donor] > 0) outgoing[donor] += Math.abs(velocity[f]) * dtOverH * aperture[f] / capacity[donor];
		}
		for (int c = 0; c < cellCount; c++) if (outgoing[c] > 1 + 8 * Math.ulp(1.0F))
			throw new IllegalArgumentException("Volume transport requires a smaller step: outgoing Courant=" + outgoing[c]);
	}

	private void reconstructPlanes(double[] volume, boolean[] solid, SignedDyadic[] exactVolume) {
		CpuWorkBatch.run(cellCount, (from, until) -> {
			for (int c = from; c < until; c++) {
				int x = c % sizeX, y = c / sizeX % sizeY, z = c / (sizeX * sizeY);
				if (exactVolume[c].compareTo(SignedDyadic.ZERO) < 0 || exactVolume[c].compareTo(SignedDyadic.of(capacity[c])) > 0)
					throw new IllegalStateException("Liquid integration cell " + c + " exceeds its geometric capacity: volume="
							+ volume[c] + ", beginningCapacity=" + capacity[c] + ", finalCapacity=" + finalCapacity[c]);
				FlipPressurePhaseGeometry plane = geometry == null ? null : geometry.transportPlane();
				if (plane != null) {
					normalX[c] = plane.planeNormal(c, 0); normalY[c] = plane.planeNormal(c, 1); normalZ[c] = plane.planeNormal(c, 2);
					offset[c] = plane.planeOffset(c); continue;
				}
				normalX[c] = (float) (sample(volume, solid, x - 1, y, z, c) - sample(volume, solid, x + 1, y, z, c));
				normalY[c] = (float) (sample(volume, solid, x, y - 1, z, c) - sample(volume, solid, x, y + 1, z, c));
				normalZ[c] = (float) (sample(volume, solid, x, y, z - 1, c) - sample(volume, solid, x, y, z + 1, c));
				if (normalX[c] == 0 && normalY[c] == 0 && normalZ[c] == 0) normalY[c] = 1;
				offset[c] = geometry == null ? FlipFreeSurfaceGeometry.solvePlaneOffset((double) normalX[c], normalY[c], normalZ[c], volume[c])
						: geometry.offset(c, normalX[c], normalY[c], normalZ[c], volume[c]);
			}
		});
	}

	private void provisionalFluxes(double[] volume, double dtOverH) {
		System.arraycopy(volume, 0, low, 0, cellCount);
		Arrays.fill(positive, 0); Arrays.fill(negative, 0);
		CpuWorkBatch.run(flux.length, (from, until) -> {
			double[] moments = faceSweep == null ? null : new double[4];
			for (int f = from; f < until; f++) {
				double courant = velocity[f] * dtOverH;
				int donor = positive(f) ? left[f] : right[f];
				if (volume[donor] == 0 || courant == 0 || aperture[f] == 0) { flux[f] = Math.copySign(0, velocity[f]); continue; }
				double width = Math.abs(courant), nx = normalX[donor], ny = normalY[donor], nz = normalZ[donor], off = offset[donor];
				if (positive(f)) off -= (axis[f] == 0 ? nx : axis[f] == 1 ? ny : nz) * (1 - width);
				if (axis[f] == 0) nx *= width; else if (axis[f] == 1) ny *= width; else nz *= width;
				donorFlux[f] = capacity[donor] == 0 ? 0 : courant * aperture[f] * volume[donor] / capacity[donor];
				double swept;
				if (faceSweep != null) {
					faceSweep.sweptMoments(f % physicalFaceCount, donor, receiver(f), axis[f], positive(f), dtOverH, 255, 255, moments);
					swept = moments[0];
				} else swept = geometry == null ? Math.abs(courant) * FlipFreeSurfaceGeometry.unitCubeLiquidFraction(nx, ny, nz, off)
						: geometry.swept(donor, receiver(f), axis[f], Math.abs(courant), positive(f),
								normalX[donor], normalY[donor], normalZ[donor], offset[donor]);
				flux[f] = Math.copySign(swept, courant) - donorFlux[f];
			}
		});
		for (int f = 0; f < flux.length; f++) {
			double courant = velocity[f] * dtOverH;
			int donor = positive(f) ? left[f] : right[f];
			if (volume[donor] == 0 || courant == 0 || aperture[f] == 0) continue;
			low[left[f]] -= donorFlux[f]; low[right[f]] += donorFlux[f];
			double a = flux[f];
			positive[left[f]] += Math.max(-a, 0); negative[left[f]] += Math.max(a, 0);
			positive[right[f]] += Math.max(a, 0); negative[right[f]] += Math.max(-a, 0);
		}
	}

	private void limitAntidiffusion(double[] volume, double dtOverH) {
		for (int c = 0; c < cellCount; c++) {
			rPositive[c] = positive[c] == 0 ? 1 : Math.min(1, Math.max(0, finalCapacity[c] - low[c]) / positive[c]);
			rNegative[c] = negative[c] == 0 ? 1 : Math.min(1, Math.max(0, low[c]) / negative[c]);
		}
		Arrays.fill(outgoing, 0);
		for (int f = 0; f < flux.length; f++) {
			double a = flux[f];
			double limiter = a >= 0 ? Math.min(rNegative[left[f]], rPositive[right[f]]) : Math.min(rPositive[left[f]], rNegative[right[f]]);
			int donor = positive(f) ? left[f] : right[f];
			flux[f] = (capacity[donor] == 0 ? 0 : velocity[f] * dtOverH * aperture[f] * volume[donor] / capacity[donor]) + a * limiter;
			geometricBlend[f] = flux[f] == 0 ? 0 : Math.max(0, Math.min(1, limiter * (donorFlux[f] + a) / flux[f]));
			outgoing[donor] += Math.abs(flux[f]);
		}
	}

	private void quantizeDonorSupply(double[] volume, double dtOverH) {
		// A single-step carrier commit draws from beginning-of-step inventory.
		// Limiting outgoing flux prevents same-step transshipment through empty parcels.
		for (int f = 0; f < flux.length; f++) {
			int donor = positive(f) ? left[f] : right[f];
			double scale = outgoing[donor] <= volume[donor] ? 1 : volume[donor] / outgoing[donor];
			double amount = Math.abs(flux[f]) * scale;
			double resolution = ConservativeMarkerSampling.VOLUME_FLUX_RESOLUTION;
			double stored = Math.floor(amount / resolution) * resolution;
			exactFlux[f] = SignedDyadic.of(stored);
			// The floor gives stored <= amount < 2*stored when stored > 0: subtraction is exact by Sterbenz.
			faceFloorLoss[f] = amount == stored ? SignedDyadic.ZERO : SignedDyadic.of(amount - stored);
			flux[f] = Math.copySign(stored, velocity[f]);
			upperFlux[f] = SignedDyadic.of(Math.floor(Math.abs(velocity[f]) * dtOverH * aperture[f] / resolution) * resolution);
		}
	}

	private void limitChemicalShares() {
		if (carrierShares != null) {
			Arrays.fill(incomingHead, 0);
			Arrays.fill(incomingNext, -1);
			// Retain one chemical quantum in each source group. At coarse chemical
			// resolution, keep only the largest outgoing faces that can be represented.
			for (int rank = 0; rank < 6; rank++) for (int c = 0; c < cellCount; c++) {
				if (carrierShares[c] >= 7) continue;
				int selected = -1;
				for (int f : adjacent(c)) if (f >= 0 && donor(f) == c && exactFlux[f].signum() > 0
						&& incomingNext[f] != -2 && (selected < 0 || exactFlux[f].compareTo(exactFlux[selected]) > 0)) selected = f;
				if (selected < 0) continue;
				incomingNext[selected] = -2;
				if (incomingHead[c]++ >= carrierShares[c] - 1) {
					exactFlux[selected] = SignedDyadic.ZERO; upperFlux[selected] = SignedDyadic.ZERO;
					faceFloorLoss[selected] = SignedDyadic.ZERO;
				}
			}
			for (int f = 0; f < flux.length; f++)
				if (carrierShares[donor(f)] < 7 && (incomingNext[f] != -2 || exactFlux[f].signum() == 0)) {
					upperFlux[f] = SignedDyadic.ZERO;
					faceFloorLoss[f] = SignedDyadic.ZERO;
				}
		}
	}

	/** Adjusts shared transfers before commit. Cell inventories and positions remain untouched. */
	private void repairSharedFluxes(SignedDyadic[] volume, boolean[] solid) {
		initializeSharedFluxes(volume);
		for (int c = 0; c < cellCount; c++) {
			SignedDyadic localCapacity = exactCapacity[c];
			if (after[c].signum() < 0) throw new IllegalStateException("Shared liquid flux overdrew its donor");
			if (solid[c]) continue;
			while (after[c].compareTo(localCapacity) > 0) {
				Arrays.fill(predecessor, -1); int head = 0, tail = 0, destination = -1;
				queue[tail++] = c; predecessor[c] = -2;
				while (head < tail && destination < 0) {
					int current = queue[head++];
					for (int f = incomingHead[current]; f >= 0; f = incomingNext[f]) {
						int previous = donor(f);
						if (exactFlux[f].signum() == 0 || predecessor[previous] != -1) continue;
						predecessor[previous] = f; queue[tail++] = previous;
						if (after[previous].compareTo(exactCapacity[previous]) < 0) { destination = previous; break; }
					}
				}
				if (destination < 0) {
					if (augmentOutgoing(c)) continue;
					if (repairQuantization(c, volume, solid)) continue;
					throw new MechanicalTimeRefinement(2, "Shared liquid flux at cell " + c + ": excess="
							+ after[c].subtract(localCapacity).toDouble() + ", initial=" + volume[c].toDouble()
							+ ", finalCapacity=" + localCapacity.toDouble());
				}
				SignedDyadic amount = min(after[c].subtract(localCapacity), exactCapacity[destination].subtract(after[destination]));
				for (int node = destination; node != c; node = receiver(predecessor[node])) amount = min(amount, exactFlux[predecessor[node]]);
				for (int node = destination; node != c; node = receiver(predecessor[node])) {
					int f = predecessor[node]; exactFlux[f] = exactFlux[f].subtract(amount);
					remainingSupply[donor(f)] = remainingSupply[donor(f)].add(amount);
				}
				after[c] = after[c].subtract(amount); after[destination] = after[destination].add(amount);
			}
		}
	}

	private void initializeSharedFluxes(SignedDyadic[] volume) {
		Arrays.fill(after, SignedDyadic.ZERO);
		for (int f = 0; f < flux.length; f++) after[donor(f)] = after[donor(f)].add(exactFlux[f]);
		for (int f = flux.length - 1; f >= 0; f--) {
			int donor = donor(f);
			SignedDyadic excess = after[donor].subtract(volume[donor]);
			if (excess.signum() > 0) {
				SignedDyadic reduce = min(excess, exactFlux[f]);
				exactFlux[f] = exactFlux[f].subtract(reduce); after[donor] = after[donor].subtract(reduce);
			}
		}
		for (int c = 0; c < cellCount; c++) remainingSupply[c] = volume[c].subtract(after[c]);
		System.arraycopy(volume, 0, after, 0, cellCount); Arrays.fill(incomingHead, -1);
		// Keep one adjacency entry per channel even while its transfer is zero;
		// capacity repair can cancel and later augment that same channel.
		for (int f = 0; f < flux.length; f++) {
			int donor = donor(f), receiver = receiver(f);
			after[donor] = after[donor].subtract(exactFlux[f]); after[receiver] = after[receiver].add(exactFlux[f]);
			incomingNext[f] = incomingHead[receiver]; incomingHead[receiver] = f;
		}
	}

	/** A moving solid changes the control-volume capacity. Complete its finite shared
	 * displacement with available outward face flux, using beginning-of-step liquid supply. */
	private boolean augmentOutgoing(int source) {
		Arrays.fill(predecessor, -1); int head = 0, tail = 0, destination = -1;
		queue[tail++] = source; predecessor[source] = -2;
		while (head < tail && destination < 0) {
			int current = queue[head++];
			if (remainingSupply[current].signum() <= 0) continue;
			for (int f : adjacent(current)) if (f >= 0 && donor(f) == current && upperFlux[f].compareTo(exactFlux[f]) > 0) {
				int next = receiver(f); if (predecessor[next] != -1) continue;
				predecessor[next] = f; queue[tail++] = next;
				if (after[next].compareTo(exactCapacity[next]) < 0) { destination = next; break; }
			}
		}
		if (destination < 0) return false;
		SignedDyadic amount = min(after[source].subtract(exactCapacity[source]), exactCapacity[destination].subtract(after[destination]));
		for (int node = destination; node != source; node = donor(predecessor[node])) {
			int f = predecessor[node]; amount = min(amount, min(upperFlux[f].subtract(exactFlux[f]), remainingSupply[donor(f)]));
		}
		for (int node = destination; node != source; node = donor(predecessor[node])) {
			int f = predecessor[node];
			SignedDyadic previous = exactFlux[f];
			exactFlux[f] = previous.add(amount);
			geometricBlend[f] *= previous.toDouble() / exactFlux[f].toDouble();
			representationBlend[f] *= previous.toDouble() / exactFlux[f].toDouble();
			remainingSupply[donor(f)] = remainingSupply[donor(f)].subtract(amount);
		}
		after[source] = after[source].subtract(amount); after[destination] = after[destination].add(amount);
		return true;
	}

	/** Representation rounding has a finite local budget, separate from the velocity face sweep. */
	private boolean repairQuantization(int source, SignedDyadic[] volume, boolean[] solid) {
		if (quantizationBudget == null) initializeQuantizationBudget();
		SignedDyadic excess = after[source].subtract(exactCapacity[source]);
		if (excess.compareTo(quantizationBudget[source]) > 0) return false;
		Arrays.fill(predecessor, -1); int read = 0, written = 0, destination = -1;
		queue[written++] = source; predecessor[source] = -2;
		while (read < written && destination < 0) {
			int current = queue[read++];
			// Directed channels retain their geometric exchange. Prefer returning an
			// incoming parcel, then append to the canonical outward channel.
			for (int pass = 0; pass < (bidirectional ? 2 : 1) && destination < 0; pass++)
			for (int face : adjacent(current)) if (face >= 0) {
				if (bidirectional && (donor(face) == current) != (pass == 1)) continue;
				int next = left[face] == current ? right[face] : left[face];
				if (solid[next] || predecessor[next] != -1 || geometry != null && !geometry.connected(left[face], right[face], axis[face])
						|| representationAllowance(current, face).signum() == 0) continue;
				predecessor[next] = 2 * face + (left[face] == current ? 0 : 1); queue[written++] = next;
				if (after[next].compareTo(exactCapacity[next]) < 0) { destination = next; break; }
			}
		}
		if (destination < 0) return false;
		SignedDyadic amount = min(excess, exactCapacity[destination].subtract(after[destination]));
		for (int node = destination; node != source;) {
			int edge = predecessor[node], face = edge / 2, previous = (edge & 1) == 0 ? left[face] : right[face];
			amount = min(amount, representationAllowance(previous, face)); node = previous;
		}
		if (!representationPathFits(source, destination, amount, volume)) return false;
		for (int node = destination; node != source;) {
			int edge = predecessor[node], face = edge / 2, previous = (edge & 1) == 0 ? left[face] : right[face];
			SignedDyadic old = exactFlux[face];
			if (donor(face) == previous) {
				exactFlux[face] = old.add(amount);
				double ratio = old.toDouble() / exactFlux[face].toDouble();
				geometricBlend[face] *= ratio;
				representationBlend[face] = representationBlend[face] * ratio + 1 - ratio;
			} else if (old.compareTo(amount) >= 0) exactFlux[face] = old.subtract(amount);
			else {
				if (bidirectional) throw new IllegalStateException("Directed liquid channel cannot reverse its geometric sweep");
				exactFlux[face] = amount.subtract(old);
				velocity[face] = Math.copySign(Math.max(Double.MIN_VALUE, Math.abs(velocity[face])), (edge & 1) == 0 ? 1 : -1);
				geometricBlend[face] = 0; representationBlend[face] = 1;
				upperFlux[face] = SignedDyadic.ZERO;
			}
			node = previous;
		}
		quantizationBudget[source] = quantizationBudget[source].subtract(amount);
		initializeSharedFluxes(volume);
		return true;
	}

	private void initializeQuantizationBudget() {
		SignedDyadic[] kernelError = geometry == null ? null : geometry.quantizationBudget(dtOverH);
		quantizationBudget = new SignedDyadic[cellCount]; Arrays.fill(quantizationBudget, SignedDyadic.ZERO);
		// Keep both signed errors until their sum: incoming floor loss can cancel solid or outgoing error.
		for (int face = 0; face < flux.length; face++) if (faceFloorLoss[face] != null) {
			int donor = donor(face), receiver = receiver(face);
			quantizationBudget[donor] = quantizationBudget[donor].add(faceFloorLoss[face]);
			quantizationBudget[receiver] = quantizationBudget[receiver].subtract(faceFloorLoss[face]);
		}
		for (int cell = 0; cell < cellCount; cell++) quantizationBudget[cell] = representationBudget(
				kernelError == null ? SignedDyadic.ZERO : kernelError[cell], quantizationBudget[cell]);
	}

	/** Round the combined error once, on the solid-capacity lattice, with less than one SVQ of envelope. */
	static SignedDyadic representationBudget(SignedDyadic kernelError, SignedDyadic faceError) {
		SignedDyadic error = kernelError.add(faceError);
		if (error.signum() <= 0) return SignedDyadic.ZERO;
		if (kernelError.signum() == 0) return error;
		int exponent = error.exponent() + 24;
		var ceiling = exponent >= 0 ? error.significand().shiftLeft(exponent)
				: error.significand().negate().shiftRight(-exponent).negate();
		return new SignedDyadic(ceiling, -24);
	}

	private SignedDyadic representationAllowance(int current, int face) {
		if (bidirectional && donor(face) != current) return exactFlux[face];
		SignedDyadic supply = remainingSupply[current];
		if (predecessor[current] >= 0) {
			int incoming = predecessor[current] / 2;
			if (donor(incoming) == current) supply = supply.add(exactFlux[incoming]);
		}
		SignedDyadic cancellation = donor(face) == current ? SignedDyadic.ZERO : exactFlux[face];
		if (donor(face) == current && exactFlux[face].signum() > 0
				|| carrierShares == null || carrierShares[current] >= 7) return supply.add(cancellation);
		int outgoingFaces = 0;
		for (int adjacent : adjacent(current)) if (adjacent >= 0 && donor(adjacent) == current && exactFlux[adjacent].signum() > 0) outgoingFaces++;
		boolean releasesFace = predecessor[current] >= 0 && donor(predecessor[current] / 2) == current;
		return outgoingFaces < carrierShares[current] - 1 || releasesFace ? supply.add(cancellation) : cancellation;
	}

	/** A reverse edge returns its original donor inventory; a forward arrival never becomes new supply. */
	private boolean representationPathFits(int source, int destination, SignedDyadic amount, SignedDyadic[] volume) {
		int outgoing = -1;
		for (int current = destination;;) {
			SignedDyadic sent = SignedDyadic.ZERO; int faces = 0;
			for (int face : adjacent(current)) if (face >= 0) {
				SignedDyadic signed = donor(face) == current ? exactFlux[face] : exactFlux[face].negate();
				if (face == outgoing) signed = signed.add(amount);
				if (predecessor[current] >= 0 && face == predecessor[current] / 2) signed = signed.subtract(amount);
				if (signed.signum() > 0) { sent = sent.add(signed); faces++; }
			}
			if (sent.compareTo(volume[current]) > 0) throw new IllegalStateException("Representation remap overdrew its original donor inventory");
			if (carrierShares != null && faces > 0 && faces >= carrierShares[current]) return false;
			if (current == source) return true;
			int edge = predecessor[current]; outgoing = edge / 2;
			current = (edge & 1) == 0 ? left[outgoing] : right[outgoing];
		}
	}

	boolean positive(int face) { return Double.doubleToRawLongBits(velocity[face]) >= 0; }
	int donor(int f) { return positive(f) ? left[f] : right[f]; }
	/** Fraction of the final face transfer assigned to the limited geometric sweep. */
	double geometricBlend(int face) { return geometricBlend[face]; }
	double representationBlend(int face) { return representationBlend[face]; }
	void sweptMoments(int face, double[] result) {
		int donor = donor(face), neighbor = receiver(face), a = axis[face];
		if (faceSweep != null) {
			faceSweep.sweptMoments(face % physicalFaceCount, donor, neighbor, a, positive(face), dtOverH, 255, 255, result); return;
		}
		double width = Math.abs(velocity[face]) * dtOverH;
		boolean positive = positive(face);
		if (geometry != null) {
			geometry.sweptMoments(donor, neighbor, a, width, positive,
					normalX[donor], normalY[donor], normalZ[donor], offset[donor], result); return;
		}
		double nx = normalX[donor], ny = normalY[donor], nz = normalZ[donor], off = offset[donor];
		if (positive) off -= (a == 0 ? nx : a == 1 ? ny : nz) * (1 - width);
		LiquidFluxGeometry.unitCubeMoments(a == 0 ? nx * width : nx, a == 1 ? ny * width : ny,
				a == 2 ? nz * width : nz, off, result);
		double volume = result[0];
		for (int i = 0; i < 3; i++) result[i + 1] = width * ((i == a ? (positive ? 1 - width : 0) * volume
				+ width * result[i + 1] : result[i + 1]) - .5 * volume);
		result[0] *= width;
	}
	int receiver(int f) { return positive(f) ? right[f] : left[f]; }
	SignedDyadic[] resultingVolumes() { return after; }
	/** Publish only the port-level reductions of this same prospective face plan. */
	void adoptPortFluxes(SignedDyadic[] volume, SignedDyadic[] amounts) {
		System.arraycopy(volume, 0, after, 0, cellCount);
		for (int face = 0; face < exactFlux.length; face++) {
			if (amounts[face].signum() < 0 || amounts[face].compareTo(exactFlux[face]) > 0)
				throw new IllegalStateException("Micro ports increased their shared face transfer");
			exactFlux[face] = amounts[face]; flux[face] = Math.copySign(amounts[face].toDouble(), velocity[face]);
			after[donor(face)] = after[donor(face)].subtract(amounts[face]);
			after[receiver(face)] = after[receiver(face)].add(amounts[face]);
		}
		for (int cell = 0; cell < cellCount; cell++) if (after[cell].signum() < 0 || after[cell].compareTo(exactCapacity[cell]) > 0)
			throw new MechanicalTimeRefinement(2, "Micro port reduction cannot satisfy final whole-cell capacity");
	}
	void sweptMoments(int face, int donorSupport, int receiverSupport, double[] result) {
		if (faceSweep != null) {
			faceSweep.sweptMoments(face % physicalFaceCount, donor(face), receiver(face), axis[face], positive(face), dtOverH,
					donorSupport, receiverSupport, result); return;
		}
		if (geometry == null) { sweptMoments(face, result); return; }
		int donor = donor(face);
		geometry.sweptMoments(donor, receiver(face), axis[face], Math.abs(velocity[face]) * dtOverH, positive(face),
				normalX[donor], normalY[donor], normalZ[donor], offset[donor], result, donorSupport, receiverSupport);
	}
	/** Separate source micro measures from the same frozen directional sweep used to plan this face. */
	void sweptMicroVolumes(int face, int donorSupport, int receiverSupport, double[] swept, double[] wet) {
		if (swept.length != 8 || wet.length != 8) throw new IllegalArgumentException("Missing liquid micro volumes");
		if (faceSweep != null) {
			faceSweep.sweptMicroVolumes(face % physicalFaceCount, donor(face), receiver(face), axis[face], positive(face),
					dtOverH, donorSupport, receiverSupport, swept, wet); return;
		}
		int donor = donor(face); double[] moments = new double[4];
		for (int m = 0; m < 8; m++) {
			swept[m] = wet[m] = 0;
			if ((donorSupport & 1 << m) == 0) continue;
			if (geometry != null) {
				geometry.sweptMoments(donor, receiver(face), axis[face], Math.abs(velocity[face]) * dtOverH, positive(face),
						normalX[donor], normalY[donor], normalZ[donor], offset[donor], moments, 1 << m, receiverSupport);
				swept[m] = moments[0];
				geometry.sweptMoments(donor, receiver(face), axis[face], 1, positive(face), normalX[donor], normalY[donor],
						normalZ[donor], offset[donor], moments, 1 << m, receiverSupport); wet[m] = moments[0];
			} else {
				double ox = .5 * (m & 1), oy = .5 * (m >>> 1 & 1), oz = .5 * (m >>> 2 & 1);
				LiquidFluxGeometry.unitCubeMoments(.5 * normalX[donor], .5 * normalY[donor], .5 * normalZ[donor],
						offset[donor] - normalX[donor] * ox - normalY[donor] * oy - normalZ[donor] * oz, moments);
				wet[m] = .125 * moments[0];
				double lower = axis[face] == 0 ? ox : axis[face] == 1 ? oy : oz;
				double width = Math.abs(velocity[face]) * dtOverH;
				double lo = Math.max(lower, positive(face) ? 1 - width : 0), hi = Math.min(lower + .5, positive(face) ? 1 : width);
				if (hi <= lo) continue;
				double ex = .5, ey = .5, ez = .5;
				if (axis[face] == 0) { ox = lo; ex = hi - lo; }
				else if (axis[face] == 1) { oy = lo; ey = hi - lo; } else { oz = lo; ez = hi - lo; }
				LiquidFluxGeometry.unitCubeMoments(ex * normalX[donor], ey * normalY[donor], ez * normalZ[donor],
						offset[donor] - normalX[donor] * ox - normalY[donor] * oy - normalZ[donor] * oz, moments);
				swept[m] = ex * ey * ez * moments[0];
			}
		}
	}
	/** Final joint liquid capacity, including any capacity reserved for independent arrivals. */
	SignedDyadic finalCapacity(int cell) {
		return reserved == null ? exactCapacity[cell] : exactCapacity[cell].add(reserved[cell]);
	}
	void geometricResult(SignedDyadic[] result, SignedDyadic[] capacities) {
		System.arraycopy(result, 0, after, 0, cellCount);
		System.arraycopy(capacities, 0, exactCapacity, 0, cellCount);
	}
	private int index(int x, int y, int z) { return (z * sizeY + y) * sizeX + x; }
	private int[] adjacent(int c) {
		int x = c % sizeX, y = c / sizeX % sizeY, z = c / (sizeX * sizeY);
		int yBase = (sizeX - 1) * sizeY * sizeZ, zBase = yBase + sizeX * (sizeY - 1) * sizeZ;
		int[] physical = {x > 0 ? (z * sizeY + y) * (sizeX - 1) + x - 1 : -1,
				x + 1 < sizeX ? (z * sizeY + y) * (sizeX - 1) + x : -1,
				y > 0 ? yBase + (z * (sizeY - 1) + y - 1) * sizeX + x : -1,
				y + 1 < sizeY ? yBase + (z * (sizeY - 1) + y) * sizeX + x : -1,
				z > 0 ? zBase + ((z - 1) * sizeY + y) * sizeX + x : -1,
				z + 1 < sizeZ ? zBase + (z * sizeY + y) * sizeX + x : -1};
		if (!bidirectional) return physical;
		int[] result = new int[12];
		System.arraycopy(physical, 0, result, 0, 6);
		for (int i = 0; i < 6; i++) result[i + 6] = physical[i] < 0 ? -1 : physical[i] + physicalFaceCount;
		return result;
	}
	private double sample(double[] volume, boolean[] solid, int x, int y, int z, int fallback) {
		int c = x < 0 || x >= sizeX || y < 0 || y >= sizeY || z < 0 || z >= sizeZ ? fallback : index(x, y, z);
		if (solid[c] || capacity[c] == 0) c = fallback;
		return capacity[c] == 0 ? 0 : volume[c] / capacity[c];
	}
	private static SignedDyadic min(SignedDyadic a, SignedDyadic b) { return a.compareTo(b) <= 0 ? a : b; }
}
