void product(uint64_t a, uint64_t b, out uint64_t hi, out uint64_t lo) {
    uint64_t x = uint64_t(uint(a)) * uint(b), y = (a >> 32) * uint(b), z = uint64_t(uint(a)) * (b >> 32);
    uint64_t mid = (x >> 32) + uint(y) + uint(z);
    lo = (mid << 32) | uint(x);
    hi = (a >> 32) * (b >> 32) + (y >> 32) + (z >> 32) + (mid >> 32);
}
// Divide one radix-2^32 digit. The floating estimate spans only 32 bits;
// exact wide-product correction determines the final quotient and remainder.
uint64_t divideDigit(uint64_t remainder, uint digit, uint64_t d, out uint64_t next) {
    uint64_t hi = remainder >> 32, lo = (remainder << 32) | uint64_t(digit);
    double estimate = (double(remainder) * 4294967296.0LF + double(digit)) / double(d);
    uint64_t q = uint64_t(clamp(floor(estimate), 0.0LF, 4294967295.0LF)), ph, pl;
    product(q, d, ph, pl);
    while (ph > hi || (ph == hi && pl > lo)) {
        q--;
        uint64_t before = pl;
        pl -= d;
        if (before < d)
            ph--;
    }
    uint64_t rest = lo - pl, restHi = hi - ph - (lo < pl ? uint64_t(1) : uint64_t(0));
    while (restHi != uint64_t(0) || rest >= d) {
        uint64_t before = rest;
        rest -= d;
        if (before < d)
            restHi--;
        q++;
    }
    next = rest;
    return q;
}
uint64_t divide(uint64_t a, uint64_t b, uint64_t d, out uint64_t remainder) {
    if (d == uint64_t(0)) {
        reportInvalid();
        remainder = uint64_t(0);
        return uint64_t(0);
    }
    if (a == uint64_t(0) || b == uint64_t(0)) {
        remainder = uint64_t(0);
        return uint64_t(0);
    }
    uint64_t hi, lo;
    product(a, b, hi, lo);
    if (hi >= d) {
        reportInvalid();
        remainder = uint64_t(0);
        return uint64_t(0);
    }
    if (d == SCALE) {
        remainder = lo & (SCALE - uint64_t(1));
        return (hi << 12) | (lo >> 52);
    }
    uint64_t middle, qHi = divideDigit(hi, uint(lo >> 32), d, middle),
                     qLo = divideDigit(middle, uint(lo), d, remainder);
    return (qHi << 32) | qLo;
}
uint64_t portion(uint64_t amount, uint64_t n, uint64_t total) {
    uint64_t remainder;
    return divide(amount, n, total, remainder);
}
int64_t signedPortion(int64_t amount, uint64_t n, uint64_t total) {
    bool negative = amount < int64_t(0);
    uint64_t magnitude = negative ? uint64_t(0) - uint64_t(amount) : uint64_t(amount);
    uint64_t result = portion(magnitude, n, total);
    return negative ? int64_t(uint64_t(0) - result) : int64_t(result);
}
