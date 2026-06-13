package eu.cj4.renderra.api.video;

public enum ColorFilter {
    FULL {
        @Override
        public boolean hasColorFilter() {
            return false;
        }

        @Override
        public int getFilteredColor(int color) {
            return color;
        }
    };

    public static final ColorFilter[] VALUES = ColorFilter.values();

    public abstract boolean hasColorFilter();
    public abstract int getFilteredColor(int color);
}
