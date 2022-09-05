package com.enderio.conduits.common.blockentity;

import com.enderio.api.UseOnly;
import com.enderio.api.conduit.IConduitScreenData;
import com.enderio.api.conduit.IConduitType;
import com.enderio.api.conduit.NodeIdentifier;
import com.enderio.conduits.common.ConduitShape;
import com.enderio.conduits.common.blockentity.action.RightClickAction;
import com.enderio.conduits.common.blockentity.connection.DynamicConnectionState;
import com.enderio.conduits.common.blockentity.connection.IConnectionState;
import com.enderio.conduits.common.menu.ConduitMenu;
import com.enderio.conduits.common.network.ConduitSavedData;
import com.enderio.core.common.blockentity.EnderBlockEntity;
import com.enderio.core.common.sync.NBTSerializableDataSlot;
import com.enderio.core.common.sync.SyncMode;
import dev.gigaherz.graph3.Graph;
import dev.gigaherz.graph3.GraphObject;
import dev.gigaherz.graph3.Mergeable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.client.model.data.ModelProperty;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class ConduitBlockEntity extends EnderBlockEntity {

    public static final ModelProperty<ConduitBundle> BUNDLE_MODEL_PROPERTY = new ModelProperty<>();

    private ConduitShape shape = new ConduitShape();

    private final ConduitBundle bundle;
    @UseOnly(LogicalSide.CLIENT)
    private ConduitBundle clientBundle;

    public ConduitBlockEntity(BlockEntityType<?> type, BlockPos worldPosition, BlockState blockState) {
        super(type, worldPosition, blockState);
        bundle = new ConduitBundle(this::scheduleTick, worldPosition);
        clientBundle = bundle.deepCopy();
        add2WayDataSlot(new NBTSerializableDataSlot<>(this::getBundle, SyncMode.WORLD));
        addAfterSyncRunnable(this::updateClient);
    }

    public void updateClient() {
        clientBundle = bundle.deepCopy();
        updateShape();
        requestModelDataUpdate();
        level.setBlocksDirty(getBlockPos(), Blocks.AIR.defaultBlockState(), getBlockState());
    }

    private void scheduleTick() {
        if (!level.isClientSide())
        //    level.scheduleTick(getBlockPos(), ConduitBlocks.CONDUIT.get(), 0);
        setChanged();
    }

    @Override
    public void onLoad() {
        if (!level.isClientSide()) {
            loadFromSavedData();
            sync();
        }
    }

    public boolean stillValid(Player pPlayer) {
        if (this.level.getBlockEntity(this.worldPosition) != this)
            return false;
        return pPlayer.distanceToSqr(this.worldPosition.getX() + 0.5D, this.worldPosition.getY() + 0.5D, this.worldPosition.getZ() + 0.5D) <= pPlayer.getAttributeValue(
            ForgeMod.REACH_DISTANCE.get());
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (level instanceof ServerLevel serverLevel) {
            ConduitSavedData savedData = ConduitSavedData.get(serverLevel);
            for (IConduitType type : bundle.getTypes()) {
                savedData.putUnloadedNodeIdentifier(type, this.worldPosition, bundle.getNodeFor(type));
            }
        }
    }

    public void everyTick() {
        serverTick();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("conduit", bundle.serializeNBT());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        bundle.deserializeNBT(tag.getCompound("conduit"));
    }

    @Override
    public ModelData getModelData() {
        return ModelData.builder().with(BUNDLE_MODEL_PROPERTY, clientBundle).build();
    }

    public RightClickAction addType(IConduitType type) {
        RightClickAction action = bundle.addType(type);
        //something has changed
        if (action.hasChanged()) {
            List<GraphObject<Mergeable.Dummy>> nodes = new ArrayList<>();
            for (Direction dir: Direction.values()) {
                BlockEntity blockEntity = level.getBlockEntity(getBlockPos().relative(dir));
                if (blockEntity != null) {
                    //add possible connections if you are upgrading or inserting
                    if (blockEntity instanceof ConduitBlockEntity conduit && conduit.connectTo(dir.getOpposite(), type)) {
                        nodes.add(conduit.bundle.getNodeFor(type));
                        connect(dir, type);
                    } else if(blockEntity.getCapability(CapabilityEnergy.ENERGY).isPresent()) {
                        connectEnd(dir, type);
                    }
                }
            }
            if (level instanceof ServerLevel serverLevel) {
                Graph.integrate(bundle.getNodeFor(type), nodes);
                ConduitSavedData.addPotentialGraph(type, Objects.requireNonNull(bundle.getNodeFor(type).getGraph()), serverLevel);
            }
            if (action instanceof RightClickAction.Upgrade upgrade) {
                removeNeighborConnections(upgrade.getNotInConduit());
            }
            updateShape();
        }
        return action;
    }

    public boolean removeType(IConduitType type) {
        boolean shouldRemove =  bundle.removeType(type);
        //something has changed
        removeNeighborConnections(type);
        updateShape();
        return shouldRemove;
    }

    public void removeNeighborConnections(IConduitType type) {
        NodeIdentifier nodeFor = bundle.getNodeFor(type);
        for (Direction dir: Direction.values()) {
            BlockEntity blockEntity = level.getBlockEntity(getBlockPos().relative(dir));
            if (blockEntity instanceof ConduitBlockEntity conduit) {
                if (conduit.disconnect(dir.getOpposite(), type)) {
                    conduit.updateShape();
                }
            }
        }
        if (level instanceof ServerLevel serverLevel && nodeFor.getGraph() != null) {
            nodeFor.getGraph().remove(nodeFor);

            for (Direction dir: Direction.values()) {
                BlockEntity blockEntity = level.getBlockEntity(getBlockPos().relative(dir));
                if (blockEntity instanceof ConduitBlockEntity conduit) {
                    Optional.ofNullable(conduit.bundle.getNodeFor(type))
                        .map(NodeIdentifier::getGraph)
                        .filter(Objects::nonNull)
                        .ifPresent(graph -> ConduitSavedData.addPotentialGraph(type, graph, serverLevel));
                }
            }
        }
        bundle.removeNodeFor(type);
    }

    private void updateShape() {
        shape.updateConduit(bundle);
    }

    private void loadFromSavedData() {
        if (!(level instanceof ServerLevel)) return;
        ConduitSavedData savedData = ConduitSavedData.get((ServerLevel) level);
        for (IConduitType type : bundle.getTypes()) {
            NodeIdentifier node = savedData.takeUnloadedNodeIdentifier(type, this.worldPosition);
            bundle.setNodeFor(type, node);
        }
    }

    public static boolean isDifferent(IConduitType first, IConduitType second) {
        return first != second;
    }

    /**
     *
     * @param direction the Direction to connect to
     * @param type the type to be connected
     * @return true if a connection happens
     */
    private boolean connectTo(Direction direction, IConduitType type) {
        if (!bundle.getTypes().contains(type))
            return false;
        connect(direction, type);
        return true;
    }

    private void connect(Direction direction, IConduitType type) {
        bundle.connectTo(direction, type, false);
        updateClient();
    }

    private void connectEnd(Direction direction, IConduitType type) {
        bundle.connectTo(direction, type, true);
        updateClient();
    }

    private boolean disconnect(Direction direction, IConduitType type) {
        if (bundle.disconnectFrom(direction, type)) {
            updateClient();
            return true;
        }
        return false;
    }

    public ConduitBundle getBundle() {
        return bundle;
    }

    public ConduitBundle getClientBundle() {
        return clientBundle;
    }

    public ConduitShape getShape() {
        return shape;
    }

    public MenuProvider menuProvider(Direction direction, IConduitType type) {
        return new ConduitMenuProvider(direction, type);
    }

    private class ConduitMenuProvider implements MenuProvider {

        private final Direction direction;
        private final IConduitType type;

        private ConduitMenuProvider(Direction direction, IConduitType type) {
            this.direction = direction;
            this.type = type;
        }

        @Override
        public Component getDisplayName() {
            return getBlockState().getBlock().getName();
        }

        @Nullable
        @Override
        public AbstractContainerMenu createMenu(int pContainerId, Inventory pInventory, Player pPlayer) {
            return new ConduitMenu(ConduitBlockEntity.this, pInventory, pContainerId, direction, type);
        }
    }

    public IItemHandler getConduitItemHandler() {
        return new ConduitItemHandler();
    }

    private class ConduitItemHandler implements IItemHandlerModifiable {

        @Override
        public int getSlots() {
            return 3 * ConduitBundle.MAX_CONDUIT_TYPES * 6;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            if (slot >= getSlots())
                return ItemStack.EMPTY;
            SlotData data = SlotData.of(slot);
            if (data.conduitIndex() >= bundle.getTypes().size())
                return ItemStack.EMPTY;
            IConnectionState connectionState = bundle.getConnection(data.direction()).getConnectionState(data.conduitIndex());
            if (!(connectionState instanceof DynamicConnectionState dynamicConnectionState))
                return ItemStack.EMPTY;
            IConduitScreenData conduitData = bundle.getTypes().get(data.conduitIndex()).getData();
            if ((data.slotType() == SlotType.FILTER_EXTRACT && conduitData.hasFilterExtract())
                    || (data.slotType() == SlotType.FILTER_INSERT && conduitData.hasFilterInsert())
                    || (data.slotType() == SlotType.UPGRADE_EXTRACT && conduitData.hasUpgrade()))
                return dynamicConnectionState.getItem(data.slotType());
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            //see ItemStackHandler
            if (stack.isEmpty())
                return ItemStack.EMPTY;

            if (!isItemValid(slot, stack))
                return stack;

            ItemStack existing = getStackInSlot(slot);

            int limit = Math.min(getSlotLimit(slot), stack.getMaxStackSize());

            if (!existing.isEmpty()) {
                if (!ItemHandlerHelper.canItemStacksStack(stack, existing))
                    return stack;

                limit -= existing.getCount();
            }

            if (limit <= 0)
                return stack;

            boolean reachedLimit = stack.getCount() > limit;

            if (!simulate) {
                if (existing.isEmpty()) {
                    setStackInSlot(slot, reachedLimit ? ItemHandlerHelper.copyStackWithSize(stack, limit) : stack);
                } else {
                    existing.grow(reachedLimit ? limit : stack.getCount());
                }
            }
            return reachedLimit ? ItemHandlerHelper.copyStackWithSize(stack, stack.getCount()- limit) : ItemStack.EMPTY;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (amount == 0)
                return ItemStack.EMPTY;

            ItemStack existing = getStackInSlot(slot);

            if (existing.isEmpty())
                return ItemStack.EMPTY;

            int toExtract = Math.min(amount, existing.getMaxStackSize());

            if (existing.getCount() <= toExtract) {
                if (!simulate) {
                    setStackInSlot(slot, ItemStack.EMPTY);
                    return existing;
                } else {
                    return existing.copy();
                }
            } else {
                if (!simulate) {
                    setStackInSlot(slot, ItemHandlerHelper.copyStackWithSize(existing, existing.getCount() - toExtract));
                }
                return ItemHandlerHelper.copyStackWithSize(existing, toExtract);
            }
        }

        @Override
        public int getSlotLimit(int slot) {
            return slot % 3 == 2 ? 64 : 1;
        }

        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            //TODO implement
            return slot < getSlots();
        }

        @Override
        public void setStackInSlot(int slot, @NotNull ItemStack stack) {
            if (slot >= getSlots())
                return;
            SlotData data = SlotData.of(slot);
            if (data.conduitIndex() >= bundle.getTypes().size())
                return;
            ConduitConnection connection = bundle.getConnection(data.direction());
            IConduitScreenData conduitData = bundle.getTypes().get(data.conduitIndex()).getData();
            if ((data.slotType() == SlotType.FILTER_EXTRACT && conduitData.hasFilterExtract())
                    || (data.slotType() == SlotType.FILTER_INSERT && conduitData.hasFilterInsert())
                    || (data.slotType() == SlotType.UPGRADE_EXTRACT && conduitData.hasUpgrade())) {
                connection.setItem(data.slotType(), data.conduitIndex(), stack);
            }
        }
    }
}