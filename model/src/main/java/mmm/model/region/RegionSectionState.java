package mmm.model.region;

import mmm.inventory.LiquidCellInventory;
import mmm.units.SignedDyadic;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** One Minecraft-aligned collision slice, its liquid stocks and its uniquely owned MAC faces. */
public record RegionSectionState(RegionStateLayout layout, UUID regionId, String dimensionId, long checkpointGeneration,
		int sectionX,int sectionY,int sectionZ,int localStartX,int localStartY,int localStartZ,int sizeX,int sizeY,int sizeZ,
		List<LiquidCellInventory.Stock> liquidStocks,List<FaceMomentum> faceMomentum,byte[] solidMask,List<RegionExtension> extensions,long checksum) {
	public record FaceMomentum(int axis,int face,double kilogramMetersPerSecond) implements Comparable<FaceMomentum> {
		public FaceMomentum {
			if (axis < 0 || axis > 2 || face < 0 || !Double.isFinite(kilogramMetersPerSecond)) throw new IllegalArgumentException("Invalid persisted MAC face");
		}
		public int compareTo(FaceMomentum other) { int order = Integer.compare(axis,other.axis); return order == 0 ? Integer.compare(face,other.face) : order; }
	}
	public RegionSectionState {
		Objects.requireNonNull(layout); Objects.requireNonNull(regionId); Objects.requireNonNull(dimensionId);
		if (checkpointGeneration <= 0 || sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || localStartX < 0 || localStartY < 0 || localStartZ < 0
				|| solidMask.length != Math.multiplyExact(Math.multiplyExact(sizeX,sizeY),sizeZ)) throw new IllegalArgumentException("Invalid section shape");
		liquidStocks = List.copyOf(liquidStocks); faceMomentum = List.copyOf(faceMomentum); solidMask = solidMask.clone(); extensions = List.copyOf(extensions);
		LiquidCellInventory.Key previous = null;
		for (var stock : liquidStocks) { if (previous != null && previous.compareTo(stock.key()) >= 0) throw new IllegalArgumentException("Unsorted or repeated liquid key"); previous = stock.key(); }
		FaceMomentum previousFace = null;
		for (var face : faceMomentum) { if (previousFace != null && previousFace.compareTo(face) >= 0) throw new IllegalArgumentException("Unsorted or repeated MAC face"); previousFace = face; }
	}
	@Override public byte[] solidMask() { return solidMask.clone(); }
	public int stockCount() { return liquidStocks.size(); }
	/** Existing manifest capacity headers count physical inventory records. */
	public int markerCount() { return stockCount(); }
	public SignedDyadic exactVolume() { var total = SignedDyadic.ZERO; for (var stock : liquidStocks) total = total.add(stock.volume()); return total; }
	public long massUnits() { long total = 0; for (var stock : liquidStocks) total = Math.addExact(total,stock.massUnits()); return total; }
	public boolean hasChemicalState() { return layout.requiresChemistry() || liquidStocks.stream().anyMatch(stock -> stock.mixture() != null); }
	public SectionKey key() { return new SectionKey(sectionX,sectionY,sectionZ); }
	public record SectionKey(int x,int y,int z) implements Comparable<SectionKey> {
		public int compareTo(SectionKey other) { int order = Integer.compare(x,other.x); if (order == 0) order = Integer.compare(y,other.y); return order == 0 ? Integer.compare(z,other.z) : order; }
	}
}
