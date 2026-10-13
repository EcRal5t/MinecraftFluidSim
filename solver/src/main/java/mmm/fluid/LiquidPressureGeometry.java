package mmm.fluid;

import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.List;

/** Immutable positive PLIC/open-microbox geometry for volume divergence and affine flux traces. */
final class LiquidPressureGeometry {
	record Vec3(double x, double y, double z) {
		static final Vec3 ZERO = new Vec3(0, 0, 0);
		double component(int axis) { return axis == 0 ? x : axis == 1 ? y : z; }
		Vec3 scale(double scale) { return new Vec3(scale * x, scale * y, scale * z); }
		Vec3 add(Vec3 value) { return new Vec3(x + value.x, y + value.y, z + value.z); }
	}
	/** Raw physical integrals of x_a*x_b over a surface. */
	record SecondMoment(double xx, double xy, double xz, double yy, double yz, double zz) {
		static final SecondMoment ZERO = new SecondMoment(0, 0, 0, 0, 0, 0);
		double component(int a, int b) {
			if (a > b) { int swap = a; a = b; b = swap; }
			return a == 0 ? b == 0 ? xx : b == 1 ? xy : xz : a == 1 ? b == 1 ? yy : yz : zz;
		}
		SecondMoment add(SecondMoment m) { return new SecondMoment(xx+m.xx,xy+m.xy,xz+m.xz,yy+m.yy,yz+m.yz,zz+m.zz); }
		static SecondMoment outer(Vec3 v,double scale) { return new SecondMoment(scale*v.x*v.x,scale*v.x*v.y,scale*v.x*v.z,scale*v.y*v.y,scale*v.y*v.z,scale*v.z*v.z); }
	}
	record SurfaceMoments(double area, Vec3 firstMoment, SecondMoment secondMoment, Vec3 centroid, SecondMoment centralSecondMoment) {
		static final SurfaceMoments EMPTY = new SurfaceMoments(0, Vec3.ZERO, SecondMoment.ZERO,Vec3.ZERO,SecondMoment.ZERO);
		SurfaceMoments add(SurfaceMoments m) {
			if(area==0)return m;if(m.area==0)return this;if(area<m.area)return m.add(this);
			double total=area+m.area;Vec3 distance=m.centroid.add(centroid.scale(-1));
			Vec3 center=centroid.add(distance.scale(m.area/total));
			SecondMoment central=centralSecondMoment.add(m.centralSecondMoment).add(SecondMoment.outer(distance,area*(m.area/total)));
			return new SurfaceMoments(total,center.scale(total),central.add(SecondMoment.outer(center,total)),center,central);
		}
		double centeredSecondMoment(int a, int b, Vec3 origin) {
			return centralSecondMoment.component(a,b)+area*(centroid.component(a)-origin.component(a))*(centroid.component(b)-origin.component(b));
		}
	}
	enum FacetKind { WALL, GAS }
	/** Micro key is owner microbox*6+axis*2+(positive-side?1:0); peer=-1 denotes the fixed exterior. */
	record Facet(FacetKind kind, int cell, int peerCell, int axis, int sign, int microKey, SurfaceMoments moments) {
		int gasPiece() {
			if (kind != FacetKind.GAS) throw new IllegalStateException("A wall facet has no gas piece");
			return 8 * peerCell + ((microKey / 6) ^ (1 << axis));
		}
	}
	record SideWet(int axis, int sign, int microKey, SurfaceMoments moments) { }
	/** One physical shared microface, oriented from the negative-axis cell to the positive-axis cell. */
	record SharedFace(int minusCell, int plusCell, int axis, int microKey, SurfaceMoments moments) { }
	/** Rows store integral n_a*x_b; all fields are immutable and expressed in SI. */
	record NormalFirstMoment(Vec3 x, Vec3 y, Vec3 z) { Vec3 row(int axis) { return axis == 0 ? x : axis == 1 ? y : z; } }
	/** Wet integration piece and actual gas-side microbox are separate when PLIC coincides with a microface. */
	record FreeFragment(int cell, int microKey, int gasMicro, Vec3 areaNormal, NormalFirstMoment normalFirstMoment) {
		int gasPiece() { return 8 * cell + gasMicro; }
	}
	record VolumeMoments(double volume, Vec3 firstMoment) {
		static final VolumeMoments EMPTY = new VolumeMoments(0, Vec3.ZERO);
		VolumeMoments add(VolumeMoments value) { return new VolumeMoments(volume + value.volume, firstMoment.add(value.firstMoment)); }
		VolumeMoments scale(double value) { return value == 1 ? this : new VolumeMoments(volume * value, firstMoment.scale(value)); }
	}
	record CellSnapshot(int cell, double volume, Vec3 firstMoment, Vec3 centroid,
	                    double admittedVolume, double rawMarkerVolume, double rawPressureVolume, double volumeErrorBound,
	                    List<SideWet> sideWet, List<Facet> facets, List<FreeFragment> freeFragments) {
		CellSnapshot { sideWet=List.copyOf(sideWet); facets=List.copyOf(facets); freeFragments=List.copyOf(freeFragments); }
	}
	private final CellSnapshot[] cells;
	private final List<SharedFace> sharedFaces;

	private LiquidPressureGeometry(CellSnapshot[] cells, List<SharedFace> faces) { this.cells=cells; sharedFaces=List.copyOf(faces); }
	static LiquidPressureGeometry capture(FluidDomain domain) {
		return capture(domain, domain.pressurePhaseGeometry);
	}
	static LiquidPressureGeometry capture(FluidDomain domain, FlipPressurePhaseGeometry phase) {
		return capture(domain, phase, null);
	}
	static LiquidPressureGeometry capture(FluidDomain domain, FlipPressurePhaseGeometry phase, List<PhaseLiquidGeometryEdit> stock) {
		if (stock != null && stock.size() != domain.cellCount) throw new IllegalArgumentException("Liquid geometry source stock has the wrong shape");
		var cells = new CellSnapshot[domain.cellCount]; var faces = new ArrayList<SharedFace>();
		for (int cell=0;cell<cells.length;cell++) if(phase.liquidFractionExact(cell)>0)
			cells[cell]=captureCell(domain,phase,cell,faces,stock == null ? domain.markerGrid.exactCellMarkerVolume[cell] : stock.get(cell).volume().toDouble());
		return new LiquidPressureGeometry(cells,faces);
	}
	void forEachGasPiece(java.util.function.IntConsumer accept) {
		for (var cell : cells) if (cell != null) {
			for (var facet : cell.facets()) if (facet.kind() == FacetKind.GAS) accept.accept(facet.gasPiece());
			for (var fragment : cell.freeFragments()) accept.accept(fragment.gasPiece());
		}
	}
	CellSnapshot cellSnapshot(int cell) { return cells[cell]; }
	List<SharedFace> sharedFaces() { return sharedFaces; }
	SurfaceMoments faceMoments(int minus, int plus, int axis) {
		SurfaceMoments result=SurfaceMoments.EMPTY;
		for(SharedFace face:sharedFaces)if(face.minusCell==minus && face.plusCell==plus && face.axis==axis)result=result.add(face.moments);
		return result;
	}

	private static CellSnapshot captureCell(FluidDomain d,FlipPressurePhaseGeometry plane,int cell,List<SharedFace> shared,double rawVolume) {
		var cut=d.geometry.cutCells;
		double h=d.config.cellSize(), h3=h*h*h;
		int[] index={cell%d.sizeX,cell/d.sizeX%d.sizeY,cell/(d.sizeX*d.sizeY)};
		double volume=0,mx=0,my=0,mz=0; int open=0;
		var sides=new ArrayList<SideWet>(); var facets=new ArrayList<Facet>(); var fragments=new ArrayList<FreeFragment>();
		for(int slot=0;slot<8;slot++) {
			int[] micro={slot&1,slot>>1&1,slot>>2&1}; if(!cut.microCellOpen(cell,micro[0],micro[1],micro[2]))continue;
			open++; double ox=.5*micro[0],oy=.5*micro[1],oz=.5*micro[2]; var integral=unitMicroMoments(plane,cell,slot);
			double boxVolume=integral[0]/8; volume+=boxVolume;
			mx+=(ox*integral[0]+.5*integral[1])/8;my+=(oy*integral[0]+.5*integral[2])/8;mz+=(oz*integral[0]+.5*integral[3])/8;
			double[] areaNormal=new double[3]; double[][] normalFirst=new double[3][3];
			for(int axis=0;axis<3;axis++)for(int sign:new int[]{-1,1}) {
				double coordinate=.5*(micro[axis]+(sign>0?1:0)), tangent=.5*micro[(axis+1)%3],other=.5*micro[(axis+2)%3];
				SurfaceMoments wet=LiquidPressureMoments.integrate(plane.wetFacePolygon(cell,axis,coordinate,tangent,other,.5),axis,coordinate,cell,d);
				areaNormal[axis]-=sign*wet.area;
				for(int b=0;b<3;b++)normalFirst[axis][b]-=sign*wet.firstMoment.component(b);
				if(wet.area==0)continue;
				int key=slot*6+2*axis+(sign>0?1:0);
				if(micro[axis]+sign>=0 && micro[axis]+sign<2) {
					int[] neighbor=micro.clone();neighbor[axis]+=sign;
					if(!cut.microCellOpen(cell,neighbor[0],neighbor[1],neighbor[2]))facets.add(new Facet(FacetKind.WALL,cell,cell,axis,sign,key,wet));
					else if(integral[0]>0 && plane.liquidFractionExact(cell,slot)<1 && coincidentFreeFace(plane,cell,slot,axis,sign,coordinate)) {
						// The closed box Gauss sum already includes this full wet face. Its exposed
						// counterpart lies on a dry box, so represent the physical PLIC face once.
						double[] normal=new double[3];normal[axis]=sign*wet.area();
						Vec3[] first={Vec3.ZERO,Vec3.ZERO,Vec3.ZERO};first[axis]=wet.firstMoment().scale(sign);
						fragments.add(new FreeFragment(cell,slot,slot^(1<<axis),vec(normal),new NormalFirstMoment(first[0],first[1],first[2])));
					}
					continue;
				}
				sides.add(new SideWet(axis,sign,key,wet));
				int[] neighbor=index.clone();neighbor[axis]+=sign;
				boolean inside=neighbor[0]>=0 && neighbor[0]<d.sizeX && neighbor[1]>=0 && neighbor[1]<d.sizeY && neighbor[2]>=0 && neighbor[2]<d.sizeZ;
				int peer=inside?d.cellIndex(neighbor[0],neighbor[1],neighbor[2]):-1;
				int[] peerMicro=micro.clone();peerMicro[axis]=sign>0?0:1;
				if(!inside || !cut.microCellOpen(peer,peerMicro[0],peerMicro[1],peerMicro[2])) {
					facets.add(new Facet(FacetKind.WALL,cell,peer,axis,sign,key,wet));continue;
				}
				int minus=sign>0?cell:peer,plus=sign>0?peer:cell;
				if(sign>0 || plane.liquidFractionExact(peer)==0) {
					SurfaceMoments both=LiquidPressureMoments.integrate(plane.sharedWetPolygon(minus,plus,axis,tangent,other,.5),axis,coordinate,cell,d);
					if(both.area>0)shared.add(new SharedFace(minus,plus,axis,micro[(axis+1)%3]+2*micro[(axis+2)%3],both));
				}
				SurfaceMoments gas=LiquidPressureMoments.integrate(plane.exposedWetPolygon(cell,peer,axis,sign,tangent,other,.5),axis,coordinate,cell,d);
				if(gas.area>0)facets.add(new Facet(FacetKind.GAS,cell,peer,axis,sign,key,gas));
			}
			// Gauss identities on each closed wet microbox include its artificial internal box faces.
			if(integral[0]>0 && integral[0]<1) {
				for(int a=0;a<3;a++)normalFirst[a][a]+=boxVolume*h3;
				fragments.add(new FreeFragment(cell,slot,slot,new Vec3(areaNormal[0],areaNormal[1],areaNormal[2]),
						new NormalFirstMoment(vec(normalFirst[0]),vec(normalFirst[1]),vec(normalFirst[2]))));
			}
		}
		double unitVolume=volume;volume*=h3;
		Vec3 first=new Vec3(h3*h*(index[0]*unitVolume+mx),h3*h*(index[1]*unitVolume+my),h3*h*(index[2]*unitVolume+mz));
		double admitted=plane.geometricLiquidVolume(cell,cut)*h3;
		// Absolute quadrature allowance covers the clipped-cube CDF, transformed moments and eight-box reduction.
		double error=(128.0*open+64)*Math.ulp(1.0)*h3+Math.ulp(admitted)+Math.ulp(volume);
		if(!(volume>0 && Double.isFinite(volume)) || Math.abs(volume-admitted)>error)throw new IllegalStateException("PLIC pressure volume integration disagrees with admitted geometry at cell "+cell);
		return new CellSnapshot(cell,volume,first,first.scale(1/volume),admitted,rawVolume*h3,
				d.projection.pressureVolume[cell]*h3,error,sides,facets,fragments);
	}
	static VolumeMoments pieceMoments(FluidDomain domain, FlipPressurePhaseGeometry phase, int cell, int micro) {
		if (cell < 0 || cell >= domain.cellCount || micro < 0 || micro >= 8) throw new IllegalArgumentException("Invalid liquid geometry piece");
		if ((domain.geometry.cutCells.mask(cell) & 1 << micro) != 0) return VolumeMoments.EMPTY;
		var integral = unitMicroMoments(phase, cell, micro);
		double h = domain.config.cellSize(), h3 = h*h*h, volume = integral[0] / 8;
		return new VolumeMoments(volume*h3, new Vec3(
				h3*h*((cell%domain.sizeX + .5*(micro&1))*integral[0] + .5*integral[1])/8,
				h3*h*((cell/domain.sizeX%domain.sizeY + .5*(micro>>1&1))*integral[0] + .5*integral[2])/8,
				h3*h*((cell/(domain.sizeX*domain.sizeY) + .5*(micro>>2&1))*integral[0] + .5*integral[3])/8));
	}
	private static double[] unitMicroMoments(FlipPressurePhaseGeometry plane, int cell, int micro) {
		var integral = new double[4]; double fraction = plane.liquidFractionExact(cell, micro);
		if (fraction >= 1) { integral[0] = 1; integral[1] = integral[2] = integral[3] = .5; }
		else if (fraction > 0) {
			double nx=plane.planeNormal(cell,micro,0), ny=plane.planeNormal(cell,micro,1), nz=plane.planeNormal(cell,micro,2);
			LiquidFluxGeometry.unitCubeMoments(.5*nx,.5*ny,.5*nz,
					plane.planeOffset(cell,micro)-.5*nx*(micro&1)-.5*ny*(micro>>1&1)-.5*nz*(micro>>2&1), integral);
		}
		return integral;
	}
	/** Exact coincidence of the outward wet side with one open internal microface. */
	private static boolean coincidentFreeFace(FlipPressurePhaseGeometry plane,int cell,int micro,int axis,int sign,double coordinate) {
		double normal=plane.planeNormal(cell,micro,axis);
		if(!(sign*normal>0) || plane.planeNormal(cell,micro,(axis+1)%3)!=0 || plane.planeNormal(cell,micro,(axis+2)%3)!=0)return false;
		return SignedDyadic.of(plane.planeOffset(cell,micro)).equals(SignedDyadic.of(normal).multiply(SignedDyadic.of(coordinate)));
	}
	private static Vec3 vec(double[] values) { return new Vec3(values[0],values[1],values[2]); }
}
