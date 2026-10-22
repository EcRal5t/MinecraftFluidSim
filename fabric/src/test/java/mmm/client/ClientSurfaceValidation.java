package mmm.client;

import mmm.network.FluidChemicalSnapshotPayload;
import mmm.network.FluidMaterialSnapshotPayload;
import mmm.network.FluidProtocol;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Surface reuse and display-only colour updates, exercised without a running game. */
public final class ClientSurfaceValidation {
	public static void main(String[] args) {
		var store = new ClientRegionSnapshotStore();
		var negotiation = FluidProtocol.negotiate(FluidProtocol.materialServerOffer());
		var first = material(1, (byte) 255);
		store.accept(first, negotiation);
		var mesh = store.surfaceMesh(first, first.toRenderSnapshot());
		require(!mesh.outerTriangles().isEmpty(), "Fixture has a visible surface");
		var second = material(2, (byte) 255);
		store.accept(second, negotiation);
		require(store.surfaceMesh(second, second.toRenderSnapshot()) == mesh, "Unchanged geometry reuses its mesh");
		var changed = material(3, (byte) 128);
		store.accept(changed, negotiation);
		require(store.surfaceMesh(changed, changed.toRenderSnapshot()) != mesh, "Changed volume rebuilds geometry");
		store.clear();
		store.accept(second, negotiation);
		require(store.surfaceMesh(second, second.toRenderSnapshot()) != mesh, "Clearing releases the previous surface");
		var red = MaterialSurfaceGeometry.prepare(first, first.toRenderSnapshot(), mesh, chemical(first, 0xffff0000), null, composition());
		var blue = MaterialSurfaceGeometry.prepare(first, first.toRenderSnapshot(), mesh, chemical(first, 0xff0000ff), null, composition());
		require(red.size() == mesh.outerTriangles().size() && blue.size() == red.size(), "Colour preparation preserves triangles");
		for (int i = 0; i < red.size(); i++) {
			require(red.get(i).triangle().equals(blue.get(i).triangle()) && red.get(i).argb() == 0xffff0000
					&& blue.get(i).argb() == 0xff0000ff, "Composition updates colour on reused geometry");
		}
		var initial = new ClientRegionSnapshotStore.MaterialFrame(first, first.toRenderSnapshot(), mesh,
				chemical(first, 0xffff0000), new short[] {4800,4800,4800,4800,4800,4800,4800,4800}, composition());
		var ph = initial.recolor(new FluidDisplaySettings.View(FluidDisplaySettings.Mode.PH, 273.15, 373.15));
		require(ph.mesh() == initial.mesh() && ph.triangles().getFirst().argb() == 0xff35d06f,
				"Mode changes preserve geometry and show exact neutral pH");
		var thermal = initial.recolor(new FluidDisplaySettings.View(FluidDisplaySettings.Mode.TEMPERATURE, 273.15, 373.15));
		var wider = thermal.recolor(new FluidDisplaySettings.View(FluidDisplaySettings.Mode.TEMPERATURE, 273.15, 473.15));
		require(thermal.mesh() == mesh && wider.mesh() == mesh
				&& thermal.triangles().getFirst().argb() != wider.triangles().getFirst().argb(),
				"Temperature range changes only colours on the accepted thermal frame");
		require(FluidColorMapping.color(new FluidDisplaySettings.View(FluidDisplaySettings.Mode.PH, 273.15, 373.15),
				null, null, first, 0, 0, 0xff197ae8) == FluidColorMapping.NO_DATA,
				"Missing pH differs from neutral");
		System.out.println("Client surface validation passed");
	}

	private static FluidMaterialSnapshotPayload material(long sequence, byte volume) {
		byte[] volumes = new byte[8], markers = new byte[8], fractions = new byte[8];
		Arrays.fill(volumes, volume); Arrays.fill(markers, (byte) 1); Arrays.fill(fractions, (byte) 255);
		return new FluidMaterialSnapshotPayload(FluidProtocol.Layout.MATERIAL, FluidMaterialSnapshotPayload.Kind.SNAPSHOT,
				Identifier.fromNamespaceAndPath("minecraft", "overworld"), new UUID(1, 1), 1, sequence,
				BlockPos.ZERO, 0.5F, 2, 2, 2,
				List.of(new FluidMaterialSnapshotPayload.MaterialEntry("mmm:water", 1000, "mmm:aqueous", 0xff197ae8)),
				List.of("mmm:aqueous"), volumes, markers, new byte[8], new byte[][] {fractions},
				new byte[][] {fractions}, false, List.of());
	}

	private static ClientRegionSnapshotStore.ChemicalFrame chemical(FluidMaterialSnapshotPayload material, int color) {
		short[] species = new short[32]; Arrays.fill(species, (short) -1);
		byte[] weights = new byte[32];
		for (int cell = 0; cell < 8; cell++) { species[cell * 4] = 0; weights[cell * 4] = (byte) 255; }
		var payload = new FluidChemicalSnapshotPayload(FluidProtocol.Layout.CHEMICAL, material, "a".repeat(64),
				List.of(new FluidChemicalSnapshotPayload.SpeciesEntry("mmm:h2o", color)), species, weights,
				new byte[8], ph());
		return new ClientRegionSnapshotStore.ChemicalFrame(payload, payload.topSpecies(), payload.topWeights(),
				payload.residualWeights(), payload.ph());
	}

	private static FluidDisplaySettings.View composition() {
		return new FluidDisplaySettings.View(FluidDisplaySettings.Mode.COMPOSITION, 273.15, 373.15);
	}
	private static byte[] ph() {
		byte[] ph = new byte[8]; Arrays.fill(ph, (byte) 70); return ph;
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
