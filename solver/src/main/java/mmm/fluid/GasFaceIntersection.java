package mmm.fluid;

import mmm.units.SignedDyadic;
import java.util.List;

/** Standard floating convex clipping on a local open microface rectangle. */
final class GasFaceIntersection {
    record Plane(double a, double b, double threshold) {
        Plane(SignedDyadic a, SignedDyadic b, SignedDyadic threshold) { this(a.toDouble(),b.toDouble(),threshold.toDouble()); }
    }
    // A rectangle cut by at most twelve half-planes has at most sixteen vertices.
    // Geometry capture is sequential within one thread; separate backend threads own separate workspaces.
    private static final ThreadLocal<Polygon> POLYGON = ThreadLocal.withInitial(Polygon::new);
    private GasFaceIntersection() { }

    static boolean coversRectangle(double tangentStart,double otherStart,double width,List<Plane> planes) {
        validateRectangle(tangentStart,otherStart,width,planes);
        if (!(width>0)) throw new IllegalArgumentException("Complete gas face width must be positive");
        for (var plane:planes) if (!covers(tangentStart,otherStart,width,plane.a(),plane.b(),plane.threshold())) return false;
        return true;
    }
    private static boolean covers(double x,double y,double width,double a,double b,double offset) {
        for(int i=0;i<2;i++)for(int j=0;j<2;j++)
            if(a*(x+i*width)+b*(y+j*width)<offset)return false;
        return true;
    }
    static boolean fullDryFace(FlipPressurePhaseGeometry phase,int cell,int axis,int coordinate) {
        if(phase==null||cell<0||axis<0||axis>2||coordinate<0||coordinate>1)
            throw new IllegalArgumentException("Invalid complete gas cell face");
        for(int a=0;a<2;a++)for(int b=0;b<2;b++) {
            double tangent=.5*a,other=.5*b;int micro=phase.microAtFace(cell,axis,coordinate,tangent,other);
            double fraction=phase.liquidFractionExact(cell,micro);
            if(!(fraction>=0&&fraction<=1))throw new IllegalArgumentException("Invalid gas face phase fraction");
            if(fraction>=1)return false;
            if(fraction>0&&!covers(tangent,other,.5,phase.planeNormal(cell,micro,(axis+1)%3),
                    phase.planeNormal(cell,micro,(axis+2)%3),phase.planeOffset(cell,micro)-phase.planeNormal(cell,micro,axis)*coordinate))return false;
        }
        return true;
    }
    /** Production path: at most one PLIC half-plane on each side of the shared face. */
    static double area(FlipPressurePhaseGeometry phase,int firstCell,int secondCell,int axis,
            double firstCoordinate,double secondCoordinate,double tangentStart,double otherStart,double width) {
        return area(phase,firstCell,phase,secondCell,axis,firstCoordinate,secondCoordinate,tangentStart,otherStart,width);
    }
    /** The same clipped face also connects old and future dry supports during one transport step. */
    static double area(FlipPressurePhaseGeometry firstPhase,int firstCell,FlipPressurePhaseGeometry secondPhase,int secondCell,int axis,
            double firstCoordinate,double secondCoordinate,double tangentStart,double otherStart,double width) {
        var phase=firstPhase;
        if(phase==null||firstCell<0||secondCell< -1||axis<0||axis>2||!Double.isFinite(firstCoordinate)
                ||!Double.isFinite(secondCoordinate)||!Double.isFinite(tangentStart)||!Double.isFinite(otherStart)
                ||!(width>=0&&Double.isFinite(width)))throw new IllegalArgumentException("Invalid gas face rectangle");
        int firstMicro=phase.microAtFace(firstCell,axis,firstCoordinate,tangentStart,otherStart);
        int secondMicro=secondCell<0?0:secondPhase.microAtFace(secondCell,axis,secondCoordinate,tangentStart,otherStart);
        return area(firstPhase,firstCell,firstMicro,secondPhase,secondCell,secondMicro,axis,
                firstCoordinate,secondCoordinate,tangentStart,otherStart,width);
    }
    static double area(FlipPressurePhaseGeometry phase,int firstCell,int firstMicro,int secondCell,int secondMicro,int axis,
            double firstCoordinate,double secondCoordinate,double tangentStart,double otherStart,double width) {
        return area(phase,firstCell,firstMicro,phase,secondCell,secondMicro,axis,firstCoordinate,secondCoordinate,tangentStart,otherStart,width);
    }
    private static double area(FlipPressurePhaseGeometry phase,int firstCell,int firstMicro,FlipPressurePhaseGeometry secondPhase,int secondCell,int secondMicro,int axis,
            double firstCoordinate,double secondCoordinate,double tangentStart,double otherStart,double width) {
        double firstFraction=phase.liquidFractionExact(firstCell,firstMicro);
        double secondFraction=secondCell<0?0:secondPhase.liquidFractionExact(secondCell,secondMicro);
        if(!(firstFraction>=0&&firstFraction<=1&&secondFraction>=0&&secondFraction<=1))
            throw new IllegalArgumentException("Invalid gas face phase fraction");
        if(width==0||firstFraction>=1||secondFraction>=1)return 0;
        requireArea(width);
        if(firstFraction==0&&secondFraction==0)return width*width;
        var polygon=POLYGON.get();polygon.reset(width);
        if(firstFraction>0)cut(polygon,phase,firstCell,firstMicro,axis,firstCoordinate,tangentStart,otherStart);
        if(secondFraction>0)cut(polygon,secondPhase,secondCell,secondMicro,axis,secondCoordinate,tangentStart,otherStart);
        return polygon.area(width);
    }
    private static void cut(Polygon polygon,FlipPressurePhaseGeometry phase,int cell,int micro,int axis,
            double coordinate,double x,double y) {
        double a=phase.planeNormal(cell,micro,(axis+1)%3),b=phase.planeNormal(cell,micro,(axis+2)%3);
        double offset=phase.planeOffset(cell,micro)-phase.planeNormal(cell,micro,axis)*coordinate;
        if(!Double.isFinite(a)||!Double.isFinite(b)||!Double.isFinite(offset))throw new IllegalArgumentException("Nonfinite gas face plane");
        polygon.cut(a,b,offset-a*x-b*y);
    }
    static double area(double tangentStart,double otherStart,double width,List<Plane> planes) {
        validateRectangle(tangentStart,otherStart,width,planes);
        if(width==0)return 0;
        if(planes.isEmpty())return width*width;
        var polygon=POLYGON.get();polygon.reset(width);
        for(var plane:planes)polygon.cut(plane.a(),plane.b(),plane.threshold()-plane.a()*tangentStart-plane.b()*otherStart);
        return polygon.area(width);
    }
    private static void validateRectangle(double x,double y,double width,List<Plane> planes) {
        if(!Double.isFinite(x)||!Double.isFinite(y)||!(width>=0&&Double.isFinite(width))||planes==null||planes.size()>12)
            throw new IllegalArgumentException("Invalid gas face rectangle");
        if(width>0)requireArea(width);
        for(var plane:planes)if(plane==null||!Double.isFinite(plane.a())||!Double.isFinite(plane.b())||!Double.isFinite(plane.threshold()))
            throw new IllegalArgumentException("Invalid gas face plane");
    }
    private static void requireArea(double width) {
        if(!(width*width>0&&Double.isFinite(width*width)))throw new GasTopologyBlockedException("Positive dry face area is not representable");
    }
    private static final class Polygon {
        private double[] x=new double[17],y=new double[17],nextX=new double[17],nextY=new double[17];
        private int count;
        void reset(double width){count=4;x[0]=x[3]=y[0]=y[1]=0;x[1]=x[2]=y[2]=y[3]=width;}
        void cut(double a,double b,double offset) {
            if(count==0)return;
            int next=0;double px=x[count-1],py=y[count-1],dp=a*px+b*py-offset;
            for(int i=0;i<count;i++) {
                double cx=x[i],cy=y[i],dc=a*cx+b*cy-offset;
                if((dc>=0)!=(dp>=0)) {
                    double t=dp/(dp-dc);nextX[next]=Math.fma(t,cx-px,px);nextY[next++]=Math.fma(t,cy-py,py);
                }
                if(dc>=0){nextX[next]=cx;nextY[next++]=cy;}
                px=cx;py=cy;dp=dc;
            }
            var swap=x;x=nextX;nextX=swap;swap=y;y=nextY;nextY=swap;count=next;
        }
        double area(double width) {
            double twice=0;
            for(int i=1;i+1<count;i++)twice+=(x[i]-x[0])*(y[i+1]-y[0])-(y[i]-y[0])*(x[i+1]-x[0]);
            double result=Math.clamp(Math.abs(twice)*.5,0,width*width);
            return result<=16*Math.ulp(width*width)?0:result;
        }
    }
}
