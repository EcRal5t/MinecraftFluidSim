package mmm.simulation.persistence;

import mmm.chemistry.ChemicalPhaseDefinition;
import mmm.chemistry.ChemicalRegistry;
import mmm.chemistry.ChemicalRegistryCodec;
import mmm.fluid.MaterialRegistry;
import mmm.fluid.MaterialRegistryCodec;
import mmm.fluid.air.GasComponentDefinition;
import mmm.fluid.air.GasRegistry;
import mmm.fluid.air.GasRegistryCodec;
import mmm.granular.GranularCarrier;
import mmm.granular.GranularRegistry;
import mmm.granular.GranularRegistryCodec;
import mmm.thermal.GasLiquidSpeciesPair;
import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalRegistry;
import mmm.thermal.ThermalRegistryCodec;
import mmm.thermal.ThermalSpeciesOffset;
import mmm.worldprofile.RegistryFingerprintSet;
import mmm.worldprofile.WorldCapability;
import mmm.worldprofile.WorldCapabilityProfile;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Publishes and restores the immutable world-capability bundle: the frozen registry snapshots plus the
 * profile that is the only interpretation of every region payload of the world.
 *
 * <p>HEAD is the sole commit point. A slot is only staging until the HEAD that binds it has been
 * atomically moved into place, so an interrupted publication can always be completed, quarantined or
 * refused without ever presenting a half-written bundle as authoritative.
 */
public final class WorldCapabilityBundleStore {
	private static final String HEAD_FILE = "HEAD.nbt";
	private static final String HEAD_TEMPORARY_FILE = "HEAD.nbt.tmp";
	private static final String SLOT_DIRECTORY = "slot-a";
	private static final String SLOT_TEMPORARY_DIRECTORY = "slot-a.tmp";
	private static final String MANIFEST_FILE = "manifest.nbt";
	/** The bundle is immutable once published: registries never change after the world's first admission. */
	private static final long GENERATION = 1L;
	private static final String POLICY_FILE = "server.properties";
	/**
	 * Persistence domains that carry their own committed truth and therefore require a committed bundle.
	 * Registry snapshots are not here: the bundle is their only root, so a per-kind registry directory is
	 * an unknown entry and locks the world rather than being adopted or migrated.
	 */
	private static final Set<String> AUTHORITY_ROOTS = Set.of(
			"container-transfer", "ownership", "region-state");
	/** Domains this store may create or read without proving authority. */
	private static final Set<String> NON_AUTHORITY_ROOTS = Set.of(
			"quarantine", POLICY_FILE, "world-capabilities");

	private final Path mmmDirectory;
	private final Path directory;
	private final Path quarantineRoot;
	private final QuarantineAdmission quarantineAdmission;

	public WorldCapabilityBundleStore(Path worldRoot, QuarantineAdmission quarantineAdmission) {
		if (worldRoot == null || quarantineAdmission == null) {
			throw new IllegalArgumentException("World capability bundle store configuration is incomplete");
		}
		this.mmmDirectory = worldRoot.toAbsolutePath().normalize().resolve("mmm");
		this.directory = mmmDirectory.resolve("world-capabilities");
		this.quarantineRoot = mmmDirectory.resolve("quarantine").resolve("world-capability-staging");
		this.quarantineAdmission = quarantineAdmission;
	}

	public OpenResult openOrPublish(Definitions definitions) throws IOException {
		return openOrPublish(definitions, FaultInjector.NONE);
	}

	/** Classifies directory entries and global authority presence without decoding any payload. */
	public Presence inspect() throws IOException {
		if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
			validateMmmInventory(false);
			return Presence.EMPTY;
		}
		requireDirectory(directory, "world capability root");
		Set<String> entries = entryNames(directory);
		boolean committed = entries.contains(HEAD_FILE);
		validateMmmInventory(committed);
		if (committed) {
			requireExact(entries, Set.of(HEAD_FILE, SLOT_DIRECTORY), "committed world capability root");
			return Presence.COMMITTED;
		}
		if (entries.isEmpty()) return Presence.EMPTY;
		if (entries.equals(Set.of(SLOT_DIRECTORY))
				|| entries.equals(Set.of(SLOT_DIRECTORY, HEAD_TEMPORARY_FILE))) {
			return Presence.COMMIT_CANDIDATE;
		}
		if (entries.equals(Set.of(SLOT_TEMPORARY_DIRECTORY))) return Presence.STAGING;
		throw new IOException("World capability root has an unprovable uncommitted layout: " + entries);
	}

	/**
	 * Completes HEAD publication from a full slot without consulting current definition sources, so an
	 * interrupted publication can be finished even when the datapack no longer decodes.
	 */
	public Bundle recoverCommitCandidate() throws IOException {
		return recoverCommitCandidate(FaultInjector.NONE);
	}

	public Bundle recoverCommitCandidate(FaultInjector faultInjector) throws IOException {
		if (faultInjector == null) {
			throw new IllegalArgumentException("World capability recovery fault injector is missing");
		}
		if (inspect() != Presence.COMMIT_CANDIDATE) {
			throw new IOException("World capability root has no complete commit candidate");
		}
		VerifiedSlot slot = verifySlot(slotPath());
		if (Files.exists(headTemporaryPath(), LinkOption.NOFOLLOW_LINKS)) {
			requireRegularFile(headTemporaryPath(), "world capability temporary HEAD");
			requireFileBudget(headTemporaryPath(), WorldCapabilityBundleMetadataCodec.MAX_HEAD_BYTES,
					"world capability temporary HEAD");
			requireHeadBinding(WorldCapabilityBundleMetadataCodec.readHead(headTemporaryPath()), slot);
			DurableFileOperations.atomicMove(headTemporaryPath(), headPath());
			DurableFileOperations.forceDirectory(directory);
			faultInjector.reached(new PublicationBoundary(PublicationPhase.HEAD_PUBLISHED, HEAD_FILE));
		} else {
			publishHead(slot, faultInjector);
		}
		return readCommitted();
	}

	/**
	 * Authority roots may only exist under a committed bundle. Anything else under `mmm/` is unprovable
	 * and is left byte for byte rather than adopted, reinterpreted or migrated.
	 */
	private void validateMmmInventory(boolean committed) throws IOException {
		if (!Files.exists(mmmDirectory, LinkOption.NOFOLLOW_LINKS)) return;
		requireDirectory(mmmDirectory, "MMM persistence root");
		Set<String> entries = entryNames(mmmDirectory);
		Set<String> known = new HashSet<>(AUTHORITY_ROOTS);
		known.addAll(NON_AUTHORITY_ROOTS);
		if (!known.containsAll(entries)) {
			Set<String> unknown = new HashSet<>(entries);
			unknown.removeAll(known);
			throw new IOException("MMM persistence root contains unknown entries: " + unknown);
		}
		Set<String> authority = new HashSet<>(entries);
		authority.retainAll(AUTHORITY_ROOTS);
		if (!committed && !authority.isEmpty()) {
			throw new IOException("MMM authority exists without a committed world capability bundle: " + authority);
		}
		for (String name : entries) {
			Path entry = mmmDirectory.resolve(name);
			if (name.equals(POLICY_FILE)) requireRegularFile(entry, "MMM policy configuration");
			else requireDirectory(entry, "MMM persistence domain " + name);
		}
	}

	public OpenResult openOrPublish(Definitions definitions, FaultInjector faultInjector) throws IOException {
		if (definitions == null || faultInjector == null) {
			throw new IllegalArgumentException("World capability bundle open is incomplete");
		}
		if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
			DurableFileOperations.createDirectories(directory);
			return new OpenResult(publish(definitions, faultInjector), true, false, false);
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
			requireHeadBinding(WorldCapabilityBundleMetadataCodec.readHead(headTemporaryPath()), slot);
			DurableFileOperations.atomicMove(headTemporaryPath(), headPath());
			DurableFileOperations.forceDirectory(directory);
			faultInjector.reached(new PublicationBoundary(PublicationPhase.HEAD_PUBLISHED, HEAD_FILE));
			Bundle bundle = readCommitted();
			return new OpenResult(bundle, false, !definitions.matches(bundle), true);
		}
		if (entries.equals(Set.of(SLOT_DIRECTORY))) {
			publishHead(verifySlot(slotPath()), faultInjector);
			Bundle bundle = readCommitted();
			return new OpenResult(bundle, false, !definitions.matches(bundle), true);
		}
		if (entries.equals(Set.of(SLOT_TEMPORARY_DIRECTORY))) {
			quarantineStaging();
			return new OpenResult(publish(definitions, faultInjector), true, false, true);
		}
		if (entries.isEmpty()) return new OpenResult(publish(definitions, faultInjector), true, false, false);
		throw new IOException("World capability root has an unprovable uncommitted layout: " + entries);
	}

	/** Reads the committed bundle; the caller must have proved HEAD binds a verified slot. */
	public Bundle readCommitted() throws IOException {
		requireDirectory(directory, "world capability root");
		requireExact(entryNames(directory), Set.of(HEAD_FILE, SLOT_DIRECTORY), "committed world capability root");
		requireRegularFile(headPath(), "world capability HEAD");
		requireFileBudget(headPath(), WorldCapabilityBundleMetadataCodec.MAX_HEAD_BYTES, "world capability HEAD");
		WorldCapabilityBundleHead head = WorldCapabilityBundleMetadataCodec.readHead(headPath());
		VerifiedSlot slot = verifySlot(slotPath());
		requireHeadBinding(head, slot);
		return slot.bundle(head);
	}

	private Bundle publish(Definitions definitions, FaultInjector faultInjector) throws IOException {
		requireExact(entryNames(directory), Set.of(), "empty world capability root");
		WorldCapabilityProfile profile = definitions.profile();
		UUID transaction = UUID.randomUUID();
		TreeMap<String, byte[]> members = memberBytes(definitions, profile);
		ArrayList<WorldCapabilityBundleManifest.FileEntry> files = new ArrayList<>(members.size());
		for (Map.Entry<String, byte[]> member : members.entrySet()) {
			files.add(new WorldCapabilityBundleManifest.FileEntry(
					member.getKey(), member.getValue().length, sha256(member.getValue())));
		}
		WorldCapabilityBundleManifest manifest = new WorldCapabilityBundleManifest(
				WorldCapabilityBundleManifest.SCHEMA, GENERATION, transaction,
				profile.profileFingerprint(), profile.aggregateFingerprint(), files);

		prepareStagingManifest(manifest, faultInjector);
		for (Map.Entry<String, byte[]> member : members.entrySet()) {
			Path memberPath = slotTemporaryPath().resolve(member.getKey());
			Files.write(memberPath, member.getValue());
			forceFile(memberPath);
			DurableFileOperations.forceDirectory(slotTemporaryPath());
			faultInjector.reached(new PublicationBoundary(PublicationPhase.MEMBER_WRITTEN, member.getKey()));
		}
		VerifiedSlot staged = verifySlot(slotTemporaryPath());
		faultInjector.reached(new PublicationBoundary(PublicationPhase.STAGING_VERIFIED, ""));
		DurableFileOperations.atomicMove(slotTemporaryPath(), slotPath());
		DurableFileOperations.forceDirectory(directory);
		faultInjector.reached(new PublicationBoundary(PublicationPhase.SLOT_PUBLISHED, SLOT_DIRECTORY));
		publishHead(staged, faultInjector);
		return readCommitted();
	}

	/**
	 * Writes the manifest inside the quarantine root first and moves it into place, so a crash before the
	 * move leaves a fully named staging tree rather than an unnamed directory inside the bundle root.
	 */
	private void prepareStagingManifest(
			WorldCapabilityBundleManifest manifest, FaultInjector faultInjector
	) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		NbtIo.writeCompressed(WorldCapabilityBundleMetadataCodec.encodeManifest(manifest), output);
		byte[] manifestBytes = output.toByteArray();
		FileTreeStats existing = treeStats(quarantineRoot);
		quarantineAdmission.requireCapacity(existing, new FileTreeStats(2L, manifestBytes.length));
		DurableFileOperations.createDirectories(quarantineRoot);
		Path preparation = quarantineRoot.resolve(manifest.publicationTransactionId() + ".preparing");
		if (Files.exists(preparation, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("World capability publication preparation already exists");
		}
		Files.createDirectory(preparation);
		DurableFileOperations.forceDirectory(quarantineRoot);
		Path manifestPath = preparation.resolve(MANIFEST_FILE);
		Files.write(manifestPath, manifestBytes);
		forceFile(manifestPath);
		DurableFileOperations.forceDirectory(preparation);
		faultInjector.reached(new PublicationBoundary(
				PublicationPhase.PREPARATION_MANIFEST_WRITTEN, MANIFEST_FILE));
		DurableFileOperations.atomicMove(preparation, slotTemporaryPath());
		DurableFileOperations.forceDirectory(quarantineRoot);
		DurableFileOperations.forceDirectory(directory);
		faultInjector.reached(new PublicationBoundary(
				PublicationPhase.STAGING_MANIFEST_WRITTEN, SLOT_TEMPORARY_DIRECTORY));
	}

	private void publishHead(VerifiedSlot slot, FaultInjector faultInjector) throws IOException {
		WorldCapabilityBundleHead head = new WorldCapabilityBundleHead(
				WorldCapabilityBundleHead.SCHEMA, slot.manifest().generation(),
				slot.manifest().publicationTransactionId(), slot.manifest().profileFingerprint(),
				slot.manifest().aggregateFingerprint(), slot.manifestChecksum());
		WorldCapabilityBundleMetadataCodec.writeHead(headTemporaryPath(), head);
		forceFile(headTemporaryPath());
		DurableFileOperations.forceDirectory(directory);
		requireHeadBinding(WorldCapabilityBundleMetadataCodec.readHead(headTemporaryPath()), slot);
		faultInjector.reached(new PublicationBoundary(
				PublicationPhase.HEAD_TEMPORARY_VERIFIED, HEAD_TEMPORARY_FILE));
		DurableFileOperations.atomicMove(headTemporaryPath(), headPath());
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
		String manifestChecksum = sha256(Files.readAllBytes(manifestPath));
		for (WorldCapabilityBundleManifest.FileEntry entry : manifest.files()) {
			Path member = slot.resolve(entry.name());
			requireRegularFile(member, "world capability member " + entry.name());
			if (Files.size(member) != entry.byteLength() || !sha256(Files.readAllBytes(member)).equals(entry.checksum())) {
				throw new IOException("World capability member does not match manifest: " + entry.name());
			}
		}

		WorldCapabilityProfile profile = WorldCapabilityProfileCodec.read(slot.resolve("profile.nbt"));
		MaterialRegistry material = MaterialRegistryCodec.readSnapshot(slot.resolve("material-registry.nbt"));
		ChemicalRegistry chemical = ChemicalRegistryCodec.readSnapshot(slot.resolve("chemical-registry.nbt"));
		GranularRegistry granular = GranularRegistryCodec.readSnapshot(
				slot.resolve("granular-registry.nbt"), chemical);
		GasRegistry gas = GasRegistryCodec.readSnapshot(slot.resolve("gas-registry.nbt"));
		ThermalRegistry thermal = ThermalRegistryCodec.readSnapshot(slot.resolve("thermal-registry.nbt"),
				chemical.fingerprint(), granular.fingerprint(), gas.fingerprint());
		Definitions definitions;
		try {
			definitions = new Definitions(profile.capabilities(), material, chemical, granular, gas, thermal);
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

	/**
	 * Stale staging is never deleted: it is moved into the quarantine root under its publication
	 * transaction so the next open can publish while the interrupted attempt stays auditable.
	 */
	private void quarantineStaging() throws IOException {
		Path staging = slotTemporaryPath();
		requireDirectory(staging, "world capability staging");
		Set<String> entries = entryNames(staging);
		Set<String> allowed = new HashSet<>(WorldCapabilityBundleManifest.REQUIRED_FILES);
		allowed.add(MANIFEST_FILE);
		if (!allowed.containsAll(entries)) {
			throw new IOException("World capability staging contains unknown entries: " + entries);
		}
		if (!entries.contains(MANIFEST_FILE)) {
			throw new IOException("World capability staging has no publication identity");
		}
		Path manifestPath = staging.resolve(MANIFEST_FILE);
		requireRegularFile(manifestPath, "world capability staging manifest");
		requireFileBudget(manifestPath, WorldCapabilityBundleMetadataCodec.MAX_MANIFEST_BYTES,
				"world capability staging manifest");
		WorldCapabilityBundleManifest manifest = WorldCapabilityBundleMetadataCodec.readManifest(manifestPath);
		int writtenMembers = entries.size() - 1;
		for (int index = 0; index < WorldCapabilityBundleManifest.REQUIRED_FILES.size(); index++) {
			String name = WorldCapabilityBundleManifest.REQUIRED_FILES.get(index);
			if (entries.contains(name) != (index < writtenMembers)) {
				throw new IOException("World capability staging member sequence is not writer-produced");
			}
			if (!entries.contains(name)) continue;
			Path member = staging.resolve(name);
			requireRegularFile(member, "world capability staging member " + name);
			WorldCapabilityBundleManifest.FileEntry expected = manifest.file(name);
			if (Files.size(member) != expected.byteLength()
					|| !sha256(Files.readAllBytes(member)).equals(expected.checksum())) {
				throw new IOException("World capability staging member is corrupt: " + name);
			}
		}
		Path destination = quarantineRoot.resolve(manifest.publicationTransactionId().toString());
		if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("World capability staging quarantine transaction already exists");
		}
		FileTreeStats staged = treeStats(staging);
		quarantineAdmission.requireCapacity(treeStats(quarantineRoot),
				new FileTreeStats(addExact(staged.entries(), 1L, "quarantine accounting"), staged.bytes()));
		DurableFileOperations.createDirectories(quarantineRoot);
		DurableFileOperations.atomicMove(staging, destination);
		DurableFileOperations.forceDirectory(quarantineRoot);
		DurableFileOperations.forceDirectory(directory);
	}

	private static TreeMap<String, byte[]> memberBytes(
			Definitions definitions, WorldCapabilityProfile profile
	) throws IOException {
		TreeMap<String, byte[]> members = new TreeMap<>();
		members.put("chemical-registry.nbt", ChemicalRegistryCodec.snapshotBytes(definitions.chemical()));
		members.put("gas-registry.nbt", GasRegistryCodec.snapshotBytes(definitions.gas()));
		members.put("granular-registry.nbt", GranularRegistryCodec.snapshotBytes(definitions.granular()));
		members.put("material-registry.nbt", MaterialRegistryCodec.snapshotBytes(definitions.material()));
		ByteArrayOutputStream profileOutput = new ByteArrayOutputStream();
		NbtIo.writeCompressed(WorldCapabilityProfileCodec.encode(profile), profileOutput);
		members.put("profile.nbt", profileOutput.toByteArray());
		members.put("thermal-registry.nbt", ThermalRegistryCodec.snapshotBytes(definitions.thermal()));
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
				if (!names.add(entry.getFileName().toString())) {
					throw new IOException("Duplicate directory entry " + entry.getFileName());
				}
			}
		}
		return Set.copyOf(names);
	}

	private static void requireDirectory(Path path, String context) throws IOException {
		if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException(context + " is not a plain directory: " + path);
		}
	}

	private static void requireRegularFile(Path path, String context) throws IOException {
		if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException(context + " is not a plain file: " + path);
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

	/** Walks an existing tree with a hard admission ceiling so an unbounded directory cannot be scanned. */
	private FileTreeStats treeStats(Path root) throws IOException {
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return new FileTreeStats(0L, 0L);
		requireDirectory(root, "quarantine tree");
		long entries = 0L;
		long bytes = 0L;
		try (var paths = Files.walk(root)) {
			for (Path path : (Iterable<Path>) paths::iterator) {
				if (path.equals(root)) continue;
				if (Files.isSymbolicLink(path)) throw new IOException("Quarantine tree contains a symbolic link");
				entries = addExact(entries, 1L, "quarantine entries");
				if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
					bytes = addExact(bytes, Files.size(path), "quarantine bytes");
				} else if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
					throw new IOException("Quarantine tree contains a non-regular entry");
				}
				quarantineAdmission.requireCapacity(new FileTreeStats(0L, 0L), new FileTreeStats(entries, bytes));
			}
		}
		return new FileTreeStats(entries, bytes);
	}

	private Path headPath() { return directory.resolve(HEAD_FILE); }
	private Path headTemporaryPath() { return directory.resolve(HEAD_TEMPORARY_FILE); }
	private Path slotPath() { return directory.resolve(SLOT_DIRECTORY); }
	private Path slotTemporaryPath() { return directory.resolve(SLOT_TEMPORARY_DIRECTORY); }

	private static long addExact(long first, long second, String context) throws IOException {
		try {
			return Math.addExact(first, second);
		} catch (ArithmeticException overflow) {
			throw new IOException("World capability " + context + " overflow", overflow);
		}
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException unavailable) {
			throw new IllegalStateException("SHA-256 unavailable", unavailable);
		}
	}

	/** One frozen registry snapshot set plus the capability set that interprets every region payload. */
	public record Definitions(
			Set<WorldCapability> capabilities,
			MaterialRegistry material,
			ChemicalRegistry chemical,
			GranularRegistry granular,
			GasRegistry gas,
			ThermalRegistry thermal
	) {
		public Definitions {
			if (capabilities == null || material == null || chemical == null || granular == null
					|| gas == null || thermal == null) {
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
			return WorldCapabilityProfile.of(capabilities, new RegistryFingerprintSet(
					material.fingerprint(), chemical.fingerprint(), granular.fingerprint(),
					gas.fingerprint(), thermal.fingerprint()));
		}

		public boolean matches(Bundle bundle) {
			return bundle != null && profile().equals(bundle.profile());
		}

		/** A bundle is only admissible when every cross-registry reference resolves in the frozen snapshot. */
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
				for (String species : component.allowedSpecies()) {
					if (!chemical.hasSpecies(species)) {
						throw new IllegalArgumentException("Gas component references unknown species " + species);
					}
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
				if (!known) {
					throw new IllegalArgumentException("Thermal material references unknown carrier "
							+ definition.carrierId());
				}
			}
			for (ThermalSpeciesOffset offset : thermal.speciesOffsets()) {
				if (!chemical.hasSpecies(offset.speciesId())) {
					throw new IllegalArgumentException("Thermal offset references unknown species " + offset.speciesId());
				}
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
			Definitions definitions = new Definitions(profile.capabilities(), material, chemical, granular, gas, thermal);
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
			if (addExact(existing.entries(), incoming.entries(), "quarantine entries") > maximumEntries
					|| addExact(existing.bytes(), incoming.bytes(), "quarantine bytes") > maximumBytes) {
				throw new IOException("World capability staging quarantine is over its admission budget");
			}
		}
	}

	/** Each phase publishes the named directory entry; the empty member publishes none. */
	public enum PublicationPhase {
		PREPARATION_MANIFEST_WRITTEN,
		STAGING_MANIFEST_WRITTEN,
		MEMBER_WRITTEN,
		STAGING_VERIFIED,
		SLOT_PUBLISHED,
		HEAD_TEMPORARY_VERIFIED,
		HEAD_PUBLISHED
	}

	public enum Presence {
		EMPTY,
		STAGING,
		COMMIT_CANDIDATE,
		COMMITTED
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
