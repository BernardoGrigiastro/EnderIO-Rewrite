package com.enderio.api.conduit.ticker;

import com.enderio.api.conduit.IExtendedConduitData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.capabilities.Capability;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public abstract class ICapabilityAwareConduitTicker<T> implements IIOAwareConduitTicker {

    @Override
    public final void tickColoredGraph(List<Connection> inserts, List<Connection> extracts, ServerLevel level) {
        List<CapabilityConnection> insertCaps = new ArrayList<>();
        for (Connection insert : inserts) {
            Optional.ofNullable(level.getBlockEntity(insert.move()))
                .flatMap(b -> b.getCapability(getCapability(), insert.dir().getOpposite()).resolve())
                .ifPresent(cap -> insertCaps.add(new CapabilityConnection(cap, insert.data())));
        }
        if (!insertCaps.isEmpty()) {
            List<CapabilityConnection> extractCaps = new ArrayList<>();

            for (Connection extract : extracts) {
                Optional.ofNullable(level.getBlockEntity(extract.move()))
                    .flatMap(b -> b.getCapability(getCapability(), extract.dir().getOpposite()).resolve())
                    .ifPresent(cap -> extractCaps.add(new CapabilityConnection(cap, extract.data())));
            }
            if (!extractCaps.isEmpty()) {
                tickCapabilityGraph(insertCaps, extractCaps, level);
            }
        }
    }

    @Override
    public boolean canConnectTo(Level level, BlockPos conduitPos, Direction direction) {
        return Optional.ofNullable(level.getBlockEntity(conduitPos.relative(direction))).flatMap(be -> be.getCapability(getCapability(), direction.getOpposite()).resolve()).isPresent();
    }

    protected abstract void tickCapabilityGraph(List<CapabilityConnection> inserts, List<CapabilityConnection> extracts, ServerLevel level);
    protected abstract Capability<T> getCapability();

    public class CapabilityConnection {
        public final T cap;
        public final IExtendedConduitData<?> data;
        private CapabilityConnection(T cap, IExtendedConduitData<?> data) {
            this.cap = cap;
            this.data = data;
        }
    }
}