// Axis-aligned liquid half-space clipped to one open half-cell face.
// The same rectangle describes wet walls and liquid/gas face openings.
bool plicPortRect(dvec4 plane, int faceAxis, double coordinate, int bit, bool liquid,
                  inout dvec2 lo, inout dvec2 hi) {
    int axis = plane.x != 0.0 ? 0 : plane.y != 0.0 ? 1 : 2;
    double normal = plane[axis], cut = plane.w * normal;
    if (axis == faceAxis) {
        bool wet = normal * coordinate <= plane.w;
        return liquid ? wet : !wet;
    }
    int tangent = (faceAxis + 1) % 3;
    int component = axis == tangent ? 0 : 1;
    bool below = liquid == (normal > 0.0);
    if (below) hi[component] = min(hi[component], cut);
    else lo[component] = max(lo[component], cut);
    return hi.x > lo.x && hi.y > lo.y;
}
// Contact includes the interface itself when it coincides with the queried face.
bool plicContactRect(dvec4 plane, int faceAxis, double coordinate, bool liquid,
                     inout dvec2 lo, inout dvec2 hi) {
    if (!liquid && plane[faceAxis] != 0.0LF)
        return plane[faceAxis] * coordinate >= plane.w;
    return plicPortRect(plane, faceAxis, coordinate, 0, liquid, lo, hi);
}
void plicPortBounds(int faceAxis, int bit, out dvec2 lo, out dvec2 hi) {
    int a = (faceAxis + 1) % 3, b = (faceAxis + 2) % 3;
    lo = .5LF * dvec2((bit >> a) & 1, (bit >> b) & 1);
    hi = lo + dvec2(.5LF);
}
double plicWetPort(dvec4 plane, int axis, double coordinate, int bit) {
    dvec2 lo, hi; plicPortBounds(axis, bit, lo, hi);
    if (!plicPortRect(plane, axis, coordinate, bit, true, lo, hi)) return 0.0;
    return (hi.x - lo.x) * (hi.y - lo.y);
}
bool plicOpenPort(uint leftSupport, uint rightSupport, int high, int low) {
    return (leftSupport & (1u << high)) != 0u && (rightSupport & (1u << low)) != 0u;
}
uint plicInternalInterfaceBoxes(dvec4 plane, uint solidMask, uint liquidSupport) {
    int axis = plane.x != 0.0LF ? 0 : plane.y != 0.0LF ? 1 : 2;
    double normal = plane[axis];
    if (normal == 0.0LF) return 0u;
    double cut = plane.w * normal;
    if (!(cut > 0.0LF && cut < 1.0LF)) return 0u;
    int a = (axis + 1) % 3, b = (axis + 2) % 3;
    uint boxes = 0u;
    for (int i = 0; i < 2; i++) for (int j = 0; j < 2; j++) {
        int low = (i << a) | (j << b), high = low | (1 << axis);
        bool open = cut == .5LF ? (solidMask & ((1u << low) | (1u << high))) == 0u
                                 : (solidMask & (1u << (cut < .5LF ? low : high))) == 0u;
        int liquidBox = cut == .5LF ? (normal > 0.0LF ? low : high) : (cut < .5LF ? low : high);
        if (open && (liquidSupport & (1u << liquidBox)) != 0u) boxes |= 1u << liquidBox;
    }
    return boxes;
}
