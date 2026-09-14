package com.svyatoslav.vpndevicechecker;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class KeyUtils {
    private KeyUtils() {}

    static String clean(String input) {
        if (input == null) return "";
        String s = input.trim();
        if (s.startsWith("```") && s.endsWith("```")) {
            s = s.substring(3, s.length() - 3).trim();
        }
        int nl = s.indexOf('\n');
        if (nl > 0 && (s.startsWith("http://") || s.startsWith("https://") || s.contains("://"))) {
            s = s.substring(0, nl).trim();
        }
        return s;
    }

    static String scheme(String input) {
        String s = clean(input);
        int i = s.indexOf("://");
        return i > 0 ? s.substring(0, i).toLowerCase(Locale.ROOT) : "";
    }

    static boolean isHttp(String s) {
        String x = clean(s).toLowerCase(Locale.ROOT);
        return x.startsWith("https://") || x.startsWith("http://");
    }

    static boolean isDirectProxyKey(String s) {
        String p = scheme(s);
        return p.equals("vless") || p.equals("vmess") || p.equals("trojan") || p.equals("ss") ||
                p.equals("ssr") || p.equals("hysteria2") || p.equals("hy2") || p.equals("tuic") ||
                p.equals("wireguard") || p.equals("wg");
    }

    static String unwrapSubscription(String input) {
        String s = clean(input);
        if (isHttp(s)) return s;

        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("hiddify://") || lower.startsWith("happ://")) {
            try {
                URI uri = URI.create(s);
                String query = uri.getRawQuery();
                if (query != null) {
                    for (String part : query.split("&")) {
                        int eq = part.indexOf('=');
                        String k = eq >= 0 ? part.substring(0, eq) : part;
                        String v = eq >= 0 ? part.substring(eq + 1) : "";
                        if (k.equalsIgnoreCase("url") || k.equalsIgnoreCase("subscription")) {
                            String decoded = URLDecoder.decode(v, StandardCharsets.UTF_8.name());
                            if (isHttp(decoded)) return decoded;
                        }
                    }
                }
            } catch (Exception ignored) { }

            int pos = lower.indexOf("url=");
            if (pos >= 0) {
                String v = s.substring(pos + 4);
                int amp = v.indexOf('&');
                if (amp >= 0) v = v.substring(0, amp);
                try {
                    v = URLDecoder.decode(v, StandardCharsets.UTF_8.name());
                } catch (Exception ignored) { }
                if (isHttp(v)) return v;
            }
        }
        return s;
    }

    static String describeDirectKey(String input) {
        String s = clean(input);
        String p = scheme(s);
        StringBuilder out = new StringBuilder();
        out.append("Тип ключа: ").append(p.isEmpty() ? "неизвестный" : p.toUpperCase(Locale.ROOT)).append('\n');
        if (!p.equals("vmess") && !p.equals("ssr")) {
            try {
                URI uri = URI.create(s);
                if (uri.getHost() != null) {
                    out.append("Сервер: ").append(uri.getHost());
                    if (uri.getPort() > 0) out.append(':').append(uri.getPort());
                    out.append('\n');
                }
            } catch (Exception ignored) { }
        }
        out.append("\nЛимит устройств в обычном ").append(p.isEmpty() ? "proxy" : p).append("-ключе не хранится.\n")
           .append("Для проверки лимита вставь именно ссылку-подписку http(s):// из Telegram-бота/личного кабинета.");
        return out.toString();
    }
}
