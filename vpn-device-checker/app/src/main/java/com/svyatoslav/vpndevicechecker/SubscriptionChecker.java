package com.svyatoslav.vpndevicechecker;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

final class SubscriptionChecker {
    private static final int CONNECT_TIMEOUT_MS = 9000;
    private static final int READ_TIMEOUT_MS = 12000;
    private static final int BODY_LIMIT = 512 * 1024;

    static final class Response {
        int code;
        String finalUrl;
        final Map<String, String> headers = new LinkedHashMap<>();
        String body = "";
        String error = null;

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        boolean headerTrue(String name) {
            String v = header(name);
            return v != null && (v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("yes"));
        }

        boolean ok() { return code >= 200 && code < 300; }
    }

    static Response fetch(String url, String hwid, String os, String osVersion, String model) {
        Response out = new Response();
        String current = url;
        try {
            for (int redirect = 0; redirect < 6; redirect++) {
                HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(CONNECT_TIMEOUT_MS);
                c.setReadTimeout(READ_TIMEOUT_MS);
                c.setRequestMethod("GET");
                c.setRequestProperty("User-Agent", "Happ/1.0 VPNDeviceChecker/1.0");
                c.setRequestProperty("Accept", "*/*");
                c.setRequestProperty("Cache-Control", "no-cache");
                if (hwid != null && !hwid.isEmpty()) {
                    c.setRequestProperty("x-hwid", hwid);
                    if (os != null) c.setRequestProperty("x-device-os", os);
                    if (osVersion != null) c.setRequestProperty("x-ver-os", osVersion);
                    if (model != null) c.setRequestProperty("x-device-model", model);
                }

                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = c.getHeaderField("Location");
                    c.disconnect();
                    if (location == null || location.isEmpty()) {
                        out.code = code;
                        out.error = "Сервер вернул перенаправление без Location";
                        return out;
                    }
                    current = new URL(new URL(current), location).toString();
                    continue;
                }

                out.code = code;
                out.finalUrl = current;
                for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                    if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) continue;
                    out.headers.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().get(0));
                }

                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                if (in != null) out.body = readLimited(in, BODY_LIMIT);
                c.disconnect();
                return out;
            }
            out.error = "Слишком много перенаправлений";
            return out;
        } catch (Exception e) {
            out.error = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "ошибка соединения" : e.getMessage());
            return out;
        }
    }

    private static String readLimited(InputStream in, int max) throws Exception {
        try (InputStream input = in; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = input.read(buf)) > 0) {
                int take = Math.min(n, max - total);
                if (take > 0) bos.write(buf, 0, take);
                total += take;
                if (total >= max) break;
            }
            return bos.toString(StandardCharsets.UTF_8.name());
        }
    }

    static String formatSafeReport(String originalInput, Response r) {
        StringBuilder s = new StringBuilder();
        s.append("БЕЗОПАСНАЯ ПРОВЕРКА\n\n");
        if (r.error != null) {
            s.append("Ошибка: ").append(r.error);
            return s.toString();
        }
        s.append("HTTP: ").append(r.code).append('\n');
        if (r.finalUrl != null && !r.finalUrl.equals(originalInput)) s.append("После redirect: да\n");

        String title = decodeMaybeBase64(r.header("profile-title"));
        if (title != null) s.append("Профиль: ").append(title).append('\n');
        String support = r.header("support-url");
        if (support != null) s.append("Поддержка: ").append(support).append('\n');
        String interval = r.header("profile-update-interval");
        if (interval != null) s.append("Обновление профиля: ").append(interval).append('\n');

        appendUserInfo(s, r.header("subscription-userinfo"));

        boolean active = r.headerTrue("x-hwid-active");
        boolean unsupported = r.headerTrue("x-hwid-not-supported");
        boolean reached = r.headerTrue("x-hwid-max-devices-reached") || r.headerTrue("x-hwid-limit");

        s.append("\nHWID-лимит: ");
        if (active) s.append("ВКЛЮЧЁН");
        else if (unsupported || reached) s.append("похоже, включён");
        else s.append("не обнаружен");
        s.append('\n');

        if (unsupported) s.append("Сервер требует HWID от клиента.\n");
        if (reached) s.append("Сервер сообщает: лимит устройств уже достигнут.\n");
        if (r.code == 404 && !active) {
            s.append("HTTP 404 без HWID может означать HWID-защиту, но также может означать неверную/истёкшую ссылку.\n");
        }

        int configs = countConfigs(r.body);
        if (configs > 0) s.append("Конфигураций в подписке: ").append(configs).append('\n');

        s.append("\nБезопасная проверка не подставляет новый HWID, поэтому не пытается занять новый слот.");
        return s.toString();
    }

    static String formatDeviceReport(Response r, String hwid) {
        StringBuilder s = new StringBuilder();
        s.append("ПРОВЕРКА ЭТОГО ТЕЛЕФОНА\n\n");
        s.append("Локальный HWID: ").append(hwid).append('\n');
        if (r.error != null) {
            s.append("Ошибка: ").append(r.error);
            return s.toString();
        }
        s.append("HTTP: ").append(r.code).append('\n');
        boolean active = r.headerTrue("x-hwid-active");
        boolean reached = r.headerTrue("x-hwid-max-devices-reached") || r.headerTrue("x-hwid-limit");
        s.append("HWID-лимит панели: ").append(active ? "ВКЛЮЧЁН" : "не подтверждён").append('\n');
        if (reached) {
            s.append("Результат: НОВОЕ УСТРОЙСТВО НЕ ПРИНИМАЕТСЯ — лимит достигнут.\n");
        } else if (r.ok()) {
            s.append("Результат: этот HWID принят сервером.\n");
            if (active) s.append("Важно: сервер мог зарегистрировать этот телефон как устройство.\n");
        } else {
            s.append("Результат неясен: сервер вернул ").append(r.code).append(".\n");
        }
        appendUserInfo(s, r.header("subscription-userinfo"));
        return s.toString();
    }

    static final class DeepResult {
        int accepted;
        boolean reached;
        boolean supported;
        String error;
        int lastCode;
        final List<String> hwids = new ArrayList<>();
    }

    static DeepResult deepProbe(String url, int maxAttempts, String os, String osVersion, String model) {
        DeepResult d = new DeepResult();
        maxAttempts = Math.max(1, Math.min(20, maxAttempts));

        for (int i = 0; i < maxAttempts; i++) {
            String hwid = randomHwid("VDCTEST");
            d.hwids.add(hwid);
            Response r = fetch(url, hwid, os, osVersion, model);
            d.lastCode = r.code;
            if (r.error != null) {
                d.error = r.error;
                return d;
            }

            boolean active = r.headerTrue("x-hwid-active");
            boolean reached = r.headerTrue("x-hwid-max-devices-reached") || r.headerTrue("x-hwid-limit");
            if (i == 0 && !active && !reached) {
                d.supported = false;
                d.error = "Панель не подтвердила Remnawave/HAPP HWID-лимит. Я не буду создавать много тестовых устройств.";
                return d;
            }
            d.supported = true;

            if (reached || (!r.ok() && (r.code == 404 || r.code == 403 || r.code == 429))) {
                d.reached = true;
                return d;
            }
            if (!r.ok()) {
                d.error = "Неожиданный HTTP " + r.code + ". Тест остановлен.";
                return d;
            }
            d.accepted++;
        }
        return d;
    }

    static String formatDeepReport(DeepResult d, int requested) {
        StringBuilder s = new StringBuilder();
        s.append("ГЛУБОКИЙ ТЕСТ HWID\n\n");
        if (!d.supported) {
            s.append("Точный тест остановлен.\n").append(d.error == null ? "HWID-лимит не подтверждён." : d.error);
            return s.toString();
        }
        s.append("Новых HWID принято: ").append(d.accepted).append('\n');
        if (d.reached) {
            s.append("Следующий новый HWID был отклонён.\n")
             .append("=> На момент теста было примерно ").append(d.accepted).append(" свободных HWID-слотов.\n");
        } else if (d.accepted >= requested) {
            s.append("Лимит за ").append(requested).append(" попыток не достигнут.\n")
             .append("=> Свободных слотов как минимум ").append(d.accepted).append(".\n");
        }
        if (d.error != null) s.append("Остановка: ").append(d.error).append('\n');
        s.append("\nВАЖНО: если подписка уже была добавлена на другие устройства, это НЕ общий лимит тарифа, а число новых слотов, которые удалось занять сейчас.\n")
         .append("Тестовые HWID могли сохраниться на сервере. Приложение не может удалить их без API провайдера.\n\n")
         .append("Тестовые HWID:\n");
        for (String h : d.hwids) s.append(h).append('\n');
        return s.toString();
    }

    private static void appendUserInfo(StringBuilder s, String userInfo) {
        if (userInfo == null || userInfo.isEmpty()) return;
        Map<String, String> m = new LinkedHashMap<>();
        for (String p : userInfo.split(";")) {
            String[] kv = p.trim().split("=", 2);
            if (kv.length == 2) m.put(kv[0].trim().toLowerCase(Locale.ROOT), kv[1].trim());
        }
        long upload = parseLong(m.get("upload"));
        long download = parseLong(m.get("download"));
        long total = parseLong(m.get("total"));
        long expire = parseLong(m.get("expire"));
        s.append("\nТрафик использован: ").append(formatBytes(upload + download)).append('\n');
        if (total > 0) s.append("Лимит трафика: ").append(formatBytes(total)).append('\n');
        else s.append("Лимит трафика: не указан/безлимит\n");
        if (expire > 0) s.append("Истекает: ").append(formatEpoch(expire)).append('\n');
    }

    private static String decodeMaybeBase64(String v) {
        if (v == null) return null;
        if (!v.toLowerCase(Locale.ROOT).startsWith("base64:")) return v;
        try {
            return new String(Base64.getDecoder().decode(v.substring(7)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return v;
        }
    }

    private static long parseLong(String s) {
        try { return s == null ? 0 : Long.parseLong(s); }
        catch (Exception e) { return 0; }
    }

    private static String formatBytes(long b) {
        if (b < 1024) return b + " B";
        double v = b;
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        int i = 0;
        while (v >= 1024 && i < units.length - 1) { v /= 1024.0; i++; }
        return String.format(Locale.ROOT, "%.2f %s", v, units[i]);
    }

    private static String formatEpoch(long seconds) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        f.setTimeZone(TimeZone.getDefault());
        return f.format(new Date(seconds * 1000L));
    }

    private static int countConfigs(String body) {
        if (body == null || body.isEmpty()) return 0;
        String text = body.trim();
        int direct = countSchemes(text);
        if (direct > 0) return direct;
        try {
            String compact = text.replaceAll("\\s+", "");
            if (compact.length() > 20) {
                byte[] decoded = Base64.getDecoder().decode(compact);
                String d = new String(decoded, StandardCharsets.UTF_8);
                return countSchemes(d);
            }
        } catch (Exception ignored) { }
        return 0;
    }

    private static int countSchemes(String text) {
        String[] schemes = {"vless://", "vmess://", "trojan://", "ss://", "ssr://", "hysteria2://", "hy2://", "tuic://"};
        int count = 0;
        for (String line : text.split("[\\r\\n]+")) {
            String x = line.trim().toLowerCase(Locale.ROOT);
            for (String p : schemes) {
                if (x.startsWith(p)) { count++; break; }
            }
        }
        if (count > 0) return count;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String p : schemes) {
            int from = 0;
            while (true) {
                int i = lower.indexOf(p, from);
                if (i < 0) break;
                count++;
                from = i + p.length();
            }
        }
        return count;
    }

    static String randomHwid(String prefix) {
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-";
        java.security.SecureRandom r = new java.security.SecureRandom();
        StringBuilder s = new StringBuilder(prefix == null ? "" : prefix);
        while (s.length() < 24) s.append(alphabet.charAt(r.nextInt(alphabet.length())));
        return s.substring(0, 24);
    }
}
