package mmm.fluid;

import mmm.chemistry.RegionChemistry;
import mmm.granular.GranularCouplingFields;
import mmm.granular.GranularDepositDynamics;
import java.util.List;
import mmm.units.SignedDyadic;
import mmm.granular.GranularInventory;
import mmm.granular.GranularParcelStore;
import mmm.granular.SolidVolume;
import mmm.thermal.GranularEnergyColumn;
import mmm.thermal.ThermalLimits;
import mmm.thermal.ThermalTransport;
import mmm.world.MovingBodyFluidCoupling;
import static mmm.fluid.HostFluidState.*;
import static mmm.fluid.FluidDomainContracts.*;

/** Geometry and mechanical-energy observations used by host inventory operations. */
final class DomainInventoryGeometry {
	private final HostFluidState domain;
	DomainInventoryGeometry(HostFluidState domain) { this.domain = domain; }
	double gridKineticEnergyJoules() {
		double densityScale = 1.0;
		double energy = faceKineticEnergyJoules(domain.mac.velocityX, domain.mac.faceMassX, densityScale)
				+ faceKineticEnergyJoules(domain.mac.velocityY, domain.mac.faceMassY, densityScale)
				+ faceKineticEnergyJoules(domain.mac.velocityZ, domain.mac.faceMassZ, densityScale);
		if (!(Double.isFinite(energy) && energy >= 0.0)) {
			throw new ArithmeticException("Grid kinetic-energy audit is invalid");
		}
		return energy;
	}
	private double faceKineticEnergyJoules(float[] velocity, double[] weight, double densityScale) {
		double energy = 0;
		for (int face = 0; face < velocity.length; face++) {
			double kilograms = domain.unitScale.kilograms(weight[face] * densityScale);
			energy += 0.5 * kilograms * velocity[face] * (double) velocity[face];
		}
		return energy;
	}

	long granularGeometricOpenSvq(int cell) {
		return SolidVolume.fromCellVolumeExact(domain.geometry.cutCells.openCellVolume(cell));
	}
	static GranularCouplingFields.Position parcelPosition(GranularParcelStore.Parcel parcel) {
		return new GranularCouplingFields.Position(parcel.x(), parcel.y(), parcel.z(), parcel.ceilMask());
	}
	static float trilinearWeight(int ox, int oy, int oz, float fx, float fy, float fz) {
		return (ox == 0 ? 1.0F - fx : fx) * (oy == 0 ? 1.0F - fy : fy) * (oz == 0 ? 1.0F - fz : fz);
	}

	static void requireFiniteMarkerVelocity(double velocity) {
		if (!Double.isFinite(velocity) || !Float.isFinite((float) velocity)) {
			throw new IllegalArgumentException("Moving-body coupling velocity exceeds marker representation");
		}
	}
	int granularParcelCell(GranularParcelStore.Parcel parcel) {
		int x = Math.min(domain.sizeX - 1, Math.max(0, (int) Math.floor(parcel.x() / domain.config.cellSize())));
		int y = Math.min(domain.sizeY - 1, Math.max(0, (int) Math.floor(parcel.y() / domain.config.cellSize())));
		int z = Math.min(domain.sizeZ - 1, Math.max(0, (int) Math.floor(parcel.z() / domain.config.cellSize())));
		return domain.domainFlowFields.cellIndex(x, y, z);
	}
	boolean granularOccupancyFitsGeometry(GranularCouplingFields coupling) {
		for (int cell = 0; cell < domain.cellCount; cell++) {
			if (coupling.solidSvq(cell) > SolidVolume.fromCellVolumeExact(domain.geometry.cutCells.openCellVolume(cell))) return false;
		}
		return true;
	}
}
