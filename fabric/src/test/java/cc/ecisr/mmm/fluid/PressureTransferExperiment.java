package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.world.MovingBodyFluidCoupling;
import java.util.Arrays;

/** Actual pressure/G2P inertia observations from public liquid and moving-body inputs. */
public final class PressureTransferExperiment {
	private record Transfer(FluidDomain domain, float[][] incoming, float[][] before, float[][] projected,
	                        float[][] published, float[][] pic, float[][] residual, double alpha,
	                        double projectedDivergence, double extensionChange) { }
	private PressureTransferExperiment() { }
	public static void main(String[] args) {
		if (Arrays.asList(args).contains("--rotation")) { markerRotation(); return; }
		report(true); report(false); publicStep(); markerRotation();
	}

	private static FluidDomain domain(boolean full) {
		var d = new FluidDomain(4, 4, 4, new FluidSolverConfig(.5F, 1000, 0, .001F, 400, 1e-5F, .45F, .002F, .8F), 2048);
		for (int z = full ? 0 : 1; z < (full ? 4 : 3); z++) for (int y = full ? 0 : 1; y < (full ? 4 : 3); y++)
			for (int x = full ? 0 : 1; x < (full ? 4 : 3); x++) d.setVolume(x, y, z, full ? 1 : .6F);
		d.sealDomainBoundary();
		var coupled = d.applyMovingBodyCoupling(new MovingBodyFluidCoupling.Request(.85F, .95F, 1.05F, 1, .2, -.1, .1));
		if (coupled.coupledMarkers() == 0) throw new AssertionError("Moving-body setup did not couple any liquid");
		d.ensureGrid();
		for (int c = 0; c < d.cellCount; c++) if (!(Double.isFinite(d.markerGrid.exactCellMarkerVolume[c])
				&& d.markerGrid.exactCellMarkerVolume[c] >= 0 && d.markerGrid.exactCellMarkerVolume[c] <= 1)) throw new AssertionError("Invalid liquid occupancy");
		return d;
	}
	private static float[][] faces(FluidDomain d) { return new float[][]{d.mac.velocityX.clone(), d.mac.velocityY.clone(), d.mac.velocityZ.clone()}; }
	private static float[][] weights(FluidDomain d) { return new float[][]{d.mac.p2gWeightX, d.mac.p2gWeightY, d.mac.p2gWeightZ}; }
	private static float[][] markers(FluidDomain d) {
		float[][] v = new float[3][d.particles.count()];
		for (int p = 0; p < d.particles.count(); p++) { v[0][p] = d.particles.velocityX(p); v[1][p] = d.particles.velocityY(p); v[2][p] = d.particles.velocityZ(p); }
		return v;
	}
	private static double[] momentum(FluidDomain d, float[][] v) {
		double[] result = new double[3];
		for (int a = 0; a < 3; a++) for (int p = 0; p < d.particles.count(); p++) result[a] += d.unitScale.kilograms(d.particles.mass(p)) * v[a][p];
		return result;
	}
	private static double kinetic(FluidDomain d, float[][] v) {
		double result = 0; for (int p = 0; p < d.particles.count(); p++) for (int a = 0; a < 3; a++) result += .5 * d.unitScale.kilograms(d.particles.mass(p)) * v[a][p] * (double) v[a][p];
		return result;
	}
	private static double proxyEnergy(FluidDomain d, float[][] v) {
		double result = 0; var w = weights(d);
		for (int a = 0; a < 3; a++) result += d.unitScale.kilograms(FluidDomainStepCoordinator.faceKineticEnergy(v[a], w[a], d.config.density()));
		return result;
	}
	private static float sample(FluidDomain d, int axis, float[] v, int p) {
		float x = d.particles.positionX(p), y = d.particles.positionY(p), z = d.particles.positionZ(p);
		return axis == 0 ? d.sampleX(v, x, y, z) : axis == 1 ? d.sampleY(v, x, y, z) : d.sampleZ(v, x, y, z);
	}
	private static Transfer transfer(boolean full, boolean project) {
		var d = domain(full); var incoming = markers(d); var before = faces(d);
		System.arraycopy(before[0], 0, d.mac.p2gVelocityX, 0, before[0].length);
		System.arraycopy(before[1], 0, d.mac.p2gVelocityY, 0, before[1].length);
		System.arraycopy(before[2], 0, d.mac.p2gVelocityZ, 0, before[2].length);
		d.gridToParticleStage.capturePreProjectionEnergy();
		if (project) { d.pressureStage.solve(.001F); d.pressureStage.projectVelocities(.001F); }
		var projected = faces(d); double divergence = maximumDivergence(d, projected);
		d.gridToParticleStage.extrapolateAndTransfer(); var published = markers(d); var extended = faces(d);
		var residual = new float[][]{incoming[0].clone(), incoming[1].clone(), incoming[2].clone()};
		if (full) {
			var resolved = new ResolvedMarkerVelocity(d.sizeX, d.sizeY, d.sizeZ, d.config.cellSize(), d.geometry.cutCells);
			resolved.project(d.particles, 0, d.mac.velocityValidX, residual[0]);
			resolved.project(d.particles, 1, d.mac.velocityValidY, residual[1]);
			resolved.project(d.particles, 2, d.mac.velocityValidZ, residual[2]);
		}
		double incomingResolvedEnergy = kinetic(d, residual), q = 0, linear = 0, picEnergy = 0, extensionChange = 0;
		float[][] pic = new float[3][d.particles.count()]; var w = weights(d);
		for (int a = 0; a < 3; a++) {
			for (int f = 0; f < w[a].length; f++) if (w[a][f] > 0) extensionChange = Math.max(extensionChange, Math.abs(extended[a][f] - projected[a][f]));
			for (int p = 0; p < d.particles.count(); p++) { pic[a][p] = sample(d, a, extended[a], p); residual[a][p] -= sample(d, a, before[a], p); }
		}
		for (int p = 0; p < d.particles.count(); p++) for (int a = 0; a < 3; a++) {
			double m = d.unitScale.kilograms(d.particles.mass(p)); q += .5 * m * residual[a][p] * (double) residual[a][p];
			linear += m * pic[a][p] * (double) residual[a][p]; picEnergy += .5 * m * pic[a][p] * (double) pic[a][p];
		}
		double budget = Math.min(proxyEnergy(d, before), proxyEnergy(d, extended));
		if (full) budget += incomingResolvedEnergy - proxyEnergy(d, before);
		double alpha = FlipTransferScheme.maximumEnergyStableTransfer(q, linear, picEnergy, Math.max(picEnergy, budget));
		return new Transfer(d, incoming, before, projected, published, pic, residual, alpha, divergence, extensionChange);
	}
	private static double[] divergence(FluidDomain d, float[][] v) {
		double[] result = new double[d.cellCount];
		for (int z = 0; z < d.sizeZ; z++) for (int y = 0; y < d.sizeY; y++) for (int x = 0; x < d.sizeX; x++) {
			int c = d.cellIndex(x, y, z); if (!d.isFluid(c)) continue;
			result[c] = (d.pressureXFaceOpen(x + 1, y, z) * v[0][d.xFaceIndex(x + 1, y, z)] - d.pressureXFaceOpen(x, y, z) * v[0][d.xFaceIndex(x, y, z)]
					+ d.pressureYFaceOpen(x, y + 1, z) * v[1][d.yFaceIndex(x, y + 1, z)] - d.pressureYFaceOpen(x, y, z) * v[1][d.yFaceIndex(x, y, z)]
					+ d.pressureZFaceOpen(x, y, z + 1) * v[2][d.zFaceIndex(x, y, z + 1)] - d.pressureZFaceOpen(x, y, z) * v[2][d.zFaceIndex(x, y, z)]) / d.config.cellSize();
		} return result;
	}
	private static double maximumDivergence(FluidDomain d, float[][] v) { double result = 0; for (double value : divergence(d, v)) result = Math.max(result, Math.abs(value)); return result; }
	private static float[][] scatter(FluidDomain d, float[][] v) {
		var result = faces(d); var weight = faces(d); for (int a = 0; a < 3; a++) { Arrays.fill(result[a], 0); Arrays.fill(weight[a], 0); }
		for (int a = 0; a < 3; a++) {
			for (int p = 0; p < d.particles.count(); p++) d.splatFaceField(result[a], weight[a], a, d.particles.positionX(p) / d.config.cellSize(),
					d.particles.positionY(p) / d.config.cellSize(), d.particles.positionZ(p) / d.config.cellSize(), v[a][p], d.particles.volume(p));
			FluidDomainStepCoordinator.normalize(result[a], weight[a]); var open = d.geometry.cutCells.openFaceField(a);
			for (int f = 0; f < open.length; f++) if (open[f] == 0) result[a][f] = 0;
		} return result;
	}
	private static double[] formalChange(Transfer t) {
		var d = t.domain; double[] result = new double[4]; double h = d.config.cellSize();
		for (int a = 0; a < 3; a++) { int nx = d.sizeX + (a == 0 ? 1 : 0), ny = d.sizeY + (a == 1 ? 1 : 0), nz = d.sizeZ + (a == 2 ? 1 : 0);
			for (int z = 0; z < nz; z++) for (int y = 0; y < ny; y++) for (int x = 0; x < nx; x++) {
				if (a == 0 && (x == 0 || x == d.sizeX) || a == 1 && (y == 0 || y == d.sizeY) || a == 2 && (z == 0 || z == d.sizeZ)) continue;
				int first = d.cellIndex(x - (a == 0 ? 1 : 0), y - (a == 1 ? 1 : 0), z - (a == 2 ? 1 : 0)), second = d.cellIndex(x, y, z);
				float open = d.pressureOpenFaceFromCell(first, second, a == 0 ? 1 : 0, a == 1 ? 1 : 0, a == 2 ? 1 : 0);
				if (open <= 0 || !d.isFluid(first) && !d.isFluid(second)) continue;
				int f = (z * ny + y) * nx + x; double m = 1000 * h * h * h * open / d.pressureFaceCoupling(first, second, a == 0 ? 1 : 0, a == 1 ? 1 : 0, a == 2 ? 1 : 0).mobilityScale();
				result[a] += m * (t.projected[a][f] - t.before[a][f]); result[3] += .5 * m * (t.projected[a][f] * (double) t.projected[a][f] - t.before[a][f] * (double) t.before[a][f]);
			}
		} return result;
	}
	private static void report(boolean full) {
		var t = transfer(full, true); var control = transfer(full, false); var d = t.domain; var formal = formalChange(t);
		if (!Arrays.deepEquals(t.incoming, control.incoming)) throw new AssertionError("Pressure control has different incoming marker velocity");
		double[] actual = momentum(d, t.published), controlP = momentum(d, control.published), picP = new double[3], proxyP = new double[3], alphaP = momentum(d, t.residual);
		float[][] mid = new float[3][d.particles.count()], deltaPic = new float[3][d.particles.count()], deltaAlpha = new float[3][d.particles.count()]; double equationError = 0;
		for (int a = 0; a < 3; a++) { actual[a] -= controlP[a]; alphaP[a] *= t.alpha - control.alpha;
			for (int p = 0; p < d.particles.count(); p++) { deltaPic[a][p] = t.pic[a][p] - control.pic[a][p]; deltaAlpha[a][p] = (float) ((t.alpha - control.alpha) * t.residual[a][p]); mid[a][p] = (float) (t.pic[a][p] + control.alpha * t.residual[a][p]);
				equationError = Math.max(equationError, Math.abs(t.published[a][p] - control.published[a][p] - deltaPic[a][p] - deltaAlpha[a][p])); }
			for (int f = 0; f < t.before[a].length; f++) proxyP[a] += d.unitScale.kilograms(1000 * (double) weights(d)[a][f]) * (t.projected[a][f] - t.before[a][f]);
		} picP = momentum(d, deltaPic);
		System.out.printf("%s markers=%d alphaControl=%.12g alphaPressure=%.12g E_supportedChange=%.12g publicationEquationError=%.12g%n", full ? "full" : "free", d.particles.count(), control.alpha, t.alpha, t.extensionChange, equationError);
		System.out.printf("formalP=%s proxyP=%s PICpressureP=%s alphaPressureP=%s actualPressureP=%s%n", Arrays.toString(Arrays.copyOf(formal, 3)), Arrays.toString(proxyP), Arrays.toString(picP), Arrays.toString(alphaP), Arrays.toString(actual));
		System.out.printf("formalWork=%.12g proxyWork=%.12g markerPressureWork=%.12g fixedAlphaWork=%.12g alphaWork=%.12g D_projected=%.12g D_markerRep2G=%.12g D_APIC=%.12g D_Aalpha=%.12g%n", formal[3], proxyEnergy(d, t.projected) - proxyEnergy(d, t.before), kinetic(d, t.published) - kinetic(d, control.published), kinetic(d, mid) - kinetic(d, control.published), kinetic(d, t.published) - kinetic(d, mid), t.projectedDivergence, maximumDivergence(d, scatter(d, t.published)), maximumDivergence(d, scatter(d, deltaPic)), maximumDivergence(d, scatter(d, deltaAlpha)));
		for (double[] values : new double[][]{actual, formal, proxyP, picP, alphaP,
				{t.alpha, control.alpha, equationError, t.extensionChange, t.projectedDivergence, kinetic(d, t.published), kinetic(d, control.published)}})
			for (double value : values) if (!Double.isFinite(value)) throw new AssertionError("Non-finite experiment observation");
	}
	private static void publicStep() {
		var d = domain(false); var before = momentum(d, markers(d)); var stats = d.stepWithMechanicalAudit(false, 0); var after = momentum(d, markers(d));
		for (int a = 0; a < 3; a++) after[a] -= before[a];
		System.out.printf("publicStep deltaP=%s substeps=%d viscosity=%s transport=%s audit=%s%n", Arrays.toString(after), stats.substeps(), stats.viscosity(), stats.liquidTransport(), stats.mechanicalAudit());
	}
	/** Fixed actual-mass J: independent face flux and compact pressure force reveal first-moment error. */
	private static void markerRotation() {
		var d=domain(false);var j=new MarkerVelocityTransfer();j.capture(d);int n=j.markerCount();
		double[][] load=new double[][]{new double[j.faceCount(0)],new double[j.faceCount(1)],new double[j.faceCount(2)]};
		int[] lower={d.xFaceIndex(1,1,1),d.yFaceIndex(1,1,1),d.zFaceIndex(1,1,1)};
		int[] upper={d.xFaceIndex(2,1,1),d.yFaceIndex(1,2,1),d.zFaceIndex(1,1,2)};
		for(int a=0;a<3;a++){load[a][lower[a]]=-.25;load[a][upper[a]]=.25;}
		var force=new double[3][n];j.transpose(load,force);double[] total=new double[3],torque=new double[3];
		for(int p=0;p<n;p++) {
			double[] r={d.particles.positionX(p)-1,d.particles.positionY(p)-1,d.particles.positionZ(p)-1};
			for(int a=0;a<3;a++){total[a]+=force[a][p];torque[a]+=r[(a+1)%3]*force[(a+2)%3][p]-r[(a+2)%3]*force[(a+1)%3][p];}
		}
		double[] peak=new double[3],compactRate=new double[3];
		for(int rotation=0;rotation<3;rotation++) {
			var velocity=new double[3][n];
			for(int p=0;p<n;p++) {
				double[] r={d.particles.positionX(p)-1,d.particles.positionY(p)-1,d.particles.positionZ(p)-1};
				velocity[(rotation+1)%3][p]=-r[(rotation+2)%3];velocity[(rotation+2)%3][p]=r[(rotation+1)%3];
			}
			var mac=new double[][]{new double[j.faceCount(0)],new double[j.faceCount(1)],new double[j.faceCount(2)]};j.apply(velocity,mac);
			for(int z=0;z<d.sizeZ;z++)for(int y=0;y<d.sizeY;y++)for(int x=0;x<d.sizeX;x++)if(d.isFluid(d.cellIndex(x,y,z))) {
				double flux=.25*(d.pressureXFaceOpen(x+1,y,z)*mac[0][d.xFaceIndex(x+1,y,z)]-d.pressureXFaceOpen(x,y,z)*mac[0][d.xFaceIndex(x,y,z)]
						+d.pressureYFaceOpen(x,y+1,z)*mac[1][d.yFaceIndex(x,y+1,z)]-d.pressureYFaceOpen(x,y,z)*mac[1][d.yFaceIndex(x,y,z)]
						+d.pressureZFaceOpen(x,y,z+1)*mac[2][d.zFaceIndex(x,y,z+1)]-d.pressureZFaceOpen(x,y,z)*mac[2][d.zFaceIndex(x,y,z)]);
				peak[rotation]=Math.max(peak[rotation],Math.abs(flux));if(x==1&&y==1&&z==1)compactRate[rotation]=flux;
			}
			if(Math.abs(compactRate[rotation]-torque[rotation])>3e-14)throw new AssertionError("Rotation virtual work differs from pressure flux");
		}
		for(double[] values:new double[][]{total,torque,peak,compactRate})for(double value:values)if(!Double.isFinite(value))throw new AssertionError("Non-finite rotation observation");
		System.out.printf("fixedMassTransferRotation markers=%d omega=1rad/s center=(1,1,1)m maxVolumeRate=%s m3/s compactPressureCell=21 pressure=1Pa totalForce=%s N torque=%s Nm rotationWork=%s W%n",n,Arrays.toString(peak),Arrays.toString(total),Arrays.toString(torque),Arrays.toString(compactRate));
	}
}
