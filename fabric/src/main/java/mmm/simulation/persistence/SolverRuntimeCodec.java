package mmm.simulation.persistence;

import mmm.fluid.*;
import mmm.granular.GranularDepositDynamics;
import mmm.chemistry.RegionChemistry;
import mmm.chemistry.ChemicalLimits;
import mmm.world.MovingBodyFluidCoupling;
import java.util.List;
import java.util.ArrayList;
import java.io.*;
import mmm.spatial.SpatialGrid;

/** Small continuation columns orthogonal to cell inventories and face momentum. */
final class SolverRuntimeCodec {
	static final String MANIFEST_EXTENSION="mmm:solver_runtime";
	record Data(SpatialGrid grid,boolean sealed,boolean precipitation,int maximumThermalSubsteps,int[] dirty,int cursor,
	            List<RegionChemistry.MassTransferPending> massTransferPending,
	            double[] boundary,CondensedPhasePressureField pressure,GranularDepositDynamics.State granular,
	            EntityBodyState entityBodies) { }
	byte[] encode(FluidDomainState state) throws IOException {
		var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)) {
			out.writeInt(0x434d5254);
			var grid=state.grid();out.writeInt(grid.blockCount());
			for(int slot=0;slot<grid.blockCount();slot++){var block=grid.block(slot);out.writeInt(block.x());out.writeInt(block.y());out.writeInt(block.z());}
			out.writeBoolean(state.sealedDomainBoundary());out.writeBoolean(state.precipitationEnabled());out.writeInt(state.maximumThermalTransportSubsteps());
			int[] dirty=state.copyChemicalDirtyCells();out.writeBoolean(dirty!=null);
			if(dirty!=null){out.writeInt(dirty.length);for(int cell:dirty)writeCell(out,grid,cell);}
			writeCell(out,grid,state.chemicalReactionCursor());
			var pending=state.chemicalMassTransferPending();out.writeInt(pending.size());
			for(var entry:pending){writeCell(out,grid,entry.cell());out.writeInt(entry.material());out.writeInt(entry.supportMask());out.writeUTF(entry.definitionId());out.writeLong(Double.doubleToRawLongBits(entry.pendingSeconds()));out.writeLong(Double.doubleToRawLongBits(entry.residualMoles()));}
			double[] boundary=state.copyThermalBoundaryTemperaturesK();out.writeBoolean(boundary!=null);if(boundary!=null){out.writeInt(boundary.length);for(double value:boundary)out.writeLong(Double.doubleToRawLongBits(value));}
			var pressure=state.exportCondensedPhasePressure();out.writeBoolean(pressure!=null);if(pressure!=null){out.writeFloat(pressure.pressureReferencePa());float[] values=pressure.copyRelativeGaugePressurePa();out.writeInt(values.length);for(float value:values)out.writeFloat(value);}
			var granular=state.granularDynamics();out.writeBoolean(granular!=null);if(granular!=null){
				out.writeLong(granular.logicalStep());int[] ages=granular.stableAge();out.writeInt(ages.length);
				for(int column=0;column<ages.length;column++){out.writeInt(grid.columnX(column));out.writeInt(grid.columnZ(column));out.writeInt(ages[column]);}
				int[] queue=granular.queuedColumns();out.writeInt(queue.length);
				for(int column:queue){out.writeInt(grid.columnX(column));out.writeInt(grid.columnZ(column));}
			}
			var bodies=state.entityBodyState().bodies();out.writeInt(bodies.size());
			for(var body:bodies){
				out.writeLong(body.bodyId().getMostSignificantBits());out.writeLong(body.bodyId().getLeastSignificantBits());
				var bounds=body.bounds();
				for(double value:new double[]{bounds.minimumX(),bounds.minimumY(),bounds.minimumZ(),bounds.maximumX(),bounds.maximumY(),bounds.maximumZ(),body.velocityXMetresPerSecond(),body.velocityYMetresPerSecond(),body.velocityZMetresPerSecond()})out.writeLong(Double.doubleToRawLongBits(value));
			}
		}return bytes.toByteArray();
	}
	Data decode(byte[] bytes,int sizeX,int sizeY,int sizeZ) throws IOException {
		try(var input=new DataInputStream(new ByteArrayInputStream(bytes))) {
			if(input.readInt()!=0x434d5254)throw new IOException("Unsupported solver continuation encoding");
			int blockCount=count(input.readInt(),bytes.length/12);
			var blocks=new ArrayList<SpatialGrid.Block>(blockCount);
			for(int i=0;i<blockCount;i++)blocks.add(new SpatialGrid.Block(input.readInt(),input.readInt(),input.readInt()));
			var grid=new SpatialGrid(sizeX,sizeY,sizeZ,blocks);int cells=grid.cellCount();
			boolean sealed=input.readBoolean(),precip=input.readBoolean();int substeps=input.readInt();
			int[] dirty=null;if(input.readBoolean()){dirty=new int[count(input.readInt(),cells)];for(int i=0;i<dirty.length;i++)dirty[i]=readCell(input,grid);}
			int cursor=readCell(input,grid);double[] boundary=null;
			int pendingCount=count(input.readInt(),(int)Math.min(bytes.length/30L,(long)cells*8*ChemicalLimits.MAX_GAS_LIQUID_MASS_TRANSFERS));
			var pending=new ArrayList<RegionChemistry.MassTransferPending>(pendingCount);
			for(int i=0;i<pendingCount;i++)pending.add(new RegionChemistry.MassTransferPending(readCell(input,grid),input.readInt(),input.readInt(),input.readUTF(),Double.longBitsToDouble(input.readLong()),Double.longBitsToDouble(input.readLong())));
			if(input.readBoolean()){int n=count(input.readInt(),6*cells);if(n!=6*cells)throw new IOException("Thermal boundary shape differs");boundary=new double[n];for(int i=0;i<n;i++)boundary[i]=Double.longBitsToDouble(input.readLong());}
			CondensedPhasePressureField pressure=null;if(input.readBoolean()){float reference=input.readFloat();int n=count(input.readInt(),cells);if(n!=cells)throw new IOException("Pressure shape differs");float[] values=new float[n];for(int i=0;i<n;i++)values[i]=input.readFloat();pressure=new CondensedPhasePressureField(reference,values);}
			GranularDepositDynamics.State granular=null;if(input.readBoolean()){
				long step=input.readLong();int n=count(input.readInt(),grid.columnCount());
				if(n!=grid.columnCount())throw new IOException("Granular column count differs from its grid");
				int[] ages=new int[n];boolean[] seen=new boolean[n];
				for(int i=0;i<n;i++){int column=grid.column(input.readInt(),input.readInt());if(column<0||seen[column])throw new IOException("Invalid granular column");seen[column]=true;ages[column]=input.readInt();}
				int[] queue=new int[count(input.readInt(),n)];
				for(int i=0;i<queue.length;i++){queue[i]=grid.column(input.readInt(),input.readInt());if(queue[i]<0)throw new IOException("Invalid queued granular column");}
				granular=new GranularDepositDynamics.State(step,ages,queue);
			}
			int bodyCount=count(input.readInt(),MovingBodyFluidCoupling.MAXIMUM_REQUESTS_PER_STEP);
			var bodies=new ArrayList<MovingBodyFluidCoupling.Request>(bodyCount);
			for(int i=0;i<bodyCount;i++){
				var id=new java.util.UUID(input.readLong(),input.readLong());
				var bounds=new MovingBodyFluidCoupling.Bounds(readDoubleBits(input),readDoubleBits(input),readDoubleBits(input),readDoubleBits(input),readDoubleBits(input),readDoubleBits(input));
				bodies.add(new MovingBodyFluidCoupling.Request(id,bounds,readDoubleBits(input),readDoubleBits(input),readDoubleBits(input)));
			}
			if(input.read()!=-1)throw new IOException("Trailing solver continuation bytes");return new Data(grid,sealed,precip,substeps,dirty,cursor,List.copyOf(pending),boundary,pressure,granular,new EntityBodyState(bodies));
		}catch(IllegalArgumentException|ArithmeticException invalid){throw new IOException("Invalid solver continuation",invalid);}
	}
	private static void writeCell(DataOutputStream out,SpatialGrid grid,int cell)throws IOException{out.writeInt(grid.x(cell));out.writeInt(grid.y(cell));out.writeInt(grid.z(cell));}
	private static int readCell(DataInputStream in,SpatialGrid grid)throws IOException{
		int cell=grid.cell(in.readInt(),in.readInt(),in.readInt());if(cell<0)throw new IOException("Solver continuation cell is outside its grid");return cell;
	}
	private static double readDoubleBits(DataInputStream input)throws IOException{return Double.longBitsToDouble(input.readLong());}
	private static int count(int count,int maximum)throws IOException{if(count<0||count>maximum)throw new IOException("Solver continuation count exceeds its shape");return count;}
}
