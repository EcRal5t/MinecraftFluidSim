package mmm.fluid;

import mmm.units.SignedDyadic;
import java.util.Arrays;
import java.util.List;

/** Opposing liquid bodies and continuous MAC transport across candidate restoration. */
public final class LiquidHandoffContinuityValidation {
	private LiquidHandoffContinuityValidation() { }
	public static void main(String[] args) {
		opposingWaterCubesJoin();
		System.out.println("Liquid joining and handoff continuity validation passed");
	}
	private static void opposingWaterCubesJoin() {
		var coarse=runJoin(.5F,.025F);var timeFine=runJoin(.5F,.0125F);
		var spaceFine=runJoin(.25F,.025F);var bothFine=runJoin(.25F,.0125F);
		System.out.printf("join refinement timeDelta=%g/%g spaceDelta=%g/%g s%n",timeFine-coarse,bothFine-spaceFine,spaceFine-coarse,bothFine-timeFine);
	}
	private static FluidDomain joiningWater(float h,float dt) {
		int nx=Math.round(12/h),ny=Math.round(6/h);
		var domain=new FluidDomain(nx,ny,ny,new FluidSolverConfig(h,1000,0,dt,500,1e-6F,.002F,.8F),nx*ny*ny*8);
		var builtin=BuiltinFluidMaterials.WATER;
		var water=new FluidMaterial(builtin.id(),builtin.referenceDensity(),builtin.miscibilityGroup(),.0728,Math.PI/2);
		domain.materialRegistry=new FluidMaterialRegistry(List.of(water));
		var base=BuiltinFluidMaterials.createRegistry();
		domain.bindMaterialDefinitions(new MaterialRegistry(base.entries().stream().map(entry->entry.material().id().equals(water.id())
				?new MaterialRegistry.MaterialEntry(water,entry.viscosity(),entry.thermalExpansion()):entry).toList(),base.pairTensions()));
		for(int z=Math.round(2.5F/h);z<Math.round(3.5F/h);z++)for(int y=Math.round(2.5F/h);y<Math.round(3.5F/h);y++)
			for(int x=Math.round(3/h);x<Math.round(7/h);x++)if(x<Math.round(4/h)||x>=Math.round(6/h))domain.setVolume(x,y,z,1);
		domain.domainFlowFields.ensureGrid();
		for(int f=0;f<domain.mac.velocityX.length;f++)if(domain.cellMacStage.massX[f]>0)
			domain.mac.velocityX[f]=f%(nx+1)*h<5?.5F:-.5F;
		domain.cellMacStage.publishMomentum();return domain;
	}
	private static double runJoin(float h,float dt) {
		var domain=joiningWater(h,dt);var initialV=domain.liquidCells.exactVolume();long initialM=domain.totalMassUnits();
		check(domain.unitScale.cubicMeters(initialV.toDouble())==2&&mmm.units.Mass.kilograms(initialM)==2000,"Joining bodies changed their analytic water inventory");
		var topology=new JoinTopology(domain);
		int steps=Math.round(2.5F/dt),finalJoinStep=steps+1;boolean previous=false;JoinObservation observed=null;double firstJoin=Double.NaN;
		for(int step=0;step<=steps;step++) {
			check(initialV.equals(domain.liquidCells.exactVolume())&&initialM==domain.totalMassUnits(),"Water joining changed exact material inventory");
			domain.domainFlowFields.ensureGrid();observed=topology.observe();
			if(step==0)check(!observed.connected&&observed.components>=2,"Water bodies started connected");
			if(observed.connected&&!previous){finalJoinStep=step;if(Double.isNaN(firstJoin))firstJoin=step*(double)dt;}
			previous=observed.connected;
			for(var available:domain.domainFlowFields.availableCellVolumes())check(available.signum()>=0,"Joining liquid exceeded cell capacity");
			check(domain.gasBoundary().auditClosed(),"Joining bodies lost gas conservation");
			if(step==steps)break;
			domain.domainStep.step();if(step%20==19){domain=FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());topology=new JoinTopology(domain);}
		}
		check(observed.connected&&finalJoinStep<=Math.round(2/dt),"Major water bodies did not stay connected from nominal contact through 2.5 seconds");
		var motion=LiquidMacroscopicMotionValidation.Motion.capture(domain);
		check(Double.isFinite(motion.kinetic()+Arrays.stream(motion.momentum()).sum()),"Water joining produced nonfinite MAC mechanics");
		System.out.printf("join h=%g dt=%g firstJoin=%g finalJoin=%g largestV=%g tailV=%g mass=%g K=%g%n",h,dt,firstJoin,finalJoinStep*(double)dt,observed.largestV,observed.tailV,mmm.units.Mass.kilograms(initialM),motion.kinetic());
		return firstJoin;
	}

	private static final class JoinTopology {
		private final FluidDomain domain;
		private final int[] component, queue;
		private final double[] volume, leftVolume, rightVolume;
		JoinTopology(FluidDomain domain) {
			this.domain = domain; component = new int[domain.cellCount]; queue = new int[domain.cellCount];
			volume = new double[domain.cellCount]; leftVolume = new double[domain.cellCount]; rightVolume = new double[domain.cellCount];
		}
		JoinObservation observe() {
			Arrays.fill(component, -1); Arrays.fill(volume, 0); Arrays.fill(leftVolume, 0); Arrays.fill(rightVolume, 0);
			int count = 0; double centerArea = 0, h = domain.config().cellSize();
			for (var stock : domain.liquidCells.stocks()) {
				int seed = stock.key().cell(); if (component[seed] >= 0) continue;
				int head = 0, tail = 0; queue[tail++] = seed; component[seed] = count;
				while (head < tail) {
					int cell = queue[head++], x = cell % domain.sizeX, y = cell / domain.sizeX % domain.sizeY, z = cell / (domain.sizeX * domain.sizeY);
					check(domain.liquidGrid.exactCellVolumeInventory[cell].compareTo(SignedDyadic.of(1)) <= 0, "Joining water exceeded cell capacity");
					double v = domain.unitScale.cubicMeters(domain.liquidGrid.cellStockVolume[cell]); volume[count] += v;
					if ((x + 0.5) * h < 5) leftVolume[count] += v; else rightVolume[count] += v;
					for (int axis = 0; axis < 3; axis++) for (int sign = -1; sign <= 1; sign += 2) {
						int xx = x + (axis == 0 ? sign : 0), yy = y + (axis == 1 ? sign : 0), zz = z + (axis == 2 ? sign : 0);
						if (!domain.domainFlowFields.inside(xx, yy, zz)) continue;
						int other = domain.domainFlowFields.cellIndex(xx, yy, zz); if (domain.liquidGrid.exactCellVolumeInventory[other].signum() <= 0) continue;
						double area = domain.pressurePhaseGeometry.sharedLiquidFaceArea(sign > 0 ? cell : other, sign > 0 ? other : cell, axis, domain.geometry.cutCells);
						if (axis == 0 && sign > 0 && (x + 1) * h == 5) centerArea += area * h * h;
						if (area > 0 && component[other] < 0) { component[other] = count; queue[tail++] = other; }
					}
				}
				count++;
			}
			int left = -1, right = -1, tails = 0; double largest = 0, second = 0, tailV = 0;
			for (int c = 0; c < count; c++) {
				if (leftVolume[c] > 0 && (left < 0 || leftVolume[c] > leftVolume[left])) left = c;
				if (rightVolume[c] > 0 && (right < 0 || rightVolume[c] > rightVolume[right])) right = c;
				if (volume[c] > largest) { second = largest; largest = volume[c]; } else if (volume[c] > second) second = volume[c];
			}
			for (int c = 0; c < count; c++) if (c != left && c != right) { tails++; tailV += volume[c]; }
			return new JoinObservation(count, left >= 0 && left == right, largest, second, tails, tailV, centerArea);
		}
	}
	private record JoinObservation(int components,boolean connected,double largestV,double secondV,int tails,double tailV,double centerArea) { }
	private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
