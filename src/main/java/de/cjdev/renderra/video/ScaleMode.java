package de.cjdev.renderra.video;

import java.awt.*;

public enum ScaleMode {
    NEAREST_NEIGHBOR {
        @Override
        public Object getRenderingHint() {
            return RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR;
        }
    },
    BILINEAR {
        @Override
        public Object getRenderingHint() {
            return RenderingHints.VALUE_INTERPOLATION_BILINEAR;
        }
    },
    BICUBIC {
        @Override
        public Object getRenderingHint() {
            return RenderingHints.VALUE_INTERPOLATION_BICUBIC;
        }
    };

    public static final ScaleMode[] VALUES = ScaleMode.values();

    public abstract Object getRenderingHint();
}
