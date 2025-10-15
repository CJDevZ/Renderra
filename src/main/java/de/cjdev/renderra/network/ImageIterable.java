package de.cjdev.renderra.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public record ImageIterable(int[] palette, List<PixelWidth> pixelWidths, List<Component> sections) {

    public static ImageIterable read(FriendlyByteBuf byteBuf, boolean pretty) {
        return read(byteBuf, pretty, false);
    }

    public static ImageIterable read(FriendlyByteBuf byteBuf, boolean pretty, boolean forClient) {
        int paletteLen = byteBuf.readVarInt();
        int[] palette = new int[paletteLen];
        for (int i = 0; i < paletteLen; ++i) {
            palette[i] = byteBuf.readVarInt();
        }

        List<Component> sectionList = new ArrayList<>();
        List<PixelWidth> pixelWidths = new ArrayList<>();
        String pixel = "A";
        if (pretty) pixel += ".";
        while (byteBuf.isReadable()) {
            var consecutive = byteBuf.readVarInt();
            var colorIndex = byteBuf.readVarInt();
            if (!forClient) pixelWidths.add(new PixelWidth(consecutive, colorIndex));
            sectionList.add(Component.literal(pixel.repeat(consecutive)).withColor(palette[colorIndex]));
        }
        return new ImageIterable(palette, pixelWidths, sectionList);
    }

    public record PixelWidth(int consecutive, int colorIndex) {
    }
}
