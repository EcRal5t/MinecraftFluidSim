package mmm.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.EnumMap;
import java.util.List;
import mmm.fluid.MacMomentumState;
import mmm.granular.SolidVolume;
import mmm.inventory.DeviceLiquidInventory;
import mmm.spatial.SpatialGrid;
import mmm.units.DeviceDyadicArray;
import mmm.units.SignedDyadic;

/** Screen aperture scales against the actual open capacity on each side. */
public final class GpuScreenGeometryValidation {
    public static void main(String[] args) {
        try (var context=new VulkanDeviceContext(Integer.parseInt(args[0]),true,true); var kernel=new VulkanLiquidGeometry(context)) {
            var grid=SpatialGrid.rectangular(2,2,2); int cells=grid.cellCount(), left=grid.cell(0,0,0), right=grid.cell(1,0,0);
            int[] offsets=new int[cells+1]; for(int c=0;c<cells;c++) offsets[c+1]=offsets[c]+(c==left||c==right?1:0);
            var inventory=new DeviceLiquidInventory(offsets,new int[]{0,0},new int[]{0xc0,0xc0},
                    DeviceDyadicArray.encode(List.of(SignedDyadic.of(.01),SignedDyadic.of(.01))),
                    new long[]{1,1},new long[]{0,0},new int[]{0,0},new int[]{0,0,0},new int[0],new long[0],new long[]{0,0});
            var momentum=new MacMomentumState(grid,new double[grid.faceCount(0)],new double[grid.faceCount(1)],new double[grid.faceCount(2)]);
            long alignment=Math.max(8,context.storageOffsetAlignment()); var liquid=VulkanLiquidStateLayout.forState(inventory,momentum,alignment);
            var geometry=GpuGasFixture.geometry(liquid,alignment); long cursor=align(geometry.endOffset(),alignment);
            var screens=new VulkanLiquidStateLayout.Column(cursor,grid.faceCount()*2,8); cursor=align(cursor+screens.bytes(),alignment);
            var bed=new VulkanLiquidStateLayout.Column(cursor,cells,8); cursor=align(cursor+bed.bytes(),alignment);
            var coherent=new VulkanLiquidStateLayout.Column(cursor,cells,8); cursor=align(cursor+coherent.bytes(),alignment);
            var fields=new EnumMap<VulkanLiquidRegion.GeometryField,VulkanLiquidStateLayout.Column>(VulkanLiquidRegion.GeometryField.class);
            fields.put(VulkanLiquidRegion.GeometryField.FIXED_MASKS,geometry.masks()); fields.put(VulkanLiquidRegion.GeometryField.OCCUPIED_SOLID,coherent);
            fields.put(VulkanLiquidRegion.GeometryField.SCREEN_FACES,screens); fields.put(VulkanLiquidRegion.GeometryField.CELLS,geometry.cells());
            fields.put(VulkanLiquidRegion.GeometryField.PLANES,geometry.planes()); fields.put(VulkanLiquidRegion.GeometryField.LIQUID_MICRO,geometry.micro());
            fields.put(VulkanLiquidRegion.GeometryField.WALL_GEOMETRY,geometry.walls()); fields.put(VulkanLiquidRegion.GeometryField.FLUXES,geometry.fluxes());
            int face=grid.face(left,1); long solid=Math.round(.03*SolidVolume.SVQ_PER_CELL);
            var data=ByteBuffer.allocate(Math.toIntExact(cursor)).order(ByteOrder.LITTLE_ENDIAN); liquid.importState(data,inventory,momentum);
            data.putInt(Math.toIntExact(geometry.masks().offset()+4L*left),0x3f);data.putInt(Math.toIntExact(geometry.masks().offset()+4L*right),0x3f);
            data.putLong(Math.toIntExact(bed.offset()+8L*left),solid);data.putLong(Math.toIntExact(bed.offset()+8L*right),solid);
            data.putDouble(Math.toIntExact(screens.offset()+16L*face),.001);data.putDouble(Math.toIntExact(screens.offset()+16L*face+8),1);
            try(var storage=new VulkanRegionStorage(context,cursor,1024*1024,grid)) {
                storage.initialize(data,0);
                try(var source=storage.capture();var candidate=storage.prepare(source)) {
                    int error=candidate.execute(0,(command,input,output,errors,staging,stack)->{
                        kernel.rebindRanges(VulkanLiquidGeometry.ranges(liquid,fields,output,errors,geometry.faces(),bed,bed));
                        kernel.record(command,2,2,2);
                    });
                    if(error!=0)throw new AssertionError("Screen geometry failed: "+error);
                    var result=ByteBuffer.allocate(Math.toIntExact(geometry.faces().bytes())).order(ByteOrder.LITTLE_ENDIAN);
                    candidate.read(geometry.faces().offset(),result);
                    double open=result.getDouble(Math.toIntExact(32L*face+16));
                    double deposited=2.0*solid/SolidVolume.SVQ_PER_CELL;
                    double factor=1-deposited/(.6*.25);
                    if(Math.abs(open-.25*factor*factor)>1e-8)throw new AssertionError("Screen open area: "+open);
                    System.out.println("GPU screen open-area normalization: "+open);
                }
            }
        }
    }
    private static long align(long value,long alignment){return (value+alignment-1)&-alignment;}
}
