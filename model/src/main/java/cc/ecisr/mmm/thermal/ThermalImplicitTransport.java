package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.units.SignedDyadic;
import java.util.List;

/** Conservative backward-Euler face solves for stiff conduction, in symmetric sweep order. */
final class ThermalImplicitTransport {
	record Face(int first, int second, double conductance, double boundaryTemperature) { }
	record Exchange(double maximumTransfer, SignedDyadic reservoir) { }
	private ThermalImplicitTransport() { }

	static Exchange step(ThermalCellGrid grid, List<Face> faces, double dt) {
		double maximum = 0.0;
		SignedDyadic reservoir = SignedDyadic.ZERO;
		for (int sweep = 0; sweep < 2; sweep++) for (int index = 0; index < faces.size(); index++) {
			Face face = faces.get(sweep == 0 ? index : faces.size() - 1 - index);
			double transfer = transfer(grid, face, dt * 0.5);
			maximum = Math.max(maximum, Math.abs(transfer));
			if (face.second() < 0) reservoir = reservoir.add(SignedDyadic.of(transfer));
		}
		if (!grid.carriersMatchSolvedTemperature()) grid.setTransportCellInternalEnergies(grid.copyInternalEnergy());
		grid.finishTransport();
		return new Exchange(maximum, reservoir);
	}

	private static double transfer(ThermalCellGrid grid, Face face, double dt) {
		int first = face.first(), second = face.second();
		double firstT = grid.temperature(first);
		double secondT = second < 0 ? face.boundaryTemperature() : grid.temperature(second);
		if (firstT == secondT) return 0.0;
		double sign = secondT > firstT ? 1.0 : -1.0;
		double firstU = grid.internalEnergy(first), secondU = second < 0 ? 0.0 : grid.internalEnergy(second);
		double high = Math.abs(grid.energyChangeToTemperature(first, secondT));
		if (second >= 0) high = Math.min(high, Math.abs(grid.energyChangeToTemperature(second, firstT)));
		if (!(Double.isFinite(high) && high > 0.0)) throw new IllegalArgumentException("Thermal contact energy interval is invalid");
		double inverseConductanceDt = (1.0 / face.conductance()) / dt;
		double slope = 1.0 / grid.heatCapacity(first) + (second < 0 ? 0.0 : 1.0 / grid.heatCapacity(second));
		double low = 0.0;
		double amount = Math.min(high, Math.abs(secondT - firstT) / (inverseConductanceDt + slope));
		while (true) {
			grid.solveTransportCell(first, firstU + sign * amount);
			if (second >= 0) grid.solveTransportCell(second, secondU - sign * amount);
			double hot = second < 0 ? secondT : grid.temperature(second);
			double residual = amount * inverseConductanceDt - sign * (hot - grid.temperature(first));
			double precision = 16.0 * Math.max(Math.ulp(hot), Math.ulp(grid.temperature(first)));
			if (Math.abs(residual) <= precision || Math.nextUp(low) >= high) break;
			if (residual > 0.0) high = amount; else low = amount;
			slope = 1.0 / grid.heatCapacity(first) + (second < 0 ? 0.0 : 1.0 / grid.heatCapacity(second));
			double next = amount - residual / (inverseConductanceDt + slope);
			if (!(next > low && next < high)) next = low + (high - low) * 0.5;
			if (next == amount) break;
			amount = next;
		}
		double transfer = sign * amount;
		grid.assignTransportCellEnergy(first, firstU + transfer);
		if (second >= 0) grid.assignTransportCellEnergy(second, secondU - transfer);
		return transfer;
	}
}
