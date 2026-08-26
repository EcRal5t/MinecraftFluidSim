package cc.ecisr.mmm.transfer;

import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.granular.GranularRegistry;
import java.util.UUID;

/**
 * Loader-neutral short-lived WAL data for one external actor transfer against a controller region.
 * Region HEAD remains the only commit point. A loader adapter owns item/state mapping and may
 * delete this record only after the actor after-state has been saved durably.
 */
public record ContainerTransferJournal(
		UUID transactionId,
		UUID regionId,
		String dimensionId,
		long baseGeneration,
		long targetGeneration,
		Operation operation,
		ResourceKind resourceKind,
		long volumeNanos,
		long solidVolumeSvq,
		ThermalTransfer thermalTransfer,
		ContainerTransferActor actor,
		ItemFingerprint beforeItem,
		ItemFingerprint afterItem,
		long targetManifestChecksum,
		Phase phase
) {
	public ContainerTransferJournal(
			UUID transactionId, UUID regionId, String dimensionId, long baseGeneration, long targetGeneration,
			Operation operation, long volumeNanos, ContainerTransferActor actor,
			ItemFingerprint beforeItem, ItemFingerprint afterItem, Phase phase
	) {
		this(transactionId, regionId, dimensionId, baseGeneration, targetGeneration, operation,
				ResourceKind.FLUID_NANOS, volumeNanos, -1L, null, actor, beforeItem, afterItem, -1L, phase);
	}

	public ContainerTransferJournal(
			UUID transactionId, UUID regionId, String dimensionId, long baseGeneration, long targetGeneration,
			Operation operation, long volumeNanos, ContainerTransferActor actor, ItemFingerprint beforeItem,
			ItemFingerprint afterItem, long targetManifestChecksum, Phase phase
	) {
		this(transactionId, regionId, dimensionId, baseGeneration, targetGeneration, operation,
				ResourceKind.FLUID_NANOS, volumeNanos, -1L, null, actor, beforeItem, afterItem,
				targetManifestChecksum, phase);
	}

	public static ContainerTransferJournal thermalFluid(
			UUID transactionId, UUID regionId, String dimensionId, long baseGeneration, long targetGeneration,
			Operation operation, long volumeNanos, ThermalTransfer thermalTransfer, ContainerTransferActor actor,
			ItemFingerprint beforeItem, ItemFingerprint afterItem, long targetManifestChecksum, Phase phase
	) {
		return new ContainerTransferJournal(transactionId, regionId, dimensionId, baseGeneration, targetGeneration,
				operation, ResourceKind.FLUID_NANOS, volumeNanos, -1L, thermalTransfer, actor, beforeItem,
				afterItem, targetManifestChecksum, phase);
	}

	public static ContainerTransferJournal granular(
			UUID transactionId, UUID regionId, String dimensionId, long baseGeneration, long targetGeneration,
			Operation operation, long solidVolumeSvq, ContainerTransferActor actor,
			ItemFingerprint beforeItem, ItemFingerprint afterItem, long targetManifestChecksum, Phase phase
	) {
		return new ContainerTransferJournal(transactionId, regionId, dimensionId, baseGeneration, targetGeneration,
				operation, ResourceKind.GRANULAR_SVQ, -1L, solidVolumeSvq, null, actor, beforeItem,
				afterItem, targetManifestChecksum, phase);
	}

	public boolean requiresChemicalRegistry() {
		return beforeItem.requiresChemicalRegistry() || afterItem.requiresChemicalRegistry();
	}

	public void validateProfileFingerprint(String expected) {
		if (expected == null || !expected.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("Authoritative world profile is unavailable");
		}
		for (ItemFingerprint item : java.util.List.of(beforeItem, afterItem)) {
			if (item.profileFingerprint() != null && !item.profileFingerprint().equals(expected)) {
				throw new IllegalArgumentException("Typed container belongs to a foreign world profile");
			}
		}
	}

	public void validateChemicalRegistry(ChemicalRegistry registry) {
		if (!requiresChemicalRegistry()) return;
		if (registry == null) throw new IllegalArgumentException("Authoritative chemical registry is unavailable");
		beforeItem.validateChemicalRegistry(registry);
		afterItem.validateChemicalRegistry(registry);
	}

	public boolean requiresGranularRegistry() {
		return resourceKind == ResourceKind.GRANULAR_SVQ
				|| beforeItem.kind() == ItemKind.GRANULAR_CONTAINER
				|| afterItem.kind() == ItemKind.GRANULAR_CONTAINER;
	}

	public void validateGranularRegistry(GranularRegistry registry, ChemicalRegistry chemistry) {
		if (!requiresGranularRegistry()) return;
		if (registry == null) throw new IllegalArgumentException("Authoritative granular registry is unavailable");
		beforeItem.validateGranularRegistry(registry, chemistry);
		afterItem.validateGranularRegistry(registry, chemistry);
	}

	public boolean requiresThermalRegistry() {
		return thermalTransfer != null || beforeItem.requiresThermalRegistry() || afterItem.requiresThermalRegistry();
	}

	public void validateThermalRegistry(String fingerprint) {
		if (!requiresThermalRegistry()) return;
		if (thermalTransfer == null || fingerprint == null
				|| !thermalTransfer.registryFingerprint().equals(fingerprint)) {
			throw new IllegalArgumentException("Authoritative thermal registry is unavailable or differs from the WAL");
		}
		beforeItem.validateThermalRegistry(fingerprint);
		afterItem.validateThermalRegistry(fingerprint);
	}

	public enum Operation { INSERT, EXTRACT }

	public enum ResourceKind { FLUID_NANOS, GRANULAR_SVQ }

	public enum Phase { PREPARED, REGION_COMMITTED }

	public record ThermalTransfer(String registryFingerprint, long internalEnergyBits) {
		public ThermalTransfer {
			if (registryFingerprint == null || !registryFingerprint.matches("[0-9a-f]{64}")
					|| !Double.isFinite(Double.longBitsToDouble(internalEnergyBits))) {
				throw new IllegalArgumentException("Invalid container thermal transfer");
			}
		}

		public ThermalTransfer(String registryFingerprint, double internalEnergy) {
			this(registryFingerprint, Double.doubleToRawLongBits(internalEnergy));
		}

		public double internalEnergy() { return Double.longBitsToDouble(internalEnergyBits); }
	}

	public enum ItemKind {
		BUCKET,
		WATER_BUCKET,
		PARTIAL,
		EMPTY_MATERIAL_BUCKET,
		MATERIAL_BUCKET,
		CHEMICAL_BUCKET,
		EMPTY_GRANULAR_CONTAINER,
		GRANULAR_CONTAINER,
		CREATIVE_SOURCE
	}

	/** Exact data needed to compare and reconstruct one count-one transfer item. */
	public record ItemFingerprint(
			ItemKind kind,
			long nanos,
			long solidVolumeSvq,
			String contents,
			long massBits,
			String thermalContents,
			String profileFingerprint
	) {
		public ItemFingerprint {
			boolean actual = kind == ItemKind.MATERIAL_BUCKET || kind == ItemKind.CHEMICAL_BUCKET
					|| kind == ItemKind.GRANULAR_CONTAINER;
			if (actual != (profileFingerprint != null)) {
				throw new IllegalArgumentException("Only filled typed containers require a world profile fingerprint");
			}
			if (profileFingerprint != null && !profileFingerprint.matches("[0-9a-f]{64}")) {
				throw new IllegalArgumentException("Container world profile fingerprint is invalid");
			}
		}

		public ItemFingerprint(ItemKind kind, long nanos, String contents) {
			this(kind, nanos, -1L, contents, -1L, null, null);
		}

		public ItemFingerprint(ItemKind kind, long nanos, String contents, long massBits) {
			this(kind, nanos, -1L, contents, massBits, null, null);
		}

		public ItemFingerprint(ItemKind kind, long nanos, long solidVolumeSvq, String contents, long massBits) {
			this(kind, nanos, solidVolumeSvq, contents, massBits, null, null);
		}

		public ItemFingerprint(ItemKind kind, long nanos, long solidVolumeSvq, String contents,
				long massBits, String thermalContents) {
			this(kind, nanos, solidVolumeSvq, contents, massBits, thermalContents, null);
		}

		public boolean requiresChemicalRegistry() {
			return kind == ItemKind.CHEMICAL_BUCKET
					|| kind == ItemKind.GRANULAR_CONTAINER
					&& GranularContents.decode(contents).groups().stream().anyMatch(GranularContents.Group::reactive);
		}

		public void validateChemicalRegistry(ChemicalRegistry registry) {
			if (kind == ItemKind.CHEMICAL_BUCKET) ChemicalContents.decode(contents).validateAgainst(registry);
			else if (kind == ItemKind.GRANULAR_CONTAINER
					&& GranularContents.decode(contents).groups().stream().anyMatch(GranularContents.Group::reactive)
					&& registry == null) {
				throw new IllegalArgumentException("Reactive granular WAL item requires chemical registry");
			}
		}

		public void validateGranularRegistry(GranularRegistry registry, ChemicalRegistry chemistry) {
			if (kind == ItemKind.GRANULAR_CONTAINER) {
				GranularContents.decode(contents).validateAgainst(registry, chemistry);
			}
		}

		public boolean requiresThermalRegistry() { return thermalContents != null; }

		public void validateThermalRegistry(String fingerprint) {
			if (thermalContents == null) return;
			ThermalContents thermal = ThermalContents.decode(thermalContents);
			thermal.validateRegistryFingerprint(fingerprint);
			ThermalContents expected = switch (kind) {
				case WATER_BUCKET -> ThermalContents.forPureWater(
						fingerprint, ContainerFluidLedger.NANOS_PER_BLOCK, thermal.internalEnergy());
				case PARTIAL -> ThermalContents.forPureWater(fingerprint, nanos, thermal.internalEnergy());
				case MATERIAL_BUCKET -> ThermalContents.forMixture(
						fingerprint, FluidMixture.decode(contents), thermal.internalEnergy());
				case CHEMICAL_BUCKET -> ThermalContents.forChemical(
						fingerprint, ChemicalContents.decode(contents), thermal.internalEnergy());
				case BUCKET, EMPTY_MATERIAL_BUCKET, EMPTY_GRANULAR_CONTAINER, GRANULAR_CONTAINER, CREATIVE_SOURCE ->
						throw new IllegalArgumentException("Non-liquid WAL item carries thermal contents");
			};
			if (expected.underlyingKind() != thermal.underlyingKind()
					|| !expected.underlyingHash().equals(thermal.underlyingHash())) {
				throw new IllegalArgumentException("WAL thermal contents do not match item truth");
			}
		}
	}
}
