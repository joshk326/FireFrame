package com.fireframe.home;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// The full-screen app menu / drawer, listing all installed apps with support for hiding/unhiding apps.
public class AppMenu {
    static final String PREF_HIDDEN_APPS = "user_hidden_apps";

    final HomeScreen home;
    final Context ctx;

    FrameLayout currentLayer;
    GridLayout currentGrid;
    ScrollView currentScroll;
    TextView hiddenBtnText;

    public AppMenu(HomeScreen home) {
        this.home = home;
        this.ctx = home.ctx;
    }

    public static Set<String> getHiddenApps(Context c) {
        SharedPreferences p = c.getSharedPreferences("fireframe", Context.MODE_PRIVATE);
        String raw = p.getString(PREF_HIDDEN_APPS, null);
        Set<String> set = new HashSet<>();
        if (raw != null && !raw.trim().isEmpty()) {
            for (String pkg : raw.split(",")) {
                String trimmed = pkg.trim();
                if (!trimmed.isEmpty()) set.add(trimmed);
            }
        }
        return set;
    }

    public static boolean isAppHidden(Context c, String pkg) {
        return getHiddenApps(c).contains(pkg);
    }

    public static void setAppHidden(Context c, String pkg, boolean hide) {
        Set<String> set = getHiddenApps(c);
        if (hide) {
            set.add(pkg);
        } else {
            set.remove(pkg);
        }
        SharedPreferences p = c.getSharedPreferences("fireframe", Context.MODE_PRIVATE);
        p.edit().putString(PREF_HIDDEN_APPS, TextUtils.join(",", set)).apply();
    }

    public static void unhideAll(Context c) {
        SharedPreferences p = c.getSharedPreferences("fireframe", Context.MODE_PRIVATE);
        p.edit().remove(PREF_HIDDEN_APPS).apply();
    }

    public void open(View anchor) {
        FrameLayout layer = new FrameLayout(ctx);
        currentLayer = layer;
        layer.addView(home.new GlassView(ctx, 0, 0xA6000000), new FrameLayout.LayoutParams(-1, -1));

        TextView title = new TextView(ctx);
        home.style(title, 48, home.semibold);
        title.setText("Apps");
        layer.addView(title, home.at(140, 64, -2, -2));

        TextView hint = new TextView(ctx);
        home.style(hint, 24, home.medium);
        hint.setAlpha(0.6f);
        hint.setText("Hold OK or press Menu to add an app to Home or hide it");
        layer.addView(hint, home.at(140, 136, -2, -2));

        // Top right: "Hidden apps" menu button
        FrameLayout hiddenBtn = home.focusTile(22, 1.05f);
        hiddenBtnText = new TextView(ctx);
        home.style(hiddenBtnText, 24, home.medium);
        updateHiddenButtonText();
        hiddenBtnText.setPadding(home.px(28), home.px(16), home.px(28), home.px(16));
        hiddenBtn.addView(hiddenBtnText, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        hiddenBtn.setOnClickListener(this::showHiddenAppsMenu);

        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
        hlp.topMargin = home.px(64);
        hlp.rightMargin = home.px(110);
        layer.addView(hiddenBtn, hlp);

        currentScroll = new ScrollView(ctx);
        currentScroll.setVerticalScrollBarEnabled(false);
        currentScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        currentScroll.setClipToPadding(false);
        currentScroll.setPadding(home.px(110), home.px(20), home.px(110), home.px(80));

        currentGrid = new GridLayout(ctx);
        currentGrid.setClipChildren(false);
        currentGrid.setColumnCount(6);

        currentScroll.addView(currentGrid);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-1, -1);
        slp.topMargin = home.px(190);
        layer.addView(currentScroll, slp);

        View first = populateGrid(0);
        home.openLayer(layer, anchor, first != null ? first : hiddenBtn);
    }

    void updateHiddenButtonText() {
        if (hiddenBtnText == null) return;
        int count = getHiddenApps(ctx).size();
        hiddenBtnText.setText(count > 0 ? "Hidden apps (" + count + ")" : "Hidden apps");
    }

    View populateGrid(int focusIndex) {
        if (currentGrid == null) return null;
        currentGrid.removeAllViews();
        Map<String, String> apps = home.installed();
        Set<String> hidden = getHiddenApps(ctx);
        View focusTarget = null;
        int index = 0;

        for (Map.Entry<String, String> a : apps.entrySet()) {
            if (hidden.contains(a.getKey())) continue;
            View t = home.tile(a.getKey(), a.getValue(), 270, 196, false);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = home.px(270);
            lp.height = home.px(196);
            lp.setMargins(home.px(5), home.px(5), home.px(5), home.px(5));
            currentGrid.addView(t, lp);
            if (index == focusIndex || focusTarget == null) {
                focusTarget = t;
            }
            index++;
        }

        updateHiddenButtonText();
        return focusTarget;
    }

    void refreshGrid() {
        View target = populateGrid(0);
        if (target != null && currentLayer != null && currentLayer.isAttachedToWindow()) {
            target.requestFocus();
        }
    }

    public void showTileMenu(View anchor, final String pkg, final String label) {
        final int i = home.dock.indexOf(pkg);
        String[] items = new String[] {
            i >= 0 ? "Remove from Home" : "Add to Home",
            "Hide app",
            "App info"
        };
        home.menu(anchor, items, which -> {
            if (which == 0) {
                if (i >= 0) home.dock.remove(pkg); else home.dock.add(pkg);
                home.saveDock();
                home.buildDock();
                home.showCaption(i >= 0 ? "Removed from Home" : "Added to Home");
            } else if (which == 1) {
                hideApp(pkg, label);
            } else if (which == 2) {
                home.start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
            }
        });
    }

    void hideApp(String pkg, String label) {
        setAppHidden(ctx, pkg, true);
        if (home.dock.contains(pkg)) {
            home.dock.remove(pkg);
            home.saveDock();
            home.buildDock();
        }
        home.showCaption("Hidden from Apps");
        refreshGrid();
    }

    public void showHiddenAppsMenu(View anchor) {
        Set<String> hidden = getHiddenApps(ctx);
        Map<String, String> allApps = home.installed();

        final List<String> hiddenPkgs = new ArrayList<>();
        final List<String> menuItems = new ArrayList<>();

        for (String p : hidden) {
            String label = allApps.get(p);
            if (label == null) {
                try {
                    label = ctx.getPackageManager().getApplicationInfo(p, 0)
                            .loadLabel(ctx.getPackageManager()).toString();
                } catch (Exception ignored) {
                    label = p;
                }
            }
            hiddenPkgs.add(p);
            menuItems.add("Unhide " + label);
        }

        if (hiddenPkgs.isEmpty()) {
            home.menu(anchor, new String[] {"No hidden apps"}, which -> {});
            return;
        }

        if (hiddenPkgs.size() > 1) {
            menuItems.add("Unhide all apps");
        }

        home.menu(anchor, menuItems.toArray(new String[0]), which -> {
            if (hiddenPkgs.size() > 1 && which == menuItems.size() - 1) {
                unhideAll(ctx);
                home.showCaption("Unhid all apps");
            } else {
                String pkg = hiddenPkgs.get(which);
                String label = allApps.get(pkg);
                setAppHidden(ctx, pkg, false);
                home.showCaption("Unhid " + (label != null ? label : "app"));
            }
            refreshGrid();
        });
    }
}
