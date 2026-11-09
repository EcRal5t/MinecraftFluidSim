package mmm.fluid;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import mmm.fluid.FluidSurfaceInput.Piece;
import mmm.fluid.FluidSurfaceInput.Plane;
import mmm.inventory.LiquidCellInventory;

/** Projects one committed inventory and its matching PLIC onto bounded display pieces. */
public final class FluidSurfaceProjection {
  static FluidSurfaceInput capture(
      int sx,
      int sy,
      int sz,
      float h,
      FluidMaterialRegistry registry,
      LiquidCellInventory.Snapshot inventory,
      FixedGeometrySnapshot fixed,
      FlipPressurePhaseGeometry phase) {
    var pieces = new ArrayList<Piece>();
    for (int cell = 0; cell < inventory.cellCount(); cell++) {
      var row = inventory.rows().get(cell);
      if (row.isEmpty()) continue;
      for (int support : LiquidCellInventory.connectedSupports(fixed.mask(cell))) {
        var stocks = new ArrayList<LiquidCellInventory.Stock>();
        double total = 0;
        for (var stock : row)
          if (stock.key().supportMask() == support) {
            stocks.add(stock);
            total += stock.volumeFraction();
          }
        if (stocks.isEmpty()) continue;
        stocks.sort(
            Comparator.<LiquidCellInventory.Stock>comparingDouble(
                    stock -> -registry.material(stock.key().material()).referenceDensity())
                .thenComparingInt(stock -> stock.key().material()));
        var base = new ArrayList<Piece>();
        var polyhedra = new ArrayList<List<SurfacePolyhedron.Face>>();
        double geometricVolume = 0;
        for (int micro = 0; micro < 8; micro++)
          if ((support & 1 << micro) != 0) {
            var plane = phase.componentPlane(cell, micro);
            if (plane == null || plane.fraction() <= 0) continue;
            List<Plane> clips =
                plane.fraction() >= 1
                    ? List.of()
                    : List.of(new Plane(plane.nx(), plane.ny(), plane.nz(), plane.offset()));
            var piece = new Piece(cell, micro, 0, 0, clips);
            if (stocks.size() == 1 && clips.isEmpty()) {
              var stock = stocks.getFirst();
              pieces.add(
                  new Piece(
                      cell,
                      micro,
                      stock.key().material(),
                      registry.phaseHandle(stock.key().material()),
                      clips));
              continue;
            }
            var polyhedron = SurfacePolyhedron.build(piece);
            double volume = SurfacePolyhedron.volume(polyhedron);
            if (volume > 0) {
              if (stocks.size() == 1) {
                var stock = stocks.getFirst();
                pieces.add(
                    new Piece(
                        cell,
                        micro,
                        stock.key().material(),
                        registry.phaseHandle(stock.key().material()),
                        clips));
                continue;
              }
              base.add(piece);
              polyhedra.add(polyhedron);
              geometricVolume += volume;
            }
          }
        double lower = 0, cumulative = 0;
        for (int at = 0; at < stocks.size(); at++) {
          var stock = stocks.get(at);
          cumulative += stock.volumeFraction();
          double upper =
              at == stocks.size() - 1 ? 1 : height(polyhedra, geometricVolume * cumulative / total);
          for (var original : base) {
            var clips = new ArrayList<>(original.clips());
            if (lower > 0) clips.add(new Plane(0, -1, 0, -lower));
            if (upper < 1) clips.add(new Plane(0, 1, 0, upper));
            var piece =
                new Piece(
                    cell,
                    original.micro(),
                    stock.key().material(),
                    registry.phaseHandle(stock.key().material()),
                    clips);
            if (SurfacePolyhedron.volume(SurfacePolyhedron.build(piece)) > 0) pieces.add(piece);
          }
          lower = upper;
        }
      }
    }
    return new FluidSurfaceInput(fixed.grid(), h, pieces);
  }

  /** Display-only projection of float device fields; these values never become inventory. */
  public static FluidMaterialRenderSnapshot fromDeviceFields(int sx,int sy,int sz,float h,
      FluidMaterialRegistry registry,float[][] materialVolume,float[] planes,float[] microVolume,byte[] masks) {
    int cells=sx*sy*sz;
    if(planes.length!=4*cells||microVolume.length!=8*cells||masks.length!=cells||materialVolume.length!=registry.size())
      throw new IllegalArgumentException("Device display dimensions differ");
    byte[][] materialFractions=new byte[registry.size()][cells],phaseFractions=new byte[registry.phaseCount()][cells];
    var order=new ArrayList<Integer>();for(int m=0;m<registry.size();m++)order.add(m);
    order.sort(Comparator.<Integer>comparingDouble(m->-registry.material(m).referenceDensity()).thenComparingInt(Integer::intValue));
    int layers=0;for(int c=0;c<cells;c++)for(int m=0;m<registry.size();m++)if(materialVolume[m][c]>0)layers++;
    var data=java.nio.ByteBuffer.allocate(8+20*cells+16*layers).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    data.putInt(cells).putInt(layers);int row=0;
    for(int cell=0;cell<cells;cell++) {
      double total=0;double[] amounts=new double[registry.size()],phaseAmounts=new double[registry.phaseCount()];
      for(int m=0;m<amounts.length;m++){amounts[m]=materialVolume[m][cell];total+=amounts[m];phaseAmounts[registry.phaseHandle(m)]+=amounts[m];}
      for(int k=0;k<4;k++)data.putFloat(8+20*cell+4*k,planes[4*cell+k]);
      if(total>0) {
        quantize(amounts,total,materialFractions,cell);quantize(phaseAmounts,total,phaseFractions,cell);
        int open=0;double geometricVolume=0;
        for(int micro=0;micro<8;micro++)if((Byte.toUnsignedInt(masks[cell])&(1<<micro))==0&&microVolume[8*cell+micro]>0){open|=1<<micro;geometricVolume+=slabVolume(planes,cell,micro,0,1);}
        double lower=0,cumulative=0;
        for(int material:order){if(amounts[material]<=0)continue;cumulative+=amounts[material];
          double upper=1;
          if(cumulative<total){double lo=lower,hi=1,target=geometricVolume*cumulative/total;
            for(int iteration=0;iteration<24;iteration++){double mid=.5*(lo+hi),volume=0;for(int micro=0;micro<8;micro++)if((open&(1<<micro))!=0)volume+=slabVolume(planes,cell,micro,0,mid);if(volume<target)lo=mid;else hi=mid;}upper=.5*(lo+hi);}
          int mask=0;for(int micro=0;micro<8;micro++)if((open&(1<<micro))!=0&&slabVolume(planes,cell,micro,lower,upper)>0)mask|=1<<micro;
          if(mask!=0){int at=8+20*cells+16*row++;data.putShort(at,(short)material).putShort(at+2,(short)registry.phaseHandle(material)).putFloat(at+4,(float)lower).putFloat(at+8,(float)upper).putInt(at+12,mask);}
          lower=upper;
        }
      }
      data.putInt(8+20*cell+16,row);
    }
    data.putInt(4,row);byte[] encoded=java.util.Arrays.copyOf(data.array(),8+20*cells+16*row);
    return FluidMaterialRenderSnapshot.fromDisplayFields(sx,sy,sz,h,registry.materials(),registry.phaseGroups(),
        materialFractions,phaseFractions,new FluidSurfaceInput(sx,sy,sz,h,new CompactFluidSurface(encoded)));
  }
  private static double slabVolume(float[] plane,int cell,int micro,double lo,double hi){
    double y=.5*(micro>>1&1),low=Math.max(y,lo),high=Math.min(y+.5,hi);if(high<=low)return 0;
    double nx=plane[4*cell],ny=plane[4*cell+1],nz=plane[4*cell+2];
    double offset=plane[4*cell+3]-nx*.5*(micro&1)-ny*low-nz*.5*(micro>>2&1);
    return MicroBoxPlaneVolume.box(new double[]{nx*.5,ny*(high-low),nz*.5},offset,0,1)*.25*(high-low);
  }
  private static void quantize(double[] values,double total,byte[][] target,int cell){
    int assigned=0;double[] remainder=new double[values.length];
    for(int i=0;i<values.length;i++){double scaled=255*values[i]/total;int floor=(int)Math.floor(scaled);target[i][cell]=(byte)floor;assigned+=floor;remainder[i]=scaled-floor;}
    while(assigned<255){int best=0;for(int i=1;i<values.length;i++)if(remainder[i]>remainder[best])best=i;target[best][cell]++;remainder[best]=-1;assigned++;}
  }

  private static double height(List<List<SurfacePolyhedron.Face>> polyhedra, double target) {
    double lower = 0, upper = 1;
    for (int iteration = 0; iteration < 24; iteration++) {
      double middle = .5 * (lower + upper), volume = 0;
      var plane = new Plane(0, 1, 0, middle);
      for (var polyhedron : polyhedra)
        volume += SurfacePolyhedron.volume(SurfacePolyhedron.clip(polyhedron, plane));
      if (volume < target) lower = middle;
      else upper = middle;
    }
    return .5 * (lower + upper);
  }
}
