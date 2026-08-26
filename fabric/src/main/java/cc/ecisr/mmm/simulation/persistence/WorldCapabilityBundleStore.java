package cc.ecisr.mmm.simulation.persistence;

import cc.ecisr.mmm.chemistry.ChemicalPhaseDefinition;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.chemistry.ChemicalRegistryStore;
import cc.ecisr.mmm.fluid.MaterialRegistry;
import cc.ecisr.mmm.fluid.MaterialRegistryStore;
import cc.ecisr.mmm.fluid.air.GasComponentDefinition;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.fluid.air.GasRegistryStore;
import cc.ecisr.mmm.granular.GranularCarrier;
import cc.ecisr.mmm.granular.GranularRegistry;
import cc.ecisr.mmm.granular.GranularRegistryStore;
import cc.ecisr.mmm.thermal.GasLiquidSpeciesPair;
import cc.ecisr.mmm.thermal.ThermalMaterialDefinition;
import cc.ecisr.mmm.thermal.ThermalRegistry;
import cc.ecisr.mmm.thermal.ThermalRegistryStore;
import cc.ecisr.mmm.thermal.ThermalSpeciesOffset;
import cc.ecisr.mmm.worldprofile.RegistryFingerprintSet;
import cc.ecisr.mmm.worldprofile.WorldCapability;
import cc.ecisr.mmm.worldprofile.WorldCapabilityProfile;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Publishes and restores the immutable world-capability bundle with HEAD as its sole commit point. */
public final class WorldCapabilityBundleStore {
	private static final String HEAD_FILE = "HEAD.nbt";
	private static final String HEAD_TEMPORARY_FILE = "HEAD.nbt.tmp";
	private static final String SLOT_DIRECTORY = "slot-a";
	private static final String SLOT_TEMPORARY_DIRECTORY = "slot-a.tmp";
	private static final String MANIFEST_FILE = "manifest.nbt";
	private static final long INITIAL_GENERATION = 1L;
	private static final Set<String> AUTHORITY_ROOTS = Set.of(
			"container-transfer", "finite-device", "ownership", "region-state", "world-action");
	private static final Set<String> NON_AUTHORITY_ROOTS = Set.of(
			"quarantine", "server.properties", "world-capabilities");

	private final Path mmmDirectory;
	private final Path directory;
	private final QuarantineAdmission quarantineAdmission;

	public WorldCapabilityBundleStore(Path worldRoot, QuarantineAdmission quarantineAdmission) {
		if (worldRoot == null || quarantineAdmission == null) {
			throw new IllegalArgumentException("World capability bundle store configuration is incomplete");
		}
		Path normalizedWorld = worldRoot.toAbsolutePath().normalize();
		this.mmmDirectory = normalizedWorld.resolve("mmm");
		this.directory = mmmDirectory.resolve("world-capabilities");
		this.quarantineAdmission = quarantineAdmission;
	}

	public OpenResult openOrPublish(Definitions definitions) throws IOException {
		return openOrPublish(definitions, FaultInjector.NONE);
	}

	public OpenResult openOrPublish(Definitions definitions, FaultInjector faultInjector) throws IOException {
		if (definitions == null || faultInjector == null) {
			throw new IllegalArgumentException("World capability bundle open is incomplete");
		}
		WorldCapabilityProfile expectedProfile = definitions.profile();
		boolean committed = Files.isRegularFile(headPath(), LinkOption.NOFOLLOW_LINKS);
		validateMmmInventory(committed);
		if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
			DurableFileOperations.createDirectories(directory);
			return new OpenResult(publish(definitions, expectedProfile, faultInjector), true, false, false);
		}
		requireDirectory(directory, "world capability root");
		Set<String> entries = entryNames(directory);
		if (entries.contains(HEAD_FILE)) {
			requireExact(entries, Set.of(HEAD_FILE, SLOT_DIRECTORY), "committed world capability root");
			Bundle bundle = readCommitted();
			return new OpenResult(bundle, false, !definitions.matches(bundle), false);
		}

		if (entries.equals(Set.of(SLOT_DIRECTORY, HEAD_TEMPORARY_FILE))) {
			VerifiedSlot slot = verifySlot(slotPath());
			requireRegularFile(headTemporaryPath(), "world capability temporary HEAD");
			requireFileBudget(headTemporaryPath(), WorldCapabilityBundleMetadataCodec.MAX_HEAD_BYTES,
					"world capability temporary HEAD");
			WorldCapabilityBundleHead temporaryHead = WorldCapabilityBundleMetadataCodec.readHead(headTemporaryPath());
			requireHeadBinding(temporaryHead, slot);
			Files.move(headTemporaryPath(), headPath(), StandardCopyOption.ATOMIC_MOVE);
			DurableFileOperations.forceDirectory(directory);
			faultInjector.reached(new PublicationBoundary(PublicationPhase.HEAD_PUBLISHED, ""));
			Bundle bundle = readCommitted();
			return new OpenResult(bundle, false, !definitions.matches(bundle), true);
		}
		if (entries.equals(Set.of(SLOT_DIRECTORY))) {
			VerifiedSlot slot = verifySlot(slotPath());
			publishHead(slot, faultInjector);
			Bundle bundle = readCommitted();
			return new OpenResult(bundle, false, !definitions.matches(bundle), true);
		}
		if (entries.equals(Set.of(SLOT_TEMPORARY_DIRECTORY))) {
			quarantineStaging();
			return new OpenResult(publish(definitions, expectedProfile, faultInjector), true, false, true);
		}
		if (entries.isEmpty()) {
			return new OpenResult(publish(definitions, expectedProfile, faultInjector), true, false, false);
		}
		throw new IOException("World capability root has an unprovable uncommitted layout: " + entries);
	}

	public Bundle readCommitted() throws IOException {
		validateMmmInventory(true);
		requireDirectory(directory, "world capability root");
		requireExact(entryNames(directory), Set.of(HEAD_FILE, SLOT_DIRECTORY), "committed world capability root");
		requireRegularFile(headPath(), "world capability HEAD");
		requireFileBudget(headPath(), WorldCapabilityBundleMetadataCodec.MAX_HEAD_BYTES,
				"world capability HEAD");
		WorldCapabilityBundleHead head = WorldCapabilityBundleMetadataCodec.readHead(headPath());
		VerifiedSlot slot = verifySlot(slotPath());
		requireHeadBinding(head, slot);
		return slot.bundle(head);
	}

	private Bundle publish(Definitions definitions, WorldCapabilityProfile profile,
			FaultInjector faultInjector) throws IOException {
		requireExact(entryNames(directory), Set.of(), "empty world capability root");
		UUID transaction = UUID.randomUUID();
		TreeMap<String, byte[]> members = memberBytes(definitions, profile);
		ArrayList<WorldCapabilityBundleManifest.FileEntry> files = new ArrayList<>(members.size());
		for (Map.Entry<String, byte[]> member : members.entrySet()) {
			files.add(new WorldCapabilityBundleManifest.FileEntry(
					member.getKey(), member.getValue().length, sha256(member.getValue())));
		}
		WorldCapabilityBundleManifest manifest = new WorldCapabilityBundleManifest(
				WorldCapabilityBundleManifest.SCHEMA, INITIAL_GENERATION, transaction,
				profile.profileFingerprint(), profile.aggregateFingerprint(), files);

		prepareStagingManifest(manifest, faultInjector);
		for (Map.Entry<String, byte[]> member : members.entrySet()) {
			Path memberPath = slotTemporaryPath().resolve(member.getKey());
			Files.write(memberPath, member.getValue());
			forceFile(memberPath);
			DurableFileOperations.forceDirectory(slotTemporaryPath());
			faultInjector.reached(new PublicationBoundary(PublicationPhase.MEMBER_WRITTEN, member.getKey()));
		}
		DurableFileOperations.forceDirectory(slotTemporaryPath());
		VerifiedSlot staged = verifySlot(slotTemporaryPath());
		faultInjector.reached(new PublicationBoundary(PublicationPhase.STAGING_VERIFIED, ""));
		Files.move(slotTemporaryPath(), slotPath(), StandardCopyOption.ATOMIC_MOVE);
		DurableFileOperations.forceDirectory(directory);
		faultInjector.reached(new PublicationBoundary(PublicationPhase.SLOT_PUBLISHED, ""));
		publishHead(staged, faultInjector);
		return readCommitted();
	}

	private void prepareStagingManifest(WorldCapabilityBundleManifest manifest,
			FaultInjector faultInjector) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		NbtIo.writeCompressed(WorldCapabilityBundleMetadataCodec.encodeManifest(manifest), output);
		byte[] manifestBytes = output.toByteArray();
		Path preparationRoot = mmmDirectory.resolve("quarantine").resolve("world-capability-staging");
		FileTreeStats existing = treeStats(preparationRoot,
				quarantineAdmission.maximumEntries(), quarantineAdmission.maximumBytes());
		quarantineAdmission.requireCapacity(existing, new FileTreeStats(2L, manifestBytes.length));
		DurableFileOperations.createDirectories(preparationRoot);
		Path preparation = preparationRoot.resolve(manifest.publicationTransactionId() + ".preparing");
		if (Files.exists(preparation, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("World capability publication preparation already exists");
		}
		Files.createDirectory(preparation);
		DurableFileOperations.forceDirectory(preparationRoot);
		Path manifestPath = preparation.resolve(MANIFEST_FILE);
		Files.write(manifestPath, manifestBytes);
		forceFile(manifestPath);
		DurableFileOperations.forceDirectory(preparation);
		faultInjector.reached(new PublicationBoundary(
				PublicationPhase.PREPARATION_MANIFEST_WRITTEN, MANIFEST_FILE));
		Files.move(preparation, slotTemporaryPath(), StandardCopyOption.ATOMIC_MOVE);
		DurableFileOperations.forceDirectory(preparationRoot);
		DurableFileOperations.forceDirectory(directory);
		faultInjector.reached(new PublicationBoundary(PublicationPhase.STAGING_MANIFEST_WRITTEN, MANIFEST_FILE));
	}

	private void publishHead(VerifiedSlot slot, FaultInjector faultInjector) throws IOException {
		WorldCapabilityBundleHead head = new WorldCapabilityBundleHead(
				WorldCapabilityBundleHead.SCHEMA, slot.manifest().generation(),
				slot.manifest().publicationTransactionId(), slot.manifest().profileFingerprint(),
				slot.manifest().aggregateFingerprint(), slot.manifestChecksum());
		WorldCapabilityBundleMetadataCodec.writeHead(headTemporaryPath(), head);
		forceFile(headTemporaryPath());
		DurableFileOperations.forceDirectory(directory);
		WorldCapabilityBundleHead reread = WorldCapabilityBundleMetadataCodec.readHead(headTemporaryPath());
		requireHeadBinding(reread, slot);
		faultInjector.reached(new PublicationBoundary(PublicationPhase.HEAD_TEMPORARY_VERIFIED, HEAD_TEMPORARY_FILE));
		Files.move(headTemporaryPath(), headPath(), StandardCopyOption.ATOMIC_MOVE);
		DurableFileOperations.forceDirectory(directory);
		faultInjector.reached(new PublicationBoundary(PublicationPhase.HEAD_PUBLISHED, HEAD_FILE));
	}

	private VerifiedSlot verifySlot(Path slot) throws IOException {
		requireDirectory(slot, "world capability slot");
		Set<String> expected = new HashSet<>(WorldCapabilityBundleManifest.REQUIRED_FILES);
		expected.add(MANIFEST_FILE);
		requireExact(entryNames(slot), expected, "world capability slot");
		Path manifestPath = slot.resolve(MANIFEST_FILE);
		requireRegularFile(manifestPath, "world capability manifest");
		requireFileBudget(manifestPath, WorldCapabilityBundleMetadataCodec.MAX_MANIFEST_BYTES,
				"world capability manifest");
		WorldCapabilityBundleManifest manifest = WorldCapabilityBundleMetadataCodec.readManifest(manifestPath);
		String manifestChecksum = checksum(manifestPath);
		for (WorldCapabilityBundleManifest.FileEntry entry : manifest.files()) {
			Path member = slot.resolve(entry.name());
			requireRegularFile(member, "world capability member " + entry.name());
			long byteLength = Files.size(member);
			if (byteLength != entry.byteLength() || !checksum(member).equals(entry.checksum())) {
				throw new IOException("World capability member does not match manifest: " + entry.name());
			}
		}

		WorldCapabilityProfile profile = WorldCapabilityProfileCodec.read(slot.resolve("profile.nbt"));
		MaterialRegistry material = MaterialRegistryStore.readSnapshot(slot.resolve("material-registry.nbt"));
		ChemicalRegistry chemical = ChemicalRegistryStore.readSnapshot(slot.resolve("chemical-registry.nbt"));
		GranularRegistry granular = GranularRegistryStore.readSnapshot(
				slot.resolve("granular-registry.nbt"), chemical);
		GasRegistry gas = GasRegistryStore.readSnapshot(slot.resolve("gas-registry.nbt"));
		ThermalRegistry thermal = ThermalRegistryStore.readSnapshot(slot.resolve("thermal-registry.nbt"),
				chemical.fingerprint(), granular.fingerprint(), gas.fingerprint());
		Definitions definitions;
		try {
			definitions = new Definitions(material, chemical, granular, gas, thermal);
		} catch (IllegalArgumentException invalid) {
			throw new IOException("World capability registries do not form one valid bundle", invalid);
		}
		if (!definitions.profile().equals(profile)
				|| !profile.profileFingerprint().equals(manifest.profileFingerprint())
				|| !profile.aggregateFingerprint().equals(manifest.aggregateFingerprint())) {
			throw new IOException("World capability profile does not bind the slot exactly");
		}
		return new VerifiedSlot(manifest, manifestChecksum, profile, material, chemical, granular, gas, thermal);
	}

	private void quarantineStaging() throws IOException {
		Path staging = slotTemporaryPath();
		requireDirectory(staging, "world capability staging");
		Set<String> entries = entryNames(staging);
		if (!entries.contains(MANIFEST_FILE)) {
			throw new IOException("World capability staging has no publication identity");
		}
		Set<String> allowed = new HashSet<>(WorldCapabilityBundleManifest.REQUIRED_FILES);
		allowed.add(MANIFEST_FILE);
		if (!allowed.containsAll(entries)) {
			throw new IOException("World capability staging contains unknown entries: " + entries);
		}
		Path manifestPath = staging.resolve(MANIFEST_FILE);
		requireRegularFile(manifestPath, "world capability staging manifest");
		requireFileBudget(manifestPath, WorldCapabilityBundleMetadataCodec.MAX_MANIFEST_BYTES,
				"world capability staging manifest");
		WorldCapabilityBundleManifest manifest = WorldCapabilityBundleMetadataCodec.readManifest(manifestPath);
		int prefixLength = entries.size() - 1;
		for (int index = 0; index < WorldCapabilityBundleManifest.REQUIRED_FILES.size(); index++) {
			String name = WorldCapabilityBundleManifest.REQUIRED_FILES.get(index);
			boolean present = entries.contains(name);
			if (present != (index < prefixLength)) {
				throw new IOException("World capability staging member sequence is not writer-produced");
			}
			if (present) {
				Path member = staging.resolve(name);
				requireRegularFile(member, "world capability staging member " + name);
				WorldCapabilityBundleManifest.FileEntry expected = manifest.file(name);
				if (Files.size(member) != expected.byteLength() || !checksum(member).equals(expected.checksum())) {
					throw new IOException("World capability staging member is corrupt: " + name);
				}
			}
		}
		Path quarantineRoot = mmmDirectory.resolve("quarantine").resolve("world-capability-staging");
		Path destination = quarantineRoot.resolve(manifest.publicationTransactionId().toString());
		if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("World capability staging quarantine transaction already exists");
		}
		FileTreeStats existing = treeStats(quarantineRoot,
				quarantineAdmission.maximumEntries(), quarantineAdmission.maximumBytes());
		FileTreeStats stagedTree = treeStats(staging,
				quarantineAdmission.maximumEntries(), quarantineAdmission.maximumBytes());
		FileTreeStats incoming;
		try {
			incoming = new FileTreeStats(Math.addExact(stagedTree.entries(), 1L), stagedTree.bytes());
		} catch (ArithmeticException overflow) {
			throw new IOException("World capability staging quarantine accounting overflow", overflow);
		}
		quarantineAdmission.requireCapacity(existing, incoming);
		DurableFileOperations.createDirectories(quarantineRoot);
		Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
		DurableFileOperations.forceDirectory(quarantineRoot);
		DurableFileOperations.forceDirectory(directory);
	}

	private TreeMap<String, byte[]> memberBytes(Definitions definitions,
			WorldCapabilityProfile profile) throws IOException {
		TreeMap<String, byte[]> members = new TreeMap<>();
		members.put("chemical-registry.nbt", ChemicalRegistryStore.snapshotBytes(definitions.chemical()));
		members.put("gas-registry.nbt", GasRegistryStore.snapshotBytes(definitions.gas()));
		members.put("granular-registry.nbt", GranularRegistryStore.snapshotBytes(definitions.granular()));
		members.put("material-registry.nbt", MaterialRegistryStore.snapshotBytes(definitions.material()));
		ByteArrayOutputStream profileOutput = new ByteArrayOutputStream();
		NbtIo.writeCompressed(WorldCapabilityProfileCodec.encode(profile), profileOutput);
		members.put("profile.nbt", profileOutput.toByteArray());
		members.put("thermal-registry.nbt", ThermalRegistryStore.snapshotBytes(definitions.thermal()));
		if (!members.keySet().equals(Set.copyOf(WorldCapabilityBundleManifest.REQUIRED_FILES))) {
			throw new IllegalStateException("World capability member table is not exact");
		}
		return members;
	}

	private static void requireHeadBinding(WorldCapabilityBundleHead head, VerifiedSlot slot) throws IOException {
		try {
			head.requireManifest(slot.manifest(), slot.manifestChecksum());
		} catch (IllegalArgumentException invalid) {
			throw new IOException("World capability HEAD does not bind its slot", invalid);
		}
	}

	private static void requireExact(Set<String> actual, Set<String> expected, String context) throws IOException {
		if (!actual.equals(expected)) throw new IOException(context + " is not exact: " + actual);
	}

	private static Set<String> entryNames(Path directory) throws IOException {
		HashSet<String> names = new HashSet<>();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
			for (Path entry : entries) {
				String name = entry.getFileName().toString();
				if (!names.add(name)) throw new IOException("Duplicate directory entry " + name);
			}
		}
		return Set.copyOf(names);
	}

	private static void requireDirectory(Path path, String context) throws IOException {
		if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
			throw new IOException(context + " is not a plain directory: " + path);
		}
	}

	private static void requireRegularFile(Path path, String context) throws IOException {
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
			throw new IOException(context + " is not a plain file: " + path);
		}
	}

	private void validateMmmInventory(boolean committed) throws IOException {
		if (!Files.exists(mmmDirectory, LinkOption.NOFOLLOW_LINKS)) return;
		requireDirectory(mmmDirectory, "MMM persistence root");
		Set<String> entries = entryNames(mmmDirectory);
		HashSet<String> known = new HashSet<>(AUTHORITY_ROOTS);
		known.addAll(NON_AUTHORITY_ROOTS);
		if (!known.containsAll(entries)) {
			HashSet<String> unknown = new HashSet<>(entries);
			unknown.removeAll(known);
			throw new IOException("MMM persistence root contains unknown entries: " + unknown);
		}
		if (!committed) {
			HashSet<String> existingAuthority = new HashSet<>(entries);
			existingAuthority.retainAll(AUTHORITY_ROOTS);
			if (!existingAuthority.isEmpty()) {
				throw new IOException("MMM authority exists without a committed world capability bundle: "
						+ existingAuthority);
			}
		}
		for (String name : entries) {
			Path entry = mmmDirectory.resolve(name);
			if (name.equals("server.properties")) requireRegularFile(entry, "MMM policy configuration");
			else requireDirectory(entry, "MMM persistence domain " + name);
		}
	}

	private static void requireFileBudget(Path path, long maximumBytes, String context) throws IOException {
		long size = Files.size(path);
		if (size <= 0L || size > maximumBytes) {
			throw new IOException(context + " is outside its compressed byte budget");
		}
	}

	private static void forceFile(Path path) throws IOException {
		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
			channel.force(true);
		}
	}

	private static String checksum(Path path) throws IOException {
		return sha256(Files.readAllBytes(path));
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException unavailable) {
			throw new IllegalStateException("SHA-256 unavailable", unavailable);
		}
	}

	private Path headPath() { return directory.resolve(HEAD_FILE); }
	private Path headTemporaryPath() { return directory.resolve(HEAD_TEMPORARY_FILE); }
	private Path slotPath() { return directory.resolve(SLOT_DIRECTORY); }
	private Path slotTemporaryPath() { return directory.resolve(SLOT_TEMPORARY_DIRECTORY); }

	private static FileTreeStats treeStats(Path root, long maximumEntries, long maximumBytes) throws IOException {
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return new FileTreeStats(0L, 0L);
		requireDirectory(root, "quarantine tree");
		long entries = 0L;
		long bytes = 0L;
		try (var paths = Files.walk(root)) {
			var iterator = paths.iterator();
			while (iterator.hasNext()) {
				Path path = iterator.next();
				if (path.equals(root)) continue;
				if (Files.isSymbolicLink(path)) throw new IOException("Quarantine tree contains a symbolic link");
				entries = Math.addExact(entries, 1L);
				if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
					bytes = Math.addExact(bytes, Files.size(path));
				} else if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
					throw new IOException("Quarantine tree contains a non-regular entry");
				}
				if (entries > maximumEntries || bytes > maximumBytes) {
					throw new IOException("World capability staging quarantine is over its admission budget");
				}
			}
		} catch (ArithmeticException overflow) {
			throw new IOException("Quarantine tree accounting overflow", overflow);
		}
		return new FileTreeStats(entries, bytes);
	}

	public record Definitions(
			MaterialRegistry material,
			ChemicalRegistry chemical,
			GranularRegistry granular,
			GasRegistry gas,
			ThermalRegistry thermal
	) {
		public Definitions {
			if (material == null || chemical == null || granular == null || gas == null || thermal == null) {
				throw new IllegalArgumentException("World capability registry definitions are incomplete");
			}
			if (!granular.chemicalRegistryFingerprint().equals(chemical.fingerprint())) {
				throw new IllegalArgumentException("Granular registry does not bind the chemical registry");
			}
			if (!thermal.chemicalRegistryFingerprint().equals(chemical.fingerprint())
					|| !thermal.granularRegistryFingerprint().equals(granular.fingerprint())
					|| !thermal.gasRegistryFingerprint().equals(gas.fingerprint())) {
				throw new IllegalArgumentException("Thermal registry dependencies do not bind the bundle registries");
			}
			validateReferences(material, chemical, granular, gas, thermal);
		}

		public WorldCapabilityProfile profile() {
			return WorldCapabilityProfile.of(EnumSet.allOf(WorldCapability.class), new RegistryFingerprintSet(
					material.fingerprint(), chemical.fingerprint(), granular.fingerprint(),
					gas.fingerprint(), thermal.fingerprint()));
		}

		public boolean matches(Bundle bundle) {
			return bundle != null && profile().equals(bundle.profile());
		}

		private static void validateReferences(MaterialRegistry material, ChemicalRegistry chemical,
				GranularRegistry granular, GasRegistry gas, ThermalRegistry thermal) {
			for (ChemicalPhaseDefinition phase : chemical.phases()) {
				if (!material.hasMaterial(phase.materialId())) {
					throw new IllegalArgumentException("Chemical phase references unknown material " + phase.materialId());
				}
			}
			for (GranularCarrier carrier : granular.carriers()) {
				if (!material.hasMaterial(carrier.liquidMaterialId())) {
					throw new IllegalArgumentException("Granular carrier references unknown material "
							+ carrier.liquidMaterialId());
				}
			}
			for (GasComponentDefinition component : gas.components()) {
				for (String species : component.allowedSpecies()) if (!chemical.hasSpecies(species)) {
					throw new IllegalArgumentException("Gas component references unknown species " + species);
				}
			}
			Set<String> granularMaterialIds = new HashSet<>();
			granular.materials().forEach(definition -> granularMaterialIds.add(definition.id()));
			for (ThermalMaterialDefinition definition : thermal.materials()) {
				boolean known = switch (definition.carrierKind()) {
					case LIQUID -> material.hasMaterial(definition.carrierId());
					case GRANULAR -> granularMaterialIds.contains(definition.carrierId());
					case GAS -> gas.hasComponent(definition.carrierId());
					case COHERENT_SOLID, DEVICE -> true;
				};
				if (!known) throw new IllegalArgumentException("Thermal material references unknown carrier "
						+ definition.carrierId());
			}
			for (ThermalSpeciesOffset offset : thermal.speciesOffsets()) if (!chemical.hasSpecies(offset.speciesId())) {
				throw new IllegalArgumentException("Thermal offset references unknown species " + offset.speciesId());
			}
			for (GasLiquidSpeciesPair pair : thermal.gasLiquidPairs()) {
				GasComponentDefinition component = gas.component(pair.gasComponentId());
				ChemicalPhaseDefinition phase = chemical.phase(chemical.phaseHandle(pair.liquidPhaseId()));
				if (!chemical.hasSpecies(pair.speciesId()) || !component.allowsSpecies(pair.speciesId())
						|| !phase.allowedSpecies().contains(pair.speciesId())) {
					throw new IllegalArgumentException("Gas/liquid pair is not admitted by both registries " + pair.id());
				}
			}
		}
	}

	public record Bundle(
			WorldCapabilityBundleHead head,
			WorldCapabilityBundleManifest manifest,
			String manifestChecksum,
			WorldCapabilityProfile profile,
			MaterialRegistry material,
			ChemicalRegistry chemical,
			GranularRegistry granular,
			GasRegistry gas,
			ThermalRegistry thermal
	) {
		public Bundle {
			if (head == null || manifest == null || profile == null) {
				throw new IllegalArgumentException("World capability bundle metadata is incomplete");
			}
			head.requireManifest(manifest, manifestChecksum);
			Definitions definitions = new Definitions(material, chemical, granular, gas, thermal);
			if (!definitions.profile().equals(profile)) {
				throw new IllegalArgumentException("World capability bundle profile does not match its registries");
			}
		}
	}

	public record OpenResult(Bundle bundle, boolean created, boolean definitionMismatch, boolean recovered) {
		public OpenResult {
			if (bundle == null) throw new IllegalArgumentException("Opened world capability bundle is missing");
		}
	}

	public record QuarantineAdmission(long maximumEntries, long maximumBytes) {
		public QuarantineAdmission {
			if (maximumEntries < 0L || maximumBytes < 0L) {
				throw new IllegalArgumentException("Quarantine admission budget cannot be negative");
			}
		}

		private void requireCapacity(FileTreeStats existing, FileTreeStats incoming) throws IOException {
			try {
				if (Math.addExact(existing.entries(), incoming.entries()) > maximumEntries
						|| Math.addExact(existing.bytes(), incoming.bytes()) > maximumBytes) {
					throw new IOException("World capability staging quarantine is over its admission budget");
				}
			} catch (ArithmeticException overflow) {
				throw new IOException("World capability staging quarantine accounting overflow", overflow);
			}
		}
	}

	public enum PublicationPhase {
		PREPARATION_MANIFEST_WRITTEN,
		STAGING_MANIFEST_WRITTEN,
		MEMBER_WRITTEN,
		STAGING_VERIFIED,
		SLOT_PUBLISHED,
		HEAD_TEMPORARY_VERIFIED,
		HEAD_PUBLISHED
	}

	public record PublicationBoundary(PublicationPhase phase, String member) {
		public PublicationBoundary {
			if (phase == null || member == null) throw new IllegalArgumentException("Publication boundary is incomplete");
		}
	}

	@FunctionalInterface
	public interface FaultInjector {
		FaultInjector NONE = boundary -> { };
		void reached(PublicationBoundary boundary) throws IOException;
	}

	private record VerifiedSlot(
			WorldCapabilityBundleManifest manifest,
			String manifestChecksum,
			WorldCapabilityProfile profile,
			MaterialRegistry material,
			ChemicalRegistry chemical,
			GranularRegistry granular,
			GasRegistry gas,
			ThermalRegistry thermal
	) {
		private Bundle bundle(WorldCapabilityBundleHead head) {
			return new Bundle(head, manifest, manifestChecksum, profile, material, chemical, granular, gas, thermal);
		}
	}

	private record FileTreeStats(long entries, long bytes) { }
}
