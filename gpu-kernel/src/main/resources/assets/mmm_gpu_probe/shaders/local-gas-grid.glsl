// MAC ordering matches VulkanLiquidStateLayout: x faces, then y, then z.
int localGasCell(ivec3 q,ivec3 size){return (q.z*size.y+q.y)*size.x+q.x;}
int localGasFace(int cell,int side,ivec3 size){
    ivec3 q=ivec3(cell%size.x,cell/size.x%size.y,cell/(size.x*size.y));
    int axis=side/2; q[axis]+=side&1;
    ivec3 shape=size;shape[axis]++;
    int offset=axis==0?0:axis==1?(size.x+1)*size.y*size.z:(size.x+1)*size.y*size.z+size.x*(size.y+1)*size.z;
    return offset+(q.z*shape.y+q.y)*shape.x+q.x;
}
int localGasFaceAt(ivec3 q,int axis,ivec3 size){
    ivec3 shape=size;shape[axis]++;
    if(any(lessThan(q,ivec3(0)))||any(greaterThanEqual(q,shape)))return -1;
    int offset=axis==0?0:axis==1?(size.x+1)*size.y*size.z:(size.x+1)*size.y*size.z+size.x*(size.y+1)*size.z;
    return offset+(q.z*shape.y+q.y)*shape.x+q.x;
}
ivec3 localGasFaceCoordinates(int face,int axis,ivec3 size){
    int offset=axis==0?0:axis==1?(size.x+1)*size.y*size.z:(size.x+1)*size.y*size.z+size.x*(size.y+1)*size.z;
    int local=face-offset;ivec3 shape=size;shape[axis]++;
    return ivec3(local%shape.x,local/shape.x%shape.y,local/(shape.x*shape.y));
}
ivec3 localGasFaceEndpoints(int face,ivec3 size){
    int nx=(size.x+1)*size.y*size.z,ny=size.x*(size.y+1)*size.z;
    int axis=face<nx?0:face<nx+ny?1:2,local=face-(axis==0?0:axis==1?nx:nx+ny);
    ivec3 shape=size;shape[axis]++;
    ivec3 q=ivec3(local%shape.x,local/shape.x%shape.y,local/(shape.x*shape.y)),left=q;
    left[axis]--;
    int a=left[axis]>=0?localGasCell(left,size):-1,b=q[axis]<size[axis]?localGasCell(q,size):-1;
    return ivec3(a,b,axis);
}
int localGasNeighbor(int cell,int side,ivec3 size){
    ivec3 ends=localGasFaceEndpoints(localGasFace(cell,side,size),size);
    return (side&1)==0?ends.x:ends.y;
}

int localGasRouteIndex(int cell,int lane,int side,int cells,int stride){return (side*stride+lane)*cells+cell;}
