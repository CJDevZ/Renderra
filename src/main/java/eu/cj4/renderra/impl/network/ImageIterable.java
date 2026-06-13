package eu.cj4.renderra.impl.network;

import java.awt.image.BufferedImage;

public record ImageIterable(int[] sections) {
    public static int[] parseImage(BufferedImage image, int width, int height, int heightOffset) {
        return image.getRGB(0, heightOffset, width, height - heightOffset, null, 0, width);
    }
}
