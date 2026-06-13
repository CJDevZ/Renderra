package eu.cj4.renderra.impl.video;

import eu.cj4.renderra.api.video.ScaleMode;
import eu.cj4.renderra.api.video.ScreenMeta;
import eu.cj4.renderra.api.video.ScreenHolder;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Rotation;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.function.UnaryOperator;

public class ScreenHolderImpl implements ScreenHolder {
    private boolean dirty;
    private ScreenMeta screenMeta;
    private LinkedHashSet<Display.ItemDisplay> screens;
    private Display.TextDisplay subtitleDisplay;

    public ScreenHolderImpl() {
        this(new ScreenMeta(16, 9, 0d, 1f, Rotation.NONE, ScaleMode.BILINEAR));
    }

    public ScreenHolderImpl(ScreenMeta screenMeta) {
        this.screens = new LinkedHashSet<>();
        this.screenMeta = screenMeta;
    }

    public ScreenMeta screenMeta() {
        return this.screenMeta;
    }

    public void screenMeta(ScreenMeta screenMeta) {
        this.screenMeta = screenMeta;
    }

    @Override
    public void updateScreenMeta(UnaryOperator<ScreenMeta> operator) {
        this.screenMeta = operator.apply(this.screenMeta);
    }

    public int width() {
        return this.screenMeta.width();
    }

    public void width(int width) {
        if (width < 0) return;
        if (this.screenMeta.width() != width) this.dirty = true;
        this.screenMeta = this.screenMeta.setWidth(width);
    }

    public int height() {
        return this.screenMeta.height();
    }

    public void height(int height) {
        if (height < 0) return;
        if (this.screenMeta.height() != height) this.dirty = true;
        this.screenMeta = this.screenMeta.setHeight(height);
    }

    @Override
    public void resolution(int width, int height) {
        if (width < 0 || height < 0) return;
        if (this.screenMeta.width() != width && this.screenMeta.height() != height) return;
        this.dirty = true;
        this.screenMeta = this.screenMeta.setResolution(width, height);
    }

    public double heightPerScreen() {
        return this.screenMeta.heightPerScreen();
    }

    public void heightPerScreen(double heightPerScreen) {
        if (heightPerScreen < 0) return;
        if (this.screenMeta.heightPerScreen() != heightPerScreen) this.dirty = true;
        this.screenMeta = this.screenMeta.setHeightPerScreen(heightPerScreen);
    }

    @Override
    public boolean eatDirty() {
        boolean isDirty = this.dirty;
        this.dirty = false;
        return isDirty;
    }

    public float scale() {
        return this.screenMeta.scale();
    }

    public void scale(float scale) {
        if (this.screenMeta.scale() != scale) this.dirty = true;
        this.screenMeta = this.screenMeta.setScale(scale);
    }

    public boolean addScreen(Display.ItemDisplay display) {
        this.dirty = true;
        return screens.add(display);
    }

    public void addScreens(Display.ItemDisplay... displays) {
        screens.addAll(Arrays.asList(displays));
    }

    @Override
    public Display.TextDisplay getSubtitleDisplay() {
        return this.subtitleDisplay;
    }

    @Override
    public void setSubtitleDisplay(Display.@Nullable TextDisplay subtitleDisplay) {
        this.subtitleDisplay = subtitleDisplay;
    }

    @Override
    public Display.@Nullable ItemDisplay getMainDisplay() {
        return screens.getFirst();
    }

    @Override
    public @Nullable Entity getSoundSource() {
        return screens.getFirst();
    }

    public boolean removeScreen(Display.ItemDisplay display) {
        if (screens.remove(display)) {
            this.dirty = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean hasNoScreens() {
        return this.screens.isEmpty();
    }

    @Override
    public boolean zeroScale() {
        return this.screenMeta.zeroScale();
    }

    public Display.ItemDisplay[] getScreens() {
        return screens.toArray(Display.ItemDisplay[]::new);
    }

    public UUID[] getScreenUUIDs() {
        return screens.stream().map(Entity::getUUID).toArray(UUID[]::new);
    }

    public Integer[] getScreenIDs() {
        return screens.stream().map(Entity::getId).toArray(Integer[]::new);
    }

    @Override
    public int getScreenCount() {
        return this.screens.size();
    }

    @Override
    public void close() {
        this.screens = null;
        this.subtitleDisplay = null;
    }
}
