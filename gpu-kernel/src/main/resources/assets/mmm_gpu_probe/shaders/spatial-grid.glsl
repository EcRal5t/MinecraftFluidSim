layout(binding=SPATIAL_GRID_BINDING,std430) readonly buffer SpatialTopology { int spatialWords[]; };
int spatialWord(int index){return spatialWords[index];}
// SPATIAL_ADDRESS
int gridCells(){return gridCellsAt(0);}
int gridFaces(){return spatialWords[7];}
int gridFaceOffset(int axis){return spatialWords[4+axis];}
int gridFaceCount(int axis){return spatialWords[5+axis]-spatialWords[4+axis];}
ivec3 gridShape(){return gridShapeAt(0);}
ivec3 gridPosition(int cell){return gridPositionAt(0,cell);}
int gridCell(ivec3 position){return gridCellAt(0,position);}
int gridNeighbor(int cell,int side){return spatialWords[spatialWords[11]+6*cell+side];}
int gridColumns(){return spatialWords[18];}
int gridColumn(int cell){return spatialWords[spatialWords[16]+cell];}
ivec2 gridColumnPosition(int column){int at=spatialWords[17]+2*column;return ivec2(spatialWords[at],spatialWords[at+1]);}
int gridColumnAt(int x,int z){
    int low=0,high=gridColumns();
    while(low<high){int middle=(low+high)/2;ivec2 at=gridColumnPosition(middle);
        if(at.y<z||(at.y==z&&at.x<x))low=middle+1;else high=middle;}
    return low<gridColumns()&&all(equal(gridColumnPosition(low),ivec2(x,z)))?low:-1;
}
int gridFace(int cell,int side){return spatialWords[spatialWords[12]+6*cell+side];}
ivec3 gridFaceEndpoints(int face){int at=spatialWords[13]+3*face;return ivec3(spatialWords[at],spatialWords[at+1],spatialWords[at+2]);}
ivec3 gridFacePosition(int face){int at=spatialWords[14]+3*face;return ivec3(spatialWords[at],spatialWords[at+1],spatialWords[at+2]);}
int gridFaceAt(ivec3 position,int axis){
    int right=gridCell(position);if(right>=0)return gridFace(right,2*axis);
    position[axis]--;int left=gridCell(position);return left<0?-1:gridFace(left,2*axis+1);
}
