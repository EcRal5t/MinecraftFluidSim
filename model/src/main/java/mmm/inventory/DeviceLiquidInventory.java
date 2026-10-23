package mmm.inventory;

import mmm.chemistry.ChemicalMixtureRecord;
import mmm.units.DeviceDyadicArray;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Device transfer layout for authoritative liquid stocks; cell and species rows use CSR offsets. */
public record DeviceLiquidInventory(int[] cellOffsets, int[] materials, int[] supportMasks,
		DeviceDyadicArray volumes, long[] massUnits, long[] energyBits, int[] mixturePresent,
		int[] speciesOffsets, int[] speciesHandles, long[] speciesAmounts, long[] massResidualUnits) {
	public DeviceLiquidInventory {
		cellOffsets=cellOffsets.clone();materials=materials.clone();supportMasks=supportMasks.clone();
		massUnits=massUnits.clone();energyBits=energyBits.clone();mixturePresent=mixturePresent.clone();
		speciesOffsets=speciesOffsets.clone();speciesHandles=speciesHandles.clone();
		speciesAmounts=speciesAmounts.clone();massResidualUnits=massResidualUnits.clone();
		int n=materials.length;
		if(cellOffsets.length<2||supportMasks.length!=n||volumes.size()!=n||massUnits.length!=n||energyBits.length!=n
				||mixturePresent.length!=n||massResidualUnits.length!=n||speciesOffsets.length!=n+1
				||speciesHandles.length!=speciesAmounts.length)
			throw new IllegalArgumentException("Liquid transfer column shapes differ");
		checkOffsets(cellOffsets,n);checkOffsets(speciesOffsets,speciesHandles.length);
		for(int i=0;i<n;i++)if(mixturePresent[i]<0||mixturePresent[i]>1
				||mixturePresent[i]==0&&(speciesOffsets[i]!=speciesOffsets[i+1]||massResidualUnits[i]!=0))
			throw new IllegalArgumentException("Liquid mixture presence differs from its columns");
	}
	private static void checkOffsets(int[] offsets,int count) {
		if(offsets[0]!=0||offsets[offsets.length-1]!=count)throw new IllegalArgumentException("CSR endpoints differ");
		for(int i=1;i<offsets.length;i++)if(offsets[i]<offsets[i-1]||offsets[i]>count)throw new IllegalArgumentException("CSR rows are unordered");
	}
	@Override public int[] cellOffsets(){return cellOffsets.clone();}
	@Override public int[] materials(){return materials.clone();}
	@Override public int[] supportMasks(){return supportMasks.clone();}
	@Override public long[] massUnits(){return massUnits.clone();}
	@Override public long[] energyBits(){return energyBits.clone();}
	@Override public int[] mixturePresent(){return mixturePresent.clone();}
	@Override public int[] speciesOffsets(){return speciesOffsets.clone();}
	@Override public int[] speciesHandles(){return speciesHandles.clone();}
	@Override public long[] speciesAmounts(){return speciesAmounts.clone();}
	@Override public long[] massResidualUnits(){return massResidualUnits.clone();}
	public int stockCount(){return materials.length;}
	public int cellCount(){return cellOffsets.length-1;}
	public static DeviceLiquidInventory encode(LiquidCellInventory.Snapshot snapshot) {
		var stocks=snapshot.stocks();int n=stocks.size();
		int[] cells=new int[snapshot.cellCount()+1],materials=new int[n],supports=new int[n],present=new int[n],speciesOffsets=new int[n+1];
		long[] mass=new long[n],energy=new long[n],residual=new long[n];
		var volumes=new ArrayList<mmm.units.SignedDyadic>(n);
		for(int c=0;c<snapshot.cellCount();c++)cells[c+1]=Math.addExact(cells[c],snapshot.rows().get(c).size());
		for(int i=0;i<n;i++){
			var stock=stocks.get(i);var mixture=stock.mixture();materials[i]=stock.key().material();supports[i]=stock.key().supportMask();
			volumes.add(stock.volume());mass[i]=stock.massUnits();energy[i]=Double.doubleToRawLongBits(stock.internalEnergy());
			present[i]=mixture==null?0:1;residual[i]=mixture==null?0:mixture.massResidualUnits();
			speciesOffsets[i+1]=Math.addExact(speciesOffsets[i],mixture==null?0:mixture.entryCount());
		}
		int[] species=new int[speciesOffsets[n]];long[] amounts=new long[species.length];
		for(int i=0;i<n;i++)if(stocks.get(i).mixture()!=null){var mixture=stocks.get(i).mixture();
			for(int j=0;j<mixture.entryCount();j++){int k=speciesOffsets[i]+j;species[k]=mixture.speciesHandle(j);amounts[k]=mixture.amountAt(j);}}
		return new DeviceLiquidInventory(cells,materials,supports,DeviceDyadicArray.encode(volumes),mass,energy,present,speciesOffsets,species,amounts,residual);
	}
	/** Rebuilds through stock, mixture and snapshot constructors so the normal inventory rules apply. */
	public LiquidCellInventory.Snapshot decode() {
		var rows=new ArrayList<List<LiquidCellInventory.Stock>>(cellCount());
		for(int cell=0;cell<cellCount();cell++) {
			var row=new ArrayList<LiquidCellInventory.Stock>(cellOffsets[cell+1]-cellOffsets[cell]);
			for(int i=cellOffsets[cell];i<cellOffsets[cell+1];i++) {
				var mixture=mixturePresent[i]==0?null:new ChemicalMixtureRecord(
						Arrays.copyOfRange(speciesHandles,speciesOffsets[i],speciesOffsets[i+1]),
						Arrays.copyOfRange(speciesAmounts,speciesOffsets[i],speciesOffsets[i+1]),massResidualUnits[i]);
				row.add(new LiquidCellInventory.Stock(new LiquidCellInventory.Key(cell,materials[i],supportMasks[i]),
						volumes.valueAt(i),massUnits[i],mixture,Double.longBitsToDouble(energyBits[i])));
			}
			rows.add(row);
		}
		return new LiquidCellInventory.Snapshot(rows);
	}
}
