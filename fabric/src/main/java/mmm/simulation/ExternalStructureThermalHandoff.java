package mmm.simulation;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import mmm.api.motion.MovingStructureApi.*;
import mmm.thermal.EnvironmentThermalState;
import mmm.thermal.EnvironmentThermalState.*;

/** Moves world-owned solid inventories between fixed positions and a structure. */
final class ExternalStructureThermalHandoff {
    private ExternalStructureThermalHandoff() { }
    private static String encode(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static String prefix(String provider, UUID structure) { return encode(provider)+"|"+structure+"|"; }
    private static String blockPrefix(String provider, UUID structure, FixedBlock block) { return prefix(provider,structure)+encode(block.key())+"|"; }
    private static Key carriedKey(String owner) {
        UUID hash=UUID.nameUUIDFromBytes(owner.getBytes(StandardCharsets.UTF_8));
        return new Key((int)(hash.getMostSignificantBits()>>>32),(int)hash.getMostSignificantBits(),
                (int)(hash.getLeastSignificantBits()>>>32),Kind.CARRIED,(int)(hash.getLeastSignificantBits()&0x7fffffff));
    }
    static boolean hasCarried(EnvironmentThermalState state, String provider, UUID structure) {
        String prefix=prefix(provider,structure);
        return state.nodes().stream().anyMatch(n->n.key().kind()==Kind.CARRIED && n.owner().startsWith(prefix));
    }
    static EnvironmentThermalState plan(EnvironmentThermalState state, List<ExternalMovingStructures.CompletedHandoff> handoffs,
                                        Function<FixedBlock,String> axisAt) {
        var nodes=new ArrayList<>(state.nodes());
        var removed=new HashSet<Key>();
        for(var entry:handoffs) {
            var handoff=entry.handoff(); var structure=handoff.structure().id();
            var keys=new HashSet<String>(); var positions=new HashSet<Key>(); var expected=new HashSet<String>();
            for(var block:handoff.blocks()) {
                var fixed=new Key(block.x(),block.y(),block.z(),Kind.SOLID,0);
                if(!keys.add(block.key()) || !positions.add(fixed))throw new IllegalArgumentException("Duplicate thermal handoff block");
                String blockPrefix=blockPrefix(entry.provider(),structure,block); expected.add(blockPrefix);
                var matches=nodes.stream().filter(n->n.key().kind()==Kind.CARRIED && n.owner().startsWith(blockPrefix)).toList();
                var solids=nodes.stream().filter(n->n.key().kind()==Kind.SOLID && n.key().x()==block.x()
                        && n.key().y()==block.y() && n.key().z()==block.z()).toList();
                if(handoff.kind()==HandoffKind.ASSEMBLE) {
                    if(!matches.isEmpty()) { if(!solids.isEmpty())throw new IllegalArgumentException("Fixed and carried inventory overlap"); continue; }
                    for(var solid:solids) {
                        int component=solid.key().component();
                        if(component>1)throw new IllegalArgumentException("Unsupported solid thermal component");
                        String axis=solid.owner().isEmpty()?null:solid.owner();
                        String owner=blockPrefix+encode(block.blockId())+"|"+(axis==null?"-":axis)+(component==0?"":"|1");
                        Key target=carriedKey(owner);
                        if(nodes.stream().anyMatch(n->n.key().equals(target)))throw new IllegalArgumentException("Carried thermal identity collision");
                        nodes.remove(solid); removed.add(solid.key()); nodes.add(move(solid,target,owner));
                    }
                } else {
                    for(var carried:matches) {
                        String base=blockPrefix+encode(block.blockId())+"|";
                        if(!carried.owner().startsWith(base))throw new IllegalArgumentException("Carried thermal block identity changed");
                        String tail=carried.owner().substring(base.length());
                        boolean second=tail.endsWith("|1");
                        String axis=second?tail.substring(0,tail.length()-2):tail;
                        if(!List.of("-","X","Y","Z").contains(axis)
                                || !axis.equals("-") && !axis.equals(axisAt.apply(block)))
                            throw new IllegalArgumentException("Carried thermal screen axis changed");
                        var target=new Key(block.x(),block.y(),block.z(),Kind.SOLID,second?1:0);
                        if(nodes.stream().anyMatch(n->n.key().equals(target)))throw new IllegalArgumentException("Target already has a fixed thermal inventory");
                        nodes.remove(carried); nodes.add(move(carried,target,axis.equals("-")?"":axis));
                    }
                }
            }
            if(handoff.kind()==HandoffKind.DISASSEMBLE)for(var node:nodes)
                if(node.key().kind()==Kind.CARRIED && node.owner().startsWith(prefix(entry.provider(),structure))
                        && expected.stream().noneMatch(node.owner()::startsWith))throw new IllegalArgumentException("Handoff omitted a carried thermal inventory");
        }
        var indexes=new HashMap<Key,Integer>(); for(int i=0;i<nodes.size();i++)indexes.put(nodes.get(i).key(),i);
        var contacts=new ArrayList<Contact>();
        for(var contact:state.contacts()) {
            var a=state.nodes().get(contact.a()).key(); var b=contact.b()<0?null:state.nodes().get(contact.b()).key();
            if(removed.contains(a) || b!=null && removed.contains(b))continue;
            Integer ai=indexes.get(a),bi=b==null?null:indexes.get(b);
            if(ai!=null && (b==null || bi!=null))contacts.add(new Contact(ai,b==null?-1:bi,contact.conductanceWPerK(),contact.farTemperatureK()));
        }
        return new EnvironmentThermalState(state.dimensionId(),state.generation(),state.registryFingerprint(),nodes,contacts,state.audit());
    }
    private static Node move(Node node,Key key,String owner) {
        return new Node(key,node.materialId(),node.massUnits(),node.volumeCubicMetres(),node.gas(),node.internalEnergyJ(),node.surfaces(),owner);
    }
}
