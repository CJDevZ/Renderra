package de.cjdev.renderra.video;

import de.cjdev.renderra.network.ImageIterable;

import java.util.List;

public record ImageProcessingResult(
        int[] palette,
        List<ImageIterable.PixelWidth> rleSegments
) {}
