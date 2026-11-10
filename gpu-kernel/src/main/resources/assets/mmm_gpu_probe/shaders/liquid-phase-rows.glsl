#ifndef MMM_PHASE_ROWS
#define MMM_PHASE_ROWS
// HOST_LAYOUT
const uint PHASE_ROWS_ERROR_INVALID_ROW = 2147483648u;
int phaseRowHeader(int header, int i) {
    return int(words[header + i]);
}
int phaseRowField(int header, int field) {
    return phaseRowHeader(header, LP_PHASE_FIELDS + field);
}
int phaseStockField(int header, int field) {
    return phaseRowHeader(header, LP_STOCK_FIELDS + field);
}
int64_t readPhaseRowInteger(int a) {
    return int64_t(uint64_t(words[a]) | (uint64_t(words[a + 1]) << 32));
}
void writePhaseRowInteger(int a, int64_t q) {
    words[a] = uint(q);
    words[a + 1] = uint(uint64_t(q) >> 32);
}
double readPhaseRowDouble(int a) {
    return packDouble2x32(uvec2(words[a], words[a + 1]));
}
void writePhaseRowDouble(int a, double q) {
    uvec2 b = unpackDouble2x32(q);
    words[a] = b.x;
    words[a + 1] = b.y;
}
int64_t phaseRowMass(int header, int row) {
    return readPhaseRowInteger(phaseRowField(header, LP_FIELD_MASS) + 2 * row);
}
double phaseRowEnergy(int header, int row) {
    return readPhaseRowDouble(phaseRowField(header, LP_FIELD_ENERGY) + 2 * row);
}
int64_t phaseRowSpecies(int header, int row, int species) {
    return readPhaseRowInteger(phaseRowField(header, LP_FIELD_SPECIES) + 2 * (row * phaseRowHeader(header, LP_H_SPECIES_COUNT) + species));
}
void writePhaseRowSpecies(int header, int row, int species, int64_t amount) {
    writePhaseRowInteger(phaseRowField(header, LP_FIELD_SPECIES) + 2 * (row * phaseRowHeader(header, LP_H_SPECIES_COUNT) + species), amount);
    if (amount != 0l) words[phaseRowField(header, LP_FIELD_MIXTURE) + row] = 1u;
}
double phaseRowVolume(int header, int row) {
    return readPhaseRowDouble(phaseRowField(header, LP_FIELD_BASE_VOLUME) + 2 * row) +
           double(readPhaseRowInteger(phaseRowField(header, LP_FIELD_DELTA_SVQ) + 2 * row)) *
               ldexp(double(readPhaseRowInteger(header + LP_H_VOLUME_SIGNIFICAND)), phaseRowHeader(header, LP_H_VOLUME_EXPONENT));
}
int findPhaseRow(int header, int cell, int material, int support) {
    if (cell < 0 || cell >= phaseRowHeader(header, LP_H_CELL_COUNT) || material < 0 ||
        material >= phaseRowHeader(header, LP_H_MATERIAL_COUNT))
        return -1;
    int row = cell * phaseRowHeader(header, LP_H_MATERIAL_COUNT) + material;
    return words[phaseRowField(header, LP_FIELD_LIVE) + row] != 0u ? row : -1;
}
// One producer owns all edits to a cell; material slots combine their connected support mask.
int createPhaseRow(int header, int cell, int material, int support) {
    if (cell < 0 || cell >= phaseRowHeader(header, LP_H_CELL_COUNT) || material < 0 ||
        material >= phaseRowHeader(header, LP_H_MATERIAL_COUNT)) {
        atomicOr(errors, PHASE_ROWS_ERROR_INVALID_ROW);
        return -1;
    }
    int row = cell * phaseRowHeader(header, LP_H_MATERIAL_COUNT) + material;
    words[phaseRowField(header, LP_FIELD_CELL) + row] = uint(cell);
    words[phaseRowField(header, LP_FIELD_MATERIAL) + row] = uint(material);
    words[phaseRowField(header, LP_FIELD_SUPPORT) + row] |= uint(support);
    words[phaseRowField(header, LP_FIELD_LIVE) + row] = 1u;
    return row;
}
#endif
