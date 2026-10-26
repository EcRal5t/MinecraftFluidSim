#ifndef MMM_PHASE_ROWS
#define MMM_PHASE_ROWS
const int LP_CELL=1,LP_MATERIAL=2,LP_SUPPORT=3,LP_MASS=4,LP_U=5,LP_DELTA=6,LP_SPECIES=7,LP_LIVE=8,
 LP_SPEC_COUNT=9,LP_SPEC_OFFSET=10,LP_SPEC_HANDLE=11,LP_SPEC_AMOUNT=12,LP_RESULT=13,LP_VOL_COUNT=14,LP_VOL_OFFSET=15,
 LP_VOL_LIMBS=16,LP_DELTA_LIMBS=17,LP_CONTROL=19,LP_OLD_U=20,LP_NEW_U=21,LP_MIXTURE=22,LP_BASE_VOLUME=23,LP_MASS_RESIDUAL=24,LP_NUMERIC_VOLUME=25;
int lpH(int header,int i){return int(words[header+i]);}int lpF(int header,int field){return lpH(header,8+field);}int lpS(int header,int field){return lpH(header,40+field);}
int64_t lpInt(int a){return int64_t(uint64_t(words[a])|(uint64_t(words[a+1])<<32));}
void lpIntAt(int a,int64_t q){words[a]=uint(q);words[a+1]=uint(uint64_t(q)>>32);}
double lpDouble(int a){return packDouble2x32(uvec2(words[a],words[a+1]));}void lpDoubleAt(int a,double q){uvec2 b=unpackDouble2x32(q);words[a]=b.x;words[a+1]=b.y;}
int64_t lpMass(int header,int row){return lpInt(lpF(header,LP_MASS)+2*row);}double lpEnergy(int header,int row){return lpDouble(lpF(header,LP_U)+2*row);}
int64_t lpSpecies(int header,int row,int species){return lpInt(lpF(header,LP_SPECIES)+2*(row*lpH(header,3)+species));}
void lpSpeciesAt(int header,int row,int species,int64_t amount){lpIntAt(lpF(header,LP_SPECIES)+2*(row*lpH(header,3)+species),amount);}
double lpVolume(int header,int row){return lpDouble(lpF(header,LP_BASE_VOLUME)+2*row)+double(lpInt(lpF(header,LP_DELTA)+2*row))*ldexp(double(lpInt(header+60)),lpH(header,62));}
int lpFind(int header,int cell,int material,int support){
 if(cell<0||cell>=lpH(header,0)||material<0||material>=lpH(header,69))return -1;
 int row=cell*lpH(header,69)+material;return words[lpF(header,LP_LIVE)+row]!=0u?row:-1;
}
// One producer owns all edits to a cell; material slots combine their connected support mask.
int lpCreate(int header,int cell,int material,int support){
 if(cell<0||cell>=lpH(header,0)||material<0||material>=lpH(header,69)){atomicOr(errors,33554432u);return -1;}
 int row=cell*lpH(header,69)+material;
 words[lpF(header,LP_CELL)+row]=uint(cell);words[lpF(header,LP_MATERIAL)+row]=uint(material);
 words[lpF(header,LP_SUPPORT)+row]|=uint(support);words[lpF(header,LP_LIVE)+row]=1u;return row;
}
#endif
