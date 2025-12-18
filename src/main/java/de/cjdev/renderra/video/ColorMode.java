package de.cjdev.renderra.video;

import de.cjdev.renderra.network.ImageIterable;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

import java.util.List;

public enum ColorMode {
    FULL {
        @Override
        public int getMappedColor(int color) {
            return color;
        }

        @Override
        public void readSectionByBuffer(FriendlyByteBuf byteBuf, int[] palette, boolean pretty, List<Component> sectionList, List<ImageIterable.PixelWidth> pixelWidths) {
            String pixel = pretty ? "A." : "A";
            while (byteBuf.isReadable()) {
                var consecutive = byteBuf.readVarInt();
                var colorIndex = byteBuf.readVarInt();
                if (consecutive < 0) continue;
                pixelWidths.add(new ImageIterable.PixelWidth(consecutive, colorIndex));
                sectionList.add(Component.literal(pixel.repeat(consecutive)).withColor(palette[colorIndex]));
            }
        }

        @Override
        public void processPixels(ImageProcessingResult imageProcessingResult, List<Component> sections, boolean pretty) {
            String pixel = pretty ? "A." : "A";
            int[] palette = imageProcessingResult.palette();
            for (ImageIterable.PixelWidth pixelWidth : imageProcessingResult.rleSegments()) {
                sections.add(Component.literal(pixel.repeat(pixelWidth.consecutive())).withColor(palette[pixelWidth.colorIndex()]));
            }
        }
    },
    FIFTEEN_BIT {
        @Override
        public int getMappedColor(int color) {
            int COLORS = 30;
            int red   = Math.round(((color >> 16) & 0xFF) / 255f * (COLORS - 1));
            int green = Math.round(((color >> 8)  & 0xFF) / 255f * (COLORS - 1));
            int blue  = Math.round(( color        & 0xFF) / 255f * (COLORS - 1));
            return red + green * COLORS + blue * COLORS * COLORS;
        }

        @Override
        public void readSectionByBuffer(FriendlyByteBuf byteBuf, int[] palette, boolean pretty, List<Component> sectionList, List<ImageIterable.PixelWidth> pixelWidths) {
            StringBuilder pixels = new StringBuilder();
            while (byteBuf.isReadable()) {
                var consecutive = byteBuf.readVarInt();
                var paletteIndex = byteBuf.readVarInt();
                if (consecutive < 0) continue;
                String pixel = String.valueOf((char)(palette[paletteIndex] + 12832));
                if (pretty) pixel += '.';
                pixelWidths.add(new ImageIterable.PixelWidth(consecutive, paletteIndex));

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
        }

        @Override
        public void processPixels(ImageProcessingResult imageProcessingResult, List<Component> sections, boolean pretty) {
            StringBuilder pixels = new StringBuilder();
            int[] palette = imageProcessingResult.palette();
            for (ImageIterable.PixelWidth pw : imageProcessingResult.rleSegments()) {
                int consecutive = pw.consecutive();
                int paletteIndex = pw.colorIndex();

                // 15-bit mapping logic
                String pixel = String.valueOf((char) (palette[paletteIndex] + 12832));
                if (pretty) pixel += ".";
                int pixelChars = pixel.length();

                while (consecutive > 0) {
                    int remaining = Short.MAX_VALUE - pixels.length();
                    int pixelsFit = remaining / pixelChars;

                    if (pixelsFit == 0) {
                        sections.add(Component.literal(pixels.toString()));
                        pixels.setLength(0);
                        continue;
                    }

                    int toWrite = Math.min(pixelsFit, consecutive);
                    pixels.repeat(pixel, toWrite);
                    consecutive -= toWrite;

                    if (pixels.length() >= Short.MAX_VALUE - (pixelChars - 1)) {
                        sections.add(Component.literal(pixels.toString()));
                        pixels.setLength(0);
                    }
                }
            }
            if (!pixels.isEmpty()) sections.add(Component.literal(pixels.toString()));
        }
    };

    public abstract int getMappedColor(int color);
    public abstract void readSectionByBuffer(FriendlyByteBuf byteBuf, int[] palette, boolean pretty, List<Component> sectionList, List<ImageIterable.PixelWidth> pixelWidths);
    public abstract void processPixels(ImageProcessingResult imageProcessingResult, List<Component> sections, boolean pretty);
}
