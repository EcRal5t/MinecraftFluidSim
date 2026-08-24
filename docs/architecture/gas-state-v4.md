# Gas state and `region-state v4`

This document freezes the persistence prerequisite that M9 must finish before gas internal energy, boiling or
condensation may enter production. It replaces M5's runtime-only checkpoint semantics without changing M5's
isothermal pressure law. `region-state v4` contains material state, optional M7 chemistry and the gas base state;
it contains neither M8 granular state nor M9 thermal state.

## Authoritative truth and derived state

For every sealed node, the authoritative gas inventory is a canonical stable-component map. Every component owns
three independent columns:

- exact standard-volume nanos at the frozen standard state `(101325 Pa, 293.15 K)`;
- raw finite non-negative mass in solver mass units;
- an optional canonical map of stable chemical-species IDs to exact M7 quanta, plus a raw finite mass residual.

The component stable ID selects its gas/EoS identity; it is not a display label. Mass is never recomputed from
standard volume during transfer or restore. Species and residual are never reconstructed from the current data pack.
Creation from an explicit ambient/source definition may calculate the initial columns once, after which they are
independent truth.

Node topology is authoritative as the complete per-cell sealed-membership map plus the canonical node key (the
smallest region-local cell ordinal belonging to the node). Runtime node handles are not persisted. Ambient cells,
liquid/solid occupied cells, available volume, pressure, geometric orifice edges and flow rates remain derived.
Available volume and edges are rebuilt from the jointly committed marker/solid geometry. A restore must reproduce
the saved sealed-membership map exactly before it may bind inventory; mismatch preserves the generation and locks
the region instead of guessing a remap.

## Registry and identity

Gas component and ambient definitions form an immutable, stable-ID-sorted world registry snapshot. Its canonical
fingerprint is saved in every gas manifest. A component definition freezes its standard mass density and optional
default species inventory at the standard state. The ambient definition freezes exact component fractions and is
the only implicit reservoir used by M5 inflow/new-cavity bootstrap.

The first production registry contains `realwater:air`. Later adding `realwater:water_vapor` or data-pack gas
components requires an explicit registry migration; it cannot reinterpret existing nodes. Unknown registry content,
missing identity, fingerprint mismatch, unknown component/species ID or an unavailable required chemistry snapshot
preserves files and locks all affected regions.

## Transfer, topology and audit

Every mutation is planned across standard volume, mass, species and residual before commit:

- node merge sums each component column independently;
- node split distributes every component by child available-volume share; integer nanos/species use deterministic
  largest remainder and raw mass/residual use the same source fraction with the final child receiving the floating
  remainder;
- an orifice moves a proportional slice of every source component, carrying its mass/species/residual rather than
  recreating them at the target;
- venting moves all columns to the component sink ledger;
- ambient inflow/new cavity creation instantiates the frozen ambient mixture and moves the same columns into the
  component source ledger.

For every stable component, exact nanos and species identities close as

```text
live + sink = initial + source
```

Raw mass and residual use exact IEEE-754 dyadic audit sums; representation roundoff is recorded separately and is
not a source or sink. Topology changes have no independent material source. Overflow, negative columns, a partial
component move, non-canonical ordering or any ledger disagreement rejects the whole plan.

M5 pressure remains the isothermal derived law `p_abs = P0 * Vstd_total / Vavailable`; existing numerical pressure
clamps remain an M5 diagnostic until M9 binds gas energy. Under the later thermal gate, reaching the validity window
is PAUSED rather than clipped, and gas node `U`, compression work and enthalpy flow are added only by complete v6.

## `region-state v4` layout

The outer slot, section, CRC and HEAD protocol is unchanged. A complete v4 generation requires:

- the normal v2 material fields and optional v3 chemical fields;
- one `realwater:gas_manifest_v1` extension containing capability/unit identifiers, gas-registry and optional
  chemistry fingerprint, canonical component/node ledgers, source/sink/roundoff audit and per-section topology counts;
- one `realwater:gas_section_v1` extension in every section containing its full cell membership array. Values are
  `0` for non-sealed cells or `nodeOrdinal + 1` for a manifest node; node ordinals follow canonical node-key order.

The manifest contains no available volume, pressure or edge. Section membership cardinalities and canonical node
keys are recomputed at capture/readback/restore. Missing, duplicate or unknown gas extensions are invalid even when
the region happens to contain zero sealed nodes.

Readers accept exact outer versions `{1,2,3,4,5,6}`. v4 is decoded only as the gas capability above; v5 never infers
gas from its skipped number, and v6 may reuse exactly the reviewed gas payload while requiring the independent
thermal payload and gas-energy ordinal for every saved node. v4 cannot contain granular or thermal extensions.

## Migration and lifecycle

v1/v2/v3 may enter v4 only through an explicit operator gas bootstrap record that names the source generation,
registry fingerprint and ambient definition. It rebuilds topology from the committed geometry, initializes every
sealed node from the ambient source, writes and verifies the inactive v4 slot, and publishes HEAD last. There is no
automatic ambient guess on ordinary load. v5 does not migrate backward to v4.

After v4 exists, an explicit thermal initialization may publish complete v6 and reuse its gas component/topology
truth while creating node `U` at the operator-supplied temperature. A topology change after a valid restore is a
normal live split/merge/vent/source event and must use the four-column transfer plan above.

## Validation boundary

The closest `air-network` gate owns component transfer and exact audit. The `region-state` gate owns strict v4
dispatch, registry identity, required extensions, topology/cardinality/ledger reconstruction, atomic publication and
legacy rejection. Thermal validation does not authorize gas persistence until both gates pass this frozen contract.
