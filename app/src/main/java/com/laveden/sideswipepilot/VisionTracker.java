package com.laveden.sideswipepilot;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Color-agnostic annular-edge / motion gated tracker, optimized for a 640px capture.
 * No game-package detection, game API, OCR, or process memory access is required.
 * Values are estimated visually, not authoritative game telemetry.
 */
public final class VisionTracker {
    public static final class Observation {
        public final float ballX,ballY,ballRadius,ballVX,ballVY,ballConfidence;
        public final float carX,carY,carVX,carVY,carConfidence,boost;
        public final boolean flipReady;
        public final int width,height;
        public final long timestamp;
        Observation(int w,int h,long t,float bx,float by,float br,float bvx,float bvy,float bc,
                    float cx,float cy,float cvx,float cvy,float cc,float boost,boolean flip){
            this.width=w;this.height=h;this.timestamp=t;
            ballX=bx;ballY=by;ballRadius=br;ballVX=bvx;ballVY=bvy;ballConfidence=bc;
            carX=cx;carY=cy;carVX=cvx;carVY=cvy;carConfidence=cc;this.boost=boost;flipReady=flip;
        }
    }
    private int width,height;
    private int[] rgb=new int[0], lum=new int[0];
    private float bx=-1,by=-1,bvx=0,bvy=0,bconf=0,br=17;
    private float cx=-1,cy=-1,cvx=0,cvy=0,cconf=0,boost=.7f;
    private boolean flipReady=true;
    private long prev=0,lastFlip=0;
    private int ticks=0;
    private float manualBallX=-1, manualBallY=-1, manualCarX=-1, manualCarY=-1;
    private int trackingColor=0;
    private final int[] carTemplate=new int[81];
    private boolean templateReady=false;
    private static final int[] RADII={9,12,15,18,22,27,32};
    private static final float[] COS=new float[12],SIN=new float[12];
    static {for(int i=0;i<12;i++){COS[i]=(float)Math.cos(i*Math.PI/6);SIN[i]=(float)Math.sin(i*Math.PI/6);}}
    public synchronized void resetForSize(int w,int h){
        if(w==width && h==height)return;
        width=w;height=h;rgb=new int[w*h];lum=new int[w*h];
        bx=by=cx=cy=-1;bconf=cconf=0;prev=0;templateReady=false;
    }
    public synchronized void seedBall(float nx,float ny){manualBallX=nx;manualBallY=ny;bx=nx*width;by=ny*height;bconf=1;}
    public synchronized void seedCar(float nx,float ny){manualCarX=nx;manualCarY=ny;cx=nx*width;cy=ny*height;cconf=1;trackingColor=0;templateReady=false;}
    private static float clamp(float v,float a,float b){return Math.max(a,Math.min(b,v));}
    private static boolean valid(float n){return n>=0;}
    private static int r(int c){return c&255;}
    private static int g(int c){return(c>>>8)&255;}
    private static int b(int c){return(c>>>16)&255;}
    private void unpack(ByteBuffer data,int w,int h,int rowStride,int pixelStride){
        // Absolute reads ignore ByteBuffer endianness and ImageReader row padding.
        int limit=data.limit();
        for(int y=0;y<h;y++){
            int i=y*w, row=y*rowStride;
            for(int x=0;x<w;x++){
                int off=row+x*pixelStride;
                if(off+2>=limit){rgb[i+x]=0;lum[i+x]=0;continue;}
                int red=data.get(off)&255,green=data.get(off+1)&255,blue=data.get(off+2)&255;
                rgb[i+x]=red|(green<<8)|(blue<<16);
                lum[i+x]=(red*77+green*150+blue*29)>>8;
            }
        }
    }
    private static class Candidate {
        float x,y,r,score;
        Candidate(float x,float y,float r,float score){this.x=x;this.y=y;this.r=r;this.score=score;}
    }
    private int l(int x,int y){if(x<0||y<0||x>=width||y>=height)return 0;return lum[y*width+x];}
    private int c(int x,int y){if(x<0||y<0||x>=width||y>=height)return 0;return rgb[y*width+x];}
    private float ring(float x,float y,int rad){
        if(x-rad-5<0||x+rad+5>=width||y-rad-5<0||y+rad+5>=height)return 0;
        int strong=0;float gradient=0, signed=0;
        for(int k=0;k<12;k++){
            int ix=Math.round(x+COS[k]*(rad-3)),iy=Math.round(y+SIN[k]*(rad-3));
            int ox=Math.round(x+COS[k]*(rad+4)),oy=Math.round(y+SIN[k]*(rad+4));
            int delta=l(ix,iy)-l(ox,oy);
            int strength=Math.abs(delta);
            gradient+=Math.min(120,strength);
            if(strength>20)strong++;
            signed+=delta;
        }
        float coherence=strong/12f;
        float grad=clamp(gradient/12f/77f,0,1);
        // Actual balls produce a mostly-closed edge (not just a straight sideline).
        float symmetry=1f-clamp(Math.abs(signed)/(gradient+1),0,1);
        int x0=(int)x,y0=(int)y;
        float detail=(Math.abs(l(x0-4,y0)-l(x0+4,y0))+Math.abs(l(x0,y0-4)-l(x0,y0+4)))/200f;
        float score=.42f*coherence+.38f*grad+.11f*clamp(detail,0,1)+.09f*symmetry;
        return score;
    }
    private Candidate searchBall(int x0,int y0,int x1,int y1,int step,float predictX,float predictY,boolean isLocal){
        Candidate best=new Candidate(-1,-1,0,0);
        x0=Math.max(36,x0);y0=Math.max(22,y0);
        x1=Math.min(width-36,x1);y1=Math.min(height-24,y1);
        for(int y=y0;y<y1;y+=step){
            for(int x=x0;x<x1;x+=step){
                // Avoid right-edge fixed controls and the lower HUD buttons.
                if(x>width*.87 && y>height*.66)continue;
                if(x<width*.17 && y>height*.71)continue;
                int px=c(x,y);
                int red=r(px),green=g(px),blue=b(px);
                // Bright green/flat grass tends to generate circular stadium line false positives.
                boolean grass=green>red*1.32f && green>blue*1.24f && green>95;
                for(int radius:RADII){
                    float s=ring(x,y,radius);
                    if(grass)s*=.84f;
                    if(isLocal){
                        float dist=(float)Math.hypot(x-predictX,y-predictY);
                        s+=.18f*(1-clamp(dist/130,0,1));
                    }
                    if(s>best.score){best.x=x;best.y=y;best.r=radius;best.score=s;}
                }
            }
        }
        return best;
    }
    private Candidate refine(Candidate co){
        if(co.x<0)return co;
        Candidate best=co;
        int bx=(int)co.x,by=(int)co.y;
        for(int y=by-4;y<=by+4;y+=2)for(int x=bx-4;x<=bx+4;x+=2){
            if(x<36||y<23||x>=width-36||y>=height-23)continue;
            for(int rad=Math.max(7,(int)co.r-3);rad<=(int)co.r+3;rad+=2){
                float s=ring(x,y,rad);
                if(s>best.score)best=new Candidate(x,y,rad,s);
            }
        }
        return best;
    }
    private static boolean neonGreen(int col){
        int r=r(col),g=g(col),b=b(col);
        return g>144 && g>r*1.55f && g>b*1.23f && b<185;
    }
    private float greenRing(float x,float y,float radius){
        int count=0;
        for(int i=0;i<24;i++){
            float ang=(float)(i*Math.PI/12.0);
            int xx=(int)(x+Math.cos(ang)*radius),yy=(int)(y+Math.sin(ang)*radius);
            if(neonGreen(c(xx,yy)))count++;
        }
        return count/24f;
    }
    private Candidate searchCar(int x0,int y0,int x1,int y1,int step,boolean local){
        Candidate best=new Candidate(-1,-1,0,0);
        x0=Math.max(22,x0);x1=Math.min(width-22,x1);
        y0=Math.max(27,y0);y1=Math.min(height-14,y1);
        for(int y=y0;y<y1;y+=step){for(int x=x0;x<x1;x+=step){
            if(x<width*.1f && y>height*.75f)continue;
            if(x>width*.87f && y>height*.70f)continue;
            float green=Math.max(greenRing(x,y,9),Math.max(greenRing(x,y,12),greenRing(x,y,15)));
            if(green<.16f)continue;
            // A boost ring surrounds a compact non-green sprite, not an entire green field.
            int sat=0, dark=0;
            for(int dy=-5;dy<=5;dy+=5)for(int dx=-5;dx<=5;dx+=5){
                int color=c(x+dx,y+dy),red=r(color),gr=g(color),bl=b(color);
                if(Math.max(red,Math.max(gr,bl))-Math.min(red,Math.min(gr,bl))>45)sat++;
                if(l(x+dx,y+dy)<115)dark++;
            }
            float score=green*.65f+dark/9f*.18f+sat/9f*.17f;
            if(local && valid(cx)){float dist=(float)Math.hypot(x-cx,y-cy);score+=.24f*(1-clamp(dist/95,0,1));}
            if(score>best.score)best=new Candidate(x,y,12,score);
        }}
        return best;
    }
    private Candidate colorTrackCar(float tx,float ty,float radius){
        if(trackingColor==0 || !valid(tx))return new Candidate(-1,-1,0,0);
        int targetR=r(trackingColor),targetG=g(trackingColor),targetB=b(trackingColor);
        float best=0;int bestX=-1,bestY=-1;
        int startX=Math.max(16,Math.round(tx-radius)),endX=Math.min(width-16,Math.round(tx+radius));
        int startY=Math.max(16,Math.round(ty-radius)),endY=Math.min(height-15,Math.round(ty+radius));
        for(int y=startY;y<endY;y+=3)for(int x=startX;x<endX;x+=3){
            int color=c(x,y),rr=r(color),gg=g(color),bb=b(color);
            float dist=(float)Math.sqrt((rr-targetR)*(rr-targetR)+(gg-targetG)*(gg-targetG)+(bb-targetB)*(bb-targetB));
            float proximity=1-clamp((float)Math.hypot(x-tx,y-ty)/radius,0,1);
            float score=(1-clamp(dist/210f,0,1))*.60f+proximity*.4f;
            if(score>best){best=score;bestX=x;bestY=y;}
        }
        return new Candidate(bestX,bestY,10,best*.75f);
    }
    private void captureCarTemplate() {
        if(!valid(cx)||cx<12||cy<12||cx>width-12||cy>height-12)return;
        int index=0;
        for(int yy=-12;yy<=12;yy+=3)for(int xx=-12;xx<=12;xx+=3){
            carTemplate[index++]=l((int)cx+xx,(int)cy+yy);
        }
        templateReady=true;
    }
    private Candidate templateTrack(float px,float py){
        if(!templateReady||!valid(px))return new Candidate(-1,-1,0,0);
        Candidate best=new Candidate(-1,-1,10,0);
        int startX=Math.max(17,(int)px-45),stopX=Math.min(width-17,(int)px+45);
        int startY=Math.max(17,(int)py-40),stopY=Math.min(height-17,(int)py+40);
        for(int yy=startY;yy<=stopY;yy+=3)for(int xx=startX;xx<=stopX;xx+=3){
            int n=0,delta=0;
            for(int ty=-12;ty<=12;ty+=3)for(int tx=-12;tx<=12;tx+=3){
                delta+=Math.abs(l(xx+tx,yy+ty)-carTemplate[n++]);
            }
            float match=1-clamp(delta/81f/85f,0,1);
            float dist=(float)Math.hypot(xx-px,yy-py);
            float score=match*.74f+(.26f*(1-clamp(dist/65f,0,1)));
            if(score>best.score)best=new Candidate(xx,yy,11,score);
        }
        return best;
    }
    private void trainColor(){
        if(!valid(cx))return;
        int cnt=0,red=0,green=0,blue=0;
        for(int y=(int)cy-5;y<=(int)cy+5;y+=2)for(int x=(int)cx-5;x<=(int)cx+5;x+=2){
            int col=c(x,y);
            int rr=r(col),gg=g(col),bb=b(col);
            if(Math.max(rr,Math.max(gg,bb))-Math.min(rr,Math.min(gg,bb))<30)continue;
            if(neonGreen(col))continue;
            red+=rr;green+=gg;blue+=bb;cnt++;
        }
        if(cnt>=4)trackingColor=(red/cnt)|((green/cnt)<<8)|((blue/cnt)<<16);
    }
    public synchronized Observation detect(ByteBuffer bytes,int w,int h,int rowStride,int pixelStride,long timestamp,GpuRingScanner gpu){
        if(w!=width||h!=height)resetForSize(w,h);
        unpack(bytes,w,h,rowStride,pixelStride);
        ticks++;
        float dt=prev>0?clamp((timestamp-prev)/1000f,.005f,.15f):.05f;
        prev=timestamp;
        float predictX=valid(bx)?bx+bvx*dt:width*.5f;
        float predictY=valid(by)?by+bvy*dt:height*.4f;
        Candidate ball=null;
        if(gpu!=null && gpu.isAvailable()) {
            try {
                float[] cand=gpu.scan(bytes,w,h,rowStride,pixelStride,predictX,predictY);
                if(cand!=null && cand[3]>.30f){ball=new Candidate(cand[0],cand[1],cand[2],cand[3]);}
            }catch(Exception ignored){}
        }
        if(ball==null && valid(bx) && bconf>.35f){
            int rad=(int)clamp(58+(1-bconf)*70,56,130);
            ball=searchBall((int)predictX-rad,(int)predictY-rad,(int)predictX+rad,(int)predictY+rad,4,predictX,predictY,true);
        }
        if(ball==null || ball.score<.49 || ticks%10==0){
            Candidate global=searchBall(20,18,width-20,height-15,7,predictX,predictY,false);
            if(ball==null || global.score>ball.score+.035f)ball=global;
        }
        ball=refine(ball);
        if(ball.score>.44f){
            if(valid(bx)){
                float dist=(float)Math.hypot(ball.x-predictX,ball.y-predictY);
                if(dist>width*.28f && bconf>.65f){ball.score*=.7f;}
            }
            if(ball.score>.43f){
                if(valid(bx)){
                    float newVX=clamp((ball.x-bx)/dt,-width*3f,width*3f);
                    float newVY=clamp((ball.y-by)/dt,-height*3f,height*3f);
                    bvx=bvx*.52f+newVX*.48f;bvy=bvy*.52f+newVY*.48f;
                    bx=bx*.20f+ball.x*.80f;by=by*.20f+ball.y*.80f;
                }else{bx=ball.x;by=ball.y;}
                br=ball.r;bconf=clamp((ball.score-.29f)*2.15f,0,1);
            }else bconf*=.88f;
        }else{bconf*=.91f;if(bconf<.16f){bx=by=-1;bvx=bvy=0;}}
        if(manualCarX>=0 && cx<0){cx=manualCarX*width;cy=manualCarY*height;cconf=.8f;}
        if(valid(cx)&&!templateReady)captureCarTemplate();
        float cpX=valid(cx)?cx+cvx*dt:width*.5f,cpY=valid(cy)?cy+cvy*dt:height*.57f;
        Candidate car=null;
        if(valid(cx) && cconf>.24){
            car=searchCar((int)cpX-65,(int)cpY-55,(int)cpX+65,(int)cpY+55,4,true);
            if(car.score<.39f){Candidate col=colorTrackCar(cpX,cpY,64);if(col.score>car.score)car=col;}
            Candidate templ=templateTrack(cpX,cpY);
            if(templ.score>car.score+.05f)car=templ;
        }
        if(car==null || car.score<.38f || ticks%18==0){
            Candidate all=searchCar(15,20,width-15,height-12,9,false);
            if(car==null||all.score>car.score+.06f)car=all;
        }
        if(car.score>.31){
            if(valid(cx)){
                float distance=(float)Math.hypot(car.x-cpX,car.y-cpY);
                if(distance>100 && cconf>.7f){car.score*=.5f;}
            }
            if(car.score>.30){
                if(valid(cx)){
                    cvx=cvx*.55f+clamp((car.x-cx)/dt,-width*2,width*2)*.45f;
                    cvy=cvy*.55f+clamp((car.y-cy)/dt,-height*2,height*2)*.45f;
                    cx=cx*.3f+car.x*.7f;cy=cy*.3f+car.y*.7f;
                }else{cx=car.x;cy=car.y;}
                cconf=clamp(car.score,0,1);
                if(ticks%16==0)trainColor();
                if(cconf>.54f && ticks%6==0)captureCarTemplate();
            }else cconf*=.88f;
        }else {cconf*=.90f;if(cconf<.12f){cx=cy=-1;cvx=cvy=0;}}
        if(valid(cx)){
            float ringBoost=Math.max(greenRing(cx,cy,9),Math.max(greenRing(cx,cy,12),greenRing(cx,cy,15)));
            // Ring indicates availability, not exact numeric boost reserve.
            boost=boost*.73f+ringBoost*.27f;
            boolean ground=cy>height*.76f && Math.abs(cvy)<height*.35f;
            float gap=valid(bx)?(float)Math.hypot(cx-bx,cy-by):999;
            boolean wheelTouch=gap>0 && gap<br+18 && Math.abs(cvy)>15;
            if(ground || wheelTouch){flipReady=true;lastFlip=timestamp;}
        }
        return new Observation(width,height,timestamp,
                valid(bx)?bx/width:-1,valid(by)?by/height:-1,br/width,bvx/width,bvy/height,bconf,
                valid(cx)?cx/width:-1,valid(cy)?cy/height:-1,cvx/width,cvy/height,cconf,clamp(boost,0,1),flipReady);
    }
    public synchronized void markFlipped(){flipReady=false;lastFlip=System.currentTimeMillis();}
}
