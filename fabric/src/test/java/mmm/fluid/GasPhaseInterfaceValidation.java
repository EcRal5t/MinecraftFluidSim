package mmm.fluid;

import java.util.Arrays;

/** Actual microgeometry controls for phase contacts and disconnected liquid components. */
public final class GasPhaseInterfaceValidation {
	private GasPhaseInterfaceValidation() { }

	public static void main(String[] args) { validate(); }

	public static void validate() {
		for (int set = 0; set < 256; set++) {
			int feasible = set;
			Integer maximum = GasPhaseBatchSearch.maximum(8, batch -> (feasible & 1 << ((int) batch - 1)) != 0 ? (int) batch : null,
					(low, high) -> (feasible & ((1 << (int) high) - (1 << ((int) low - 1)))) == 0);
			check(maximum == null ? set == 0 : maximum == 32 - Integer.numberOfLeadingZeros(set),
					"Certified interval exclusion changed a disconnected integer feasible set");
		}
		for (boolean bothWet : new boolean[]{false, true}) {
			FluidDomain domain = domain(126, false, bothWet
					? new float[]{.25F,.25F,.25F,.0625F, .75F,.75F,.75F,.03125F}
					: new float[]{.25F,.25F,.25F,.0625F});
			var before = domain.exportParticleState();
			var frame = GasRuntimeGeometry.capture(domain, null, new long[8], true);
			var contacts = GasPhaseInterfaceResolver.contacts(frame, 0);
			check(contacts.size() == (bothWet ? 2 : 1), "Disconnected dry microbox became a liquid contact");
			for (var contact : contacts) {
				check(contact.supportMask() == 1 || bothWet && contact.supportMask() == 128,
						"Contact crossed a fixed solid partition");
				int micro = contact.supportMask() == 1 ? 0 : 7;
				check(contact.node() == frame.nodeOfPiece(micro) && contact.pieces().equals(java.util.List.of(micro)),
						"Independent wet components shared a gas owner or contact piece");
			}
			check(Arrays.equals(before.copyPositionVolume(), domain.exportParticleState().copyPositionVolume())
					&& frame.matchesSourceCarrierGeometry(domain, new long[8]), "Contact observation changed its source stock");
		}
		// The interface lies exactly on the internal y=.5 microface; no individual piece is partially dry.
		var half = domain(0, false, new float[]{.5F,.25F,.5F,.5F});
		var halfFrame = GasRuntimeGeometry.capture(half, null, new long[8], true);
		var halfContacts = GasPhaseInterfaceResolver.contacts(halfFrame, 0);
		check(halfContacts.size() == 1 && halfContacts.getFirst().supportMask() == 255
				&& halfContacts.getFirst().pieces().size() == 4, "A microface-aligned PLIC interface lost its gas contact");
		for (int piece : halfContacts.getFirst().pieces()) check((piece & 2) != 0,
				"An aligned liquid interface selected the wet side as gas");

		var full = domain(0, true, new float[]{.5F,.5F,.5F,1});
		var fullFrame = GasRuntimeGeometry.capture(full, null, new long[8], true);
		var sharedContacts = GasPhaseInterfaceResolver.contacts(fullFrame, 0);
		check(sharedContacts.size() == 1 && sharedContacts.getFirst().pieces().size() == 4,
				"Full liquid lost its shared wet/dry face contact");
		for (int piece : sharedContacts.getFirst().pieces()) check(piece / 8 == 1 && (piece & 1) == 0,
				"Shared-face contact selected an unrelated gas microbox");
		full.bootstrapGasState("");
		var source = full.gasState.frame;
		var envelope = GasPhaseGeometryEnvelope.capture(source, full.gasState.network, null, 0, source.commonCellNode(1));
		check(envelope != null, "A fixed source gas chamber lost its single-successor capacity certificate");
		var volume = mmm.units.SignedDyadic.of(.75);
		var moment = volume.multiply(mmm.units.SignedDyadic.of(.5));
		var edit = new PhaseLiquidGeometryEdit(0, 0, java.util.List.of(new LiquidComponentMoments(0,255,1,volume,moment,moment,moment)));
		var future = GasRuntimeGeometry.capture(full, edit, new long[8], true);
		check(envelope.volumeUpper(edit) >= future.capacity().chambers().getFirst().cubicMeters()
				&& envelope.volumeUpper(edit) <= Math.nextUp(Math.nextUp(Math.nextUp(1.25))),
				"Certified future capacity omitted released volume or broadened its rounding budget");
		var merger = domain(0, true, new float[]{.5F,.5F,.5F,1});
		merger.geometry.cutCells.replaceMask(2,0); merger.geometry.solid[2]=false; merger.bootstrapGasState("");
		var mergingSource = merger.gasState.frame;
		check(mergingSource.nodeCount()==2 && GasPhaseGeometryEnvelope.capture(mergingSource,merger.gasState.network,null,
				0,mergingSource.commonCellNode(1))==null,"A possible merger reused an isolated gas inventory/energy certificate");
		System.out.println("Gas phase microcontact validation passed");
	}

	private static FluidDomain domain(int mask, boolean peerOpen, float[] markers) {
		byte[] masks = new byte[8]; Arrays.fill(masks, (byte)255); masks[0] = (byte) mask;
		if (peerOpen) masks[1] = 0;
		float total = 0;
		for (int i = 3; i < markers.length; i += 4) total += markers[i];
		FluidDomain domain = new FluidDomain(2, 2, 2,
				new FluidSolverConfig(1,1000,0,.01F,200,1e-5F,.5F,1e-4F,0), 32);
		domain.restoreParticleState(new FlipParticleState(2,2,2,1,markers.length/4,total,markers,
				new float[markers.length/4*3],masks));
		domain.sealDomainBoundary();
		return domain;
	}

	private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
