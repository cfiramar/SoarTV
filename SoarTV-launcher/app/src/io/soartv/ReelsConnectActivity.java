package io.soartv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Liquid Glass TV setup activity for connecting dedicated TV Instagram / TikTok (and future
 * plugin) accounts, selecting couple or 3+ family group chats, and naming every group member.
 *
 * <p><b>Why:</b> Users should never type personal passwords into SoarTV's code or risk personal
 * social accounts. This activity runs in the isolated {@code :reels} process, guides creating a
 * dedicated TV account (Mode B) or optional personal login (Mode A), lets the user sign in
 * directly on each platform's real login page (supporting Companion Remote keyboard &amp; TikTok
 * QR scan), picks any group/1-on-1 chat with D-pad, and renames unlimited family members.
 */
public class ReelsConnectActivity extends Activity {
    public static final String EXTRA_AUTO_SOURCE = "io.soartv.REELS_AUTO_SOURCE";

    private ReelsWebBridgeHost bridgeHost;
    private ReelsRepository repo;

    private FrameLayout root;
    private LinearLayout sourcesColumn;
    private LinearLayout sendersColumn;
    private FrameLayout webOverlay;
    private WebView loginWebView;
    private TextView webHeaderTitle;
    private ReelsSourcePlugin activeLoginPlugin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ReelsWebBridgeHost.ensureProcessWebViewDir();
        Ui.request4kDisplayMode(getWindow());
        Ui.init(this);
        repo = ReelsRepository.get(this);
        bridgeHost = new ReelsWebBridgeHost(this);

        buildUi();
        renderAll();

        String autoSource = getIntent().getStringExtra(EXTRA_AUTO_SOURCE);
        if (autoSource != null && !autoSource.isEmpty()) {
            ReelsSourcePlugin p = ReelsSourceRegistry.getDefault().find(autoSource);
            if (p != null && p.supportsChatBridge()) {
                openSignInOverlay(p);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (loginWebView != null) {
            try {
                loginWebView.stopLoading();
                loginWebView.destroy();
            } catch (RuntimeException ignored) {
            }
            loginWebView = null;
        }
        if (bridgeHost != null) bridgeHost.destroy();
    }

    private void buildUi() {
        root = new FrameLayout(this);
        GradientDrawable aurora = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[] { 0xFF4A2370, 0xFF17274D, 0xFF0E3D52, 0xFF181624 });
        root.setBackground(aurora);

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(44), dp(30), dp(44), dp(28));
        root.addView(shell, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Top Header Bar
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        shell.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout titleWrap = new LinearLayout(this);
        titleWrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams twLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(titleWrap, twLp);

        TextView title = new TextView(this);
        title.setText(Ui.tr("Reels Accounts & Family Chats"));
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleWrap.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText(Ui.tr("Recommended: Create a free dedicated TV account on Instagram & TikTok, add it to your couple or 3+ family group chat, then sign in once below."));
        subtitle.setTextColor(0xFFD1D1D6);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        subtitle.setPadding(0, dp(4), dp(24), 0);
        titleWrap.addView(subtitle);

        Button watchNowBtn = makeGlassButton(Ui.tr("Open Reels Feed"), true);
        watchNowBtn.setOnClickListener(v -> {
            startActivity(new Intent(this, ReelsPlayerActivity.class));
            finish();
        });
        header.addView(watchNowBtn);

        // Two-Column Body: Left = Platforms & Group Chats (56%), Right = Custom Family Member Names (44%)
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyLp.topMargin = dp(20);
        shell.addView(body, bodyLp);

        ScrollView leftScroll = new ScrollView(this);
        leftScroll.setBackground(glassPanelDrawable(0x18FFFFFF, 0x2BFFFFFF, dp(22)));
        leftScroll.setPadding(dp(22), dp(20), dp(22), dp(20));
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.56f);
        leftLp.rightMargin = dp(18);
        body.addView(leftScroll, leftLp);

        sourcesColumn = new LinearLayout(this);
        sourcesColumn.setOrientation(LinearLayout.VERTICAL);
        leftScroll.addView(sourcesColumn, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView rightScroll = new ScrollView(this);
        rightScroll.setBackground(glassPanelDrawable(0x12FFFFFF, 0x22FFFFFF, dp(22)));
        rightScroll.setPadding(dp(22), dp(20), dp(22), dp(20));
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.44f);
        body.addView(rightScroll, rightLp);

        sendersColumn = new LinearLayout(this);
        sendersColumn.setOrientation(LinearLayout.VERTICAL);
        rightScroll.addView(sendersColumn, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Full-Screen WebView Sign-In Overlay (hidden until user clicks Sign In)
        webOverlay = new FrameLayout(this);
        webOverlay.setBackgroundColor(0xF00B1020);
        webOverlay.setVisibility(View.GONE);
        root.addView(webOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout webShell = new LinearLayout(this);
        webShell.setOrientation(LinearLayout.VERTICAL);
        webOverlay.addView(webShell, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout webTop = new LinearLayout(this);
        webTop.setOrientation(LinearLayout.HORIZONTAL);
        webTop.setGravity(Gravity.CENTER_VERTICAL);
        webTop.setPadding(dp(24), dp(14), dp(24), dp(14));
        webTop.setBackgroundColor(0xFF13182B);
        webShell.addView(webTop, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        webHeaderTitle = new TextView(this);
        webHeaderTitle.setTextColor(Color.WHITE);
        webHeaderTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        webHeaderTitle.setTypeface(Typeface.DEFAULT_BOLD);
        webTop.addView(webHeaderTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button pickChatBtn = makeGlassButton(Ui.tr("Signed In — Pick Chat"), true);
        pickChatBtn.setOnClickListener(v -> finishSignInAndPickChat());
        LinearLayout.LayoutParams pickLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pickLp.rightMargin = dp(12);
        webTop.addView(pickChatBtn, pickLp);

        Button cancelWebBtn = makeGlassButton(Ui.tr("Close"), false);
        cancelWebBtn.setOnClickListener(v -> closeSignInOverlay());
        webTop.addView(cancelWebBtn);

        loginWebView = new WebView(this);
        ReelsWebBridgeHost.configureWebView(loginWebView);
        loginWebView.setWebViewClient(new WebViewClient());
        webShell.addView(loginWebView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private void renderAll() {
        renderSourcesColumn();
        renderSendersColumn();
    }

    private void renderSourcesColumn() {
        sourcesColumn.removeAllViews();
        addSectionLabel(sourcesColumn, Ui.tr("CONNECTED SOCIAL CHATS (MODULAR PLUGINS)"));

        // Quick 3-step account creation checklist
        LinearLayout guideBox = new LinearLayout(this);
        guideBox.setOrientation(LinearLayout.VERTICAL);
        guideBox.setBackground(glassPanelDrawable(0x162563EB, 0x4060A5FA, dp(16)));
        guideBox.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams gLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gLp.bottomMargin = dp(16);
        sourcesColumn.addView(guideBox, gLp);

        TextView gTitle = new TextView(this);
        gTitle.setText(Ui.tr("Quick Dedicated TV Account Setup (Works on iPhone, Android & 3+ Family Groups)"));
        gTitle.setTextColor(Color.WHITE);
        gTitle.setTypeface(Typeface.DEFAULT_BOLD);
        gTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        guideBox.addView(gTitle);

        TextView gBody = new TextView(this);
        gBody.setText(Ui.tr("1. Create a free dedicated TV account (e.g. @livingroom.tv) so your personal account stays private.\n2. Add that TV account to your native Instagram and/or TikTok group chat.\n3. Sign into each TV account below (TikTok supports instant QR scan; Instagram works with Companion Remote keyboard) and pick your group chat."));
        gBody.setTextColor(0xFFD1D1D6);
        gBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        gBody.setPadding(0, dp(6), 0, 0);
        guideBox.addView(gBody);

        Map<String, ReelsModels.SourceWatermark> watermarks = repo.loadWatermarks();
        for (final ReelsSourcePlugin plugin : ReelsSourceRegistry.getDefault().chatPlugins()) {
            ReelsModels.SourceWatermark wm = watermarks.get(plugin.sourceId());
            boolean connected = wm != null && wm.isConfigured();

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(glassPanelDrawable(0x14FFFFFF, connected ? 0x5534D399 : 0x26FFFFFF, dp(18)));
            card.setPadding(dp(18), dp(16), dp(18), dp(16));
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cLp.bottomMargin = dp(14);
            sourcesColumn.addView(card, cLp);

            LinearLayout rowTop = new LinearLayout(this);
            rowTop.setOrientation(LinearLayout.HORIZONTAL);
            rowTop.setGravity(Gravity.CENTER_VERTICAL);
            card.addView(rowTop);

            ImageView icon = makeSymbolView(plugin.iconName(), 24, Color.WHITE);
            LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(dp(24), dp(24));
            icLp.rightMargin = dp(12);
            rowTop.addView(icon, icLp);

            TextView name = new TextView(this);
            name.setText(plugin.displayName());
            name.setTextColor(Color.WHITE);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            rowTop.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView status = new TextView(this);
            status.setText(connected
                    ? (Ui.tr("Connected: ") + (wm.threadTitle.isEmpty() ? wm.threadId : wm.threadTitle))
                    : Ui.tr("Not Connected"));
            status.setTextColor(connected ? 0xFF34D399 : 0xFFFBBF24);
            status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            status.setTypeface(Typeface.DEFAULT_BOLD);
            rowTop.addView(status);

            TextView hint = new TextView(this);
            hint.setText(plugin.accountCreationHint());
            hint.setTextColor(0xFFC7C7CC);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            hint.setPadding(0, dp(6), 0, dp(12));
            card.addView(hint);

            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            card.addView(actions);

            Button signInBtn = makeGlassButton(
                    connected ? Ui.tr("Switch / Re-Sign In") : Ui.tr("Sign In & Pick Chat"), !connected);
            signInBtn.setOnClickListener(v -> openSignInOverlay(plugin));
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bLp.rightMargin = dp(10);
            actions.addView(signInBtn, bLp);

            if (connected) {
                Button pickBtn = makeGlassButton(Ui.tr("Change Chat"), false);
                pickBtn.setOnClickListener(v -> pickChatForPlugin(plugin));
                actions.addView(pickBtn, bLp);

                Button disconnectBtn = makeGlassButton(Ui.tr("Disconnect"), false);
                disconnectBtn.setOnClickListener(v -> {
                    repo.disconnectSource(plugin.sourceId());
                    Toast.makeText(this, plugin.displayName() + " disconnected", Toast.LENGTH_SHORT).show();
                    renderAll();
                });
                actions.addView(disconnectBtn);
            }
        }
    }

    private void renderSendersColumn() {
        sendersColumn.removeAllViews();
        addSectionLabel(sendersColumn, Ui.tr("ACCOUNT DISPLAY NAMES (CORNER BADGE)"));

        TextView desc = new TextView(this);
        desc.setText(Ui.tr("Name each family or group member so the TV shows their friendly name in the corner of every video."));
        desc.setTextColor(0xFFD1D1D6);
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        desc.setPadding(0, 0, 0, dp(14));
        sendersColumn.addView(desc);

        Map<String, ReelsModels.SenderAlias> aliases = repo.loadSenderAliases();
        if (aliases.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(Ui.tr("Participants appear automatically once you pick an Instagram or TikTok group chat."));
            empty.setTextColor(0xFF9CA3AF);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            sendersColumn.addView(empty);
            return;
        }

        for (final ReelsModels.SenderAlias alias : aliases.values()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(glassPanelDrawable(0x14FFFFFF, 0x24FFFFFF, dp(14)));
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            row.setFocusable(true);
            row.setClickable(true);
            row.setOnFocusChangeListener((v, hasFocus) -> v.setBackground(glassPanelDrawable(
                    hasFocus ? 0xFFFFFFFF : 0x14FFFFFF,
                    hasFocus ? 0xFFFFFFFF : 0x24FFFFFF,
                    dp(14))));

            ImageView icon = makeSymbolView(
                    ReelsSourceRegistry.getDefault().iconNameFor(alias.sourceId), 18, Color.WHITE);
            LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(dp(18), dp(18));
            icLp.rightMargin = dp(10);
            row.addView(icon, icLp);

            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);
            row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView nameTv = new TextView(this);
            nameTv.setText(alias.effectiveName());
            nameTv.setTextColor(Color.WHITE);
            nameTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            nameTv.setTypeface(Typeface.DEFAULT_BOLD);
            textCol.addView(nameTv);

            TextView subTv = new TextView(this);
            subTv.setText(ReelsSourceRegistry.getDefault().displayNameFor(alias.sourceId)
                    + (alias.rawUsername.isEmpty() ? "" : (" · @" + alias.rawUsername)));
            subTv.setTextColor(0xFFB0B8C8);
            subTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            textCol.addView(subTv);

            TextView editHint = new TextView(this);
            editHint.setText(Ui.tr("Rename"));
            editHint.setTextColor(0xFF93C5FD);
            editHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            row.addView(editHint);

            row.setOnFocusChangeListener((v, hasFocus) -> {
                v.setBackground(glassPanelDrawable(
                        hasFocus ? 0xFFFFFFFF : 0x14FFFFFF,
                        hasFocus ? 0xFFFFFFFF : 0x24FFFFFF,
                        dp(14)));
                nameTv.setTextColor(hasFocus ? 0xFF121622 : Color.WHITE);
                subTv.setTextColor(hasFocus ? 0xFF475569 : 0xFFB0B8C8);
                editHint.setTextColor(hasFocus ? 0xFF2563EB : 0xFF93C5FD);
                icon.setColorFilter(hasFocus ? 0xFF121622 : Color.WHITE);
            });
            row.setOnClickListener(v -> promptRenameSender(alias));

            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rLp.bottomMargin = dp(10);
            sendersColumn.addView(row, rLp);
        }
    }

    private void openSignInOverlay(ReelsSourcePlugin plugin) {
        activeLoginPlugin = plugin;
        webHeaderTitle.setText(plugin.displayName()
                + " — " + Ui.tr("Sign into your TV account, then select 'Signed In — Pick Chat'"));
        webOverlay.setVisibility(View.VISIBLE);
        loginWebView.loadUrl(plugin.loginUrl());
        loginWebView.requestFocus();
    }

    private void closeSignInOverlay() {
        webOverlay.setVisibility(View.GONE);
        activeLoginPlugin = null;
        try {
            CookieManager.getInstance().flush();
        } catch (RuntimeException ignored) {
        }
    }

    private void finishSignInAndPickChat() {
        ReelsSourcePlugin plugin = activeLoginPlugin;
        closeSignInOverlay();
        if (plugin != null) {
            pickChatForPlugin(plugin);
        }
    }

    @SuppressWarnings("unchecked")
    private void pickChatForPlugin(final ReelsSourcePlugin plugin) {
        Toast.makeText(this, Ui.tr("Loading chats from ") + plugin.displayName() + "...", Toast.LENGTH_SHORT).show();
        bridgeHost.listThreads(plugin, (ok, data, err) -> {
            if (!ok || data == null) {
                Toast.makeText(this, plugin.displayName() + ": " + (err == null || err.isEmpty() ? "Please sign in first" : err),
                        Toast.LENGTH_LONG).show();
                return;
            }
            String selfId = CloudVaultCrypto.JsonCodec.optString(data, "self_sender_id", "");
            Object rawThreads = data.get("threads");
            final List<ReelsModels.ChatThreadInfo> threads = new ArrayList<>();
            if (rawThreads instanceof List) {
                for (Object o : (List<?>) rawThreads) {
                    if (!(o instanceof Map)) continue;
                    Map<String, Object> m = (Map<String, Object>) o;
                    String tid = CloudVaultCrypto.JsonCodec.optString(m, "thread_id", "");
                    if (tid.isEmpty()) continue;
                    String title = CloudVaultCrypto.JsonCodec.optString(m, "title", plugin.displayName() + " Chat");
                    boolean isGroup = CloudVaultCrypto.JsonCodec.optBoolean(m, "is_group", false);
                    List<ReelsModels.SenderAlias> parts = new ArrayList<>();
                    Object rawParts = m.get("participants");
                    if (rawParts instanceof List) {
                        for (Object po : (List<?>) rawParts) {
                            if (!(po instanceof Map)) continue;
                            Map<String, Object> pm = (Map<String, Object>) po;
                            parts.add(new ReelsModels.SenderAlias(
                                    plugin.sourceId(),
                                    CloudVaultCrypto.JsonCodec.optString(pm, "sender_id", ""),
                                    CloudVaultCrypto.JsonCodec.optString(pm, "raw_username", ""),
                                    CloudVaultCrypto.JsonCodec.optString(pm, "display_name", ""),
                                    0));
                        }
                    }
                    threads.add(new ReelsModels.ChatThreadInfo(plugin.sourceId(), tid, title, isGroup, parts));
                }
            }
            if (threads.isEmpty()) {
                Toast.makeText(this, Ui.tr("No chats found yet. Send a message in your group chat first and try again."),
                        Toast.LENGTH_LONG).show();
                return;
            }
            CharSequence[] labels = new CharSequence[threads.size()];
            for (int i = 0; i < threads.size(); i++) {
                ReelsModels.ChatThreadInfo t = threads.get(i);
                labels[i] = (t.isGroup ? "[Group] " : "[Chat] ") + t.title;
            }
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(plugin.displayName() + " — " + Ui.tr("Choose Group or Chat"))
                    .setItems(labels, (d, which) -> {
                        ReelsModels.ChatThreadInfo chosen = threads.get(which);
                        Map<String, ReelsModels.SourceWatermark> existing = repo.loadWatermarks();
                        ReelsModels.SourceWatermark prev = existing.get(plugin.sourceId());
                        String keepMsgId = (prev != null && chosen.threadId.equals(prev.threadId)) ? prev.lastWatchedMessageId : "";
                        long keepTs = (prev != null && chosen.threadId.equals(prev.threadId)) ? prev.lastWatchedSentAtMs : 0L;
                        repo.saveWatermark(new ReelsModels.SourceWatermark(
                                plugin.sourceId(), chosen.threadId, chosen.title, selfId, keepMsgId, keepTs, ""));
                        repo.discoverSenders(chosen.participants);
                        Toast.makeText(this, Ui.tr("Connected ") + plugin.displayName() + ": " + chosen.title,
                                Toast.LENGTH_SHORT).show();
                        renderAll();
                    })
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .show();
        });
    }

    private void promptRenameSender(final ReelsModels.SenderAlias alias) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(alias.effectiveName());
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Ui.tr("Name Account") + " (" + ReelsSourceRegistry.getDefault().displayNameFor(alias.sourceId) + ")")
                .setView(input)
                .setPositiveButton(Ui.tr("Save"), (d, w) -> {
                    String next = input.getText().toString().trim();
                    if (!next.isEmpty()) {
                        repo.saveSenderAlias(alias.sourceId, alias.senderId, alias.rawUsername, next);
                        renderSendersColumn();
                    }
                })
                .setNegativeButton(Ui.tr("Cancel"), null)
                .show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webOverlay.getVisibility() == View.VISIBLE) {
            if (loginWebView != null && loginWebView.canGoBack()) {
                loginWebView.goBack();
                return true;
            }
            closeSignInOverlay();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void addSectionLabel(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0x88FFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setLetterSpacing(0.08f);
        tv.setPadding(0, 0, 0, dp(12));
        parent.addView(tv);
    }

    private Button makeGlassButton(String label, final boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(dp(18), dp(10), dp(18), dp(10));
        b.setTextColor(primary ? 0xFF121622 : Color.WHITE);
        b.setBackground(glassPanelDrawable(primary ? 0xFFFFFFFF : 0x22FFFFFF, primary ? 0xFFFFFFFF : 0x3AFFFFFF, dp(22)));
        b.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                b.setBackground(glassPanelDrawable(0xFFFFFFFF, 0xFFFFFFFF, dp(22)));
                b.setTextColor(0xFF121622);
            } else {
                b.setBackground(glassPanelDrawable(primary ? 0xEBFFFFFF : 0x22FFFFFF, primary ? 0xFFFFFFFF : 0x3AFFFFFF, dp(22)));
                b.setTextColor(primary ? 0xFF121622 : Color.WHITE);
            }
        });
        return b;
    }

    private ImageView makeSymbolView(String iconName, int sizeDp, int tint) {
        ImageView iv = new ImageView(this);
        Bitmap bmp = Symbols.get(this, iconName);
        if (bmp != null) {
            iv.setImageBitmap(bmp);
            iv.setColorFilter(tint);
        }
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return iv;
    }

    private GradientDrawable glassPanelDrawable(int fillColor, int strokeColor, int radiusPx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fillColor);
        gd.setCornerRadius(radiusPx);
        gd.setStroke(dp(1), strokeColor);
        return gd;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
