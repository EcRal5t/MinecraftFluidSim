// Inverts the same piecewise mixture caloric function for inventory and transfers.
double caloricStateRead(int at) {
    return packDouble2x32(uvec2(words[at], words[at + 1]));
}
double caloricStateTemperature(int header, int material, double mass, double ethanol, double extraCapacity,
                               double remaining, out double capacity) {
    int first = int(words[int(words[header + CAL_SEGMENT_OFFSETS]) + material]);
    int last = int(words[int(words[header + CAL_SEGMENT_OFFSETS]) + material + 1]) - 1;
    int segment = first;
    double temperature = caloricStateRead(int(words[header + CAL_REFERENCE_TEMPERATURE]) + 2 * material);
    while (segment < last && temperature >= caloricStateRead(int(words[header + CAL_SEGMENT_UPPER]) + 2 * segment))
        segment++;
    if (remaining >= 0.0LF) {
        for (;; segment++) {
            capacity = caloricCapacity(mass, ethanol, caloricStateRead(int(words[header + CAL_SEGMENT_CAPACITY]) + 2 * segment),caloricStateRead(header+CAL_ETHANOL_HEAT_CAPACITY))+extraCapacity;
            double upper = caloricStateRead(int(words[header + CAL_SEGMENT_UPPER]) + 2 * segment);
            double span = (upper - temperature) * capacity;
            if (segment == last || remaining < span) { temperature += remaining / capacity; break; }
            remaining -= span; temperature = upper;
        }
    } else {
        remaining = -remaining;
        for (;; segment--) {
            capacity = caloricCapacity(mass, ethanol, caloricStateRead(int(words[header + CAL_SEGMENT_CAPACITY]) + 2 * segment),caloricStateRead(header+CAL_ETHANOL_HEAT_CAPACITY))+extraCapacity;
            double lower = segment == first ? 0.0LF : caloricStateRead(int(words[header + CAL_SEGMENT_UPPER]) + 2 * (segment - 1));
            double span = (temperature - lower) * capacity;
            if (segment == first || remaining <= span) { temperature -= remaining / capacity; break; }
            remaining -= span; temperature = lower;
        }
    }
    return temperature;
}
