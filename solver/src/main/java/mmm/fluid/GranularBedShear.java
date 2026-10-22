package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import mmm.granular.ParticulateMaterial;
import mmm.granular.GranularMechanics;
import mmm.granular.SolidVolume;
import mmm.units.SignedDyadic;

/** Equilibrium smooth-wall closure for unresolved near-bed shear; no synthetic turbulent velocity. */
final class GranularBedShear {
	private GranularBedShear() { }

	/** Local bed traction pays the exact lift work from persistent horizontal MAC momentum. */
	static LiftWork planLift(FluidDomain domain, int cell, double joules) {
		if (!Double.isFinite(joules)) throw new IllegalArgumentException("Non-finite bed lift work");
		return planLift(domain, cell, SignedDyadic.of(joules));
	}
	static LiftWork planLift(FluidDomain domain, int cell, SignedDyadic work) {
		domain.domainFlowFields.ensureVelocityGrid();
		return planLiftPrepared(domain,cell,work);
	}
	private static LiftWork planLiftPrepared(FluidDomain domain,int cell,SignedDyadic work){
		if (domain == null || cell < 0 || cell >= domain.cellCount || work == null || !Double.isFinite(work.toDouble()))
			throw new IllegalArgumentException("Invalid bed lift work");
		var faces = new java.util.ArrayList<Face>(8);
		int x=cell%domain.sizeX, y=cell/domain.sizeX%domain.sizeY, z=cell/(domain.sizeX*domain.sizeY);
		// Whole dual faces of the bed cell and the free-flow cell above share the local traction.
		for(int row=y;row<=Math.min(y+1,domain.sizeY-1);row++) {
			addFace(domain,faces,0,domain.domainFlowFields.xFaceIndex(x,row,z));
			addFace(domain,faces,0,domain.domainFlowFields.xFaceIndex(x+1,row,z));
			addFace(domain,faces,2,domain.domainFlowFields.zFaceIndex(x,row,z));
			addFace(domain,faces,2,domain.domainFlowFields.zFaceIndex(x,row,z+1));
		}
		double kinetic=0;
		for(var face:faces) kinetic+=face.energy(face.before());
		if(!Double.isFinite(kinetic)) throw new ArithmeticException("Bed traction energy overflow");
		if(work.compareTo(SignedDyadic.of(kinetic))>0 || kinetic==0 && work.signum()<0) return null;
		double scale=kinetic==0?1:Math.sqrt(Math.max(0,kinetic-work.toDouble()))/Math.sqrt(kinetic);
		var prepared=new Face[faces.size()];
		var loss=SignedDyadic.ZERO; double impulseX=0,impulseZ=0;
		for(int i=0;i<prepared.length;i++) {
			var face=faces.get(i); double next=face.before()*scale;
			float observed=(float)(next/face.mass());
			if(!Double.isFinite(next)||!Float.isFinite(observed)) throw new ArithmeticException("Bed traction momentum overflow");
			prepared[i]=new Face(face.axis(),face.index(),face.mass(),face.before(),next,observed);
			loss=loss.add(SignedDyadic.of(face.energy(face.before())))
					.subtract(SignedDyadic.of(face.energy(next)));
			if(face.axis()==0) impulseX+=face.before()-next; else impulseZ+=face.before()-next;
		}
		if(!Double.isFinite(impulseX)||!Double.isFinite(impulseZ)) throw new ArithmeticException("Bed traction impulse overflow");
		return new LiftWork(domain,domain.liquidCells,domain.liquidCells.revision(),prepared,
				work,loss.subtract(work),impulseX,impulseZ);
	}
	private static void addFace(FluidDomain domain,java.util.List<Face> faces,int axis,int index) {
		double mass=(axis==0?domain.cellMacStage.massX:domain.cellMacStage.massZ)[index];
		if(mass<=0||domain.geometry.cutCells.openFaceField(axis)[index]<=0) return;
		double momentum=(axis==0?domain.mac.momentumX:domain.mac.momentumZ)[index];
		if(!(Double.isFinite(mass)&&Double.isFinite(momentum))) throw new ArithmeticException("Invalid bed traction face");
		faces.add(new Face(axis,index,mass,momentum,momentum,0));
	}
	private record Face(int axis,int index,double mass,double before,double after,float observed) {
		double energy(double momentum){return .5*momentum*(momentum/mass);}
	}
	static final class LiftWork {
		private final FluidDomain owner;
		private final LiquidCellInventory inventory;
		private final long revision;
		private final Face[] faces;
		private final SignedDyadic physicalWork,rounding;
		private final double impulseX,impulseZ;
		private boolean committed;
		private LiftWork(FluidDomain owner,LiquidCellInventory inventory,long revision,Face[] faces,
				SignedDyadic physicalWork,SignedDyadic rounding,double impulseX,double impulseZ){
			this.owner=owner;this.inventory=inventory;this.revision=revision;this.faces=faces;
			this.physicalWork=physicalWork;this.rounding=rounding;this.impulseX=impulseX;this.impulseZ=impulseZ;
		}
		SignedDyadic physicalWork(){return physicalWork;}
		SignedDyadic mechanicalRounding(){return rounding;}
		void requireCurrent(FluidDomain domain){
			if(committed||domain!=owner||domain.liquidCells!=inventory||inventory.revision()!=revision)
				throw new IllegalStateException("Bed traction source changed");
			for(var face:faces){
				double mass=(face.axis()==0?domain.cellMacStage.massX:domain.cellMacStage.massZ)[face.index()];
				double momentum=(face.axis()==0?domain.mac.momentumX:domain.mac.momentumZ)[face.index()];
				if(Double.doubleToRawLongBits(mass)!=Double.doubleToRawLongBits(face.mass())
						||Double.doubleToRawLongBits(momentum)!=Double.doubleToRawLongBits(face.before())
						||domain.geometry.cutCells.openFaceField(face.axis())[face.index()]<=0)
					throw new IllegalStateException("Bed traction face changed");
			}
		}
		double[] apply(FluidDomain domain){
			requireCurrent(domain);
			for(var face:faces){
				(face.axis()==0?domain.mac.momentumX:domain.mac.momentumZ)[face.index()]=face.after();
				(face.axis()==0?domain.mac.velocityX:domain.mac.velocityZ)[face.index()]=face.observed();
			}
			domain.mac.markMomentumChanged();
			// Face mass and support are unchanged; the matching velocity view was updated above.
			committed=true;
			return new double[]{impulseX,0,impulseZ};
		}
	}

	/** Source liquid observation remains valid while a local solid transition changes its candidate beds. */
	static final class Flow {
		private final FluidDomain domain;
		private final LiquidCarrierProperties properties;
		private final long[] sourceSolid;
		private Flow(FluidDomain domain,LiquidCarrierProperties properties,long[] sourceSolid){
			this.domain=domain;this.properties=properties;this.sourceSolid=sourceSolid;
		}
		LiftWork planLift(int cell,SignedDyadic work){return planLiftPrepared(domain,cell,work);}
		double shields(int cell,ParticulateMaterial material){return shieldsPrepared(domain,cell,material,this);}
		GranularMechanics.FluidSample sample(float x,float y,float z){return domain.granularMechanicalStage.samplePrepared(x,y,z,properties,sourceSolid);}
	}
	static Flow prepareFlow(FluidDomain domain,long[] sourceSolid){
		domain.domainFlowFields.ensureVelocityGrid();
		return new Flow(domain,domain.domainPhaseInventory.derivedCarrierState(),sourceSolid);
	}
	static double shields(FluidDomain domain, int cell, ParticulateMaterial material) {
		return prepareFlow(domain,null).shields(cell,material);
	}
	private static double shieldsPrepared(FluidDomain domain,int cell,ParticulateMaterial material,Flow flow){
		int x = cell % domain.sizeX, y = cell / domain.sizeX % domain.sizeY;
		int z = cell / (domain.sizeX * domain.sizeY);
		double h = domain.config.cellSize();
		double bed = (y + domain.granularState.deposits.cellBulkVolumeSvq(cell)
				/ (double) SolidVolume.SVQ_PER_CELL) * h;
		float px = (float) ((x + .5) * h), pz = (float) ((z + .5) * h);
		// The deposit cell carries Darcy pore flow. Sample the first free-flow cell above it.
		if (y + 1 >= domain.sizeY) return 0;
		float py = (float) ((y + 1.5) * h);
		if (!(py > bed)) return 0;
		int sampleCell = domain.domainFlowFields.cellIndex(x, (int) (py / h), z);
		// Extrapolated MAC velocities in dry cells cannot erode a bed. Check the actual carrier.
		if (domain.liquidCells.exactCellVolume(sampleCell).signum() == 0) return 0;
		for (double cy = bed; cy < py; cy += h / 4) {
			if (domain.liquidCells.exactCellVolume(domain.domainFlowFields.cellIndex(x, (int) (cy / h), z)).signum() == 0
					|| domain.geometry.cutCells.pointInsideSolid(px, (float) cy, pz, (float) h)) return 0;
		}
		if (domain.geometry.cutCells.pointInsideSolid(px, py, pz, (float) h)) return 0;
		var liquid = flow.sample(px, py, pz);
		if (!liquid.hasLiquid()) return 0;
		double speed = Math.hypot(liquid.velocityX(), liquid.velocityZ());
		if (speed == 0) return 0;
		double stress = shearStress(speed, py - bed, liquid.density(), liquid.dynamicViscosity())
				* liquid.submergedFraction();
		double weight = Math.max(0, material.referenceDensity() - liquid.density())
				* domain.config.gravity() * material.primaryGrainDiameterMeters();
		return weight > 0 ? Math.min(Double.MAX_VALUE, stress / weight) : Double.MAX_VALUE;
	}

	/** Spalding law: y+ = u+ + (exp(k*u+)-1-k*u+-(k*u+)²/2-(k*u+)³/6)/E.
	 * Solving Re_y = u+ y+ retains tau=mu*U/y as Re tends to zero and the logarithmic layer at high Re.
	 * Constants and formulation: OpenFOAM nutUSpaldingWallFunction (kappa=0.41, E=9.8).
	 */
	static double shearStress(double speed, double distance, double density, double viscosity) {
		if (!(Double.isFinite(speed) && speed >= 0 && Double.isFinite(distance) && distance > 0
				&& Double.isFinite(density) && density > 0 && Double.isFinite(viscosity) && viscosity > 0)) {
			throw new IllegalArgumentException("Invalid bed shear input");
		}
		if (speed == 0) return 0;
		double reynolds = speed * distance * density / viscosity;
		if (!Double.isFinite(reynolds)) throw new ArithmeticException("Bed shear Reynolds overflow");
		if (reynolds < 1e-8) return viscosity * speed / distance;
		double low = 0, high = Math.sqrt(reynolds);
		// The viscous solution bounds u+ from above. Tighten for large Reynolds numbers.
		if (reynolds > 1) high = Math.min(high, (Math.log(reynolds) + Math.log(9.8)) / .41 + 1);
		for (int iteration = 0; iteration < 56; iteration++) {
			double u = .5 * (low + high), a = .41 * u;
			double tail;
			if (a < 1) {
				double term = a * a * a * a / 24;
				tail = term;
				for (int n = 5; n <= 18; n++) { term *= a / n; tail += term; }
			} else tail = Math.expm1(a) - a - a * a / 2 - a * a * a / 6;
			if (u * (u + tail / 9.8) > reynolds) high = u;
			else low = u;
		}
		double frictionVelocity = speed / (.5 * (low + high));
		double stress = density * frictionVelocity * frictionVelocity;
		if (!Double.isFinite(stress)) throw new ArithmeticException("Bed shear stress overflow");
		return stress;
	}
}
