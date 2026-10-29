package mmm.fluid;

import java.util.List;
import mmm.chemistry.ExactRational;
import mmm.fluid.air.GasInventory;
import mmm.fluid.air.GasRegistry;
import mmm.units.SignedDyadic;

/** Local external gas transfers on the committed finite chamber geometry. */
final class DomainGasInventory {
  private DomainGasInventory() {}

  static int nodeAt(HostFluidState domain, double x, double y, double z) {
    if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return -1;
    double h = domain.config.cellSize();
    int cx = (int) Math.floor(x / h), cy = (int) Math.floor(y / h), cz = (int) Math.floor(z / h);
    if (cx < 0
        || cy < 0
        || cz < 0
        || cx >= domain.sizeX
        || cy >= domain.sizeY
        || cz >= domain.sizeZ) return -1;
    var frame = domain.gasState.frame;
    if (frame == null) return -1;
    double lx = x / h - cx, ly = y / h - cy, lz = z / h - cz;
    int micro = (lx >= .5 ? 1 : 0) + (ly >= .5 ? 2 : 0) + (lz >= .5 ? 4 : 0);
    int pieceId = 8 * (cx + domain.sizeX * (cy + domain.sizeY * cz)) + micro;
    int node = frame.nodeOfPiece(pieceId);
    if (node <= 0 || !(domain.gasState.network.availableVolume(node) > 0)) return -1;
    var piece = frame.geometry().piece(pieceId);
    if (piece == null) return -1;
    var plane = piece.dryPlane();
    if (plane != null && plane.nx() * lx + plane.ny() * ly + plane.nz() * lz <= plane.threshold())
      return -1;
    return node;
  }

  static FluidDomainContracts.GasEdit edit(
      HostFluidState domain,
      int node,
      String componentId,
      long nanos,
      double temperatureK,
      boolean extract) {
    if (nanos < 0 || !extract && !(Double.isFinite(temperatureK) && temperatureK > 0))
      throw new IllegalArgumentException(
          "Gas transfer requires a nonnegative amount and positive insertion temperature");
    var network = domain.gasState.network;
    if (domain.gasState.frame == null
        || node <= 0
        || node > network.nodeCount()
        || !(network.availableVolume(node) > 0))
      throw new IllegalArgumentException(
          "Gas transfer requires a finite chamber with positive capacity");
    for(var chamber:domain.gasState.frame.topology().chambers())if(chamber.minimumPiece()/8>=0
        && domain.gasState.frame.nodeOfPiece(chamber.minimumPiece())==node
        && !domain.groupAvailable(chamber.minimumPiece()/8))throw new IllegalStateException("Gas physical group is unavailable");
    var thermal = domain.thermalState;
    if (thermal == null || !thermal.gasModelBound())
      throw new IllegalStateException("Gas transfer requires a bound gas thermal model");
    var chamberCells = domain.gasState.frame.topology().chambers().stream()
        .filter(chamber -> domain.gasState.frame.nodeOfPiece(chamber.minimumPiece()) == node)
        .map(chamber -> new FluidDomainContracts.CellCandidate(chamber.minimumPiece() / 8, 0)).toList();
    if (domain.activityGroups.pendingInGroups(chamberCells))
      throw new IllegalStateException("Gas transfer requires pending phase motion to be committed");
    if (componentId != null) network.registry().component(componentId);
    GasInventory before = network.inventory(node), moved, after;
    double finalEnergy, carriedEnergy;
    if (extract) {
      long available =
          componentId == null
              ? before.standardVolumeNanos()
              : before.component(componentId) == null
                  ? 0
                  : before.component(componentId).standardVolumeNanos();
      long actual = Math.min(nanos, available);
      if (actual == 0) return new FluidDomainContracts.GasEdit(node, GasInventory.EMPTY, SignedDyadic.ZERO);
      var split =
          componentId == null
              ? before.splitTotal(actual)
              : before.splitComponent(componentId, actual);
      moved = split.first();
      after = split.second();
      if (moved.isEmpty())
        return new FluidDomainContracts.GasEdit(node, GasInventory.EMPTY, SignedDyadic.ZERO);
      double[] energies = thermal.partitionGasNodeEnergy(node, List.of(moved, after), network);
      carriedEnergy = energies[0];
      finalEnergy = energies[1];
    } else {
      moved =
          componentId == null
              ? network.registry().createAmbient(nanos)
              : network
                  .registry()
                  .createMixture(
                      nanos,
                      List.of(new GasRegistry.AmbientFraction(componentId, ExactRational.ONE)));
      if (moved.isEmpty())
        return new FluidDomainContracts.GasEdit(node, GasInventory.EMPTY, SignedDyadic.ZERO);
      after = before.merge(moved);
      carriedEnergy = thermal.gasEnergyAtTemperature(moved, temperatureK);
      finalEnergy =
          SignedDyadic.of(thermal.gasNodeEnergy(node, network))
              .add(SignedDyadic.of(carriedEnergy))
              .toDouble();
    }
    var ids = List.of(node);
    var inventories = List.of(after);
    double[] energies = {finalEnergy};
    var carried = SignedDyadic.of(carriedEnergy);
    var source = extract ? GasInventory.EMPTY : moved;
    var sink = extract ? moved : GasInventory.EMPTY;
    var inventoryPlan = network.planInventoryBatch(ids, inventories, source, sink);
    var energyPlan =
        thermal.planExternalGasInventoryEnergy(
            network,
            ids,
            inventories,
            energies,
            extract ? SignedDyadic.ZERO : carried,
            extract ? carried : SignedDyadic.ZERO);
    network.requireInventoryBatchCurrent(inventoryPlan);
    thermal.requireGasInventoryEnergyCurrent(energyPlan, network);
    thermal.commitExternalGasInventoryEnergy(energyPlan);
    network.commitInventoryBatch(inventoryPlan);
    domain.gasState.pressureActive = true;
    domain.pressureSystem.invalidateGeometry();
    return new FluidDomainContracts.GasEdit(node, moved, carried);
  }
}
