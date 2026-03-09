package de.cjdev.renderra.video;

import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.network.FriendlyByteBuf;

public enum ColorMode {
    FULL {
        @Override
        public int getMappedColor(int color) {
            return color;
        }

        @Override
        public void readSectionByBuffer(FriendlyByteBuf byteBuf, IntList sectionList) {
            while (byteBuf.isReadable()) {
                sectionList.add(byteBuf.readVarInt());
            }
        }
    };

    public static final ColorMode[] VALUES = ColorMode.values();

    public abstract int getMappedColor(int color);
    public abstract void readSectionByBuffer(FriendlyByteBuf byteBuf, IntList sectionList);
}
