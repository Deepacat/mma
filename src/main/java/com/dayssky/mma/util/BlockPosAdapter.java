package com.dayssky.mma.util;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import net.minecraft.core.BlockPos;

import java.io.IOException;

public class BlockPosAdapter extends TypeAdapter<BlockPos> {

    @Override
    public void write(JsonWriter out, BlockPos value) throws IOException {
        if (value == null) {
            out.nullValue();
            return;
        }
        // Proper streaming serialization as a JSON array
        out.beginArray();
        out.value(value.getX());
        out.value(value.getY());
        out.value(value.getZ());
        out.endArray();
    }

    @Override
    public BlockPos read(JsonReader in) throws IOException {
        if (in.peek() == com.google.gson.stream.JsonToken.NULL) {
            in.nextNull();
            return null;
        }
        in.beginArray();
        int x = in.nextInt();
        int y = in.nextInt();
        int z = in.nextInt();
        in.endArray();
        return new BlockPos(x, y, z);
    }
}