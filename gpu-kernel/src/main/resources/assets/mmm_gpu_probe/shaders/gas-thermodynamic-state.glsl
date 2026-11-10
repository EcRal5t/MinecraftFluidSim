void publishCaloric(int outputOffset,double volume,double temperature,double mass,double r,double capacity){
    double pressure = GAS_MIXTURE_LIMIT * r * temperature / (GAS_MIXTURE_BULK * volume + r * temperature), density = mass / volume,
           gamma = gasStateHeaderWord(GAS_H_ISOTHERMAL) != 0 ? 1.0 : 1.0 + r / capacity;
    double gasScale=GAS_MIXTURE_BULK*volume/(GAS_MIXTURE_BULK*volume+r*temperature);
    double bulk=pressure*gasScale*(1.0LF+(gamma-1.0LF)*gasScale*GAS_MIXTURE_LIMIT/GAS_MIXTURE_BULK);
    double sound = sqrt(bulk / density), compliance = 1.0 / bulk;
    if (!(isFinite(pressure) && pressure > 0.0 && isFinite(density) && density > 0.0 && isFinite(capacity) &&
          capacity > 0.0 && isFinite(r) && r > 0.0 && isFinite(gamma) && gamma >= 1.0 && isFinite(bulk) && bulk > 0.0 &&
          isFinite(sound) && sound > 0.0 && isFinite(compliance) && compliance > 0.0)) {
        reportGasStateInvalid();
        return;
    }
    writeDouble(outputOffset, temperature);
    writeDouble(outputOffset + 2, pressure);
    writeDouble(outputOffset + 4, density);
    writeDouble(outputOffset + 6, capacity);
    writeDouble(outputOffset + 8, r);
    writeDouble(outputOffset + 10, gamma);
    writeDouble(outputOffset + 12, bulk);
    writeDouble(outputOffset + 14, sound);
    writeDouble(outputOffset + 16, compliance);
}
bool updateEnergy(int node){
    int cache=gasStateHeaderWord(GAS_H_CALORIC_CACHE)+8*node,outAt=gasStateHeaderWord(GAS_H_STATES)+18*node;
    double oldEnergy=readDouble(cache),mass=readDouble(cache+2),lower=readDouble(cache+4),upper=readDouble(cache+6);
    double capacity=readDouble(outAt+6),constant=readDouble(outAt+8),temperature=readDouble(outAt);
    if(!(mass>0.0LF&&capacity>0.0LF&&constant>0.0LF))return false;
    int target=gasStateField(GAS_INTERNAL_ENERGY)+2*node;
    double energy=readDouble(target);
    if(!isFinite(energy)){reportGasStateInvalid();return true;}
    if(gasStateHeaderWord(GAS_H_ISOTHERMAL)!=0){energy=oldEnergy;writeDouble(target,energy);}
    else temperature+=(energy-oldEnergy)/capacity;
    if(!(isFinite(temperature)&&temperature>=lower&&temperature<upper))return false;
    writeDouble(cache,energy);
    publishCaloric(outAt,1.0LF,temperature,mass,constant,capacity);
    return true;
}
void deriveGasState(int node,int stage,int count) {
    if (node >= count)
        return;
    int outputOffset = gasStateHeaderWord(GAS_H_STATES) + 18 * node;
    if(stage==6 && errors==0u && node<int(words[gasStateField(GAS_COUNTS)]) && updateEnergy(node))return;
    int cache=gasStateHeaderWord(GAS_H_CALORIC_CACHE)+8*node;writeDouble(cache+2,0.0LF);
    for (int i = 0; i < 9; i++)
        writeDouble(outputOffset + 2 * i, 0.0);
    if (errors != 0u)
        return;
    int actual = int(words[gasStateField(GAS_COUNTS)]);
    if (actual < 0 || actual > gasStateHeaderWord(GAS_H_NODE_CAPACITY)) {
        reportGasStateInvalid();
        return;
    }
    if (node >= actual)
        return;
    double volume = stage >= 5 ? 1.0LF : readDouble(gasStateHeaderWord(GAS_H_NODE_VOLUMES) + 2 * node), energy = readDouble(gasStateField(GAS_INTERNAL_ENERGY) + 2 * node);
    if (!isFinite(volume) || volume < 0.0 || !isFinite(energy)) {
        reportGasStateInvalid();
        return;
    }
    int first, last;
    double anchor, mass, r,hotCapacity,coldCapacity,hotBoundary,coldBoundary;
    if (!gasCaloricInventory(node, first, last, anchor, mass, r,hotCapacity,coldCapacity,hotBoundary,coldBoundary))
        return;
    double temperature = readDouble(gasCaloricHeader() + GAS_H_STANDARD_TEMPERATURE);
    if (first == last) {
        if (energy != 0.0) {
            reportGasStateInvalid();
            return;
        }
        writeDouble(outputOffset, temperature);
        writeDouble(outputOffset + 10, 1.0);
        return;
    }
    if (words[gasStateField(GAS_ENERGY_PRESENT)] == 0u || volume <= 0.0) {
        reportGasStateInvalid();
        return;
    }
    if (gasStateHeaderWord(GAS_H_ISOTHERMAL) != 0) {
        energy = anchor;
        writeDouble(gasStateField(GAS_INTERNAL_ENERGY) + 2 * node, anchor);
    }
    double capacity;
    if (!gasCaloricInvert(first,last,anchor,energy,hotCapacity,coldCapacity,hotBoundary,coldBoundary,temperature,capacity)) return;
    double standard=readDouble(gasCaloricHeader()+GAS_H_STANDARD_TEMPERATURE),lower,upper;
    if(temperature>=coldBoundary && temperature<hotBoundary){
        lower=temperature>=standard?standard:coldBoundary;
        upper=temperature>=standard?hotBoundary:standard;
    } else gasCaloricSegmentBounds(first,last,temperature,lower,upper);
    writeDouble(cache,energy);writeDouble(cache+2,mass);writeDouble(cache+4,lower);writeDouble(cache+6,upper);
    publishCaloric(outputOffset,volume,temperature,mass,r,capacity);
}
