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
    ivec3 block=position/spatialWord(base+15);int low=0,high=spatialWord(base+8);
    while(low<high){
        int middle=(low+high)/2,at=base+spatialWord(base+9)+8*middle;
        ivec3 key=ivec3(spatialWord(at),spatialWord(at+1),spatialWord(at+2));
        int order=key.z!=block.z?key.z-block.z:key.y!=block.y?key.y-block.y:key.x-block.x;
        if(order<0)low=middle+1;else high=middle;
    }
    if(low>=spatialWord(base+8))return -1;
    int at=base+spatialWord(base+9)+8*low;
    if(any(notEqual(block,ivec3(spatialWord(at),spatialWord(at+1),spatialWord(at+2)))))return -1;
    ivec3 q=position%spatialWord(base+15);
    return spatialWord(at+3)+(q.z*spatialWord(at+5)+q.y)*spatialWord(at+4)+q.x;
}
