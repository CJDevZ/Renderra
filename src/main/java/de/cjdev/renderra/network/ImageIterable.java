package de.cjdev.renderra.network;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.network.FriendlyByteBuf;

import java.awt.image.BufferedImage;

public record ImageIterable(int[] sections) {

    public static int[] parseImage(BufferedImage image, int width, int height, int heightOffset) {
        return image.getRGB(0, heightOffset, width, height - heightOffset, null, 0, width);
    }

    public static int[] read(FriendlyByteBuf byteBuf) {
        IntList sectionList = new IntArrayList();
        int pixelCount = byteBuf.readVarInt();
        for (int i = 0; i < pixelCount; i++) {
            sectionList.add(byteBuf.readVarInt());
        }
        return sectionList.toIntArray();
    }
}
