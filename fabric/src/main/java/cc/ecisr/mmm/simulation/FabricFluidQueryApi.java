package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.api.StableId;
import cc.ecisr.mmm.api.query.FluidCellObservation;
import cc.ecisr.mmm.api.query.FluidQueryRequest;
import cc.ecisr.mmm.api.query.FluidQueryResult;
import cc.ecisr.mmm.api.query.FluidQueryStatus;
import cc.ecisr.mmm.api.query.MmmQueryApi;
import cc.ecisr.mmm.fluid.FluidCellInventoryView;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

import java.util.OptionalDouble;

/** Server-thread Fabric adapter over loaded region owners only. */
final class FabricFluidQueryApi implements MmmQueryApi {
	private final MinecraftServer server;
	private final FluidRegionManager regions;

	FabricFluidQueryApi(MinecraftServer server, FluidRegionManager regions) {
		this.server = java.util.Objects.requireNonNull(server, "server");
		this.regions = java.util.Objects.requireNonNull(regions, "regions");
	}

	@Override public FluidQueryResult query(FluidQueryRequest request) {
		if (!server.isSameThread()) return absent(FluidQueryStatus.WRONG_THREAD,
				"query is synchronous and server-thread-only");
		if (request == null || !StableId.isValid(request.dimensionId(), 128)
				|| !Double.isFinite(request.worldXMetres()) || !Double.isFinite(request.worldYMetres())
				|| !Double.isFinite(request.worldZMetres())) {
			return absent(FluidQueryStatus.INVALID_REQUEST, "invalid dimension id or world-space coordinate");
		}
		FluidRegion identityMatch = null;
		FluidRegion owner = null;
		boolean outsideInterior = false;
		for (FluidRegion region : regions.regions()) {
			if (request.regionId() != null && !request.regionId().equals(region.id())) continue;
			if (!request.dimensionId().equals(region.dimension().identifier().toString())) continue;
			identityMatch = region;
			if (!contains(region, request)) continue;
			BlockPos block = BlockPos.containing(
					request.worldXMetres(), request.worldYMetres(), request.worldZMetres());
			if (region.footprint() != null && !region.footprint().containsInterior(block)) {
				outsideInterior = true;
				continue;
			}
			owner = region;
			break;
		}
		if (owner == null) {
			if (outsideInterior) return absent(FluidQueryStatus.OUTSIDE_INTERIOR,
					"point is outside the controller-owned interior");
			return absent(FluidQueryStatus.NO_REGION, identityMatch == null
					? "no matching region identity/dimension" : "point is outside the matching region");
		}
		if (!owner.solverLoaded()) return absent(FluidQueryStatus.REGION_NOT_LOADED,
				"matching region exists but its solver is unloaded");
		if (owner.availabilityFailure() != null) return absent(FluidQueryStatus.REGION_UNAVAILABLE,
				"matching region is unavailable: " + owner.availabilityFailure());
		double h = owner.solverConfig().cellSize();
		int cellX = (int) Math.floor((request.worldXMetres() - owner.origin().getX()) / h);
		int cellY = (int) Math.floor((request.worldYMetres() - owner.origin().getY()) / h);
		int cellZ = (int) Math.floor((request.worldZMetres() - owner.origin().getZ()) / h);
		FluidCellInventoryView view;
		try {
			view = owner.domain().observeCellInventory(cellX, cellY, cellZ);
		} catch (IllegalArgumentException | IllegalStateException unavailable) {
			return absent(FluidQueryStatus.STATE_NOT_READY, "authoritative cell state is not observable: "
					+ unavailable.getMessage());
		}
		if (view == null) return absent(FluidQueryStatus.EMPTY, "cell contains no liquid marker inventory");
		return FluidQueryResult.ok(new FluidCellObservation(request.dimensionId(), owner.id(), owner.networkGeneration(),
				cellX, cellY, cellZ, h, view.liquidVolumeCubicMetres(), view.liquidMassKilograms(),
				view.densityKilogramsPerCubicMetre(), copy(view.temperatureKelvin()),
				view.materials().stream().map(value -> new FluidCellObservation.MaterialAmount(
						value.materialId(), value.volumeCubicMetres(), value.massKilograms())).toList(),
				view.species().stream().map(value -> new FluidCellObservation.SpeciesAmount(
						value.speciesId(), value.amountQuanta())).toList()));
	}

	private static boolean contains(FluidRegion region, FluidQueryRequest request) {
		double h = region.solverConfig().cellSize();
		return request.worldXMetres() >= region.origin().getX()
				&& request.worldXMetres() < region.origin().getX() + region.sizeX() * h
				&& request.worldYMetres() >= region.origin().getY()
				&& request.worldYMetres() < region.origin().getY() + region.sizeY() * h
				&& request.worldZMetres() >= region.origin().getZ()
				&& request.worldZMetres() < region.origin().getZ() + region.sizeZ() * h;
	}

	private static OptionalDouble copy(OptionalDouble value) {
		return value.isPresent() ? OptionalDouble.of(value.getAsDouble()) : OptionalDouble.empty();
	}

	private static FluidQueryResult absent(FluidQueryStatus status, String detail) {
		return FluidQueryResult.absent(status, detail);
	}
}
