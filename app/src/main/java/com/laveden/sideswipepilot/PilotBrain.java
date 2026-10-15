package com.laveden.sideswipepilot;

import android.content.SharedPreferences;
import android.util.Base64;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/** Small online tabular Q learner blended with a safety-gated intercept policy. */
public final class PilotBrain {
    public static final class Command {
        public final int steer; // -1: left, +1: right
        public final boolean jump,boost,flip;
        public static final Command IDLE=new Command(0,false,false,false);
        public Command(int steer,boolean jump,boolean boost,boolean flip){
            this.steer=steer;this.jump=jump;this.boost=boost;this.flip=flip;
        }
        @Override public boolean equals(Object o){
            if(!(o instanceof Command))return false;
            Command c=(Command)o;return steer==c.steer&&jump==c.jump&&boost==c.boost&&flip==c.flip;
        }
    }
    // Choose an action template; steering is recomputed each frame.
    private static final int ACTIONS=7,STATES=3*3*3*2*2;
    private final float[][] q=new float[STATES][ACTIONS];
    private final Random random=new Random();
    private long previousTime=0,lastDecision=0;
    private float previousDistance=0,previousBallVx=0;
    private int previousState=-1,previousAction=-1;
    private int trainingSteps=0;
    private static int quant(float f,float threshold){return f<-threshold?0:f>threshold?2:1;}
    private static int state(float dx,float dy,float d,float boost,boolean flip){
        int h=quant(dx,.09f),v=quant(dy,.10f),distance=d<.16f?0:d<.37f?1:2;
        int fuel=boost>.27f?1:0,canFlip=flip?1:0;
        return (((h*3+v)*3+distance)*2+fuel)*2+canFlip;
    }
    private float maxQ(int state){float b=q[state][0];for(int a=1;a<ACTIONS;a++)b=Math.max(b,q[state][a]);return b;}
    public Command decide(VisionTracker.Observation o,boolean training,boolean defendLeft,long now){
        if(now-lastDecision<95)return null;
        lastDecision=now;
        if(o.carX<0 || o.ballX<0 || o.ballConfidence<.44f || o.carConfidence<.35f)return Command.IDLE;
        float lead=.12f;
        float bx=Math.max(0,Math.min(1,o.ballX+o.ballVX*lead));
        float by=Math.max(0,Math.min(1,o.ballY+o.ballVY*.08f));
        float dx=bx-o.carX,dy=by-o.carY;
        float distance=(float)Math.hypot(dx,dy);
        int st=state(dx,dy,distance,o.boost,o.flipReady);
        if(training && previousState>=0 && previousAction>=0){
            float goalSide=defendLeft?1f:-1f;
            float reward=(previousDistance-distance)*2.1f;
            // Reward ball progress toward opponent goal, not just chasing.
            reward+=goalSide*(o.ballVX-previousBallVx)*.03f;
            if(distance<.10f)reward+=.04f;
            if(distance>.8f)reward-=.025f;
            q[previousState][previousAction]+=.14f*(reward+.94f*maxQ(st)-q[previousState][previousAction]);
            trainingSteps++;
        }
        previousState=st;previousDistance=distance;previousBallVx=o.ballVX;previousTime=now;
        int steer=dx>.042?1:dx<-.042?-1:0;
        boolean high=dy<-.058f;
        boolean near=Math.abs(dx)<.20f;
        boolean jump=high && near;
        boolean boost=(high || distance>.34f) && o.boost>.28f;
        boolean flip=o.flipReady && distance<.145f && near && (high||Math.abs(o.ballVX)>.10f);
        // Policy action scores keep intercept behavior primary; Q-values adjust it in training.
        float[] score=new float[ACTIONS];
        score[0]=-.08f; score[1]=-.01f;score[2]=-.01f;
        score[3]=jump?.37f:-.18f;
        score[4]=boost?.27f:-.25f;
        score[5]=(jump&&boost)?.43f:-.18f;
        score[6]=flip?.50f:-.40f;
        if(steer<0)score[1]+=.29f;
        if(steer>0)score[2]+=.29f;
        if(steer==0)score[0]+=.22f;
        if(training){for(int a=0;a<ACTIONS;a++)score[a]+=.4f*q[st][a];}
        int selected=0;float best=score[0];
        for(int a=1;a<ACTIONS;a++)if(score[a]>best){best=score[a];selected=a;}
        if(training && random.nextFloat()<.13f)selected=random.nextInt(ACTIONS);
        previousAction=selected;
        switch(selected){
            case 0:return Command.IDLE;
            case 1:return new Command(-1,false,false,false);
            case 2:return new Command(1,false,false,false);
            case 3:return new Command(steer,true,false,false);
            case 4:return new Command(steer,false,true,false);
            case 5:return new Command(steer,true,true,false);
            case 6:return new Command(steer,true,false,true);
            default:return Command.IDLE;
        }
    }
    public void load(SharedPreferences prefs){
        String stored=prefs.getString("weights",null);if(stored==null)return;
        try{
            byte[] bytes=Base64.decode(stored,Base64.NO_WRAP);
            if(bytes.length!=STATES*ACTIONS*4)return;
            ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            for(int i=0;i<STATES;i++)for(int j=0;j<ACTIONS;j++)q[i][j]=b.getFloat();
            trainingSteps=prefs.getInt("steps",0);
        }catch(Exception ignored){}
    }
    public void save(SharedPreferences prefs){
        ByteBuffer b=ByteBuffer.allocate(STATES*ACTIONS*4).order(ByteOrder.LITTLE_ENDIAN);
        for(float[] state:q)for(float v:state)b.putFloat(v);
        prefs.edit().putString("weights",Base64.encodeToString(b.array(),Base64.NO_WRAP)).putInt("steps",trainingSteps).apply();
    }
}
