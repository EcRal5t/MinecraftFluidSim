package mmm.thermal;

import java.io.*;
import java.util.Base64;
import java.util.TreeMap;

/** Removable surface load with its actual adsorbates and carried internal energy. */
public record SurfaceCoatingContents(String registryFingerprint,FixedSurfaceState surface,double internalEnergyJ) {
    public SurfaceCoatingContents {
        if(registryFingerprint==null||!registryFingerprint.matches("[0-9a-f]{64}")||surface==null||!Double.isFinite(internalEnergyJ))throw new IllegalArgumentException("Invalid surface contents");
    }
    public String encode(){
        try{
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            out.writeUTF(registryFingerprint);out.writeUTF(surface.face().name());out.writeUTF(surface.materialId());out.writeLong(surface.loadingMassUnits());out.writeDouble(internalEnergyJ);
            out.writeInt(surface.speciesQuanta().size());for(var entry:new TreeMap<>(surface.speciesQuanta()).entrySet()){out.writeUTF(entry.getKey());out.writeLong(entry.getValue());}
            out.writeInt(surface.residualQuanta().size());for(var entry:surface.residualQuanta().entrySet()){out.writeUTF(entry.getKey());out.writeDouble(entry.getValue());}
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        }catch(IOException failure){throw new UncheckedIOException(failure);}
    }
    public static SurfaceCoatingContents decode(String encoded){
        try{
            var in=new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
            String registry=in.readUTF();var face=FiniteThermalDeviceDefinition.LocalFace.valueOf(in.readUTF());String material=in.readUTF();long mass=in.readLong();double energy=in.readDouble();
            var species=new TreeMap<String,Long>();int count=in.readInt();for(int i=0;i<count;i++)species.put(in.readUTF(),in.readLong());
            var residuals=new TreeMap<String,Double>();count=in.readInt();for(int i=0;i<count;i++)residuals.put(in.readUTF(),in.readDouble());
            return new SurfaceCoatingContents(registry,new FixedSurfaceState(face,material,mass,species,residuals),energy);
        }catch(IOException failure){throw new IllegalArgumentException("Invalid surface payload",failure);}
    }
}
