package de.cjdev.renderra.network;

import de.cjdev.renderra.ColorMode;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import org.luaj.vm2.ast.Str;

import java.util.ArrayList;
import java.util.List;

public record ImageIterable(int[] palette, List<PixelWidth> pixelWidths, List<Component> sections) {

    public static ImageIterable read(FriendlyByteBuf byteBuf, ColorMode colorMode, boolean pretty) {
        return read(byteBuf, colorMode, pretty, false);
    }

    public static ImageIterable read(FriendlyByteBuf byteBuf, ColorMode colorMode, boolean pretty, boolean forClient) {
        int paletteLen = byteBuf.readVarInt();
        int[] palette = new int[paletteLen];
        for (int i = 0; i < paletteLen; ++i) {
            palette[i] = byteBuf.readVarInt();
        }

        List<Component> sectionList = new ArrayList<>();
        List<PixelWidth> pixelWidths = new ArrayList<>();
        if (colorMode == ColorMode.FIFTEEN_BIT) {
            StringBuilder pixels = new StringBuilder();
            while (byteBuf.isReadable()) {
                var consecutive = byteBuf.readVarInt();
                var colorIndex = byteBuf.readVarInt();
                if (consecutive < 0) continue;
                String pixel = String.valueOf((char)(palette[colorIndex] + 12832));
                if (pretty) pixel += '.';
                if (!forClient) pixelWidths.add(new PixelWidth(consecutive, colorIndex));

                int pixelChars = pixel.length();

                while (consecutive > 0) {
                    // How many pixels remain in this section
                    int remainingChars = Short.MAX_VALUE - pixels.length();

                    // How many pixels can fit
                    int pixelsFit = remainingChars / pixelChars;

                    if (pixelsFit == 0) {
                        // No room on this line, flush it
                        sectionList.add(Component.literal(pixels.toString()));
                        pixels.setLength(0);
                        continue;
                    }

                    int toWrite = Math.min(pixelsFit, consecutive);
                    pixels.repeat(pixel, toWrite);

                    consecutive -= toWrite;

                    if (pixels.length() == Short.MAX_VALUE) {
                        sectionList.add(Component.literal(pixels.toString()));
                        pixels.setLength(0);
                    }
                }
            }
            if (!pixels.isEmpty()) {
                sectionList.add(Component.literal(pixels.toString()));
            }
        } else {
            String pixel = "A";
            if (pretty) pixel += '.';
            while (byteBuf.isReadable()) {
                var consecutive = byteBuf.readVarInt();
                var colorIndex = byteBuf.readVarInt();
                if (consecutive < 0) continue;
                if (!forClient) pixelWidths.add(new PixelWidth(consecutive, colorIndex));
                sectionList.add(Component.literal(pixel.repeat(consecutive)).withColor(palette[colorIndex]));
            }
        }
        return new ImageIterable(palette, pixelWidths, sectionList);
    }

    public record PixelWidth(int consecutive, int colorIndex) {
    }
}
