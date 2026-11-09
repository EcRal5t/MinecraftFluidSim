int gridCellsAt(int base){return spatialWord(base+3);}
ivec3 gridShapeAt(int base){return ivec3(spatialWord(base),spatialWord(base+1),spatialWord(base+2));}
ivec3 gridPositionAt(int base,int cell){
    int block=spatialWord(base+spatialWord(base+10)+cell),at=base+spatialWord(base+9)+8*block;
    int local=cell-spatialWord(at+3),nx=spatialWord(at+4),ny=spatialWord(at+5);
    return ivec3(spatialWord(at),spatialWord(at+1),spatialWord(at+2))*spatialWord(base+15)
        +ivec3(local%nx,local/nx%ny,local/(nx*ny));
}
int gridCellAt(int base,ivec3 position){
    if(any(lessThan(position,ivec3(0)))||any(greaterThanEqual(position,gridShapeAt(base))))return -1;
    ivec3 block=position/spatialWord(base+15);
    int table=base+spatialWord(base+19); uint mask=uint(spatialWord(table));
    uint bucket=(uint(block.x)*0x8da6b343u ^ uint(block.y)*0xd8163841u ^ uint(block.z)*0xcb1ab31fu)&mask;
    int at;
    for (;;) {
        int slot=spatialWord(table+1+int(bucket))-1;
        if(slot<0)return -1;
        at=base+spatialWord(base+9)+8*slot;
        if(all(equal(block,ivec3(spatialWord(at),spatialWord(at+1),spatialWord(at+2)))))break;
        bucket=(bucket+1u)&mask;
    }
    ivec3 q=position%spatialWord(base+15);
    return spatialWord(at+3)+(q.z*spatialWord(at+5)+q.y)*spatialWord(at+4)+q.x;
}
