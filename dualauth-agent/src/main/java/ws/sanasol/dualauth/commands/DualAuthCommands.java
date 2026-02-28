package ws.sanasol.dualauth.commands;

import ws.sanasol.dualauth.context.DualAuthContext;
import ws.sanasol.dualauth.agent.DualAuthAgent;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Handles DualAuth in-game commands:
 * - /authinfo [player]  — Show auth type for self or target player
 * - /authlist            — List all online players with auth type
 *
 * Permission model:
 * - Any player can /authinfo (self)
 * - Admin commands require one of:
 *   1. UUID in DUALAUTH_ADMIN_UUIDS env var
 *   2. "dualauth.admin" game permission
 *   3. Configurable fallback permission (DUALAUTH_ADMIN_PERMISSION env var, default: "server.commands.who")
 */
public class DualAuthCommands {

    private static final String ADMIN_PERMISSION;
    static {
        String envPerm = System.getenv("DUALAUTH_ADMIN_PERMISSION");
        ADMIN_PERMISSION = System.getProperty("dualauth.admin.permission",
                envPerm != null ? envPerm : "server.commands.who");
    }

    /**
     * Called from ChatCommandTransformer advice.
     * Returns CompletableFuture<Void> if command was handled, null otherwise.
     */
    public static Object tryHandle(Object sender, String commandString) {
        if (commandString == null) return null;

        String trimmed = commandString.trim();
        int spaceIdx = trimmed.indexOf(' ');
        String commandName = (spaceIdx < 0 ? trimmed : trimmed.substring(0, spaceIdx)).toLowerCase();
        String args = spaceIdx < 0 ? "" : trimmed.substring(spaceIdx + 1).trim();

        switch (commandName) {
            case "authinfo":
                handleAuthInfo(sender, args);
                return CompletableFuture.completedFuture(null);
            case "authlist":
                handleAuthList(sender);
                return CompletableFuture.completedFuture(null);
            default:
                return null;
        }
    }

    private static void handleAuthInfo(Object sender, String targetArg) {
        UUID senderUuid = getUuid(sender);

        if (targetArg.isEmpty()) {
            // Self info — any player can use
            if (senderUuid == null) {
                sendMsg(sender, "[DualAuth] Could not determine your UUID.");
                return;
            }
            DualAuthContext.PlayerAuthInfo info = DualAuthContext.getPlayerInfo(senderUuid.toString());
            if (info == null) {
                sendMsg(sender, "[DualAuth] No auth info found for your session.");
                return;
            }
            sendAuthInfo(sender, info);
        } else {
            // Target player — requires admin
            if (!isAdmin(sender)) {
                sendMsg(sender, "[DualAuth] Permission denied. Requires dualauth.admin or operator status.");
                return;
            }
            DualAuthContext.PlayerAuthInfo target = findPlayerByName(targetArg);
            if (target == null) {
                // Try by UUID
                target = DualAuthContext.getPlayerInfo(targetArg);
            }
            if (target == null) {
                sendMsg(sender, "[DualAuth] Player '" + targetArg + "' not found online.");
                return;
            }
            sendAuthInfo(sender, target);
        }
    }

    private static void handleAuthList(Object sender) {
        if (!isAdmin(sender)) {
            sendMsg(sender, "[DualAuth] Permission denied. Requires dualauth.admin or operator status.");
            return;
        }

        Map<String, DualAuthContext.PlayerAuthInfo> players = DualAuthContext.getOnlinePlayers();
        if (players.isEmpty()) {
            sendMsg(sender, "[DualAuth] No players in auth registry.");
            return;
        }

        sendMsg(sender, "[DualAuth] Online players (" + players.size() + "):");
        sendMsg(sender, "  Name             | Type          | UUID");
        sendMsg(sender, "  -----------------+---------------+--------------------------------------");
        for (DualAuthContext.PlayerAuthInfo info : players.values()) {
            String authType = getAuthTypeLabel(info);
            String name = pad(info.username != null ? info.username : "?", 16);
            String type = pad(authType, 13);
            sendMsg(sender, "  " + name + " | " + type + " | " + info.uuid);
        }
    }

    private static void sendAuthInfo(Object sender, DualAuthContext.PlayerAuthInfo info) {
        String authType = getAuthTypeLabel(info);
        long seconds = (System.currentTimeMillis() - info.authenticatedAt) / 1000;
        String duration = formatDuration(seconds);

        sendMsg(sender, "[DualAuth] === Auth Info: " + (info.username != null ? info.username : "?") + " ===");
        sendMsg(sender, "  UUID:      " + info.uuid);
        sendMsg(sender, "  Auth Type: " + authType);
        sendMsg(sender, "  Issuer:    " + (info.issuer != null ? info.issuer : "unknown"));
        sendMsg(sender, "  Session:   " + duration + " ago");
        sendMsg(sender, "  F2P:       " + info.isF2P);
        sendMsg(sender, "  Omni-Auth: " + info.isOmni);
        sendMsg(sender, "  Agent:     v" + DualAuthAgent.VERSION);
    }

    private static String getAuthTypeLabel(DualAuthContext.PlayerAuthInfo info) {
        if (info.isOmni) return "OMNI (self-signed)";
        if (info.isF2P) return "F2P";
        return "OFFICIAL";
    }

    private static String formatDuration(long totalSeconds) {
        if (totalSeconds < 0) return "?";
        if (totalSeconds < 60) return totalSeconds + "s";
        long minutes = totalSeconds / 60;
        long secs = totalSeconds % 60;
        if (minutes < 60) return minutes + "m " + secs + "s";
        long hours = minutes / 60;
        minutes = minutes % 60;
        return hours + "h " + minutes + "m";
    }

    private static String pad(String s, int len) {
        if (s == null) s = "?";
        if (s.length() >= len) return s.substring(0, len);
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < len) sb.append(' ');
        return sb.toString();
    }

    private static DualAuthContext.PlayerAuthInfo findPlayerByName(String name) {
        for (DualAuthContext.PlayerAuthInfo info : DualAuthContext.getOnlinePlayers().values()) {
            if (info.username != null && info.username.equalsIgnoreCase(name)) {
                return info;
            }
        }
        return null;
    }

    // ---- Permission check ----

    private static boolean isAdmin(Object sender) {
        UUID uuid = getUuid(sender);

        // 1. Check env var admin UUID list
        String adminUuids = System.getenv("DUALAUTH_ADMIN_UUIDS");
        if (adminUuids != null && uuid != null) {
            String uuidStr = uuid.toString();
            for (String entry : adminUuids.split(",")) {
                if (entry.trim().equalsIgnoreCase(uuidStr)) return true;
            }
        }

        // 2. Check game permission system
        try {
            Method m = findMethod(sender, "hasPermission", String.class);
            if (m != null) {
                if ((boolean) m.invoke(sender, "dualauth.admin")) return true;
                if ((boolean) m.invoke(sender, ADMIN_PERMISSION)) return true;
            }
        } catch (Exception ignored) {}

        return false;
    }

    // ---- Reflection utilities ----

    private static UUID getUuid(Object sender) {
        try {
            Method m = findMethod(sender, "getUuid");
            if (m != null) return (UUID) m.invoke(sender);
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Send a plain text message to a CommandSender using reflection.
     * Uses Message.raw(text) → sender.sendMessage(message)
     */
    public static void sendMsg(Object sender, String text) {
        try {
            ClassLoader cl = sender.getClass().getClassLoader();
            Class<?> messageClass = cl.loadClass("com.hypixel.hytale.server.core.Message");
            Method rawMethod = messageClass.getMethod("raw", String.class);
            Object message = rawMethod.invoke(null, text);

            // Find sendMessage(Message) on the sender
            Method sendMethod = null;
            for (Method m : sender.getClass().getMethods()) {
                if (m.getName().equals("sendMessage") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isAssignableFrom(messageClass)) {
                    sendMethod = m;
                    break;
                }
            }
            if (sendMethod != null) {
                sendMethod.setAccessible(true);
                sendMethod.invoke(sender, message);
            } else {
                System.err.println("[DualAuth] sendMessage method not found on " + sender.getClass().getName());
            }
        } catch (Exception e) {
            System.err.println("[DualAuth] Failed to send message: " + e.getMessage());
        }
    }

    private static Method findMethod(Object obj, String name, Class<?>... paramTypes) {
        try {
            return obj.getClass().getMethod(name, paramTypes);
        } catch (NoSuchMethodException e) {
            for (Method m : obj.getClass().getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == paramTypes.length) {
                    return m;
                }
            }
            return null;
        }
    }
}
