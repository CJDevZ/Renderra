package de.cjdev.renderra.video;

import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.UUID;

public class ScreenMetaData {
    public boolean dirty;
    private int width;
    private int height;
    private float scale;
    private boolean pretty;
    public final LinkedHashSet<Display.TextDisplay> screens;
    public Display.TextDisplay subtitleScreen;

    public ScreenMetaData() {
        this.screens = new LinkedHashSet<>();
        this.width = 16;
        this.height = 9;
        this.scale = 1F;
        this.pretty = true;
    }

    public int width() {
        return this.width;
    }

    public void width(int width) {
        if (width < 0) return;
        if (this.width != width) this.dirty = true;
        this.width = width;
    }

    public int height() {
        return this.height;
    }

    public void height(int height) {
        if (height < 0) return;
        if (this.height != height) this.dirty = true;
        this.height = height;
    }

    public float scale() {
        return this.scale;
    }

    public void scale(float scale) {
        if (this.scale != scale) this.dirty = true;
        this.scale = scale;
    }

    public boolean pretty() {
        return this.pretty;
    }

    public void pretty(boolean pretty) {
        if (this.pretty != pretty) this.dirty = true;
        this.pretty = pretty;
    }

    public boolean invisible() {
        return this.height == 0 || this.width == 0;
    }

    public boolean addScreen(Display.TextDisplay display) {
        this.dirty = true;
        return screens.add(display);
    }

    public void addScreens(Display.TextDisplay... displays) {
        screens.addAll(Arrays.asList(displays));
    }

    public boolean removeScreen(Display.TextDisplay display) {
        this.dirty = true;
        return screens.remove(display);
    }

    public Display.TextDisplay[] getScreens() {
        return screens.toArray(Display.TextDisplay[]::new);
    }

    public UUID[] getScreenUUIDs() {
        return screens.stream().map(Entity::getUUID).toArray(UUID[]::new);
    }

    public Integer[] getScreenIDs() {
        return screens.stream().map(Entity::getId).toArray(Integer[]::new);
    }

    public Display.TextDisplay getMainScreen() {
        return screens.getFirst();
    }
}
