package mmm.fluid;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import mmm.fluid.FluidSurfaceInput.Piece;
import mmm.fluid.FluidSurfaceInput.Plane;
import mmm.inventory.LiquidCellInventory;

/** Projects one committed inventory and its matching PLIC onto bounded display pieces. */
final class FluidSurfaceProjection {
  static FluidSurfaceInput capture(
      int sx,
      int sy,
      int sz,
      float h,
      FluidMaterialRegistry registry,
      LiquidCellInventory.Snapshot inventory,
      FixedGeometrySnapshot fixed,
      FlipPressurePhaseGeometry phase) {
    var pieces = new ArrayList<Piece>();
    for (int cell = 0; cell < inventory.cellCount(); cell++) {
      var row = inventory.rows().get(cell);
      if (row.isEmpty()) continue;
      for (int support : LiquidCellInventory.connectedSupports(fixed.mask(cell))) {
        var stocks = new ArrayList<LiquidCellInventory.Stock>();
        double total = 0;
        for (var stock : row)
          if (stock.key().supportMask() == support) {
            stocks.add(stock);
            total += stock.volumeFraction();
          }
        if (stocks.isEmpty()) continue;
        stocks.sort(
            Comparator.<LiquidCellInventory.Stock>comparingDouble(
                    stock -> -registry.material(stock.key().material()).referenceDensity())
                .thenComparingInt(stock -> stock.key().material()));
        var base = new ArrayList<Piece>();
        var polyhedra = new ArrayList<List<SurfacePolyhedron.Face>>();
        double geometricVolume = 0;
        for (int micro = 0; micro < 8; micro++)
          if ((support & 1 << micro) != 0) {
            var plane = phase.componentPlane(cell, micro);
            if (plane == null || plane.fraction() <= 0) continue;
            List<Plane> clips =
                plane.fraction() >= 1
                    ? List.of()
                    : List.of(new Plane(plane.nx(), plane.ny(), plane.nz(), plane.offset()));
            var piece = new Piece(cell, micro, 0, 0, clips);
            if (stocks.size() == 1 && clips.isEmpty()) {
              var stock = stocks.getFirst();
              pieces.add(
                  new Piece(
                      cell,
                      micro,
                      stock.key().material(),
                      registry.phaseHandle(stock.key().material()),
                      clips));
              continue;
            }
            var polyhedron = SurfacePolyhedron.build(piece);
            double volume = SurfacePolyhedron.volume(polyhedron);
            if (volume > 0) {
              if (stocks.size() == 1) {
                var stock = stocks.getFirst();
                pieces.add(
                    new Piece(
                        cell,
                        micro,
                        stock.key().material(),
                        registry.phaseHandle(stock.key().material()),
                        clips));
                continue;
              }
              base.add(piece);
              polyhedra.add(polyhedron);
              geometricVolume += volume;
            }
          }
        double lower = 0, cumulative = 0;
        for (int at = 0; at < stocks.size(); at++) {
          var stock = stocks.get(at);
          cumulative += stock.volumeFraction();
          double upper =
              at == stocks.size() - 1 ? 1 : height(polyhedra, geometricVolume * cumulative / total);
          for (var original : base) {
            var clips = new ArrayList<>(original.clips());
            if (lower > 0) clips.add(new Plane(0, -1, 0, -lower));
            if (upper < 1) clips.add(new Plane(0, 1, 0, upper));
            var piece =
                new Piece(
                    cell,
                    original.micro(),
                    stock.key().material(),
                    registry.phaseHandle(stock.key().material()),
                    clips);
            if (SurfacePolyhedron.volume(SurfacePolyhedron.build(piece)) > 0) pieces.add(piece);
          }
          lower = upper;
        }
      }
    }
    return new FluidSurfaceInput(sx, sy, sz, h, pieces);
  }

  private static double height(List<List<SurfacePolyhedron.Face>> polyhedra, double target) {
    double lower = 0, upper = 1;
    for (int iteration = 0; iteration < 24; iteration++) {
      double middle = .5 * (lower + upper), volume = 0;
      var plane = new Plane(0, 1, 0, middle);
      for (var polyhedron : polyhedra)
        volume += SurfacePolyhedron.volume(SurfacePolyhedron.clip(polyhedron, plane));
      if (volume < target) lower = middle;
      else upper = middle;
    }
    return .5 * (lower + upper);
  }
}
