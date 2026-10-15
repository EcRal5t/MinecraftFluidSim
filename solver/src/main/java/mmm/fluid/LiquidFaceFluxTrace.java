package mmm.fluid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import mmm.units.SignedDyadic;

/** Common affine numerical traces on shared wet regions and each donor's remaining open columns. */
final class LiquidFaceFluxTrace {
	private LiquidFaceFluxTrace() { }
	record Piece(int velocityBlock,int blockSupport,int support,double[] q,List<GasGeometryOverlap.HalfSpace> clip) { }
	private record Key(int block,int blockSupport,double a,double b,double c,List<GasGeometryOverlap.HalfSpace> clip) { }
	static Piece[][] capture(FluidDomain domain,ConservativeLiquidFlux faces,CellAffineVelocityField field) {
		var result = new Piece[2*faces.physicalFaceCount][];
		var phase = domain.pressurePhaseGeometry;
		for (int f = 0; f < faces.physicalFaceCount; f++) {
			int left = faces.left[f], right = faces.right[f], axis = faces.axis[f];
			var l = field.hasCell(left) ? field.faceTraces(left,axis,true,domain.sizeX,domain.sizeY) : new CellAffineVelocityField.FaceTrace[0];
			var r = field.hasCell(right) ? field.faceTraces(right,axis,false,domain.sizeX,domain.sizeY) : new CellAffineVelocityField.FaceTrace[0];
			var leftPieces = new LinkedHashMap<Key,Integer>(); var rightPieces = new LinkedHashMap<Key,Integer>();
			for (int column = 0; column < 4; column++) {
				int low = (column&1)<<((axis+1)%3) | (column>>1)<<((axis+2)%3), high = low | 1<<axis;
				if ((domain.geometry.cutCells.mask(left)&1<<high)!=0 || (domain.geometry.cutCells.mask(right)&1<<low)!=0) continue;
				var a = at(l,high); var b = at(r,low);
				if (a == null && b == null) continue;
				int support = (1<<low)|(1<<high);
				if (a == null || b == null || Arrays.equals(a.q(),b.q())) {
					add(leftPieces,a,support,a == null ? null : a.q(),List.of());
					add(rightPieces,b,support,b == null ? null : b.q(),List.of()); continue;
				}
				var shared = new ArrayList<List<GasGeometryOverlap.HalfSpace>>();
				for (var first : projected(LiquidShapeGeometry.faceWetSupports(phase,left,high),axis,1,high))
					for (var second : projected(LiquidShapeGeometry.faceWetSupports(phase,right,low),axis,0,high)) {
						var clip = new ArrayList<GasGeometryOverlap.HalfSpace>(first); clip.addAll(second);
						var reduced=reduce(high,clip); if (reduced!=null && positive(high,reduced)) shared.add(reduced);
					}
				var average = new double[3]; for (int i = 0; i < 3; i++) average[i] = .5*a.q()[i]+.5*b.q()[i];
				for (var clip : shared) { add(leftPieces,a,support,average,clip); add(rightPieces,b,support,average,clip); }
				// Keep the donor's actual volume sweep when a front reaches an initially dry face during dt.
				var exclusive = new ArrayList<List<GasGeometryOverlap.HalfSpace>>(); exclusive.add(List.of());
				for (var wet : shared) {
					var remaining = new ArrayList<List<GasGeometryOverlap.HalfSpace>>();
					for (var part : exclusive) {
						var prefix = new ArrayList<GasGeometryOverlap.HalfSpace>(part);
						for (var plane : wet) {
							var outside = new ArrayList<GasGeometryOverlap.HalfSpace>(prefix); outside.add(negate(plane));
							var reduced=reduce(high,outside); if (reduced!=null && positive(high,reduced)) remaining.add(reduced);
							prefix.add(plane);
						}
					}
					exclusive = remaining;
				}
				for (var clip : exclusive) { add(leftPieces,a,support,a.q(),clip); add(rightPieces,b,support,b.q(),clip); }
			}
			result[f] = finish(leftPieces); result[f+faces.physicalFaceCount] = finish(rightPieces);
		}
		return result;
	}
	private static CellAffineVelocityField.FaceTrace at(CellAffineVelocityField.FaceTrace[] traces,int micro) {
		for (var trace : traces) if ((trace.support()&1<<micro)!=0) return trace;
		return null;
	}
	private static void add(LinkedHashMap<Key,Integer> pieces,CellAffineVelocityField.FaceTrace source,int column,double[] q,List<GasGeometryOverlap.HalfSpace> clip) {
		if (source == null) return;
		for (double value : q) if (!Double.isFinite(value)) throw new FluidDomain.PressureSolveBlockedException("Nonfinite common liquid normal trace");
		var key = new Key(source.velocityBlock(),source.support(),q[0],q[1],q[2],clip);
		pieces.merge(key,column&source.support(),(first,second)->first|second);
	}
	private static Piece[] finish(LinkedHashMap<Key,Integer> pieces) {
		if (pieces.isEmpty()) return null;
		var result = new Piece[pieces.size()]; int i = 0;
		for (var entry : pieces.entrySet()) { var key=entry.getKey(); result[i++]=new Piece(key.block,key.blockSupport,entry.getValue(),new double[]{key.a,key.b,key.c},key.clip); }
		return result;
	}
	private static List<List<GasGeometryOverlap.HalfSpace>> projected(List<List<GasGeometryOverlap.HalfSpace>> supports,int axis,int side,int micro) {
		var result = new ArrayList<List<GasGeometryOverlap.HalfSpace>>();
		for (var support : supports) {
			var part = new ArrayList<GasGeometryOverlap.HalfSpace>(); boolean empty = false;
			for (var plane : support) {
				var n = new SignedDyadic[]{plane.nx(),plane.ny(),plane.nz()}; var threshold = plane.threshold().subtract(n[axis].multiply(SignedDyadic.of(side))); n[axis]=SignedDyadic.ZERO;
				if (n[0].signum()==0&&n[1].signum()==0&&n[2].signum()==0) { if (threshold.signum()>0) { empty=true; break; } }
				else part.add(new GasGeometryOverlap.HalfSpace(n[0],n[1],n[2],threshold));
			}
			if (!empty) { var reduced=reduce(micro,part); if(reduced!=null)result.add(reduced); }
		}
		return result;
	}
	/** Drop inactive and same-direction redundant planes before the bounded convex integration. */
	static List<GasGeometryOverlap.HalfSpace> reduce(int micro,List<GasGeometryOverlap.HalfSpace> planes) {
		var result=new ArrayList<GasGeometryOverlap.HalfSpace>();
		for(var plane:planes) {
			var n=new SignedDyadic[]{plane.nx(),plane.ny(),plane.nz()}; var lo=plane.threshold().negate();
			for(int a=0;a<3;a++)lo=lo.add(n[a].multiply(SignedDyadic.of(micro>>a&1,-1)));
			var hi=lo; int pivot=-1;
			for(int a=0;a<3;a++) { var spread=n[a].multiply(SignedDyadic.of(1,-1)); if(n[a].signum()<0)lo=lo.add(spread);else hi=hi.add(spread); if(pivot<0 && n[a].signum()!=0)pivot=a; }
			if(hi.signum()<=0 && lo.signum()<0)return null;
			if(lo.signum()>=0)continue;
			boolean added=false;
			for(int i=0;i<result.size();i++) {
				var previous=result.get(i); var m=new SignedDyadic[]{previous.nx(),previous.ny(),previous.nz()};
				if(m[pivot].signum()!=n[pivot].signum())continue;
				boolean parallel=true; for(int a=0;a<3;a++)parallel&=n[a].multiply(m[pivot]).equals(m[a].multiply(n[pivot]));
				if(!parallel)continue;
				var scale=n[pivot].signum()>0?n[pivot]:n[pivot].negate(); var oldScale=m[pivot].signum()>0?m[pivot]:m[pivot].negate();
				if(plane.threshold().multiply(oldScale).compareTo(previous.threshold().multiply(scale))>0)result.set(i,plane);
				added=true;break;
			}
			if(!added)result.add(plane);
		}
		return List.copyOf(result);
	}
	private static boolean positive(int micro,List<GasGeometryOverlap.HalfSpace> clip) { return clip.isEmpty() || GasGeometryOverlap.normalizedVolume(micro,clip).signum()>0; }
	private static GasGeometryOverlap.HalfSpace negate(GasGeometryOverlap.HalfSpace plane) { return new GasGeometryOverlap.HalfSpace(plane.nx().negate(),plane.ny().negate(),plane.nz().negate(),plane.threshold().negate()); }
}
