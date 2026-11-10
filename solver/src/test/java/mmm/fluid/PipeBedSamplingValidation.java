package mmm.fluid;

import mmm.granular.SolidVolume;

/** Bed surfaces inside quarter-block pipe cavities sample open, not wall, microboxes. */
public final class PipeBedSamplingValidation {
    private PipeBedSamplingValidation() { }
    public static void run() {
        for(int y=0;y<2;y++)for(int z=0;z<2;z++) {
            int open=(1<<(2*y+4*z))|(1<<(1+2*y+4*z));
            var cut=new FlipCutCellGeometry(1,1,1);cut.replaceMask(0,(~open)&255);
            var geometry=new GranularCollisionGeometry(1,1,1,.5f,cut,null);
            long bulk=SolidVolume.SVQ_PER_CELL/8;
            double top=geometry.bedTop(0,bulk),expected=(y*.5+.25)*.5;
            if(Math.abs(top-expected)>1e-12)throw new AssertionError("Pipe bed height differs");
            int sample=GranularBedShear.surfaceMicro(cut,0,top/.5);
            if(sample<0 || !cut.microCellOpen(0,sample&1,y,sample>>1))throw new AssertionError("Pipe bed sampled a wall");
            float px=(float)(((sample&1)+.5)*.25),pz=(float)(((sample>>1)+.5)*.25);
            float py=(float)(top-1e-6);
            if(cut.pointInsideSolid(px,py,pz,.5f))throw new AssertionError("Pipe bed sample intersects wall");
            if(!geometry.bedGeometryFits(0,SolidVolume.SVQ_PER_CELL/4)
                    ||geometry.bedGeometryFits(0,SolidVolume.SVQ_PER_CELL/4+1))throw new AssertionError("Pipe bed capacity differs");
        }
    }
}
