package com.example.gpuprobe;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.EXTShaderAtomicFloat.VK_EXT_SHADER_ATOMIC_FLOAT_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;

/** Windowless 3D PIC/FLIP probe backend with an explicit device-local production path and host-visible baseline. */
final class VulkanFlipPipeline implements AutoCloseable {
	private static final int LOCAL_SIZE = 256;
	private static final int BUFFER_COUNT = 23;
	private static final int PUSH_BYTES = 52;
	private static final int MG_PRE_SMOOTHING_SWEEPS = 2;
	private static final int MG_COARSE_ITERATIONS = 24;
	private static final int MG_POST_SMOOTHING_SWEEPS = 3;

	private static final int POSITIONS = 0;
	private static final int VELOCITIES = 1;
	private static final int DENSITY = 2;
	private static final int MARKERS = 3;
	private static final int U = 4;
	private static final int U_WEIGHT = 5;
	private static final int U_OLD = 6;
	private static final int V = 7;
	private static final int V_WEIGHT = 8;
	private static final int V_OLD = 9;
	private static final int W = 10;
	private static final int W_WEIGHT = 11;
	private static final int W_OLD = 12;
	private static final int PRESSURE_A = 13;
	private static final int PRESSURE_B = 14;
	private static final int DIVERGENCE = 15;
	private static final int SOLIDS = 16;
	private static final int SNAPSHOT = 17;
	private static final int DIAGNOSTICS = 18;
	private static final int COARSE_RHS = 19;
	private static final int COARSE_MASK = 20;
	private static final int COARSE_PRESSURE_A = 21;
	private static final int COARSE_PRESSURE_B = 22;

	private static final int STAGE_CLEAR = 0;
	private static final int STAGE_P2G = 1;
	private static final int STAGE_GRID_PREP = 2;
	private static final int STAGE_DIVERGENCE = 3;
	private static final int STAGE_JACOBI = 4;
	private static final int STAGE_PROJECT = 5;
	private static final int STAGE_G2P = 6;
	private static final int STAGE_ADVECT = 7;
	private static final int STAGE_SNAPSHOT = 8;
	private static final int STAGE_CLEAR_SURFACE = 9;
	private static final int STAGE_SURFACE_P2G = 10;
	private static final int STAGE_DIAGNOSTICS = 11;
	private static final int STAGE_RBGS = 12;
	private static final int STAGE_PCG_INIT = 13;
	private static final int STAGE_PCG_CLEAR = 14;
	private static final int STAGE_PCG_APPLY = 15;
	private static final int STAGE_PCG_REDUCE = 16;
	private static final int STAGE_PCG_UPDATE_XR = 17;
	private static final int STAGE_PCG_UPDATE_P = 18;
	private static final int STAGE_MG_RESTRICT = 19;
	private static final int STAGE_MG_COARSE_JACOBI = 20;
	private static final int STAGE_MG_PROLONG = 21;

	private static final String SHADER = """
			#version 450
			#extension GL_EXT_shader_atomic_float : require
			layout(local_size_x = 256, local_size_y = 1, local_size_z = 1) in;

			layout(set=0,binding=0,std430) buffer Positions { vec4 value[]; } positions;
			layout(set=0,binding=1,std430) buffer Velocities { vec4 value[]; } velocities;
			layout(set=0,binding=2,std430) buffer Density { float value[]; } density;
			layout(set=0,binding=3,std430) buffer Markers { uint value[]; } markers;
			layout(set=0,binding=4,std430) buffer U { float value[]; } u;
			layout(set=0,binding=5,std430) buffer UWeight { float value[]; } uw;
			layout(set=0,binding=6,std430) buffer UOld { float value[]; } uold;
			layout(set=0,binding=7,std430) buffer V { float value[]; } v;
			layout(set=0,binding=8,std430) buffer VWeight { float value[]; } vw;
			layout(set=0,binding=9,std430) buffer VOld { float value[]; } vold;
			layout(set=0,binding=10,std430) buffer W { float value[]; } w;
			layout(set=0,binding=11,std430) buffer WWeight { float value[]; } ww;
			layout(set=0,binding=12,std430) buffer WOld { float value[]; } wold;
			layout(set=0,binding=13,std430) buffer PressureA { float value[]; } pa;
			layout(set=0,binding=14,std430) buffer PressureB { float value[]; } pb;
			layout(set=0,binding=15,std430) buffer Divergence { float value[]; } divergence;
			layout(set=0,binding=16,std430) readonly buffer Solids { uint value[]; } solids;
			layout(set=0,binding=17,std430) buffer Snapshot { uint value[]; } snapshot;
			layout(set=0,binding=18,std430) buffer Diagnostics { uint value[]; } diagnostics;
			layout(set=0,binding=19,std430) buffer CoarseRhs { float value[]; } coarseRhs;
			layout(set=0,binding=20,std430) buffer CoarseMask { uint value[]; } coarseMask;
			layout(set=0,binding=21,std430) buffer CoarsePressureA { float value[]; } coarsePa;
			layout(set=0,binding=22,std430) buffer CoarsePressureB { float value[]; } coarsePb;
			shared float diagnosticSquared[256];
			shared float diagnosticVolume[256];
			shared float diagnosticWeightedX[256];
			shared uint diagnosticPressure[256];

			layout(push_constant,std430) uniform Params {
			    int stage; int nx; int ny; int nz;
			    int particleCount; int iteration; int pressureIterations; int flags;
			    float dt; float h; float gravity; float rho; int solver;
			} pc;

			int cellCount() { return pc.nx * pc.ny * pc.nz; }
			int coarseNx() { return (pc.nx + 1) / 2; }
			int coarseNy() { return (pc.ny + 1) / 2; }
			int coarseNz() { return (pc.nz + 1) / 2; }
			int coarseCount() { return coarseNx() * coarseNy() * coarseNz(); }
			int uCount() { return (pc.nx + 1) * pc.ny * pc.nz; }
			int vCount() { return pc.nx * (pc.ny + 1) * pc.nz; }
			int wCount() { return pc.nx * pc.ny * (pc.nz + 1); }
			bool insideCell(ivec3 c) { return all(greaterThanEqual(c, ivec3(0))) && all(lessThan(c, ivec3(pc.nx,pc.ny,pc.nz))); }
			int ci(ivec3 c) { return c.x + pc.nx * (c.y + pc.ny * c.z); }
			bool insideCoarse(ivec3 c) { return all(greaterThanEqual(c, ivec3(0))) && all(lessThan(c, ivec3(coarseNx(),coarseNy(),coarseNz()))); }
			int coarseCi(ivec3 c) { return c.x + coarseNx() * (c.y + coarseNy() * c.z); }
			int ui(ivec3 f) { return f.x + (pc.nx + 1) * (f.y + pc.ny * f.z); }
			int vi(ivec3 f) { return f.x + pc.nx * (f.y + (pc.ny + 1) * f.z); }
			int wi(ivec3 f) { return f.x + pc.nx * (f.y + pc.ny * f.z); }
			bool fluid(ivec3 c) { return insideCell(c) && solids.value[ci(c)] == 0u && markers.value[ci(c)] > 0u; }
			bool solid(ivec3 c) { return !insideCell(c) || solids.value[ci(c)] != 0u; }
			bool coarseFluid(ivec3 c) { return insideCoarse(c) && (coarseMask.value[coarseCi(c)] & 1u) != 0u; }
			bool coarseSolid(ivec3 c) { return !insideCoarse(c) || (coarseMask.value[coarseCi(c)] & 2u) != 0u; }
			bool validU(ivec3 f) { return f.x > 0 && f.x < pc.nx && f.y >= 0 && f.y < pc.ny && f.z >= 0 && f.z < pc.nz && !solid(f + ivec3(-1,0,0)) && !solid(f); }
			bool validV(ivec3 f) { return f.y > 0 && f.y < pc.ny && f.x >= 0 && f.x < pc.nx && f.z >= 0 && f.z < pc.nz && !solid(f + ivec3(0,-1,0)) && !solid(f); }
			bool validW(ivec3 f) { return f.z > 0 && f.z < pc.nz && f.x >= 0 && f.x < pc.nx && f.y >= 0 && f.y < pc.ny && !solid(f + ivec3(0,0,-1)) && !solid(f); }

			float triWeight(ivec3 corner, vec3 p) {
			    vec3 d = abs(p - vec3(corner));
			    return max(0.0, 1.0-d.x) * max(0.0, 1.0-d.y) * max(0.0, 1.0-d.z);
			}

			void splatDensity(vec3 q, float volume) {
			    ivec3 base = ivec3(floor(q)); float sum = 0.0;
			    for (int z=0;z<2;z++) for(int y=0;y<2;y++) for(int x=0;x<2;x++) {
			        ivec3 c=base+ivec3(x,y,z); if(insideCell(c) && !solid(c)) sum += triWeight(c,q);
			    }
			    if(sum<=0.0) return;
			    for (int z=0;z<2;z++) for(int y=0;y<2;y++) for(int x=0;x<2;x++) {
			        ivec3 c=base+ivec3(x,y,z); if(insideCell(c) && !solid(c)) atomicAdd(density.value[ci(c)], volume*triWeight(c,q)/sum);
			    }
			}

			void splatU(vec3 q,float velocity) {
			    ivec3 base=ivec3(floor(q)); float sum=0.0;
			    for(int z=0;z<2;z++)for(int y=0;y<2;y++)for(int x=0;x<2;x++){ivec3 f=base+ivec3(x,y,z);if(validU(f))sum+=triWeight(f,q);}
			    if(sum<=0.0)return;
			    for(int z=0;z<2;z++)for(int y=0;y<2;y++)for(int x=0;x<2;x++){ivec3 f=base+ivec3(x,y,z);if(validU(f)){float a=triWeight(f,q)/sum;atomicAdd(u.value[ui(f)],velocity*a);atomicAdd(uw.value[ui(f)],a);}}
			}
			void splatV(vec3 q,float velocity) {
			    ivec3 base=ivec3(floor(q)); float sum=0.0;
			    for(int z=0;z<2;z++)for(int y=0;y<2;y++)for(int x=0;x<2;x++){ivec3 f=base+ivec3(x,y,z);if(validV(f))sum+=triWeight(f,q);}
			    if(sum<=0.0)return;
			    for(int z=0;z<2;z++)for(int y=0;y<2;y++)for(int x=0;x<2;x++){ivec3 f=base+ivec3(x,y,z);if(validV(f)){float a=triWeight(f,q)/sum;atomicAdd(v.value[vi(f)],velocity*a);atomicAdd(vw.value[vi(f)],a);}}
			}
			void splatW(vec3 q,float velocity) {
			    ivec3 base=ivec3(floor(q)); float sum=0.0;
			    for(int z=0;z<2;z++)for(int y=0;y<2;y++)for(int x=0;x<2;x++){ivec3 f=base+ivec3(x,y,z);if(validW(f))sum+=triWeight(f,q);}
			    if(sum<=0.0)return;
			    for(int z=0;z<2;z++)for(int y=0;y<2;y++)for(int x=0;x<2;x++){ivec3 f=base+ivec3(x,y,z);if(validW(f)){float a=triWeight(f,q)/sum;atomicAdd(w.value[wi(f)],velocity*a);atomicAdd(ww.value[wi(f)],a);}}
			}

			float sampleU(vec3 q,bool oldField) { ivec3 b=ivec3(floor(q));float s=0.0,a=0.0;for(int z=0;z<2;z++)for(int y=0;y<2;y++)for(int x=0;x<2;x++){ivec3 f=b+ivec3(x,y,z);if(validU(f)){float k=triWeight(f,q);s+=(oldField?uold.value[ui(f)]:u.value[ui(f)])*k;a+=k;}}return a>0.0?s/a:0.0; }
			float sampleV(vec3 q,bool oldField) { ivec3 b=ivec3(floor(q));float s=0.0,a=0.0;for(int z=0;z<2;z++)for(int y=0;y<2;y++)for(int x=0;x<2;x++){ivec3 f=b+ivec3(x,y,z);if(validV(f)){float k=triWeight(f,q);s+=(oldField?vold.value[vi(f)]:v.value[vi(f)])*k;a+=k;}}return a>0.0?s/a:0.0; }
			float sampleW(vec3 q,bool oldField) { ivec3 b=ivec3(floor(q));float s=0.0,a=0.0;for(int z=0;z<2;z++)for(int y=0;y<2;y++)for(int x=0;x<2;x++){ivec3 f=b+ivec3(x,y,z);if(validW(f)){float k=triWeight(f,q);s+=(oldField?wold.value[wi(f)]:w.value[wi(f)])*k;a+=k;}}return a>0.0?s/a:0.0; }

			float pressureAt(ivec3 c) {
			    if(!fluid(c)) return 0.0;
			    int i=ci(c); if(pc.solver!=0)return pa.value[i];return (pc.pressureIterations & 1)==0 ? pa.value[i] : pb.value[i];
			}
			float matrixDiagonal(ivec3 c) {
			    ivec3 offsets[6]=ivec3[6](ivec3(-1,0,0),ivec3(1,0,0),ivec3(0,-1,0),ivec3(0,1,0),ivec3(0,0,-1),ivec3(0,0,1));float diagonal=0.0;
			    for(int n=0;n<6;n++)if(!solid(c+offsets[n]))diagonal+=1.0;return diagonal;
			}
			void diagnosticAdd(uint index,float amount) {
			    uint previous=diagnostics.value[index];
			    for(;;){uint next=floatBitsToUint(uintBitsToFloat(previous)+amount);uint observed=atomicCompSwap(diagnostics.value[index],previous,next);if(observed==previous)return;previous=observed;}
			}

			void clearStage(uint id) {
			    if(id<uint(cellCount())){density.value[id]=0.0;markers.value[id]=0u;pa.value[id]=0.0;pb.value[id]=0.0;divergence.value[id]=0.0;snapshot.value[id]=0u;}
			    if(id<uint(coarseCount())){coarseRhs.value[id]=0.0;coarseMask.value[id]=0u;coarsePa.value[id]=0.0;coarsePb.value[id]=0.0;}
			    if(id<uint(uCount())){u.value[id]=0.0;uw.value[id]=0.0;uold.value[id]=0.0;}
			    if(id<uint(vCount())){v.value[id]=0.0;vw.value[id]=0.0;vold.value[id]=0.0;}
			    if(id<uint(wCount())){w.value[id]=0.0;ww.value[id]=0.0;wold.value[id]=0.0;}
			    if(id<16u) diagnostics.value[id]=0u;
			}

			void p2gStage(uint id) {
			    if(id>=uint(pc.particleCount))return;
			    vec4 pv=positions.value[id];vec3 q=pv.xyz/pc.h;vec3 vel=velocities.value[id].xyz;
			    ivec3 center=ivec3(clamp(floor(q),vec3(0.0),vec3(pc.nx-1,pc.ny-1,pc.nz-1)));
			    if(!solid(center)) atomicAdd(markers.value[ci(center)],1u);
			    splatDensity(q-vec3(0.5),pv.w);
			    splatU(vec3(q.x,q.y-0.5,q.z-0.5),vel.x);
			    splatV(vec3(q.x-0.5,q.y,q.z-0.5),vel.y);
			    splatW(vec3(q.x-0.5,q.y-0.5,q.z),vel.z);
			}

			void prepStage(uint id) {
			    if(id<uint(uCount())){float value=uw.value[id]>0.0?u.value[id]/uw.value[id]:0.0;uold.value[id]=value;u.value[id]=value;}
			    if(id<uint(vCount())){float value=vw.value[id]>0.0?v.value[id]/vw.value[id]:0.0;vold.value[id]=value;v.value[id]=vw.value[id]>0.0?value-pc.gravity*pc.dt:0.0;}
			    if(id<uint(wCount())){float value=ww.value[id]>0.0?w.value[id]/ww.value[id]:0.0;wold.value[id]=value;w.value[id]=value;}
			}

			void divergenceStage(uint id) {
			    if(id>=uint(cellCount()))return;int ix=int(id)%pc.nx;int yz=int(id)/pc.nx;int iy=yz%pc.ny;int iz=yz/pc.ny;ivec3 c=ivec3(ix,iy,iz);
			    if(!fluid(c)){divergence.value[id]=0.0;return;}
			    float d=(u.value[ui(c+ivec3(1,0,0))]-u.value[ui(c)]+v.value[vi(c+ivec3(0,1,0))]-v.value[vi(c)]+w.value[wi(c+ivec3(0,0,1))]-w.value[wi(c)])/pc.h;
			    divergence.value[id]=d;
			    if(pc.flags==1)atomicMax(diagnostics.value[0],floatBitsToUint(abs(d)));
			    if(pc.flags==2)atomicMax(diagnostics.value[1],floatBitsToUint(abs(d)));
			}

			void jacobiStage(uint id) {
			    if(id>=uint(cellCount()))return;int ix=int(id)%pc.nx;int yz=int(id)/pc.nx;int iy=yz%pc.ny;int iz=yz/pc.ny;ivec3 c=ivec3(ix,iy,iz);
			    bool even=(pc.iteration&1)==0;
			    if(!fluid(c)){if(even)pb.value[id]=0.0;else pa.value[id]=0.0;return;}
			    ivec3 offsets[6]=ivec3[6](ivec3(-1,0,0),ivec3(1,0,0),ivec3(0,-1,0),ivec3(0,1,0),ivec3(0,0,-1),ivec3(0,0,1));
			    float sum=0.0;float diagonal=0.0;
			    for(int n=0;n<6;n++){ivec3 nb=c+offsets[n];if(solid(nb))continue;diagonal+=1.0;if(fluid(nb)){int ni=ci(nb);sum+=even?pa.value[ni]:pb.value[ni];}}
			    float rhs=(pc.rho/pc.dt)*divergence.value[id];float next=diagonal>0.0?(sum-rhs*pc.h*pc.h)/diagonal:0.0;
			    if(even)pb.value[id]=next;else pa.value[id]=next;
			}

			void rbgsStage(uint id) {
			    if(id>=uint(cellCount()))return;int ix=int(id)%pc.nx;int yz=int(id)/pc.nx;int iy=yz%pc.ny;int iz=yz/pc.ny;ivec3 c=ivec3(ix,iy,iz);
			    if(!fluid(c)){pa.value[id]=0.0;return;}if(((ix+iy+iz)&1)!=pc.flags)return;
			    ivec3 offsets[6]=ivec3[6](ivec3(-1,0,0),ivec3(1,0,0),ivec3(0,-1,0),ivec3(0,1,0),ivec3(0,0,-1),ivec3(0,0,1));
			    float sum=0.0;float diagonal=0.0;for(int n=0;n<6;n++){ivec3 nb=c+offsets[n];if(solid(nb))continue;diagonal+=1.0;if(fluid(nb))sum+=pa.value[ci(nb)];}
			    float rhs=(pc.rho/pc.dt)*divergence.value[id];float next=diagonal>0.0?(sum-rhs*pc.h*pc.h)/diagonal:0.0;pa.value[id]=mix(pa.value[id],next,pc.solver==4?1.0:1.65);
			}

			void pcgInitStage(uint id) {
			    if(id>=uint(cellCount()))return;int ix=int(id)%pc.nx;int yz=int(id)/pc.nx;ivec3 c=ivec3(ix,yz%pc.ny,yz/pc.ny);float rhs=fluid(c)?-(pc.rho/pc.dt)*divergence.value[id]*pc.h*pc.h:0.0;float diagonal=pc.solver==3?matrixDiagonal(c):1.0;float z=diagonal>0.0?rhs/diagonal:0.0;pa.value[id]=0.0;pb.value[id]=rhs;density.value[id]=z;uw.value[id]=pc.solver==3?z:rhs;vw.value[id]=0.0;
			}
			void pcgClearStage(uint id) { if(id==0u){if(pc.flags==0){diagnostics.value[8]=0u;diagnostics.value[9]=0u;}diagnostics.value[10]=0u;} }
			void pcgApplyStage(uint id) {
			    if(id>=uint(cellCount()))return;int ix=int(id)%pc.nx;int yz=int(id)/pc.nx;ivec3 c=ivec3(ix,yz%pc.ny,yz/pc.ny);if(!fluid(c)){vw.value[id]=0.0;return;}
			    ivec3 offsets[6]=ivec3[6](ivec3(-1,0,0),ivec3(1,0,0),ivec3(0,-1,0),ivec3(0,1,0),ivec3(0,0,-1),ivec3(0,0,1));float sum=0.0;float diagonal=0.0;
			    for(int n=0;n<6;n++){ivec3 nb=c+offsets[n];if(solid(nb))continue;diagonal+=1.0;if(fluid(nb))sum+=uw.value[ci(nb)];}vw.value[id]=diagonal*uw.value[id]-sum;
			}
			void pcgReduceStage(uint id) {
			    uint local=gl_LocalInvocationID.x;float first=0.0;float second=0.0;if(id<uint(cellCount())){float r=pb.value[id];first=r*(pc.solver==3?density.value[id]:r);if(pc.flags==0)second=uw.value[id]*vw.value[id];}
			    diagnosticSquared[local]=first;diagnosticVolume[local]=second;barrier();for(uint stride=128u;stride>0u;stride>>=1u){if(local<stride){diagnosticSquared[local]+=diagnosticSquared[local+stride];diagnosticVolume[local]+=diagnosticVolume[local+stride];}barrier();}
			    if(local==0u){diagnosticAdd(pc.flags==0?8u:10u,diagnosticSquared[0]);if(pc.flags==0)diagnosticAdd(9u,diagnosticVolume[0]);}
			}
			void pcgUpdateXrStage(uint id) {
			    if(id>=uint(cellCount()))return;float rr=uintBitsToFloat(diagnostics.value[8]);float pap=uintBitsToFloat(diagnostics.value[9]);float alpha=abs(pap)>1e-20?rr/pap:0.0;pa.value[id]+=alpha*uw.value[id];pb.value[id]-=alpha*vw.value[id];
			    if(pc.solver==3){int ix=int(id)%pc.nx;int yz=int(id)/pc.nx;ivec3 c=ivec3(ix,yz%pc.ny,yz/pc.ny);float diagonal=matrixDiagonal(c);density.value[id]=fluid(c)&&diagonal>0.0?pb.value[id]/diagonal:0.0;}
			}
			void pcgUpdatePStage(uint id) {
			    if(id>=uint(cellCount()))return;float oldRr=uintBitsToFloat(diagnostics.value[8]);float newRr=uintBitsToFloat(diagnostics.value[10]);float beta=oldRr>1e-20?newRr/oldRr:0.0;uw.value[id]=(pc.solver==3?density.value[id]:pb.value[id])+beta*uw.value[id];
			}

			void mgRestrictStage(uint id) {
			    if(id>=uint(coarseCount()))return;int ix=int(id)%coarseNx();int yz=int(id)/coarseNx();ivec3 cc=ivec3(ix,yz%coarseNy(),yz/coarseNy());
			    float residualSum=0.0;int childCount=0;bool anyFluid=false;bool allSolid=true;
			    ivec3 offsets[6]=ivec3[6](ivec3(-1,0,0),ivec3(1,0,0),ivec3(0,-1,0),ivec3(0,1,0),ivec3(0,0,-1),ivec3(0,0,1));
			    for(int z=0;z<2;z++)for(int y=0;y<2;y++)for(int x=0;x<2;x++){
			        ivec3 c=cc*2+ivec3(x,y,z);if(!insideCell(c))continue;childCount++;allSolid=allSolid&&solid(c);if(!fluid(c))continue;anyFluid=true;
			        float sum=0.0;float diagonal=0.0;for(int n=0;n<6;n++){ivec3 nb=c+offsets[n];if(solid(nb))continue;diagonal+=1.0;if(fluid(nb))sum+=pa.value[ci(nb)];}
			        float rhs=-(pc.rho/pc.dt)*divergence.value[ci(c)]*pc.h*pc.h;residualSum+=rhs-(diagonal*pa.value[ci(c)]-sum);
			    }
			    coarseMask.value[id]=anyFluid?1u:(allSolid?2u:0u);coarseRhs.value[id]=anyFluid&&childCount>0?4.0*residualSum/float(childCount):0.0;coarsePa.value[id]=0.0;coarsePb.value[id]=0.0;
			}
			void mgCoarseJacobiStage(uint id) {
			    if(id>=uint(coarseCount()))return;int ix=int(id)%coarseNx();int yz=int(id)/coarseNx();ivec3 c=ivec3(ix,yz%coarseNy(),yz/coarseNy());bool even=(pc.iteration&1)==0;
			    if(!coarseFluid(c)){if(even)coarsePb.value[id]=0.0;else coarsePa.value[id]=0.0;return;}
			    ivec3 offsets[6]=ivec3[6](ivec3(-1,0,0),ivec3(1,0,0),ivec3(0,-1,0),ivec3(0,1,0),ivec3(0,0,-1),ivec3(0,0,1));float sum=0.0;float diagonal=0.0;
			    for(int n=0;n<6;n++){ivec3 nb=c+offsets[n];if(coarseSolid(nb))continue;diagonal+=1.0;if(coarseFluid(nb)){int ni=coarseCi(nb);sum+=even?coarsePa.value[ni]:coarsePb.value[ni];}}
			    float next=diagonal>0.0?(sum+coarseRhs.value[id])/diagonal:0.0;if(even)coarsePb.value[id]=next;else coarsePa.value[id]=next;
			}
			float mgCoarsePressure(ivec3 c) {
			    c=clamp(c,ivec3(0),ivec3(coarseNx()-1,coarseNy()-1,coarseNz()-1));if(!coarseFluid(c))return 0.0;return (pc.flags&1)==0?coarsePa.value[coarseCi(c)]:coarsePb.value[coarseCi(c)];
			}
			void mgProlongStage(uint id) {
			    if(id>=uint(cellCount()))return;int ix=int(id)%pc.nx;int yz=int(id)/pc.nx;ivec3 c=ivec3(ix,yz%pc.ny,yz/pc.ny);if(!fluid(c))return;
			    vec3 q=vec3(c)*0.5-vec3(0.25);ivec3 base=ivec3(floor(q));vec3 fraction=fract(q);float correction=0.0;
			    for(int z=0;z<2;z++)for(int y=0;y<2;y++)for(int x=0;x<2;x++){ivec3 o=ivec3(x,y,z);vec3 weight=mix(vec3(1.0)-fraction,fraction,vec3(o));correction+=mgCoarsePressure(base+o)*weight.x*weight.y*weight.z;}
			    pa.value[id]+=correction;
			}

			void projectStage(uint id) {
			    if(id<uint(uCount())){int fx=int(id)%(pc.nx+1);int yz=int(id)/(pc.nx+1);int fy=yz%pc.ny;int fz=yz/pc.ny;ivec3 f=ivec3(fx,fy,fz);if(!validU(f))u.value[id]=0.0;else{ivec3 l=f+ivec3(-1,0,0),r=f;if(fluid(l)||fluid(r))u.value[id]-=(pc.dt/pc.rho)*(pressureAt(r)-pressureAt(l))/pc.h;}}
			    if(id<uint(vCount())){int fx=int(id)%pc.nx;int yz=int(id)/pc.nx;int fy=yz%(pc.ny+1);int fz=yz/(pc.ny+1);ivec3 f=ivec3(fx,fy,fz);if(!validV(f))v.value[id]=0.0;else{ivec3 b=f+ivec3(0,-1,0),t=f;if(fluid(b)||fluid(t))v.value[id]-=(pc.dt/pc.rho)*(pressureAt(t)-pressureAt(b))/pc.h;}}
			    if(id<uint(wCount())){int fx=int(id)%pc.nx;int yz=int(id)/pc.nx;int fy=yz%pc.ny;int fz=yz/pc.ny;ivec3 f=ivec3(fx,fy,fz);if(!validW(f))w.value[id]=0.0;else{ivec3 b=f+ivec3(0,0,-1),t=f;if(fluid(b)||fluid(t))w.value[id]-=(pc.dt/pc.rho)*(pressureAt(t)-pressureAt(b))/pc.h;}}
			}

			void g2pStage(uint id) {
			    if(id>=uint(pc.particleCount))return;vec3 q=positions.value[id].xyz/pc.h;vec3 oldv=velocities.value[id].xyz;
			    vec3 pic=vec3(sampleU(vec3(q.x,q.y-0.5,q.z-0.5),false),sampleV(vec3(q.x-0.5,q.y,q.z-0.5),false),sampleW(vec3(q.x-0.5,q.y-0.5,q.z),false));
			    vec3 before=vec3(sampleU(vec3(q.x,q.y-0.5,q.z-0.5),true),sampleV(vec3(q.x-0.5,q.y,q.z-0.5),true),sampleW(vec3(q.x-0.5,q.y-0.5,q.z),true));
			    vec3 flip=oldv+(pic-before);velocities.value[id]=vec4(mix(pic,flip,0.90),0.0);
			}

			void advectStage(uint id) {
			    if(id>=uint(pc.particleCount))return;vec3 p=positions.value[id].xyz;vec3 vel=velocities.value[id].xyz;vec3 limit=vec3(pc.nx,pc.ny,pc.nz)*pc.h;
			    float nx=clamp(p.x+vel.x*pc.dt,0.05*pc.h,limit.x-0.05*pc.h);ivec3 cx=ivec3(floor(vec3(nx,p.y,p.z)/pc.h));if(solid(cx))vel.x=0.0;else p.x=nx;
			    float ny=clamp(p.y+vel.y*pc.dt,0.05*pc.h,limit.y-0.05*pc.h);ivec3 cy=ivec3(floor(vec3(p.x,ny,p.z)/pc.h));if(solid(cy))vel.y=0.0;else p.y=ny;
			    float nz=clamp(p.z+vel.z*pc.dt,0.05*pc.h,limit.z-0.05*pc.h);ivec3 cz=ivec3(floor(vec3(p.x,p.y,nz)/pc.h));if(solid(cz))vel.z=0.0;else p.z=nz;
			    if(p.x<=0.051*pc.h||p.x>=limit.x-0.051*pc.h)vel.x=0.0;if(p.y<=0.051*pc.h||p.y>=limit.y-0.051*pc.h)vel.y=0.0;if(p.z<=0.051*pc.h||p.z>=limit.z-0.051*pc.h)vel.z=0.0;
			    positions.value[id].xyz=p;velocities.value[id].xyz=vel;
			}

			void snapshotStage(uint id) {
			    if(id>=uint(cellCount()))return;uint d=uint(clamp(density.value[id],0.0,1.0)*255.0+0.5);uint m=pc.flags==1?(snapshot.value[id]&(1u<<8)):(markers.value[id]>0u?(1u<<8):0u);uint s=solids.value[id]>0u?(1u<<9):0u;snapshot.value[id]=d|m|s;
			}
			void clearSurfaceStage(uint id) { if(id<uint(cellCount())){density.value[id]=0.0;snapshot.value[id]=0u;} }
			void surfaceP2gStage(uint id) {
			    if(id>=uint(pc.particleCount))return;vec4 pv=positions.value[id];vec3 q=pv.xyz/pc.h;
			    ivec3 center=ivec3(clamp(floor(q),vec3(0.0),vec3(pc.nx-1,pc.ny-1,pc.nz-1)));
			    if(!solid(center))atomicOr(snapshot.value[ci(center)],1u<<8);splatDensity(q-vec3(0.5),pv.w);
			}
			void diagnosticsStage(uint id) {
			    uint local=gl_LocalInvocationID.x;float squared=0.0;float volume=0.0;float weightedX=0.0;uint pressureBits=0u;
			    if(id<uint(cellCount())){ivec3 c=ivec3(int(id)%pc.nx,(int(id)/pc.nx)%pc.ny,int(id)/(pc.nx*pc.ny));float d=divergence.value[id];squared=d*d;if(fluid(c))pressureBits=floatBitsToUint(abs(pressureAt(c)));}
			    if(id<uint(pc.particleCount)){vec4 pv=positions.value[id];volume=pv.w;weightedX=(pv.x/pc.h)*pv.w;}
			    diagnosticSquared[local]=squared;diagnosticVolume[local]=volume;diagnosticWeightedX[local]=weightedX;diagnosticPressure[local]=pressureBits;barrier();
			    for(uint stride=128u;stride>0u;stride>>=1u){if(local<stride){diagnosticSquared[local]+=diagnosticSquared[local+stride];diagnosticVolume[local]+=diagnosticVolume[local+stride];diagnosticWeightedX[local]+=diagnosticWeightedX[local+stride];diagnosticPressure[local]=max(diagnosticPressure[local],diagnosticPressure[local+stride]);}barrier();}
			    if(local==0u){diagnosticAdd(3,diagnosticSquared[0]);diagnosticAdd(4,diagnosticVolume[0]);diagnosticAdd(5,diagnosticWeightedX[0]);atomicMax(diagnostics.value[2],diagnosticPressure[0]);}
			}

			void main(){uint id=gl_GlobalInvocationID.x;if(pc.stage==0)clearStage(id);else if(pc.stage==1)p2gStage(id);else if(pc.stage==2)prepStage(id);else if(pc.stage==3)divergenceStage(id);else if(pc.stage==4)jacobiStage(id);else if(pc.stage==5)projectStage(id);else if(pc.stage==6)g2pStage(id);else if(pc.stage==7)advectStage(id);else if(pc.stage==8)snapshotStage(id);else if(pc.stage==9)clearSurfaceStage(id);else if(pc.stage==10)surfaceP2gStage(id);else if(pc.stage==11)diagnosticsStage(id);else if(pc.stage==12)rbgsStage(id);else if(pc.stage==13)pcgInitStage(id);else if(pc.stage==14)pcgClearStage(id);else if(pc.stage==15)pcgApplyStage(id);else if(pc.stage==16)pcgReduceStage(id);else if(pc.stage==17)pcgUpdateXrStage(id);else if(pc.stage==18)pcgUpdatePStage(id);else if(pc.stage==19)mgRestrictStage(id);else if(pc.stage==20)mgCoarseJacobiStage(id);else if(pc.stage==21)mgProlongStage(id);}
			""";

	private final Config config;
	private final MemoryMode memoryMode;
	private final PressureSolver pressureSolver;
	private final List<GpuBuffer> buffers = new ArrayList<>(BUFFER_COUNT);
	private GpuBuffer transferBuffer;
	private VkInstance instance;
	private VkPhysicalDevice physicalDevice;
	private VkDevice device;
	private VkQueue queue;
	private int queueFamily;
	private String deviceName;
	private long descriptorLayout;
	private long pipelineLayout;
	private long shaderModule;
	private long pipeline;
	private long descriptorPool;
	private long descriptorSet;
	private long commandPool;
	private VkCommandBuffer commandBuffer;
	private int particleCount;
	private float particleVolumeLedger;
	private Diagnostics lastDiagnostics = new Diagnostics(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, Float.NaN);
	private long lastStepNanos;

	VulkanFlipPipeline(Config config, int deviceIndex) {
		this(config, deviceIndex, MemoryMode.DEVICE_LOCAL_STAGING, PressureSolver.JACOBI);
	}

	VulkanFlipPipeline(Config config, int deviceIndex, MemoryMode memoryMode) {
		this(config, deviceIndex, memoryMode, PressureSolver.JACOBI);
	}

	VulkanFlipPipeline(Config config, int deviceIndex, MemoryMode memoryMode, PressureSolver pressureSolver) {
		this.config = config;
		this.memoryMode = memoryMode;
		this.pressureSolver = pressureSolver;
		try {
			createInstanceAndDevice(deviceIndex);
			allocateBuffers();
			createPipelineAndDescriptors();
		} catch (RuntimeException | LinkageError exception) {
			close();
			throw exception;
		}
	}

	Config config() { return config; }
	String deviceName() { return deviceName; }
	MemoryMode memoryMode() { return memoryMode; }
	PressureSolver pressureSolver() { return pressureSolver; }
	int particleCount() { return particleCount; }
	double lastStepMilliseconds() { return lastStepNanos / 1_000_000.0; }
	float totalParticleVolume() { return particleVolumeLedger; }

	void initializeDamBreak() {
		initialize((x, y, z) -> x < config.nx() * 2 / 5 && y < config.ny() * 3 / 4);
	}

	void initializeColumn() {
		initialize((x, y, z) -> x >= config.nx() / 3 && x < config.nx() * 2 / 3
				&& z >= config.nz() / 3 && z < config.nz() * 2 / 3 && y < config.ny() * 3 / 4);
	}

	void initializeFull() {
		initialize((x, y, z) -> true);
	}

	private void initialize(CellSelector selector) {
		GpuBuffer upload = memoryMode == MemoryMode.HOST_VISIBLE ? buffer(POSITIONS) : transferBuffer;
		MemoryUtil.memSet(upload.mappedAddress(), 0, buffer(POSITIONS).size());
		FloatBuffer positions = upload.floats(buffer(POSITIONS).size());
		if (memoryMode == MemoryMode.HOST_VISIBLE) {
			MemoryUtil.memSet(buffer(VELOCITIES).mappedAddress(), 0, buffer(VELOCITIES).size());
			MemoryUtil.memSet(buffer(SOLIDS).mappedAddress(), 0, buffer(SOLIDS).size());
		}
		particleCount = 0;
		for (int z = 0; z < config.nz(); z++) for (int y = 0; y < config.ny(); y++) for (int x = 0; x < config.nx(); x++) {
			if (!selector.filled(x, y, z)) continue;
			for (int slot = 0; slot < 8; slot++) {
				if (particleCount >= config.maxParticles()) throw new IllegalStateException("GPU marker capacity exceeded");
				float ox = (slot & 1) == 0 ? 0.25F : 0.75F;
				float oy = (slot & 2) == 0 ? 0.25F : 0.75F;
				float oz = (slot & 4) == 0 ? 0.25F : 0.75F;
				if (x == 0) ox = Math.max(ox, 0.45F); if (x + 1 == config.nx()) ox = Math.min(ox, 0.55F);
				if (y == 0) oy = Math.max(oy, 0.45F); if (y + 1 == config.ny()) oy = Math.min(oy, 0.55F);
				if (z == 0) oz = Math.max(oz, 0.45F); if (z + 1 == config.nz()) oz = Math.min(oz, 0.55F);
				int base = particleCount * 4;
				positions.put(base, (x + ox) * config.cellSize());
				positions.put(base + 1, (y + oy) * config.cellSize());
				positions.put(base + 2, (z + oz) * config.cellSize());
				positions.put(base + 3, 0.125F);
				particleCount++;
			}
		}
		particleVolumeLedger = particleCount * 0.125F;
		if (memoryMode == MemoryMode.DEVICE_LOCAL_STAGING) uploadInitialState();
		rebuildSnapshot();
	}

	void rebuildSnapshot() {
		recordAndSubmit(false);
	}

	void step() {
		long started = System.nanoTime();
		recordAndSubmit(true);
		lastStepNanos = System.nanoTime() - started;
	}

	SnapshotData snapshot() {
		IntBuffer packed = readableBuffer(SNAPSHOT).order(ByteOrder.nativeOrder()).asIntBuffer();
		byte[] density = new byte[config.cellCount()];
		byte[] markers = new byte[config.cellCount()];
		byte[] solids = new byte[config.cellCount()];
		for (int i = 0; i < config.cellCount(); i++) {
			int value = packed.get(i);
			density[i] = (byte) (value & 0xff);
			markers[i] = (byte) ((value >>> 8) & 1);
			solids[i] = (byte) ((value >>> 9) & 1);
		}
		return new SnapshotData(density, markers, solids);
	}

	int markerCellCountFromParticles() {
		FloatBuffer positions = copiedBuffer(POSITIONS).asFloatBuffer();
		boolean[] occupied = new boolean[config.cellCount()];
		int count = 0;
		for (int i = 0; i < particleCount; i++) {
			int base = i * 4;
			int x = Math.clamp((int) Math.floor(positions.get(base) / config.cellSize()), 0, config.nx() - 1);
			int y = Math.clamp((int) Math.floor(positions.get(base + 1) / config.cellSize()), 0, config.ny() - 1);
			int z = Math.clamp((int) Math.floor(positions.get(base + 2) / config.cellSize()), 0, config.nz() - 1);
			int cell = x + config.nx() * (y + config.ny() * z);
			if (!occupied[cell]) { occupied[cell] = true; count++; }
		}
		return count;
	}

	float reconstructedDensityVolume() {
		FloatBuffer density = copiedBuffer(DENSITY).asFloatBuffer();
		double total = 0.0;
		for (int i = 0; i < config.cellCount(); i++) total += density.get(i);
		return (float) total;
	}

	Diagnostics diagnostics() { return lastDiagnostics; }

	PressureProfile pressureProfile() {
		int pressureIndex = pressureSolver == PressureSolver.JACOBI && (config.pressureIterations() & 1) != 0 ? PRESSURE_B : PRESSURE_A;
		FloatBuffer pressure = copiedBuffer(pressureIndex).asFloatBuffer();
		IntBuffer markers = copiedBuffer(MARKERS).asIntBuffer();
		double[] sums = new double[config.ny()];
		int[] counts = new int[config.ny()];
		for (int z = 0; z < config.nz(); z++) for (int y = 0; y < config.ny(); y++) for (int x = 0; x < config.nx(); x++) {
			int index = x + config.nx() * (y + config.ny() * z);
			if (markers.get(index) == 0) continue;
			sums[y] += pressure.get(index);
			counts[y]++;
		}
		float[] averages = new float[config.ny()];
		for (int y = 0; y < config.ny(); y++) averages[y] = counts[y] == 0 ? Float.NaN : (float) (sums[y] / counts[y]);
		return new PressureProfile(averages, counts);
	}

	ReadbackStats measureReadback() {
		long fullStarted = System.nanoTime();
		long fullChecksum = 0L;
		long fullBytes = 0L;
		for (int i = 0; i < buffers.size(); i++) {
			GpuBuffer current = buffer(i);
			fullChecksum ^= Long.rotateLeft(checksum(readableBuffer(i), current.size()), i * 7);
			fullBytes += current.size();
		}
		long fullNanos = System.nanoTime() - fullStarted;
		long compactStarted = System.nanoTime();
		long compactChecksum = checksum(readableBuffer(SNAPSHOT), buffer(SNAPSHOT).size());
		long compactNanos = System.nanoTime() - compactStarted;
		return new ReadbackStats(fullBytes, buffer(SNAPSHOT).size(), fullNanos, compactNanos, fullChecksum, compactChecksum);
	}

	private long checksum(ByteBuffer bytes, long byteCount) {
		long checksum = 0xcbf29ce484222325L;
		for (int i = 0; i < byteCount; i += 4) checksum = (checksum ^ Integer.toUnsignedLong(bytes.getInt(i))) * 0x100000001b3L;
		return checksum;
	}

	private void recordAndSubmit(boolean fullStep) {
		try (MemoryStack stack = stackPush()) {
			check(vkResetCommandPool(device, commandPool, 0), "vkResetCommandPool");
			VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack).sType$Default();
			check(vkBeginCommandBuffer(commandBuffer, begin), "vkBeginCommandBuffer");
			vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
			vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(descriptorSet), null);
			VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
			barrier.get(0).sType$Default().srcAccessMask(VK_ACCESS_HOST_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT)
					.dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
			vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_HOST_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
					VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, barrier, null, null);
			ByteBuffer push = stack.malloc(PUSH_BYTES);
			dispatch(push, STAGE_CLEAR, 0, maxGridCount());
			computeBarrier(barrier);
			dispatch(push, STAGE_P2G, 0, particleCount);
			computeBarrier(barrier);
			if (fullStep) {
				dispatch(push, STAGE_GRID_PREP, 0, maxFaceCount());
				computeBarrier(barrier);
				dispatch(push, STAGE_DIVERGENCE, 0, 1, config.cellCount());
				computeBarrier(barrier);
				solvePressure(push, barrier);
				dispatch(push, STAGE_PROJECT, 0, maxFaceCount());
				computeBarrier(barrier);
				// Diagnostics must observe the projected field, not the pressure RHS.
				dispatch(push, STAGE_DIVERGENCE, 0, 2, config.cellCount());
				computeBarrier(barrier);
				dispatch(push, STAGE_G2P, 0, particleCount);
				computeBarrier(barrier);
				dispatch(push, STAGE_ADVECT, 0, particleCount);
				computeBarrier(barrier);
				// The network surface must represent post-advection particles, while pressure markers above
				// remain the exact occupancy used by this pressure solve for diagnostics.
				dispatch(push, STAGE_CLEAR_SURFACE, 0, config.cellCount());
				computeBarrier(barrier);
				dispatch(push, STAGE_SURFACE_P2G, 0, particleCount);
				computeBarrier(barrier);
			}
			dispatch(push, STAGE_SNAPSHOT, 0, fullStep ? 1 : 0, config.cellCount());
			computeBarrier(barrier);
			dispatch(push, STAGE_DIAGNOSTICS, 0, Math.max(config.cellCount(), particleCount));
			if (memoryMode == MemoryMode.DEVICE_LOCAL_STAGING) {
				barrier.get(0).srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
				vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, barrier, null, null);
				copyBuffer(stack, buffer(DIAGNOSTICS), transferBuffer, buffer(DIAGNOSTICS).size());
				barrier.get(0).srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
				vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, barrier, null, null);
			} else {
				barrier.get(0).srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
				vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, barrier, null, null);
			}
			check(vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer");
			VkSubmitInfo.Buffer submit = VkSubmitInfo.calloc(1, stack);
			submit.get(0).sType$Default().pCommandBuffers(stack.pointers(commandBuffer));
			check(vkQueueSubmit(queue, submit, VK_NULL_HANDLE), "vkQueueSubmit");
			check(vkQueueWaitIdle(queue), "vkQueueWaitIdle");
			updateCachedDiagnostics(memoryMode == MemoryMode.DEVICE_LOCAL_STAGING ? transferBuffer.bytes(buffer(DIAGNOSTICS).size()) : buffer(DIAGNOSTICS).bytes());
		}
	}

	private void solvePressure(ByteBuffer push, VkMemoryBarrier.Buffer barrier) {
		switch (pressureSolver) {
			case JACOBI -> {
				for (int iteration = 0; iteration < config.pressureIterations(); iteration++) {
					dispatch(push, STAGE_JACOBI, iteration, config.cellCount());
					computeBarrier(barrier);
				}
			}
			case RBGS_SOR -> {
				for (int iteration = 0; iteration < config.pressureIterations(); iteration++) {
					dispatch(push, STAGE_RBGS, iteration, 0, config.cellCount());
					computeBarrier(barrier);
					dispatch(push, STAGE_RBGS, iteration, 1, config.cellCount());
					computeBarrier(barrier);
				}
			}
			case PCG, PCG_DIAGONAL -> {
				dispatch(push, STAGE_PCG_INIT, 0, config.cellCount());
				computeBarrier(barrier);
				for (int iteration = 0; iteration < config.pressureIterations(); iteration++) {
					dispatch(push, STAGE_PCG_CLEAR, iteration, 0, 1);
					computeBarrier(barrier);
					dispatch(push, STAGE_PCG_APPLY, iteration, config.cellCount());
					computeBarrier(barrier);
					dispatch(push, STAGE_PCG_REDUCE, iteration, 0, config.cellCount());
					computeBarrier(barrier);
					dispatch(push, STAGE_PCG_UPDATE_XR, iteration, config.cellCount());
					computeBarrier(barrier);
					dispatch(push, STAGE_PCG_CLEAR, iteration, 1, 1);
					computeBarrier(barrier);
					dispatch(push, STAGE_PCG_REDUCE, iteration, 1, config.cellCount());
					computeBarrier(barrier);
					dispatch(push, STAGE_PCG_UPDATE_P, iteration, config.cellCount());
					computeBarrier(barrier);
				}
			}
			case MULTIGRID_TWO_LEVEL -> {
				for (int cycle = 0; cycle < config.pressureIterations(); cycle++) {
					for (int sweep = 0; sweep < MG_PRE_SMOOTHING_SWEEPS; sweep++) smoothFineGrid(push, barrier, sweep);
					dispatch(push, STAGE_MG_RESTRICT, cycle, coarseCellCount());
					computeBarrier(barrier);
					for (int iteration = 0; iteration < MG_COARSE_ITERATIONS; iteration++) {
						dispatch(push, STAGE_MG_COARSE_JACOBI, iteration, coarseCellCount());
						computeBarrier(barrier);
					}
					dispatch(push, STAGE_MG_PROLONG, cycle, MG_COARSE_ITERATIONS & 1, config.cellCount());
					computeBarrier(barrier);
					for (int sweep = 0; sweep < MG_POST_SMOOTHING_SWEEPS; sweep++) smoothFineGrid(push, barrier, sweep);
				}
			}
		}
	}

	private void smoothFineGrid(ByteBuffer push, VkMemoryBarrier.Buffer barrier, int sweep) {
		dispatch(push, STAGE_RBGS, sweep, 0, config.cellCount());
		computeBarrier(barrier);
		dispatch(push, STAGE_RBGS, sweep, 1, config.cellCount());
		computeBarrier(barrier);
	}

	private void uploadInitialState() {
		try (MemoryStack stack = stackPush()) {
			check(vkResetCommandPool(device, commandPool, 0), "vkResetCommandPool(upload)");
			VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack).sType$Default();
			check(vkBeginCommandBuffer(commandBuffer, begin), "vkBeginCommandBuffer(upload)");
			VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
			barrier.get(0).sType$Default().srcAccessMask(VK_ACCESS_HOST_WRITE_BIT).dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
			vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_HOST_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, barrier, null, null);
			copyBuffer(stack, transferBuffer, buffer(POSITIONS), buffer(POSITIONS).size());
			vkCmdFillBuffer(commandBuffer, buffer(VELOCITIES).handle(), 0, buffer(VELOCITIES).size(), 0);
			vkCmdFillBuffer(commandBuffer, buffer(SOLIDS).handle(), 0, buffer(SOLIDS).size(), 0);
			barrier.get(0).srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
			vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, barrier, null, null);
			submitAndWait(stack, "upload");
		}
	}

	private ByteBuffer readableBuffer(int index) {
		GpuBuffer source = buffer(index);
		if (memoryMode == MemoryMode.HOST_VISIBLE) return source.bytes();
		copyDeviceBufferToHost(source);
		return transferBuffer.bytes(source.size());
	}

	private ByteBuffer copiedBuffer(int index) {
		GpuBuffer source = buffer(index);
		ByteBuffer readable = readableBuffer(index).duplicate().order(ByteOrder.nativeOrder());
		readable.position(0).limit(Math.toIntExact(source.size()));
		ByteBuffer copy = ByteBuffer.allocate(Math.toIntExact(source.size())).order(ByteOrder.nativeOrder());
		copy.put(readable).flip();
		return copy;
	}

	private void copyDeviceBufferToHost(GpuBuffer source) {
		if (source.size() > transferBuffer.size()) throw new IllegalArgumentException("Transfer buffer too small");
		try (MemoryStack stack = stackPush()) {
			check(vkResetCommandPool(device, commandPool, 0), "vkResetCommandPool(readback)");
			VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack).sType$Default();
			check(vkBeginCommandBuffer(commandBuffer, begin), "vkBeginCommandBuffer(readback)");
			VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
			barrier.get(0).sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
			vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, barrier, null, null);
			copyBuffer(stack, source, transferBuffer, source.size());
			barrier.get(0).srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
			vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, barrier, null, null);
			submitAndWait(stack, "readback");
		}
	}

	private void copyBuffer(MemoryStack stack, GpuBuffer source, GpuBuffer destination, long bytes) {
		VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
		region.get(0).srcOffset(0).dstOffset(0).size(bytes);
		vkCmdCopyBuffer(commandBuffer, source.handle(), destination.handle(), region);
	}

	private void submitAndWait(MemoryStack stack, String label) {
		check(vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer(" + label + ")");
		VkSubmitInfo.Buffer submit = VkSubmitInfo.calloc(1, stack);
		submit.get(0).sType$Default().pCommandBuffers(stack.pointers(commandBuffer));
		check(vkQueueSubmit(queue, submit, VK_NULL_HANDLE), "vkQueueSubmit(" + label + ")");
		check(vkQueueWaitIdle(queue), "vkQueueWaitIdle(" + label + ")");
	}

	private void updateCachedDiagnostics(ByteBuffer bytes) {
		IntBuffer diagnostics = bytes.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer();
		float pre = Float.intBitsToFloat(diagnostics.get(0));
		float post = Float.intBitsToFloat(diagnostics.get(1));
		float maxPressure = Float.intBitsToFloat(diagnostics.get(2));
		float sumSquared = Float.intBitsToFloat(diagnostics.get(3));
		float volume = Float.intBitsToFloat(diagnostics.get(4));
		float weightedX = Float.intBitsToFloat(diagnostics.get(5));
		float rms = (float) Math.sqrt(sumSquared / config.cellCount());
		float centerX = volume == 0.0F ? Float.NaN : weightedX / volume;
		if (!Float.isFinite(pre) || !Float.isFinite(post) || !Float.isFinite(maxPressure)
				|| !Float.isFinite(rms) || (particleCount > 0 && (!Float.isFinite(volume) || !Float.isFinite(centerX)))) {
			throw new IllegalStateException("Non-finite compact GPU diagnostics");
		}
		lastDiagnostics = new Diagnostics(pre, post, rms, maxPressure, volume, centerX);
	}

	private void dispatch(ByteBuffer push, int stage, int iteration, int workItems) {
		dispatch(push, stage, iteration, 0, workItems);
	}

	private void dispatch(ByteBuffer push, int stage, int iteration, int flags, int workItems) {
		push.clear();
		push.putInt(stage).putInt(config.nx()).putInt(config.ny()).putInt(config.nz());
		push.putInt(particleCount).putInt(iteration).putInt(config.pressureIterations()).putInt(flags);
		push.putFloat(config.timeStep()).putFloat(config.cellSize()).putFloat(config.gravity()).putFloat(config.density());
		push.putInt(pressureSolver.code());
		push.flip();
		vkCmdPushConstants(commandBuffer, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
		vkCmdDispatch(commandBuffer, groups(workItems), 1, 1);
	}

	private void computeBarrier(VkMemoryBarrier.Buffer barrier) {
		barrier.get(0).srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
		vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, barrier, null, null);
	}

	private static int groups(int workItems) { return Math.max(1, (workItems + LOCAL_SIZE - 1) / LOCAL_SIZE); }
	private int maxFaceCount() { return Math.max(config.uCount(), Math.max(config.vCount(), config.wCount())); }
	private int maxGridCount() { return Math.max(config.cellCount(), maxFaceCount()); }
	private int coarseCellCount() {
		return Math.multiplyExact(Math.multiplyExact((config.nx() + 1) / 2, (config.ny() + 1) / 2), (config.nz() + 1) / 2);
	}

	private void createInstanceAndDevice(int deviceIndex) {
		try (MemoryStack stack = stackPush()) {
			VkApplicationInfo app = VkApplicationInfo.calloc(stack).sType$Default()
					.pApplicationName(stack.UTF8("Real Water 3D FLIP Probe")).applicationVersion(1)
					.pEngineName(stack.UTF8("Real Water")).engineVersion(1).apiVersion(VK_MAKE_API_VERSION(0,1,1,0));
			VkInstanceCreateInfo instanceInfo = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(app);
			PointerBuffer instancePointer = stack.mallocPointer(1);
			check(vkCreateInstance(instanceInfo, null, instancePointer), "vkCreateInstance");
			instance = new VkInstance(instancePointer.get(0), instanceInfo);
			IntBuffer count = stack.ints(0);
			check(vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices(count)");
			if (deviceIndex < 0 || deviceIndex >= count.get(0)) throw new IllegalArgumentException("GPU index " + deviceIndex + " not in [0," + count.get(0) + ")");
			PointerBuffer physicalPointers = stack.mallocPointer(count.get(0));
			check(vkEnumeratePhysicalDevices(instance, count, physicalPointers), "vkEnumeratePhysicalDevices(list)");
			physicalDevice = new VkPhysicalDevice(physicalPointers.get(deviceIndex), instance);
			VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
			vkGetPhysicalDeviceProperties(physicalDevice, properties);
			deviceName = properties.deviceNameString();
			queueFamily = findComputeQueue(physicalDevice, stack);

			VkPhysicalDeviceShaderAtomicFloatFeaturesEXT atomicFeatures = VkPhysicalDeviceShaderAtomicFloatFeaturesEXT.calloc(stack).sType$Default();
			VkPhysicalDeviceFeatures2 features = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(atomicFeatures.address());
			vkGetPhysicalDeviceFeatures2(physicalDevice, features);
			if (!atomicFeatures.shaderBufferFloat32AtomicAdd()) throw new IllegalStateException(deviceName + " lacks shaderBufferFloat32AtomicAdd");
			atomicFeatures.shaderBufferFloat32AtomicAdd(true).shaderBufferFloat32Atomics(true);
			VkDeviceQueueCreateInfo.Buffer queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack);
			queueInfo.get(0).sType$Default().queueFamilyIndex(queueFamily).pQueuePriorities(stack.floats(1.0F));
			VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.calloc(stack).sType$Default().pNext(atomicFeatures.address())
					.pQueueCreateInfos(queueInfo).ppEnabledExtensionNames(stack.pointers(stack.UTF8(VK_EXT_SHADER_ATOMIC_FLOAT_EXTENSION_NAME)));
			PointerBuffer devicePointer = stack.mallocPointer(1);
			check(vkCreateDevice(physicalDevice, deviceInfo, null, devicePointer), "vkCreateDevice");
			device = new VkDevice(devicePointer.get(0), physicalDevice, deviceInfo);
			PointerBuffer queuePointer = stack.mallocPointer(1); vkGetDeviceQueue(device, queueFamily, 0, queuePointer);
			queue = new VkQueue(queuePointer.get(0), device);
		}
	}

	private void allocateBuffers() {
		long particleBytes = Math.multiplyExact((long) config.maxParticles(), 4L * Float.BYTES);
		addBuffer(particleBytes); addBuffer(particleBytes);
		addBuffer((long) config.cellCount() * Float.BYTES); addBuffer((long) config.cellCount() * Integer.BYTES);
		addBuffer((long) config.uCount() * Float.BYTES); addBuffer((long) config.uCount() * Float.BYTES); addBuffer((long) config.uCount() * Float.BYTES);
		addBuffer((long) config.vCount() * Float.BYTES); addBuffer((long) config.vCount() * Float.BYTES); addBuffer((long) config.vCount() * Float.BYTES);
		addBuffer((long) config.wCount() * Float.BYTES); addBuffer((long) config.wCount() * Float.BYTES); addBuffer((long) config.wCount() * Float.BYTES);
		addBuffer((long) config.cellCount() * Float.BYTES); addBuffer((long) config.cellCount() * Float.BYTES);
		addBuffer((long) config.cellCount() * Float.BYTES); addBuffer((long) config.cellCount() * Integer.BYTES);
		addBuffer((long) config.cellCount() * Integer.BYTES); addBuffer(16L * Integer.BYTES);
		addBuffer((long) coarseCellCount() * Float.BYTES); addBuffer((long) coarseCellCount() * Integer.BYTES);
		addBuffer((long) coarseCellCount() * Float.BYTES); addBuffer((long) coarseCellCount() * Float.BYTES);
		if (buffers.size() != BUFFER_COUNT) throw new IllegalStateException("Wrong GPU buffer count");
		if (memoryMode == MemoryMode.DEVICE_LOCAL_STAGING) {
			long transferBytes = 0L;
			for (GpuBuffer buffer : buffers) transferBytes = Math.max(transferBytes, buffer.size());
			transferBuffer = createBuffer(transferBytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
					VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, true);
		}
	}

	private void addBuffer(long bytes) {
		int memoryProperties = memoryMode == MemoryMode.DEVICE_LOCAL_STAGING
				? VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
				: VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
		buffers.add(createBuffer(bytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
				memoryProperties, memoryMode == MemoryMode.HOST_VISIBLE));
	}

	private GpuBuffer createBuffer(long bytes, int usage, int memoryProperties, boolean map) {
		long handle = VK_NULL_HANDLE;
		long memory = VK_NULL_HANDLE;
		long mappedAddress = NULL;
		try (MemoryStack stack = stackPush()) {
			VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack).sType$Default().size(bytes)
					.usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
			LongBuffer bufferPointer = stack.mallocLong(1); check(vkCreateBuffer(device, info, null, bufferPointer), "vkCreateBuffer");
			handle = bufferPointer.get(0);
			VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack); vkGetBufferMemoryRequirements(device, handle, requirements);
			int type = findMemoryType(requirements.memoryTypeBits(), memoryProperties, stack);
			VkMemoryAllocateInfo allocation = VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(requirements.size()).memoryTypeIndex(type);
			LongBuffer memoryPointer = stack.mallocLong(1); check(vkAllocateMemory(device, allocation, null, memoryPointer), "vkAllocateMemory");
			memory = memoryPointer.get(0); check(vkBindBufferMemory(device, handle, memory, 0), "vkBindBufferMemory");
			if (map) {
				PointerBuffer mapped = stack.mallocPointer(1); check(vkMapMemory(device, memory, 0, bytes, 0, mapped), "vkMapMemory");
				mappedAddress = mapped.get(0);
				MemoryUtil.memSet(mappedAddress, 0, bytes);
			}
			return new GpuBuffer(handle, memory, mappedAddress, bytes);
		} catch (RuntimeException | LinkageError exception) {
			if (mappedAddress != NULL) vkUnmapMemory(device, memory);
			if (handle != VK_NULL_HANDLE) vkDestroyBuffer(device, handle, null);
			if (memory != VK_NULL_HANDLE) vkFreeMemory(device, memory, null);
			throw exception;
		}
	}

	private void createPipelineAndDescriptors() {
		try (MemoryStack stack = stackPush()) {
			VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(BUFFER_COUNT, stack);
			for (int i = 0; i < BUFFER_COUNT; i++) bindings.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			VkDescriptorSetLayoutCreateInfo descriptorInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings);
			LongBuffer layoutPointer = stack.mallocLong(1); check(vkCreateDescriptorSetLayout(device, descriptorInfo, null, layoutPointer), "vkCreateDescriptorSetLayout"); descriptorLayout = layoutPointer.get(0);
			VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack); pushRange.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
			VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(descriptorLayout)).pPushConstantRanges(pushRange);
			LongBuffer pipelineLayoutPointer = stack.mallocLong(1); check(vkCreatePipelineLayout(device, pipelineLayoutInfo, null, pipelineLayoutPointer), "vkCreatePipelineLayout"); pipelineLayout = pipelineLayoutPointer.get(0);
			ByteBuffer spirv = compileShader(SHADER);
			try {
				VkShaderModuleCreateInfo shaderInfo = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
				LongBuffer shaderPointer = stack.mallocLong(1); check(vkCreateShaderModule(device, shaderInfo, null, shaderPointer), "vkCreateShaderModule"); shaderModule = shaderPointer.get(0);
			} finally { MemoryUtil.memFree(spirv); }
			VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(shaderModule).pName(stack.UTF8("main"));
			VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack); pipelineInfo.get(0).sType$Default().stage(stage).layout(pipelineLayout);
			LongBuffer pipelinePointer = stack.mallocLong(1); check(vkCreateComputePipelines(device, VK_NULL_HANDLE, pipelineInfo, null, pipelinePointer), "vkCreateComputePipelines"); pipeline = pipelinePointer.get(0);
			VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack); poolSize.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(BUFFER_COUNT);
			VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(poolSize);
			LongBuffer poolPointer = stack.mallocLong(1); check(vkCreateDescriptorPool(device, poolInfo, null, poolPointer), "vkCreateDescriptorPool"); descriptorPool = poolPointer.get(0);
			VkDescriptorSetAllocateInfo setInfo = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(descriptorPool).pSetLayouts(stack.longs(descriptorLayout));
			LongBuffer setPointer = stack.mallocLong(1); check(vkAllocateDescriptorSets(device, setInfo, setPointer), "vkAllocateDescriptorSets"); descriptorSet = setPointer.get(0);
			VkDescriptorBufferInfo.Buffer infos = VkDescriptorBufferInfo.calloc(BUFFER_COUNT, stack);
			VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(BUFFER_COUNT, stack);
			for (int i = 0; i < BUFFER_COUNT; i++) {
				GpuBuffer buffer = buffer(i); infos.get(i).buffer(buffer.handle()).offset(0).range(buffer.size());
				writes.get(i).sType$Default().dstSet(descriptorSet).dstBinding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(VkDescriptorBufferInfo.create(infos.get(i).address(), 1));
			}
			vkUpdateDescriptorSets(device, writes, null);
			VkCommandPoolCreateInfo commandPoolInfo = VkCommandPoolCreateInfo.calloc(stack).sType$Default().flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(queueFamily);
			LongBuffer commandPoolPointer = stack.mallocLong(1); check(vkCreateCommandPool(device, commandPoolInfo, null, commandPoolPointer), "vkCreateCommandPool"); commandPool = commandPoolPointer.get(0);
			VkCommandBufferAllocateInfo commandInfo = VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
			PointerBuffer commandPointer = stack.mallocPointer(1); check(vkAllocateCommandBuffers(device, commandInfo, commandPointer), "vkAllocateCommandBuffers"); commandBuffer = new VkCommandBuffer(commandPointer.get(0), device);
		}
	}

	private int findComputeQueue(VkPhysicalDevice physical, MemoryStack stack) {
		IntBuffer count = stack.ints(0); vkGetPhysicalDeviceQueueFamilyProperties(physical, count, null);
		VkQueueFamilyProperties.Buffer properties = VkQueueFamilyProperties.calloc(count.get(0), stack); vkGetPhysicalDeviceQueueFamilyProperties(physical, count, properties);
		int fallback = -1;
		for (int i = 0; i < properties.capacity(); i++) if ((properties.get(i).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0) { fallback = i; if ((properties.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) == 0) return i; }
		if (fallback < 0) throw new IllegalStateException("No compute queue on selected device"); return fallback;
	}

	private int findMemoryType(int bits, int required, MemoryStack stack) {
		VkPhysicalDeviceMemoryProperties memory = VkPhysicalDeviceMemoryProperties.calloc(stack); vkGetPhysicalDeviceMemoryProperties(physicalDevice, memory);
		for (int i = 0; i < memory.memoryTypeCount(); i++) if ((bits & (1 << i)) != 0 && (memory.memoryTypes(i).propertyFlags() & required) == required) return i;
		throw new IllegalStateException("No Vulkan memory type for required flags 0x" + Integer.toHexString(required));
	}

	private static ByteBuffer compileShader(String source) {
		long compiler = org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_initialize(); if (compiler == NULL) throw new IllegalStateException("Shaderc compiler unavailable");
		long result = NULL;
		try {
			result = org.lwjgl.util.shaderc.Shaderc.shaderc_compile_into_spv(compiler, source, org.lwjgl.util.shaderc.Shaderc.shaderc_compute_shader, "realwater-flip.comp", "main", NULL);
			if (result == NULL) throw new IllegalStateException("Shaderc returned NULL");
			if (org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_compilation_status(result) != org.lwjgl.util.shaderc.Shaderc.shaderc_compilation_status_success)
				throw new IllegalStateException("FLIP shader compile failed: " + org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_error_message(result));
			ByteBuffer bytes = org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_bytes(result); if (bytes == null) throw new IllegalStateException("Shaderc returned no SPIR-V");
			ByteBuffer copy = MemoryUtil.memAlloc(bytes.remaining()); copy.put(bytes).flip(); return copy;
		} finally { if (result != NULL) org.lwjgl.util.shaderc.Shaderc.shaderc_result_release(result); org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_release(compiler); }
	}

	private GpuBuffer buffer(int index) { return buffers.get(index); }
	private static void check(int result, String operation) { if (result != VK_SUCCESS) throw new IllegalStateException(operation + " failed with VkResult " + result); }
	private void destroyBuffer(GpuBuffer buffer) {
		if (buffer == null) return;
		if (buffer.mappedAddress() != NULL) vkUnmapMemory(device, buffer.memory());
		vkDestroyBuffer(device, buffer.handle(), null);
		vkFreeMemory(device, buffer.memory(), null);
	}

	@Override public void close() {
		if (device != null) {
			vkDeviceWaitIdle(device);
			if (commandPool != VK_NULL_HANDLE) vkDestroyCommandPool(device, commandPool, null);
			if (descriptorPool != VK_NULL_HANDLE) vkDestroyDescriptorPool(device, descriptorPool, null);
			if (pipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, pipeline, null);
			if (shaderModule != VK_NULL_HANDLE) vkDestroyShaderModule(device, shaderModule, null);
			if (pipelineLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(device, pipelineLayout, null);
			if (descriptorLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(device, descriptorLayout, null);
			destroyBuffer(transferBuffer); transferBuffer = null;
			for (GpuBuffer buffer : buffers) destroyBuffer(buffer);
			buffers.clear(); vkDestroyDevice(device, null); device = null;
		}
		if (instance != null) { vkDestroyInstance(instance, null); instance = null; }
	}

	record Config(int nx, int ny, int nz, float cellSize, float timeStep, float gravity, float density, int pressureIterations, int maxParticles) {
		Config {
			if (nx <= 0 || ny <= 0 || nz <= 0 || cellSize <= 0 || timeStep <= 0 || pressureIterations <= 0 || maxParticles <= 0) throw new IllegalArgumentException("Invalid GPU FLIP config");
		}
		int cellCount() { return Math.multiplyExact(Math.multiplyExact(nx, ny), nz); }
		int uCount() { return Math.multiplyExact(Math.multiplyExact(nx + 1, ny), nz); }
		int vCount() { return Math.multiplyExact(Math.multiplyExact(nx, ny + 1), nz); }
		int wCount() { return Math.multiplyExact(Math.multiplyExact(nx, ny), nz + 1); }
	}

	record SnapshotData(byte[] density, byte[] markers, byte[] solids) { }
	record Diagnostics(float preProjectionMaximumDivergence, float maximumDivergence, float rmsDivergence,
	                   float maximumPressure, float particleVolume, float centerOfMassX) { }
	record PressureProfile(float[] averageByY, int[] fluidCellsByY) { }
	record ReadbackStats(long fullBytes, long compactBytes, long fullNanos, long compactNanos, long fullChecksum, long compactChecksum) { }
	private record GpuBuffer(long handle, long memory, long mappedAddress, long size) {
		FloatBuffer floats(long byteCount) { return MemoryUtil.memFloatBuffer(requireMapped(), Math.toIntExact(byteCount / Float.BYTES)); }
		ByteBuffer bytes() { return bytes(size); }
		ByteBuffer bytes(long byteCount) { return MemoryUtil.memByteBuffer(requireMapped(), Math.toIntExact(byteCount)).order(ByteOrder.nativeOrder()); }
		private long requireMapped() { if (mappedAddress == NULL) throw new IllegalStateException("GPU buffer is not host mapped"); return mappedAddress; }
	}
	enum MemoryMode {
		DEVICE_LOCAL_STAGING("device-local+staging"), HOST_VISIBLE("host-visible baseline");
		private final String label;
		MemoryMode(String label) { this.label = label; }
		String label() { return label; }
	}
	enum PressureSolver {
		JACOBI(0, "Jacobi"), RBGS_SOR(1, "RBGS/SOR(omega=1.65)"), PCG(2, "PCG(identity)"),
		PCG_DIAGONAL(3, "PCG(diagonal)"), MULTIGRID_TWO_LEVEL(4, "MG2(V-cycle 2+24+3)");
		private final int code;
		private final String label;
		PressureSolver(int code, String label) { this.code = code; this.label = label; }
		int code() { return code; }
		String label() { return label; }
	}
	@FunctionalInterface private interface CellSelector { boolean filled(int x, int y, int z); }
}
