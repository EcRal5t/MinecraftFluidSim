#ifndef MMM_PHASE_ROWS
#define MMM_PHASE_ROWS
// HOST_LAYOUT
int lpH(int header, int i) {
    return int(words[header + i]);
}
int lpF(int header, int field) {
    return lpH(header, LP_PHASE_FIELDS + field);
}
int lpS(int header, int field) {
    return lpH(header, LP_STOCK_FIELDS + field);
}
int64_t lpInt(int a) {
    return int64_t(uint64_t(words[a]) | (uint64_t(words[a + 1]) << 32));
}
void lpIntAt(int a, int64_t q) {
    words[a] = uint(q);
    words[a + 1] = uint(uint64_t(q) >> 32);
}
double lpDouble(int a) {
    return packDouble2x32(uvec2(words[a], words[a + 1]));
}
void lpDoubleAt(int a, double q) {
    uvec2 b = unpackDouble2x32(q);
    words[a] = b.x;
    words[a + 1] = b.y;
}
int64_t lpMass(int header, int row) {
    return lpInt(lpF(header, LP_FIELD_MASS) + 2 * row);
}
double lpEnergy(int header, int row) {
    return lpDouble(lpF(header, LP_FIELD_ENERGY) + 2 * row);
}
int64_t lpSpecies(int header, int row, int species) {
    return lpInt(lpF(header, LP_FIELD_SPECIES) + 2 * (row * lpH(header, LP_H_SPECIES_COUNT) + species));
}
void lpSpeciesAt(int header, int row, int species, int64_t amount) {
    lpIntAt(lpF(header, LP_FIELD_SPECIES) + 2 * (row * lpH(header, LP_H_SPECIES_COUNT) + species), amount);
}
double lpVolume(int header, int row) {
    return lpDouble(lpF(header, LP_FIELD_BASE_VOLUME) + 2 * row) +
           double(lpInt(lpF(header, LP_FIELD_DELTA_SVQ) + 2 * row)) *
               ldexp(double(lpInt(header + LP_H_VOLUME_SIGNIFICAND)), lpH(header, LP_H_VOLUME_EXPONENT));
}
int lpFind(int header, int cell, int material, int support) {
    if (cell < 0 || cell >= lpH(header, LP_H_CELL_COUNT) || material < 0 ||
        material >= lpH(header, LP_H_MATERIAL_COUNT))
        return -1;
    int row = cell * lpH(header, LP_H_MATERIAL_COUNT) + material;
    return words[lpF(header, LP_FIELD_LIVE) + row] != 0u ? row : -1;
}
// One producer owns all edits to a cell; material slots combine their connected support mask.
int lpCreate(int header, int cell, int material, int support) {
    if (cell < 0 || cell >= lpH(header, LP_H_CELL_COUNT) || material < 0 ||
        material >= lpH(header, LP_H_MATERIAL_COUNT)) {
        atomicOr(errors, 33554432u);
        return -1;
    }
    int row = cell * lpH(header, LP_H_MATERIAL_COUNT) + material;
    words[lpF(header, LP_FIELD_CELL) + row] = uint(cell);
    words[lpF(header, LP_FIELD_MATERIAL) + row] = uint(material);
    words[lpF(header, LP_FIELD_SUPPORT) + row] |= uint(support);
    words[lpF(header, LP_FIELD_LIVE) + row] = 1u;
    return row;
}
#endif
