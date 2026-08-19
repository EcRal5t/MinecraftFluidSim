# M2 controller persistence smoke

Date: 2026-08-19

Minecraft: Java 26.2 / Fabric Loader 0.19.3 / Fabric API 0.157.0+26.2

Main Mod: 0.8.2, commit `9638353`

## Fixture

- One visible controller shell crossing four chunks in `minecraft:overworld`.
- Region UUID: `723dbb82-a5e4-470b-a4ab-f69436108184`.
- Origin/grid: `1006,101,1006`, `10x8x10` cells.
- Reservation: 800 cells / 6,400 markers.
- Exact initial marker ledger: 3,100 markers, 377.77777 cell-volume (47.22 block-volume).
- Reconstruction diagnostics: 400 marker cells, 500 visible cells and 300 dense cells.

The command fixture placed two vertical layers of vanilla source water. Minecraft reports the exposed top source
surface below a full block height, so the captured 47.22 block-volume is expected sampling input rather than loss.
Acceptance follows the exact marker ledger, not the smooth visible/dense reconstruction counts.

## Results

1. Leaving the four required chunks entered `UNLOADED/PAUSED`, published checkpoint 2, retained the full manager
   reservation, and reported 3,100 markers / 377.778 cell-volume without a live solver.
2. Returning restored `ACTIVE/PAUSED` with the same UUID, grid, marker count, exact volume and reconstruction counts.
3. Replacing one visible boundary block with air entered `UNLOADED/PAUSED`, identified the exact broken position and
   published checkpoint 3. Replacing the boundary restored the same paused state and ledger.
4. Manual checkpoint 4 published successfully. A normal exit/re-entry recovered the same UUID, 3,100 markers and
   377.77777 cell-volume as `ACTIVE/PAUSED`.
5. `/realwater remove` completed with no takeover blocks, cleared the registered region/client snapshot and did not
   leave a takeover journal or WARNING.

Together with `runFluidRegionPersistenceValidation`, lifecycle/manager/boundary/protocol gates and the prior protocol
client validation, this closes M2. No permanent chunk ticket, takeover expansion, journal-v1 change or GPU authority
change was introduced.
