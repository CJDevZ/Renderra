package eu.cj4.renderra.api.video;

public enum ReplayMode {
    ONCE(0xFFFFFF),
    LOOP(0x55FF55);

    public static final ReplayMode[] VALUES = ReplayMode.values();

    public final int buttonColor;

    ReplayMode(int buttonColor) {
        this.buttonColor = buttonColor;
    }
}
