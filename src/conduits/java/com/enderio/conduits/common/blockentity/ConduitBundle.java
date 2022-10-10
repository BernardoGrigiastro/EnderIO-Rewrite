package com.enderio.conduits.common.blockentity;

import com.enderio.api.UseOnly;
import com.enderio.api.conduit.ConduitTypes;
import com.enderio.api.conduit.IConduitType;
import com.enderio.api.conduit.IExtendedConduitData;
import com.enderio.api.conduit.NodeIdentifier;
import com.enderio.conduits.client.ConduitClientSetup;
import com.enderio.conduits.common.blockentity.action.RightClickAction;
import com.enderio.conduits.common.blockentity.connection.DynamicConnectionState;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.INBTSerializable;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.util.thread.EffectiveSide;

import java.util.*;

public final class ConduitBundle implements INBTSerializable<CompoundTag> {

    //Do not change this value unless you fix the OffsetHelper
    public static final int MAX_CONDUIT_TYPES = 9;

    private final Map<Direction, ConduitConnection> connections = new EnumMap<>(Direction.class);

    private final List<IConduitType<?>> types = new ArrayList<>();

    //fill back after world save
    private final Map<IConduitType<?>, NodeIdentifier<?>> nodes = new HashMap<>();
    private final Runnable scheduleSync;
    private final BlockPos pos;

    private final Map<Direction, BlockState> facadeTextures = new EnumMap<>(Direction.class);

    public ConduitBundle(Runnable scheduleSync, BlockPos pos) {
        this.scheduleSync = scheduleSync;
        for (Direction value : Direction.values()) {
            connections.put(value, new ConduitConnection());
        }
        this.pos = pos;
    }

    /**
     * @param type
     * @return the type that is now not in this bundle
     */
    public RightClickAction addType(Level level, IConduitType<?> type, Player player) {
        if (types.size() == MAX_CONDUIT_TYPES)
            return new RightClickAction.Blocked();
        if (types.contains(type))
            return new RightClickAction.Blocked();
        //upgrade a conduit
        Optional<? extends IConduitType<?>> first = types.stream().filter(existingConduit -> existingConduit.canBeReplacedBy(type)).findFirst();
        NodeIdentifier<?> node = new NodeIdentifier<>(pos, type.createExtendedConduitData(level, pos));
        if (first.isPresent()) {
            int index = types.indexOf(first.get());
            types.set(index, type);
            var prevNode = nodes.put(type, node);
            if (prevNode != null) {
                prevNode.getExtendedConduitData().onRemoved(type, level, pos);
                if (!level.isClientSide() && prevNode.getGraph() != null) {
                    prevNode.getGraph().remove(prevNode);
                }
            }
            node.getExtendedConduitData().onCreated(type, level, pos, player);
            connections.values().forEach(connection -> connection.clearType(index));
            scheduleSync.run();
            return new RightClickAction.Upgrade(first.get());
        }
        //some conduit says no (like higher energy conduit)
        if (types.stream().anyMatch(existingConduit -> !existingConduit.canBeInSameBlock(type)))
            return new RightClickAction.Blocked();
        //sort the list, so order is consistent
        int id = ConduitTypeSorter.getSortIndex(type);
        var addBefore = types.stream().filter(existing -> ConduitTypeSorter.getSortIndex(existing) > id).findFirst();
        if (addBefore.isPresent()) {
            var value = types.indexOf(addBefore.get());
            types.add(value, type);
            nodes.put(type, node);
            node.getExtendedConduitData().onCreated(type, level, pos, player);
            for (Direction direction: Direction.values()) {
                connections.get(direction).addType(value);
            }
        } else {
            types.add(type);
            nodes.put(type, node);
            node.getExtendedConduitData().onCreated(type, level, pos, player);
        }
        scheduleSync.run();
        return new RightClickAction.Insert();
    }

    void onLoad(Level level, BlockPos pos) {
        for (IConduitType<?> type : types) {
            getNodeFor(type).getExtendedConduitData().onCreated(type, level, pos, null);
        }
    }

    /**
     * @param type
     * @throws IllegalArgumentException if this type is not in the conduitbundle and we are in dev env
     * @return if this bundle is empty and the block has to be removed
     */
    public boolean removeType(Level level, IConduitType<?> type) {
        int index = types.indexOf(type);
        if (index == -1) {
            if (!FMLLoader.isProduction()) {
                throw new IllegalArgumentException("Conduit: " + ConduitTypes.REGISTRY.get().getKey(type) + " is not present in conduit bundle "
                    + Arrays.toString(types.stream().map(existingType -> ConduitTypes.REGISTRY.get().getKey(existingType)).toArray()));
            }
            return types.isEmpty();
        }
        for (Direction direction: Direction.values()) {
            connections.get(direction).removeType(index);
        }
        if (EffectiveSide.get().isServer())
            removeNodeFor(level, type);
        types.remove(index);
        scheduleSync.run();
        return types.isEmpty();
    }

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        ListTag listTag = new ListTag();
        for (IConduitType<?> type : types) {
            listTag.add(StringTag.valueOf(ConduitTypes.getRegistry().getKey(type).toString()));
        }
        tag.put("types", listTag);
        CompoundTag connectionsTag = new CompoundTag();
        for (Direction dir: Direction.values()) {
            connectionsTag.put(dir.getName(), connections.get(dir).serializeNBT());
        }
        tag.put("connections", connectionsTag);
        CompoundTag facades = new CompoundTag();
        for (Map.Entry<Direction, BlockState> entry : facadeTextures.entrySet()) {
            Tag blockStateTag = BlockState.CODEC.encode(entry.getValue(), NbtOps.INSTANCE, new CompoundTag()).get().left().orElse(new CompoundTag());
            facades.put(entry.getKey().getName(), blockStateTag);
        }
        tag.put("facades", facades);
        if (EffectiveSide.get().isServer()) {
            ListTag nodeTag = new ListTag();
            for (var entry : nodes.entrySet()) {
                var data = entry.getValue().getExtendedConduitData();
                if (data.syncDataToClient()) {
                    CompoundTag dataTag = new CompoundTag();
                    dataTag.putString("type", ConduitTypes.getRegistry().getKey(entry.getKey()).toString());
                    dataTag.put("data", data.serializeNBT());
                    nodeTag.add(dataTag);
                }
            }
            if (!nodeTag.isEmpty()) {
                tag.put("nodes", nodeTag);
            }
        }
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        types.clear();
        ListTag typesTag = nbt.getList("types", Tag.TAG_STRING);
        //this is used to shift connections back if a ConduitType was removed from
        List<Integer> invalidTypes = new ArrayList<>();
        for (int i = 0; i < typesTag.size(); i++) {
            StringTag stringTag = (StringTag)typesTag.get(i);
            IConduitType<?> type = ConduitTypes.getRegistry().getValue(ResourceLocation.tryParse(stringTag.getAsString()));
            if (type == null) {
                invalidTypes.add(i);
                continue;
            }
            types.add(type);
        }
        CompoundTag connectionsTag = nbt.getCompound("connections");
        for (Direction dir: Direction.values()) {
            connections.get(dir).deserializeNBT(connectionsTag.getCompound(dir.getName()));
            for (Integer invalidType : invalidTypes) {
                connections.get(dir).removeType(invalidType);
            }
            //remove backwards to not shift list further
            for (int i = invalidTypes.size() - 1; i >= 0; i--) {
                connections.get(dir).removeType(invalidTypes.get(i));
            }
        }
        facadeTextures.clear();
        CompoundTag facades = nbt.getCompound("facades");
        for (Direction direction: Direction.values()) {
            if (facades.contains(direction.getName())) {
                facadeTextures.put(direction, BlockState.CODEC.decode(NbtOps.INSTANCE, facades.getCompound(direction.getName())).get().left().get().getFirst());
            }
        }
        for (Map.Entry<Direction, BlockState> entry : facadeTextures.entrySet()) {
            Tag blockStateTag = BlockState.CODEC.encode(entry.getValue(), NbtOps.INSTANCE, new CompoundTag()).get().left().orElse(new CompoundTag());
            facades.put(entry.getKey().getName(), blockStateTag);
        }
        nodes.entrySet().removeIf(entry -> !types.contains(entry.getKey()));
        //push change from clientsync to node TODO: move to aftersyncrunnable
        if (EffectiveSide.get().isServer()) {
            for (IConduitType<?> type: types) {
                if (nodes.containsKey(type)) {
                    for (Direction direction : Direction.values()) {
                        if (getConnection(direction).getConnectionState(type, this) instanceof DynamicConnectionState dyn) {
                            nodes.get(type).pushState(direction, dyn.isInsert() ? dyn.insert() : null, dyn.isExtract() ? dyn.extract() : null);
                        }
                    }
                }
            }
        } else {
            types.forEach(type -> {
                if (!nodes.containsKey(type))
                    nodes.put(type, new NodeIdentifier<>(pos, type.createExtendedConduitData(ConduitClientSetup.getClientLevel(), pos)));
            });
            if (nbt.contains("nodes")) {
                ListTag nodesTag = nbt.getList("nodes", Tag.TAG_COMPOUND);
                for (Tag tag : nodesTag) {
                    CompoundTag cmp = (CompoundTag) tag;
                    nodes.get(ConduitTypes.getRegistry().getValue(new ResourceLocation(cmp.getString("type")))).getExtendedConduitData().deserializeNBT(cmp.getCompound("data"));
                }
            }
        }

    }

    //TODO: RFC
    /**
     * IMO this should only be used on the client, as this exposes renderinformation, for gamelogic: helper should be created here imo.
     * @param direction
     * @return
     */
    public ConduitConnection getConnection(Direction direction) {
        return connections.get(direction);
    }
    public List<IConduitType<?>> getTypes() {
        return types;
    }

    public boolean hasFacade(Direction direction) {
        return facadeTextures.containsKey(direction);
    }

    public Optional<BlockState> getFacade(Direction direction) {
        return Optional.ofNullable(facadeTextures.get(direction));
    }

    public void setFacade(BlockState facade, Direction direction) {
        facadeTextures.put(direction, facade);
    }

    //TODO, make this method more useable

    public void connectTo(Direction direction, IConduitType<?> type, boolean end) {
        getConnection(direction).connectTo(nodes.get(type), direction,types.indexOf(type), end);
        scheduleSync.run();
    }

    public boolean disconnectFrom(Direction direction, IConduitType<?> type) {
        if (types.contains(type)) {
            getConnection(direction).disconnectFrom(types.indexOf(type));
            scheduleSync.run();
            return true;
        }
        return false;
    }

    public NodeIdentifier<?> getNodeFor(IConduitType<?> type) {
        return nodes.get(type);
    }

    public void setNodeFor(IConduitType<?> type, NodeIdentifier<?> node) {
        nodes.put(type, node);
        for (var direction : Direction.values()) {
            ConduitConnection connection = connections.get(direction);
            int index = connection.getConnectedTypes(this).indexOf(type);
            if (index >= 0) {
                var state = connection.getConnectionState(index);
                if (state instanceof DynamicConnectionState dynamicState) {
                    node.pushState(direction, dynamicState.isInsert() ? dynamicState.insert() : null, dynamicState.isExtract() ? dynamicState.extract() : null);
                }
            }
        }
    }

    public void removeNodeFor(Level level, IConduitType<?> type) {
        NodeIdentifier<?> node = nodes.get(type);
        node.getExtendedConduitData().onRemoved(type, level, pos);
        if (node.getGraph() != null) {
            node.getGraph().remove(node);
        }
        nodes.remove(type);
    }

    @UseOnly(LogicalSide.CLIENT)
    public ConduitBundle deepCopy() {
        var bundle = new ConduitBundle(() -> {}, pos);
        bundle.types.addAll(types);
        connections.forEach((dir, connection) ->
            bundle.connections.put(dir, connection.deepCopy())
        );
        bundle.facadeTextures.putAll(facadeTextures);
        nodes.forEach((type, node) -> bundle.setNodeFor(type, new NodeIdentifier<>(node.getPos(), node.getExtendedConduitData().deepCopy())));
        return bundle;
    }
}