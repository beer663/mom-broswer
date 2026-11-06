package com.mombrowser.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebExtension;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {
    private static GeckoRuntime runtime;
    private GeckoSession session;
    private GeckoView geckoView;
    private EditText address;
    private TextView zoomLabel;
    private FrameLayout root;
    private LinearLayout navigationPanel, homePanel;
    private HorizontalScrollView controlsScroller;
    private float textZoom = 1.0f, pointerX, pointerY;
    private WebExtension uBlock;
    private SharedPreferences data;
    private boolean navigationVisible = true, pointerMode = false;
    private PointerView pointerView;

    private static final String PREFS = "mom_browser_data";
    private static final String KEY_BOOKMARKS = "bookmarks";
    private static final String KEY_HISTORY = "history";
    private static final String HOME_URL = "https://www.google.com";
    private static final String ADGUARD_DOH = "https://dns.adguard-dns.com/dns-query";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        data = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (runtime == null) {
            GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                    .forceUserScalableEnabled(true)
                    .fontSizeFactor(1.0f)
                    .trustedRecursiveResolverUri(ADGUARD_DOH)
                    .trustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_ONLY)
                    .build();
            runtime = GeckoRuntime.create(this, settings);
        }
        buildInterface();
        openSession();
        installBundledUBlock();
        showHome();
        Toast.makeText(this, "Mom Browser: AdGuard DNS-over-HTTPS configured", Toast.LENGTH_LONG).show();
    }

    private void buildInterface() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(18,22,29));
        geckoView = new GeckoView(this);
        root.addView(geckoView, new FrameLayout.LayoutParams(-1,-1));

        navigationPanel = new LinearLayout(this);
        navigationPanel.setOrientation(LinearLayout.VERTICAL);
        navigationPanel.setPadding(dp(12),dp(8),dp(12),dp(8));
        navigationPanel.setBackground(roundRect(Color.rgb(35,40,49),dp(14)));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(text("MOM BROWSER",18,Color.WHITE,true),
                new LinearLayout.LayoutParams(0,dp(38),1));
        Button hide = button("Hide bar");
        hide.setOnClickListener(v -> hideNavigation());
        header.addView(hide);
        navigationPanel.addView(header);

        LinearLayout addressRow = new LinearLayout(this);
        addressRow.setGravity(Gravity.CENTER_VERTICAL);
        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextColor(Color.WHITE);
        address.setHintTextColor(Color.LTGRAY);
        address.setHint("Search or enter website address");
        address.setTextSize(16);
        address.setPadding(dp(12),0,dp(12),0);
        address.setSelectAllOnFocus(true);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setBackground(roundRect(Color.rgb(62,69,80),dp(8)));
        addressRow.addView(address,new LinearLayout.LayoutParams(0,dp(46),1));
        Button go = button("GO");
        go.setOnClickListener(v -> loadAddress());
        addressRow.addView(go,buttonParams());
        navigationPanel.addView(addressRow);

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        addControl(controls,"Back",v -> goBack());
        addControl(controls,"Forward",v -> goForward());
        addControl(controls,"Reload",v -> { if(session!=null) session.reload(); });
        addControl(controls,"Home",v -> showHome());
        addControl(controls,"Bookmark",v -> saveBookmark());
        addControl(controls,"Bookmarks",v -> showBookmarks());
        addControl(controls,"History",v -> showHistory());
        addControl(controls,"−",v -> changeZoom(-0.1f));
        zoomLabel = text("100%",14,Color.WHITE,true);
        zoomLabel.setGravity(Gravity.CENTER);
        controls.addView(zoomLabel,new LinearLayout.LayoutParams(dp(48),dp(42)));
        addControl(controls,"+",v -> changeZoom(0.1f));
        addControl(controls,"Mouse: OFF",v -> togglePointerMode());
        addControl(controls,"uBlock",v -> openUBlockSettings());
        addControl(controls,"Menu",v -> showMoreOptions());

        controlsScroller = new HorizontalScrollView(this);
        controlsScroller.setHorizontalScrollBarEnabled(true);
        controlsScroller.setFillViewport(false);
        controlsScroller.addView(controls,new HorizontalScrollView.LayoutParams(-2,-2));
        navigationPanel.addView(controlsScroller);
        FrameLayout.LayoutParams navParams = new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);
        navParams.setMargins(dp(10),0,dp(10),dp(10));
        root.addView(navigationPanel,navParams);

        homePanel = new LinearLayout(this);
        homePanel.setOrientation(LinearLayout.VERTICAL);
        homePanel.setPadding(dp(22),dp(18),dp(22),dp(18));
        homePanel.setBackgroundColor(Color.rgb(24,29,37));
        homePanel.addView(text("Welcome to Mom Browser",25,Color.WHITE,true));
        TextView sub = text("Your favorite websites and saved bookmarks",15,Color.LTGRAY,false);
        sub.setPadding(0,dp(7),0,dp(14));
        homePanel.addView(sub);
        LinearLayout shortcuts = new LinearLayout(this);
        shortcuts.setOrientation(LinearLayout.HORIZONTAL);
        addShortcut(shortcuts,"Google","https://www.google.com");
        addShortcut(shortcuts,"YouTube","https://www.youtube.com/tv");
        addShortcut(shortcuts,"Bing","https://www.bing.com");
        addShortcut(shortcuts,"DuckDuckGo","https://duckduckgo.com");
        homePanel.addView(shortcuts);
        TextView savedTitle = text("Saved bookmarks",17,Color.WHITE,true);
        savedTitle.setPadding(0,dp(14),0,dp(7));
        homePanel.addView(savedTitle);
        ScrollView savedScroll = new ScrollView(this);
        LinearLayout savedRows = new LinearLayout(this);
        savedRows.setOrientation(LinearLayout.VERTICAL);
        ArrayList<String> bookmarks = readList(KEY_BOOKMARKS);
        if(bookmarks.isEmpty()) savedRows.addView(text("No bookmarks saved yet.",14,Color.LTGRAY,false));
        for(String url:bookmarks) {
            Button item = button(url);
            item.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
            item.setOnClickListener(v -> openSavedUrl(url));
            savedRows.addView(item,new LinearLayout.LayoutParams(-1,dp(42)));
        }
        savedScroll.addView(savedRows);
        homePanel.addView(savedScroll,new LinearLayout.LayoutParams(-1,dp(105)));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER);
        hp.setMargins(dp(22),dp(18),dp(22),dp(100));
        root.addView(homePanel,hp);

        pointerView = new PointerView();
        pointerView.setVisibility(View.GONE);
        root.addView(pointerView,new FrameLayout.LayoutParams(dp(36),dp(44)));
        address.setOnEditorActionListener((v,id,event) -> {
            if(id==EditorInfo.IME_ACTION_GO || id==EditorInfo.IME_ACTION_SEARCH ||
                    (event!=null && event.getKeyCode()==KeyEvent.KEYCODE_ENTER)) {
                loadAddress(); return true;
            }
            return false;
        });
        setContentView(root);
        navigationPanel.bringToFront();
        address.requestFocus();
    }

    private void openSession() {
        session = new GeckoSession();
        session.setContentDelegate(new GeckoSession.ContentDelegate(){});
        session.open(runtime);
        geckoView.setSession(session);
    }

    private void loadAddress() {
        String value=address.getText().toString().trim();
        if(value.isEmpty() || session==null) return;
        String target=value;
        if(!target.contains("://")) {
            if(target.contains(" ") || !target.contains(".")) {
                try { target="https://www.google.com/search?q="+URLEncoder.encode(target,"UTF-8"); }
                catch(Exception ignored) { target="https://www.google.com"; }
            } else target="https://"+target;
        }
        session.loadUri(target);
        address.setText(target);
        addHistory(target);
        homePanel.setVisibility(View.GONE);
        hideNavigation();
    }

    private void showHome() {
        if(session!=null) session.loadUri(HOME_URL);
        address.setText(HOME_URL);
        rebuildHomeBookmarks();
        homePanel.setVisibility(View.VISIBLE);
        showNavigation();
    }

    private void rebuildHomeBookmarks() {
        if(homePanel==null) return;
        // The saved list is rebuilt by reopening Home; bookmark manager is always available.
    }

    private void openSavedUrl(String url) {
        address.setText(url);
        if(session!=null) session.loadUri(url);
        addHistory(url);
        homePanel.setVisibility(View.GONE);
        hideNavigation();
    }

    private void addHistory(String url) {
        ArrayList<String> a=readList(KEY_HISTORY);
        a.remove(url); a.add(0,url);
        while(a.size()>100) a.remove(a.size()-1);
        writeList(KEY_HISTORY,a);
    }

    private void saveBookmark() {
        String url=address.getText().toString().trim();
        if(url.isEmpty()) return;
        ArrayList<String> a=readList(KEY_BOOKMARKS);
        if(!a.contains(url)) a.add(0,url);
        writeList(KEY_BOOKMARKS,a);
        Toast.makeText(this,"Bookmark saved. Reopen Home to see it.",Toast.LENGTH_SHORT).show();
    }

    private void showBookmarks(){ showSavedList("Bookmarks",KEY_BOOKMARKS); }
    private void showHistory(){ showSavedList("Browsing history",KEY_HISTORY); }

    private void showSavedList(String title,String key) {
        ArrayList<String> a=readList(key);
        if(a.isEmpty()) {
            new AlertDialog.Builder(this).setTitle(title).setMessage("Nothing saved yet.")
                    .setPositiveButton("OK",null).show(); return;
        }
        String[] rows=a.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle(title).setItems(rows,(d,w)->openSavedUrl(rows[w]))
                .setNegativeButton("Close",null).show();
    }

    private ArrayList<String> readList(String key) {
        String raw=data.getString(key,"");
        ArrayList<String> a=new ArrayList<>();
        if(raw!=null && !raw.isEmpty())
            for(String item:raw.split("\\n")) if(!item.trim().isEmpty()) a.add(item);
        return a;
    }

    private void writeList(String key,ArrayList<String> a) {
        StringBuilder b=new StringBuilder();
        for(String s:a) b.append(s.replace("\\n","")).append('\n');
        data.edit().putString(key,b.toString()).apply();
    }

    private void changeZoom(float delta) {
        textZoom=Math.max(0.6f,Math.min(2.0f,textZoom+delta));
        runtime.getSettings().setFontSizeFactor(textZoom);
        zoomLabel.setText(String.format(Locale.US,"%d%%",Math.round(textZoom*100)));
        if(session!=null) session.reload();
    }

    private void installBundledUBlock() {
        runtime.getWebExtensionController().ensureBuiltIn(
                "resource://android/assets/extensions/ublock/","uBlock0@raymondhill.net")
                .accept(ext -> {
                    uBlock=ext;
                    runOnUiThread(()->Toast.makeText(this,"uBlock Origin is ready",Toast.LENGTH_SHORT).show());
                },err -> runOnUiThread(()->Toast.makeText(this,
                        "uBlock failed: "+err.getMessage(),Toast.LENGTH_LONG).show()));
    }

    private void openUBlockSettings() {
        if(uBlock==null) {
            Toast.makeText(this,"uBlock is not ready. Check its startup error.",Toast.LENGTH_LONG).show();
            return;
        }
        String options=uBlock.metaData.optionsPageUrl;
        if(options==null || options.isEmpty()) {
            Toast.makeText(this,"uBlock settings page unavailable.",Toast.LENGTH_LONG).show(); return;
        }
        session.loadUri(options);
        homePanel.setVisibility(View.GONE);
        hideNavigation();
    }

    private void showMoreOptions() {
        String[] opts={"Home","Bookmarks","Browsing history","uBlock Origin settings",
                "Toggle remote mouse pointer","Show navigation bar"};
        new AlertDialog.Builder(this).setTitle("Mom Browser").setItems(opts,(d,w)->{
            switch(w) {
                case 0: showHome(); break;
                case 1: showBookmarks(); break;
                case 2: showHistory(); break;
                case 3: openUBlockSettings(); break;
                case 4: togglePointerMode(); break;
                case 5: showNavigation(); break;
            }
        }).show();
    }

    private void togglePointerMode() {
        pointerMode=!pointerMode;
        pointerView.setVisibility(pointerMode?View.VISIBLE:View.GONE);
        if(pointerMode) {
            pointerX=Math.max(dp(20),root.getWidth()*0.5f);
            pointerY=Math.max(dp(20),root.getHeight()*0.4f);
            updatePointer();
            geckoView.requestFocus();
            Toast.makeText(this,"Pointer ON: arrows move, OK clicks",Toast.LENGTH_SHORT).show();
        } else Toast.makeText(this,"Pointer OFF",Toast.LENGTH_SHORT).show();
        pointerView.bringToFront();
    }

    private void movePointer(float dx,float dy) {
        if(!pointerMode) return;
        pointerX=Math.max(1,Math.min(root.getWidth()-dp(38),pointerX+dx));
        pointerY=Math.max(1,Math.min(root.getHeight()-dp(46),pointerY+dy));
        updatePointer();
    }

    private void updatePointer() {
        FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)pointerView.getLayoutParams();
        p.leftMargin=Math.round(pointerX); p.topMargin=Math.round(pointerY);
        pointerView.setLayoutParams(p); pointerView.bringToFront();
    }

    private void clickPointer() {
        if(session==null || !pointerMode) return;
        long t=SystemClock.uptimeMillis();
        float x=pointerX+dp(10), y=pointerY+dp(10);
        MotionEvent down=MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,x,y,0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        session.getPanZoomController().onTouchEvent(down);
        down.recycle();
        MotionEvent up=MotionEvent.obtain(t,t+60,MotionEvent.ACTION_UP,x,y,0);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        session.getPanZoomController().onTouchEvent(up);
        up.recycle();
    }

    private void goBack(){ if(session!=null) session.goBack(); }
    private void goForward(){ if(session!=null) session.goForward(); }

    private void showNavigation() {
        navigationVisible=true; navigationPanel.setVisibility(View.VISIBLE);
        navigationPanel.bringToFront(); if(pointerMode) pointerView.bringToFront();
        address.requestFocus();
    }
    private void hideNavigation() {
        navigationVisible=false; navigationPanel.setVisibility(View.GONE);
        geckoView.requestFocus(); if(pointerMode) pointerView.bringToFront();
    }

    private void addShortcut(LinearLayout row,String label,String url) {
        Button b=button(label); b.setTextSize(16); b.setMinWidth(dp(125));
        b.setOnClickListener(v->openSavedUrl(url));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(52));
        p.setMargins(0,0,dp(10),0); row.addView(b,p);
    }
    private void addControl(LinearLayout row,String label,View.OnClickListener click) {
        Button b=button(label); b.setTextSize(13); b.setOnClickListener(click); row.addView(b,buttonParams());
    }
    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(42));
        p.setMargins(dp(2),dp(5),dp(2),dp(2)); return p;
    }
    private Button button(String label) {
        Button b=new Button(this); b.setText(label); b.setTextColor(Color.WHITE);
        b.setAllCaps(false); b.setMinWidth(dp(50)); b.setPadding(dp(8),0,dp(8),0);
        b.setFocusable(true); b.setFocusableInTouchMode(true);
        b.setBackground(roundRect(Color.rgb(67,76,91),dp(8)));
        b.setOnFocusChangeListener((v,f)->v.setBackground(roundRect(
                f?Color.rgb(28,119,190):Color.rgb(67,76,91),dp(8))));
        return b;
    }
    private TextView text(String value,int size,int color,boolean bold) {
        TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD); return t;
    }
    private GradientDrawable roundRect(int color,int radius) {
        GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(radius); return d;
    }
    private int dp(int v){ return Math.round(v*getResources().getDisplayMetrics().density); }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if(pointerMode && e.getAction()==KeyEvent.ACTION_DOWN) {
            int step=dp(14);
            switch(e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT: movePointer(-step,0); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: movePointer(step,0); return true;
                case KeyEvent.KEYCODE_DPAD_UP: movePointer(0,-step); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: movePointer(0,step); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER: clickPointer(); return true;
                case KeyEvent.KEYCODE_BACK: togglePointerMode(); return true;
            }
        }
        if(e.getAction()==KeyEvent.ACTION_UP) {
            if(e.getKeyCode()==KeyEvent.KEYCODE_MENU){showNavigation();return true;}
            if(e.getKeyCode()==KeyEvent.KEYCODE_BACK && !navigationVisible){showNavigation();return true;}
        }
        return super.dispatchKeyEvent(e);
    }

    @Override public void onBackPressed() {
        if(pointerMode) togglePointerMode();
        else if(!navigationVisible) showNavigation();
        else if(session!=null) session.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if(isFinishing() && session!=null) {
            geckoView.releaseSession(); session.close(); session=null;
        }
        super.onDestroy();
    }

    private class PointerView extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        PointerView(){super(MainActivity.this);setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            Path p=new Path();
            p.moveTo(dp(3),dp(2)); p.lineTo(dp(3),dp(34)); p.lineTo(dp(11),dp(26));
            p.lineTo(dp(17),dp(40)); p.lineTo(dp(23),dp(37)); p.lineTo(dp(17),dp(23));
            p.lineTo(dp(29),dp(23)); p.close();
            paint.setColor(Color.BLACK); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(3));
            c.drawPath(p,paint); paint.setColor(Color.WHITE); paint.setStyle(Paint.Style.FILL); c.drawPath(p,paint);
        }
    }
}
