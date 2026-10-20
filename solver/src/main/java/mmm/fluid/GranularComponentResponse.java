package mmm.fluid;

import mmm.granular.ParticulateMaterial;
import static mmm.fluid.GranularContactCompression.*;

/** Fixed-cost accumulated normal and Coulomb impulses for one representative contact component. */
final class GranularComponentResponse {
    private final double h;
    private final Runnable spend;
    GranularComponentResponse(double h,Runnable spend){this.h=h;this.spend=spend;}

    Result respond(double[] mass,double[][] input,ParticulateMaterial[] materials,Row[] rows){
        // Compression provides a warm start and the energy available to restitution.
        var compressed=solve(mass,input,rows,spend);
        double[][] values=new double[mass.length][3],tangent=new double[rows.length][3];
        for(int i=0;i<values.length;i++)values[i]=compressed.values()[i].clone();
        double[] normal=compressed.impulses().clone(),target=new double[rows.length];
        double[] inverse=new double[rows.length],norm2=new double[rows.length],muStatic=new double[rows.length],muDynamic=new double[rows.length];
        boolean loaded=false;
        for(int k=0;k<rows.length;k++)loaded|=rows[k].second()<0&&normal[k]>0;
        for(int k=0;k<rows.length;k++){
            var row=rows[k];int i=row.first(),j=row.second();double closing=0;
            for(int a=0;a<3;a++){double n=row.normal()[a];norm2[k]=Math.fma(n,n,norm2[k]);closing=Math.fma(n,j<0?input[i][a]:input[j][a]-input[i][a],closing);}
            inverse[k]=1/mass[i]+(j<0?0:1/mass[j]);
            double e=j<0?materials[i].restitution():Math.min(materials[i].restitution(),materials[j].restitution());
            target[k]=closing<0&&(j>=0&&!loaded||-e*closing>h*.125)?-e*closing:0;
            muStatic[k]=j<0?materials[i].staticFriction():0;
            muDynamic[k]=j<0?materials[i].dynamicFriction():0;
        }
        for(int pass=0;pass<2*SWEEPS;pass++){
            boolean changed=false;
            for(int visit=0;visit<rows.length;visit++){
                spend.run();int k=(pass&1)==0?visit:rows.length-1-visit;var row=rows[k];int i=row.first(),j=row.second();double gap=0;
                for(int a=0;a<3;a++)gap=Math.fma(row.normal()[a],j<0?values[i][a]:values[j][a]-values[i][a],gap);
                double next=Math.max(0,normal[k]+(target[k]-gap)/(inverse[k]*norm2[k])),delta=next-normal[k];normal[k]=next;changed|=delta!=0;
                for(int a=0;a<3;a++)apply(values,mass,i,j,a,delta*row.normal()[a]);
                // Translational representative pairs carry normal impulses; fixed surfaces carry Coulomb traction.
                if(j>=0)continue;
                gap=0;for(int a=0;a<3;a++)gap=Math.fma(row.normal()[a],j<0?values[i][a]:values[j][a]-values[i][a],gap);
                double tx=tangent[k][0]-((j<0?values[i][0]:values[j][0]-values[i][0])-gap*row.normal()[0]/norm2[k])/inverse[k];
                double ty=tangent[k][1]-((j<0?values[i][1]:values[j][1]-values[i][1])-gap*row.normal()[1]/norm2[k])/inverse[k];
                double tz=tangent[k][2]-((j<0?values[i][2]:values[j][2]-values[i][2])-gap*row.normal()[2]/norm2[k])/inverse[k];
                double length=Math.hypot(Math.hypot(tx,ty),tz),load=normal[k]*Math.sqrt(norm2[k]);
                double scale=length==0||length<=muStatic[k]*load?1:Math.min(1,muDynamic[k]*load/length);
                for(int a=0;a<3;a++){
                    double value=(a==0?tx:a==1?ty:tz)*scale,change=value-tangent[k][a];tangent[k][a]=value;changed|=change!=0;
                    apply(values,mass,i,j,a,change);
                }
            }
            if(!changed)break;
        }
        // Simultaneous rebound spends at most the kinetic energy released by compression.
        double available=0,linear=0,quadratic=0;
        for(int i=0;i<mass.length;i++)for(int a=0;a<3;a++){
            double c=compressed.values()[i][a],d=values[i][a]-c;
            available+=.5*mass[i]*(input[i][a]-c)*(input[i][a]+c);
            linear+=mass[i]*c*d;quadratic+=.5*mass[i]*d*d;
        }
        if(linear+quadratic>Math.max(0,available)&&quadratic>0){
            double root=Math.sqrt(linear*linear+4*quadratic*Math.max(0,available));
            double scale=Math.clamp(linear>=0?(available<=0?0:2*available/(linear+root)):(root-linear)/(2*quadratic),0,1);
            for(int i=0;i<mass.length;i++)for(int a=0;a<3;a++)values[i][a]=Math.fma(scale,values[i][a]-compressed.values()[i][a],compressed.values()[i][a]);
            for(int k=0;k<normal.length;k++)normal[k]=Math.fma(scale,normal[k]-compressed.impulses()[k],compressed.impulses()[k]);
        }
        double[][] error=new double[mass.length][3];double[] gapError=new double[rows.length];
        for(int i=0;i<mass.length;i++)for(int a=0;a<3;a++)error[i][a]=64*Math.ulp(Math.max(Math.abs(values[i][a]),Math.abs(input[i][a])));
        for(int k=0;k<rows.length;k++)gapError[k]=GranularContactCompression.gapError(rows[k],values,error);
        return new Result(values,normal,gapError,error);
    }
    private static void apply(double[][] values,double[] mass,int first,int second,int axis,double impulse){
        values[first][axis]+=(second<0?impulse:-impulse)/mass[first];
        if(second>=0)values[second][axis]+=impulse/mass[second];
    }
}
