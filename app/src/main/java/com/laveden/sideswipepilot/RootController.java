package com.laveden.sideswipepilot;

import android.os.SystemClock;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Persistent KernelSU-compatible su shell with nonblocking, rate limited input commands.
 * This is an external touchscreen controller, never a game-memory injector.
 * Coordinates match the reference recording's default three-button HUD and are adjustable here.
 */
public final class RootController {
    private volatile PilotBrain.Command pending=PilotBrain.Command.IDLE;
    private volatile boolean active=false;
    private final AtomicBoolean connected=new AtomicBoolean(false);
    private Thread thread;
    private Process shell;
    private BufferedWriter writer;
    private int displayW=2340,displayH=1080;
    private volatile long lastCommand=0;
    private volatile int lastSteer=0;
    private int samples=0;
    public void start(){
        if(active)return;
        active=true;
        thread=new Thread(this::loop,"PilotRootInput");thread.setDaemon(true);thread.start();
    }
    public void offer(PilotBrain.Command cmd){if(cmd!=null)pending=cmd;}
    public boolean hasRoot(){return connected.get();}
    public void updateDisplaySize(int w,int h){if(w>0 && h>0){displayW=w;displayH=h;}}
    private void loop(){
        try{
            shell=new ProcessBuilder("su").redirectErrorStream(true).start();
            writer=new BufferedWriter(new OutputStreamWriter(shell.getOutputStream()));
            // Keep shell alive, so no new su session or permission prompt per touch.
            connected.set(true);
            Thread drain=new Thread(()->{
                try(BufferedReader r=new BufferedReader(new InputStreamReader(shell.getInputStream()))){
                    while(active && r.readLine()!=null){} 
                }catch(Exception ignored){}
            },"PilotSuOutput");drain.setDaemon(true);drain.start();
            while(active){
                PilotBrain.Command desired=pending;
                long now=SystemClock.uptimeMillis();
                if(desired==null || desired.equals(PilotBrain.Command.IDLE)){
                    lastSteer=0;SystemClock.sleep(80);continue;
                }
                if(now-lastCommand<105){SystemClock.sleep(35);continue;}
                lastCommand=now;
                int w=displayW,h=displayH;
                // When switching into landscape, DisplayMetrics follows the actual screen rotation.
                if(w<h){int z=w;w=h;h=z;}
                int joyX=(int)(w*.125f), joyY=(int)(h*.79f);
                // Swipe once per control tick: stock Android input has no stable simultaneous hold API.
                if(desired.steer!=0){
                    int tx=joyX+(int)(w*.080f*desired.steer);
                    line("input touchscreen swipe "+joyX+" "+joyY+" "+tx+" "+joyY+" 70");
                }
                if(desired.jump)line("input touchscreen tap "+(int)(w*.670f)+" "+(int)(h*.810f));
                if(desired.boost)line("input touchscreen tap "+(int)(w*.775f)+" "+(int)(h*.810f));
                if(desired.flip)line("input touchscreen tap "+(int)(w*.935f)+" "+(int)(h*.815f));
                writer.flush();samples++;
                SystemClock.sleep(25);
            }
        }catch(Throwable ex){connected.set(false);android.util.Log.w("PilotRoot","Root unavailable",ex);}
        finally{connected.set(false);if(shell!=null)try{shell.destroy();}catch(Exception ignored){}}
    }
    private void line(String command)throws IOException {writer.write(command);writer.newLine();}
    public void shutdown(){active=false;pending=PilotBrain.Command.IDLE;
        if(writer!=null)try{writer.write("exit\n");writer.flush();}catch(Exception ignored){}
        if(thread!=null)thread.interrupt();
        if(shell!=null)shell.destroy();
    }
}
