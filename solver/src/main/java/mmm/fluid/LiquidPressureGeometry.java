package mmm.fluid;

import mmm.chemistry.ExactRational;
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
	record SurfaceMoments(double area, Vec3 firstMoment, SecondMoment secondMoment, Vec3 centroid, SecondMoment centralSecondMoment,
	                      LiquidPressureMoments.FullFace fullFace) {
		SurfaceMoments(double area, Vec3 firstMoment, SecondMoment secondMoment, Vec3 centroid, SecondMoment centralSecondMoment) {
			this(area, firstMoment, secondMoment, centroid, centralSecondMoment, null);
		}
		static final SurfaceMoments EMPTY = new SurfaceMoments(0, Vec3.ZERO, SecondMoment.ZERO,Vec3.ZERO,SecondMoment.ZERO);
		SurfaceMoments add(SurfaceMoments m) {
			if(area==0)return m;if(m.area==0)return this;if(area<m.area)return m.add(this);
			var face = fullFace == null ? null : fullFace.add(m.fullFace);
			if (face != null && face.mask() == 15) return LiquidPressureMoments.fullFace(face);
			double total=area+m.area;Vec3 distance=m.centroid.add(centroid.scale(-1));
			Vec3 center=centroid.add(distance.scale(m.area/total));
			SecondMoment central=centralSecondMoment.add(m.centralSecondMoment).add(SecondMoment.outer(distance,area*(m.area/total)));
			return new SurfaceMoments(total,center.scale(total),central.add(SecondMoment.outer(center,total)),center,central,face);
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
	record SharedFace(int minusCell, int plusCell, int axis, int microKey, SurfaceMoments moments) {
		int plusMicro() { return (microKey&1)<<((axis+1)%3) | (microKey>>1&1)<<((axis+2)%3); }
		int minusMicro() { return plusMicro() | 1<<axis; }
	}
	record InteriorFace(int cell, int minusMicro, int plusMicro, int axis, SurfaceMoments moments) { }
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
	interface Profile {
		int cell(); double volume(); Vec3 firstMoment(); Vec3 centroid();
		List<SideWet> sideWet(); List<Facet> facets(); List<FreeFragment> freeFragments();
	}
	record CellSnapshot(int cell, double volume, Vec3 firstMoment, Vec3 centroid,
	                    double admittedVolume, double rawMarkerVolume, double rawPressureVolume, double volumeErrorBound,
	                    List<SideWet> sideWet, List<Facet> facets, List<FreeFragment> freeFragments) implements Profile {
		CellSnapshot { sideWet=List.copyOf(sideWet); facets=List.copyOf(facets); freeFragments=List.copyOf(freeFragments); }
	}
	private record ComponentSnapshot(int cell, double volume, Vec3 firstMoment, Vec3 centroid,
	                                 List<SideWet> sideWet, List<Facet> facets, List<FreeFragment> freeFragments) implements Profile { }
	private final CellSnapshot[] cells;
	private final Profile[][] components;
	private final byte[] collisionMasks;
	private final List<SharedFace> sharedFaces;
	private final MicroSnapshot[][] microPieces;
	private final List<InteriorFace> interiorFaces;
	private final double cellVolume;
	private static final class MicroSnapshot {
		final VolumeMoments physical;
		final double[] first;
		final GasGeometryOverlap.HalfSpace wetPlane;
		final List<SideWet> faces;
		private double[] moments;
		MicroSnapshot(VolumeMoments physical, double[] first, GasGeometryOverlap.HalfSpace wetPlane, List<SideWet> faces) {
			this.physical = physical; this.first = first; this.wetPlane = wetPlane; this.faces = List.copyOf(faces);
		}
		synchronized double[] moments(int micro, double cellVolume) {
			if (moments == null) {
				var integrated = GasGeometryOverlap.normalizedSecondMoments(micro, wetPlane == null ? List.of() : List.of(wetPlane));
				var result = new double[10]; System.arraycopy(first, 0, result, 0, 4);
				var scale = PlicComponentGeometry.ratio(SignedDyadic.of(cellVolume), SignedDyadic.of(1));
				for (int i = 4; i < result.length; i++) result[i] = integrated[i].multiply(scale).toDouble();
				moments = result;
			}
			return moments;
		}
	}

	private LiquidPressureGeometry(CellSnapshot[] cells, Profile[][] components, byte[] masks, List<SharedFace> faces,
	                                MicroSnapshot[][] microPieces, List<InteriorFace> interiorFaces, double cellVolume) {
		this.cells=cells; this.components=components; collisionMasks=masks; sharedFaces=List.copyOf(faces);
		this.microPieces=microPieces; this.interiorFaces=List.copyOf(interiorFaces); this.cellVolume=cellVolume;
	}
	static LiquidPressureGeometry capture(FluidDomain domain) {
		return capture(domain, domain.pressurePhaseGeometry);
	}
	static LiquidPressureGeometry capture(FluidDomain domain, FlipPressurePhaseGeometry phase) {
		return capture(domain, phase, null);
	}
	static LiquidPressureGeometry capture(FluidDomain domain, FlipPressurePhaseGeometry phase, List<PhaseLiquidGeometryEdit> stock) {
		if (stock != null && stock.size() != domain.cellCount) throw new IllegalArgumentException("Liquid geometry source stock has the wrong shape");
		var cells = new CellSnapshot[domain.cellCount]; var components = new Profile[domain.cellCount][];
		var microPieces = new MicroSnapshot[domain.cellCount][];
		var faces = new ArrayList<SharedFace>();
		for (int cell=0;cell<cells.length;cell++) if(phase.liquidFractionExact(cell)>0)
			cells[cell]=captureCell(domain,phase,cell,faces,stock == null ? domain.markerGrid.exactCellMarkerVolume[cell] : stock.get(cell).volume().toDouble(),components,microPieces);
		var interior = new ArrayList<InteriorFace>();
		for (int cell=0;cell<cells.length;cell++) if(microPieces[cell]!=null) for(int micro=0;micro<8;micro++) {
			var piece=microPieces[cell][micro]; if(piece==null)continue;
			for(int axis=0;axis<3;axis++) if((micro&1<<axis)==0 && microPieces[cell][micro|1<<axis]!=null) {
				var wet=piece.faces.get(2*axis+1).moments();
				if(wet.area()>0)interior.add(new InteriorFace(cell,micro,micro|1<<axis,axis,wet));
			}
		}
		double h=domain.config.cellSize();
		return new LiquidPressureGeometry(cells,components,domain.geometry.cutCells.copyMicroMasks(),faces,microPieces,interior,h*h*h);
	}
	void forEachGasPiece(java.util.function.IntConsumer accept) {
		for (var cell : cells) if (cell != null) {
			for (var facet : cell.facets()) if (facet.kind() == FacetKind.GAS) accept.accept(facet.gasPiece());
			for (var fragment : cell.freeFragments()) accept.accept(fragment.gasPiece());
		}
	}
	CellSnapshot cellSnapshot(int cell) { return cells[cell]; }
	Profile componentSnapshot(int cell, int support) {
		requireSupport(cell,support);
		if(support==(~collisionMasks[cell]&255))return cells[cell];
		int[] masks = CellRemapMicroTopology.components(Byte.toUnsignedInt(collisionMasks[cell]));
		for (int i=0;i<masks.length;i++) if(masks[i]==support)
			return components[cell]==null ? cells[cell] : components[cell][i];
		VolumeMoments total=VolumeMoments.EMPTY;
		for(int micro=0;micro<8;micro++)if((support&1<<micro)!=0 && microPieces[cell]!=null && microPieces[cell][micro]!=null)
			total=total.add(microPieces[cell][micro].physical);
		if(!(total.volume()>0))return null;
		var profile=cells[cell];
		return new ComponentSnapshot(cell,total.volume(),total.firstMoment(),total.firstMoment().scale(1/total.volume()),
				profile.sideWet().stream().filter(side->(support&1<<(side.microKey()/6))!=0).toList(),
				profile.facets().stream().filter(facet->(support&1<<(facet.microKey()/6))!=0).toList(),
				profile.freeFragments().stream().filter(fragment->(support&1<<fragment.microKey())!=0).toList());
	}
	int wetMicroMask(int cell) {
		int mask=0; if(microPieces[cell]!=null)for(int micro=0;micro<8;micro++)if(microPieces[cell][micro]!=null)mask|=1<<micro;
		return mask;
	}
	List<InteriorFace> interiorFaces() { return interiorFaces; }
	List<SideWet> microFaces(int cell,int micro) {
		if(micro<0 || micro>=8)throw new IllegalArgumentException("Invalid liquid microbox");
		return microPieces[cell]==null || microPieces[cell][micro]==null ? List.of() : microPieces[cell][micro].faces;
	}
	double[] microMoments(int cell,int micro) {
		if(micro<0 || micro>=8)throw new IllegalArgumentException("Invalid liquid microbox");
		return microPieces[cell]==null || microPieces[cell][micro]==null ? new double[10] : microPieces[cell][micro].moments(micro,cellVolume).clone();
	}
	/** SI volume measure of 1, t_x, t_y, t_z and six quadratic terms, t=(x-cellCentre)/h. */
	double[] supportMoments(int cell,int support) {
		requireSupport(cell,support); var result=new double[10];
		for(int micro=0;micro<8;micro++)if((support&1<<micro)!=0 && microPieces[cell]!=null && microPieces[cell][micro]!=null) {
			var part=microPieces[cell][micro].moments(micro,cellVolume);
			for(int i=0;i<result.length;i++)result[i]+=part[i];
		}
		return result;
	}
	boolean needsMicroBlocks(int cell,int support) {
		requireSupport(cell,support); var wall=new boolean[3][3]; var open=new boolean[3][3];
		for(int micro=0;micro<8;micro++)if((support&1<<micro)!=0)for(var face:microFaces(cell,micro)) {
			if(!(face.moments().area()>0))continue;
			boolean closed=false;
			for(var facet:cells[cell].facets())if(facet.kind()==FacetKind.WALL && facet.microKey()==face.microKey()) { closed=true; break; }
			int plane=(micro>>>face.axis()&1)+(face.sign()>0?1:0);
			(closed?wall:open)[face.axis()][plane]=true;
			if(wall[face.axis()][plane] && open[face.axis()][plane])return true;
		}
		return false;
	}
	private void requireSupport(int cell,int support) {
		if(cell<0 || cell>=cells.length || support<=0 || support>255 || (support&collisionMasks[cell])!=0)
			throw new IllegalArgumentException("Velocity support must contain open microboxes");
	}
	List<SharedFace> sharedFaces() { return sharedFaces; }
	SurfaceMoments faceMoments(int minus, int plus, int axis) {
		SurfaceMoments result=SurfaceMoments.EMPTY;
		for(SharedFace face:sharedFaces)if(face.minusCell==minus && face.plusCell==plus && face.axis==axis)result=result.add(face.moments);
		return result;
	}

	private static CellSnapshot captureCell(FluidDomain d,FlipPressurePhaseGeometry plane,int cell,List<SharedFace> shared,double rawVolume,Profile[][] components,MicroSnapshot[][] microPieces) {
		var cut=d.geometry.cutCells;
		double h=d.config.cellSize(), h3=h*h*h;
		int[] index={cell%d.sizeX,cell/d.sizeX%d.sizeY,cell/(d.sizeX*d.sizeY)};
		int[] supports=CellRemapMicroTopology.components(cut.mask(cell));
		VolumeMoments[] pieces=supports.length>1 ? new VolumeMoments[8] : null;
		microPieces[cell]=new MicroSnapshot[8];
		double volume=0,mx=0,my=0,mz=0; int open=0;
		var sides=new ArrayList<SideWet>(); var facets=new ArrayList<Facet>(); var fragments=new ArrayList<FreeFragment>();
		for(int slot=0;slot<8;slot++) {
			int[] micro={slot&1,slot>>1&1,slot>>2&1}; if(!cut.microCellOpen(cell,micro[0],micro[1],micro[2]))continue;
			open++; double ox=.5*micro[0],oy=.5*micro[1],oz=.5*micro[2]; var integral=unitMicroMoments(plane,cell,slot);
			double boxVolume=integral[0]/8; volume+=boxVolume;
			mx+=(ox*integral[0]+.5*integral[1])/8;my+=(oy*integral[0]+.5*integral[2])/8;mz+=(oz*integral[0]+.5*integral[3])/8;
			VolumeMoments physical=boxVolume>0 ? new VolumeMoments(boxVolume*h3,new Vec3(
					h3*h*(index[0]*integral[0]+ox*integral[0]+.5*integral[1])/8,
					h3*h*(index[1]*integral[0]+oy*integral[0]+.5*integral[2])/8,
					h3*h*(index[2]*integral[0]+oz*integral[0]+.5*integral[3])/8)) : VolumeMoments.EMPTY;
			if(pieces!=null)pieces[slot]=physical;
			var microFaces=boxVolume>0 ? new ArrayList<SideWet>(6) : null;
			double[] areaNormal=new double[3]; double[][] normalFirst=new double[3][3];
			for(int axis=0;axis<3;axis++)for(int sign:new int[]{-1,1}) {
				double coordinate=.5*(micro[axis]+(sign>0?1:0)), tangent=.5*micro[(axis+1)%3],other=.5*micro[(axis+2)%3];
				SurfaceMoments wet=LiquidPressureMoments.integrate(plane.wetFacePolygon(cell,axis,coordinate,tangent,other,.5),axis,coordinate,cell,d);
				if (plane.liquidFractionExact(cell, slot) == 1)
					wet = LiquidPressureMoments.fullMicroFace(wet, axis, coordinate, tangent, other, cell, d);
				int key=slot*6+2*axis+(sign>0?1:0);
				SideWet side=wet.area()>0 || microFaces!=null ? new SideWet(axis,sign,key,wet) : null;
				if(microFaces!=null)microFaces.add(side);
				areaNormal[axis]-=sign*wet.area;
				for(int b=0;b<3;b++)normalFirst[axis][b]-=sign*wet.firstMoment.component(b);
				if(wet.area==0)continue;
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
				sides.add(side);
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
					if (plane.liquidFractionExact(cell, slot) == 1 && plane.liquidFractionExact(peer,
							peerMicro[0] | peerMicro[1] << 1 | peerMicro[2] << 2) == 1)
						both = LiquidPressureMoments.fullMicroFace(both, axis, coordinate, tangent, other, cell, d);
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
			if(microFaces!=null)microPieces[cell][slot]=new MicroSnapshot(physical,new double[]{boxVolume*h3,
					h3*((ox-.5)*integral[0]+.5*integral[1])/8,
					h3*((oy-.5)*integral[0]+.5*integral[2])/8,
					h3*((oz-.5)*integral[0]+.5*integral[3])/8},wetHalfSpace(plane,cell,slot),microFaces);
		}
		double unitVolume=volume;volume*=h3;
		Vec3 first=new Vec3(h3*h*(index[0]*unitVolume+mx),h3*h*(index[1]*unitVolume+my),h3*h*(index[2]*unitVolume+mz));
		double admitted=plane.geometricLiquidVolume(cell,cut)*h3;
		// Absolute quadrature allowance covers the clipped-cube CDF, transformed moments and eight-box reduction.
		double error=(128.0*open+64)*Math.ulp(1.0)*h3+Math.ulp(admitted)+Math.ulp(volume);
		if(!(volume>0 && Double.isFinite(volume)) || Math.abs(volume-admitted)>error)throw new IllegalStateException("PLIC pressure volume integration disagrees with admitted geometry at cell "+cell
				+": integrated="+volume+", admitted="+admitted+", raw="+rawVolume*h3);
		if(pieces!=null) {
			components[cell]=new Profile[supports.length];
			for(int i=0;i<supports.length;i++) {
				int support=supports[i]; VolumeMoments total=VolumeMoments.EMPTY;
				for(int micro=0;micro<8;micro++)if((support&1<<micro)!=0)total=total.add(pieces[micro]);
				if(!(total.volume()>0))continue;
				components[cell][i]=new ComponentSnapshot(cell,total.volume(),total.firstMoment(),total.firstMoment().scale(1/total.volume()),
						sides.stream().filter(side->(support&1<<(side.microKey()/6))!=0).toList(),
						facets.stream().filter(facet->(support&1<<(facet.microKey()/6))!=0).toList(),
						fragments.stream().filter(fragment->(support&1<<fragment.microKey())!=0).toList());
			}
		}
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
	/** Exact normalized coverage moments through degree two, about the whole-cell centre. */
	static ExactRational[] pieceSecondMoments(FluidDomain domain, FlipPressurePhaseGeometry phase, int cell, int micro) {
		if (cell < 0 || cell >= domain.cellCount || micro < 0 || micro >= 8) throw new IllegalArgumentException("Invalid liquid geometry piece");
		if ((domain.geometry.cutCells.mask(cell) & 1 << micro) != 0 || phase.liquidFractionExact(cell, micro) == 0) {
			var empty = new ExactRational[10]; java.util.Arrays.fill(empty, ExactRational.ZERO); return empty;
		}
		if (phase.liquidFractionExact(cell, micro) >= 1) return GasGeometryOverlap.normalizedSecondMoments(micro, List.of());
		return GasGeometryOverlap.normalizedSecondMoments(micro, List.of(wetHalfSpace(phase,cell,micro)));
	}
	private static GasGeometryOverlap.HalfSpace wetHalfSpace(FlipPressurePhaseGeometry phase,int cell,int micro) {
		return phase.liquidFractionExact(cell,micro)>=1 ? null : new GasGeometryOverlap.HalfSpace(
				SignedDyadic.of(phase.planeNormal(cell,micro,0)).negate(),SignedDyadic.of(phase.planeNormal(cell,micro,1)).negate(),
				SignedDyadic.of(phase.planeNormal(cell,micro,2)).negate(),SignedDyadic.of(phase.planeOffset(cell,micro)).negate());
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
