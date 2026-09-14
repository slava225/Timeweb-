package com.svyatoslav.vpndevicechecker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private EditText keyInput;
    private EditText attemptsInput;
    private TextView result;
    private Button safeButton;
    private Button phoneButton;
    private Button deepButton;

    private final int bg = Color.rgb(12, 15, 20);
    private final int card = Color.rgb(24, 29, 38);
    private final int text = Color.rgb(238, 242, 247);
    private final int muted = Color.rgb(157, 169, 184);
    private final int accent = Color.rgb(78, 140, 255);
    private final int danger = Color.rgb(225, 92, 92);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        setContentView(buildUi());
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(bg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = tv("VPN Device Checker", 26, text, true);
        root.addView(title);
        TextView subtitle = tv("Проверка VPN-подписки и реального HWID-лимита устройств", 14, muted, false);
        setMargins(subtitle, 0, 6, 0, 18);
        root.addView(subtitle);

        TextView hint = tv("Вставь ссылку-подписку http(s):// из Telegram-бота, Hiddify/Happ или ключ. Обычный vless:// сам по себе лимит устройств не содержит.", 13, muted, false);
        hint.setPadding(dp(12), dp(12), dp(12), dp(12));
        hint.setBackground(round(card, 14));
        root.addView(hint);
        setMargins(hint, 0, 0, 0, 12);

        keyInput = new EditText(this);
        keyInput.setHint("https://.../subscription или vless://...");
        keyInput.setHintTextColor(Color.rgb(104, 115, 130));
        keyInput.setTextColor(text);
        keyInput.setTextSize(14);
        keyInput.setMinLines(4);
        keyInput.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        keyInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        keyInput.setBackground(round(card, 14));
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        root.addView(keyInput, matchWrap());
        setMargins(keyInput, 0, 0, 0, 10);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button paste = button("Вставить", accent);
        Button clear = button("Очистить", Color.rgb(70, 80, 95));
        row.addView(paste, weight());
        row.addView(space(dp(8)));
        row.addView(clear, weight());
        root.addView(row);
        setMargins(row, 0, 0, 0, 16);

        safeButton = button("1. Безопасная проверка", accent);
        phoneButton = button("2. Проверить этот телефон (HWID)", Color.rgb(73, 159, 116));
        deepButton = button("3. Глубокий тест свободных слотов", danger);
        root.addView(safeButton, matchWrap());
        setMargins(safeButton, 0, 0, 0, 8);
        root.addView(phoneButton, matchWrap());
        setMargins(phoneButton, 0, 0, 0, 8);

        LinearLayout deepRow = new LinearLayout(this);
        deepRow.setOrientation(LinearLayout.HORIZONTAL);
        attemptsInput = new EditText(this);
        attemptsInput.setText("10");
        attemptsInput.setTextColor(text);
        attemptsInput.setHintTextColor(muted);
        attemptsInput.setHint("макс. 20");
        attemptsInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        attemptsInput.setGravity(android.view.Gravity.CENTER);
        attemptsInput.setBackground(round(card, 12));
        attemptsInput.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams small = new LinearLayout.LayoutParams(dp(80), dp(52));
        deepRow.addView(attemptsInput, small);
        deepRow.addView(space(dp(8)));
        deepRow.addView(deepButton, weightHeight(dp(52)));
        root.addView(deepRow);
        setMargins(deepRow, 0, 0, 0, 16);

        TextView caution = tv("Глубокий тест запускай только если понимаешь риск: панель может зарегистрировать тестовые HWID и занять слоты подписки. Приложение не умеет удалять их без API провайдера.", 12, Color.rgb(245, 174, 105), false);
        caution.setPadding(dp(12), dp(10), dp(12), dp(10));
        caution.setBackground(round(Color.rgb(48, 36, 24), 12));
        root.addView(caution);
        setMargins(caution, 0, 0, 0, 18);

        TextView resultTitle = tv("Результат", 18, text, true);
        root.addView(resultTitle);
        setMargins(resultTitle, 0, 0, 0, 8);

        result = tv("Пока ничего не проверялось.", 13, text, false);
        result.setTypeface(Typeface.MONOSPACE);
        result.setTextIsSelectable(true);
        result.setPadding(dp(12), dp(12), dp(12), dp(12));
        result.setBackground(round(card, 14));
        root.addView(result, matchWrap());
        setMargins(result, 0, 0, 0, 10);

        Button copy = button("Копировать результат", Color.rgb(70, 80, 95));
        root.addView(copy, matchWrap());

        paste.setOnClickListener(v -> pasteClipboard());
        clear.setOnClickListener(v -> { keyInput.setText(""); result.setText("Пока ничего не проверялось."); });
        copy.setOnClickListener(v -> copyResult());
        safeButton.setOnClickListener(v -> runSafe());
        phoneButton.setOnClickListener(v -> confirmPhoneCheck());
        deepButton.setOnClickListener(v -> confirmDeep());

        return scroll;
    }

    private void runSafe() {
        String raw = getInputOrWarn();
        if (raw == null) return;
        String normalized = KeyUtils.unwrapSubscription(raw);
        if (KeyUtils.isDirectProxyKey(normalized)) {
            result.setText(KeyUtils.describeDirectKey(normalized));
            return;
        }
        if (!KeyUtils.isHttp(normalized)) {
            result.setText("Не удалось распознать ключ.\n\nВставь http(s):// ссылку-подписку или vless:// / trojan:// / vmess:// ключ.");
            return;
        }
        setBusy(true, "Проверяю ссылку без HWID…");
        executor.execute(() -> {
            SubscriptionChecker.Response r = SubscriptionChecker.fetch(normalized, null, null, null, null);
            String report = SubscriptionChecker.formatSafeReport(normalized, r);
            main.post(() -> { result.setText(report); setBusy(false, null); });
        });
    }

    private void confirmPhoneCheck() {
        String raw = getInputOrWarn();
        if (raw == null) return;
        String normalized = KeyUtils.unwrapSubscription(raw);
        if (!KeyUtils.isHttp(normalized)) {
            result.setText(KeyUtils.isDirectProxyKey(normalized)
                    ? KeyUtils.describeDirectKey(normalized)
                    : "Для HWID-проверки нужна http(s):// ссылка-подписка.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Проверить HWID телефона?")
                .setMessage("Если провайдер использует Remnawave/HAPP HWID-лимит, этот телефон может быть зарегистрирован как одно устройство. Продолжить?")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Проверить", (d, w) -> runPhoneCheck(normalized))
                .show();
    }

    private void runPhoneCheck(String url) {
        String hwid = stableHwid();
        setBusy(true, "Проверяю с HWID этого телефона…");
        executor.execute(() -> {
            SubscriptionChecker.Response r = SubscriptionChecker.fetch(url, hwid, "Android", Build.VERSION.RELEASE, deviceModel());
            String report = SubscriptionChecker.formatDeviceReport(r, hwid);
            main.post(() -> { result.setText(report); setBusy(false, null); });
        });
    }

    private void confirmDeep() {
        String raw = getInputOrWarn();
        if (raw == null) return;
        String normalized = KeyUtils.unwrapSubscription(raw);
        if (!KeyUtils.isHttp(normalized)) {
            result.setText("Глубокий тест работает только со ссылкой-подпиской http(s)://, а не с отдельным vless:// ключом.");
            return;
        }
        int attempts = parseAttempts();
        new AlertDialog.Builder(this)
                .setTitle("ВНИМАНИЕ: тест может занять слоты")
                .setMessage("Будут отправляться НОВЫЕ виртуальные HWID. Если сервер регистрирует устройства, эти тестовые ID могут остаться в подписке и заполнить лимит. Автоматически удалить их приложение не сможет.\n\nМаксимум попыток: " + attempts + "\n\nПродолжить только если ключ твой и ты готов при необходимости попросить поддержку удалить тестовые устройства.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Я понимаю, проверить", (d, w) -> runDeep(normalized, attempts))
                .show();
    }

    private void runDeep(String url, int attempts) {
        setBusy(true, "Глубокий тест: создаю тестовые HWID…");
        executor.execute(() -> {
            SubscriptionChecker.DeepResult d = SubscriptionChecker.deepProbe(url, attempts, "Android", Build.VERSION.RELEASE, "VPN Device Checker test");
            String report = SubscriptionChecker.formatDeepReport(d, attempts);
            main.post(() -> { result.setText(report); setBusy(false, null); });
        });
    }

    private String getInputOrWarn() {
        String s = KeyUtils.clean(keyInput.getText().toString());
        if (s.isEmpty()) {
            Toast.makeText(this, "Сначала вставь VPN-ключ или ссылку-подписку", Toast.LENGTH_SHORT).show();
            return null;
        }
        return s;
    }

    private int parseAttempts() {
        try { return Math.max(1, Math.min(20, Integer.parseInt(attemptsInput.getText().toString().trim()))); }
        catch (Exception e) { return 10; }
    }

    private String stableHwid() {
        SharedPreferences p = getSharedPreferences("checker", MODE_PRIVATE);
        String h = p.getString("stable_hwid", null);
        if (h == null || h.length() < 10) {
            h = SubscriptionChecker.randomHwid("VDC");
            p.edit().putString("stable_hwid", h).apply();
        }
        return h;
    }

    private String deviceModel() {
        String m = (Build.MANUFACTURER + " " + Build.MODEL).trim();
        return m.isEmpty() ? "Android device" : m;
    }

    private void pasteClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence s = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            if (s != null) keyInput.setText(s.toString());
        } else Toast.makeText(this, "Буфер обмена пуст", Toast.LENGTH_SHORT).show();
    }

    private void copyResult() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("VPN Device Checker result", result.getText()));
            Toast.makeText(this, "Результат скопирован", Toast.LENGTH_SHORT).show();
        }
    }

    private void setBusy(boolean busy, String message) {
        safeButton.setEnabled(!busy);
        phoneButton.setEnabled(!busy);
        deepButton.setEnabled(!busy);
        if (busy && message != null) result.setText(message);
    }

    private TextView tv(String value, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        v.setLineSpacing(0, 1.12f);
        return v;
    }

    private Button button(String label, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setBackgroundTintList(ColorStateList.valueOf(color));
        b.setMinHeight(dp(48));
        return b;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private View space(int px) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(px, 1));
        return v;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams weightHeight(int h) {
        return new LinearLayout.LayoutParams(0, h, 1f);
    }

    private void setMargins(View v, int l, int t, int r, int b) {
        ViewGroup.LayoutParams p = v.getLayoutParams();
        if (p instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) p).setMargins(dp(l), dp(t), dp(r), dp(b));
            v.setLayoutParams(p);
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
