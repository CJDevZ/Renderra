package eu.cj4.renderra.api.video;

import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

import java.util.UUID;
import java.util.function.UnaryOperator;

public interface ScreenHolder {
    Display.ItemDisplay[] getScreens();
    Integer[] getScreenIDs();
    int getScreenCount();

    boolean addScreen(Display.ItemDisplay display);
    void addScreens(Display.ItemDisplay[] displays);

    boolean removeScreen(Display.ItemDisplay display);
    boolean hasNoScreens();

    boolean zeroScale();

    Display.@Nullable TextDisplay getSubtitleDisplay();
    void setSubtitleDisplay(Display.@Nullable TextDisplay subtitleDisplay);

    Display.@Nullable ItemDisplay getMainDisplay();
    @Nullable Entity getSoundSource();

    UUID[] getScreenUUIDs();

    ScreenMeta screenMeta();
    void screenMeta(ScreenMeta screenMeta);
    void updateScreenMeta(UnaryOperator<ScreenMeta> operator);
    float scale();
    void scale(float scale);
    int width();
    void width(int width);
    int height();
    void height(int height);
    void resolution(int width, int height);
    double heightPerScreen();
    void heightPerScreen(double heightPerScreen);

    boolean eatDirty();

    void close();
}
