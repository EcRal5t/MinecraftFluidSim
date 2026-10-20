package mmm.fluid;

import mmm.granular.SolidVolume;
import java.util.Arrays;

/** One bounded fluid aperture and complementary parcel pressure shares on the MAC faces. */
final class GranularFaceVolumes {
		private final FluidDomain domain;
	private final int[] offsets = new int[3];
	private final float[] liquid, fixedPore;
	private int[] parcelFaces=new int[0],parcelCounts=new int[0];
	private double[] parcelVolumes=new double[0];
	private final double[] activeVolume, fixedVolume;
	private final int[] faces = new int[8];
	private final double[] volumes = new double[8];
	private long revision;

	GranularFaceVolumes(FluidDomain domain) {
		this.domain = domain;
		offsets[1] = domain.mac.velocityX.length;
		offsets[2] = offsets[1] + domain.mac.velocityY.length;
		liquid = new float[offsets[2] + domain.mac.velocityZ.length]; fixedPore = new float[liquid.length];
		activeVolume = new double[liquid.length]; fixedVolume = new double[domain.cellCount];
	}

	long revision() { return revision; }
	float open(int axis, int local) { return liquid[offsets[axis] + local]; }
	float fixedPoreOpen(int axis, int local) { return fixedPore[offsets[axis] + local]; }

	void rebuild() {
		Arrays.fill(activeVolume, 0); Arrays.fill(fixedVolume, 0);
		int count = domain.granularState == null ? 0 : domain.granularState.parcels.count();
		if(parcelCounts.length<3*count){parcelCounts=new int[3*count];parcelFaces=new int[24*count];parcelVolumes=new double[24*count];}

		if (domain.pressureGranularCoupling != null) {
			for (int cell = 0; cell < domain.cellCount; cell++)
				fixedVolume[cell] = SolidVolume.toCellVolume(domain.pressureGranularCoupling.fixedCellSvq(cell));
			if (domain.granularState != null) domain.granularState.deposits.forEachCell(
					(cell, svq, porosity) -> fixedVolume[cell] += SolidVolume.toCellVolume(svq));
		}
		for (int p = 0; p < count; p++) {
			var parcel = domain.granularState.parcels.parcel(p);
			for (int axis = 0; axis < 3; axis++) {
				double h = domain.config.cellSize();
				double px = parcel.x() / h - (axis == 0 ? 0 : .5);
				double py = parcel.y() / h - (axis == 1 ? 0 : .5);
				double pz = parcel.z() / h - (axis == 2 ? 0 : .5);
				int x0 = (int) Math.floor(px), y0 = (int) Math.floor(py), z0 = (int) Math.floor(pz);
				int base = 0, entries = 0;
				double sum = 0;
				for (int dz = 0; dz < 2; dz++) for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) {
					int x = x0 + dx, y = y0 + dy, z = z0 + dz;
					if (x < 0 || x >= domain.sizeX + (axis == 0 ? 1 : 0)
							|| y < 0 || y >= domain.sizeY + (axis == 1 ? 1 : 0)
							|| z < 0 || z >= domain.sizeZ + (axis == 2 ? 1 : 0)) continue;
					if (axis == 0 && (x == 0 || x == domain.sizeX)
							|| axis == 1 && (y == 0 || y == domain.sizeY)
							|| axis == 2 && (z == 0 || z == domain.sizeZ)) continue;
					int local = local(axis, x, y, z);
					double weight = (dx == 0 ? 1 - px + x0 : px - x0)
							* (dy == 0 ? 1 - py + y0 : py - y0) * (dz == 0 ? 1 - pz + z0 : pz - z0)
							* geometric(axis, local);
					if (!(weight > 0)) continue;
					faces[base + entries] = offsets[axis] + local;
					volumes[base + entries++] = weight; sum += weight;
				}
				// A blocked axis transfers its pressure load to the fixed geometry through contact.
				parcelCounts[3*p+axis]=entries;
				if (sum == 0) continue;
				double fraction = SolidVolume.toCellVolume(parcel.solidVolumeSvq());
				for (int i = base; i < base + entries; i++) {
					volumes[i] *= fraction / sum;
					activeVolume[faces[i]] += volumes[i];
					int slot=24*p+8*axis+i;parcelFaces[slot]=faces[i];parcelVolumes[slot]=volumes[i];
				}
			}
		}
		boolean hasFixed=false;
		for(double volume:fixedVolume)if(volume>0){hasFixed=true;break;}
		for (int axis = 0; axis < 3; axis++) {
			float[] geometric=domain.geometry.cutCells.openFaceField(axis);
			for (int local = 0; local < geometric.length; local++) {
				int face = offsets[axis] + local;
				double available = geometric[local] * (hasFixed ? 1-fixedFraction(axis,local) : 1);
				fixedPore[face] = (float) available;
				liquid[face] = (float) Math.max(0, available - activeVolume[face]);
				if (liquid[face] > available) liquid[face] = Math.nextDown(liquid[face]);
			}
		}
		revision++;
	}

	int parcelFaceCount(int parcel,int axis){return parcelCounts[3*parcel+axis];}
	int parcelFace(int parcel,int axis,int slot){return parcelFaces[24*parcel+8*axis+slot];}
	double parcelPressureShare(int parcel,int axis,int slot){
		int index=24*parcel+8*axis+slot,face=parcelFaces[index];
		return activeVolume[face]>0?parcelVolumes[index]/activeVolume[face]:0;
	}
	double solidArea(int face){return Math.max(0,fixedPore[face]-liquid[face]);}

	private int length(int axis) { return axis == 0 ? domain.mac.velocityX.length : axis == 1 ? domain.mac.velocityY.length : domain.mac.velocityZ.length; }
	private int local(int axis, int x, int y, int z) { return axis == 0 ? domain.domainFlowFields.xFaceIndex(x, y, z) : axis == 1 ? domain.domainFlowFields.yFaceIndex(x, y, z) : domain.domainFlowFields.zFaceIndex(x, y, z); }
	private int rightCell(int axis, int local) {
		int nx = domain.sizeX + (axis == 0 ? 1 : 0), ny = domain.sizeY + (axis == 1 ? 1 : 0);
		return domain.domainFlowFields.cellIndex(local % nx, local / nx % ny, local / (nx * ny));
	}
	private double geometric(int axis, int local) {
		int nx = domain.sizeX + (axis == 0 ? 1 : 0), ny = domain.sizeY + (axis == 1 ? 1 : 0);
		int x = local % nx, y = local / nx % ny, z = local / (nx * ny);
		return axis == 0 ? domain.geometry.cutCells.xFaceOpen(x, y, z)
				: axis == 1 ? domain.geometry.cutCells.yFaceOpen(x, y, z) : domain.geometry.cutCells.zFaceOpen(x, y, z);
	}
	private double fixedFraction(int axis, int local) {
		int nx = domain.sizeX + (axis == 0 ? 1 : 0), ny = domain.sizeY + (axis == 1 ? 1 : 0);
		int x = local % nx, y = local / nx % ny, z = local / (nx * ny);
		if (axis == 0 && (x == 0 || x == domain.sizeX) || axis == 1 && (y == 0 || y == domain.sizeY)
				|| axis == 2 && (z == 0 || z == domain.sizeZ)) return 0;
		int right = domain.domainFlowFields.cellIndex(x, y, z), left = right - (axis == 0 ? 1 : axis == 1 ? domain.sizeX : domain.sizeX * domain.sizeY);
		double a = domain.geometry.cutCells.openCellVolume(left), b = domain.geometry.cutCells.openCellVolume(right);
		return Math.min(1, Math.max(a > 0 ? fixedVolume[left] / a : 1, b > 0 ? fixedVolume[right] / b : 1));
	}
}
