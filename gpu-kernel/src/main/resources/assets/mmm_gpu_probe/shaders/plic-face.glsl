// Area of the liquid side of a PLIC plane on a half-cell face, in cell units.
// The sorted projection CDF retains small corner cuts without polygon rounding.
double plicWetPort(dvec4 plane, int axis, double coordinate, int bit) {
    int a = (axis + 1) % 3, b = (axis + 2) % 3;
    dvec2 n = dvec2(plane[a], plane[b]);
    double scale = max(abs(n.x), abs(n.y));
    double offset = plane.w - plane[axis] * coordinate;
    if (scale == 0.0)
        return offset >= 0.0 ? .25LF : 0.0;
    n /= scale;
    double d = offset / scale - dot(n, .5LF * dvec2((bit >> a) & 1, (bit >> b) & 1))
               - .5LF * (min(n.x, 0.0LF) + min(n.y, 0.0LF));
    double small = min(abs(n.x), abs(n.y)) * .5LF, large = max(abs(n.x), abs(n.y)) * .5LF;
    if (d <= 0.0) return 0.0;
    if (d >= small + large) return .25LF;
    bool complement = d > .5LF * (small + large);
    if (complement) d = small + large - d;
    double fraction = small == 0.0 ? d / large
        : d <= small ? .5LF * (d / small) * (d / large)
                     : (d - .5LF * small) / large;
    return .25LF * (complement ? 1.0LF - fraction : fraction);
}
bool plicOpenPort(uint leftSupport, uint rightSupport, int high, int low) {
    return (leftSupport & (1u << high)) != 0u && (rightSupport & (1u << low)) != 0u;
}
