package mmm.fluid;

import java.util.List;
import mmm.inventory.LiquidCellInventory;

/** Immutable material display fields and their matching bounded liquid geometry. */
public final class FluidMaterialRenderSnapshot {
  private final int sizeX, sizeY, sizeZ;
  private final float cellSize;
  private final List<FluidMaterial> materials;
  private final List<String> phaseGroups;
  private final byte[][] materialFractions, phaseFractions;
  private final FluidSurfaceInput geometry;

  private FluidMaterialRenderSnapshot(
      int sx,
      int sy,
      int sz,
      float h,
      List<FluidMaterial> materials,
      List<String> phases,
      byte[][] materialFractions,
      byte[][] phaseFractions,
      FluidSurfaceInput geometry) {
    sizeX = sx;
    sizeY = sy;
    sizeZ = sz;
    cellSize = h;
    this.materials = List.copyOf(materials);
    phaseGroups = List.copyOf(phases);
    if (geometry.sizeX() != sx
        || geometry.sizeY() != sy
        || geometry.sizeZ() != sz
        || geometry.cellSize() != h)
      throw new IllegalArgumentException("Display geometry dimensions differ");
    this.geometry = geometry;
    int cells = geometry.grid().cellCount();
    this.materialFractions = copyFields(materialFractions, materials.size(), cells);
    this.phaseFractions = copyFields(phaseFractions, phases.size(), cells);
    if(geometry.pieces() instanceof CompactFluidSurface compact)compact.validatePalette(materials.size(),phases.size(),null);
    else for (var piece : geometry.pieces())
      if (piece.material() >= materials.size() || piece.phase() >= phases.size())
        throw new IllegalArgumentException("Display geometry palette differs");
  }

  static FluidMaterialRenderSnapshot capture(
      int sx,
      int sy,
      int sz,
      float h,
      FluidMaterialRegistry registry,
      LiquidCellInventory.Snapshot inventory,
      FluidSurfaceInput geometry) {
    int cells = inventory.cellCount();
    byte[][] materials = new byte[registry.size()][cells],
        phases = new byte[registry.phaseCount()][cells];
    for (int cell = 0; cell < cells; cell++) {
      var row = inventory.rows().get(cell);
      double total = 0;
      for (var stock : row) total += stock.volumeFraction();
      if (total == 0) continue;
      double[] material = new double[materials.length], phase = new double[phases.length];
      for (var stock : row) {
        double amount = stock.volumeFraction() / total;
        material[stock.key().material()] += amount;
        phase[registry.phaseHandle(stock.key().material())] += amount;
      }
      quantize(material, materials, cell);
      quantize(phase, phases, cell);
    }
    return new FluidMaterialRenderSnapshot(
        sx, sy, sz, h, registry.materials(), registry.phaseGroups(), materials, phases, geometry);
  }

  public static FluidMaterialRenderSnapshot fromDisplayFields(
      int sx,
      int sy,
      int sz,
      float h,
      List<FluidMaterial> materials,
      List<String> phases,
      byte[][] materialFractions,
      byte[][] phaseFractions,
      FluidSurfaceInput geometry) {
    return new FluidMaterialRenderSnapshot(
        sx, sy, sz, h, materials, phases, materialFractions, phaseFractions, geometry);
  }

  public int sizeX() {
    return sizeX;
  }

  public int sizeY() {
    return sizeY;
  }

  public int sizeZ() {
    return sizeZ;
  }

  public float cellSize() {
    return cellSize;
  }

  public int cellCount() {
    return geometry.grid().cellCount();
  }

  public List<FluidMaterial> materials() {
    return materials;
  }

  public List<String> phaseGroups() {
    return phaseGroups;
  }

  public FluidSurfaceInput geometry() {
    return geometry;
  }

  public byte[] copyMaterialFractions(int material) {
    return materialFractions[material].clone();
  }

  public byte[] copyPhaseFractions(int phase) {
    return phaseFractions[phase].clone();
  }

  public int materialFraction(int material, int cell) {
    return Byte.toUnsignedInt(materialFractions[material][cell]);
  }

  public int phaseFraction(int phase, int cell) {
    return Byte.toUnsignedInt(phaseFractions[phase][cell]);
  }

  private static byte[][] copyFields(byte[][] fields, int count, int cells) {
    if (fields.length != count) throw new IllegalArgumentException("Display palette differs");
    var result = new byte[count][];
    for (int i = 0; i < count; i++) {
      if (fields[i].length != cells)
        throw new IllegalArgumentException("Display field dimensions differ");
      result[i] = fields[i].clone();
    }
    return result;
  }

  private static void quantize(double[] amounts, byte[][] fields, int cell) {
    int assigned = 0;
    double[] remainder = new double[amounts.length];
    for (int i = 0; i < amounts.length; i++) {
      int value = (int) Math.floor(255 * amounts[i]);
      fields[i][cell] = (byte) value;
      assigned += value;
      remainder[i] = 255 * amounts[i] - value;
    }
    for (int left = 255 - assigned; left > 0; left--) {
      int best = 0;
      for (int i = 1; i < amounts.length; i++) if (remainder[i] > remainder[best]) best = i;
      fields[best][cell]++;
      remainder[best] = -1;
    }
  }
}
