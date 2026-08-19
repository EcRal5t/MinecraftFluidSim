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
  corrupt or incomplete data is retained and reported as locked. If a later v2 is needed, it must keep an explicit v1
  reader and publish a new generation before replacing v1's HEAD.
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
deletion and v1 optional-section skipping.
