package io.dinzai.starforce;

import android.content.Context;
import android.graphics.*;
import android.opengl.*;
import android.view.*;
import java.io.File;
import java.util.*;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** OpenGL bind-pose renderer with touch and D-pad inspection controls. */
public final class ModelView extends GLSurfaceView implements GLSurfaceView.Renderer {
    private final SceneModel model;private final File root;
    private final HashMap<String,Integer> textures=new HashMap<>();
    private volatile float yaw=25,pitch=-12,distance=3.8f;
    private float lastX,lastY;
    private final ScaleGestureDetector scale;
    public ModelView(Context context,SceneModel model,File root) {
        super(context);this.model=model;this.root=root;
        setEGLConfigChooser(8,8,8,8,16,0);setRenderer(this);setRenderMode(RENDERMODE_WHEN_DIRTY);
        setFocusableInTouchMode(true);setContentDescription("Leo 3D model. Drag or use D-pad to rotate. Pinch to zoom.");
        scale=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector d){distance=Math.max(2f,Math.min(9f,distance/d.getScaleFactor()));requestRender();return true;}
        });
    }
    @Override public void onSurfaceCreated(GL10 gl,EGLConfig config) {
        textures.clear();gl.glClearColor(.03f,.06f,.10f,1);gl.glEnable(GL10.GL_DEPTH_TEST);
        gl.glEnable(GL10.GL_BLEND);gl.glBlendFunc(GL10.GL_SRC_ALPHA,GL10.GL_ONE_MINUS_SRC_ALPHA);
        gl.glEnable(GL10.GL_ALPHA_TEST);gl.glAlphaFunc(GL10.GL_GREATER,.03f);
        gl.glEnableClientState(GL10.GL_VERTEX_ARRAY);gl.glEnableClientState(GL10.GL_COLOR_ARRAY);
        for(SceneModel.Part part:model.parts) {
            if(part.image==null||textures.containsKey(part.image))continue;
            Bitmap bitmap=BitmapFactory.decodeFile(new File(root,part.image).getPath());if(bitmap==null)continue;
            int[] id=new int[1];gl.glGenTextures(1,id,0);gl.glBindTexture(GL10.GL_TEXTURE_2D,id[0]);
            gl.glTexParameterf(GL10.GL_TEXTURE_2D,GL10.GL_TEXTURE_MIN_FILTER,GL10.GL_NEAREST);
            gl.glTexParameterf(GL10.GL_TEXTURE_2D,GL10.GL_TEXTURE_MAG_FILTER,GL10.GL_NEAREST);
            GLUtils.texImage2D(GL10.GL_TEXTURE_2D,0,bitmap,0);bitmap.recycle();textures.put(part.image,id[0]);
        }
    }
    @Override public void onSurfaceChanged(GL10 gl,int width,int height) {
        gl.glViewport(0,0,width,height);gl.glMatrixMode(GL10.GL_PROJECTION);gl.glLoadIdentity();
        GLU.gluPerspective(gl,45,(float)width/Math.max(1,height),.1f,30);gl.glMatrixMode(GL10.GL_MODELVIEW);
    }
    @Override public void onDrawFrame(GL10 gl) {
        gl.glClear(GL10.GL_COLOR_BUFFER_BIT|GL10.GL_DEPTH_BUFFER_BIT);gl.glLoadIdentity();
        gl.glTranslatef(0,0,-distance);gl.glRotatef(pitch,1,0,0);gl.glRotatef(yaw,0,1,0);
        for(SceneModel.Part part:model.parts) {
            Integer tex=part.image==null?null:textures.get(part.image);
            if(tex!=null) {
                gl.glEnable(GL10.GL_TEXTURE_2D);gl.glEnableClientState(GL10.GL_TEXTURE_COORD_ARRAY);gl.glBindTexture(GL10.GL_TEXTURE_2D,tex);
                gl.glTexParameterf(GL10.GL_TEXTURE_2D,GL10.GL_TEXTURE_WRAP_S,part.repeatS?(part.mirrorS?0x8370:GL10.GL_REPEAT):GL10.GL_CLAMP_TO_EDGE);
                gl.glTexParameterf(GL10.GL_TEXTURE_2D,GL10.GL_TEXTURE_WRAP_T,part.repeatT?(part.mirrorT?0x8370:GL10.GL_REPEAT):GL10.GL_CLAMP_TO_EDGE);
                gl.glTexCoordPointer(2,GL10.GL_FLOAT,0,part.uv);
            }else{gl.glDisable(GL10.GL_TEXTURE_2D);gl.glDisableClientState(GL10.GL_TEXTURE_COORD_ARRAY);}
            gl.glVertexPointer(3,GL10.GL_FLOAT,0,part.positions);gl.glColorPointer(4,GL10.GL_FLOAT,0,part.colors);
            gl.glDrawArrays(GL10.GL_TRIANGLES,0,part.count);
        }
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
        scale.onTouchEvent(e);
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN){getParent().requestDisallowInterceptTouchEvent(true);requestFocus();lastX=e.getX();lastY=e.getY();}
        if(e.getActionMasked()==MotionEvent.ACTION_MOVE){if(!scale.isInProgress()){yaw+=(e.getX()-lastX)*.5f;pitch=Math.max(-85,Math.min(85,pitch+(e.getY()-lastY)*.5f));requestRender();}lastX=e.getX();lastY=e.getY();}
        if(e.getActionMasked()==MotionEvent.ACTION_UP){getParent().requestDisallowInterceptTouchEvent(false);performClick();}
        return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
    @Override public boolean onKeyDown(int key,KeyEvent event) {
        if(key==KeyEvent.KEYCODE_DPAD_LEFT)yaw-=8;
        else if(key==KeyEvent.KEYCODE_DPAD_RIGHT)yaw+=8;
        else if(key==KeyEvent.KEYCODE_DPAD_UP)pitch=Math.max(-85,pitch-8);
        else if(key==KeyEvent.KEYCODE_DPAD_DOWN)pitch=Math.min(85,pitch+8);
        else return super.onKeyDown(key,event);
        requestRender();return true;
    }
}
