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
		}
		private Snapshot(LiquidCellInventory owner) {
			rows = owner.rows; ordinals = owner.ordinals; physical = owner.physical; geometryRevision = owner.geometryRevision; geometryIdentity = owner.geometryIdentity;
			inventoryRevision = owner.revision; validatedChemistry = owner.chemistry; validated = true;
			presence = owner.presence;
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

	/** One shared, directed, already capacity-constrained geometric flux. */
	public record FaceFlux(int face, int donorCell, int receiverCell, int donorSupport, int receiverSupport, int material,
	                       SignedDyadic volume) {
		public FaceFlux {
			if (face < 0 || donorCell < 0 || receiverCell < 0 || donorCell == receiverCell || material < 0
					|| donorSupport <= 0 || donorSupport > 255 || receiverSupport <= 0 || receiverSupport > 255
					|| volume == null || volume.signum() <= 0)
				throw new IllegalArgumentException("Invalid liquid face flux");
		}
	}

	/** The same transported integer mass is available to the MAC momentum operator. */
	public record Transfer(int face, Key source, Key target, SignedDyadic volume, long massUnits,
	                       ChemicalMixtureRecord mixture, SignedDyadic internalEnergy) { }
	/** One face transfer or the retained portion of a numerical transport pass. */
	public record TransportRoute(int face,Key source,Key target,SignedDyadic volume) {
		public TransportRoute {
			if(source==null||target==null||source.material()!=target.material()||volume==null||volume.signum()<0
					||face < -1||face==-1&&!source.equals(target)||face>=0&&source.cell()==target.cell())
				throw new IllegalArgumentException("Invalid liquid transport route");
		}
	}
	/** A bound caloric model partitions carried U after the actual integer shares are known. */
	@FunctionalInterface
	public interface EnergyPartitioner {
		SignedDyadic[] partition(Stock source,long[] masses,ChemicalMixtureRecord[] mixtures);
	}

	public static final class Plan {
		private final LiquidCellInventory owner, future;
		private final long sourceRevision;
		private final List<Integer> changedCells;
		private final List<Transfer> transfers;
		private final SignedDyadic volumeChange, energyChange, energyRoundoff, mechanicalHeat;
		private final long massChange;
		private Plan(LiquidCellInventory owner, LiquidCellInventory future, List<Integer> changedCells,
		             List<Transfer> transfers, SignedDyadic volumeChange, long massChange,
		             SignedDyadic energyChange, SignedDyadic energyRoundoff, SignedDyadic mechanicalHeat) {
			this.owner = owner; this.future = future; sourceRevision = owner.revision;
			this.changedCells = List.copyOf(changedCells); this.transfers = List.copyOf(transfers);
			this.volumeChange = volumeChange; this.massChange = massChange;
			this.energyChange = energyChange; this.energyRoundoff = energyRoundoff;
			this.mechanicalHeat = mechanicalHeat;
		}
		public LiquidCellInventory future() { return future; }
		public List<Integer> changedCells() { return changedCells; }
		public List<Transfer> transfers() { return transfers; }
		public SignedDyadic volumeChange() { return volumeChange; }
		public long massChange() { return massChange; }
		public SignedDyadic energyChange() { return energyChange; }
		public SignedDyadic energyRoundoff() { return energyRoundoff; }
		public SignedDyadic mechanicalHeat() { return mechanicalHeat; }
		public Plan withMechanicalHeat(Map<Integer, SignedDyadic> heatByCell) { return owner.addMechanicalHeat(this, heatByCell); }
		public void requireCurrent() {
			if (owner.revision != sourceRevision || future.revision != sourceRevision + 1)
				throw new IllegalStateException("Liquid cell inventory changed after preparation");
		}
		public void commit() { requireCurrent(); owner.rows = future.rows; owner.ordinals = future.ordinals; owner.physical = future.physical; owner.presence = future.presence; owner.geometryRevision = future.geometryRevision; owner.geometryIdentity = future.geometryIdentity; owner.revision++; }
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

	public LiquidCellInventory(int cellCount, ChemicalRegistry chemistry) {
		if (cellCount <= 0) throw new IllegalArgumentException("Liquid cell count must be positive");
		this.chemistry = chemistry;
		presence = new PresenceIndex(new int[chemistry == null ? 0 : chemistry.species().size()], new int[0]);
		var empty = new ArrayList<List<Stock>>(cellCount);
		for (int cell = 0; cell < cellCount; cell++) empty.add(List.of());
		rows = List.copyOf(empty);
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
	public List<Stock> physicalStocksInCell(int cell) { return rows.get(cell).stream().filter(stock -> stock.massUnits() > 0).toList(); }
	public Stock stock(Key key) {
		for (var stock : rows.get(key.cell())) if (stock.key().equals(key)) return stock;
		return null;
	}
	public SignedDyadic exactCellVolume(int cell) {
		var total = SignedDyadic.ZERO;
		for (var stock : rows.get(cell)) total = total.add(stock.volume());
		return total;
	}
	public SignedDyadic exactVolume() {
		var total = SignedDyadic.ZERO; for (int cell = 0; cell < cellCount(); cell++) total = total.add(exactCellVolume(cell)); return total;
	}
	public long massUnits() {
		long total = 0; for (var row : rows) for (var stock : row) total = Math.addExact(total, stock.massUnits()); return total;
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
		return new Plan(this,successor,changed,List.of(),SignedDyadic.ZERO,0,energyChange,SignedDyadic.ZERO,SignedDyadic.ZERO);
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
		if (futureSpecies != null) future.presence = new PresenceIndex(futureSpecies, futureMaterials);
		if (geometryChanged) { future.geometryRevision++; future.geometryIdentity = new Object(); }
		return new Plan(this, future, new ArrayList<>(changed.keySet()), transfers, volumeChange, massChange, energyChange, roundoff, SignedDyadic.ZERO);
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
		return new Plan(this,successor,changed,base.transfers,base.volumeChange,base.massChange,
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

	/** Material shares are allocated once per source cell; the caller has constrained shared fluxes to future pore capacities. */
	public Plan planTransport(List<FaceFlux> fluxes, int sizeX, int sizeY, int[] solidMasks) {
		return planTransport(fluxes,sizeX,sizeY,solidMasks,null);
	}
	public Plan planTransport(List<FaceFlux> fluxes,int sizeX,int sizeY,int[] solidMasks,EnergyPartitioner caloric) {
		if (sizeX <= 0 || sizeY <= 0 || cellCount() % (sizeX * sizeY) != 0 || solidMasks.length != cellCount())
			throw new IllegalArgumentException("Liquid transport grid does not match inventory");
		var outgoing = new LinkedHashMap<Long, List<FaceFlux>>();
		for (var flux : fluxes) {
			validateFace(flux, sizeX, sizeY, solidMasks);
			outgoing.computeIfAbsent(component(flux.donorCell(), flux.donorSupport()), ignored -> new ArrayList<>()).add(flux);
		}
		var routes = new LinkedHashMap<Key, List<Route>>();
		for (var entry : outgoing.entrySet()) {
			int cell = (int) (entry.getKey() >>> 8), support = (int) (entry.getKey() & 255);
			var sources = stocksInCell(cell).stream().filter(stock -> stock.key().supportMask() == support).toList();
			var remaining = new SignedDyadic[sources.size()];
			for (int i = 0; i < sources.size(); i++) { remaining[i] = sources.get(i).volume(); routes.put(sources.get(i).key(), new ArrayList<>()); }
			for (var flux : entry.getValue()) {
				var materialSupply = remaining.clone();
				for (int i = 0; i < sources.size(); i++) if (sources.get(i).key().material() != flux.material()) materialSupply[i] = SignedDyadic.ZERO;
				var volumes = splitVolume(flux.volume(), materialSupply);
				for (int i = 0; i < sources.size(); i++) if (volumes[i].signum() > 0) {
					var source = sources.get(i); remaining[i] = remaining[i].subtract(volumes[i]);
					routes.get(source.key()).add(new Route(flux.face(), new Key(flux.receiverCell(), source.key().material(), flux.receiverSupport()), volumes[i]));
				}
			}
			for (int i = 0; i < sources.size(); i++) if (remaining[i].signum() > 0)
				routes.get(sources.get(i).key()).add(new Route(-1, sources.get(i).key(), remaining[i]));
		}
		var paths=new ArrayList<TransportRoute>();
		for(var entry:routes.entrySet())for(var route:entry.getValue())
			paths.add(new TransportRoute(route.face(),entry.getKey(),route.target(),route.volume()));
		return planRoutes(paths,caloric);
	}

	/** Partitions actual donor quantities over the bounded face transfers of one numerical pass. */
	public Plan planRoutes(List<TransportRoute> paths,EnergyPartitioner caloric) {
		var routes=new LinkedHashMap<Key,List<TransportRoute>>();
		for(var path:paths){
			if(path.volume().signum()==0)continue;
			if(path.source().cell()>=cellCount()||path.target().cell()>=cellCount()||stock(path.source())==null)
				throw new IllegalArgumentException("Liquid transport route has no original stock or recipient cell");
			routes.computeIfAbsent(path.source(),ignored->new ArrayList<>()).add(path);
		}
		var totals = new LinkedHashMap<Key, Accumulator>();
		for (var key : routes.keySet()) totals.put(key, new Accumulator(key));
		var transfers = new ArrayList<Transfer>();
		for (var entry : routes.entrySet()) {
			Stock source = stock(entry.getKey()); var destinations = entry.getValue();
			var weights = new SignedDyadic[destinations.size()];
			var sourceVolume=SignedDyadic.ZERO;
			for (int i = 0; i < weights.length; i++) { weights[i] = destinations.get(i).volume(); sourceVolume=sourceVolume.add(weights[i]); }
			if(!sourceVolume.equals(source.volume()))throw new IllegalArgumentException("Liquid transport routes differ from the original stock volume");
			long[] masses = allocateQuantities(source.massUnits(), weights);
			long[][] species = new long[source.mixture() == null ? 0 : source.mixture().entryCount()][];
			for (int i = 0; i < species.length; i++) species[i] = allocateQuantities(source.mixture().amountAt(i), weights);
			var mixtures=new ChemicalMixtureRecord[destinations.size()];
			if(mixtures.length==1)mixtures[0]=source.mixture();
			else for(int i=0;i<mixtures.length;i++)mixtures[i]=mixturePart(source.mixture(),species,i,masses[i]);
			SignedDyadic[] energies = partitionEnergy(source,masses,mixtures,caloric);
			for (int i = 0; i < destinations.size(); i++) {
				var route = destinations.get(i); var mixture = mixtures[i];
				var target = totals.computeIfAbsent(route.target(), key -> {
					var result = new Accumulator(key); var existing = stock(key); if (existing != null) result.add(existing.volume(), existing.massUnits(), existing.mixture(), SignedDyadic.of(existing.internalEnergy()), existing.indicator()); return result;
				});
				target.add(route.volume(), masses[i], mixture, energies[i], source.indicator());
				if(route.face()>=0)transfers.add(new Transfer(route.face(),route.source(),route.target(),route.volume(),masses[i],mixture,energies[i]));
			}
		}
		var plan = prepareTransport(totals,transfers);
		if (plan.massChange() != 0 || plan.volumeChange().signum() != 0 || !plan.energyChange().equals(plan.energyRoundoff()))
			throw new IllegalStateException("Closed liquid transport did not conserve its inventory");
		return plan;
	}
	/** Already partitioned inventory quantities are assembled once, retaining their exact source-part residuals. */
	private Plan prepareTransport(Map<Key,Accumulator> totals,List<Transfer> transfers){
		var futureRows=new ArrayList<>(rows);var changed=new LinkedHashMap<Integer,ArrayList<Stock>>();
		int[] futureSpecies=null,futureMaterials=null;boolean geometryChanged=false;
		var volumeChange=SignedDyadic.ZERO;var energyChange=SignedDyadic.ZERO;var roundoff=SignedDyadic.ZERO;long massChange=0;
		for(var total:totals.values()){
			var key=total.key;var before=stock(key);var after=total.preparedStock();
			var row=changed.computeIfAbsent(key.cell(),cell->new ArrayList<>(rows.get(cell)));int i=0;
			while(i<row.size()&&row.get(i).key().compareTo(key)<0)i++;
			if(i<row.size()&&row.get(i).key().equals(key)){if(after==null)row.remove(i);else row.set(i,after);}
			else if(after!=null)row.add(i,after);
			if(!samePresence(before,after)){
				if(futureSpecies==null){futureSpecies=presence.species.clone();futureMaterials=presence.materials.clone();}
				if(after!=null&&after.key().material()>=futureMaterials.length)futureMaterials=java.util.Arrays.copyOf(futureMaterials,after.key().material()+1);
				adjustPresence(before,-1,futureSpecies,futureMaterials);adjustPresence(after,1,futureSpecies,futureMaterials);
			}
			geometryChanged|=before==null?after!=null:after==null||!before.volume().equals(after.volume());
			if(before!=null){volumeChange=volumeChange.subtract(before.volume());massChange=Math.subtractExact(massChange,before.massUnits());energyChange=energyChange.subtract(SignedDyadic.of(before.internalEnergy()));}
			if(after!=null){
				volumeChange=volumeChange.add(after.volume());massChange=Math.addExact(massChange,after.massUnits());
				var stored=SignedDyadic.of(after.internalEnergy());energyChange=energyChange.add(stored);roundoff=roundoff.add(stored.subtract(total.energy));
			}
		}
		for(var entry:changed.entrySet())futureRows.set(entry.getKey(),List.copyOf(entry.getValue()));
		var future=new LiquidCellInventory(this,java.util.Collections.unmodifiableList(futureRows),revision+1);
		if(futureSpecies!=null)future.presence=new PresenceIndex(futureSpecies,futureMaterials);
		if(geometryChanged){future.geometryRevision++;future.geometryIdentity=new Object();}
		return new Plan(this,future,new ArrayList<>(changed.keySet()),transfers,volumeChange,massChange,energyChange,roundoff,SignedDyadic.ZERO);
	}

	private record Route(int face, Key target, SignedDyadic volume) { }
	private static long component(int cell, int support) { return (long) cell << 8 | support; }
	private static void validateFace(FaceFlux flux, int sizeX, int sizeY, int[] masks) {
		if (flux.donorCell() >= masks.length || flux.receiverCell() >= masks.length) throw new IllegalArgumentException("Liquid face is outside the grid");
		int donor = flux.donorCell(), receiver = flux.receiverCell(), difference = receiver - donor;
		int axis = Math.abs(difference) == 1 && donor / sizeX == receiver / sizeX ? 0
				: Math.abs(difference) == sizeX && donor / (sizeX * sizeY) == receiver / (sizeX * sizeY) ? 1
				: Math.abs(difference) == sizeX * sizeY ? 2 : -1;
		if (axis < 0 || !connectedSupports(masks[donor]).contains(flux.donorSupport())
				|| !connectedSupports(masks[receiver]).contains(flux.receiverSupport()))
			throw new IllegalArgumentException("Liquid flux has no actual cut-connected support");
		boolean positive = difference > 0;
		for (int bit = 0; bit < 8; bit++) if (((bit >>> axis & 1) != 0) == positive
				&& (flux.donorSupport() & 1 << bit) != 0 && (flux.receiverSupport() & 1 << (bit ^ 1 << axis)) != 0) return;
		throw new IllegalArgumentException("Liquid flux crosses a closed cut face");
	}
	private static SignedDyadic[] splitVolume(SignedDyadic total, SignedDyadic[] capacities) {
		var available = SignedDyadic.ZERO; for (var capacity : capacities) available = available.add(capacity);
		if (total.compareTo(available) > 0) throw new IllegalArgumentException("Liquid face flux exceeds its source volume");
		var parts = new SignedDyadic[capacities.length]; var remainder = total;
		for (int i = 0; i < parts.length; i++) {
			parts[i] = total.equals(available) ? capacities[i] : SignedDyadic.of(total.toDouble() * (capacities[i].toDouble() / available.toDouble()));
			if (parts[i].compareTo(capacities[i]) > 0) parts[i] = capacities[i];
			remainder = remainder.subtract(parts[i]);
		}
		for (int i = 0; i < parts.length && remainder.signum() != 0; i++) {
			var room = remainder.signum() > 0 ? capacities[i].subtract(parts[i]) : parts[i];
			var magnitude = remainder.signum() < 0 ? remainder.negate() : remainder;
			var change = magnitude.compareTo(room) < 0 ? magnitude : room;
			if (remainder.signum() < 0) change = change.negate();
			parts[i] = parts[i].add(change); remainder = remainder.subtract(change);
		}
		if (remainder.signum() != 0) throw new IllegalStateException("Liquid volume allocation did not close");
		return parts;
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
		var result = new long[weights.length]; long assigned = 0;
		for (int i = 0; i < weights.length; i++) if (i != largest) { result[i] = Mass.portion(total, weights[i], sum); assigned = Math.addExact(assigned, result[i]); }
		result[largest] = Math.subtractExact(total, assigned); return result;
	}
	public static SignedDyadic[] partitionEnergy(Stock source,long[] masses,ChemicalMixtureRecord[] mixtures,EnergyPartitioner caloric) {
		if(masses.length==0||mixtures.length!=masses.length)throw new IllegalArgumentException("Liquid energy partition columns differ");
		long totalMass=0;for(long mass:masses){if(mass<0)throw new IllegalArgumentException("Negative liquid energy partition mass");totalMass=Math.addExact(totalMass,mass);}
		if(totalMass!=source.massUnits())throw new IllegalArgumentException("Liquid energy partition mass differs from source");
		var result=caloric==null?splitEnergy(source,masses):caloric.partition(source,masses,mixtures);
		if(result==null||result.length!=masses.length)throw new IllegalArgumentException("Liquid energy partition result differs");
		var total=SignedDyadic.ZERO;
		for(int i=0;i<result.length;i++){
			if(result[i]==null||masses[i]==0&&result[i].signum()!=0)throw new IllegalArgumentException("Geometric liquid tail carried energy");
			total=total.add(result[i]);
		}
		if(!total.equals(SignedDyadic.of(source.internalEnergy())))throw new IllegalArgumentException("Liquid energy partition did not conserve source U");
		return result;
	}
	private static SignedDyadic[] splitEnergy(Stock source, long[] masses) {
		var result = new SignedDyadic[masses.length]; int largest = 0;
		for (int i = 1; i < masses.length; i++) if (masses[i] > masses[largest]) largest = i;
		var remainder = SignedDyadic.of(source.internalEnergy());
		for (int i = 0; i < masses.length; i++) if (i != largest) {
			result[i] = masses[i] == 0 ? SignedDyadic.ZERO : SignedDyadic.of(source.internalEnergy() * (masses[i] / (double) source.massUnits()));
			remainder = remainder.subtract(result[i]);
		}
		result[largest] = remainder; return result;
	}
	private ChemicalMixtureRecord mixturePart(ChemicalMixtureRecord source, long[][] amounts, int index, long mass) {
		if (source == null) return null;
		int count = 0; for (var shares : amounts) if (shares[index] > 0) count++;
		int[] handles = new int[count]; long[] quantities = new long[count]; int next = 0;
		for (int i = 0; i < amounts.length; i++) if (amounts[i][index] > 0) { handles[next] = source.speciesHandle(i); quantities[next++] = amounts[i][index]; }
		var mixture = new ChemicalMixtureRecord(handles, quantities, 0);
		return mixture.withMassResidualUnits(Math.subtractExact(mass, mixture.speciesMassUnits(chemistry)));
	}
	private final class Accumulator {
		final Key key; SignedDyadic volume = SignedDyadic.ZERO, energy = SignedDyadic.ZERO; long mass,residualMass; boolean indicator, chemical;
		final TreeMap<Integer, Long> species = new TreeMap<>();
		Accumulator(Key key) { this.key = key; }
		void add(SignedDyadic amount, long mass, ChemicalMixtureRecord mixture, SignedDyadic energy, boolean indicator) {
			volume = volume.add(amount); this.mass = Math.addExact(this.mass, mass); this.energy = this.energy.add(energy); this.indicator |= indicator;
			residualMass=Math.addExact(residualMass,mixture==null?mass:mixture.massResidualUnits());
			if (mixture != null) { chemical = true; for (int i = 0; i < mixture.entryCount(); i++) species.merge(mixture.speciesHandle(i), mixture.amountAt(i), Math::addExact); }
		}
		Edit edit() {
			if (volume.signum() == 0) return new Edit(key, null);
			return new Edit(key,preparedStock(),energy);
		}
		Stock preparedStock(){
			if(volume.signum()==0)return null;
			ChemicalMixtureRecord mixture = null;
			if (chemical) {
				int[] handles = new int[species.size()]; long[] quantities = new long[species.size()]; int next = 0;
				for (var entry : species.entrySet()) { handles[next] = entry.getKey(); quantities[next++] = entry.getValue(); }
				mixture = new ChemicalMixtureRecord(handles, quantities, residualMass);
			}
			return new Stock(key,volume,mass,mixture,energy.toDouble(),indicator);
		}
	}
}
