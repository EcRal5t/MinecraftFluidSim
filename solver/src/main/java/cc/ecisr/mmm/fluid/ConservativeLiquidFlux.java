package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.compute.CpuWorkBatch;
import java.util.Arrays;

/** Shared geometric liquid fluxes with conservative multidimensional capacity constraints. */
final class ConservativeLiquidFlux {
	final int sizeX, sizeY, sizeZ, cellCount;
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
	private double dtOverH;
	private LiquidFluxGeometry geometry;
	private SignedDyadic[] reserved;
	private int[] carrierShares;

	ConservativeLiquidFlux(int sizeX, int sizeY, int sizeZ) {
		this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ;
		cellCount = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		int faces = (sizeX - 1) * sizeY * sizeZ + sizeX * (sizeY - 1) * sizeZ + sizeX * sizeY * (sizeZ - 1);
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
	}

	void geometry(LiquidFluxGeometry geometry) { this.geometry = geometry; }
	void reserve(SignedDyadic[] reserved) { this.reserved = reserved; }
	void carrierShares(int[] carrierShares) { this.carrierShares = carrierShares; }

	/** Volumes and capacities are in this grid's cell-volume units; velocities are m/s. */
	void plan(double[] volume, boolean[] solid, double dtOverH, SignedDyadic[] exactVolume) {
		this.dtOverH = dtOverH;
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
			if (exactCapacity[c].signum() < 0) throw new MechanicalTimeRefinement(2);
			finalCapacity[c] = exactCapacity[c].toDouble();
			if (SignedDyadic.of(finalCapacity[c]).compareTo(exactCapacity[c]) > 0) finalCapacity[c] = Math.nextDown(finalCapacity[c]);
		}
		for (int f = 0; f < flux.length; f++) aperture[f] = geometry == null ? 1 : geometry.aperture(left[f], right[f], axis[f]);
		Arrays.fill(outgoing, 0);
		for (int f = 0; f < flux.length; f++) {
			if (solid[left[f]] || solid[right[f]] || aperture[f] == 0) { velocity[f] = 0; continue; }
			int donor = velocity[f] >= 0 ? left[f] : right[f];
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
			for (int f = from; f < until; f++) {
				double courant = velocity[f] * dtOverH;
				int donor = courant >= 0 ? left[f] : right[f];
				if (volume[donor] == 0 || courant == 0 || aperture[f] == 0) { flux[f] = 0; continue; }
				double width = Math.abs(courant), nx = normalX[donor], ny = normalY[donor], nz = normalZ[donor], off = offset[donor];
				if (courant > 0) off -= (axis[f] == 0 ? nx : axis[f] == 1 ? ny : nz) * (1 - width);
				if (axis[f] == 0) nx *= width; else if (axis[f] == 1) ny *= width; else nz *= width;
				donorFlux[f] = capacity[donor] == 0 ? 0 : courant * aperture[f] * volume[donor] / capacity[donor];
				double swept = geometry == null ? Math.abs(courant) * FlipFreeSurfaceGeometry.unitCubeLiquidFraction(nx, ny, nz, off)
						: geometry.swept(donor, courant >= 0 ? right[f] : left[f], axis[f], Math.abs(courant), courant >= 0,
								normalX[donor], normalY[donor], normalZ[donor], offset[donor]);
				flux[f] = Math.copySign(swept, courant) - donorFlux[f];
			}
		});
		for (int f = 0; f < flux.length; f++) {
			double courant = velocity[f] * dtOverH;
			int donor = courant >= 0 ? left[f] : right[f];
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
			int donor = velocity[f] >= 0 ? left[f] : right[f];
			flux[f] = (capacity[donor] == 0 ? 0 : velocity[f] * dtOverH * aperture[f] * volume[donor] / capacity[donor]) + a * limiter;
			geometricBlend[f] = flux[f] == 0 ? 0 : Math.max(0, Math.min(1, limiter * (donorFlux[f] + a) / flux[f]));
			outgoing[donor] += Math.abs(flux[f]);
		}
	}

	private void quantizeDonorSupply(double[] volume, double dtOverH) {
		// A single-step carrier commit draws from beginning-of-step inventory.
		// Limiting outgoing flux prevents same-step transshipment through empty parcels.
		for (int f = 0; f < flux.length; f++) {
			int donor = flux[f] >= 0 ? left[f] : right[f];
			double scale = outgoing[donor] <= volume[donor] ? 1 : volume[donor] / outgoing[donor];
			double amount = Math.abs(flux[f]) * scale;
			double resolution = ConservativeMarkerSampling.VOLUME_FLUX_RESOLUTION;
			double stored = Math.floor(amount / resolution) * resolution;
			exactFlux[f] = SignedDyadic.of(stored);
			flux[f] = Math.copySign(stored, flux[f]);
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
				if (incomingHead[c]++ >= carrierShares[c] - 1) { exactFlux[selected] = SignedDyadic.ZERO; upperFlux[selected] = SignedDyadic.ZERO; }
			}
			for (int f = 0; f < flux.length; f++)
				if (carrierShares[donor(f)] < 7 && (incomingNext[f] != -2 || exactFlux[f].signum() == 0))
					upperFlux[f] = SignedDyadic.ZERO;
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
		for (int f = 0; f < flux.length; f++) if (exactFlux[f].signum() > 0) {
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
			if (exactFlux[f].signum() == 0) {
				incomingNext[f] = incomingHead[receiver(f)]; incomingHead[receiver(f)] = f;
			}
			SignedDyadic previous = exactFlux[f];
			exactFlux[f] = previous.add(amount);
			geometricBlend[f] *= previous.toDouble() / exactFlux[f].toDouble();
			remainingSupply[donor(f)] = remainingSupply[donor(f)].subtract(amount);
		}
		after[source] = after[source].subtract(amount); after[destination] = after[destination].add(amount);
		return true;
	}

	int donor(int f) { return velocity[f] >= 0 ? left[f] : right[f]; }
	/** Fraction of the final face transfer assigned to the limited geometric sweep. */
	double geometricBlend(int face) { return geometricBlend[face]; }
	void sweptMoments(int face, double[] result) {
		int donor = donor(face), neighbor = receiver(face), a = axis[face];
		double width = Math.abs(velocity[face]) * dtOverH;
		boolean positive = velocity[face] >= 0;
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
	int receiver(int f) { return velocity[f] >= 0 ? right[f] : left[f]; }
	SignedDyadic[] resultingVolumes() { return after; }
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
		return new int[] {x > 0 ? (z * sizeY + y) * (sizeX - 1) + x - 1 : -1,
				x + 1 < sizeX ? (z * sizeY + y) * (sizeX - 1) + x : -1,
				y > 0 ? yBase + (z * (sizeY - 1) + y - 1) * sizeX + x : -1,
				y + 1 < sizeY ? yBase + (z * (sizeY - 1) + y) * sizeX + x : -1,
				z > 0 ? zBase + ((z - 1) * sizeY + y) * sizeX + x : -1,
				z + 1 < sizeZ ? zBase + (z * sizeY + y) * sizeX + x : -1};
	}
	private double sample(double[] volume, boolean[] solid, int x, int y, int z, int fallback) {
		int c = x < 0 || x >= sizeX || y < 0 || y >= sizeY || z < 0 || z >= sizeZ ? fallback : index(x, y, z);
		if (solid[c] || capacity[c] == 0) c = fallback;
		return capacity[c] == 0 ? 0 : volume[c] / capacity[c];
	}
	private static SignedDyadic min(SignedDyadic a, SignedDyadic b) { return a.compareTo(b) <= 0 ? a : b; }
}
