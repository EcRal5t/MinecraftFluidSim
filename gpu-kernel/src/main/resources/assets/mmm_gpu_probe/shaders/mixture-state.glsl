int mixtureHeaderWord(int index){return int(words[mixtureHeader()+index]);}
double get(int address){return packDouble2x32(uvec2(words[address],words[address+1]));}
void put(int address,double value){uvec2 bits=unpackDouble2x32(value);words[address]=bits.x;words[address+1]=bits.y;}
bool finiteNumber(double value){return !isnan(value)&&!isinf(value);}
void invalid(){atomicOr(errors,ERROR_MIXTURE_STATE);}
void store(int cell,int quantity,double value){put(mixtureHeaderWord(8)+2*(7*cell+quantity),value);}
void evaluateMixture(int cell,double cellSize){
    double bulk=get(mixtureHeader()+16),reference=get(mixtureHeader()+18),h3=cellSize*cellSize*cellSize;
    double capacity=get(mixtureHeaderWord(5)+2*mixtureHeaderWord(6)*cell)*h3,v0=get(mixtureHeaderWord(1)+2*mixtureHeaderWord(2)*cell)*h3,mass=get(mixtureHeaderWord(3)+2*mixtureHeaderWord(4)*cell);
    int gas=mixtureHeaderWord(7)<0?-1:mixtureHeaderWord(7)+18*cell;double temperature=gas<0?293.15LF:get(gas),constant=gas<0?0.0LF:get(gas+8),gamma=gas<0?1.4LF:get(gas+10);
    if(!(finiteNumber(capacity)&&capacity>=0.0&&finiteNumber(v0)&&v0>=0.0&&finiteNumber(mass)&&mass>=0.0
        &&finiteNumber(temperature)&&temperature>0.0&&finiteNumber(constant)&&constant>=0.0&&finiteNumber(gamma)&&gamma>=1.0)){invalid();return;}
    double gasMass=gas<0?0.0LF:get(gas+4);
    if(capacity==0.0){
        if(v0!=0.0||mass!=0.0||constant!=0.0){atomicOr(errors,ERROR_ZERO_PORE);return;}
        for(int i=0;i<7;i++)store(cell,i,i==Q_LIQUID_SCALE?1.0LF:0.0LF);
        if(mixtureHeaderWord(9)>=0)for(int i=0;i<4;i++)put(mixtureHeaderWord(9)+8*cell+2*i,0.0LF);
        if(gas>=0)put(mixtureHeaderWord(10)+2*cell,0.0LF);return;
    }
    if(v0==0.0&&mass!=0.0){invalid();return;}
    double limit=bulk+reference,r=constant*temperature,pressure,scale;
    if(!finiteNumber(r)){invalid();return;}
    double norm;pressure=mixturePressure(capacity,v0,r,bulk,reference,norm);
    if(v0==0.0LF)scale=1.0LF;
    else if(r==0.0LF)scale=pressure>0.0LF?capacity/v0:limit/bulk;
    else scale=2.0LF*capacity*limit/(capacity*bulk+limit*v0+r+norm);
    double liquid=v0*scale,gasScale=v0>0.0LF?scale:capacity*limit/(capacity*bulk+r);
    double gasVolume=r>0.0LF?r*gasScale/pressure:max(0.0LF,capacity-liquid),density=liquid>0.0LF?mass/liquid:0.0LF;
    double gasCompliance=r>0.0LF?r*limit/(bulk*pressure*pressure*(1.0LF+(gamma-1.0LF)*gasScale)):0.0LF;
    double storage=(v0/bulk+gasCompliance)/h3,displacement=scale-1.0LF,elastic=.5LF*bulk*v0*displacement*displacement;
    if(!(finiteNumber(pressure)&&pressure>=0.0&&finiteNumber(scale)&&scale>0.0&&finiteNumber(liquid)&&liquid>=0.0
        &&finiteNumber(gasVolume)&&gasVolume>=0.0&&finiteNumber(density)&&density>=0.0&&finiteNumber(storage)&&storage>=0.0&&finiteNumber(elastic)&&elastic>=0.0)){invalid();return;}
    store(cell,Q_PRESSURE,pressure);store(cell,Q_LIQUID_VOLUME,liquid);store(cell,Q_GAS_VOLUME,gasVolume);store(cell,Q_LIQUID_SCALE,scale);
    store(cell,Q_LIQUID_DENSITY,density);store(cell,Q_PRESSURE_COMPLIANCE,storage);store(cell,Q_ELASTIC_ENERGY,elastic);
    if(mixtureHeaderWord(9)>=0){int outCell=mixtureHeaderWord(9)+8*cell;put(outCell,liquid/h3);put(outCell+2,mass+gasMass);put(outCell+4,capacity/h3);put(outCell+6,liquid/capacity);}
    if(gas>=0){
        put(mixtureHeaderWord(10)+2*cell,gasVolume);
        double gasDensity=gasVolume>0.0?gasMass/gasVolume:0.0LF,gasBulk=gasCompliance>0.0?gasVolume/gasCompliance:0.0LF;
        put(gas+2,gasMass>0.0?pressure:0.0LF);put(gas+4,gasDensity);put(gas+12,gasBulk);
        put(gas+14,gasDensity>0.0?sqrt(gasBulk/gasDensity):0.0LF);put(gas+16,gasBulk>0.0?1.0LF/gasBulk:0.0LF);
    }
}
