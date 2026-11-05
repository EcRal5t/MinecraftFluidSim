// Material energy and its derivative use the same uploaded caloric endpoints as the EOS.
void material(int handle, double temperature, out double energy, out double capacity) {
    int begin = int(words[sourceEosWord(GAS_H_SEGMENT_OFFSETS) + handle]),
        end = int(words[sourceEosWord(GAS_H_SEGMENT_OFFSETS) + handle + 1]);
    energy = readDouble(sourceEosWord(GAS_H_ANCHOR_ENERGY) + 2 * handle);
    capacity = 0.0;
    if (begin == end || !isFinite(energy)) {
        reportInvalid();
        return;
    }
    double current = readDouble(headerWord(H_SOURCE_EOS) + GAS_H_STANDARD_TEMPERATURE);
    bool heating = temperature >= current;
    while (current != temperature) {
        int segment = begin;
        while (segment + 1 < end && (heating ? current >= readDouble(sourceEosWord(GAS_H_SEGMENT_UPPER) + 2 * segment)
                                             : current > readDouble(sourceEosWord(GAS_H_SEGMENT_UPPER) + 2 * segment)))
            segment++;
        double bound = heating ? (segment + 1 < end ? readDouble(sourceEosWord(GAS_H_SEGMENT_UPPER) + 2 * segment) : temperature)
                               : (segment > begin ? readDouble(sourceEosWord(GAS_H_SEGMENT_UPPER) + 2 * (segment - 1)) : temperature);
        double next = heating ? min(temperature, bound) : max(temperature, bound);
        energy += (next - current) * readDouble(sourceEosWord(GAS_H_SEGMENT_CAPACITY) + 2 * segment);
        current = next;
    }
    int segment = begin;
    while (segment + 1 < end && temperature >= readDouble(sourceEosWord(GAS_H_SEGMENT_UPPER) + 2 * segment))
        segment++;
    capacity = readDouble(sourceEosWord(GAS_H_SEGMENT_CAPACITY) + 2 * segment);
}
