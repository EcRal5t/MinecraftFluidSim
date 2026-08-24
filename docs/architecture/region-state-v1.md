# `region-state v1` checkpoint design

This document defines the implemented first persistent Real Water region format. It is independent from
`takeover-journal v1`, the network protocol and GPU buffer layouts.

## Scope and migration boundary

- v1 persists only controller-managed pure-water CPU regions. Development `demo`, `capture` and `takeover` regions
  remain transient; persisting takeover fluid without shared world ownership would duplicate restored vanilla water.
- The physical liquid truth is every marker's `particleVolume`. Smooth P2G density is never serialized as a ledger.
- v1 records exact marker position/volume floats, marker velocity, the solid mask, the full CPU solver configuration,
  visible controller footprint, region/runtime identity and reserved budgets.
- This is the first format on the `26.2` branch. No earlier region-state format is accepted or overwritten. Unknown,
  corrupt or incomplete data is retained and reported as locked.
- The M3 material format `region-state v2` now exists behind the `materialsEnabled` gate. It keeps this v1 reader and
  v1's canonical bytes/CRC path untouched, dispatches on manifest version, and publishes a new full generation in the
  inactive slot before HEAD points at it. See [`m3-material-state-migration.md`](m3-material-state-migration.md).
- The M7 chemical format `region-state v3` exists behind `chemistryEnabled && materialsEnabled`. It preserves the v1/v2
  readers and canonical bytes while adding authoritative registry identity, canonical mixture tables, per-marker indicator,
  species/residual ledgers and bounded source/sink audit. See [`chemistry-state.md`](chemistry-state.md).
- `region-state v4` is the separately reviewed gas-persistence base generation in
  [`gas-state-v4.md`](gas-state-v4.md): material + optional chemistry + required component/topology gas state, with no
  granular or thermal fields. M8 granular state uses independent complete `region-state v5`; its skipped number never
  implies gas state and a v5 reader may not infer gas from runtime cavities.
- A committed v5 generation is one complete material + optional chemistry + granular checkpoint, not a sidecar attached to
  v3. Its manifest and every participating section require typed `realwater:granular_*_v1` extensions containing stable-ID
  palette identity, exact active/deposit SVQ and raw-mass ledgers, active parcel kinematics, deposit porosity and solid
  composition references plus cumulative source/sink audit. Every audit material ID is checked against the authoritative
  granular registry during readback; reactive live/audit payloads additionally require the same chemical capability and
  registry fingerprint. The outer manifest/section identity, CRC, slot and HEAD rules remain the only commit protocol.
- Enabling granular persistence atomically migrates accepted v1/v2/v3 generations to a new complete v5 generation in the
  inactive slot and publishes HEAD last. Missing world granular registry identity, absent required v5 extensions, unknown
  capability/unit bits, non-canonical records or ledger disagreement preserve the data and lock the region.
- Experimental files created by another branch are not compatible and are never auto-migrated or deleted.

## Layout and atomic commit

```text
<world>/realwater/region-state/
  <region-uuid>/
    HEAD.nbt
    slot-a/
      manifest.nbt
      section_<x>_<y>_<z>.nbt
    slot-b/
      manifest.nbt
      section_<x>_<y>_<z>.nbt
```

Each slot is an immutable complete generation while HEAD points at it. A checkpoint:

1. reads and validates the current HEAD when one exists;
2. writes every section and the manifest to a temporary form of the inactive slot;
3. forces each file, reads the temporary generation back and verifies all identity/checksum/ledger invariants;
4. atomically renames the temporary directory to the inactive slot;
5. writes and forces a temporary HEAD, then atomically replaces HEAD as the sole commit point.

A crash before step 5 leaves the previous HEAD and generation authoritative. A crash after step 5 exposes only the
already-verified new slot. Stale temporary or inactive data is never selected by guessing. Filesystems that cannot
provide the required atomic moves fail closed and keep the live solver paused in memory.

Logical deletion first atomically quarantines the whole region directory under a non-discoverable name. Physical
cleanup may be retried without allowing an old checkpoint to resurrect.

## Identity and completeness

Every section stores and checks:

- format and pure-water palette versions;
- region UUID, dimension, checkpoint generation and chunk-section coordinates;
- exact marker position/volume and velocity records;
- its rectangular solid-mask slice;
- optional type/length extension records;
- a CRC covering all of the above.

The manifest stores the same ownership identity, complete solver/runtime metadata, controller footprint, exact total
marker count/volume and a sorted descriptor for every section including its CRC, marker count and canonical volume.
Its CRC covers those descriptors. Restore refuses missing, extra, duplicated, mixed-generation or mixed-owner slices,
and independently recomputes marker count and canonical `particleVolume` total before allocating the solver.

## Lifecycle

- A missing associated chunk or disabled/broken controller first pauses the whole region, publishes a checkpoint, then
  releases the live solver and enters `UNLOADED`. Checkpoint failure keeps the solver allocated and paused.
- Startup reads only HEAD and the active manifest, admitting metadata under configured manager budgets without
  allocating marker/grid arrays. Marker data is read only when all required chunks and the controller boundary are
  ready.
- Restore always remains operator-paused. Current world collision is re-sampled before `/realwater run` can step, so a
  block edit made while unloaded cannot be bypassed by a stale solid mask.
- Normal server shutdown checkpoints every loaded controller region. `/realwater remove` or region replacement must
  logically delete its committed checkpoint before removing the manager entry.

## Failure and compatibility gates

The standalone gate injects failure before/after section writes, manifest publication, slot publication and HEAD
publication. Reopening storage after every fault must yield exactly the previous or the new complete generation, never
a mixed one. It also covers unknown versions, corrupt HEAD/manifest/section checksums, mixed UUID/dimension/generation,
missing/extra slices, marker-budget rejection, startup metadata admission, unload/load memory release, quarantine
deletion and v1 optional-section skipping. The gas extension covers strict v4 capability/registry/topology reconstruction;
the M8 extension additionally covers exact v1/v2/v3-to-v5 publication, authoritative granular-registry matching, required
v5 extension presence and granular inventory ledger reconstruction without changing the v1/v2/v3 canonical byte paths.
