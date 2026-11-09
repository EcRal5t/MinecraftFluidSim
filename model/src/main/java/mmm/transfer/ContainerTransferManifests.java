package mmm.transfer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.TreeMap;
import mmm.api.transfer.TransferManifest;
import mmm.api.transfer.TransferQuantity;

/** Public carried inventory derived from the exact two container snapshots in the durable journal. */
public final class ContainerTransferManifests {
	private ContainerTransferManifests() { }
	private record Inventory(Map<String,TransferManifest.Component> components,String chemical,String granular) { }
	public static TransferManifest from(ContainerTransferJournal journal, double waterDensity) {
		var first=inventory(journal.operation()==ContainerTransferJournal.Operation.INSERT?journal.beforeItem():journal.afterItem(),waterDensity);
		var second=inventory(journal.operation()==ContainerTransferJournal.Operation.INSERT?journal.afterItem():journal.beforeItem(),waterDensity);
		var components=new ArrayList<TransferManifest.Component>();
		for(var source:first.components().values()) {
			var remainder=second.components().get(source.materialId());
			long amount=source.amount()-(remainder==null?0:remainder.amount());
			long mass=source.massUnits()-(remainder==null?0:remainder.massUnits());
			long residual=source.massResidualUnits()-(remainder==null?0:remainder.massResidualUnits());
			var species=new TreeMap<String,Long>();
			for(var value:source.species()) species.put(value.speciesId(),value.amountQuanta());
			if(remainder!=null) for(var value:remainder.species()) species.merge(value.speciesId(),-value.amountQuanta(),Math::addExact);
			species.values().removeIf(value->value==0);
			if(amount==0 && mass==0 && residual==0 && species.isEmpty()) continue;
			components.add(new TransferManifest.Component(source.materialId(),amount,mass,residual,
					species.entrySet().stream().map(value->new TransferManifest.Species(value.getKey(),value.getValue())).toList()));
		}
		OptionalDouble energy=journal.thermalTransfer()==null?OptionalDouble.empty():OptionalDouble.of(journal.thermalTransfer().internalEnergy());
		TransferQuantity quantity=journal.resourceKind()==ContainerTransferJournal.ResourceKind.GRANULAR_SVQ?
				new TransferQuantity.Granular(journal.solidVolumeSvq()):new TransferQuantity.Liquid(journal.volumeNanos(),energy);
		return new TransferManifest(quantity,components,first.chemical(),first.granular(),energy);
	}
	private static Inventory inventory(ContainerTransferJournal.ItemFingerprint item, double waterDensity) {
		var result=new TreeMap<String,TransferManifest.Component>();
		String chemical=null,granular=null;
		switch(item.kind()) {
			case WATER_BUCKET,PARTIAL -> {
				long nanos=item.kind()==ContainerTransferJournal.ItemKind.WATER_BUCKET?ContainerFluidLedger.NANOS_PER_BLOCK:item.nanos();
				result.put("mmm:water",new TransferManifest.Component("mmm:water",nanos,
						mmm.units.Mass.fromKilograms(nanos*1e-9*waterDensity),0,List.of()));
			}
			case MATERIAL_BUCKET -> {
				for(var value:FluidMixture.decode(item.contents()).components()) result.put(value.materialId(),
						new TransferManifest.Component(value.materialId(),value.volumeNanos(),value.massUnits(),0,List.of()));
			}
			case CHEMICAL_BUCKET -> {
				var contents=ChemicalContents.decode(item.contents()); chemical=contents.registryFingerprint();
				for(var value:contents.groups()) result.put(value.materialId(),new TransferManifest.Component(value.materialId(),
						value.volumeNanos(),value.massUnits(),value.massResidualUnits(),value.species().stream()
						.map(entry->new TransferManifest.Species(entry.speciesId(),entry.amountQuanta())).toList()));
			}
			case GRANULAR_CONTAINER -> {
				var contents=GranularContents.decode(item.contents()); granular=contents.registryFingerprint();
				for(var value:contents.groups()) result.put(value.materialId(),new TransferManifest.Component(value.materialId(),
						value.solidVolumeSvq(),value.massUnits(),value.massResidualUnits(),value.composition().stream()
						.map(entry->new TransferManifest.Species(entry.speciesId(),entry.amountQuanta())).toList()));
			}
			case BUCKET,EMPTY_MATERIAL_BUCKET,EMPTY_GRANULAR_CONTAINER -> { }
			case CREATIVE_SOURCE -> throw new IllegalArgumentException("Public transfer requires a finite inventory actor");
		}
		return new Inventory(result,chemical,granular);
	}
}
