package mmm.fluid;

/** Positive donor transport on MAC dual cells, using actual integer mass transfers. */
final class MacMomentumAdvection {
    private final int nx,ny,nz,count;
    private final int[] left,right,fluxAxis,firstFlux,secondFlux,firstCell,secondCell;
    private final long[] remaining,flow;
    private final double[] oldVelocity,velocity,momentum,heat;
    MacMomentumAdvection(int nx,int ny,int nz,int component){
        this.nx=nx;this.ny=ny;this.nz=nz;
        int sx=nx+(component==0?1:0),sy=ny+(component==1?1:0),sz=nz+(component==2?1:0);
        count=sx*sy*sz;int edges=(sx-1)*sy*sz+sx*(sy-1)*sz+sx*sy*(sz-1);
        left=new int[edges];right=new int[edges];fluxAxis=new int[edges];firstFlux=new int[edges];secondFlux=new int[edges];flow=new long[edges];
        firstCell=new int[count];secondCell=new int[count];remaining=new long[count];
        oldVelocity=new double[count];velocity=new double[count];momentum=new double[count];heat=new double[count];
        int edge=0;
        for(int z=0;z<sz;z++)for(int y=0;y<sy;y++)for(int x=0;x<sx;x++){
            int i=(z*sy+y)*sx+x;firstCell[i]=cell(x,y,z);secondCell[i]=cell(x-(component==0?1:0),y-(component==1?1:0),z-(component==2?1:0));
            for(int axis=0;axis<3;axis++){
                int dx=axis==0?1:0,dy=axis==1?1:0,dz=axis==2?1:0;if(x+dx>=sx||y+dy>=sy||z+dz>=sz)continue;
                left[edge]=i;right[edge]=((z+dz)*sy+y+dy)*sx+x+dx;fluxAxis[edge]=axis;
                firstFlux[edge]=primal(axis,x+dx,y+dy,z+dz);
                secondFlux[edge]=axis==component?primal(axis,x,y,z):primal(axis,x+dx-(component==0?1:0),y+dy-(component==1?1:0),z+dz-(component==2?1:0));edge++;
            }
        }
    }
    /** Integer columns are doubled dual masses; a dual interface carries half two primal fluxes. */
    double[] transport(long[] before,long[] after,long[][] primalFlux,double[] inputVelocity){
        System.arraycopy(inputVelocity,0,oldVelocity,0,count);
        for(int i=0;i<count;i++)remaining[i]=value(before,firstCell[i])+value(before,secondCell[i]);
        for(int e=0;e<flow.length;e++){
            long[] q=primalFlux[fluxAxis[e]];flow[e]=value(q,firstFlux[e])+value(q,secondFlux[e]);
            remaining[flow[e]>=0?left[e]:right[e]]-=Math.abs(flow[e]);
        }
        for(int i=0;i<count;i++)momentum[i]=remaining[i]*oldVelocity[i];
        for(int e=0;e<flow.length;e++){
            int from=flow[e]>=0?left[e]:right[e],to=flow[e]>=0?right[e]:left[e];momentum[to]+=Math.abs(flow[e])*oldVelocity[from];
        }
        double kgPerDoubledUnit=.5/mmm.units.Mass.UNITS_PER_KILOGRAM;
        for(int i=0;i<count;i++){
            long mass=value(after,firstCell[i])+value(after,secondCell[i]);velocity[i]=mass>0?momentum[i]/mass:0;
            double difference=oldVelocity[i]-velocity[i];heat[i]=.5*kgPerDoubledUnit*remaining[i]*difference*difference;
            momentum[i]*=kgPerDoubledUnit;
        }
        for(int e=0;e<flow.length;e++){
            int from=flow[e]>=0?left[e]:right[e],to=flow[e]>=0?right[e]:left[e];double difference=oldVelocity[from]-velocity[to];
            heat[to]+=.5*kgPerDoubledUnit*Math.abs(flow[e])*difference*difference;
        }
        return momentum;
    }
    void addHeatToCells(long[] after,double[] cells){
        for(int i=0;i<count;i++)if(heat[i]>0){int a=firstCell[i],b=secondCell[i];long ma=value(after,a),mb=value(after,b);double first=ma+mb>0?heat[i]*(ma/(double)(ma+mb)):0;if(a>=0)cells[a]+=first;if(b>=0)cells[b]+=heat[i]-first;}
    }
    private int cell(int x,int y,int z){return x<0||y<0||z<0||x>=nx||y>=ny||z>=nz?-1:(z*ny+y)*nx+x;}
    private int primal(int axis,int x,int y,int z){int sx=nx+(axis==0?1:0),sy=ny+(axis==1?1:0),sz=nz+(axis==2?1:0);return x<0||y<0||z<0||x>=sx||y>=sy||z>=sz?-1:(z*sy+y)*sx+x;}
    private static long value(long[] values,int index){return index<0?0:values[index];}
}
