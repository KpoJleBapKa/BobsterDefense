package ua.bobster.defence.raid;

import ua.bobster.defence.BobsterDefence;

import java.lang.reflect.Method;
import java.util.logging.Level;

/**
 * Надсилання повідомлень у Discord через DiscordSRV.
 * <p>
 * Звертаємось рефлексією: інакше довелося б тягнути DiscordSRV у залежності збірки,
 * а плагін має працювати й без нього. Якщо DiscordSRV немає або його API змінилося —
 * просто мовчки лишаємось без Discord, гра від цього не ламається.
 */
public class DiscordBridge {

    private final BobsterDefence plugin;

    private boolean enabled;
    private String channelName;
    private boolean warned;

    public DiscordBridge(BobsterDefence plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        this.enabled = plugin.getConfig().getBoolean("air-raid.discord.enabled", true);
        this.channelName = plugin.getConfig().getString("air-raid.discord.channel", "");
        this.warned = false;
    }

    public boolean available() {
        return plugin.getServer().getPluginManager().getPlugin("DiscordSRV") != null;
    }

    public void send(String message) {
        if (!enabled || message == null || message.isBlank() || !available()) {
            return;
        }
        try {
            Class<?> discordSrv = Class.forName("github.scarsz.discordsrv.DiscordSRV");
            Object instance = discordSrv.getMethod("getPlugin").invoke(null);

            Object channel = channelName.isBlank()
                    ? discordSrv.getMethod("getMainTextChannel").invoke(instance)
                    : discordSrv.getMethod("getDestinationTextChannelForGameChannelName", String.class)
                            .invoke(instance, channelName);
            if (channel == null) {
                warnOnce("DiscordSRV: канал не знайдено (air-raid.discord.channel)");
                return;
            }

            // Шукаємо sendMessage(TextChannel, String) за формою, а не за точним типом:
            // DiscordSRV шейдить JDA, і повне ім'я TextChannel змінюється між версіями.
            Class<?> discordUtil = Class.forName("github.scarsz.discordsrv.util.DiscordUtil");
            for (Method method : discordUtil.getMethods()) {
                if (method.getName().equals("sendMessage")
                        && method.getParameterCount() == 2
                        && method.getParameterTypes()[1] == String.class
                        && method.getParameterTypes()[0].isInstance(channel)) {
                    method.invoke(null, channel, message);
                    return;
                }
            }
            warnOnce("DiscordSRV: не знайдено метод sendMessage — версія не підтримується");
        } catch (ClassNotFoundException ex) {
            warnOnce("DiscordSRV знайдено, але його класи недоступні — повідомлення не надсилаються");
        } catch (ReflectiveOperationException | RuntimeException ex) {
            warnOnce("DiscordSRV: помилка надсилання — " + ex);
        }
    }

    /** Скаржимося лише раз: інакше кожна тривога сипала б стектрейсами в консоль. */
    private void warnOnce(String message) {
        if (warned) {
            return;
        }
        warned = true;
        plugin.getLogger().log(Level.WARNING, message);
    }
}
