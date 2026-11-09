// SPATIAL_GRID
int localGasCell(ivec3 q,ivec3 size){return gridCell(q);}
int localGasFace(int cell,int side,ivec3 size){return gridFace(cell,side);}
int localGasFaceAt(ivec3 q,int axis,ivec3 size){return gridFaceAt(q,axis);}
ivec3 localGasFaceCoordinates(int face,int axis,ivec3 size){return gridFacePosition(face);}
ivec3 localGasFaceEndpoints(int face,ivec3 size){return gridFaceEndpoints(face);}
int localGasNeighbor(int cell,int side,ivec3 size){return gridNeighbor(cell,side);}
int localGasRouteIndex(int cell,int lane,int side,int cells,int stride){return (side*stride+lane)*cells+cell;}
