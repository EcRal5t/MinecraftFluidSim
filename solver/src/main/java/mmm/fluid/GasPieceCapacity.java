package mmm.fluid;

import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.List;

/** Allocates authoritative component capacity over its floating-point dry geometry. */
final class GasPieceCapacity {
    record Piece(int id, double normalizedVolume, double cubicMeters, double roundingErrorBound) { }
    private GasPieceCapacity() { }
    static List<Piece> capture(int cell, int support, SignedDyadic available, GasComplementGeometry geometry) {
        if (geometry == null || available == null || available.signum() < 0)
            throw new IllegalArgumentException("Gas piece capacity input is invalid");
        if (available.signum() == 0) return List.of();
        var selected = new ArrayList<GasComplementGeometry.Piece>(8);
        double total = 0; int pivot = 0;
        for (int micro = 0; micro < 8; micro++) if ((support & 1 << micro) != 0) {
            var piece = geometry.piece(8 * cell + micro);
            if (piece == null) continue;
            if (selected.isEmpty() || piece.normalizedVolume() > selected.get(pivot).normalizedVolume()) pivot = selected.size();
            selected.add(piece); total += piece.normalizedVolume();
        }
        if (!(total > 0)) throw new GasTopologyBlockedException("Positive gas capacity has no dry geometry at cell " + cell);
        double raw = available.toDouble(), target = raw * geometry.cellVolumeCubicMeters();
        double[] normalized = new double[selected.size()], physical = new double[selected.size()];
        double assigned = 0, physicalAssigned = 0;
        for (int i = 0; i < selected.size(); i++) if (i != pivot) {
            normalized[i] = raw * (selected.get(i).normalizedVolume() / total);
            physical[i] = normalized[i] * geometry.cellVolumeCubicMeters();
            assigned += normalized[i]; physicalAssigned += physical[i];
        }
        normalized[pivot] = raw - assigned; physical[pivot] = target - physicalAssigned;
        var result = new ArrayList<Piece>(selected.size());
        for (int i = 0; i < selected.size(); i++) {
            if (!(physical[i] > 0 && Double.isFinite(physical[i])))
                throw new GasTopologyBlockedException("Positive gas piece capacity is not representable at cell " + cell);
            double error = 16 * Math.ulp(target);
            result.add(new Piece(selected.get(i).id(), normalized[i], physical[i], error));
        }
        return List.copyOf(result);
    }
}
