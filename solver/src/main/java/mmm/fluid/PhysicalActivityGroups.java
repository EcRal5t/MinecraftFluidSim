package mmm.fluid;

import mmm.inventory.LiquidCellInventory;
import mmm.thermal.GranularEnergyColumn;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Independent scheduling over one shared inventory, MAC field and audit. */
final class PhysicalActivityGroups {
	private static final int QUIET_STEPS = 200;
	private static final double QUIET_SPEED = 2.5E-3;
	private final HostFluidState domain;
	private Snapshot previous = Snapshot.EMPTY;
	private List<int[]> connections = List.of();
	private List<int[]> thermalConnections = List.of();
	private boolean[] requestedWake;
	private int[] parent = new int[0], gasCells = new int[0], mechanicalQuiet = new int[0], thermalQuiet = new int[0];
	private boolean[] mechanical = new boolean[0], thermal = new boolean[0], keepMechanical = new boolean[0], keepThermal = new boolean[0];
	private LiquidCellInventory.Snapshot startLiquid;
	private long[] startAuxiliary;
	private boolean enabled;
	private boolean[] worldAvailable, blocked = new boolean[0];

	static final class Snapshot {
		static final Snapshot EMPTY = new Snapshot(null, null, new int[0], new int[0], new int[0], List.of(), List.of(), null, null);
		final LiquidCellInventory.Snapshot liquid;
		final FixedGeometrySnapshot geometry;
		final int[] roots, mechanicalQuiet, thermalQuiet;
		final List<int[]> connections, thermalConnections;
		final long[] auxiliary;
		final boolean[] requestedWake;
		final boolean[] worldAvailable;
		Snapshot(LiquidCellInventory.Snapshot liquid, FixedGeometrySnapshot geometry, int[] roots,
		         int[] mechanicalQuiet, int[] thermalQuiet, List<int[]> connections, List<int[]> thermalConnections,
		         long[] auxiliary, boolean[] requestedWake) {
            this(liquid,geometry,roots,mechanicalQuiet,thermalQuiet,connections,thermalConnections,auxiliary,requestedWake,null);
        }
        Snapshot(LiquidCellInventory.Snapshot liquid, FixedGeometrySnapshot geometry, int[] roots,
                 int[] mechanicalQuiet, int[] thermalQuiet, List<int[]> connections, List<int[]> thermalConnections,
                 long[] auxiliary, boolean[] requestedWake, boolean[] worldAvailable) {
			this.liquid=liquid; this.geometry=geometry; this.roots=roots;
			this.mechanicalQuiet=mechanicalQuiet; this.thermalQuiet=thermalQuiet;
			this.connections=connections; this.thermalConnections=thermalConnections;
			this.auxiliary=auxiliary; this.requestedWake=requestedWake;
            this.worldAvailable=worldAvailable==null?null:worldAvailable.clone();
		}
	}

	PhysicalActivityGroups(HostFluidState domain) { this.domain=domain; }

	void restore(Snapshot state) {
		previous=state; connections=state.connections; thermalConnections=state.thermalConnections;
        worldAvailable=state.worldAvailable==null?null:state.worldAvailable.clone();
		requestedWake=state.requestedWake==null?null:state.requestedWake.clone();
		parent=state.roots.clone();mechanicalQuiet=state.mechanicalQuiet.clone();thermalQuiet=state.thermalQuiet.clone();
		mechanical=new boolean[parent.length];thermal=new boolean[parent.length];
		keepMechanical=new boolean[parent.length];keepThermal=new boolean[parent.length];blocked=new boolean[parent.length];
		for(int cell=0;cell<parent.length;cell++)if(parent[cell]>=0) {
			mechanical[cell]=mechanicalQuiet[cell]<QUIET_STEPS;thermal[cell]=thermalQuiet[cell]<QUIET_STEPS;
		}
        for(int cell=0;cell<parent.length;cell++)if(parent[cell]>=0&&!worldCellAvailable(cell))blocked[root(cell)]=true;
	}

	Snapshot capture() {
		return new Snapshot(previous.liquid,previous.geometry,previous.roots,previous.mechanicalQuiet,previous.thermalQuiet,
				connections,thermalConnections,previous.auxiliary,requestedWake==null?null:requestedWake.clone(),worldAvailable);
	}

    void prepareAvailability(boolean[] available) {
        if(enabled)throw new IllegalStateException("Cannot replace world availability during a physical step");
        if(available==null||available.length!=domain.cellCount)throw new IllegalArgumentException("World availability has the wrong cell layout");
        var before=worldAvailable;
        boolean all=true;for(boolean value:available)all&=value;
        if(all&&before==null)return;
        worldAvailable=all?null:available.clone();
        if(before!=null)for(int cell=0;cell<available.length;cell++)if(available[cell]&&!before[cell]) {
            if(requestedWake==null)requestedWake=new boolean[domain.cellCount];requestedWake[cell]=true;
        }
        // Availability observation preserves quiet ages and accepted inventory comparisons.
        var wake=requestedWake==null?null:requestedWake.clone();
        beginStep();enabled=false;requestedWake=wake;
        int[] mq=new int[parent.length],tq=new int[parent.length];
        for(int cell=0;cell<parent.length;cell++){int r=root(cell);if(r>=0){mq[cell]=mechanicalQuiet[r];tq[cell]=thermalQuiet[r];}}
        previous=new Snapshot(previous.liquid,previous.geometry,parent.clone(),mq,tq,connections,thermalConnections,previous.auxiliary,wake,worldAvailable);
    }
    boolean worldCellAvailable(int cell) {return cell>=0&&cell<domain.cellCount&&(worldAvailable==null||worldAvailable[cell]);}
    boolean groupAvailable(int cell) {int r=root(cell);return worldCellAvailable(cell)&&(r<0||!blocked[r]);}
    boolean hasAvailableGroups(){if(worldAvailable==null)return true;for(int cell=0;cell<domain.cellCount;cell++)if(root(cell)>=0&&groupAvailable(cell))return true;return false;}
    boolean parcelAvailable(int index){return groupAvailable(domain.domainInventoryGeometry.granularParcelCell(domain.granularState.parcels.parcel(index)));}
    boolean bodyAvailable(mmm.world.MovingBodyFluidCoupling.Bounds bounds) {
        if (worldAvailable == null) return true;
        for (int cell : bodyCells(bounds)) if (!groupAvailable(cell)) return false;
        return true;
    }

	void replaceConnections(List<int[]> groups) {
		var copy=new ArrayList<int[]>();
		for(var cells:groups) {
			int[] ordered=cells.clone(); Arrays.sort(ordered);
			for(int cell:ordered) if(cell<0||cell>=domain.cellCount) throw new IllegalArgumentException("Activity connection is outside the domain");
			copy.add(ordered);
		}
		boolean same=copy.size()==connections.size();
		for(int i=0;same&&i<copy.size();i++) same=Arrays.equals(copy.get(i),connections.get(i));
		if(same)return;
		if(requestedWake==null)requestedWake=new boolean[domain.cellCount];
		for(var cells:connections)for(int cell:cells)requestedWake[cell]=true;
		for(var cells:copy)for(int cell:cells)requestedWake[cell]=true;
		connections=List.copyOf(copy);
		if(enabled)for(var cells:connections)joinCells(cells,true);
	}

	void beginStep() {
		if(enabled)throw new IllegalStateException("Activity step is already open");
		domain.domainFlowFields.preparePhysicalState();
		int count=domain.cellCount;
		parent=new int[count]; mechanicalQuiet=new int[count]; thermalQuiet=new int[count];
		mechanical=new boolean[count]; thermal=new boolean[count];keepMechanical=new boolean[count];keepThermal=new boolean[count];blocked=new boolean[count];
		var geometry=domain.geometry.cutCells.snapshot();
		boolean continued=previous.liquid!=null&&previous.roots.length==count;
		for(int cell=0;cell<count;cell++) {
			parent[cell]=geometry.openCellVolume(cell)>0?cell:-1;
            blocked[cell]=!worldCellAvailable(cell);
			mechanicalQuiet[cell]=continued?previous.mechanicalQuiet[cell]:0;
			thermalQuiet[cell]=continued?previous.thermalQuiet[cell]:0;
			mechanical[cell]=mechanicalQuiet[cell]<QUIET_STEPS;
			thermal[cell]=thermalQuiet[cell]<QUIET_STEPS;
		}
		for(int cell=0;cell<count;cell++)if(parent[cell]>=0) {
			int x=cell%domain.sizeX,y=cell/domain.sizeX%domain.sizeY,z=cell/(domain.sizeX*domain.sizeY);
			if(x+1<domain.sizeX&&geometry.faceOpen(0)[domain.domainFlowFields.xFaceIndex(x+1,y,z)]>0)coupleCells(cell,cell+1);
			if(y+1<domain.sizeY&&geometry.faceOpen(1)[domain.domainFlowFields.yFaceIndex(x,y+1,z)]>0)coupleCells(cell,cell+domain.sizeX);
			if(z+1<domain.sizeZ&&geometry.faceOpen(2)[domain.domainFlowFields.zFaceIndex(x,y,z+1)]>0)coupleCells(cell,cell+domain.sizeX*domain.sizeY);
		}
		var frame=domain.gasState.frame;
		gasCells=new int[domain.gasState.network.nodeCount()+1];Arrays.fill(gasCells,-1);
		if(frame!=null)for(int cell=0;cell<count;cell++)if(parent[cell]>=0)for(int micro=0;micro<8;micro++) {
			int node=frame.nodeOfPiece(8*cell+micro);
			if(node>0) { if(gasCells[node]<0)gasCells[node]=cell;else coupleCells(gasCells[node],cell); }
		}
		for(var edge:domain.gasState.network.edges())if(edge.firstNode()>0&&edge.secondNode()>0)
			coupleCells(gasCells[edge.firstNode()],gasCells[edge.secondNode()]);
		if(previous.geometry!=geometry)thermalConnections=List.of();
		for(var cells:connections)joinCells(cells,false);
		for(var cells:thermalConnections)joinCells(cells,false);
        // Conducting carriers can connect otherwise separate pores; observe before mechanics.
        if(worldAvailable!=null&&domain.thermalState!=null) {
            var carriers=mmm.thermal.ThermalCarrierTransport.prepare(domain.thermalState.exchangeCarrierAccess(domain.domainCapabilities.thermalMarkerContext()));
            CarrierThermalConduction.contacts(domain,carriers);
        }
		if(domain.gasState.pressureActive) domain.gasState.network.forEachPressureDrivenNode(
				domain.thermalState!=null && domain.thermalState.gasModelBound()
						?domain.thermalState.gasThermodynamics(domain.gasState.network):null,
				node->{ if(node<gasCells.length) {keepMechanicsAwake(gasCells[node]);keepThermalAwake(gasCells[node]);} });
		for(var body:domain.entityBodies.bodies()) {
			var cells=bodyCells(body.bounds());joinCells(cells,false);
			for(int cell:cells)keepMechanicsAwake(cell);
		}
		if(domain.granularState!=null)for(int parcel=0;parcel<domain.granularState.parcels.count();parcel++) {
			var p=domain.granularState.parcels.parcel(parcel);
			int[] cells=parcelCells(p.x(),p.y(),p.z());joinCells(cells,false);
			for(int cell:cells)keepMechanicsAwake(cell);
		}
		if(domain.granularDepositDynamics!=null)for(int column:domain.granularDepositDynamics.captureState().queuedColumns()) {
			int x=column%domain.sizeX,z=column/domain.sizeX;
			for(int y=0;y<domain.sizeY;y++)keepMechanicsAwake(x+domain.sizeX*(y+domain.sizeY*z));
		}
		startLiquid=domain.liquidCells.snapshot();startAuxiliary=auxiliary();
		for(int cell=0;cell<count;cell++)if(parent[cell]>=0) {
			boolean edited=continued&&(!startLiquid.rows().get(cell).equals(previous.liquid.rows().get(cell))
					||startAuxiliary[cell]!=previous.auxiliary[cell]);
			boolean reshaped=continued&&(geometry.mask(cell)!=previous.geometry.mask(cell)
					||root(cell)!=previous.roots[cell]);
			if(edited||reshaped||requestedWake!=null&&requestedWake[cell])wakeCell(cell);
			if(domain.pendingPhaseMotion.isPending(cell)) {keepMechanicsAwake(cell);keepThermalAwake(cell);}
		}
		requestedWake=null;
		enabled=true;
	}

	void finishStep(boolean mechanicalRan,boolean thermalRan) {
		if(!enabled)return;
		try {
			var current=domain.liquidCells.snapshot();var auxiliary=auxiliary();
			for(int cell=0;cell<domain.cellCount;cell++)if(parent[cell]>=0) {
				int root=root(cell);
				if(mechanicalRan&&cellSpeed(cell)>QUIET_SPEED)keepMechanical[root]=true;
				boolean changed=!current.rows().get(cell).equals(startLiquid.rows().get(cell))||auxiliary[cell]!=startAuxiliary[cell];
				if(changed) {
					if(thermalRan)keepThermal[root]=true;
					else keepThermalAwake(cell);
					if(thermalRan&&!mechanical[root])keepMechanicsAwake(cell);
				}
				if(domain.pendingPhaseMotion.isPending(cell))keepMechanical[root]=keepThermal[root]=true;
			}
			for(int cell=0;cell<domain.cellCount;cell++)if(parent[cell]==cell&&!blocked[cell]) {
				if(mechanicalRan&&mechanical[cell])mechanicalQuiet[cell]=keepMechanical[cell]?0:Math.min(QUIET_STEPS,mechanicalQuiet[cell]+1);
				if(thermalRan&&thermal[cell])thermalQuiet[cell]=keepThermal[cell]?0:Math.min(QUIET_STEPS,thermalQuiet[cell]+1);
				mechanical[cell]=mechanicalQuiet[cell]<QUIET_STEPS;thermal[cell]=thermalQuiet[cell]<QUIET_STEPS;
			}
			int[] roots=new int[domain.cellCount],mq=new int[domain.cellCount],tq=new int[domain.cellCount];
			for(int cell=0;cell<domain.cellCount;cell++) {
				int root=root(cell);roots[cell]=root;
				if(root>=0){mq[cell]=mechanicalQuiet[root];tq[cell]=thermalQuiet[root];}
			}
			previous=new Snapshot(current,domain.geometry.cutCells.snapshot(),roots,mq,tq,connections,thermalConnections,auxiliary,null,worldAvailable);
		} finally {enabled=false;}
	}

	void abortStep() { enabled=false; }

	boolean mechanicsActive(int cell) {
		int r=root(cell);
		return worldCellAvailable(cell)&&(r<0||!blocked[r])&&(!enabled||r>=0&&mechanical[r]);
	}
	boolean thermalActive(int cell) {
		int r=root(cell);
		return worldCellAvailable(cell)&&(r<0||!blocked[r])&&(!enabled||r>=0&&thermal[r]);
	}
	boolean mechanicsFaceActive(int first,int second) {return mechanicsActive(first)||mechanicsActive(second);}
	boolean thermalSleeping(int cell) {int r=root(cell);return r>=0&&!thermal[r];}
	boolean mechanicsSleeping(int cell) {int r=root(cell);return r>=0&&!mechanical[r];}
	boolean gasNodeIsActive(int node) {
		if(!enabled&&worldAvailable==null)return true;
		int cell=gasCell(node);
		return mechanicsActive(cell)||thermalActive(cell);
	}

	void wakeCell(int cell) {int r=root(cell);if(r>=0){mechanical[r]=thermal[r]=true;mechanicalQuiet[r]=thermalQuiet[r]=0;}}
	void keepMechanicsAwake(int cell) {int r=root(cell);if(r>=0){mechanical[r]=true;mechanicalQuiet[r]=0;keepMechanical[r]=true;}}
	void keepThermalAwake(int cell) {int r=root(cell);if(r>=0){thermal[r]=true;thermalQuiet[r]=0;keepThermal[r]=true;}}
	void coupleThermalCells(int first,int second) {
		if(root(first)<0||root(second)<0||root(first)==root(second))return;
		var copy=new ArrayList<>(thermalConnections);copy.add(new int[]{first,second});thermalConnections=List.copyOf(copy);
		coupleCells(first,second);
	}

	void coupleCells(int first,int second) {
		int a=root(first),b=root(second);if(a<0||b<0||a==b)return;
		if(a>b){int swap=a;a=b;b=swap;}
		parent[b]=a;blocked[a]|=blocked[b];
		mechanical[a]|=mechanical[b];thermal[a]|=thermal[b];
		keepMechanical[a]|=keepMechanical[b];keepThermal[a]|=keepThermal[b];
		mechanicalQuiet[a]=Math.min(mechanicalQuiet[a],mechanicalQuiet[b]);thermalQuiet[a]=Math.min(thermalQuiet[a],thermalQuiet[b]);
	}

	private void joinCells(int[] cells,boolean wake) {int anchor=-1;for(int cell:cells)if(root(cell)>=0){if(anchor<0)anchor=cell;else coupleCells(anchor,cell);if(wake)wakeCell(cell);}}
	private int root(int cell) {
		if(cell<0||cell>=parent.length||parent[cell]<0)return -1;
		int direct=parent[cell];if(parent[direct]==direct)return direct;
		int r=cell;while(parent[r]!=r)r=parent[r];
		while(parent[cell]!=cell){int next=parent[cell];parent[cell]=r;cell=next;}
		return r;
	}

	private int[] bodyCells(mmm.world.MovingBodyFluidCoupling.Bounds b) {
		double h=domain.config.cellSize();var cells=new java.util.ArrayList<Integer>();
		for(int z=Math.max(0,(int)Math.floor(b.minimumZ()/h));z<=Math.min(domain.sizeZ-1,(int)Math.ceil(b.maximumZ()/h)-1);z++)
		for(int y=Math.max(0,(int)Math.floor(b.minimumY()/h));y<=Math.min(domain.sizeY-1,(int)Math.ceil(b.maximumY()/h)-1);y++)
		for(int x=Math.max(0,(int)Math.floor(b.minimumX()/h));x<=Math.min(domain.sizeX-1,(int)Math.ceil(b.maximumX()/h)-1);x++) {
			int cell=x+domain.sizeX*(y+domain.sizeY*z);
			if(parent[cell]>=0)cells.add(cell);
		}
		return cells.stream().mapToInt(Integer::intValue).toArray();
	}

	private int[] parcelCells(float x,float y,float z) {
		float h=domain.config.cellSize();int ix=(int)Math.floor(x/h-.5),iy=(int)Math.floor(y/h-.5),iz=(int)Math.floor(z/h-.5);
		int[] cells=new int[8];int count=0;
		for(int dz=0;dz<2;dz++)for(int dy=0;dy<2;dy++)for(int dx=0;dx<2;dx++) {
			int a=ix+dx,b=iy+dy,c=iz+dz;
			if(a>=0&&a<domain.sizeX&&b>=0&&b<domain.sizeY&&c>=0&&c<domain.sizeZ)cells[count++]=a+domain.sizeX*(b+domain.sizeY*c);
		}
		return Arrays.copyOf(cells,count);
	}

	private double cellSpeed(int cell) {
		int x=cell%domain.sizeX,y=cell/domain.sizeX%domain.sizeY,z=cell/(domain.sizeX*domain.sizeY);double maximum=0;
		for(int axis=0;axis<3;axis++) {
			int first=axis==0?domain.domainFlowFields.xFaceIndex(x,y,z):axis==1?domain.domainFlowFields.yFaceIndex(x,y,z):domain.domainFlowFields.zFaceIndex(x,y,z);
			int stride=axis==0?1:axis==1?domain.sizeX:domain.sizeX*domain.sizeY;
			float[] velocity=axis==0?domain.mac.velocityX:axis==1?domain.mac.velocityY:domain.mac.velocityZ;
			maximum=Math.max(maximum,Math.max(Math.abs(velocity[first]),Math.abs(velocity[first+stride])));
		}
		return maximum;
	}

	private long[] auxiliary() {
		long[] values=new long[domain.cellCount];
		for(int cell=0;cell<domain.cellCount;cell++) {
			int x=cell%domain.sizeX,y=cell/domain.sizeX%domain.sizeY,z=cell/(domain.sizeX*domain.sizeY);
			for(int axis=0;axis<3;axis++) {
				int face=axis==0?domain.domainFlowFields.xFaceIndex(x,y,z):axis==1?domain.domainFlowFields.yFaceIndex(x,y,z):domain.domainFlowFields.zFaceIndex(x,y,z);
				double[] momentum=axis==0?domain.mac.momentumX:axis==1?domain.mac.momentumY:domain.mac.momentumZ;
				values[cell]=31*values[cell]+Double.doubleToLongBits(momentum[face]);
			}
			if(domain.thermalBoundaryTemperatureK!=null)for(int face=0;face<6;face++)values[cell]=31*values[cell]+Double.doubleToLongBits(domain.thermalBoundaryTemperatureK[6*cell+face]);
		}
		if(domain.thermalState!=null)for(var group:domain.thermalState.coherentPhases().snapshot())
			values[group.cell()]=31*values[group.cell()]+Double.doubleToLongBits(group.internalEnergy())+group.massUnits();
		if(domain.granularState!=null) {
			for(int i=0;i<domain.granularState.parcels.count();i++) {
				var parcel=domain.granularState.parcels.parcel(i);int cell=domain.domainInventoryGeometry.granularParcelCell(parcel);
				values[cell]=31*values[cell]+parcel.massUnits()+parcel.solidVolumeSvq();
				if(domain.thermalState!=null)values[cell]+=Double.doubleToLongBits(domain.thermalState.granularColumn().activeEnergy(i));
			}
			domain.granularState.deposits.forEachGroup((cell,porosity,material,composition,volume,mass)->{
				values[cell]=31*values[cell]+volume+mass;
				if(domain.thermalState!=null)values[cell]+=Double.doubleToLongBits(domain.thermalState.granularColumn().depositEnergy(new GranularEnergyColumn.DepositKey(cell,material,composition)));
			});
		}
		for(int node=1;node<=domain.gasState.network.nodeCount();node++) {
			int cell=gasCell(node);if(cell<0)continue;
			values[cell]=31*values[cell]+domain.gasState.network.inventory(node).components().hashCode();
			if(domain.thermalState!=null)values[cell]+=Double.doubleToLongBits(domain.thermalState.gasNodeEnergy(node,domain.gasState.network));
		}
		return values;
	}

	private int gasCell(int node) {
		if(node<=0||domain.gasState.frame==null)return -1;
		var chambers=domain.gasState.frame.topology().chambers();
		return node<=chambers.size()?chambers.get(node-1).minimumPiece()/8:-1;
	}

	FluidDomainContracts.ActivityStats stats() {
		if(parent.length==0)return new FluidDomainContracts.ActivityStats(0,0,0,domain.cellCount);
		int groups=0,sleeping=0,sleepingCells=0,active=0,blockedGroups=0,blockedCells=0;
		for(int cell=0;cell<parent.length;cell++)if(parent[cell]>=0) {
			int r=root(cell);
            if(blocked[r]){if(r==cell){groups++;blockedGroups++;}blockedCells++;continue;}
            boolean asleep=!mechanical[r]&&!thermal[r];
			if(r==cell){groups++;if(asleep)sleeping++;}
			if(asleep)sleepingCells++;else active++;
		}
		return new FluidDomainContracts.ActivityStats(groups,sleeping,sleepingCells,active,blockedGroups,blockedCells);
	}
}
