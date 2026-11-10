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
                if(matches.size()>1)throw new IllegalArgumentException("Duplicate carried thermal inventory");
                var solid=nodes.stream().filter(n->n.key().equals(fixed)).findFirst().orElse(null);
                if(handoff.kind()==HandoffKind.ASSEMBLE) {
                    if(!matches.isEmpty()) { if(solid!=null)throw new IllegalArgumentException("Fixed and carried inventory overlap"); continue; }
                    if(solid==null)continue;
                    String axis=solid.owner().isEmpty()?axisAt.apply(block):solid.owner();
                    String owner=blockPrefix+encode(block.blockId())+"|"+(axis==null?"-":axis);
                    Key target=carriedKey(owner);
                    if(nodes.stream().anyMatch(n->n.key().equals(target)))throw new IllegalArgumentException("Carried thermal identity collision");
                    nodes.remove(solid); removed.add(fixed); nodes.add(move(solid,target,owner));
                } else {
                    if(matches.isEmpty())continue;
                    var carried=matches.getFirst(); String axis=axisAt.apply(block);
                    String owner=blockPrefix+encode(block.blockId())+"|"+(axis==null?"-":axis);
                    if(!carried.owner().equals(owner))throw new IllegalArgumentException("Carried thermal block identity or screen axis changed");
                    if(solid!=null)throw new IllegalArgumentException("Target already has a fixed thermal inventory");
                    nodes.remove(carried); nodes.add(move(carried,fixed,axis==null?"":axis));
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
