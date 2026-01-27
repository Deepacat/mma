package com.dayssky.mma.util;

import com.dayssky.mma.MMAClient;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import net.minecraft.core.BlockPos;

import java.io.IOException;

// Used for gson file save formatting, outputs inline [ x, y, z ]
public class BlockPosAdapter extends TypeAdapter<BlockPos> {
    @Override
    public void write(JsonWriter out, BlockPos v) throws IOException {
        out.jsonValue("[ " + v.getX() + ", " + v.getY() + ", " + v.getZ() + " ]");
    }

    @Override
    public BlockPos read(JsonReader in) throws IOException {
        in.beginArray();
        int x = in.nextInt();
        int y = in.nextInt();
        int z = in.nextInt();
        in.endArray();
        return new BlockPos(x, y, z);
    }
}