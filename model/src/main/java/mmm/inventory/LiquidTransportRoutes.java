package mmm.inventory;

import mmm.units.SignedDyadic;
import java.util.Arrays;

/** Owned flat columns for actual donor outflow; unused stock stays with its owner. */
public final class LiquidTransportRoutes {
    private final LiquidCellInventory.Stock[] sources;
    private final int[] starts, faces, cells, supports;
    private final SignedDyadic[] volumes;
    private final int sourceCount;

    private LiquidTransportRoutes(Builder builder) {
        sources=builder.sources;starts=builder.starts;faces=builder.faces;
        cells=builder.cells;supports=builder.supports;volumes=builder.volumes;sourceCount=builder.sourceCount;
    }
    public static Builder builder(int expectedSources,int expectedRoutes){return new Builder(expectedSources,expectedRoutes);}
    public int sourceCount(){return sourceCount;}
    public LiquidCellInventory.Stock source(int donor){return sources[donor];}
    public int routeStart(int donor){return starts[donor];}
    public int routeEnd(int donor){return starts[donor+1];}
    public int face(int route){return faces[route];}
    public int targetCell(int route){return cells[route];}
    public int supportMask(int route){return supports[route];}
    public SignedDyadic volume(int route){return volumes[route];}

    /** Building transfers ownership of the columns; a published batch is immutable. */
    public static final class Builder {
        private LiquidCellInventory.Stock[] sources;
        private int[] starts,faces,cells,supports;
        private SignedDyadic[] volumes;
        private int sourceCount,size;
        private boolean built;
        private Builder(int expectedSources,int expectedRoutes){
            if(expectedSources<0||expectedRoutes<0)throw new IllegalArgumentException("Negative transport capacity");
            sources=new LiquidCellInventory.Stock[expectedSources];starts=new int[expectedSources+1];
            faces=new int[expectedRoutes];cells=new int[expectedRoutes];supports=new int[expectedRoutes];volumes=new SignedDyadic[expectedRoutes];
        }
        public Builder source(LiquidCellInventory.Stock source){
            if(built||source==null||sourceCount>0&&starts[sourceCount-1]==size)
                throw new IllegalStateException("Liquid donor requires unpublished outflow");
            if(sourceCount==sources.length){int n=Math.max(4,2*sourceCount);sources=Arrays.copyOf(sources,n);starts=Arrays.copyOf(starts,n+1);}
            starts[sourceCount]=size;sources[sourceCount++]=source;return this;
        }
        public Builder route(int face,int targetCell,int supportMask,SignedDyadic volume){
            if(built||sourceCount==0)throw new IllegalStateException("Liquid route requires an unpublished donor");
            var key=sources[sourceCount-1].key();
            if(face<0||targetCell<0||supportMask<=0||supportMask>255||volume==null||volume.signum()<=0
                    ||targetCell==key.cell())throw new IllegalArgumentException("Invalid liquid transport route");
            if(size==faces.length){int n=Math.max(16,2*size);faces=Arrays.copyOf(faces,n);cells=Arrays.copyOf(cells,n);supports=Arrays.copyOf(supports,n);volumes=Arrays.copyOf(volumes,n);}
            faces[size]=face;cells[size]=targetCell;supports[size]=supportMask;volumes[size++]=volume;return this;
        }
        public LiquidTransportRoutes build(){
            if(built||sourceCount>0&&starts[sourceCount-1]==size)throw new IllegalStateException("Liquid routes are already published or incomplete");
            starts[sourceCount]=size;built=true;return new LiquidTransportRoutes(this);
        }
    }
}
