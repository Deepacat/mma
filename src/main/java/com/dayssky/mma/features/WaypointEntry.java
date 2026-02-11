package com.dayssky.mma.features;

import net.minecraft.core.BlockPos;

public record WaypointEntry(BlockPos pos, boolean looted) {
    // equality based only on position – a chest can only have one entry
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof WaypointEntry other)) return false;
        return pos.equals(other.pos);
    }

    @Override
    public int hashCode() {
        return pos.hashCode();
    }
}