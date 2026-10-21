package mmm.fluid;

import mmm.units.SignedDyadic;
import mmm.inventory.LiquidCellInventory;


import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.Collections;

/** Actual coarse gas capacity with shared PLIC dry openings and finite fixed apertures. */
final class GasComplementGeometry {
	/** On-demand microbox view; normalizedVolume uses whole-cell units, volume uses SI. */
    record Piece(int id, int cell, int micro, double volume, double normalizedVolume,
                 GasGeometryOverlap.HalfSpace dryPlane, PlicPlaneOffset.Result offsetBracket) { }
    /** One fixed support's dry PLIC geometry, sharing its immutable local plane and occupation. */
    record Component(int cell,int supportMask,int dryMicroMask,double normalizedVolume,double volume,boolean compact,
                     PlicComponentGeometry.Plane plane,FlipPressurePhaseGeometry.OccupancyCell occupancy) {
        int slot(){return 8*cell+Integer.numberOfTrailingZeros(supportMask);}
        int minimumPiece(){return 8*cell+Integer.numberOfTrailingZeros(dryMicroMask);}
    }
	/** One actual dry opening per adjacent coarse support pair, using canonical gas-piece endpoints. */
	record Port(int first, int second, int axis, double area, boolean fullCutConnection) { }
	record ExteriorPort(int piece, int axis, int sign, double area, boolean fullCutConnection) { }

	private static final double MICRO_VOLUME = .125;
	private final int sizeX, sizeY, sizeZ;
	private final double cellVolumeCubicMeters;
	private final byte[] collisionMasks;
	private final SignedDyadic[] poreVolumes;
	private final double[] componentDry;
	private final GasComponentCapacity.Prepared actualCapacity;
	private GasChamberTopology sealedTopology,openTopology;
	private final Component[] bySlot;
	private final Port[] portArray;
	private final ExteriorPort[] exteriorArray;
	private final List<Component> components;
	private final List<Port> ports;
	private final List<ExteriorPort> exteriorPorts;

	/** All arrays are owned snapshots; the public lists cannot modify their backing arrays. */
	private GasComplementGeometry(int sizeX, int sizeY, int sizeZ, double h3, byte[] masks,
	                              SignedDyadic[] poreVolumes, double[] componentDry, Component[] bySlot, List<Component> components, Port[] ports,
	                              ExteriorPort[] exterior,GasComponentCapacity.Prepared actualCapacity) {
		this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ;
		cellVolumeCubicMeters = h3;
		collisionMasks = masks; this.poreVolumes = poreVolumes; this.componentDry=componentDry; this.bySlot=bySlot;
		this.actualCapacity=actualCapacity;
		portArray = ports; exteriorArray = exterior;
		this.components=Collections.unmodifiableList(components);
		this.ports = Collections.unmodifiableList(Arrays.asList(ports));
		exteriorPorts = Collections.unmodifiableList(Arrays.asList(exterior));
	}

	static GasComplementGeometry capture(int sizeX,int sizeY,int sizeZ,double cellSize,
	                                    boolean[] solid,FlipCutCellGeometry cut,FlipPressurePhaseGeometry phase) {
		return capture(sizeX,sizeY,sizeZ,cellSize,solid,cut,phase,null,null,null,null);
	}
	/** One preparation owns geometry change detection, exact capacity and incident dry ports. */
	static GasComplementGeometry capture(int sizeX,int sizeY,int sizeZ,double cellSize,
	                                    boolean[] solid,FlipCutCellGeometry cut,FlipPressurePhaseGeometry phase,
	                                    GasComplementGeometry source,FlipPressurePhaseGeometry sourcePhase,
	                                    LiquidCellInventory.Snapshot cells,GasComponentCapacity.Prepared preparedCapacity) {
		validateShape(sizeX, sizeY, sizeZ, solid, cut, phase);
		double h2 = cellSize * cellSize, h3 = h2 * cellSize;
		if (!(cellSize > 0 && h2 > 0 && h3 > 0 && Double.isFinite(h2) && Double.isFinite(h3)))
			throw new IllegalArgumentException("Gas complement SI area and volume must be representable");
		int count = solid.length;
		if (source != null && (source.sizeX != sizeX || source.sizeY != sizeY || source.sizeZ != sizeZ
				|| source.cellVolumeCubicMeters != h3 || sourcePhase == null))
			throw new IllegalArgumentException("Gas geometry continuation differs in shape or scale");
		var actualCapacity=preparedCapacity!=null?preparedCapacity:cells==null?null:GasComponentCapacity.prepare(cut,phase,cells,source==null?null:source.actualCapacity);
		double[] componentDry=actualCapacity==null?componentDry(cut,phase,count):actualCapacity.dry;
		boolean[] changed=source==null?null:new boolean[count];
		if(source!=null){
			boolean anyChanged=false;
			for(int cell=0;cell<count;cell++){
				changed[cell]=phase!=sourcePhase&&!phase.sameCellGeometry(cell,sourcePhase);
				for(int support:CellRemapMicroTopology.components(cut.mask(cell))){
					int slot=8*cell+Integer.numberOfTrailingZeros(support);
					if(componentDry[slot]!=source.componentDry[slot])changed[cell]=true;
				}
				anyChanged|=changed[cell];
			}
			if(!anyChanged&&actualCapacity==source.actualCapacity)return source;
		}
		Component[] bySlot=source==null?new Component[Math.multiplyExact(count,8)]:source.bySlot.clone();
		SignedDyadic[] pores = actualCapacity!=null?actualCapacity.pores:source == null ? new SignedDyadic[count] : source.poreVolumes.clone();
		var components=new ArrayList<Component>(source==null?count:source.components.size());
		var ports = new ArrayList<Port>(source==null?count:source.portArray.length);
		var exterior = new ArrayList<ExteriorPort>(source==null?0:source.exteriorArray.length);
		for (int cell = 0; cell < count; cell++) {
			if (source == null || changed[cell]) {
				Arrays.fill(bySlot,8*cell,8*cell+8,null);if(actualCapacity==null)pores[cell]=phase.poreVolume(cell);
				captureCell(cell,solid,cut,phase,h3,pores[cell],componentDry,cells!=null,bySlot);
			}
			for(int support:CellRemapMicroTopology.components(cut.mask(cell))){
				var component=bySlot[8*cell+Integer.numberOfTrailingZeros(support)];if(component!=null)components.add(component);
			}
		}
		int[] canonical=new int[8*count];Arrays.fill(canonical,-1);
        for(var component:components)for(int bits=component.supportMask();bits!=0;bits&=bits-1)
            canonical[8*component.cell()+Integer.numberOfTrailingZeros(bits)]=component.minimumPiece();
		int[] first=new int[4],second=new int[4];double[] areas=new double[4];
		int oldPort=0,oldExterior=0;
		for(int cell=0;cell<count;cell++)for(int axis=0;axis<3;axis++){
			int coordinate=coordinate(cell,axis,sizeX,sizeY),size=axis==0?sizeX:axis==1?sizeY:sizeZ;
			if(coordinate+1<size){
				int peer=cell+(axis==0?1:axis==1?sizeX:sizeX*sizeY);long key=3L*cell+axis;
				if(source!=null)while(oldPort<source.portArray.length&&portKey(source.portArray[oldPort])<key)oldPort++;
				if(source!=null&&!changed[cell]&&!changed[peer]){
					while(oldPort<source.portArray.length&&portKey(source.portArray[oldPort])==key)ports.add(source.portArray[oldPort++]);
				}else captureFace(cell,peer,axis,canonical,cut,phase,h2,first,second,areas,ports);
			}
			int bit=coordinate==0?0:coordinate+1==size?1:-1;if(bit<0)continue;long key=6L*cell+2*axis+bit;
			if(source!=null)while(oldExterior<source.exteriorArray.length&&exteriorKey(source.exteriorArray[oldExterior])<key)oldExterior++;
			if(source!=null&&!changed[cell]){
				while(oldExterior<source.exteriorArray.length&&exteriorKey(source.exteriorArray[oldExterior])==key)exterior.add(source.exteriorArray[oldExterior++]);
			}else captureExteriorFace(cell,axis,bit,canonical,cut,phase,h2,first,areas,exterior);
		}
		var result=new GasComplementGeometry(sizeX,sizeY,sizeZ,h3,actualCapacity==null?cut.copyMicroMasks():actualCapacity.masks,pores,componentDry,bySlot,
				components,ports.toArray(Port[]::new),exterior.toArray(ExteriorPort[]::new),actualCapacity);
		if(source!=null)GasChamberTopology.continueCached(result,source);
		return result;
	}
    /** Resolution decisions read the coarse pore remainder, independently of a face's PLIC film. */
    private static double[] componentDry(FlipCutCellGeometry cut,FlipPressurePhaseGeometry phase,int count){
        double[] dry=new double[8*count];
        for(int cell=0;cell<count;cell++){
            double open=cut.openCellVolume(cell),fallback=open>0?phase.poreVolume(cell).toDouble()/open:0;
            int[] supports=CellRemapMicroTopology.components(cut.mask(cell));
            for(int index=0;index<supports.length;index++){
                int support=supports[index];
                int micro=Integer.numberOfTrailingZeros(support),slot=8*cell+micro;
                double scale=phase.componentPoreScale(cell,micro,fallback),capacity=scale*Integer.bitCount(support)*.125,liquid=0;
                var plane=phase.offsetBracket(cell,micro);
                liquid=plane==null?capacity*phase.liquidFractionExact(cell,micro):scale*plane.targetVolume();
                dry[slot]=Math.max(0,capacity-liquid);
            }
        }
        return dry;
    }
	private static long portKey(Port port) { return 3L*(port.first()/8)+port.axis(); }
	private static long exteriorKey(ExteriorPort port) { return 6L*(port.piece()/8)+2*port.axis()+(port.sign()>0?1:0); }
	private static void validateShape(int sizeX, int sizeY, int sizeZ, boolean[] solid, FlipCutCellGeometry cut, FlipPressurePhaseGeometry phase) {
		if (sizeX < 2 || sizeY < 2 || sizeZ < 2 || solid == null || cut == null || phase == null
				|| solid.length != Math.multiplyExact(sizeX, Math.multiplyExact(sizeY, sizeZ))
				|| !cut.matchesShape(sizeX, sizeY, sizeZ) || !phase.matchesShape(sizeX, sizeY, sizeZ))
			throw new IllegalArgumentException("Gas complement geometry shapes differ");
	}
    private static void captureCell(int cell,boolean[] solid,FlipCutCellGeometry cut,FlipPressurePhaseGeometry phase,
                                    double physicalScale,SignedDyadic poreVolume,double[] componentDry,
                                    boolean actualInventory,Component[] bySlot) {
        if(phase.collisionMask(cell)!=cut.mask(cell))throw new IllegalArgumentException("Gas complement PLIC collision geometry differs at cell "+cell);
        if(solid[cell])return;
        int[] supports=CellRemapMicroTopology.components(cut.mask(cell));
        var capacities=actualInventory?null:CellRemapMicroTopology.capacities(cut.mask(cell),poreVolume);
        for(int i=0;i<supports.length;i++) {
            int support=supports[i],slot=8*cell+Integer.numberOfTrailingZeros(support);
            double available=componentDry[slot];
            if(actualInventory?!(available>0):capacities[i].signum()<=0)continue;
            int dryMask=0;double normalized=0;
            if(phase.componentLiquidFraction(cell,Integer.numberOfTrailingZeros(support))==0) {
                dryMask=support;normalized=Integer.bitCount(support)*MICRO_VOLUME;
            } else for(int bits=support;bits!=0;bits&=bits-1) {
                int micro=Integer.numberOfTrailingZeros(bits);double value=dryMicroVolume(phase,cell,micro);
                if(value>0){dryMask|=1<<micro;normalized+=value;}
            }
            boolean compact=false;
            if(dryMask==0&&actualInventory) {
                dryMask=Integer.lowestOneBit(support);normalized=available;compact=true;
            }
            if(dryMask==0)continue;
            double volume=normalized*physicalScale;
            if(!(volume>0&&Double.isFinite(volume)))throw blocked("Positive gas component volume is not representable",cell);
            bySlot[slot]=new Component(cell,support,dryMask,normalized,volume,compact,
                    phase.componentPlane(cell,Integer.numberOfTrailingZeros(support)),phase.occupancyCell(cell));
        }
    }
    private static double dryMicroVolume(FlipPressurePhaseGeometry phase,int cell,int micro) {
        double fraction=phase.liquidFractionExact(cell,micro);
        double nx=phase.planeNormal(cell,micro,0),ny=phase.planeNormal(cell,micro,1),nz=phase.planeNormal(cell,micro,2),offset=phase.planeOffset(cell,micro);
        if(!(fraction>=0&&fraction<=1)||!Double.isFinite(nx)||!Double.isFinite(ny)||!Double.isFinite(nz)||!Double.isFinite(offset))
            throw new IllegalArgumentException("Gas complement PLIC is invalid at cell "+cell);
        if(fraction==1)return 0;
        if(fraction>0&&nx==0&&ny==0&&nz==0)throw blocked("Partial PLIC has no normal",cell);
        if(phase.hasMicroOccupation())return MICRO_VOLUME*(1-fraction);
        return fraction==0?MICRO_VOLUME:MicroBoxPlaneVolume.dryVolume(micro,new GasGeometryOverlap.HalfSpace(nx,ny,nz,offset));
    }

	private static void captureFace(int cell,int peer,int axis,int[] canonical,FlipCutCellGeometry cut,
	                                FlipPressurePhaseGeometry phase,double h2,int[] first,int[] second,double[] areas,List<Port> result){
        boolean full=fullFixedConnection(cut,cell,peer,axis);
        if(full&&canonical[8*cell+(1<<axis)]>=0&&canonical[8*peer]>=0
                &&GasFaceIntersection.fullDryFace(phase,cell,axis,1)&&GasFaceIntersection.fullDryFace(phase,peer,axis,0)){
            result.add(new Port(canonical[8*cell+(1<<axis)],canonical[8*peer],axis,h2,true));
            return;
        }
		int count=0;
		for(int a=0;a<2;a++)for(int b=0;b<2;b++){
			int slot=1<<axis|a<<((axis+1)%3)|b<<((axis+2)%3),other=slot^(1<<axis);
			if(canonical[8*cell+slot]<0||canonical[8*peer+other]<0)continue;
			double area=GasFaceIntersection.area(phase,cell,slot,peer,other,axis,1,0,.5*a,.5*b,.5);if(area<=0)continue;
			int from=canonical[8*cell+slot],to=canonical[8*peer+other],entry=0;while(entry<count&&(first[entry]!=from||second[entry]!=to))entry++;
			if(entry==count){first[count]=from;second[count]=to;areas[count++]=0;}areas[entry]+=area;
		}
		if(count==0)return;
		for(int i=0;i<count;i++)result.add(new Port(first[i],second[i],axis,physicalArea(areas[i],h2,cell),full));
	}
	private static void captureExteriorFace(int cell,int axis,int bit,int[] canonical,FlipCutCellGeometry cut,
	                                        FlipPressurePhaseGeometry phase,double h2,int[] first,double[] areas,List<ExteriorPort> result){
        boolean full=fullFixedFace(cut,cell,axis,bit);
        if(full&&canonical[8*cell+(bit<<axis)]>=0&&GasFaceIntersection.fullDryFace(phase,cell,axis,bit)){
            result.add(new ExteriorPort(canonical[8*cell+(bit<<axis)],axis,bit==0?-1:1,h2,true));
            return;
        }
		int count=0;
		for(int a=0;a<2;a++)for(int b=0;b<2;b++){
			int slot=bit<<axis|a<<((axis+1)%3)|b<<((axis+2)%3);if(canonical[8*cell+slot]<0)continue;
			double area=GasFaceIntersection.area(phase,cell,-1,axis,bit,0,.5*a,.5*b,.5);if(area<=0)continue;
			int from=canonical[8*cell+slot],entry=0;while(entry<count&&first[entry]!=from)entry++;
			if(entry==count){first[count]=from;areas[count++]=0;}areas[entry]+=area;
		}
		if(count==0)return;
		for(int i=0;i<count;i++)result.add(new ExteriorPort(first[i],axis,bit==0?-1:1,physicalArea(areas[i],h2,cell),full));
	}
	private static int coordinate(int cell, int axis, int sizeX, int sizeY) {
		return axis == 0 ? cell % sizeX : axis == 1 ? cell / sizeX % sizeY : cell / (sizeX * sizeY);
	}
	/** The carrier and topology use the same criterion for an unrestricted fixed opening. */
	static boolean fullFixedConnection(FlipCutCellGeometry cut,int first,int second,int axis) {
		int positive=switch(axis){case 0->0xaa;case 1->0xcc;default->0xf0;};
		return (cut.mask(first)&positive)==0&&(cut.mask(second)&(positive^255))==0;
	}
	private static boolean fullFixedFace(FlipCutCellGeometry cut,int cell,int axis,int side) {
		int positive=switch(axis){case 0->0xaa;case 1->0xcc;default->0xf0;};
		return (cut.mask(cell)&(side==1?positive:positive^255))==0;
	}
	private static double physicalArea(double normalizedArea, double h2, int cell) {
		if (normalizedArea == 0) return 0;
		double result = normalizedArea * h2;
		if (!(result > 0 && Double.isFinite(result))) throw blocked("Positive gas port area is not representable", cell);
		return result;
	}
	private static GasTopologyBlockedException blocked(String message, int cell) {
		return new GasTopologyBlockedException(message + " at cell " + cell);
	}

	double componentDry(int cell,int support){return componentDry[8*cell+Integer.numberOfTrailingZeros(support)];}
	GasComponentCapacity.Prepared actualCapacity(){return actualCapacity;}
	GasChamberTopology cachedTopology(boolean sealed){return sealed?sealedTopology:openTopology;}
	void cacheTopology(boolean sealed,GasChamberTopology topology){if(sealed)sealedTopology=topology;else openTopology=topology;}
	boolean matchesCollisionMasks(byte[] masks){return Arrays.equals(collisionMasks,masks);}
	boolean sameCollisionMasks(GasComplementGeometry other){return Arrays.equals(collisionMasks,other.collisionMasks);}
	double cellVolumeCubicMeters() { return cellVolumeCubicMeters; }
	int collisionMask(int cell) { return Byte.toUnsignedInt(collisionMasks[cell]); }
	SignedDyadic poreVolume(int cell) { return poreVolumes[cell]; }
	int sizeX() { return sizeX; }
	int sizeY() { return sizeY; }
	int sizeZ() { return sizeZ; }
	Component component(int cell,int support){return bySlot[8*cell+Integer.numberOfTrailingZeros(support)];}
    Component componentAtMicro(int piece){int cell=piece/8,support=CellRemapMicroTopology.support(collisionMask(cell),piece&7);return support==0?null:component(cell,support);}
    boolean hasPiece(int piece){var component=componentAtMicro(piece);return component!=null&&(component.dryMicroMask()&1<<(piece&7))!=0;}
    double pieceNormalizedVolume(int piece){
        var component=componentAtMicro(piece);if(component==null||(component.dryMicroMask()&1<<(piece&7))==0)return 0;
        if(component.compact())return component.normalizedVolume();
        if(component.occupancy()!=null)return MICRO_VOLUME*(1-component.occupancy().fractions()[piece&7]);
        var plane=component.plane();
        return plane.fraction()==0?MICRO_VOLUME:MicroBoxPlaneVolume.dryVolume(piece&7,
                new GasGeometryOverlap.HalfSpace(plane.nx(),plane.ny(),plane.nz(),plane.offset()));
    }
    Piece piece(int id) {
        if(!hasPiece(id))return null;int cell=id/8,micro=id&7;var component=componentAtMicro(id);
        double normalized=pieceNormalizedVolume(id);var p=component.plane();
        double fraction=component.occupancy()==null?p.fraction():component.occupancy().fractions()[micro];
        var plane=component.compact()||fraction==0?null:new GasGeometryOverlap.HalfSpace(p.nx(),p.ny(),p.nz(),p.offset());
        return new Piece(id,cell,micro,normalized*cellVolumeCubicMeters,normalized,plane,component.compact()?null:p.bracket());
    }
    /** Explicit microbox observation; runtime ownership and remapping use components. */
    List<Piece> pieces(){
        var result=new ArrayList<Piece>();for(var component:components)for(int bits=component.dryMicroMask();bits!=0;bits&=bits-1)
            result.add(piece(8*component.cell()+Integer.numberOfTrailingZeros(bits)));
        return List.copyOf(result);
    }
    List<Component> components(){return components;}
	List<Port> ports() { return ports; }
	List<ExteriorPort> exteriorPorts() { return exteriorPorts; }
}
