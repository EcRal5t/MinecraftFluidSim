package mmm.chemistry;

import java.util.ArrayList;
import java.util.List;

/** Supported neutral and electroneutral solute exchanges in a water-based phase. */
public final class AqueousDiffusion {
    public record Rule(String id,String phaseId,int first,int second,int firstCount,int secondCount,
                       double diffusivityM2PerS,double solventMolesPerExtent,int axis) { }
    private AqueousDiffusion() { }
    public static List<Rule> rules(ChemicalRegistry registry) {
        var result=new ArrayList<Rule>();
        for(var phase:registry.phases()) {
            if(!phase.allowedSpecies().contains(BuiltinChemicalDefinitions.H2O))continue;
            var water=registry.species(registry.speciesHandle(BuiltinChemicalDefinitions.H2O));
            if(water.effectiveVolumeM3PerMol()==null)continue;
            for(String id:phase.allowedSpecies()) {
                if(id.equals(BuiltinChemicalDefinitions.H2O))continue;
                var first=registry.species(registry.speciesHandle(id));
                if(first.diffusivityM2PerS()==null||first.effectiveVolumeM3PerMol()==null||first.charge()<0)continue;
                if(first.charge()==0){add(result,registry,phase,first,null,1,0,water.effectiveVolumeM3PerMol());continue;}
                for(String otherId:phase.allowedSpecies()) {
                    var second=registry.species(registry.speciesHandle(otherId));
                    if(second.charge()>=0||second.diffusivityM2PerS()==null||second.effectiveVolumeM3PerMol()==null)continue;
                    int gcd=gcd(first.charge(),-second.charge());
                    add(result,registry,phase,first,second,-second.charge()/gcd,first.charge()/gcd,water.effectiveVolumeM3PerMol());
                }
            }
        }
        return List.copyOf(result);
    }
    private static void add(List<Rule> out,ChemicalRegistry registry,ChemicalPhaseDefinition phase,ChemicalSpecies a,ChemicalSpecies b,int ac,int bc,double waterVolume){
        double volume=(ac*a.effectiveVolumeM3PerMol()+(b==null?0:bc*b.effectiveVolumeM3PerMol()))/waterVolume;
        double diffusivity=b==null?a.diffusivityM2PerS():Math.min(a.diffusivityM2PerS(),b.diffusivityM2PerS());
        String base="mmm:diffusion/"+phase.id().replace(':','/')+"/"+a.id().replace(':','/')+(b==null?"":"/"+b.id().replace(':','/'));
        for(int axis=0;axis<3;axis++)out.add(new Rule(base+"/"+axis,phase.id(),registry.speciesHandle(a.id()),b==null?-1:registry.speciesHandle(b.id()),ac,bc,diffusivity,volume,axis));
    }
    private static int gcd(int a,int b){while(b!=0){int t=a%b;a=b;b=t;}return a;}
}
