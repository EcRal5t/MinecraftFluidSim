package mmm.gpu;

/** Analytic pressure input and its discrete right-hand side for the resident solver. */
final class ManufacturedPressureFixture {
    record Fixture(int size, int[] starts, int[] columns, double[] values, int[] rows,
                   double[] rhs, double[] exact, double tolerance, int maximumIterations) { }
    static Fixture manufacture(int dimension,double tolerance,int maximumIterations) {
        if(dimension<2)throw new IllegalArgumentException("Grid side must be at least two");
        int size=Math.multiplyExact(Math.multiplyExact(dimension,dimension),dimension);
        int entries=Math.subtractExact(Math.multiplyExact(7,size),Math.multiplyExact(6,dimension*dimension));
        int[] starts=new int[size+1],columns=new int[entries],rows=new int[size];
        double[] values=new double[entries],exact=new double[size],rhs=new double[size];int cursor=0;
        for(int z=0;z<dimension;z++)for(int y=0;y<dimension;y++)for(int x=0;x<dimension;x++) {
            int row=x+dimension*(y+dimension*z);rows[row]=row;starts[row]=cursor;
            if(z>0){columns[cursor]=row-dimension*dimension;values[cursor++]=-1;}
            if(y>0){columns[cursor]=row-dimension;values[cursor++]=-1;}
            if(x>0){columns[cursor]=row-1;values[cursor++]=-1;}
            columns[cursor]=row;values[cursor++]=6;
            if(x+1<dimension){columns[cursor]=row+1;values[cursor++]=-1;}
            if(y+1<dimension){columns[cursor]=row+dimension;values[cursor++]=-1;}
            if(z+1<dimension){columns[cursor]=row+dimension*dimension;values[cursor++]=-1;}
            double px=Math.PI*(x+1)/(dimension+1),py=Math.PI*(y+1)/(dimension+1),pz=Math.PI*(z+1)/(dimension+1);
            long hash=(row+1L)*0x9e3779b97f4a7c15L;hash=(hash^(hash>>>30))*0xbf58476d1ce4e5b9L;
            double noise=(hash>>>11)*0x1.0p-53-.5;
            exact[row]=1000*(Math.sin(px)*Math.sin(py)*Math.sin(pz)+.3*Math.sin(3*px)*Math.sin(2*py)*Math.sin(4*pz)+.01*noise);
        }
        starts[size]=cursor;
        var fixture=new Fixture(size,starts,columns,values,rows,rhs,exact,tolerance,maximumIterations);
        multiply(fixture,exact,rhs);return fixture;
    }
    private static void multiply(Fixture f,double[] x,double[] y) {
        for(int row=0;row<f.size;row++){double value=0;for(int edge=f.starts[row];edge<f.starts[row+1];edge++)value+=f.values[edge]*x[f.columns[edge]];y[row]=value;}
    }
}
