package mmm.simulation;

import java.util.OptionalDouble;
import java.util.UUID;
import mmm.api.transfer.*;
import mmm.block.*;
import mmm.item.*;
import mmm.transfer.ContainerTransferActor;
import mmm.transfer.TransferActorState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;

/** World block operations sharing the production region and durable inventory transfer entry points. */
public final class IndustrialOperations {
    private final WorldStartupCoordinator.Result startup;
    private final FluidWorldTargetResolver targets;
    private final CommittedWorldRegistries world;
    private final MinecraftContainerTransactionAdapter transactions;
    private final FluidSnapshotPublisher snapshots;
    private final mmm.application.ContainerRecoveryCoordinator recovery;
    private final MinecraftRegionAvailability availability;
    private final MinecraftPendingPhaseWorkBarrier phaseWork;
    IndustrialOperations(WorldStartupCoordinator.Result startup,FluidWorldTargetResolver targets,CommittedWorldRegistries world,MinecraftContainerTransactionAdapter transactions,
            mmm.application.ContainerRecoveryCoordinator recovery,MinecraftRegionAvailability availability,MinecraftPendingPhaseWorkBarrier phaseWork,
            FluidSnapshotPublisher snapshots){
        this.startup=startup;this.targets=targets;this.world=world;this.transactions=transactions;this.recovery=recovery;this.availability=availability;this.phaseWork=phaseWork;this.snapshots=snapshots;
    }
    FluidRegion regionAt(ServerLevel level,BlockPos position){return targets.regionAtBlock(level,position);}
    public InteractionResult useBox(ServerPlayer player,InteractionHand hand,BlockPos position){
        var box=(MaterialBoxBlockEntity)player.level().getBlockEntity(position);
        var held=player.getItemInHand(hand);
        if(box.transferBusy()){player.sendSystemMessage(Component.literal("物料箱正在交接"));return InteractionResult.SUCCESS;}
        if(held.isEmpty() && player.isShiftKeyDown()) {
            box.selectNext();
            player.sendSystemMessage(Component.literal("物料箱批次 " + (box.selected()+1) + " · " + describe(box.getItem(box.selected()))));
            return InteractionResult.SUCCESS;
        }
        var current=box.getItem(box.selected());
        if(recovery.hasPending(new UUID(0,0),slotActor(player.level(),position,box.selected()))){
            player.sendSystemMessage(Component.literal("物料交接恢复中"));return InteractionResult.SUCCESS;
        }
        if(held.isEmpty()) {
            if(current.isEmpty()) player.sendSystemMessage(Component.literal("该批次为空"));
            else {
                var result=box.removeItemNoUpdate(box.selected());
                result.remove(MmmComponents.TRANSFER_ACTOR);
                player.setItemInHand(hand,result); player.getInventory().setChanged();
            }
            return InteractionResult.SUCCESS;
        }
        if(held.getCount()!=1)return InteractionResult.PASS;
        if(!MaterialBoxItemStorage.supported(held))return InteractionResult.PASS;
        var stored=held.copy();
        var previous=box.getItem(box.selected()).copy();if(!previous.isEmpty())previous.remove(MmmComponents.TRANSFER_ACTOR);
        box.setItem(box.selected(),MaterialBoxBlockEntity.newBatch(stored));player.setItemInHand(hand,previous);
        player.getInventory().setChanged();return InteractionResult.SUCCESS;
    }
    public boolean boxTransferPending(ServerLevel level,MaterialBoxBlockEntity box){
        if(box.transferBusy())return true;
        for(int slot=0;slot<box.getContainerSize();slot++)
            if(recovery.hasPending(new UUID(0,0),slotActor(level,box.getBlockPos(),slot)))return true;
        return false;
    }
    public void usePort(ServerPlayer player,BlockPos position){
        var port=(FluidPortBlockEntity)player.level().getBlockEntity(position);
        IndustrialDialogs.port(player,port);
    }
    public boolean probe(ServerPlayer player,BlockPos position){
        var state=player.level().getBlockState(position);
        if(state.is(MmmBlocks.VALVE)||state.is(MmmBlocks.PIPE_VALVE)){IndustrialDialogs.valve(player,position,state.getValue(state.is(MmmBlocks.PIPE_VALVE)?PipeValveBlock.OPENING:ValveBlock.OPENING));return true;}
        var entity=player.level().getBlockEntity(position);
        if(entity instanceof MaterialBoxBlockEntity box){IndustrialDialogs.box(player,box);return true;}
        if(entity instanceof FluidPortBlockEntity port){IndustrialDialogs.port(player,port);return true;}
        if(entity instanceof ProcessSensorBlockEntity sensor){IndustrialDialogs.sensor(player,sensor);return true;}
        if(entity instanceof ScreenBlockEntity screen){IndustrialDialogs.screen(player,screen,screenDepositMass(player.level(),screen));return true;}
        if(state.getBlock() instanceof PipeBlock) {
            var pipes=MmmRuntimeRegistry.require(player.level().getServer()).pipes();
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(pipes==null?"当前世界需要启用热物性以运行管腔":pipes.describe(player.level(),position)));return true;
        }
        return false;
    }
    public String configure(ServerPlayer player,BlockPos position,String settings){
        if(!player.level().mayInteract(player,position))return "该位置无法编辑";
        var args=settings.trim().split("\\s+");var entity=player.level().getBlockEntity(position);
        if(entity instanceof MaterialBoxBlockEntity box && box.transferBusy())return "物料箱正在交接";
        try {
            if(args[0].equals("box")&&args.length==2&&entity instanceof MaterialBoxBlockEntity box){box.select(Integer.parseInt(args[1]));return "已选择批次 "+(box.selected()+1);}
            if(args[0].equals("port")&&args.length==5&&entity instanceof FluidPortBlockEntity port){
                port.configure(FluidPortBlockEntity.Mode.valueOf(args[1]),FluidPortBlockEntity.Phase.valueOf(args[2]),Math.round(Double.parseDouble(args[3])*1e6),Boolean.parseBoolean(args[4]));return "接口已设置";
            }
            if(args[0].equals("sensor")&&args.length==12&&entity instanceof ProcessSensorBlockEntity sensor){
                sensor.configure(mmm.api.sensor.ProcessSensorSnapshot.Measure.valueOf(args[1]),Double.parseDouble(args[2]),Double.parseDouble(args[3]),Boolean.parseBoolean(args[4]),Boolean.parseBoolean(args[5]),
                    new BlockPos(Integer.parseInt(args[6]),Integer.parseInt(args[7]),Integer.parseInt(args[8])),new BlockPos(Integer.parseInt(args[9]),Integer.parseInt(args[10]),Integer.parseInt(args[11])));return "测点已设置";
            }
            if(args[0].equals("valve")&&args.length==2)return setValve(player.level(),position,Integer.parseInt(args[1]));
            if(args[0].equals("screen")&&args.length==3&&entity instanceof ScreenBlockEntity screen){screen.configure(Double.parseDouble(args[1])*.001,Double.parseDouble(args[2]));return "筛孔已设置";}
        }catch(IllegalArgumentException invalid){return "设置无效："+invalid.getMessage();}
        return "请选择对应的工业方块";
    }
    private double screenDepositMass(ServerLevel level,ScreenBlockEntity screen){
        var region=targets.regionAtBlock(level,screen.getBlockPos());if(region==null||!region.solverLoaded())return 0;
        var cells=new java.util.HashSet<Integer>();var grid=region.grid();
        for(int face:screenFaces(region,screen.getBlockPos(),screen.getBlockState())){cells.add(grid.faceLeft(face));cells.add(grid.faceRight(face));}
        double mass=0;
        for(int cell:cells)if(cell>=0){
            var carriers=region.observeCellCarriers(grid.x(cell),grid.y(cell),grid.z(cell));
            if(carriers!=null)for(var solid:carriers.solids())
                if(solid.kind()==mmm.fluid.CellCarrierInventoryView.SolidKind.DEPOSIT)mass+=solid.massKilograms();
        }
        return mass;
    }
    static int[] screenFaces(FluidRegion region,BlockPos position,net.minecraft.world.level.block.state.BlockState state){
        var axis=state.getValue(ScreenBlock.FACING).getAxis();int normal=axis==net.minecraft.core.Direction.Axis.X?0:axis==net.minecraft.core.Direction.Axis.Y?1:2;
        int scale=FluidRegionFactory.CELLS_PER_BLOCK;var origin=region.origin();int[] base={(position.getX()-origin.getX())*scale,(position.getY()-origin.getY())*scale,(position.getZ()-origin.getZ())*scale};
        var faces=new java.util.ArrayList<Integer>();
        for(int a=0;a<scale;a++)for(int b=0;b<scale;b++){
            var cell=base.clone();cell[normal]+=scale/2;cell[(normal+1)%3]+=a;cell[(normal+2)%3]+=b;
            int face=region.grid().faceAt(cell[0],cell[1],cell[2],normal);
            if(face>=0&&region.grid().faceLeft(face)>=0&&region.grid().faceRight(face)>=0)faces.add(face);
        }
        return faces.stream().mapToInt(Integer::intValue).toArray();
    }
    public String setValve(ServerLevel level,BlockPos position,int opening){
        var state=level.getBlockState(position);
        boolean pipe=state.is(MmmBlocks.PIPE_VALVE);
        if(!pipe&&!state.is(MmmBlocks.VALVE))return "请选择阀门";
        int maximum=pipe?2:4;
        if(opening<0||opening>maximum)return "开度应在 0—"+maximum+" 之间";
        var property=pipe?PipeValveBlock.OPENING:ValveBlock.OPENING;
        var next=state.setValue(property,opening);
        if(next!=state){
            String failure=changeGeometry(level,position,next,pipe?PipeValveBlock.collision(next):ValveBlock.shutter(next));
            if(failure!=null)return failure;
        }
        return "开度 "+opening+" / "+maximum;
    }
    public String setPipePort(ServerLevel level,BlockPos position,net.minecraft.world.level.block.state.BlockState next){
        var previous=level.getBlockState(position);
        if(!(previous.getBlock() instanceof PipeBlock)||next.getBlock()!=previous.getBlock())return "管道已变化";
        if(next==previous)return "管口未变化";
        String failure=changeGeometry(level,position,next,PipeBlock.collision(next));
        return failure==null?"管口已更新":failure;
    }
    private String changeGeometry(ServerLevel level,BlockPos position,net.minecraft.world.level.block.state.BlockState next,
                                  net.minecraft.world.phys.shapes.VoxelShape shape){
        if(PipeThermalGeometry.isPipe(next)) {
            var pipes=MmmRuntimeRegistry.require(level.getServer()).pipes();
            if(pipes==null)return "当前世界需要启用热物性以运行管腔";
            if(!pipes.allowsBlockChange(level,position,next))return "管内库存超过新管腔容量，请先排空或清理沉积";
            return level.setBlock(position,next,net.minecraft.world.level.block.Block.UPDATE_ALL)?null:"管道正在进行物质交接";
        }
        var region=targets.regionAtBlock(level,position);
        if(region!=null){
            if(!region.solverLoaded()||!availability.ensure(level,region)||!phaseWork.settle(level,region))return "等待区域恢复";
            var edits=new java.util.ArrayList<mmm.fluid.FluidDomainContracts.CollisionEdit>();
            int scale=FluidRegionFactory.CELLS_PER_BLOCK;var origin=region.origin();
            for(int z=0;z<scale;z++)for(int y=0;y<scale;y++)for(int x=0;x<scale;x++){
                int cx=(position.getX()-origin.getX())*scale+x,cy=(position.getY()-origin.getY())*scale+y,cz=(position.getZ()-origin.getZ())*scale+z;
                if(region.grid().cell(cx,cy,cz)>=0)edits.add(new mmm.fluid.FluidDomainContracts.CollisionEdit(cx,cy,cz,
                        FluidRegionWorldInteraction.sampleCutCellMask(shape,x,y,z,scale,1e-7)));
            }
            try { var generation=region.solverGeneration();var candidate=region.beginHostEdit();candidate.applyCollisionEdits(edits);region.adoptCollisionCandidate(generation,candidate); }
            catch(mmm.fluid.FluidDomainContracts.CollisionEditBlockedException|mmm.fluid.GasTopologyBlockedException blocked){return "管路调整受阻："+blocked.getMessage();}
            region.wakeForActivity();
        }
        level.setBlock(position,next,net.minecraft.world.level.block.Block.UPDATE_ALL);
        return null;
    }
    public void tickSensor(ServerLevel level,ProcessSensorBlockEntity sensor){
        if(level.getGameTime()%4!=0)return;
        var first=sensor.first();var second=sensor.second();
        if(first==null){first=sensor.getBlockPos().relative(sensor.getBlockState().getValue(ProcessSensorBlock.FACING).getOpposite());second=first;}
        var region=targets.regionAtBlock(level,first);
        if(region==null||!region.solverLoaded()||region.availabilityFailure()!=null||targets.regionAtBlock(level,second)!=region){sensor.unavailable(mmm.api.sensor.ProcessSensorSnapshot.Status.UNAVAILABLE);return;}
        sensor.history().region(region.id(),region.networkGeneration());
        boolean active=!region.paused();
        observeSensor(region,sensor,first,second,active);
    }
    static void observeSensor(FluidRegion region,ProcessSensorBlockEntity sensor,BlockPos first,BlockPos second){
        observeSensor(region,sensor,first,second,true);
    }
    private static void observeSensor(FluidRegion region,ProcessSensorBlockEntity sensor,BlockPos first,BlockPos second,boolean record){
        double h=region.solverConfig().cellSize();var origin=region.origin();
        int[] min={(int)Math.floor((Math.min(first.getX(),second.getX())-origin.getX())/h),(int)Math.floor((Math.min(first.getY(),second.getY())-origin.getY())/h),(int)Math.floor((Math.min(first.getZ(),second.getZ())-origin.getZ())/h)};
        int[] max={(int)Math.ceil((Math.max(first.getX(),second.getX())+1-origin.getX())/h),(int)Math.ceil((Math.max(first.getY(),second.getY())+1-origin.getY())/h),(int)Math.ceil((Math.max(first.getZ(),second.getZ())+1-origin.getZ())/h)};
        if(sensor.measure()==mmm.api.sensor.ProcessSensorSnapshot.Measure.FLOW){
            var axis=sensor.getBlockState().getValue(ProcessSensorBlock.FACING).getAxis();int a=axis==net.minecraft.core.Direction.Axis.X?0:axis==net.minecraft.core.Direction.Axis.Y?1:2;
            max[a]=min[a]+1;var faces=new java.util.ArrayList<Integer>();
            for(int z=min[2];z<max[2];z++)for(int y=min[1];y<max[1];y++)for(int x=min[0];x<max[0];x++){
                int face=region.grid().faceAt(x,y,z,a);
                if(face>=0){
                    int left=region.grid().faceLeft(face),right=region.grid().faceRight(face);
                    if((left>=0&&!region.groupAvailable(left))||(right>=0&&!region.groupAvailable(right))){sensor.unavailable(mmm.api.sensor.ProcessSensorSnapshot.Status.UNAVAILABLE);return;}
                    faces.add(face);
                }
            }
            if(faces.isEmpty()){sensor.unavailable(mmm.api.sensor.ProcessSensorSnapshot.Status.NO_SAMPLE);return;}
            recordSensorSample(region,sensor,record,region.observeLiquidFlow(faces.stream().mapToInt(Integer::intValue).toArray())*1000);return;
        }
        double sum=0,weight=0;var host=sensor.measure()==mmm.api.sensor.ProcessSensorSnapshot.Measure.PRESSURE?region.hostSnapshot():null;
        for(int z=min[2];z<max[2];z++)for(int y=min[1];y<max[1];y++)for(int x=min[0];x<max[0];x++){
            int cell=region.grid().cell(x,y,z);if(cell<0)continue;
            if(!region.groupAvailable(cell)){sensor.unavailable(mmm.api.sensor.ProcessSensorSnapshot.Status.UNAVAILABLE);return;}
            double openVolume=Integer.bitCount((~region.solidMicroMask(x,y,z))&255)*h*h*h/8;
            if(openVolume==0)continue;
            var liquid=region.observeCellInventory(x,y,z);
            switch(sensor.measure()){
                case LEVEL->{sum+=liquid==null?0:liquid.liquidVolumeCubicMetres();weight+=Integer.bitCount((~region.solidMicroMask(x,y,z))&255)*h*h*h/8;}
                case LIQUID_TEMPERATURE->{if(liquid!=null&&liquid.temperatureKelvin().isPresent()){sum+=liquid.temperatureKelvin().getAsDouble()*liquid.liquidMassKilograms();weight+=liquid.liquidMassKilograms();}}
                case GAS_TEMPERATURE->{var carriers=region.observeCellCarriers(x,y,z);var gas=carriers==null?null:carriers.gas();if(gas!=null){double mass=gas.components().stream().mapToDouble(mmm.fluid.CellCarrierInventoryView.ComponentAmount::massKilograms).sum();sum+=gas.temperatureKelvin()*mass;weight+=mass;}}
                case PRESSURE->{sum+=(host.pressure(x,y,z)+mmm.thermal.MixtureVolumeModel.REFERENCE_PRESSURE_PA)*openVolume;weight+=openVolume;}
                case FLOW->throw new IllegalStateException("Flow is sampled from accepted faces");
            }
        }
        if(weight>0)recordSensorSample(region,sensor,record,sum/weight);
        else sensor.unavailable(mmm.api.sensor.ProcessSensorSnapshot.Status.NO_SAMPLE);
    }
    private static void recordSensorSample(FluidRegion region,ProcessSensorBlockEntity sensor,boolean running,double value){
        sensor.observe(value,region.id(),region.networkGeneration(),region.solverGeneration(),region.sessionSimulationSeconds(),running);
        if(running)sensor.history().sample(region.sessionSimulationSeconds(),value);
        else sensor.history().samplePaused(region.sessionSimulationSeconds(),value);
    }
    public void tickPort(ServerLevel level,FluidPortBlockEntity port){
        if(port.mode()==FluidPortBlockEntity.Mode.STOPPED)return;
        if(!startup.authoritativeWritesReady()){port.status("世界暂不可写");return;}
        var position=port.getBlockPos();var outward=port.getBlockState().getValue(FluidPortBlock.FACING);
        if(port.redstone()&&!level.hasNeighborSignal(position)){port.status("等待红石");return;}
        var boxPosition=position.relative(outward);
        if(!level.hasChunkAt(boxPosition)||!(level.getBlockEntity(boxPosition) instanceof MaterialBoxBlockEntity box)){port.status("等待物料箱");return;}
        if(box.transferBusy()){port.status("物料箱正在交接");return;}
        var target=targets.transferTargetAtBlock(level,position,outward.getOpposite());
        if(target==null||target.region().paused()||!target.region().solverLoaded()||!availability.ensure(level,target.region())||!phaseWork.settle(level,target.region())){port.status("等待区域");return;}
        boolean insert=port.mode()==FluidPortBlockEntity.Mode.INPUT;
        for(int offset=0;offset<box.getContainerSize();offset++){
            int slot=(box.selected()+offset)%box.getContainerSize();var item=box.getItem(slot);
            if(item.isEmpty())continue;
            if(recovery.hasPending(target.region().id(),actor(level,boxPosition,slot,item))){port.status("物料交接恢复中");return;}
            boolean solid=item.getItem() instanceof GranularContainerItem,gas=item.getItem() instanceof GasContainerItem;
            if(gas!=(port.phase()==FluidPortBlockEntity.Phase.GAS)||solid!=(port.phase()==FluidPortBlockEntity.Phase.SOLID))continue;
            if(!insert && !(item.getItem() instanceof MaterialBucketItem || solid || gas))continue;
            var kind=MinecraftContainerTransferOperations.classify(item);
            boolean filled=kind==MinecraftContainerTransferOperations.TransferKind.POUR||kind==MinecraftContainerTransferOperations.TransferKind.MATERIAL_POUR||kind==MinecraftContainerTransferOperations.TransferKind.GRANULAR_POUR||kind==MinecraftContainerTransferOperations.TransferKind.GAS_POUR;
            if(insert&&!filled)continue;
            long rate=solid?Math.round(port.nanosPerSecond()*1e-9*mmm.granular.SolidVolume.SVQ_PER_CUBIC_METER):port.nanosPerSecond();
            long amount=tickQuota(rate,level.getGameTime());if(amount==0)return;
            TransferQuantity quantity=solid?new TransferQuantity.Granular(amount):gas?new TransferQuantity.Gas(amount,0):new TransferQuantity.Liquid(amount,OptionalDouble.empty());
            var edit=target.region().beginHostEdit();
            int[] cell=target.cell(quantity,!insert,edit);
            if(cell==null)continue;
            var actor=actor(level,boxPosition,slot,item);
            try {
                var result=transferBatch(transactions,recovery,world,target.region(),cell,edit,item.copy(),actor,!insert,amount,
                        () -> new MinecraftContainerActorAdapter().persistAfterItem(level.getServer(),actor));
                if(result.pending()){port.status("物料交接恢复中");return;}
                if(result.transaction()!=null && result.transaction().status()==mmm.application.ContainerTransferUseCase.Status.COMMITTED){
                    port.status("运行");snapshots.sendToLevel(level,target.region());return;
                }
                if(result.transaction()!=null && result.transaction().status()!=mmm.application.ContainerTransferUseCase.Status.NOT_TRANSFERRED){
                    port.status(result.diagnostic());return;
                }
            } catch(java.io.IOException | RuntimeException failure) {
                mmm.MmmMod.LOGGER.error("Material box transfer failed",failure);
                port.status(recovery.hasPending(target.region().id(),actor) ? "物料交接恢复中" : "物料交接失败：" + failure.getMessage());return;
            }

        }
        port.status(insert?"等待供料":"等待可用批次或接触物料");
    }
    @FunctionalInterface interface BeforeSave { void save() throws java.io.IOException; }
    record PortTransfer(MinecraftContainerTransactionAdapter.Result transaction,String diagnostic,boolean pending) { }
    /** One bounded internal port transfer using the shared durable container coordinator. */
    static PortTransfer transferBatch(MinecraftContainerTransactionAdapter transactions,
            mmm.application.ContainerRecoveryCoordinator recovery,CommittedWorldRegistries world,FluidRegion region,
            int[] cell,mmm.fluid.HostFluidState edit,ItemStack before,ContainerTransferActor.BlockInventory actor,boolean extract,long maximum,
            BeforeSave beforeSave) throws java.io.IOException {
        if(recovery.hasPending(region.id(),actor))return new PortTransfer(null,"物料交接恢复中",true);
        if(!actor.dimensionId().equals(region.dimension().identifier().toString()))
            throw new IllegalArgumentException("物料箱与区域不在同一维度");
        if(maximum<=0)return new PortTransfer(null,"本次额度为空",false);
        var resolution=MinecraftContainerTransferPlan.resolveDirected(transactions,world,region,cell,edit,before,extract);
        if(!resolution.resolved())return new PortTransfer(null,resolution.diagnostic(),false);
        var plan=resolution.plan();
        long requested=Math.min(maximum,plan.requestedAmount());
        if(requested<=0)return new PortTransfer(null,"容器没有可用空间",false);
        var candidate=region.lifecycleCandidate(region.beginHostEdit(),region.paused());
        var preview=plan.executeExact(candidate,requested,plan.baseAmount());
        if(preview==null || !preview.transferred())return new PortTransfer(null,"没有可交接的库存",false);
        if(preview.resourceAmount()<=0 || preview.resourceAmount()>requested)
            throw new IllegalStateException("物料预演返回无效数量");
        var expected=ContainerItemMapper.fingerprint(world.bindActualContainerProfile(preview.stack()));
        var expectedEnergy=preview.thermalEnergy();
        long expectedAmount=preview.resourceAmount();
        beforeSave.save();
        var result=transactions.executeForRegion(region,actor,before,plan.sourceStack(),
                extract?mmm.transfer.ContainerTransferJournal.Operation.EXTRACT:mmm.transfer.ContainerTransferJournal.Operation.INSERT,
                plan.resourceKind(),requested,plan.baseAmount(),(actualCandidate,limit,base)->{
                    var actual=plan.executeExact(actualCandidate,limit,base);
                    if(actual==null || !actual.transferred() || actual.resourceAmount()!=expectedAmount
                            || !expected.equals(ContainerItemMapper.fingerprint(world.bindActualContainerProfile(actual.stack())))
                            || !java.util.Objects.equals(expectedEnergy,actual.thermalEnergy()))
                        throw new IllegalStateException("实际物料交接与预演不一致");
                    return actual;
                },(raw,old)->raw,null,plan.edit(),plan.editGeneration());
        if(result.status()==mmm.application.ContainerTransferUseCase.Status.COMMITTED)
            transactions.completeCommitted(result.transactionId());
        return new PortTransfer(result,result.outcome().message(),recovery.hasPending(region.id(),actor));
    }
    static long tickQuota(long perSecond,long tick) {
        long phase=Math.floorMod(tick,20);
        return (phase+1)*perSecond/20-phase*perSecond/20;
    }
    public static boolean allowsBoxRemoval(ServerLevel level,BlockPos position) {
        if(!level.hasChunkAt(position) || !(level.getBlockEntity(position) instanceof MaterialBoxBlockEntity box))return true;
        if(box.transferBusy())return false;
        var runtime=MmmRuntimeRegistry.find(level.getServer());
        return runtime==null || !runtime.industrial().boxTransferPending(level,box);
    }
    static boolean inventoryAvailable(net.minecraft.world.Container inventory) {
        return !(inventory instanceof MaterialBoxBlockEntity box) || !box.transferBusy();
    }
    boolean mayAutomateBoxSlot(ServerLevel level,MaterialBoxBlockEntity box,int slot) {
        return !recovery.hasPending(new UUID(0,0),slotActor(level,box.getBlockPos(),slot));
    }
    private static ContainerTransferActor.BlockInventory slotActor(ServerLevel level,BlockPos position,int slot){
        return new ContainerTransferActor.BlockInventory(level.dimension().identifier().toString(),position.getX(),position.getY(),position.getZ(),slot);
    }
    private static ContainerTransferActor.BlockInventory actor(ServerLevel level,BlockPos position,int slot,ItemStack item){
        var identity=TransferActorState.decode(item.get(MmmComponents.TRANSFER_ACTOR));
        return new ContainerTransferActor.BlockInventory(level.dimension().identifier().toString(),position.getX(),position.getY(),position.getZ(),slot,identity.instanceId(),identity.generation());
    }
    static String describe(ItemStack item){
        if(item.isEmpty())return "空槽";
        if(item.getItem() instanceof GasContainerItem){var contents=GasContainerItem.contents(item);return contents==null?"空气体批次":mmm.units.UnitFormat.volumeNanos(contents.inventory().standardVolumeNanos())+" · 标准体积";}
        if(item.getItem() instanceof GranularContainerItem){var contents=GranularContainerItem.unvalidatedContents(item);return contents==null?"空颗粒批次":mmm.units.UnitFormat.solidVolume(contents.totalSolidVolumeSvq());}
        long volume=MaterialBucketItem.volume(item);if(item.is(net.minecraft.world.item.Items.WATER_BUCKET))volume=1_000_000_000L;
        return volume>0?mmm.units.UnitFormat.volumeNanos(volume):"空液体批次";
    }
}
