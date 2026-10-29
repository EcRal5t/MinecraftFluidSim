#ifndef MMM_LIQUID_DYADIC_GLSL
#define MMM_LIQUID_DYADIC_GLSL

// Include after enabling GL_EXT_shader_explicit_arithmetic_types_int64.
// Inputs use DeviceDyadicArray's canonical signed magnitude: little-endian uint32 limbs,
// odd low limb, nonzero high limb, at most 32768 significant bits. The caller supplies
// valid descriptors and incorporates each sum coefficient (+1 or -1) into its term sign.
// Read banks must remain immutable between measurement and writing; output must not alias them.
uint dyadicWord(int bank,int index);
void dyadicWrite(int index,uint word);

const int DYADIC_ZERO=0;
const int DYADIC_POSITIVE=1;
const int DYADIC_NEGATIVE=2;
const int DYADIC_SIGNIFICAND_OVERFLOW=3;
const int DYADIC_EXPONENT_OVERFLOW=4;
const int DYADIC_MAX_TERMS=7;
const int DYADIC_MAX_SIGNIFICAND_BITS=32768;

struct DyadicTerm { int bank; int offset; int count; int exponent; int sign; };

// words and exponent describe the canonical positive result. The remaining fields allow
// an exact second pass without storing an unbounded intermediate significand per invocation.
struct DyadicMeasure {
    int status;
    int words;
    int exponent;
    int minimumExponent;
    int firstWord;
    int lastWord;
    int trailingBits;
};

struct DyadicCursor { int position; int limit; int minimumExponent; int64_t carry; };
struct DyadicChunk { int first; int count; uint word; };

bool dyadicPresent(DyadicTerm item) { return item.sign!=0&&item.count>0; }

DyadicCursor dyadicBegin(DyadicTerm items[DYADIC_MAX_TERMS],int itemCount) {
    int minimum=2147483647;bool present=false;
    for(int i=0;i<itemCount;i++)if(dyadicPresent(items[i])) {
        minimum=min(minimum,items[i].exponent);present=true;
    }
    if(!present)return DyadicCursor(0,0,0,int64_t(0));
    int end=0;
    for(int i=0;i<itemCount;i++)if(dyadicPresent(items[i])) {
        int64_t shift=int64_t(items[i].exponent)-int64_t(minimum);
        int first=int(shift>>5),extra=(shift&int64_t(31))!=int64_t(0)?1:0;
        end=max(end,first+items[i].count+extra);
    }
    // Seven inputs produce at most one additional carry word, including opposite-sign cancellation.
    return DyadicCursor(0,end+1,minimum,int64_t(0));
}

uint dyadicAlignedWord(DyadicTerm item,int position,int minimumExponent) {
    if(!dyadicPresent(item))return 0u;
    int64_t shift=int64_t(item.exponent)-int64_t(minimumExponent);
    int relative=position-int(shift>>5),bits=int(shift&int64_t(31));
    uint value=0u;
    if(relative>=0&&relative<item.count)value=dyadicWord(item.bank,item.offset+relative)<<bits;
    if(bits!=0&&relative>0&&relative<=item.count)
        value|=dyadicWord(item.bank,item.offset+relative-1)>>(32-bits);
    return value;
}

// A zero or all-ones carry crosses an empty exponent interval in one chunk. This permits
// widely separated inputs to cancel without scanning millions of absent words or rejecting
// a small final answer merely because its intermediate alignment span is large.
DyadicChunk dyadicNext(inout DyadicCursor cursor,DyadicTerm items[DYADIC_MAX_TERMS],int itemCount) {
    int next=cursor.limit;bool covered=false;
    for(int i=0;i<itemCount;i++)if(dyadicPresent(items[i])) {
        int64_t shift=int64_t(items[i].exponent)-int64_t(cursor.minimumExponent);
        int first=int(shift>>5),end=first+items[i].count+((shift&int64_t(31))!=int64_t(0)?1:0);
        if(cursor.position<first)next=min(next,first);
        else if(cursor.position<end)covered=true;
    }
    if(!covered&&(cursor.carry==int64_t(0)||cursor.carry==int64_t(-1))) {
        DyadicChunk chunk=DyadicChunk(cursor.position,next-cursor.position,uint(cursor.carry));
        cursor.position=next;return chunk;
    }
    int64_t value=cursor.carry;
    for(int i=0;i<itemCount;i++)if(dyadicPresent(items[i])) {
        int64_t limb=int64_t(uint64_t(dyadicAlignedWord(items[i],cursor.position,cursor.minimumExponent)));
        value+=items[i].sign>0?limb:-limb;
    }
    // Seven unsigned 32-bit limbs and their signed carry fit comfortably in int64.
    DyadicChunk chunk=DyadicChunk(cursor.position,1,uint(value));
    cursor.position++;cursor.carry=value>>32;return chunk;
}

DyadicMeasure dyadicMeasure(DyadicTerm items[DYADIC_MAX_TERMS],int itemCount) {
    DyadicCursor cursor=dyadicBegin(items,itemCount);
    DyadicMeasure result=DyadicMeasure(DYADIC_ZERO,0,0,cursor.minimumExponent,-1,-1,0);
    uint last=0u;
    while(cursor.position<cursor.limit) {
        DyadicChunk chunk=dyadicNext(cursor,items,itemCount);
        if(chunk.word!=0u) {
            if(result.firstWord<0) {
                result.firstWord=chunk.first;result.trailingBits=findLSB(chunk.word);
            }
            result.lastWord=chunk.first+chunk.count-1;last=chunk.word;
        }
    }
    // A negative final carry denotes the sign extension of a negative exact result.
    if(cursor.carry<int64_t(0)){result.status=DYADIC_NEGATIVE;return result;}
    if(result.firstWord<0)return result;
    int64_t bits=int64_t(result.lastWord-result.firstWord)*int64_t(32)
            +int64_t(findMSB(last)-result.trailingBits+1);
    int64_t exponent=int64_t(result.minimumExponent)+int64_t(result.firstWord)*int64_t(32)+int64_t(result.trailingBits);
    result.words=int((bits+int64_t(31))>>5);
    if(exponent>int64_t(2147483647)||exponent<int64_t(-2147483647)-int64_t(1)) {
        result.status=DYADIC_EXPONENT_OVERFLOW;return result;
    }
    result.exponent=int(exponent);
    result.status=bits>int64_t(DYADIC_MAX_SIGNIFICAND_BITS)?DYADIC_SIGNIFICAND_OVERFLOW:DYADIC_POSITIVE;
    return result;
}

// Emits exactly result.words canonical limbs for a measured positive result. Zero needs no
// writes; negative and overflow statuses remain explicit and are never truncated into output.
void dyadicWriteSum(DyadicTerm items[DYADIC_MAX_TERMS],int itemCount,DyadicMeasure result,int outputOffset) {
    if(result.status!=DYADIC_POSITIVE)return;
    DyadicCursor cursor=dyadicBegin(items,itemCount);
    uint previous=0u;
    while(cursor.position<=result.lastWord&&cursor.position<cursor.limit) {
        DyadicChunk chunk=dyadicNext(cursor,items,itemCount);
        int first=max(result.firstWord,chunk.first),last=min(result.lastWord,chunk.first+chunk.count-1);
        // A valid result spans at most 1025 unnormalized words, even when skipped intervals are huge.
        for(int index=first;index<=last;index++) {
            int relative=index-result.firstWord;
            if(result.trailingBits==0)dyadicWrite(outputOffset+relative,chunk.word);
            else if(relative>0)dyadicWrite(outputOffset+relative-1,
                    (previous>>result.trailingBits)|(chunk.word<<(32-result.trailingBits)));
            previous=chunk.word;
        }
    }
    if(result.trailingBits!=0&&result.lastWord-result.firstWord<result.words)
        dyadicWrite(outputOffset+result.words-1,previous>>result.trailingBits);
}

// Comparison requires only the sign of the exact difference; a wide positive difference still
// orders correctly even when its magnitude would not fit the inventory storage envelope.
int dyadicCompare(DyadicTerm left,DyadicTerm right) {
    DyadicTerm items[DYADIC_MAX_TERMS];items[0]=left;items[1]=right;items[1].sign=-items[1].sign;
    DyadicMeasure result=dyadicMeasure(items,2);
    return result.status==DYADIC_ZERO?0:result.status==DYADIC_NEGATIVE?-1:1;
}

// Exact canonical four-word sum for positive inputs. Wider aligned spans use dyadicMeasure.
// Both the standalone sum pass and direct inventory publication share this register arithmetic.
bool dyadicPositiveSum(DyadicTerm items[DYADIC_MAX_TERMS],int itemCount,out int count,out int exponent,out uvec4 limbs) {
    count=0;exponent=0;limbs=uvec4(0u);
    int minimum=2147483647,present=0;int64_t maximum=int64_t(-9223372036854775807L);
    for(int i=0;i<itemCount;i++)if(dyadicPresent(items[i])){
        if(items[i].sign<0)return false;
        minimum=min(minimum,items[i].exponent);present++;
        int top=items[i].count-1;
        maximum=max(maximum,int64_t(items[i].exponent)+int64_t(32)*int64_t(top)+int64_t(findMSB(dyadicWord(items[i].bank,items[i].offset+top))));
    }
    if(present==0)return true;
    int headroom=present==1?0:findMSB(present-1)+1;
    if(maximum-int64_t(minimum)>=int64_t(128-headroom))return false;
    uvec4 sum=uvec4(0u);
    for(int term=0;term<itemCount;term++)if(dyadicPresent(items[term])){
        uint64_t carry=uint64_t(0);
        for(int word=0;word<4;word++){
            uint64_t value=uint64_t(sum[word])+uint64_t(dyadicAlignedWord(items[term],word,minimum))+carry;
            sum[word]=uint(value);carry=value>>32;
        }
    }
    int first=0;while(first<4&&sum[first]==0u)first++;
    if(first==4)return false;
    int last=3;while(sum[last]==0u)last--;
    int trailing=findLSB(sum[first]),bits=32*(last-first)+findMSB(sum[last])-trailing+1;count=(bits+31)/32;
    int64_t power=int64_t(minimum)+int64_t(32*first+trailing);
    if(power>int64_t(2147483647)||power<int64_t(-2147483647)-int64_t(1))return false;
    exponent=int(power);
    for(int word=0;word<count;word++){
        int index=first+word;uint value=sum[index]>>trailing;
        if(trailing!=0&&index<3)value|=sum[index+1]<<(32-trailing);
        limbs[word]=value;
    }
    return true;
}

#endif
