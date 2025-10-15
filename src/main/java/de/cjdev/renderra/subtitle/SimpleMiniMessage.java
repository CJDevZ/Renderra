package de.cjdev.renderra.subtitle;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

public class SimpleMiniMessage {

    private final String input;
    private int cursor = 0;

    public SimpleMiniMessage(String input) {
        this.input = input;
    }

    private boolean canRead() {
        return cursor < input.length();
    }

    private char peek() {
        if (!canRead()) throw new RuntimeException("End of input");
        return input.charAt(cursor);
    }

    private char read() {
        if (!canRead()) throw new RuntimeException("End of input");
        return input.charAt(cursor++);
    }

    private boolean readIf(char expected) {
        if (canRead() && peek() == expected) {
            cursor++;
            return true;
        }
        return false;
    }

    private String readUntil(char endChar) {
        int start = cursor;
        while (canRead() && peek() != endChar) {
            cursor++;
        }
        return input.substring(start, cursor);
    }

    public Component parse() {
        return parseComponent(null, Component.literal(""));
    }

    private Component parseComponent(@Nullable String expectedClosingTag, MutableComponent currentComponent) {
        applyStyle(currentComponent, expectedClosingTag);

        StringBuilder textBuffer = new StringBuilder();

        while (canRead()) {
            if (readIf('<')) {
                if (!textBuffer.isEmpty()) {
                    currentComponent.getSiblings().add(Component.literal(textBuffer.toString()));
                    textBuffer.setLength(0);
                }

                boolean closing = readIf('/');
                String tagName = readUntil('>');
                read(); // consume '>'

                if (closing) {
                    if (tagName.equalsIgnoreCase(expectedClosingTag)) {
                        return currentComponent;
                    } else {
                        throw new RuntimeException("Unexpected closing tag: " + tagName);
                    }
                } else {
                    MutableComponent child = Component.literal("");
                    parseComponent(tagName, child);
                    applyStyle(child, tagName);
                    currentComponent.getSiblings().add(child);
                }
            } else {
                textBuffer.append(read());
            }
        }

        if (!textBuffer.isEmpty()) {
            currentComponent.getSiblings().add(Component.literal(textBuffer.toString()));
        }

        if (expectedClosingTag != null) {
            throw new RuntimeException("Missing closing tag: " + expectedClosingTag);
        }

        return currentComponent;
    }

    private static void applyStyle(MutableComponent component, String tag) {
        if (tag == null) return;
        switch (tag) {
            case "b" -> component.withStyle(style -> style.withBold(true));
            case "i" -> component.withStyle(style -> style.withItalic(true));
        }
    }

}
