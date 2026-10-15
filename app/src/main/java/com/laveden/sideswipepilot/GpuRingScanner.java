package com.laveden.sideswipepilot;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES31;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Optional real GLES 3.1 compute-shader circle-edge scan, with CPU fallback. */
public final class GpuRingScanner {
    private EGLDisplay display=EGL14.EGL_NO_DISPLAY;
    private EGLContext context=EGL14.EGL_NO_CONTEXT;
    private EGLSurface surface=EGL14.EGL_NO_SURFACE;
    private int program,texture,ssbo,width,height,groupsX,groupsY;
    private ByteBuffer packed;
    private boolean initialized=false,available=false;
    private static final String GLSL="#version 310 es\n"
        +"precision highp float; precision highp int;\n"
        +"layout(local_size_x=8,local_size_y=8) in;\n"
        +"layout(binding=0,rgba8) uniform readonly highp image2D source;\n"
        +"layout(std430,binding=1) buffer Scores { vec4 entries[]; };\n"
        +"uniform ivec2 size;\n"
        +"float L(ivec2 p) { vec3 v=imageLoad(source,clamp(p,ivec2(0),size-ivec2(1))).rgb; return dot(v,vec3(.299,.587,.114)); }\n"
        +"void main() {\n"
        +"  ivec2 at=ivec2(gl_GlobalInvocationID.xy);\n"
        +"  ivec2 p=at*8+ivec2(4);\n"
        +"  ivec2 grid=(size+ivec2(7))/8;\n"
        +"  if(at.x>=grid.x || at.y>=grid.y)return;\n"
        +"  vec4 result=vec4(float(p.x),float(p.y),15.0,0.0);\n"
        +"  if(p.x>36 && p.x<size.x-36 && p.y>24 && p.y<size.y-25) {\n"
        +"   for(int radius=10;radius<=30;radius+=5){\n"
        +"    float n=0.0; float e=0.0;\n"
        +"    for(int k=0;k<12;k++){\n"
        +"     float angle=float(k)*.5235987756;\n"
        +"     vec2 dir=vec2(cos(angle),sin(angle));\n"
        +"     float dd=abs(L(p+ivec2(dir*float(radius-3)))-L(p+ivec2(dir*float(radius+4))));\n"
        +"     e+=min(dd,0.50); if(dd>.08)n+=1.0;\n"
        +"    }\n"
        +"    float score=.48*n/12.0+.52*min(e/12.0/.30,1.0);\n"
        +"    if(score>result.w)result=vec4(float(p.x),float(p.y),float(radius),score);\n"
        +"   }\n"
        +"  }\n"
        +"  entries[at.y*grid.x+at.x]=result;\n"
        +"}\n";
    public boolean isAvailable(){return !initialized||available;}
    private boolean init(){
        if(initialized)return available;
        initialized=true;
        try{
            display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if(display==EGL14.EGL_NO_DISPLAY)return false;
            if(!EGL14.eglInitialize(display,new int[2],0,new int[2],0))return false;
            int[] attr={EGL14.EGL_RENDERABLE_TYPE,0x0040,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,EGL14.EGL_NONE};
            EGLConfig[] config=new EGLConfig[1];int[] count=new int[1];
            if(!EGL14.eglChooseConfig(display,attr,0,config,0,1,count,0)||count[0]==0)return false;
            context=EGL14.eglCreateContext(display,config[0],EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE},0);
            surface=EGL14.eglCreatePbufferSurface(display,config[0],new int[]{EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE},0);
            if(context==EGL14.EGL_NO_CONTEXT || surface==EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display,surface,surface,context))return false;
            String version=GLES31.glGetString(GLES31.GL_VERSION);
            if(version==null||!version.contains("OpenGL ES 3.1")&&!version.contains("OpenGL ES 3.2"))return false;
            int shader=GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER);
            GLES31.glShaderSource(shader,GLSL);GLES31.glCompileShader(shader);
            int[] ok=new int[1];GLES31.glGetShaderiv(shader,GLES31.GL_COMPILE_STATUS,ok,0);
            if(ok[0]==0){android.util.Log.w("PilotGPU",GLES31.glGetShaderInfoLog(shader));return false;}
            program=GLES31.glCreateProgram();GLES31.glAttachShader(program,shader);GLES31.glLinkProgram(program);
            GLES31.glGetProgramiv(program,GLES31.GL_LINK_STATUS,ok,0);GLES31.glDeleteShader(shader);
            if(ok[0]==0){android.util.Log.w("PilotGPU",GLES31.glGetProgramInfoLog(program));return false;}
            int[] ids=new int[1];
            GLES31.glGenTextures(1,ids,0);texture=ids[0];
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D,texture);
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D,GLES31.GL_TEXTURE_MIN_FILTER,GLES31.GL_NEAREST);
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D,GLES31.GL_TEXTURE_MAG_FILTER,GLES31.GL_NEAREST);
            GLES31.glGenBuffers(1,ids,0);ssbo=ids[0];
            available=true;return true;
        }catch(Throwable e){android.util.Log.w("PilotGPU","GPU unavailable",e);return false;}
    }
    public void reconfigure(int w,int h){width=0;height=0;}
    public float[] scan(ByteBuffer rgba,int w,int h,int rowStride,int pixelStride,float predictedX,float predictedY){
        if(!init())return null;
        if(!EGL14.eglMakeCurrent(display,surface,surface,context))return null;
        try{
            if(w!=width||h!=height){
                width=w;height=h;groupsX=(w+7)/8;groupsY=(h+7)/8;
                packed=ByteBuffer.allocateDirect(w*h*4).order(ByteOrder.nativeOrder());
                GLES31.glBindTexture(GLES31.GL_TEXTURE_2D,texture);
                GLES31.glTexImage2D(GLES31.GL_TEXTURE_2D,0,GLES31.GL_RGBA8,w,h,0,GLES31.GL_RGBA,GLES31.GL_UNSIGNED_BYTE,null);
                GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER,ssbo);
                GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER,groupsX*groupsY*16,null,GLES31.GL_DYNAMIC_READ);
            }
            packed.clear();
            // glTexSubImage2D requires tightly packed, directly addressable RGBA rows.
            if(pixelStride==4){
                byte[] line=new byte[w*4];
                for(int y=0;y<h;y++){int base=y*rowStride;
                    for(int i=0;i<line.length;i++)line[i]=rgba.get(base+i);
                    packed.put(line);
                }
            }else{
                for(int y=0;y<h;y++)for(int x=0;x<w;x++){
                    int base=y*rowStride+x*pixelStride;
                    for(int c=0;c<4;c++)packed.put(c<pixelStride?rgba.get(base+c):(byte)255);
                }
            }
            packed.flip();
            GLES31.glActiveTexture(GLES31.GL_TEXTURE0);
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D,texture);
            GLES31.glTexSubImage2D(GLES31.GL_TEXTURE_2D,0,0,0,w,h,GLES31.GL_RGBA,GLES31.GL_UNSIGNED_BYTE,packed);
            GLES31.glUseProgram(program);
            GLES31.glUniform2i(GLES31.glGetUniformLocation(program,"size"),w,h);
            GLES31.glBindImageTexture(0,texture,0,false,0,GLES31.GL_READ_ONLY,GLES31.GL_RGBA8);
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER,1,ssbo);
            GLES31.glDispatchCompute((groupsX+7)/8,(groupsY+7)/8,1);
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT|GLES31.GL_BUFFER_UPDATE_BARRIER_BIT);
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER,ssbo);
            Buffer mapped=GLES31.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER,0,groupsX*groupsY*16,GLES31.GL_MAP_READ_BIT);
            if(!(mapped instanceof ByteBuffer))return null;
            ByteBuffer out=((ByteBuffer)mapped).order(ByteOrder.nativeOrder());
            float[] best=null;float score=0;
            for(int i=0;i<groupsX*groupsY;i++){
                float x=out.getFloat(),y=out.getFloat(),radius=out.getFloat(),s=out.getFloat();
                if(x>width*.87f && y>height*.66f)continue;
                float dist=(float)Math.hypot(x-predictedX,y-predictedY);
                float weighted=s+(dist<125?.15f*(1-dist/125f):0);
                if(weighted>score){score=weighted;best=new float[]{x,y,radius,s};}
            }
            GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER);
            return best;
        }catch(Throwable t){available=false;android.util.Log.w("PilotGPU","Compute fallback to CPU",t);return null;}
    }
    public void release(){
        try{
            if(display!=EGL14.EGL_NO_DISPLAY){
                EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
                if(surface!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(display,surface);
                if(context!=EGL14.EGL_NO_CONTEXT)EGL14.eglDestroyContext(display,context);
                EGL14.eglTerminate(display);
            }
        }catch(Throwable ignored){}
        available=false;
    }
}
