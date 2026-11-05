package mmm.fluid;

/** One phase plane shared by the fixed open microboxes of a cell. */
final class PlicComponentGeometry {
	record Plane(int support,double fraction,double porosity,float nx,float ny,float nz,PlicPlaneOffset.Result bracket) {
		float normal(int axis) { return axis==0?nx:axis==1?ny:nz; }
		double offset() { return bracket==null?0:bracket.offset(); }
	}
	private final Plane[] byMicro=new Plane[8];
	static PlicComponentGeometry single(int support,double fraction,double porosity,float nx,float ny,float nz,PlicPlaneOffset.Result bracket) {
		var geometry=new PlicComponentGeometry();var plane=new Plane(support,fraction,porosity,nx,ny,nz,bracket);
		for(int bit=0;bit<8;bit++)if((support&1<<bit)!=0)geometry.byMicro[bit]=plane;
		return geometry;
	}
	Plane plane(int micro) { return byMicro[micro]; }
}
