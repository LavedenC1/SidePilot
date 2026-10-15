package com.laveden.sideswipepilot;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.*;
import android.hardware.display.*;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.*;
import android.widget.*;
import java.nio.ByteBuffer;

public class PilotService extends Service implements DisplayManager.DisplayListener {
    static final String EXTRA_RESULT="capture_result", EXTRA_DATA="capture_data", EXTRA_MODE="pilot_mode";
    static final String EXTRA_GPU="pilot_gpu", EXTRA_DEFEND_LEFT="defend_left", EXTRA_INTERVAL="interval";
    static final int MODE_PAUSE=0, MODE_TRAIN=1, MODE_LIVE=2;
    private static final int NOTIF=3002;
    private MediaProjection projection;
    private VirtualDisplay mirror;
    private ImageReader reader;
    private HandlerThread visionThread;
    private Handler visionHandler, mainHandler;
    private DisplayManager displays;
    private WindowManager wm;
    private TextView bubble;
    private WindowManager.LayoutParams bubbleParams;
    private View calibration;
    private int calibrationTarget=0;
    private final VisionTracker tracker=new VisionTracker();
    private final PilotBrain brain=new PilotBrain();
    private RootController root;
    private GpuRingScanner gpu;
    private boolean gpuEnabled=false, defendLeft=true, alive=false;
    private int mode=MODE_PAUSE, scanInterval=50, captureW=640, captureH=296;
    private long frameMs=0, frames=0, lastUi=0;
    private float fps=0;
    private VisionTracker.Observation latest;

    @Override public void onCreate(){
        super.onCreate();
        mainHandler=new Handler(Looper.getMainLooper());
        displays=(DisplayManager)getSystemService(DISPLAY_SERVICE);
        wm=(WindowManager)getSystemService(WINDOW_SERVICE);
        visionThread=new HandlerThread("PilotVision",Process.THREAD_PRIORITY_DISPLAY);
        visionThread.start(); visionHandler=new Handler(visionThread.getLooper());
        createNotificationChannel();
        root=new RootController();
        brain.load(getSharedPreferences("training",MODE_PRIVATE));
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null)return START_NOT_STICKY;
        if(alive){mode=intent.getIntExtra(EXTRA_MODE,mode);updateBubble();return START_NOT_STICKY;}
        mode=intent.getIntExtra(EXTRA_MODE,MODE_TRAIN);
        gpuEnabled=intent.getBooleanExtra(EXTRA_GPU,false);
        defendLeft=intent.getBooleanExtra(EXTRA_DEFEND_LEFT,true);
        scanInterval=intent.getIntExtra(EXTRA_INTERVAL,50);
        Notification n=notification("Preparing screen capture");
        try { if(Build.VERSION.SDK_INT>=29)startForeground(NOTIF,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
              else startForeground(NOTIF,n); }
        catch (Exception ex){ stopSelf();return START_NOT_STICKY; }
        int result=intent.getIntExtra(EXTRA_RESULT,0);
        Intent data;
        if(Build.VERSION.SDK_INT>=33) data=intent.getParcelableExtra(EXTRA_DATA,Intent.class);
        else data=(Intent)intent.getParcelableExtra(EXTRA_DATA);
        if(result!=Activity.RESULT_OK || data==null){stopSelf();return START_NOT_STICKY;}
        try{
            MediaProjectionManager manager=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
            projection=manager.getMediaProjection(result,data);
            if(projection==null)throw new IllegalStateException("No MediaProjection");
            projection.registerCallback(new MediaProjection.Callback(){
                @Override public void onStop(){mainHandler.post(()->stopSelf());}
            },mainHandler);
            alive=true;
            if(displays!=null)displays.registerDisplayListener(this,mainHandler);
            if(gpuEnabled) gpu=new GpuRingScanner();
            configureCapture(true);
            installBubble();
            root.start();
            notifyStatus("Vision online • "+(mode==MODE_TRAIN?"TRAIN":"LIVE"));
        } catch(Throwable ex){
            android.util.Log.e("Pilot", "Start failed",ex);
            notifyStatus("Error: "+ex.getClass().getSimpleName());
            stopSelf();
        }
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent i){return null;}
    private void createNotificationChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel c=new NotificationChannel("pilot_channel","Sideswipe Pilot",NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Screen observation and external touch control");
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
    }
    private Notification notification(String status){
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent pending=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,"pilot_channel"):new Notification.Builder(this);
        return b.setContentTitle("Sideswipe Pilot").setContentText(status).setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pending).setOngoing(true).build();
    }
    private void notifyStatus(String txt){
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF,notification(txt));
    }
    private synchronized void configureCapture(boolean initial){
        if(!alive || projection==null)return;
        Display d=wm.getDefaultDisplay(); DisplayMetrics dm=new DisplayMetrics(); d.getRealMetrics(dm);
        int w=dm.widthPixels,h=dm.heightPixels;
        if(w<10 || h<10)return;
        float scale=640f/Math.max(w,h);
        captureW=Math.max(160,Math.round(w*scale));
        captureH=Math.max(160,Math.round(h*scale));
        final ImageReader old=reader;
        ImageReader next=ImageReader.newInstance(captureW,captureH,PixelFormat.RGBA_8888,3);
        next.setOnImageAvailableListener(r->{
            Image image=null;
            try {image=r.acquireLatestImage(); if(image!=null)processImage(image);}
            catch(Throwable t){android.util.Log.e("Pilot","Frame error",t);}
            finally {if(image!=null) image.close();}
        },visionHandler);
        reader=next;
        if(mirror==null){
            // MediaProjection.createVirtualDisplay may only be called once per permission token on Android 14+.
            mirror=projection.createVirtualDisplay("PilotDisplay",captureW,captureH,dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,next.getSurface(),null,visionHandler);
        }else{
            mirror.setSurface(next.getSurface());
            mirror.resize(captureW,captureH,dm.densityDpi);
        }
        if(old!=null){old.setOnImageAvailableListener(null,null);old.close();}
        root.updateDisplaySize(w,h);
        tracker.resetForSize(captureW,captureH);
        if(gpu!=null)gpu.reconfigure(captureW,captureH);
    }
    @Override public void onDisplayChanged(int displayId){
        if(displayId==Display.DEFAULT_DISPLAY) mainHandler.postDelayed(()->{
            if(!alive)return;
            DisplayMetrics dm=new DisplayMetrics();wm.getDefaultDisplay().getRealMetrics(dm);
            int w=Math.max(160,Math.round(dm.widthPixels*640f/Math.max(dm.widthPixels,dm.heightPixels)));
            int h=Math.max(160,Math.round(dm.heightPixels*640f/Math.max(dm.widthPixels,dm.heightPixels)));
            if(w!=captureW || h!=captureH)configureCapture(false);
        },400);
    }
    @Override public void onDisplayAdded(int id){}
    @Override public void onDisplayRemoved(int id){}
    private void processImage(Image image){
        if(!alive)return;
        long now=SystemClock.uptimeMillis();
        if(now-frameMs<scanInterval)return;
        long prev=frameMs;frameMs=now;
        if(prev>0){float instant=1000f/Math.max(1,now-prev); fps=fps==0?instant:fps*.86f+instant*.14f;}
        Image.Plane p=image.getPlanes()[0];
        ByteBuffer buf=p.getBuffer();
        VisionTracker.Observation obs=tracker.detect(buf,image.getWidth(),image.getHeight(),p.getRowStride(),p.getPixelStride(),now,
                gpuEnabled?gpu:null);
        latest=obs;frames++;
        if(mode!=MODE_PAUSE && obs.ballConfidence>.44 && obs.carConfidence>.35){
            PilotBrain.Command cmd=brain.decide(obs,mode==MODE_TRAIN,defendLeft,now);
            if(cmd!=null)root.offer(cmd);
        } else {root.offer(PilotBrain.Command.IDLE);}
        if(now-lastUi>275){lastUi=now;mainHandler.post(this::updateBubble);}
    }
    private int dp(float d){return (int)(d*getResources().getDisplayMetrics().density+.5f);}
    private void installBubble(){
        if(!Settings.canDrawOverlays(this))return;
        bubble=new TextView(this);bubble.setGravity(Gravity.CENTER);
        bubble.setTextColor(Color.WHITE);bubble.setTextSize(11);bubble.setTypeface(null,Typeface.BOLD);
        bubble.setPadding(dp(8),dp(4),dp(8),dp(4));
        GradientDrawable background=new GradientDrawable();background.setColor(0xDE10243B);background.setCornerRadius(dp(14));
        background.setStroke(dp(2),0xFF38DEC8);bubble.setBackground(background);
        int type=Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;
        bubbleParams=new WindowManager.LayoutParams(dp(110),dp(66),type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity=Gravity.TOP|Gravity.LEFT;bubbleParams.x=dp(8);bubbleParams.y=dp(75);
        bubble.setOnTouchListener(new View.OnTouchListener(){
            float sx,sy;int px,py;long press;boolean dragged;
            @Override public boolean onTouch(View v,android.view.MotionEvent event){
                switch(event.getActionMasked()){
                    case android.view.MotionEvent.ACTION_DOWN:
                        sx=event.getRawX();sy=event.getRawY();px=bubbleParams.x;py=bubbleParams.y;
                        press=SystemClock.uptimeMillis();dragged=false;return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        int dx=(int)(event.getRawX()-sx),dy=(int)(event.getRawY()-sy);
                        if(Math.abs(dx)+Math.abs(dy)>dp(12))dragged=true;
                        if(dragged){bubbleParams.x=px+dx;bubbleParams.y=py+dy;wm.updateViewLayout(bubble,bubbleParams);}return true;
                    case android.view.MotionEvent.ACTION_UP:
                        if(!dragged){
                            if(SystemClock.uptimeMillis()-press>650)calibrate(calibrationTarget==2?2:1);
                            else {mode=mode==MODE_PAUSE?MODE_TRAIN:mode==MODE_TRAIN?MODE_LIVE:MODE_PAUSE;
                                if(mode==MODE_PAUSE)root.offer(PilotBrain.Command.IDLE);updateBubble();}
                        }return true;
                }return true;
            }
        });
        wm.addView(bubble,bubbleParams);updateBubble();
    }
    private void updateBubble(){
        if(bubble==null)return;
        VisionTracker.Observation o=latest;
        String name=mode==MODE_PAUSE?"PAUSED":mode==MODE_TRAIN?"TRAIN":"LIVE";
        String line=o==null?"SEARCHING":"B "+(int)(o.ballConfidence*100)+"%  C "+(int)(o.carConfidence*100)+"%";
        String line2=o==null?"Hold to calibrate":"⛽~"+(int)(o.boost*100)+"% ↻"+(o.flipReady?"?Y":"?N")+" "+Math.round(fps)+"f";
        bubble.setText("PILOT • "+name+"\n"+line+"\n"+line2);
    }
    // Long-press the bubble. First tap sets car track target, second sets ball. No game process access.
    private void calibrate(int target){
        if(calibration!=null)return;
        calibrationTarget=target;
        TextView full=new TextView(this);full.setText("TAP YOUR "+(target==1?"CAR":"BALL")+" POSITION\n(overlay closes immediately)");
        full.setTextSize(20);full.setTextColor(Color.WHITE);full.setGravity(Gravity.CENTER);
        full.setBackgroundColor(0x66201524);calibration=full;
        int type=WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(-1,-1,type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        full.setOnTouchListener((v,event)->{
            if(event.getActionMasked()!=android.view.MotionEvent.ACTION_DOWN)return true;
            DisplayMetrics dm=new DisplayMetrics();wm.getDefaultDisplay().getRealMetrics(dm);
            float x=event.getRawX()/Math.max(1,dm.widthPixels), y=event.getRawY()/Math.max(1,dm.heightPixels);
            if(calibrationTarget==1)tracker.seedCar(x,y);else tracker.seedBall(x,y);
            removeCalibration();
            // One long-press calibrates car, subsequent long-press calibrates ball.
            calibrationTarget=calibrationTarget==1?2:1;
            return true;
        });
        wm.addView(full,p);
    }
    private void removeCalibration(){if(calibration!=null){try{wm.removeView(calibration);}catch(Exception ignored){}calibration=null;}}
    @Override public void onDestroy(){
        alive=false;
        mode=MODE_PAUSE;
        root.offer(PilotBrain.Command.IDLE);
        root.shutdown();
        brain.save(getSharedPreferences("training",MODE_PRIVATE));
        if(displays!=null)displays.unregisterDisplayListener(this);
        removeCalibration();
        if(bubble!=null){try{wm.removeView(bubble);}catch(Exception ignored){}bubble=null;}
        if(reader!=null){reader.close();reader=null;}
        if(mirror!=null){mirror.release();mirror=null;}
        if(projection!=null){projection.stop();projection=null;}
        if(gpu!=null){gpu.release();gpu=null;}
        if(visionThread!=null)visionThread.quitSafely();
        super.onDestroy();
    }
}
