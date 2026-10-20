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
	public record Stock(Key key, SignedDyadic volume, long massUnits, ChemicalMixtureRecord mixture,
	                    double internalEnergy, boolean indicator) {
		public Stock {
			if (internalEnergy == 0) internalEnergy = 0;
			if (key == null || volume == null || volume.signum() <= 0 || !(volume.toDouble() > 0)
					|| !Double.isFinite(volume.toDouble()) || massUnits < 0 || !Double.isFinite(internalEnergy)
					|| massUnits == 0 && internalEnergy != 0)
				throw new IllegalArgumentException("Invalid liquid cell stock");
		}
		public double volumeFraction() { return volume.toDouble(); }
		public double massKilograms() { return Mass.kilograms(massUnits); }
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
	/** One face transfer or the retained portion of a numerical transport pass. */
	public record TransportRoute(int face,Key source,Key target,SignedDyadic volume) {
		public TransportRoute {
			if(source==null||target==null||source.material()!=target.material()||volume==null||volume.signum()<0
					||face < -1||face==-1&&!source.equals(target)||face>=0&&source.cell()==target.cell())
				throw new IllegalArgumentException("Invalid liquid transport route");
		}
	}
	/** One original owner and its complete accepted shares, prepared together by transport. */
	public record SourceRoutes(Stock source,List<TransportRoute> routes) {
		public SourceRoutes {
			if(source==null||routes==null||routes.isEmpty())throw new IllegalArgumentException("Liquid source routes are missing");
			routes=List.copyOf(routes);
			for(var route:routes)if(!route.source().equals(source.key()))throw new IllegalArgumentException("Liquid route differs from its donor");
		}
	}
	/** A caloric model writes non-remainder shares; the inventory assigns the source U remainder. */
	@FunctionalInterface
	public interface EnergyPartitioner {
		void partition(Stock source,long[] masses,long[][] speciesAmounts,int remainder,double[] energies);
	}
	/** Actual quantity settlement for the mechanical operator, before target U is stored. */
	public record TransportQuantities(List<Transfer> transfers,long[] beforeMass,long[] afterMass,double[] afterVolume) { }
	@FunctionalInterface
	public interface TransportFinalizer { Map<Integer,SignedDyadic> finish(TransportQuantities quantities); }

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
		public SignedDyadic energyChange() { return energyChange; }
		public SignedDyadic energyRoundoff() { return energyRoundoff; }
		public SignedDyadic mechanicalHeat() { return mechanicalHeat; }
		public Plan withMechanicalHeat(Map<Integer, SignedDyadic> heatByCell) { return owner.addMechanicalHeat(this, heatByCell); }
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
	private LiquidCellInventory(LiquidCellInventory source, List<List<Stock>> rows, long revision) {
		this.chemistry = source.chemistry; this.rows = rows; this.revision = revision;
		presence = source.presence;
		if(rows==source.rows){quantities=source.quantities;totalVolume=source.totalVolume;totalMass=source.totalMass;}
		else {
			quantities=source.quantities.clone();
			for(int cell=0;cell<rows.size();cell++)if(rows.get(cell)!=source.rows.get(cell))quantities[cell]=null;
		}
		geometryRevision = source.geometryRevision; geometryIdentity = source.geometryIdentity;
		if (rows == source.rows) { ordinals = source.ordinals; physical = source.physical; }
	}
	public int cellCount() { return rows.size(); }
	public long revision() { return revision; }
	/** Geometry identity survives immutable handoff and energy-only edits. */
	public boolean hasSameGeometry(Snapshot snapshot) { return snapshot != null && geometryIdentity == snapshot.geometryIdentity; }
	public long geometryRevision() { return geometryRevision; }
	public ChemicalRegistry chemicalRegistry() { return chemistry; }
	public boolean hasSpecies(int handle) { return presence.species[handle] != 0; }
	public boolean hasMaterial(int handle) { return handle < presence.materials.length && presence.materials[handle] != 0; }
	public Snapshot snapshot() { return new Snapshot(this); }
	public LiquidCellInventory copy() { return new LiquidCellInventory(this, rows, revision); }
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
				if (before != null) result.add(before.volume(), before.massUnits(), before.mixture(), SignedDyadic.of(before.internalEnergy()), before.indicator());
				return result;
			});
			total.add(addition.volume(), addition.massUnits(), addition.mixture(), SignedDyadic.of(addition.internalEnergy()), addition.indicator());
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
		int currentCell=-1;var energyChange=SignedDyadic.ZERO;
		for (int i = 0; i < energies.length; i++) {
			Stock stock = stocks.get(i);
			if(Double.doubleToLongBits(stock.internalEnergy())==Double.doubleToLongBits(energies[i]))continue;
			int cell=stock.key().cell();
			if(futureRows==null)futureRows=new ArrayList<>(rows);
			if(currentCell!=cell){
				if(row!=null)futureRows.set(currentCell,List.copyOf(row));
				currentCell=cell;row=new ArrayList<>(rows.get(cell));changed.add(cell);
			}
			row.set(findOrdinal(row,stock.key()),new Stock(stock.key(),stock.volume(),stock.massUnits(),stock.mixture(),energies[i],stock.indicator()));
			energyChange=energyChange.add(SignedDyadic.of(energies[i]).subtract(SignedDyadic.of(stock.internalEnergy())));
		}
		if(row!=null)futureRows.set(currentCell,List.copyOf(row));
		var successor=new LiquidCellInventory(this,futureRows==null?rows:java.util.Collections.unmodifiableList(futureRows),revision+1);
		successor.quantities=quantities;successor.totalVolume=totalVolume;successor.totalMass=totalMass;
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
		var future = new LiquidCellInventory(this, List.copyOf(futureRows), revision + 1);
		if(totalVolume!=null)future.totalVolume=totalVolume.add(volumeChange);
		if(totalMass!=null)future.totalMass=Math.addExact(totalMass,massChange);
		if (futureSpecies != null) future.presence = new PresenceIndex(futureSpecies, futureMaterials);
		if (geometryChanged) { future.geometryRevision++; future.geometryIdentity = new Object(); }
		return new Plan(this, future, new ArrayList<>(changed.keySet()), transfers, energyChange, roundoff, SignedDyadic.ZERO);
	}
	/** Local kinetic mixing heat updates the prepared owners while sharing their validated quantities. */
	private Plan addMechanicalHeat(Plan base, Map<Integer, SignedDyadic> heatByCell) {
		base.requireCurrent(); if (heatByCell.isEmpty()) return base;
		var futureRows=new ArrayList<>(base.future.rows);var changed=new ArrayList<>(base.changedCells);var included=new boolean[cellCount()];
		for(int cell:changed)included[cell]=true;
		var totalHeat=SignedDyadic.ZERO;var energyChange=SignedDyadic.ZERO;var roundoff=SignedDyadic.ZERO;
		for (var entry : heatByCell.entrySet()) {
			var heat = entry.getValue();
			if (heat == null || heat.signum() < 0) throw new IllegalArgumentException("Mechanical heat must be nonnegative");
			if (heat.signum() == 0) continue;
			int cell=entry.getKey();var stocks=base.future.rows.get(cell);long totalMass=0;int largest=-1;
			for(int i=0;i<stocks.size();i++){long mass=stocks.get(i).massUnits();totalMass=Math.addExact(totalMass,mass);if(mass>0&&(largest<0||mass>stocks.get(largest).massUnits()))largest=i;}
			if (totalMass == 0) throw new IllegalArgumentException("Mechanical heat requires actual liquid mass");
			var shares = new SignedDyadic[stocks.size()]; var remainder = heat;
			for (int i = 0; i < shares.length; i++) if (i != largest&&stocks.get(i).massUnits()>0) { shares[i] = SignedDyadic.of(heat.toDouble() * (stocks.get(i).massUnits() / (double) totalMass)); remainder = remainder.subtract(shares[i]); }
			shares[largest] = remainder;
			var row=new ArrayList<>(stocks);
			for (int i = 0; i < shares.length; i++) if(shares[i]!=null&&shares[i].signum()>0){
				var stock=stocks.get(i);var before=SignedDyadic.of(stock.internalEnergy());var exact=before.add(shares[i]);double stored=exact.toDouble();
				row.set(i,new Stock(stock.key(),stock.volume(),stock.massUnits(),stock.mixture(),stored,stock.indicator()));
				var delta=SignedDyadic.of(stored).subtract(before);energyChange=energyChange.add(delta);roundoff=roundoff.add(delta.subtract(shares[i]));
			}
			futureRows.set(cell,List.copyOf(row));if(!included[cell]){included[cell]=true;changed.add(cell);}totalHeat=totalHeat.add(heat);
		}
		if(totalHeat.signum()==0)return base;
		var successor=new LiquidCellInventory(base.future,java.util.Collections.unmodifiableList(futureRows),revision+1);
		successor.quantities=base.future.quantities;successor.totalVolume=base.future.totalVolume;successor.totalMass=base.future.totalMass;
		return new Plan(this,successor,changed,base.transfers,
				base.energyChange.add(energyChange),base.energyRoundoff.add(roundoff),base.mechanicalHeat.add(totalHeat));
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
		return new Stock(stock.key(), stock.volume(), stock.massUnits(), stock.mixture().withMassResidualUnits(residual), stock.internalEnergy(), stock.indicator());
	}

	/** Partitions actual donor quantities over the bounded face transfers of one numerical pass. */
	public Plan planRoutes(List<SourceRoutes> routes,EnergyPartitioner caloric) {
		return planRoutes(routes,caloric,null);
	}
	public Plan planRoutes(List<SourceRoutes> routes,EnergyPartitioner caloric,TransportFinalizer finalizer) {
		var partitions=new ArrayList<RoutePartition>(routes.size());
		var totals=new TransportTargets();var transfers=new ArrayList<Transfer>();
		// Donors publish retained shares before incoming quantities are accumulated.
		for(var group:routes)partitions.add(prepareSourcePartition(group,totals,caloric));
		for(var partition:partitions)if(partition.changed)appendActualTransfers(partition,totals,transfers);
		var heat=finalizer==null?Map.<Integer,SignedDyadic>of():finalizer.finish(prepareTransportQuantities(transfers));
		return prepareTransport(totals,transfers,heat);
	}
	/** One donor closes V, integer quantities and caloric U before recipient assembly. */
	private RoutePartition prepareSourcePartition(SourceRoutes group,TransportTargets totals,EnergyPartitioner caloric) {

		var partition=new RoutePartition(group);
		Stock source=partition.source;var destinations=partition.routes;
		if(source.key().cell()>=cellCount()||stock(source.key())!=source)throw new IllegalArgumentException("Liquid transport donor is not an original owner");
		for(var route:destinations)if(route.target().cell()>=cellCount())throw new IllegalArgumentException("Liquid transport recipient is outside the grid");
		if(destinations.size()==1&&destinations.get(0).face()<0&&destinations.get(0).volume().equals(source.volume()))return partition;
		partition.changed=true;
		if(destinations.size()==1){
			if(!destinations.get(0).volume().equals(source.volume()))throw new IllegalArgumentException("Liquid transport routes differ from the original stock volume");
			partition.masses=new long[]{source.massUnits()};
			partition.energies=new double[]{source.internalEnergy()};
			totals.donor(source);return partition;
		}
		var weights = new SignedDyadic[destinations.size()];
		var sourceVolume=SignedDyadic.ZERO;int largest=0;
		for (int i = 0; i < weights.length; i++) {
			weights[i]=destinations.get(i).volume();sourceVolume=sourceVolume.add(weights[i]);
			if(weights[i].compareTo(weights[largest])>0)largest=i;
		}
		if(!sourceVolume.equals(source.volume()))throw new IllegalArgumentException("Liquid transport routes differ from the original stock volume");
		var shares=new QuantityShares(weights,source.volume(),largest);
		long[] masses=partition.masses=shares.allocate(source.massUnits());
		long[][] species = partition.species=new long[source.mixture() == null ? 0 : source.mixture().entryCount()][];
		for(int i=0;i<species.length;i++)species[i]=shares.allocate(source.mixture().amountAt(i));
		// Caloric transport uses floating shares; the largest share retains the
		// numerical remainder and the committed endpoint ledger records rounding.
		partition.energies=energyShares(source,masses,species,largest,caloric);
		var retained=totals.donor(source);
		for (int i = 0; i < destinations.size(); i++) {
			var route=destinations.get(i);
			if(route.face()<0)retained.addTransportShare(route.volume(),masses[i],source,species,i,partition.energies[i]);
		}
		return partition;
	}
	private void appendActualTransfers(RoutePartition partition,TransportTargets totals,List<Transfer> transfers) {

		var source=partition.source;
		for(int i=0;i<partition.routes.size();i++){
			var route=partition.routes.get(i);if(route.face()<0)continue;
			var target=totals.owner(route.target());
			var energy=partition.energies[i];long mass=partition.masses[i];
			if(partition.species==null)target.addTransport(route.volume(),mass,source.mixture(),energy,source.indicator());
			else target.addTransportShare(route.volume(),mass,source,partition.species,i,energy);
			transfers.add(new Transfer(route.face(),route.source(),route.target(),route.volume(),mass));
		}
	}
	/** Actual face quantities provide the common mass and geometry inputs to momentum. */
	private TransportQuantities prepareTransportQuantities(List<Transfer> transfers) {
		long[] beforeMass=new long[cellCount()],afterMass=new long[cellCount()];double[] afterVolume=new double[cellCount()];
		for(int cell=0;cell<cellCount();cell++){
			var quantities=cellQuantities(cell);beforeMass[cell]=quantities.mass;afterVolume[cell]=quantities.volume.toDouble();
		}
		System.arraycopy(beforeMass,0,afterMass,0,beforeMass.length);
		for(var transfer:transfers){
			int from=transfer.source().cell(),to=transfer.target().cell();
			afterMass[from]=Math.subtractExact(afterMass[from],transfer.massUnits());afterMass[to]=Math.addExact(afterMass[to],transfer.massUnits());
			double volume=transfer.volume().toDouble();afterVolume[from]-=volume;afterVolume[to]+=volume;
		}
		// Momentum uses floating geometry; exact recipient capacities remain in
		// the stock rows. A fully drained numerical cell has zero volume.
		for(int cell=0;cell<cellCount();cell++)afterVolume[cell]=Math.max(0,afterVolume[cell]);
		return new TransportQuantities(java.util.Collections.unmodifiableList(transfers),beforeMass,afterMass,afterVolume);
	}
	private static final class RoutePartition {
		final Stock source;final List<TransportRoute> routes;
		boolean changed;long[] masses;long[][] species;double[] energies;
		RoutePartition(SourceRoutes group){
			source=group.source();routes=group.routes();
		}
	}
	/** Direct cell indexing keeps all changed owners and heat recipients in one place. */
	private final class TransportTargets {
		final ArrayList<Accumulator>[] byCell;
		final ArrayList<Integer> cells=new ArrayList<>();
		@SuppressWarnings("unchecked") TransportTargets(){byCell=(ArrayList<Accumulator>[])new ArrayList<?>[cellCount()];}
		ArrayList<Accumulator> row(int cell){
			var row=byCell[cell];if(row==null){byCell[cell]=row=new ArrayList<>();cells.add(cell);}return row;
		}
		Accumulator find(Key key){
			var row=byCell[key.cell()];if(row!=null)for(var total:row)if(total.key.equals(key))return total;
			return null;
		}
		Accumulator donor(Stock source){
			var key=source.key();
			if(find(key)!=null)throw new IllegalArgumentException("Repeated liquid transport donor");
			var total=new Accumulator(source);row(key.cell()).add(total);return total;
		}
		Accumulator owner(Key key){
			var total=find(key);if(total==null){total=new Accumulator(key,stock(key));row(key.cell()).add(total);}return total;
		}
	}
	/** Exact material quantities and numerical U are assembled into their final stored owners. */
	private Plan prepareTransport(TransportTargets totals,List<Transfer> transfers,Map<Integer,SignedDyadic> heat){
		var mechanicalHeat=addTransportHeat(totals,heat);
		var futureRows=new ArrayList<>(rows);
		int[] futureSpecies=null,futureMaterials=null;boolean geometryChanged=false;
		int ownersCount=0;for(int cell:totals.cells)ownersCount+=totals.byCell[cell].size();
		double[] beforeEnergy=new double[ownersCount],afterEnergy=new double[ownersCount];int energyIndex=0;
		for(int cell:totals.cells){
			var old=rows.get(cell);var owners=totals.byCell[cell];owners.sort(java.util.Comparator.comparing(total->total.key));
			var row=new ArrayList<Stock>(old.size()+owners.size());int original=0;
			for(var total:owners){
				var key=total.key;var before=total.original;var after=total.preparedTransportStock();
				while(original<old.size()&&old.get(original).key().compareTo(key)<0)row.add(old.get(original++));
				if(original<old.size()&&old.get(original).key().equals(key))original++;
				if(after!=null)row.add(after);
				if(!samePresence(before,after)){
					if(futureSpecies==null){futureSpecies=presence.species.clone();futureMaterials=presence.materials.clone();}
					if(after!=null&&after.key().material()>=futureMaterials.length)futureMaterials=java.util.Arrays.copyOf(futureMaterials,after.key().material()+1);
					adjustPresence(before,-1,futureSpecies,futureMaterials);adjustPresence(after,1,futureSpecies,futureMaterials);
				}
				geometryChanged|=before==null?after!=null:after==null||!before.volume().equals(after.volume());
				beforeEnergy[energyIndex]=before==null?0:before.internalEnergy();
				afterEnergy[energyIndex++]=after==null?0:after.internalEnergy();
			}
			while(original<old.size())row.add(old.get(original++));
			futureRows.set(cell,List.copyOf(row));
		}
		var future=new LiquidCellInventory(this,java.util.Collections.unmodifiableList(futureRows),revision+1);
		future.totalVolume=totalVolume;future.totalMass=totalMass;
		if(futureSpecies!=null)future.presence=new PresenceIndex(futureSpecies,futureMaterials);
		if(geometryChanged){future.geometryRevision++;future.geometryIdentity=new Object();}
		// Only committed binary64 owners enter exact energy accounting. Numerical
		// partitioning and mixing roundoff is the endpoint difference after the
		// separately computed mechanical heat has been removed.
		var roundoff=SignedDyadic.sum(afterEnergy).subtract(SignedDyadic.sum(beforeEnergy)).subtract(mechanicalHeat);
		return new Plan(this,future,totals.cells,transfers,mechanicalHeat.add(roundoff),roundoff,mechanicalHeat);
	}
	private SignedDyadic addTransportHeat(TransportTargets totals,Map<Integer,SignedDyadic> heat){
		if(heat.isEmpty())return SignedDyadic.ZERO;
		var sum=SignedDyadic.ZERO;
		for(var entry:heat.entrySet()){
			int cell=entry.getKey();var amount=entry.getValue();
			if(amount==null||amount.signum()<0)throw new IllegalArgumentException("Mechanical heat must be nonnegative");
			if(amount.signum()==0)continue;
			for(var stock:rows.get(cell))totals.owner(stock.key());
			var owners=totals.byCell[cell];
			long mass=0;Accumulator largest=null;
			for(var owner:owners){mass=Math.addExact(mass,owner.mass);if(owner.mass>0&&(largest==null||owner.mass>largest.mass))largest=owner;}
			if(largest==null)throw new IllegalArgumentException("Mechanical heat requires actual liquid mass");
			var remaining=amount;
			for(var owner:owners)if(owner!=largest&&owner.mass>0){
				var part=SignedDyadic.of(amount.toDouble()*(owner.mass/(double)mass));owner.addTransportEnergy(part.toDouble());remaining=remaining.subtract(part);
			}
			largest.addTransportEnergy(remaining.toDouble());sum=sum.add(amount);
		}
		return sum;
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
	/** One exact ratio preparation serves mass and every species column of a donor. */
	private static final class QuantityShares {
		final long[] numerator,denominator;
		final java.math.BigInteger[] wideNumerator,wideDenominator;
		final int largest;
		QuantityShares(SignedDyadic[] weights,SignedDyadic total,int largest){
			this.largest=largest;int size=weights.length;
			numerator=new long[size];denominator=new long[size];
			java.math.BigInteger[] top=null,bottom=null;
			for(int i=0;i<size;i++)if(i!=largest){
				var weight=weights[i];int common=Math.min(weight.exponent(),total.exponent());
				if(weight.fitsLongUnits(common)&&total.fitsLongUnits(common)){
					numerator[i]=weight.longUnitsExact(common);denominator[i]=total.longUnitsExact(common);
				}else{
					if(top==null){top=new java.math.BigInteger[size];bottom=new java.math.BigInteger[size];}
					top[i]=weight.significand().shiftLeft(weight.exponent()-common);
					bottom[i]=total.significand().shiftLeft(total.exponent()-common);
				}
			}
			wideNumerator=top;wideDenominator=bottom;
		}
		long[] allocate(long total){
			var result=new long[numerator.length];long assigned=0;
			for(int i=0;i<result.length;i++)if(i!=largest){
				result[i]=denominator[i]>0?Mass.portion(total,numerator[i],denominator[i])
						:java.math.BigInteger.valueOf(total).multiply(wideNumerator[i]).divide(wideDenominator[i]).longValueExact();
				assigned=Math.addExact(assigned,result[i]);
			}
			result[largest]=Math.subtractExact(total,assigned);return result;
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
		final Key key; SignedDyadic volume = SignedDyadic.ZERO, energy = SignedDyadic.ZERO; long mass,residualMass; boolean indicator, chemical;
		double transportEnergy,transportEnergyError;
		final Stock original;
		ChemicalMixtureRecord firstMixture;
		int[] speciesHandles;long[] speciesAmounts;int speciesCount;
		Accumulator(Key key) { this.key = key;original=null; }
		Accumulator(Stock donor){key=donor.key();original=donor;}
		Accumulator(Key key,Stock initial){
			this.key=key;original=initial;
			if(initial!=null){
				volume=initial.volume();mass=initial.massUnits();energy=SignedDyadic.of(initial.internalEnergy());indicator=initial.indicator();
				transportEnergy=initial.internalEnergy();
				firstMixture=initial.mixture();chemical=firstMixture!=null;residualMass=chemical?firstMixture.massResidualUnits():mass;
			}
		}
		void add(SignedDyadic amount, long mass, ChemicalMixtureRecord mixture, SignedDyadic energy, boolean indicator) {
			this.energy=this.energy.add(energy);addQuantities(amount,mass,mixture,indicator);
		}
		void addTransport(SignedDyadic amount,long mass,ChemicalMixtureRecord mixture,double carriedEnergy,boolean indicator){
			addTransportEnergy(carriedEnergy);addQuantities(amount,mass,mixture,indicator);
		}
		/** Source integer columns go directly to the recipient; only stored owners need a mixture object. */
		void addTransportShare(SignedDyadic amount,long mass,Stock source,long[][] species,int part,double energy){
			addTransportEnergy(energy);volume=volume.add(amount);this.mass=Math.addExact(this.mass,mass);indicator|=source.indicator();
			if(source.mixture()==null)return;
			prepareSpecies(source.mixture().entryCount());chemical=true;
			for(int entry=0;entry<species.length;entry++)if(species[entry][part]>0)addSpecies(source.mixture().speciesHandle(entry),species[entry][part]);
		}
		void addTransportEnergy(double value){
			double corrected=value-transportEnergyError,next=transportEnergy+corrected;
			transportEnergyError=(next-transportEnergy)-corrected;transportEnergy=next;
		}
		private void addQuantities(SignedDyadic amount,long mass,ChemicalMixtureRecord mixture,boolean indicator){
			volume = volume.add(amount); this.mass = Math.addExact(this.mass, mass); this.indicator |= indicator;
			residualMass=Math.addExact(residualMass,mixture==null?mass:mixture.massResidualUnits());
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
		Stock preparedTransportStock(){return preparedStock(transportEnergy,true);}
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
			return new Stock(key,volume,mass,mixture,storedEnergy,indicator);
		}
	}
}
