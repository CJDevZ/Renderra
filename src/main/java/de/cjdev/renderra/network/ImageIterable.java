package de.cjdev.renderra.network;

import de.cjdev.renderra.video.ColorMode;
import de.cjdev.renderra.video.ImageProcessingResult;
import it.unimi.dsi.fastutil.ints.AbstractInt2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntLinkedOpenHashMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

public record ImageIterable(ImageProcessingResult processingResult, List<Component> sections) {

    public static ImageIterable parseImage(ColorMode colorMode, BufferedImage image, int width, int height, int heightOffset, boolean pretty) {
        AbstractInt2IntMap paletteMap = new Int2IntLinkedOpenHashMap();
        paletteMap.defaultReturnValue(-1);
        int paletteIndex = 0;

        List<PixelWidth> pixelWidths = new ArrayList<>();
        int lastColor = -1;
        int consecutivePixels = 0;

        for (int y = heightOffset; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                int rgb = colorMode.getMappedColor(image.getRGB(x, y) & 0xFFFFFF);

                // 1. Build Palette (Primitive check)
                if (!paletteMap.containsKey(rgb)) {
                    paletteMap.put(rgb, paletteIndex++);
                }

                // 2. Build RLE Segments
                if (lastColor == -1) {
                    lastColor = rgb;
                }

                if (rgb == lastColor) {
                    ++consecutivePixels;
                } else {
                    pixelWidths.add(new PixelWidth(consecutivePixels, paletteMap.get(lastColor)));
                    consecutivePixels = 1;
                    lastColor = rgb;
                }
            }
        }

        // Flush last segment
        if (consecutivePixels > 0) {
            pixelWidths.add(new PixelWidth(consecutivePixels, paletteMap.get(lastColor)));
        }

        // 3. Generate Sections via ColorMode
        int[] paletteArray = paletteMap.keySet().toIntArray();
        ImageProcessingResult imageProcessingResult = new ImageProcessingResult(paletteArray, pixelWidths);
        List<Component> sections = new ArrayList<>();
        colorMode.processPixels(imageProcessingResult, sections, pretty);

        return new ImageIterable(new ImageProcessingResult(paletteArray, pixelWidths), sections);
    }

    public static ImageIterable read(FriendlyByteBuf byteBuf, ColorMode colorMode, boolean pretty) {
        int paletteLen = byteBuf.readVarInt();
        int[] palette = new int[paletteLen];
        for (int i = 0; i < paletteLen; ++i) {
            palette[i] = byteBuf.readVarInt();
        }

        List<Component> sectionList = new ArrayList<>();
        List<PixelWidth> pixelWidths = new ArrayList<>();
        colorMode.readSectionByBuffer(byteBuf, palette, pretty, sectionList, pixelWidths);
        return new ImageIterable(new ImageProcessingResult(palette, pixelWidths), sectionList);
    }

    public record PixelWidth(int consecutive, int colorIndex) {
    }
}
