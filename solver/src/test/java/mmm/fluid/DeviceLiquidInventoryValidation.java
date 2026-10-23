package mmm.fluid;

import mmm.chemistry.ChemicalMixtureRecord;
import mmm.inventory.DeviceLiquidInventory;
import mmm.inventory.LiquidCellInventory;
import mmm.units.DeviceDyadicArray;
import mmm.units.SignedDyadic;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Exact inventory transfer checks, independent of device floating-point arithmetic. */
public final class DeviceLiquidInventoryValidation {
	public static void main(String[] args) {
		var tail=new SignedDyadic(BigInteger.ONE.shiftLeft(1100).add(BigInteger.ONE),-1101);
		var rows=new ArrayList<List<LiquidCellInventory.Stock>>();
		rows.add(List.of(
				new LiquidCellInventory.Stock(new LiquidCellInventory.Key(0,0,255),SignedDyadic.of(1,-1),625_000_000_000L,
						new ChemicalMixtureRecord(new int[]{0,4},new long[]{3_000_000_000L,9},17),123.456789),
				new LiquidCellInventory.Stock(new LiquidCellInventory.Key(0,1,255),SignedDyadic.of(1,-2),200_000_000_000L,null,75)));
		rows.add(List.of());
		rows.add(List.of(new LiquidCellInventory.Stock(new LiquidCellInventory.Key(2,0,15),tail,1,
				new ChemicalMixtureRecord(new int[0],new long[0],1),Double.MIN_VALUE)));
		rows.add(List.of(new LiquidCellInventory.Stock(new LiquidCellInventory.Key(3,2,240),SignedDyadic.of(Double.MIN_VALUE),0,null,0)));
		var before=new LiquidCellInventory.Snapshot(rows);
		var encoded=DeviceLiquidInventory.encode(before);
		if(!before.rows().equals(encoded.decode().rows()))throw new AssertionError("Liquid inventory transfer changed authoritative columns");
		if(encoded.cellOffsets()[1]!=2||encoded.cellOffsets()[2]!=2||encoded.speciesOffsets()[1]!=2)
			throw new AssertionError("CSR rows lost empty cells or species");
		encoded.massUnits()[0]=0;encoded.cellOffsets()[1]=0;encoded.volumes().limbs()[0]=0;
		if(!before.rows().equals(encoded.decode().rows()))throw new AssertionError("Exported columns mutate the transfer snapshot");
		var exact=List.of(SignedDyadic.ZERO,tail,SignedDyadic.of(-7,-1074),
				new SignedDyadic(BigInteger.ONE.shiftLeft(SignedDyadic.MAX_SIGNIFICAND_BITS-1).add(BigInteger.ONE),-2000));
		var dyadics=DeviceDyadicArray.encode(exact);
		for(int i=0;i<exact.size();i++)if(!exact.get(i).equals(dyadics.valueAt(i)))
			throw new AssertionError("Signed magnitude limbs changed value "+i);
		System.out.println("Device liquid inventory: multi-material, empty cells, integer composition and exact tails passed");
	}
}
