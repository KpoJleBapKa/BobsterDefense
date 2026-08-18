package ua.bobster.defence.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.Map;

/**
 * Дрібний хелпер навколо MiniMessage (він уже вбудований у Paper).
 */
public final class MessageUtil {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private MessageUtil() {
    }

    public static Component parse(String raw) {
        return MM.deserialize(raw == null ? "" : raw);
    }

    /**
     * Значення плейсхолдера, яке саме є MiniMessage і має лишитися розміткою.
     * <p>
     * Потрібне для назв установок і ракет: вони задані в конфізі з кольорами, і якщо їх
     * екранувати нарівні з ніками, у чат летить літеральне {@code <red><bold>…}.
     */
    public record Raw(String value) {
    }

    public static Raw raw(String value) {
        return new Raw(value);
    }

    /**
     * Парсить рядок, попередньо підставивши плейсхолдери виду {key}.
     * <p>
     * Звичайні значення екрануються, щоб ніки й назви коаліцій не ламали розмітку.
     * Загорнуті в {@link #raw(String)} підставляються як є.
     */
    public static Component parse(String raw, Map<String, ?> placeholders) {
        if (raw == null) {
            return Component.empty();
        }
        String result = raw;
        for (Map.Entry<String, ?> entry : placeholders.entrySet()) {
            Object value = entry.getValue();
            String replacement = value instanceof Raw wrapped
                    ? wrapped.value()
                    : MM.escapeTags(String.valueOf(value));
            result = result.replace("{" + entry.getKey() + "}", replacement);
        }
        return MM.deserialize(result);
    }

    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
