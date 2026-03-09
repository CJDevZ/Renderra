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
    private double heightPerScreen;
    private float scale;
    public final LinkedHashSet<Display.ItemDisplay> screens;
    public Display.TextDisplay subtitleScreen;

    public ScreenMetaData() {
        this.screens = new LinkedHashSet<>();
        this.width = 16;
        this.height = 9;
        this.scale = 1F;
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

    public double heightPerScreen() {
        return this.heightPerScreen;
    }

    public void heightPerScreen(double heightPerScreen) {
        if (heightPerScreen < 0) return;
        if (this.heightPerScreen != heightPerScreen) this.dirty = true;
        this.heightPerScreen = heightPerScreen;
    }

    public float scale() {
        return this.scale;
    }

    public void scale(float scale) {
        if (this.scale != scale) this.dirty = true;
        this.scale = scale;
    }

    public boolean invisible() {
        return this.height == 0 || this.width == 0;
    }

    public boolean addScreen(Display.ItemDisplay display) {
        this.dirty = true;
        return screens.add(display);
    }

    public void addScreens(Display.ItemDisplay... displays) {
        screens.addAll(Arrays.asList(displays));
    }

    public boolean removeScreen(Display.ItemDisplay display) {
        this.dirty = true;
        return screens.remove(display);
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

    public Display.ItemDisplay getMainScreen() {
        return screens.getFirst();
    }
}
