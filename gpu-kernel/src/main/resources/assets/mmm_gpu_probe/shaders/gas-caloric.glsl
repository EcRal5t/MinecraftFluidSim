// Gas inventory access is supplied by the embedding shader.
int gasCaloricHeaderWord(int index) { return int(words[gasCaloricHeader() + index]); }
void gasCaloricAddCompensated(inout double sum, inout double correction, double value) {
    precise double adjusted = value - correction;
    precise double next = sum + adjusted;
    correction = (next - sum) - adjusted;
    sum = next;
}
bool gasCaloricInventory(int node, out int first, out int last, out double anchor, out double mass, out double r,
               out double hotCapacity,out double coldCapacity,out double hotBoundary,out double coldBoundary) {
    first = int(words[gasField(GAS_ROW_COMPONENT_OFFSETS) + node]);
    last = int(words[gasField(GAS_ROW_COMPONENT_OFFSETS) + node + 1]);
    int components = int(words[gasField(GAS_COUNTS) + 1]), species = int(words[gasField(GAS_COUNTS) + 2]);
    if (first < 0 || last < first || last > components) {
        reportInvalid();
        return false;
    }
    anchor = 0.0;
    hotCapacity=0.0LF;coldCapacity=0.0LF;
    hotBoundary=packDouble2x32(uvec2(0u,0x7ff00000u));
    coldBoundary=readDouble(gasCaloricHeader()+GAS_H_MINIMUM_TEMPERATURE);
    double correction = 0.0,hotCorrection=0.0LF,coldCorrection=0.0LF;
    double standardTemperature=readDouble(gasCaloricHeader()+GAS_H_STANDARD_TEMPERATURE);
    int64_t totalMass = int64_t(0), totalNanos = int64_t(0);
    for (int component = first; component < last; component++) {
        int64_t componentMass = gasCaloricReadInt64(gasField(GAS_MASS_UNITS) + 2 * component),
                nanos = gasCaloricReadInt64(gasField(GAS_STANDARD_VOLUME_NANOS) + 2 * component);
        if (componentMass == int64_t(0) && nanos == int64_t(0))
            continue;
        int handle = int(words[gasField(GAS_COMPONENT_HANDLES) + component]);
        if (handle < 0 || handle >= gasCaloricHeaderWord(GAS_H_COMPONENT_COUNT)) {
            reportInvalid();
            return false;
        }
        int begin = int(words[gasCaloricHeaderWord(GAS_H_SEGMENT_OFFSETS) + handle]),
            end = int(words[gasCaloricHeaderWord(GAS_H_SEGMENT_OFFSETS) + handle + 1]);
        double reference = readDouble(gasCaloricHeaderWord(GAS_H_ANCHOR_ENERGY) + 2 * handle);
        if (begin == end || !isFinite(reference) || componentMass <= int64_t(0) || nanos <= int64_t(0) ||
            componentMass > int64_t(0x7fffffffffffffffUL) - totalMass ||
            nanos > int64_t(0x7fffffffffffffffUL) - totalNanos) {
            reportInvalid();
            return false;
        }
        totalMass += componentMass;
        totalNanos += nanos;
        double componentKg=double(componentMass)*1e-10LF;
        gasCaloricAddCompensated(anchor, correction, componentKg * reference);
        int hot=begin;
        while(hot+1<end && standardTemperature>=readDouble(gasCaloricHeaderWord(GAS_H_SEGMENT_UPPER)+2*hot))hot++;
        int cold=hot;
        if(hot>begin && standardTemperature==readDouble(gasCaloricHeaderWord(GAS_H_SEGMENT_UPPER)+2*(hot-1)))cold--;
        gasCaloricAddCompensated(hotCapacity,hotCorrection,componentKg*readDouble(gasCaloricHeaderWord(GAS_H_SEGMENT_CAPACITY)+2*hot));
        gasCaloricAddCompensated(coldCapacity,coldCorrection,componentKg*readDouble(gasCaloricHeaderWord(GAS_H_SEGMENT_CAPACITY)+2*cold));
        if(hot+1<end)hotBoundary=min(hotBoundary,readDouble(gasCaloricHeaderWord(GAS_H_SEGMENT_UPPER)+2*hot));
        if(cold>begin)coldBoundary=max(coldBoundary,readDouble(gasCaloricHeaderWord(GAS_H_SEGMENT_UPPER)+2*(cold-1)));
        int speciesBegin = int(words[gasField(GAS_COMPONENT_SPECIES_OFFSETS) + component]),
            speciesEnd = int(words[gasField(GAS_COMPONENT_SPECIES_OFFSETS) + component + 1]);
        if (speciesBegin < 0 || speciesEnd < speciesBegin || speciesEnd > species) {
            reportInvalid();
            return false;
        }
        for (int entry = speciesBegin; entry < speciesEnd; entry++) {
            int64_t amount = gasCaloricReadInt64(gasField(GAS_SPECIES_AMOUNTS) + 2 * entry);
            if (amount == int64_t(0))
                continue;
            int speciesHandle = int(words[gasField(GAS_SPECIES_HANDLES) + entry]);
            if (amount < int64_t(0) || speciesHandle < 0 || speciesHandle >= gasCaloricHeaderWord(GAS_H_SPECIES_STRIDE)) {
                reportInvalid();
                return false;
            }
            double offset = readDouble(gasCaloricHeaderWord(GAS_H_SPECIES_REFERENCE) + 2 * (handle * gasCaloricHeaderWord(GAS_H_SPECIES_STRIDE) + speciesHandle));
            if (!isFinite(offset)) {
                reportInvalid();
                return false;
            }
            gasCaloricAddCompensated(anchor, correction, double(amount) * offset);
        }
    }
    if (totalMass == int64_t(0) && totalNanos == int64_t(0))
        first = last;
    mass = double(totalMass) * 1e-10LF;
    r = double(totalNanos) * readDouble(gasCaloricHeader() + GAS_H_PRESSURE_FACTOR);
    if (!isFinite(anchor) || !isFinite(mass) || !isFinite(r)) {
        reportInvalid();
        return false;
    }
    return true;
}
// The union of material segment endpoints is traversed implicitly. Each iteration
// advances to the next real caloric endpoint, so there is no numerical iteration cap.
double gasCaloricCapacityAndBoundary(int first, int last, double temperature, bool heating, out double boundary) {
    boundary = heating ? packDouble2x32(uvec2(0u, 0x7ff00000u)) : readDouble(gasCaloricHeader() + GAS_H_MINIMUM_TEMPERATURE);
    double capacity = 0.0, correction = 0.0;
    for (int component = first; component < last; component++) {
        if (gasCaloricReadInt64(gasField(GAS_STANDARD_VOLUME_NANOS) + 2 * component) == int64_t(0))
            continue;
        int handle = int(words[gasField(GAS_COMPONENT_HANDLES) + component]);
        int begin = int(words[gasCaloricHeaderWord(GAS_H_SEGMENT_OFFSETS) + handle]),
            end = int(words[gasCaloricHeaderWord(GAS_H_SEGMENT_OFFSETS) + handle + 1]), segment = begin;
        while (segment + 1 < end) {
            double upper = readDouble(gasCaloricHeaderWord(GAS_H_SEGMENT_UPPER) + 2 * segment);
            if (heating ? temperature < upper : temperature <= upper)
                break;
            segment++;
        }
        double cp = readDouble(gasCaloricHeaderWord(GAS_H_SEGMENT_CAPACITY) + 2 * segment),
               mass = double(gasCaloricReadInt64(gasField(GAS_MASS_UNITS) + 2 * component)) * 1e-10LF;
        gasCaloricAddCompensated(capacity, correction, mass * cp);
        if (heating && segment + 1 < end)
            boundary = min(boundary, readDouble(gasCaloricHeaderWord(GAS_H_SEGMENT_UPPER) + 2 * segment));
        else if (!heating && segment > begin)
            boundary = max(boundary, readDouble(gasCaloricHeaderWord(GAS_H_SEGMENT_UPPER) + 2 * (segment - 1)));
    }
    return capacity;
}
void gasCaloricSegmentBounds(int first,int last,double temperature,out double lower,out double upper){
    lower=readDouble(gasCaloricHeader()+GAS_H_MINIMUM_TEMPERATURE);
    upper=packDouble2x32(uvec2(0u,0x7ff00000u));
    for(int component=first;component<last;component++){
        if(gasCaloricReadInt64(gasField(GAS_STANDARD_VOLUME_NANOS)+2*component)==int64_t(0))continue;
        int handle=int(words[gasField(GAS_COMPONENT_HANDLES)+component]);
        int begin=int(words[gasCaloricHeaderWord(GAS_H_SEGMENT_OFFSETS)+handle]);
        int end=int(words[gasCaloricHeaderWord(GAS_H_SEGMENT_OFFSETS)+handle+1]),segment=begin;
        while(segment+1<end && temperature>=readDouble(gasCaloricHeaderWord(GAS_H_SEGMENT_UPPER)+2*segment))segment++;
        if(segment>begin)lower=max(lower,readDouble(gasCaloricHeaderWord(GAS_H_SEGMENT_UPPER)+2*(segment-1)));
        if(segment+1<end)upper=min(upper,readDouble(gasCaloricHeaderWord(GAS_H_SEGMENT_UPPER)+2*segment));
    }
}
bool gasCaloricInvert(int first,int last,double anchor,double energy,double hotCapacity,double coldCapacity,
                      double hotBoundary,double coldBoundary,out double temperature,out double capacity) {
    temperature=readDouble(gasCaloricHeader()+GAS_H_STANDARD_TEMPERATURE);
    double residual = energy - anchor;
    if (!isFinite(residual)) {
        reportInvalid();
        return false;
    }
    bool heating = residual >= 0.0;
    double remaining=abs(residual);
    capacity=heating?hotCapacity:coldCapacity;
    double boundary=heating?hotBoundary:coldBoundary;
    while (true) {
        if (!(isFinite(capacity) && capacity > 0.0)) {
            reportInvalid();
            return false;
        }
        double step=remaining/capacity,previous=temperature;
        double span=heating?boundary-temperature:temperature-boundary;
        if(step<=span){
            temperature+=heating?step:-step;
            // The reported endpoint derivative belongs to the upper-temperature segment.
            if((heating && isFinite(boundary) && temperature>=boundary) || (!heating && temperature==previous))
                capacity=gasCaloricCapacityAndBoundary(first,last,temperature,true,boundary);
            break;
        }
        if (!(span > 0.0 && isFinite(span))) {
            reportInvalid();
            return false;
        }
        remaining-=span*capacity;
        temperature=boundary;
        capacity=gasCaloricCapacityAndBoundary(first,last,temperature,heating,boundary);
    }
    if (!isFinite(temperature) || temperature < readDouble(gasCaloricHeader() + GAS_H_MINIMUM_TEMPERATURE)) {
        reportInvalid();
        return false;
    }
    return true;
}
bool gasCaloricState(int node,double energy,out double temperature,out double capacity) {
    int first,last;
    double anchor,mass,r,hotCapacity,coldCapacity,hotBoundary,coldBoundary;
    if (!isFinite(energy)) { reportInvalid(); return false; }
    if (!gasCaloricInventory(node,first,last,anchor,mass,r,hotCapacity,coldCapacity,hotBoundary,coldBoundary)) return false;
    if (first==last) {
        temperature=readDouble(gasCaloricHeader()+GAS_H_STANDARD_TEMPERATURE); capacity=0.0LF; return true;
    }
    return gasCaloricInvert(first,last,anchor,energy,hotCapacity,coldCapacity,hotBoundary,coldBoundary,temperature,capacity);
}
