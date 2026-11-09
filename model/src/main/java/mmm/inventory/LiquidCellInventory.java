package mmm.inventory;

import mmm.chemistry.ChemicalMixtureRecord;
import mmm.chemistry.ChemicalRegistry;
import mmm.units.Mass;
import mmm.units.SignedDyadic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Persistent finite-volume liquid quantities, independent of numerical or display markers. */
public final class LiquidCellInventory {
	private static final List<List<Integer>> CONNECTED_SUPPORTS = connectedSupports();
	private static List<List<Integer>> connectedSupports() {
		var masks = new ArrayList<List<Integer>>(256);
		for (int mask = 0; mask < 256; mask++) {
			var components = new ArrayList<Integer>(4); int unseen = ~mask & 255;
			while (unseen != 0) {
				int component = Integer.lowestOneBit(unseen), frontier = component;
				while (frontier != 0) {
					int next = 0;
					for (int bit = 0; bit < 8; bit++) if ((frontier & 1 << bit) != 0)
						for (int axis = 0; axis < 3; axis++) next |= 1 << (bit ^ 1 << axis);
					next &= unseen & ~component; component |= next; frontier = next;
				}
				components.add(component); unseen &= ~component;
			}
			masks.add(List.copyOf(components));
		}
		return List.copyOf(masks);
	}
	public static List<Integer> connectedSupports(int solidMask) { return CONNECTED_SUPPORTS.get(solidMask); }
	public record Key(int cell, int material, int supportMask) implements Comparable<Key> {
		public Key {
			if (cell < 0 || material < 0 || supportMask <= 0 || supportMask > 255)
				throw new IllegalArgumentException("Invalid liquid cell identity");
		}
		@Override public int compareTo(Key other) {
			int order = Integer.compare(cell, other.cell);
			if (order == 0) order = Integer.compare(material, other.material);
			return order == 0 ? Integer.compare(supportMask, other.supportMask) : order;
		}
	}

	/** Volume uses cell-volume units; mass and species retain their integer inventory encodings. */
	public static final class Stock {
		private final Key key;
		private final SignedDyadic volume;
		private final long massUnits;
		private final ChemicalMixtureRecord mixture;
		private final double internalEnergy,volumeFraction;
		public Stock(Key key,SignedDyadic volume,long massUnits,ChemicalMixtureRecord mixture,double internalEnergy) {
			if (internalEnergy == 0) internalEnergy = 0;
			double numericalVolume=volume==null?0:volume.toDouble();
			if (key == null || volume == null || volume.signum() <= 0 || !(numericalVolume > 0)
					|| !Double.isFinite(numericalVolume) || massUnits < 0 || !Double.isFinite(internalEnergy)
					|| massUnits == 0 && internalEnergy != 0)
				throw new IllegalArgumentException("Invalid liquid cell stock");
			this.key=key;this.volume=volume;this.massUnits=massUnits;this.mixture=mixture;
			this.internalEnergy=internalEnergy;volumeFraction=numericalVolume;
		}
		/** Energy publication shares the immutable material quantities and their numerical view. */
		private Stock(Stock source,double energy) {
			if(!Double.isFinite(energy)||source.massUnits==0&&energy!=0)throw new IllegalArgumentException("Invalid liquid cell energy");
			key=source.key;volume=source.volume;massUnits=source.massUnits;mixture=source.mixture;
			volumeFraction=source.volumeFraction;internalEnergy=energy==0?0:energy;
		}
		public Key key(){return key;}
		public SignedDyadic volume(){return volume;}
		public long massUnits(){return massUnits;}
		public ChemicalMixtureRecord mixture(){return mixture;}
		public double internalEnergy(){return internalEnergy;}
		public double volumeFraction() { return volumeFraction; }
		public double massKilograms() { return Mass.kilograms(massUnits); }
		@Override public boolean equals(Object value){
			return this==value||value instanceof Stock other&&key.equals(other.key)&&volume.equals(other.volume)
					&&massUnits==other.massUnits&&java.util.Objects.equals(mixture,other.mixture)
					&&Double.doubleToLongBits(internalEnergy)==Double.doubleToLongBits(other.internalEnergy);
		}
		@Override public int hashCode(){return java.util.Objects.hash(key,volume,massUnits,mixture,internalEnergy);}
		@Override public String toString(){
			return "Stock[key="+key+", volume="+volume+", massUnits="+massUnits+", mixture="+mixture
					+", internalEnergy="+internalEnergy+"]";
		}
	}

	/** Both row levels are immutable; unchanged rows are shared by private candidates. */
	public static final class Snapshot {
		private final List<List<Stock>> rows;
		private volatile List<Stock> ordinals;
		private volatile List<Stock> physical;
		private final PresenceIndex presence;
		private final CellQuantities[] quantities;
		private final SignedDyadic totalVolume;
		private final Long totalMass;
		private final long geometryRevision;
		private final Object geometryIdentity;
		private final long inventoryRevision;
		private final ChemicalRegistry validatedChemistry;
		private final boolean validated;
		public Snapshot(List<List<Stock>> rows) {
			if (rows == null || rows.isEmpty()) throw new IllegalArgumentException("Liquid cell rows are missing");
			var copy = new ArrayList<List<Stock>>(rows.size());
			for (int cell = 0; cell < rows.size(); cell++) {
				var row = new ArrayList<>(rows.get(cell)); row.sort(java.util.Comparator.comparing(Stock::key));
				Key previous = null;
				for (var stock : row) {
					if (stock.key().cell() != cell || stock.key().equals(previous)) throw new IllegalArgumentException("Liquid snapshot row identity is invalid");
					previous = stock.key();
				}
				copy.add(List.copyOf(row));
			}
			this.rows = List.copyOf(copy);
			geometryRevision = 0; geometryIdentity = new Object();
			inventoryRevision = 0; validatedChemistry = null; validated = false;
			presence = null;
			quantities = null; totalVolume = null; totalMass = null;
		}
		private Snapshot(LiquidCellInventory owner) {
			rows = owner.rows; ordinals = owner.ordinals; physical = owner.physical; geometryRevision = owner.geometryRevision; geometryIdentity = owner.geometryIdentity;
			inventoryRevision = owner.revision; validatedChemistry = owner.chemistry; validated = true;
			presence = owner.presence;
			quantities=owner.quantities;totalVolume=owner.totalVolume;totalMass=owner.totalMass;
		}
		public int cellCount() { return rows.size(); }
		public List<List<Stock>> rows() { return rows; }
		public long geometryRevision() { return geometryRevision; }
		public long revision() { return inventoryRevision; }
		public List<Stock> stocks() { var cached = ordinals; if (cached == null) ordinals = cached = flatten(rows); return cached; }
		public int size() { return stocks().size(); }
		public Stock stockAt(int ordinal) { return stocks().get(ordinal); }
		public int ordinalOf(Key key) { return findOrdinal(stocks(), key); }
		public List<Stock> physicalStocks() { var cached = physical; if (cached == null) physical = cached = physical(stocks()); return cached; }
	}

	/** A null replacement removes the key. Exact U is rounded once when the candidate is prepared. */
	public record Edit(Key key, Stock after, SignedDyadic exactEnergyAfter) {
		public Edit(Key key, Stock after) { this(key, after, SignedDyadic.of(after == null ? 0 : after.internalEnergy())); }
		public Edit {
			if (key == null || exactEnergyAfter == null || after != null && !after.key().equals(key)
					|| after == null && exactEnergyAfter.signum() != 0
					|| after != null && Double.doubleToLongBits(after.internalEnergy()) != Double.doubleToLongBits(exactEnergyAfter.toDouble()))
				throw new IllegalArgumentException("Invalid liquid inventory edit");
		}
	}

	/** The same transported integer mass is available to the MAC momentum operator. */
	public record Transfer(int face, Key source, Key target, SignedDyadic volume, long massUnits) { }
	/** A caloric model writes non-remainder shares; the inventory assigns the source U remainder. */
	@FunctionalInterface
	public interface EnergyPartitioner {
		void partition(Stock source,long[] masses,long[][] speciesAmounts,int remainder,double[] energies);
	}
	/** Actual quantity settlement for the mechanical operator, before target U is stored. */
	public static final class TransportQuantities {
		private final List<Transfer> transfers;
		private final long[] beforeMass,afterMass;
		private final double[] afterVolume;
		private SignedDyadic[] heatByCell;
		private double[] heatTerms;
		private int heatCount;
		private TransportQuantities(List<Transfer> transfers,long[] beforeMass,long[] afterMass,double[] afterVolume){
			this.transfers=transfers;this.beforeMass=beforeMass;this.afterMass=afterMass;this.afterVolume=afterVolume;
		}
		public List<Transfer> transfers(){return transfers;}
		public long[] beforeMass(){return beforeMass;}
		public long[] afterMass(){return afterMass;}
		public double[] afterVolume(){return afterVolume;}
		/** Numerical mechanical contributions retain their exact binary64 sum until publication. */
		public void addMechanicalHeat(int cell,double joules){
			if(!Double.isFinite(joules))throw new IllegalArgumentException("Mechanical energy transfer is not finite");
			if(joules==0)return;
			if(heatByCell==null){heatByCell=new SignedDyadic[afterMass.length];heatTerms=new double[Math.max(8,afterMass.length)];}
			var amount=SignedDyadic.of(joules);var before=heatByCell[cell];heatByCell[cell]=before==null?amount:before.add(amount);
			if(heatCount==heatTerms.length)heatTerms=java.util.Arrays.copyOf(heatTerms,2*heatCount);
			heatTerms[heatCount++]=joules;
		}
	}
	@FunctionalInterface
	public interface TransportFinalizer { void finish(TransportQuantities quantities); }

	public static final class Plan {
		private final LiquidCellInventory owner, future;
		private final long sourceRevision;
		private final List<Integer> changedCells;
		private final List<Transfer> transfers;
		private final SignedDyadic energyChange, energyRoundoff, mechanicalHeat;
		private Plan(LiquidCellInventory owner, LiquidCellInventory future, List<Integer> changedCells,
		             List<Transfer> transfers,
		             SignedDyadic energyChange, SignedDyadic energyRoundoff, SignedDyadic mechanicalHeat) {
			this.owner = owner; this.future = future; sourceRevision = owner.revision;
			this.changedCells = List.copyOf(changedCells); this.transfers = List.copyOf(transfers);
			this.energyChange = energyChange; this.energyRoundoff = energyRoundoff;
			this.mechanicalHeat = mechanicalHeat;
		}
		public LiquidCellInventory future() { return future; }
		public List<Integer> changedCells() { return changedCells; }
		public List<Transfer> transfers() { return transfers; }
		/** Associates actual routed quantities with this prepared inventory successor. */
		public Plan withTransfers(List<Transfer> routed) {
			requireCurrent();
			return new Plan(owner,future,changedCells,List.copyOf(routed),energyChange,energyRoundoff,mechanicalHeat);
		}
		public SignedDyadic energyChange() { return energyChange; }
		public SignedDyadic energyRoundoff() { return energyRoundoff; }
		public SignedDyadic mechanicalHeat() { return mechanicalHeat; }
		public Plan withMechanicalHeat(Map<Integer, SignedDyadic> heatByCell) { return owner.addMechanicalHeat(this, heatByCell); }
		/** Applies work to exact prepared owners, preserving separation of fixed supports. */
		public Plan withMechanicalHeatByStock(Map<Key, SignedDyadic> heatByStock) {
			requireCurrent(); return owner.addMechanicalHeatByStock(this, heatByStock);
		}
		/** Final caloric assignment reuses the already prepared mass, volume and composition. */
		public Plan withInternalEnergiesByStock(Map<Key,Double> energies){
			requireCurrent();var updates=owner.new PreparedEnergyRows(this,energies.size());
			for(var entry:energies.entrySet())updates.set(entry.getKey(),entry.getValue());
			return updates.finish(SignedDyadic.ZERO,false);
		}
		public void requireCurrent() {
			if (owner.revision != sourceRevision || future.revision != sourceRevision + 1)
				throw new IllegalStateException("Liquid cell inventory changed after preparation");
		}
		public void commit() { requireCurrent(); owner.rows = future.rows; owner.ordinals = future.ordinals; owner.physical = future.physical; owner.presence = future.presence; owner.quantities=future.quantities;owner.totalVolume=future.totalVolume;owner.totalMass=future.totalMass;owner.geometryRevision = future.geometryRevision; owner.geometryIdentity = future.geometryIdentity; owner.revision++; }
	}

	private List<List<Stock>> rows;
	private final ChemicalRegistry chemistry;
	private long revision;
	private long geometryRevision;
	private Object geometryIdentity = new Object();
	private volatile List<Stock> ordinals;
	private volatile List<Stock> physical;
	/** Immutable derived presence counts, shared by snapshots and unchanged composition edits. */
	private record PresenceIndex(int[] species, int[] materials) { }
	private PresenceIndex presence;
	private record CellQuantities(SignedDyadic volume,long mass) { }
	private CellQuantities[] quantities;
	private SignedDyadic totalVolume;
	private Long totalMass;

	public LiquidCellInventory(int cellCount, ChemicalRegistry chemistry) {
		if (cellCount <= 0) throw new IllegalArgumentException("Liquid cell count must be positive");
		this.chemistry = chemistry;
		presence = new PresenceIndex(new int[chemistry == null ? 0 : chemistry.species().size()], new int[0]);
		var empty = new ArrayList<List<Stock>>(cellCount);
		for (int cell = 0; cell < cellCount; cell++) empty.add(List.of());
		rows = List.copyOf(empty);
		quantities=new CellQuantities[cellCount];
		java.util.Arrays.fill(quantities,new CellQuantities(SignedDyadic.ZERO,0));
		totalVolume=SignedDyadic.ZERO;totalMass=0L;
	}
	public LiquidCellInventory(Snapshot snapshot, ChemicalRegistry chemistry) {
		this(snapshot.cellCount(), chemistry);
		var edits = new ArrayList<Edit>();
		for (int cell = 0; cell < cellCount(); cell++) for (var stock : snapshot.rows().get(cell)) {
			if (stock.key().cell() != cell) throw new IllegalArgumentException("Liquid stock is in the wrong cell row");
			edits.add(new Edit(stock.key(), stock));
		}
		planEdits(edits).commit(); revision = snapshot.revision(); geometryRevision = snapshot.geometryRevision(); geometryIdentity = snapshot.geometryIdentity;
	}
	private LiquidCellInventory(Snapshot snapshot, ChemicalRegistry chemistry, boolean trusted) {
		this.chemistry = chemistry; rows = snapshot.rows; ordinals = snapshot.ordinals; physical = snapshot.physical;
		presence = snapshot.presence;
		quantities=snapshot.quantities;totalVolume=snapshot.totalVolume;totalMass=snapshot.totalMass;
		revision = snapshot.revision(); geometryRevision = snapshot.geometryRevision(); geometryIdentity = snapshot.geometryIdentity;
	}
	/** In-memory handoff shares validated immutable rows; cold decoded rows are checked once. */
	public static LiquidCellInventory fromValidatedSnapshot(Snapshot snapshot, ChemicalRegistry chemistry) {
		return snapshot.validated && snapshot.validatedChemistry == chemistry
				? new LiquidCellInventory(snapshot, chemistry, true) : new LiquidCellInventory(snapshot, chemistry);
	}
	/** A null quantity-change set shares unchanged mass and volume caches. */
	private LiquidCellInventory(LiquidCellInventory source, List<List<Stock>> rows, long revision,Iterable<Integer> quantityChanges) {
		this.chemistry = source.chemistry; this.rows = rows; this.revision = revision;
		presence = source.presence;
		if(quantityChanges==null){quantities=source.quantities;totalVolume=source.totalVolume;totalMass=source.totalMass;}
		else {
			quantities=source.quantities.clone();
			for(int cell:quantityChanges)quantities[cell]=null;
		}
		geometryRevision = source.geometryRevision; geometryIdentity = source.geometryIdentity;
		if (rows == source.rows) { ordinals = source.ordinals; physical = source.physical; }
	}
	public int cellCount() { return rows.size(); }
	public long revision() { return revision; }
	/** Geometry identity survives immutable handoff and energy-only edits. */
	public boolean hasSameGeometry(Snapshot snapshot) { return snapshot != null && geometryIdentity == snapshot.geometryIdentity; }
	/** Material quantities retain their identity through energy-only publication. */
	public boolean hasSameQuantities(Snapshot snapshot) { return snapshot != null && quantities == snapshot.quantities; }
	public long geometryRevision() { return geometryRevision; }
	public ChemicalRegistry chemicalRegistry() { return chemistry; }
	public boolean hasSpecies(int handle) { return presence.species[handle] != 0; }
	public boolean hasMaterial(int handle) { return handle < presence.materials.length && presence.materials[handle] != 0; }
	public Snapshot snapshot() { return new Snapshot(this); }
	public LiquidCellInventory copy() { return new LiquidCellInventory(this, rows, revision,null); }
	public List<Stock> stocksInCell(int cell) { return rows.get(cell); }
	public List<Stock> stocks() { var cached = ordinals; if (cached == null) ordinals = cached = flatten(rows); return cached; }
	public int size() { return stocks().size(); }
	public Stock stockAt(int ordinal) { return stocks().get(ordinal); }
	public int ordinalOf(Key key) { return findOrdinal(stocks(), key); }
	/** Zero-mass geometric tails merge at their existing key and never enter caloric or inertial inversion. */
	public List<Stock> physicalStocks() { var cached = physical; if (cached == null) physical = cached = physical(stocks()); return cached; }
	public int physicalSize() { return physicalStocks().size(); }
	public Stock physicalStockAt(int ordinal) { return physicalStocks().get(ordinal); }
	public int physicalOrdinalOf(Key key) { return findOrdinal(physicalStocks(), key); }
	public List<Stock> physicalStocksInCell(int cell) { return physical(rows.get(cell)); }
	public Stock stock(Key key) {
		for (var stock : rows.get(key.cell())) if (stock.key().equals(key)) return stock;
		return null;
	}
	public SignedDyadic exactCellVolume(int cell) {
		return cellQuantities(cell).volume;
	}
	private CellQuantities cellQuantities(int cell){
		var cached=quantities[cell];if(cached!=null)return cached;
		var volume=SignedDyadic.ZERO;long mass=0;
		for(var stock:rows.get(cell)){volume=volume.add(stock.volume());mass=Math.addExact(mass,stock.massUnits());}
		return quantities[cell]=new CellQuantities(volume,mass);
	}
	public SignedDyadic exactVolume() {
		if(totalVolume==null){var total=SignedDyadic.ZERO;for(int cell=0;cell<cellCount();cell++)total=total.add(exactCellVolume(cell));totalVolume=total;}
		return totalVolume;
	}
	public long massUnits() {
		if(totalMass==null){long total=0;for(int cell=0;cell<cellCount();cell++)total=Math.addExact(total,cellQuantities(cell).mass);totalMass=total;}
		return totalMass;
	}
	public SignedDyadic exactInternalEnergy() {
		var total = SignedDyadic.ZERO; for (var row : rows) for (var stock : row) total = total.add(SignedDyadic.of(stock.internalEnergy())); return total;
	}

	public Plan planEdits(List<Edit> edits) { return prepareEdits(edits, List.of()); }
	public Plan planAdditions(List<Stock> additions) {
		var totals = new LinkedHashMap<Key, Accumulator>();
		for (var addition : additions) {
			var total = totals.computeIfAbsent(addition.key(), key -> {
				var result = new Accumulator(key); var before = stock(key);
				if (before != null) result.add(before.volume(), before.massUnits(), before.mixture(), SignedDyadic.of(before.internalEnergy()));
				return result;
			});
			total.add(addition.volume(), addition.massUnits(), addition.mixture(), SignedDyadic.of(addition.internalEnergy()));
		}
		var edits = new ArrayList<Edit>(); for (var total : totals.values()) edits.add(total.edit()); return planEdits(edits);
	}
	public Plan planEnergyUpdate(double[] energies) {
		return planEnergyUpdate(energies, stocks());
	}
	public Plan planPhysicalEnergyUpdate(double[] energies) { return planEnergyUpdate(energies, physicalStocks()); }
	public Plan planPhysicalEnergyUpdate(int ordinal, double energy) {
		return planEnergyUpdate(new double[]{energy}, List.of(physicalStockAt(ordinal)));
	}
	/** Energy-only writes share quantities, species and the inventory presence index. */
	private Plan planEnergyUpdate(double[] energies, List<Stock> stocks) {
		if (energies.length != stocks.size()) throw new IllegalArgumentException("Liquid energy ordinals differ from cell inventory");
		ArrayList<List<Stock>> futureRows=null;ArrayList<Stock> row=null;var changed=new ArrayList<Integer>();
		int currentCell=-1,energyCount=0;double[] beforeEnergy=null,afterEnergy=null;
		for (int i = 0; i < energies.length; i++) {
			Stock stock = stocks.get(i);
			if(Double.doubleToLongBits(stock.internalEnergy())==Double.doubleToLongBits(energies[i]))continue;
			int cell=stock.key().cell();
			if(futureRows==null)futureRows=new ArrayList<>(rows);
			if(currentCell!=cell){
				if(row!=null)futureRows.set(currentCell,List.copyOf(row));
				currentCell=cell;row=new ArrayList<>(rows.get(cell));changed.add(cell);
			}
			row.set(findOrdinal(row,stock.key()),new Stock(stock,energies[i]));
			if(beforeEnergy==null){beforeEnergy=new double[energies.length];afterEnergy=new double[energies.length];}
			beforeEnergy[energyCount]=stock.internalEnergy();afterEnergy[energyCount++]=energies[i];
		}
		if(row!=null)futureRows.set(currentCell,List.copyOf(row));
		var successor=new LiquidCellInventory(this,futureRows==null?rows:java.util.Collections.unmodifiableList(futureRows),revision+1,null);
		var energyChange=energyCount==0?SignedDyadic.ZERO:SignedDyadic.sum(afterEnergy,energyCount).subtract(SignedDyadic.sum(beforeEnergy,energyCount));
		return new Plan(this,successor,changed,List.of(),energyChange,SignedDyadic.ZERO,SignedDyadic.ZERO);
	}
	private static List<Stock> flatten(List<List<Stock>> rows) {
		var flat = new ArrayList<Stock>(); for (var row : rows) flat.addAll(row); return List.copyOf(flat);
	}
	private static List<Stock> physical(List<Stock> stocks) {
		for (var stock : stocks) if (stock.massUnits() == 0) return stocks.stream().filter(entry -> entry.massUnits() > 0).toList();
		return stocks;
	}
	private static int findOrdinal(List<Stock> stocks, Key key) {
		int low = 0, high = stocks.size() - 1;
		while (low <= high) { int mid = (low + high) >>> 1, order = stocks.get(mid).key().compareTo(key);
			if (order < 0) low = mid + 1; else if (order > 0) high = mid - 1; else return mid; }
		return -1;
	}
	private Plan prepareEdits(List<Edit> edits, List<Transfer> transfers) {
		if(edits.isEmpty())return new Plan(this,new LiquidCellInventory(this,rows,revision+1,null),List.of(),transfers,
				SignedDyadic.ZERO,SignedDyadic.ZERO,SignedDyadic.ZERO);
		var futureRows = new ArrayList<>(rows);
		int[] futureSpecies = null, futureMaterials = null;
		var changed = new LinkedHashMap<Integer, TreeMap<Key, Stock>>();
		var seen = new java.util.HashSet<Key>();
		boolean geometryChanged = false;
		var volumeChange = SignedDyadic.ZERO; var energyChange = SignedDyadic.ZERO; var roundoff = SignedDyadic.ZERO; long massChange = 0;
		for (var edit : edits) {
			Key key = edit.key();
			if (key.cell() >= cellCount() || !seen.add(key)) throw new IllegalArgumentException("Liquid edit is outside the grid or repeated");
			var row = changed.computeIfAbsent(key.cell(), cell -> {
				var result = new TreeMap<Key, Stock>(); for (var stock : rows.get(cell)) result.put(stock.key(), stock); return result;
			});
			Stock before = row.remove(key), after = normalize(edit.after());
			if (!samePresence(before, after)) {
				if (futureSpecies == null) {
					futureSpecies = presence.species.clone();
					futureMaterials = presence.materials.clone();
				}
				if (after != null && after.key().material() >= futureMaterials.length)
					futureMaterials = java.util.Arrays.copyOf(futureMaterials, after.key().material() + 1);
				adjustPresence(before, -1, futureSpecies, futureMaterials);
				adjustPresence(after, 1, futureSpecies, futureMaterials);
			}
			geometryChanged |= before == null ? after != null : after == null || !before.volume().equals(after.volume());
			if (before != null) { volumeChange = volumeChange.subtract(before.volume()); massChange = Math.subtractExact(massChange, before.massUnits()); energyChange = energyChange.subtract(SignedDyadic.of(before.internalEnergy())); }
			if (after != null) {
				row.put(key, after); volumeChange = volumeChange.add(after.volume()); massChange = Math.addExact(massChange, after.massUnits());
				energyChange = energyChange.add(SignedDyadic.of(after.internalEnergy()));
				roundoff = roundoff.add(SignedDyadic.of(after.internalEnergy()).subtract(edit.exactEnergyAfter()));
			}
		}
		for (var entry : changed.entrySet()) futureRows.set(entry.getKey(), List.copyOf(entry.getValue().values()));
		var future = new LiquidCellInventory(this, List.copyOf(futureRows), revision + 1,changed.keySet());
		if(totalVolume!=null)future.totalVolume=totalVolume.add(volumeChange);
		if(totalMass!=null)future.totalMass=Math.addExact(totalMass,massChange);
		if (futureSpecies != null) future.presence = new PresenceIndex(futureSpecies, futureMaterials);
		if (geometryChanged) { future.geometryRevision++; future.geometryIdentity = new Object(); }
		return new Plan(this, future, new ArrayList<>(changed.keySet()), transfers, energyChange, roundoff, SignedDyadic.ZERO);
	}
	/** One private row editor serves mechanical work and final caloric assignments. */
	private final class PreparedEnergyRows {
		final Plan base;
		ArrayList<List<Stock>> futureRows;
		ArrayList<Stock>[] changedRows;
		final ArrayList<Integer> changed=new ArrayList<>();
		double[] beforeEnergy,afterEnergy;int energyCount;
		PreparedEnergyRows(Plan base,int expected){this.base=base;beforeEnergy=new double[expected];afterEnergy=new double[expected];}
		Stock stock(Key key){
			var row=changedRows==null||changedRows[key.cell()]==null?base.future.rows.get(key.cell()):changedRows[key.cell()];
			int index=findOrdinal(row,key);if(index<0)throw new IllegalArgumentException("Prepared liquid owner is missing");return row.get(index);
		}
		void add(Key key,SignedDyadic heat){
			if(heat==null)throw new IllegalArgumentException("Mechanical energy transfer is missing");
			if(heat.signum()==0)return;
			var stock=stock(key);if(stock.massUnits()==0)throw new IllegalArgumentException("Mechanical heat requires an actual liquid owner");
			set(key,stock.internalEnergy()+heat.toDouble());
		}
		@SuppressWarnings("unchecked")
		void set(Key key,double energy){
			var stock=stock(key);if(Double.doubleToLongBits(stock.internalEnergy())==Double.doubleToLongBits(energy))return;
			var after=new Stock(stock,energy);
			if(futureRows==null){futureRows=new ArrayList<>(base.future.rows);changedRows=(ArrayList<Stock>[])new ArrayList<?>[cellCount()];}
			var row=changedRows[key.cell()];if(row==null){changedRows[key.cell()]=row=new ArrayList<>(base.future.rows.get(key.cell()));changed.add(key.cell());}
			row.set(findOrdinal(row,key),after);
			if(energyCount==beforeEnergy.length){int n=Math.max(8,2*energyCount);beforeEnergy=java.util.Arrays.copyOf(beforeEnergy,n);afterEnergy=java.util.Arrays.copyOf(afterEnergy,n);}
			beforeEnergy[energyCount]=stock.internalEnergy();afterEnergy[energyCount++]=energy;
		}
		Plan finish(SignedDyadic heat,boolean mechanical){
			if(energyCount==0&&heat.signum()==0)return base;
			var change=SignedDyadic.sum(afterEnergy,energyCount).subtract(SignedDyadic.sum(beforeEnergy,energyCount));
			var successor=base.future;List<Integer> allChanged=base.changedCells;
			if(futureRows!=null){
				allChanged=new ArrayList<>(base.changedCells);var included=new boolean[cellCount()];for(int cell:allChanged)included[cell]=true;
				for(int cell:changed){futureRows.set(cell,List.copyOf(changedRows[cell]));if(!included[cell])allChanged.add(cell);}
				successor=new LiquidCellInventory(base.future,java.util.Collections.unmodifiableList(futureRows),revision+1,null);
			}
			return new Plan(LiquidCellInventory.this,successor,allChanged,base.transfers,base.energyChange.add(change),
					mechanical?base.energyRoundoff.add(change.subtract(heat)):base.energyRoundoff,base.mechanicalHeat.add(heat));
		}
	}
	/** Signed local work goes directly to prepared owners with one exact remainder. */
	private Plan addMechanicalHeat(Plan base,Map<Integer,SignedDyadic> heatByCell){
		base.requireCurrent();if(heatByCell.isEmpty())return base;
		var updates=new PreparedEnergyRows(base,heatByCell.size());var total=SignedDyadic.ZERO;
		for(var entry:heatByCell.entrySet()){
			var heat=entry.getValue();if(heat==null)throw new IllegalArgumentException("Mechanical energy transfer is missing");if(heat.signum()==0)continue;
			var stocks=base.future.rows.get(entry.getKey());long mass=0;Stock largest=null;
			for(var stock:stocks){mass=Math.addExact(mass,stock.massUnits());if(stock.massUnits()>0&&(largest==null||stock.massUnits()>largest.massUnits()))largest=stock;}
			if(largest==null)throw new IllegalArgumentException("Mechanical heat requires actual liquid mass");
			var remainder=heat;double numerical=heat.toDouble();
			for(var stock:stocks)if(stock!=largest&&stock.massUnits()>0){var share=SignedDyadic.of(numerical*(stock.massUnits()/(double)mass));updates.add(stock.key(),share);remainder=remainder.subtract(share);}
			updates.add(largest.key(),remainder);total=total.add(heat);
		}
		return updates.finish(total,true);
	}
	private Plan addMechanicalHeatByStock(Plan base,Map<Key,SignedDyadic> heatByStock){
		if(heatByStock.isEmpty())return base;
		var updates=new PreparedEnergyRows(base,heatByStock.size());var total=SignedDyadic.ZERO;
		for(var entry:heatByStock.entrySet()){updates.add(entry.getKey(),entry.getValue());total=total.add(entry.getValue());}
		return updates.finish(total,true);
	}
	private static boolean samePresence(Stock before, Stock after) {
		if (before != null && before.massUnits() == 0) before = null;
		if (after != null && after.massUnits() == 0) after = null;
		if (before == null || after == null) return before == after;
		if (before.key().material() != after.key().material()) return false;
		var first = before.mixture(); var second = after.mixture();
		if (first == second) return true;
		if (first == null || second == null || first.entryCount() != second.entryCount()) return false;
		for (int entry = 0; entry < first.entryCount(); entry++)
			if (first.speciesHandle(entry) != second.speciesHandle(entry)) return false;
		return true;
	}
	private static void adjustPresence(Stock stock, int delta, int[] species, int[] materials) {
		if (stock == null || stock.massUnits() == 0) return;
		materials[stock.key().material()] += delta;
		if (stock.mixture() != null) for (int entry = 0; entry < stock.mixture().entryCount(); entry++)
			species[stock.mixture().speciesHandle(entry)] += delta;
	}
	private Stock normalize(Stock stock) {
		if (stock == null || stock.mixture() == null) return stock;
		if (chemistry == null) throw new IllegalArgumentException("Chemical liquid stock requires its registry");
		long residual = Math.subtractExact(stock.massUnits(), stock.mixture().speciesMassUnits(chemistry));
		return new Stock(stock.key(), stock.volume(), stock.massUnits(), stock.mixture().withMassResidualUnits(residual), stock.internalEnergy());
	}

	/** Partitions actual donor quantities over the bounded face transfers of one numerical pass. */
	public Plan planRoutes(LiquidTransportRoutes routes,EnergyPartitioner caloric) {
		return planRoutes(routes,caloric,null);
	}
	public Plan planRoutes(LiquidTransportRoutes routes,EnergyPartitioner caloric,TransportFinalizer finalizer) {

		var totals=new TransportTargets();
		var partitions=retainTransportDonors(routes,caloric,totals);
		var settledTransfers=receiveTransport(routes,partitions,totals);
		var quantities=finalizer==null?null:prepareTransportQuantities(totals,settledTransfers);
		if(finalizer!=null)finalizer.finish(quantities);
		return prepareTransport(totals,settledTransfers,quantities);
	}
	/** All donors retain their own original-generation remainder before any incoming merge. */
	private SourcePartition[] retainTransportDonors(LiquidTransportRoutes routes,EnergyPartitioner caloric,TransportTargets totals) {
		var partitions=new SourcePartition[routes.sourceCount()];
		var outflows=new Outflow[routes.sourceCount()];
		for(int donor=0;donor<routes.sourceCount();donor++) {
			var source=routes.source(donor);int start=routes.routeStart(donor),end=routes.routeEnd(donor);
			if(source.key().cell()>=cellCount()||stock(source.key())!=source)throw new IllegalArgumentException("Liquid transport donor is not an original owner");
			for(int r=start;r<end;r++)if(routes.targetCell(r)>=cellCount())throw new IllegalArgumentException("Liquid transport recipient is outside the grid");
			var outflow=outflows[donor]=prepareOutflow(routes,donor);
		}
		for(int donor=0;donor<routes.sourceCount();donor++){
			var source=routes.source(donor);var outflow=outflows[donor];
			var partition=partitions[donor]=outflow==null?null:partitionOutflow(source,outflow,caloric);
			totals.donor(source).retainSource(source,partition);
		}
		return partitions;
	}
	/** Recipients merge prepared shares in the original donor and face order. */
	private List<Transfer> receiveTransport(LiquidTransportRoutes routes,SourcePartition[] partitions,TransportTargets totals) {
		var transfers=new ArrayList<Transfer>();
		for(int donor=0;donor<routes.sourceCount();donor++) {
			var source=routes.source(donor);var partition=partitions[donor];int start=routes.routeStart(donor),end=routes.routeEnd(donor);
			for(int r=start;r<end;r++) {
				int part=r-start;var target=totals.owner(routes.targetCell(r),source.key().material(),routes.supportMask(r));
				long mass=partition==null?source.massUnits():partition.masses[part];
				if(partition==null)target.addTransport(source.volume(),mass,source.mixture(),source.internalEnergy());
				else target.addTransportShare(routes.volume(r),mass,source,partition.species,part,partition.energies[part]);
				transfers.add(new Transfer(routes.face(r),source.key(),target.key,routes.volume(r),mass));
			}
		}
		return List.copyOf(transfers);
	}
	/** Actual outgoing columns and one implicit source remainder; no retained route or stock is generated. */
	private record SourcePartition(SignedDyadic retainedVolume,long[] masses,long[][] species,double[] energies,int retained) { }
	private record Outflow(SignedDyadic retainedVolume,QuantityShares shares,int retained) { }
	private Outflow prepareOutflow(LiquidTransportRoutes routes,int donor){
		var source=routes.source(donor);int start=routes.routeStart(donor),count=routes.routeEnd(donor)-start;
		var outgoing=SignedDyadic.ZERO;int largest=0;
		for(int i=0;i<count;i++){
			var volume=routes.volume(start+i);outgoing=outgoing.add(volume);
			if(volume.compareTo(routes.volume(start+largest))>0)largest=i;
		}
		var retained=source.volume().subtract(outgoing);
		if(retained.signum()<0)throw new IllegalArgumentException("Liquid outflow exceeds the original stock volume");
		if(count==1&&retained.signum()==0)return null;
		boolean partial=retained.signum()>0;
		int remainder=partial?count:largest;
		var shares=new QuantityShares(routes,start,count,source.volume(),remainder,partial);
		return new Outflow(retained,shares,partial?count:-1);
	}
	private SourcePartition partitionOutflow(Stock source,Outflow outflow,EnergyPartitioner caloric){
		var shares=outflow.shares;
		long[] masses=shares.allocate(source.massUnits());
		// One material source has heat capacity proportional to its actual mass.
		int energyRemainder=0;
		for(int part=1;part<masses.length;part++)if(masses[part]>masses[energyRemainder])energyRemainder=part;
		var speciesShares=source.massUnits()==0?shares:new QuantityShares(masses,source.massUnits(),energyRemainder);
		long[][] species=new long[source.mixture()==null?0:source.mixture().entryCount()][];
		for(int entry=0;entry<species.length;entry++)species[entry]=speciesShares.allocate(source.mixture().amountAt(entry));
		var energies=energyShares(source,masses,species,energyRemainder,caloric);
		return new SourcePartition(outflow.retainedVolume,masses,species,energies,outflow.retained);
	}
	/** Actual face quantities provide the common mass and geometry inputs to momentum. */
	private TransportQuantities prepareTransportQuantities(TransportTargets totals,List<Transfer> transfers) {
		long[] beforeMass=new long[cellCount()],afterMass=new long[cellCount()];double[] afterVolume=new double[cellCount()];
		for(int cell=0;cell<cellCount();cell++){
			var quantities=cellQuantities(cell);beforeMass[cell]=afterMass[cell]=quantities.mass;afterVolume[cell]=quantities.volume.toDouble();
		}
		for(int cell:totals.cells){
			var quantities=totals.byCell[cell].quantities();afterMass[cell]=quantities.mass;afterVolume[cell]=quantities.volume.toDouble();
		}
		return new TransportQuantities(transfers,beforeMass,afterMass,afterVolume);
	}
	/** One ordered complete recipient row supplies momentum, publication and the next inventory generation. */
	private final class TransportRow {
		final ArrayList<Accumulator> owners;
		CellQuantities preparedQuantities;
		TransportRow(int cell){
			var initial=rows.get(cell);owners=new ArrayList<>(initial.size()+1);
			for(var stock:initial)owners.add(new Accumulator(stock.key(),stock));
		}
		int index(Key key){return index(key.material(),key.supportMask());}
		int index(int material,int support){
			int low=0,high=owners.size()-1;
			while(low<=high){int mid=(low+high)>>>1;var key=owners.get(mid).key;int order=Integer.compare(key.material(),material);if(order==0)order=Integer.compare(key.supportMask(),support);if(order<0)low=mid+1;else if(order>0)high=mid-1;else return mid;}
			return -low-1;
		}
		CellQuantities quantities(){
			if(preparedQuantities==null){
				var volume=SignedDyadic.ZERO;long mass=0;
				for(var owner:owners){volume=volume.add(owner.volume);mass=Math.addExact(mass,owner.mass);}
				preparedQuantities=new CellQuantities(volume,mass);
			}
			return preparedQuantities;
		}
	}
	/** Direct cell indexing and sorted row insertion establish each owner exactly once. */
	private final class TransportTargets {
		final TransportRow[] byCell=new TransportRow[cellCount()];
		final ArrayList<Integer> cells=new ArrayList<>();
		int ownerCount;
		int[] futureSpecies,futureMaterials;
		boolean geometryChanged;
		double[] beforeEnergy,afterEnergy;
		int energyCount;
		TransportRow row(int cell){
			var row=byCell[cell];if(row==null){byCell[cell]=row=new TransportRow(cell);cells.add(cell);ownerCount+=row.owners.size();}return row;
		}
		Accumulator donor(Stock source){
			var total=owner(source.key());
			if(total.donor)throw new IllegalArgumentException("Repeated liquid transport donor");
			total.donor=true;return total;
		}
		Accumulator owner(Key key){
			var row=row(key.cell());int at=row.index(key);if(at>=0)return row.owners.get(at);
			var total=new Accumulator(key,null);row.owners.add(-at-1,total);ownerCount++;return total;
		}
		Accumulator owner(int cell,int material,int support){
			var row=row(cell);int at=row.index(material,support);if(at>=0)return row.owners.get(at);
			var total=new Accumulator(new Key(cell,material,support),null);row.owners.add(-at-1,total);ownerCount++;return total;
		}
		/** Material endpoints and their exact energy accounting share one ordered row pass. */
		List<List<Stock>> prepareRows() {
			beforeEnergy=new double[ownerCount];afterEnergy=new double[ownerCount];
			var futureRows=new ArrayList<>(rows);
			for(int cell:cells) {
				var prepared=prepareRow(byCell[cell]);
				if(prepared!=null)futureRows.set(cell,prepared);
			}
			return java.util.Collections.unmodifiableList(futureRows);
		}
		private List<Stock> prepareRow(TransportRow target) {
			var row=new ArrayList<Stock>(target.owners.size());boolean changed=false;
			for(var total:target.owners) {
				if(!total.transportChanged){if(total.original!=null)row.add(total.original);continue;}
				var before=total.original;var after=total.preparedTransportStock();
				if(after!=null)row.add(after);
				if(before==after)continue;
				changed=true;
				if(!samePresence(before,after)) {
					if(futureSpecies==null){futureSpecies=presence.species.clone();futureMaterials=presence.materials.clone();}
					if(after!=null&&after.key().material()>=futureMaterials.length)futureMaterials=java.util.Arrays.copyOf(futureMaterials,after.key().material()+1);
					adjustPresence(before,-1,futureSpecies,futureMaterials);adjustPresence(after,1,futureSpecies,futureMaterials);
				}
				geometryChanged|=before==null?after!=null:after==null||!before.volume().equals(after.volume());
				beforeEnergy[energyCount]=before==null?0:before.internalEnergy();
				afterEnergy[energyCount++]=after==null?0:after.internalEnergy();
			}
			return changed?List.copyOf(row):null;
		}

	}
	/** Exact material quantities and numerical U are assembled into their final stored owners. */
	private Plan prepareTransport(TransportTargets totals,List<Transfer> transfers,TransportQuantities heat){
		var mechanicalHeat=addTransportHeat(totals,heat);
		var futureRows=totals.prepareRows();
		var future=new LiquidCellInventory(this,futureRows,revision+1,null);
		future.quantities=quantities.clone();
		for(int cell:totals.cells)future.quantities[cell]=totals.byCell[cell].quantities();
		future.totalVolume=totalVolume;future.totalMass=totalMass;
		if(totals.futureSpecies!=null)future.presence=new PresenceIndex(totals.futureSpecies,totals.futureMaterials);
		if(totals.geometryChanged){future.geometryRevision++;future.geometryIdentity=new Object();}
		// Only committed binary64 owners enter exact energy accounting. Numerical
		// partitioning and mixing roundoff is the endpoint difference after the
		// separately computed mechanical heat has been removed.
		var roundoff=SignedDyadic.sum(totals.afterEnergy,totals.energyCount).subtract(SignedDyadic.sum(totals.beforeEnergy,totals.energyCount)).subtract(mechanicalHeat);
		return new Plan(this,future,totals.cells,transfers,mechanicalHeat.add(roundoff),roundoff,mechanicalHeat);
	}
	private SignedDyadic addTransportHeat(TransportTargets totals,TransportQuantities heat){
		if(heat==null||heat.heatCount==0)return SignedDyadic.ZERO;
		for(int cell=0;cell<heat.heatByCell.length;cell++){
			var amount=heat.heatByCell[cell];if(amount==null||amount.signum()==0)continue;
			var row=totals.row(cell);var owners=row.owners;
			long mass=row.quantities().mass;Accumulator largest=null;
			for(var owner:owners)if(owner.mass>0&&(largest==null||owner.mass>largest.mass))largest=owner;
			if(largest==null)throw new IllegalArgumentException("Mechanical heat requires actual liquid mass");
			var remaining=amount;double numerical=amount.toDouble();
			for(var owner:owners)if(owner!=largest&&owner.mass>0){
				var part=SignedDyadic.of(numerical*(owner.mass/(double)mass));owner.addTransportEnergy(part.toDouble());remaining=remaining.subtract(part);
			}
			largest.addTransportEnergy(remaining.toDouble());
		}
		return SignedDyadic.sum(heat.heatTerms,heat.heatCount);
	}

	/** Floating geometric preferences with exact inventory subtraction and a final local remainder. */
	public static SignedDyadic[] allocateVolumes(SignedDyadic amount, SignedDyadic[] capacities, double[] preferences) {
		if (capacities.length != preferences.length) throw new IllegalArgumentException("Liquid geometric weights differ from supports");
		var remaining = capacities.clone(); var result = new SignedDyadic[capacities.length];
		java.util.Arrays.fill(result, SignedDyadic.ZERO); var unassigned = amount;
		var available = SignedDyadic.ZERO; for (var capacity : capacities) available = available.add(capacity);
		if (amount.signum() < 0 || amount.compareTo(available) > 0) throw new IllegalArgumentException("Liquid geometric shares exceed source volume");
		for (int pass = 0; unassigned.signum() > 0 && pass <= capacities.length; pass++) {
			double totalWeight = 0; int largest = -1;
			for (int i = 0; i < remaining.length; i++) if (remaining[i].signum() > 0) {
				if (!(preferences[i] >= 0 && Double.isFinite(preferences[i]))) throw new IllegalArgumentException("Invalid liquid geometric preference");
				totalWeight += preferences[i]; if (largest < 0 || remaining[i].compareTo(remaining[largest]) > 0) largest = i;
			}
			if (largest < 0) break;
			boolean uniform = totalWeight == 0, saturated = false;
			if (uniform) for (var capacity : remaining) totalWeight += capacity.toDouble();
			double proposal = unassigned.toDouble();
			for (int i = 0; i < remaining.length; i++) if (remaining[i].signum() > 0 && unassigned.signum() > 0) {
				double weight = uniform ? remaining[i].toDouble() : preferences[i];
				var part = SignedDyadic.of(proposal * (weight / totalWeight));
				if (part.compareTo(remaining[i]) >= 0) { part = remaining[i]; saturated = true; }
				if (part.compareTo(unassigned) > 0) part = unassigned;
				result[i] = result[i].add(part); remaining[i] = remaining[i].subtract(part); unassigned = unassigned.subtract(part);
			}
			if (!saturated && unassigned.signum() > 0) {
				var part = unassigned.compareTo(remaining[largest]) < 0 ? unassigned : remaining[largest];
				result[largest] = result[largest].add(part); remaining[largest] = remaining[largest].subtract(part); unassigned = unassigned.subtract(part);
			}
		}
		if (unassigned.signum() != 0) throw new IllegalStateException("Liquid geometric shares did not close");
		return result;
	}
	public static long[] allocateQuantities(long total, SignedDyadic[] weights) {
		var sum = SignedDyadic.ZERO; int largest = 0;
		for (int i = 0; i < weights.length; i++) { sum = sum.add(weights[i]); if (weights[i].compareTo(weights[largest]) > 0) largest = i; }
		return allocateQuantities(total,weights,sum,largest);
	}
	private static long[] allocateQuantities(long total,SignedDyadic[] weights,SignedDyadic sum,int largest){
		return new QuantityShares(weights,sum,largest).allocate(total);
	}
	/** Species travel with actual accepted mass; native zero-mass geometry retains its volume shares. */
	public static long[] allocateSpecies(long total,long[] masses,SignedDyadic[] volumes){
		long mass=0;int largest=0;
		for(int part=0;part<masses.length;part++){mass=Math.addExact(mass,masses[part]);if(masses[part]>masses[largest])largest=part;}
		return mass==0?allocateQuantities(total,volumes):new QuantityShares(masses,mass,largest).allocate(total);
	}
	/** Numerical geometry supplies one ratio column; integer inventories close at the largest share. */
	private static final class QuantityShares {
		final double[] fractions;
		final int largest;
		QuantityShares(SignedDyadic[] weights,SignedDyadic total,int largest){
			this.largest=largest;fractions=new double[weights.length];double volume=total.toDouble();
			if(!(volume>0&&Double.isFinite(volume)))throw new IllegalArgumentException("Liquid shares require positive finite volume");
			for(int i=0;i<weights.length;i++)if(i!=largest){
				if(weights[i].signum()<0)throw new IllegalArgumentException("Liquid share volume must be nonnegative");
				fractions[i]=weights[i].toDouble()/volume;
			}
		}
		QuantityShares(LiquidTransportRoutes routes,int start,int count,SignedDyadic total,int largest,boolean retained){
			this.largest=largest;fractions=new double[count+(retained?1:0)];double volume=total.toDouble();
			if(!(volume>0&&Double.isFinite(volume)))throw new IllegalArgumentException("Liquid shares require positive finite volume");
			for(int i=0;i<count;i++)if(i!=largest)fractions[i]=routes.volume(start+i).toDouble()/volume;
		}
		QuantityShares(long[] masses,long total,int largest){
			this.largest=largest;fractions=new double[masses.length];
			for(int part=0;part<masses.length;part++)if(part!=largest)fractions[part]=masses[part]/(double)total;
		}
		long[] allocate(long total){
			if(total<0)throw new IllegalArgumentException("Liquid quantity must be nonnegative");
			var result=new long[fractions.length];long remaining=total;
			for(int i=0;i<result.length;i++)if(i!=largest){
				// Positive casts take the integer floor. Limiting by the still
				// owned quantity also covers binary64 rounding near Long.MAX_VALUE.
				long part=Math.min(remaining,(long)(total*fractions[i]));
				result[i]=part;remaining-=part;
			}
			result[largest]=remaining;return result;
		}
	}
	public static double[] partitionEnergy(Stock source,long[] masses,ChemicalMixtureRecord[] mixtures,EnergyPartitioner caloric) {
		if(masses.length==0||mixtures.length!=masses.length)throw new IllegalArgumentException("Liquid energy partition columns differ");
		long totalMass=0;int largest=0;
		for(int i=0;i<masses.length;i++){
			long mass=masses[i];if(mass<0)throw new IllegalArgumentException("Negative liquid energy partition mass");
			totalMass=Math.addExact(totalMass,mass);if(mass>masses[largest])largest=i;
		}
		if(totalMass!=source.massUnits())throw new IllegalArgumentException("Liquid energy partition mass differs from source");
		var species=new long[source.mixture()==null?0:source.mixture().entryCount()][masses.length];
		for(int entry=0;entry<species.length;entry++)for(int part=0;part<masses.length;part++)
			if(mixtures[part]!=null)species[entry][part]=mixtures[part].amount(source.mixture().speciesHandle(entry));
		return energyShares(source,masses,species,largest,caloric);
	}
	/** Numerical U has one remainder owner for every caller and caloric provider. */
	private static double[] energyShares(Stock source,long[] masses,long[][] species,int largest,EnergyPartitioner caloric){
		var result=new double[masses.length];
		if(source.massUnits()==0)return result;
		if(caloric!=null)caloric.partition(source,masses,species,largest,result);
		else for(int i=0;i<masses.length;i++)if(i!=largest&&masses[i]>0)
			result[i]=source.internalEnergy()*(masses[i]/(double)source.massUnits());
		double sum=0,error=0;
		for (int i = 0; i < masses.length; i++) if (i != largest) {
			if(!Double.isFinite(result[i])||masses[i]==0&&result[i]!=0)throw new IllegalArgumentException("Invalid carried liquid energy share");
			double corrected=result[i]-error,next=sum+corrected;error=(next-sum)-corrected;sum=next;
		}
		result[largest]=source.internalEnergy()-sum;
		if(!Double.isFinite(result[largest]))throw new IllegalArgumentException("Liquid energy remainder is not finite");
		return result;
	}
	private final class Accumulator {
		final Key key; SignedDyadic volume = SignedDyadic.ZERO, energy = SignedDyadic.ZERO; long mass,residualMass; boolean chemical;
		double transportEnergy,transportEnergyError;
		final Stock original;
		boolean donor,transportChanged;
		ChemicalMixtureRecord firstMixture;
		int[] speciesHandles;long[] speciesAmounts;int speciesCount;
		Accumulator(Key key) { this.key = key;original=null; }
		Accumulator(Key key,Stock initial){
			this.key=key;original=initial;
			if(initial!=null){
				volume=initial.volume();mass=initial.massUnits();
				transportEnergy=initial.internalEnergy();
				firstMixture=initial.mixture();chemical=firstMixture!=null;residualMass=chemical?firstMixture.massResidualUnits():mass;
			}
		}
		/** Subtracts accepted original outflow before any incoming source is merged. */
		void retainSource(Stock source,SourcePartition partition){
			transportChanged=true;
			if(partition==null||partition.retained<0){
				volume=SignedDyadic.ZERO;mass=residualMass=0;transportEnergy=transportEnergyError=0;
				chemical=false;firstMixture=null;return;
			}
			int remainder=partition.retained;
			volume=partition.retainedVolume;mass=partition.masses[remainder];transportEnergy=partition.energies[remainder];
			if(source.mixture()==null)return;
			boolean changed=false;
			for(int entry=0;entry<partition.species.length;entry++)changed|=partition.species[entry][remainder]!=source.mixture().amountAt(entry);
			if(!changed)return;
			prepareSpecies(0);speciesCount=0;
			for(int entry=0;entry<partition.species.length;entry++){
				long amount=partition.species[entry][remainder];if(amount==0)continue;
				speciesHandles[speciesCount]=source.mixture().speciesHandle(entry);speciesAmounts[speciesCount++]=amount;
			}
		}
		void add(SignedDyadic amount, long mass, ChemicalMixtureRecord mixture, SignedDyadic energy) {
			this.energy=this.energy.add(energy);addQuantities(amount,mass,mixture);
		}
		void addTransport(SignedDyadic amount,long mass,ChemicalMixtureRecord mixture,double carriedEnergy){
			addTransportEnergy(carriedEnergy);volume=volume.add(amount);this.mass=Math.addExact(this.mass,mass);
			addComposition(mixture);
		}
		/** Source integer columns go directly to the recipient; only stored owners need a mixture object. */
		void addTransportShare(SignedDyadic amount,long mass,Stock source,long[][] species,int part,double energy){
			addTransportEnergy(energy);volume=volume.add(amount);this.mass=Math.addExact(this.mass,mass);
			if(source.mixture()==null)return;
			prepareSpecies(source.mixture().entryCount());chemical=true;
			for(int entry=0;entry<species.length;entry++)if(species[entry][part]>0)addSpecies(source.mixture().speciesHandle(entry),species[entry][part]);
		}
		void addTransportEnergy(double value){
			transportChanged=true;
			double corrected=value-transportEnergyError,next=transportEnergy+corrected;
			transportEnergyError=(next-transportEnergy)-corrected;transportEnergy=next;
		}
		private void addQuantities(SignedDyadic amount,long mass,ChemicalMixtureRecord mixture){
			volume = volume.add(amount); this.mass = Math.addExact(this.mass, mass);
			residualMass=Math.addExact(residualMass,mixture==null?mass:mixture.massResidualUnits());
			addComposition(mixture);
		}
		private void addComposition(ChemicalMixtureRecord mixture){
			if(mixture==null)return;
			if(!chemical){chemical=true;firstMixture=mixture;return;}
			prepareSpecies(mixture.entryCount());
			for(int i=0;i<mixture.entryCount();i++)addSpecies(mixture.speciesHandle(i),mixture.amountAt(i));
		}
		private void prepareSpecies(int incoming){
			if(speciesHandles!=null)return;
			speciesCount=firstMixture==null?0:firstMixture.entryCount();int capacity=Math.max(4,speciesCount+incoming);
			speciesHandles=firstMixture==null?new int[capacity]:java.util.Arrays.copyOf(firstMixture.copySpeciesHandles(),capacity);
			speciesAmounts=firstMixture==null?new long[capacity]:java.util.Arrays.copyOf(firstMixture.copyAmounts(),capacity);
		}
		private void addSpecies(int handle,long amount){
				int index=java.util.Arrays.binarySearch(speciesHandles,0,speciesCount,handle);
				if(index>=0){speciesAmounts[index]=Math.addExact(speciesAmounts[index],amount);return;}
				index=-index-1;
				if(speciesCount==speciesHandles.length){speciesHandles=java.util.Arrays.copyOf(speciesHandles,2*speciesCount);speciesAmounts=java.util.Arrays.copyOf(speciesAmounts,2*speciesCount);}
				System.arraycopy(speciesHandles,index,speciesHandles,index+1,speciesCount-index);
				System.arraycopy(speciesAmounts,index,speciesAmounts,index+1,speciesCount-index);
				speciesHandles[index]=handle;speciesAmounts[index]=amount;speciesCount++;
		}
		Edit edit() {
			if (volume.signum() == 0) return new Edit(key, null);
			return new Edit(key,preparedStock(),energy);
		}
		Stock preparedStock(){
			return preparedStock(energy.toDouble(),false);
		}
		Stock preparedTransportStock(){
			if(original!=null&&mass==original.massUnits()&&volume.equals(original.volume())
					&&retainsOriginalSpecies())
				return transportEnergy==original.internalEnergy()?original:new Stock(original,transportEnergy);
			return preparedStock(transportEnergy,true);
		}
		private boolean retainsOriginalSpecies(){
			var mixture=original.mixture();if(!chemical)return mixture==null;
			if(mixture==null)return false;
			if(speciesHandles==null){
				if(firstMixture==mixture)return true;
				if(firstMixture.entryCount()!=mixture.entryCount())return false;
				for(int i=0;i<mixture.entryCount();i++)if(firstMixture.speciesHandle(i)!=mixture.speciesHandle(i)||firstMixture.amountAt(i)!=mixture.amountAt(i))return false;
			}else{
				if(speciesCount!=mixture.entryCount())return false;
				for(int i=0;i<speciesCount;i++)if(speciesHandles[i]!=mixture.speciesHandle(i)||speciesAmounts[i]!=mixture.amountAt(i))return false;
			}
			return true;
		}
		private Stock preparedStock(double storedEnergy,boolean transported){
			if(volume.signum()==0)return null;
			ChemicalMixtureRecord mixture = null;
			if (chemical) {
				long residual=residualMass;
				if(transported){
					long represented=0;
					if(speciesHandles==null)represented=firstMixture.speciesMassUnits(chemistry);
					else for(int i=0;i<speciesCount;i++)represented=Math.addExact(represented,chemistry.speciesMassUnits(speciesHandles[i],speciesAmounts[i]));
					residual=Math.subtractExact(mass,represented);
				}
				mixture=speciesHandles==null?firstMixture.withMassResidualUnits(residual)
						:new ChemicalMixtureRecord(java.util.Arrays.copyOf(speciesHandles,speciesCount),java.util.Arrays.copyOf(speciesAmounts,speciesCount),residual);
			}
			return new Stock(key,volume,mass,mixture,storedEnergy);
		}
	}
}
