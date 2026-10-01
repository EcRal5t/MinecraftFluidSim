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
		double firstU = grid.internalEnergy(first), secondU = second < 0 ? 0.0 : grid.internalEnergy(second);
		double transfer = ThermalContactExchange.transfer(grid.contactBody(first), second < 0
				? new ThermalContactExchange.Reservoir(face.boundaryTemperature()) : grid.contactBody(second),
				face.conductance(), dt);
		if (transfer == 0.0) return 0.0;
		grid.assignTransportCellEnergy(first, firstU + transfer);
		if (second >= 0) grid.assignTransportCellEnergy(second, secondU - transfer);
		return transfer;
	}
}
