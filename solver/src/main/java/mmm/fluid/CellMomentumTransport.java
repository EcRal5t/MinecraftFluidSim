package mmm.fluid;

import mmm.inventory.LiquidCellInventory;
import mmm.units.SignedDyadic;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** One donor pass shares the liquid quantity transfers and their local mixing heat. */
final class CellMomentumTransport {
    record Prepared(MacMomentumState momentum, Map<Integer, SignedDyadic> heatByCell,
                    CellMacStage.WallImpulse wallImpulse) { }

    private final FluidDomain domain;
    private final long[][] massFlux;
    private final double[] heat;
    private final int[] anchors;
    private final MacMomentumAdvection[] components = new MacMomentumAdvection[3];
    private long[] before, after;
    private double[] fallbackVolume;

    CellMomentumTransport(FluidDomain domain) {
        this.domain = domain;
        massFlux = new long[][]{new long[domain.mac.velocityX.length],
                new long[domain.mac.velocityY.length], new long[domain.mac.velocityZ.length]};
        heat = new double[domain.cellCount];
        anchors = new int[domain.cellCount];
        for (int axis = 0; axis < 3; axis++) {
            components[axis] = new MacMomentumAdvection(domain.sizeX, domain.sizeY, domain.sizeZ, axis);
        }
    }

    Prepared prepare(CellMacStage.PreparedLiquid liquid) {
        return prepare(liquid, new float[][]{domain.mac.velocityX, domain.mac.velocityY, domain.mac.velocityZ});
    }

    Prepared prepare(CellMacStage.PreparedLiquid liquid, float[][] initial) {
        if (before == null) {
            before = new long[domain.cellCount];
            after = new long[domain.cellCount];
            fallbackVolume = new double[domain.cellCount];
        }
        cellMass(liquid.sourceInventory(), before);
        cellMass(liquid.inventory().future(), after);
        for (int cell = 0; cell < fallbackVolume.length; cell++) {
            fallbackVolume[cell] = liquid.inventory().future().exactCellVolume(cell).toDouble();
        }
        return prepare(liquid.volumeFlux(), liquid.inventory().transfers(), before, after, fallbackVolume, initial);
    }

    Prepared prepare(ConservativeLiquidFlux flux, LiquidCellInventory.TransportQuantities quantities) {
        return prepare(flux, quantities.transfers(), quantities.beforeMass(), quantities.afterMass(),
                quantities.afterVolume(), new float[][]{domain.mac.velocityX, domain.mac.velocityY, domain.mac.velocityZ});
    }

    private Prepared prepare(ConservativeLiquidFlux flux, List<LiquidCellInventory.Transfer> transfers,
                             long[] beforeMass, long[] afterMass, double[] volume, float[][] initial) {
        Arrays.fill(heat, 0);
        double[][] result = new double[3][];
        for (var column : massFlux) Arrays.fill(column, 0);
        for (var transfer : transfers) {
            if (transfer.massUnits() <= 0) continue;
            int face = transfer.face(), axis = flux.axis[face], right = flux.right[face];
            int x = right % domain.sizeX, y = right / domain.sizeX % domain.sizeY;
            int z = right / (domain.sizeX * domain.sizeY);
            int index = (z * (domain.sizeY + (axis == 1 ? 1 : 0)) + y)
                    * (domain.sizeX + (axis == 0 ? 1 : 0)) + x;
            massFlux[axis][index] += transfer.massUnits() * (transfer.source().cell() == flux.left[face] ? 1 : -1);
        }
        for (int axis = 0; axis < 3; axis++) {
            var component = components[axis];
            boolean[] constrained = domain.cellMacStage.constrainedNormalFaces(axis);
            result[axis] = component.transport(beforeMass, afterMass, massFlux, initial[axis]);
            component.prepareMergeAnchors(afterMass, anchors, domain.pressureSystem.solidFaces,
                    domain.geometry.cutCells, constrained);
            component.mixUnresolved(volume, anchors, domain.geometry.cutCells.openFaceField(axis),
                    domain.pressureSystem.phaseGeometry(), domain.geometry.cutCells, constrained);
            component.addHeatToCells(afterMass, heat);
        }
        var walls = domain.cellMacStage.stopWallMomentum(result, afterMass, heat, null);
        var exactHeat = new TreeMap<Integer, SignedDyadic>();
        for (int cell = 0; cell < heat.length; cell++) {
            if (heat[cell] > 0) exactHeat.put(cell, SignedDyadic.of(heat[cell]));
        }
        return new Prepared(new MacMomentumState(domain.sizeX, domain.sizeY, domain.sizeZ,
                result[0], result[1], result[2]), Map.copyOf(exactHeat), walls);
    }

    private void cellMass(LiquidCellInventory inventory, long[] result) {
        Arrays.fill(result, 0);
        for (var stock : inventory.physicalStocks()) {
            int cell = stock.key().cell();
            result[cell] = Math.addExact(result[cell], stock.massUnits());
        }
    }
}
