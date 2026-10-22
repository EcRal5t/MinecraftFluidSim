package mmm.simulation.persistence;

import mmm.fluid.*;
import mmm.granular.GranularDepositDynamics;
import java.io.*;

/** Small continuation columns orthogonal to cell inventories and face momentum. */
final class SolverRuntimeCodec {
	static final String MANIFEST_EXTENSION="mmm:solver_runtime";
	record Data(boolean sealed,boolean precipitation,int maximumThermalSubsteps,int[] dirty,int cursor,
	            double[] boundary,CondensedPhasePressureField pressure,GranularDepositDynamics.State granular) { }
	byte[] encode(FluidDomainState state) throws IOException {
		var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)) {
			out.writeInt(0x434d5254);out.writeBoolean(state.sealedDomainBoundary());out.writeBoolean(state.precipitationEnabled());out.writeInt(state.maximumThermalTransportSubsteps());
			int[] dirty=state.copyChemicalDirtyCells();out.writeBoolean(dirty!=null);if(dirty!=null)writeInts(out,dirty);out.writeInt(state.chemicalReactionCursor());
			double[] boundary=state.copyThermalBoundaryTemperaturesK();out.writeBoolean(boundary!=null);if(boundary!=null){out.writeInt(boundary.length);for(double value:boundary)out.writeLong(Double.doubleToRawLongBits(value));}
			var pressure=state.exportCondensedPhasePressure();out.writeBoolean(pressure!=null);if(pressure!=null){out.writeFloat(pressure.pressureReferencePa());float[] values=pressure.copyRelativeGaugePressurePa();out.writeInt(values.length);for(float value:values)out.writeFloat(value);}
			var granular=state.granularDynamics();out.writeBoolean(granular!=null);if(granular!=null){out.writeLong(granular.logicalStep());writeInts(out,granular.stableAge());writeInts(out,granular.queuedColumns());}
		}return bytes.toByteArray();
	}
	Data decode(byte[] bytes,int cells) throws IOException {
		try(var input=new DataInputStream(new ByteArrayInputStream(bytes))) {
			if(input.readInt()!=0x434d5254)throw new IOException("Unsupported solver continuation encoding");
			boolean sealed=input.readBoolean(),precip=input.readBoolean();int substeps=input.readInt();
			int[] dirty=input.readBoolean()?readInts(input,cells):null;int cursor=input.readInt();double[] boundary=null;
			if(input.readBoolean()){int n=count(input.readInt(),6*cells);if(n!=6*cells)throw new IOException("Thermal boundary shape differs");boundary=new double[n];for(int i=0;i<n;i++)boundary[i]=Double.longBitsToDouble(input.readLong());}
			CondensedPhasePressureField pressure=null;if(input.readBoolean()){float reference=input.readFloat();int n=count(input.readInt(),cells);if(n!=cells)throw new IOException("Pressure shape differs");float[] values=new float[n];for(int i=0;i<n;i++)values[i]=input.readFloat();pressure=new CondensedPhasePressureField(reference,values);}
			GranularDepositDynamics.State granular=null;if(input.readBoolean())granular=new GranularDepositDynamics.State(input.readLong(),readInts(input,cells),readInts(input,cells));
			if(input.read()!=-1)throw new IOException("Trailing solver continuation bytes");return new Data(sealed,precip,substeps,dirty,cursor,boundary,pressure,granular);
		}catch(IllegalArgumentException|ArithmeticException invalid){throw new IOException("Invalid solver continuation",invalid);}
	}
	private static void writeInts(DataOutputStream out,int[] values)throws IOException{out.writeInt(values.length);for(int value:values)out.writeInt(value);}
	private static int[] readInts(DataInputStream input,int max)throws IOException{int[] values=new int[count(input.readInt(),max)];for(int i=0;i<values.length;i++)values[i]=input.readInt();return values;}
	private static int count(int count,int maximum)throws IOException{if(count<0||count>maximum)throw new IOException("Solver continuation count exceeds its shape");return count;}
}
