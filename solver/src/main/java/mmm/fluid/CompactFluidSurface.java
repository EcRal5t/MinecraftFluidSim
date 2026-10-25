package mmm.fluid;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.RandomAccess;

/** Cell planes and material slabs. Convex pieces are generated only when the client meshes them. */
public final class CompactFluidSurface extends AbstractList<FluidSurfaceInput.Piece> implements RandomAccess {
  private final byte[] encoded;
  private final ByteBuffer data;
  private final int cells, layers, layerBase;
  private final int[] prefix, layerCells;
  public CompactFluidSurface(byte[] encoded) {
    this.encoded=encoded.clone();data=ByteBuffer.wrap(this.encoded).order(ByteOrder.LITTLE_ENDIAN);
    if(encoded.length<8)throw new IllegalArgumentException("Missing compact surface header");
    cells=data.getInt(0);layers=data.getInt(4);layerBase=Math.toIntExact(8L+20L*cells);
    if(cells<0||layers<0||8L+20L*cells+16L*layers!=encoded.length)throw new IllegalArgumentException("Invalid compact surface layout");
    prefix=new int[layers+1];layerCells=new int[layers];int previous=0;
    for(int c=0;c<cells;c++) {
      int end=data.getInt(8+20*c+16);
      if(end<previous||end>layers)throw new IllegalArgumentException("Invalid surface layer offsets");
      for(int k=0;k<4;k++)if(!Float.isFinite(data.getFloat(8+20*c+4*k)))throw new IllegalArgumentException("Nonfinite surface plane");
      for(int row=previous;row<end;row++)layerCells[row]=c;
      previous=end;
    }
    if(previous!=layers)throw new IllegalArgumentException("Surface layer count differs");
    for(int row=0;row<layers;row++) {
      int at=layerBase+16*row,mask=data.getInt(at+12);
      float lo=data.getFloat(at+4),hi=data.getFloat(at+8);
      if(mask<=0||mask>255||!(lo>=0&&hi<=1&&lo<hi))throw new IllegalArgumentException("Invalid surface slab");
      prefix[row+1]=Math.addExact(prefix[row],Integer.bitCount(mask));
    }
  }
  public byte[] encoded(){return encoded.clone();}
  public int encodedBytes(){return encoded.length;}
  public int cells(){return cells;}
  public void validatePalette(int materials,int phases,java.util.function.IntUnaryOperator phaseOfMaterial){
    for(int r=0;r<layers;r++) {
      int at=layerBase+16*r,m=Short.toUnsignedInt(data.getShort(at)),p=Short.toUnsignedInt(data.getShort(at+2));
      if(m>=materials||p>=phases||phaseOfMaterial!=null&&phaseOfMaterial.applyAsInt(m)!=p)throw new IllegalArgumentException("Compact surface palette differs");
    }
  }
  @Override public int size(){return prefix[layers];}
  @Override public FluidSurfaceInput.Piece get(int index){
    java.util.Objects.checkIndex(index,size());int low=0,high=layers;
    while(low<high){int middle=(low+high)>>>1;if(prefix[middle+1]<=index)low=middle+1;else high=middle;}
    int row=low,at=layerBase+16*row,cell=layerCells[row],mask=data.getInt(at+12),micro=0,ordinal=index-prefix[row];
    while(true){micro=Integer.numberOfTrailingZeros(mask);if(ordinal--==0)break;mask&=mask-1;}
    double nx=data.getFloat(8+20*cell),ny=data.getFloat(12+20*cell),nz=data.getFloat(16+20*cell),d=data.getFloat(20+20*cell);
    double lower=data.getFloat(at+4),upper=data.getFloat(at+8);
    var clips=new ArrayList<FluidSurfaceInput.Plane>(3);
    double maximum=nx*.5*((micro&1)+(nx>0?1:0))+ny*.5*((micro>>1&1)+(ny>0?1:0))+nz*.5*((micro>>2&1)+(nz>0?1:0));
    if(maximum>d)clips.add(new FluidSurfaceInput.Plane(nx,ny,nz,d));
    if(lower>.5*(micro>>1&1))clips.add(new FluidSurfaceInput.Plane(0,-1,0,-lower));
    if(upper<.5*((micro>>1&1)+1))clips.add(new FluidSurfaceInput.Plane(0,1,0,upper));
    return new FluidSurfaceInput.Piece(cell,micro,Short.toUnsignedInt(data.getShort(at)),Short.toUnsignedInt(data.getShort(at+2)),clips);
  }
  @Override public boolean equals(Object other){return other instanceof CompactFluidSurface c?Arrays.equals(encoded,c.encoded):super.equals(other);}
  @Override public int hashCode(){return Arrays.hashCode(encoded);}
}
