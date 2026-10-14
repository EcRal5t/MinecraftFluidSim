package mmm.fluid;

import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Arrays;

/** Certifies that lightly wetted changing full cells retain every old piece on one fixed gas backbone. */
final class GasPhaseSourceBackbone {
	private static final ExactRational WET_LIMIT = ExactRational.ONE.divide(16);
	private static final ExactRational OFFSET_VOLUME_ERROR = ExactRational.ONE.divide(1L << 50);
	private record Face(int axis, int side, int micro) { }
	private GasPhaseSourceBackbone() { }

	static boolean retainsSource(GasRuntimeGeometry source, int node, boolean[] variable) {
		int[] parent=new int[Math.multiplyExact(8,variable.length)]; Arrays.fill(parent,-1);
		boolean[] changingSource=new boolean[variable.length]; int anchor=-1;
		for(var piece:source.geometry().pieces()) if(source.nodeOfPiece(piece.id())==node) {
			if(variable[piece.cell()]) changingSource[piece.cell()]=true;
			else { parent[piece.id()]=piece.id(); if(anchor<0) anchor=piece.id(); }
		}
		if(anchor<0) return false;
		for(var port:source.geometry().ports()) if(port.fullCutConnection() && parent[port.first()]>=0 && parent[port.second()]>=0)
			join(parent,port.first(),port.second());
		int backbone=root(parent,anchor);
		for(var piece:source.geometry().pieces()) if(parent[piece.id()]>=0 && root(parent,piece.id())!=backbone) return false;
		for(int cell=0;cell<changingSource.length;cell++) if(changingSource[cell]) {
			if(source.geometry().collisionMask(cell)!=0 || source.geometry().poreVolume(cell).signum()<=0) return false;
			for(int micro=0;micro<8;micro++) if(source.geometry().piece(8*cell+micro)==null || source.nodeOfPiece(8*cell+micro)!=node) return false;
			var bracket=source.geometry().piece(8*cell).offsetBracket();
			var target=PlicComponentGeometry.ratio(source.liquidStock().get(cell).volume(),source.geometry().poreVolume(cell));
			if(bracket==null && target.signum()!=0) return false;
			// For any nonzero binary32 normal, extrema lie within +/-3*nMax. Adjacent
			// binary64 offsets change whole-cube wet volume by at most ulp(3*nMax)/nMax <= 2^-50.
			var futureUpper=target.add(OFFSET_VOLUME_ERROR);
			var oldUpper=bracket==null?ExactRational.ZERO:bracket.upperVolume();
			// Two wet sets below 1/16 leave positive overlap in every 1/8 microbox.
			// Covering an internal microface centre requires half its symmetric 1/4 box.
			// Covering both edge-sharing outer patches requires their 1/16 convex hull.
			if(oldUpper.compareTo(WET_LIMIT)>=0 || futureUpper.compareTo(WET_LIMIT)>=0
					|| !hasDryEdge(source,cell,variable,parent,backbone)) return false;
		}
		return true;
	}

	private static boolean hasDryEdge(GasRuntimeGeometry source, int cell, boolean[] variable, int[] parent, int backbone) {
		var geometry=source.geometry(); int[] sizes={geometry.sizeX(),geometry.sizeY(),geometry.sizeZ()}, strides={1,sizes[0],sizes[0]*sizes[1]};
		int[] position={cell%sizes[0],cell/sizes[0]%sizes[1],cell/strides[2]};
		var faces=new ArrayList<Face>();
		for(int axis=0;axis<3;axis++) for(int side=0;side<2;side++) {
			int sign=2*side-1;
			if(position[axis]+sign<0 || position[axis]+sign>=sizes[axis]) continue;
			int peer=cell+sign*strides[axis]; if(variable[peer] || !fullCutFace(geometry,peer,axis,1-side)) continue;
			for(int micro=0;micro<8;micro++) if((micro>>>axis&1)==side) {
				int piece=8*peer+(micro^1<<axis);
				if(parent[piece]>=0 && root(parent,piece)==backbone && dryFace(geometry.piece(piece),axis,1-side)) {
					var face=new Face(axis,side,micro);
					for(var other:faces) if(other.axis()!=axis && (micro>>>other.axis()&1)==other.side()
							&& (other.micro()>>>axis&1)==side && (micro>>>(3-axis-other.axis())&1)==(other.micro()>>>(3-axis-other.axis())&1)) return true;
					faces.add(face);
				}
			}
		}
		return false;
	}

	private static boolean dryFace(GasComplementGeometry.Piece piece, int axis, int side) {
		var plane=piece.dryPlane(); if(plane==null) return true;
		SignedDyadic[] normal={plane.nx(),plane.ny(),plane.nz()}; int tangent=(axis+1)%3, other=(axis+2)%3;
		for(int a=0;a<2;a++) for(int b=0;b<2;b++) {
			SignedDyadic value=normal[axis].multiply(SignedDyadic.of(side,0))
					.add(normal[tangent].multiply(SignedDyadic.of((piece.micro()>>>tangent&1)+a,-1)))
					.add(normal[other].multiply(SignedDyadic.of((piece.micro()>>>other&1)+b,-1)));
			if(value.compareTo(plane.threshold())<0) return false;
		}
		return true;
	}

	private static boolean fullCutFace(GasComplementGeometry geometry, int cell, int axis, int side) {
		for(int micro=0;micro<8;micro++) if((micro>>>axis&1)==side && (geometry.collisionMask(cell)&1<<micro)!=0) return false;
		return true;
	}
	private static int root(int[] parent,int piece) {
		int result=piece; while(parent[result]!=result) result=parent[result];
		while(parent[piece]!=piece) { int next=parent[piece]; parent[piece]=result; piece=next; } return result;
	}
	private static void join(int[] parent,int first,int second) {
		int a=root(parent,first),b=root(parent,second); if(a!=b) parent[Math.max(a,b)]=Math.min(a,b);
	}
}
