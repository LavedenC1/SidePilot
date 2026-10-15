package com.laveden.sideswipepilot;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int CAPTURE = 1002;
    private int pendingMode = PilotService.MODE_TRAIN;
    private LinearLayout body;
    private TextView gpu, side, interval;
    private boolean gpuOn;
    private boolean defendLeft;
    private int scanInterval;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        gpuOn = getPreferences(0).getBoolean("gpu", false);
        defendLeft = getPreferences(0).getBoolean("defendLeft", true);
        scanInterval = getPreferences(0).getInt("interval", 50);
        draw();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
    }

    private int dp(float v){ return (int)(getResources().getDisplayMetrics().density*v + .5f); }
    private TextView label(String text,int size,int color){
        TextView t = new TextView(this); t.setText(text); t.setTextSize(size); t.setTextColor(color);
        t.setPadding(0,dp(6),0,dp(6)); return t;
    }
    private void draw() {
        int white=Color.rgb(237,245,251), muted=Color.rgb(153,174,189);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true);
        body=new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(22),dp(32),dp(22),dp(25));
        body.setBackgroundColor(Color.rgb(10,17,29)); scroll.addView(body); setContentView(scroll);
        TextView head=label("SIDESWIPE  /  PILOT",28,white); head.setTypeface(Typeface.DEFAULT,Typeface.BOLD); body.addView(head);
        body.addView(label("Standalone screen-vision controller  •  KernelSU root",13,muted));
        gap(15);
        body.addView(label("QUICK START",12,Color.rgb(58,217,188)));
        body.addView(label("1  Grant floating-window permission.\n2  Configure your controls and side.\n3  Start training or live assist, approve screen sharing, then open Sideswipe.\n4  Tap the floating PILOT button to pause/resume; hold it for calibration.",15,white));
        gap(18);
        button("START TRAINING  •  freeplay / bots",()->start(PilotService.MODE_TRAIN));
        button("START LIVE ASSIST",()->start(PilotService.MODE_LIVE));
        button("STOP PILOT",()->{stopService(new Intent(this,PilotService.class)); toast("Pilot stopped");});
        gap(14);
        button("ALLOW FLOATING CONTROLS",()->{
            Intent i=new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:"+getPackageName())); startActivity(i);
        });
        button("OPEN ROCKET LEAGUE SIDESWIPE",()->{
            Intent i=getPackageManager().getLaunchIntentForPackage("com.Psyonix.RL2D");
            if(i==null){toast("Open Sideswipe manually (package name may vary)");return;}
            startActivity(i);
        });
        gap(16);
        body.addView(label("TRACKING & INPUT",12,Color.rgb(58,217,188)));
        gpu=label("",14,white); body.addView(gpu);
        button("TOGGLE EXPERIMENTAL GPU TRACKER",()->{gpuOn=!gpuOn;persist();refresh();});
        side=label("",14,white);body.addView(side);
        button("CHANGE OWN GOAL SIDE",()->{defendLeft=!defendLeft;persist();refresh();});
        interval=label("",14,white);body.addView(interval);
        button("CHANGE SCAN RATE",()->{scanInterval=scanInterval==50?33:scanInterval==33?75:50;persist();refresh();});
        gap(14);
        body.addView(label("Touch layout (landscape): left stick at 12%,80%; boost at 77%,80%; jump at 92%,64%; flip at 92%,84%. Pilot can mispress if your HUD differs. Live mode controls the game; do not use it in ranked matches.",13,muted));
        gap(15);
        body.addView(label("Vision-based boost/flip-reset estimation is experimental. No other process memory is read or patched. KernelSU can grant root to the external touch controller when asked.",12,muted));
        refresh();
    }
    private void gap(int height){View v=new View(this);body.addView(v,new LinearLayout.LayoutParams(1,dp(height)));}
    private void button(String text,Runnable action){
        Button b=new Button(this); b.setText(text); b.setAllCaps(false); b.setTextColor(Color.WHITE);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(25,47,64)));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(54));p.bottomMargin=dp(8); body.addView(b,p);
        b.setOnClickListener(v->action.run());
    }
    private void persist(){getPreferences(0).edit().putBoolean("gpu",gpuOn).putBoolean("defendLeft",defendLeft).putInt("interval",scanInterval).apply();}
    private void refresh(){gpu.setText("GPU edge-scoring: "+(gpuOn?"ON (fallback to CPU)":"OFF (CPU only)"));side.setText("Defend: "+(defendLeft?"LEFT":"RIGHT"));interval.setText("Frame analysis interval: "+scanInterval+" ms");}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private void start(int mode){
        if(!Settings.canDrawOverlays(this)){toast("Grant floating-window permission first");startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:"+getPackageName())));return;}
        pendingMode=mode;
        MediaProjectionManager m=(MediaProjectionManager)getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if(m==null){toast("Screen capture unavailable");return;}
        startActivityForResult(m.createScreenCaptureIntent(),CAPTURE);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==CAPTURE && result==RESULT_OK && data!=null){
            Intent svc=new Intent(this,PilotService.class);
            svc.putExtra(PilotService.EXTRA_RESULT,result);
            svc.putExtra(PilotService.EXTRA_DATA,data);
            svc.putExtra(PilotService.EXTRA_MODE,pendingMode);
            svc.putExtra(PilotService.EXTRA_GPU,gpuOn);
            svc.putExtra(PilotService.EXTRA_DEFEND_LEFT,defendLeft);
            svc.putExtra(PilotService.EXTRA_INTERVAL,scanInterval);
            if(Build.VERSION.SDK_INT>=26)startForegroundService(svc);else startService(svc);
            toast("Capture enabled. Switch to Sideswipe; PILOT overlay stays available.");
        } else if(request==CAPTURE)toast("Screen capture permission required");
    }
}
