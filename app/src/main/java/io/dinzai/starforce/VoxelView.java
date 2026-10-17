package io.dinzai.starforce;

import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import android.view.View;

/** Original procedural sprite extruded into isometric blocks. No ROM artwork or game simulation. */
public final class VoxelView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final VisualMod mod;
    private float yaw=0.7f, lastX;
    // Original abstract energy-emblem test image; replace through a decoded asset adapter later.
    private static final String[] MASK={"....XX......","...XXX......","..XXXX......",".XXXX.......","XXXXXXX.....","...XXXX.....","...XXX......","..XXX.......","..XX........",".XX........."};
    public VoxelView(Context context,VisualMod mod) {
        super(context); this.mod=mod; setContentDescription("Voxel rendering preview. Drag horizontally to rotate.");
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); canvas.drawColor(Color.rgb(7,13,24));
        float scale=Math.min(getWidth()/25f,getHeight()/22f);
        canvas.save(); canvas.translate(getWidth()/2f,getHeight()*0.38f);
        if (mod.grid) {
            paint.setColor(Color.rgb(22,49,64)); paint.setStrokeWidth(1);
            for(int i=-10;i<=10;i++) {
                float[] a=project(i,0,-10,scale),b=project(i,0,10,scale);
                canvas.drawLine(a[0],a[1],b[0],b[1],paint);
                a=project(-10,0,i,scale); b=project(10,0,i,scale);
                canvas.drawLine(a[0],a[1],b[0],b[1],paint);
            }
        }
        // Back-to-front along the positive x/z camera direction; yaw is clamped to this quadrant.
        for(int row=0;row<MASK.length;row++) for(int x=0;x<MASK[row].length();x++) {
            if(MASK[row].charAt(x)=='X') block(canvas,x-6,1, row-5,mod.depth,scale);
        }
        canvas.restore();
        paint.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        paint.setTextSize(14*getResources().getDisplayMetrics().scaledDensity);
        paint.setColor(Color.rgb(174,197,211));
        canvas.drawText("Drag to rotate • procedural test artwork",16,getHeight()-24,paint);
    }
    private float[] project(float x,float y,float z,float s) {
        return new float[]{(float)(x*Math.cos(yaw)-z*Math.sin(yaw))*s,
                (float)(x*Math.sin(yaw)+z*Math.cos(yaw))*s*0.45f-y*s};
    }
    private void face(Canvas c,float[][] vertices,int color,float s) {
        Path path=new Path();
        for(int i=0;i<vertices.length;i++) {
            float[] p=project(vertices[i][0],vertices[i][1],vertices[i][2],s);
            if(i==0)path.moveTo(p[0],p[1]);else path.lineTo(p[0],p[1]);
        }
        path.close();paint.setColor(color);c.drawPath(path,paint);
    }
    private int shade(float factor) {
        return Color.rgb((int)(Color.red(mod.accent)*factor),(int)(Color.green(mod.accent)*factor),(int)(Color.blue(mod.accent)*factor));
    }
    private void block(Canvas c,float x,float y,float z,float h,float s) {
        float top=y+h;
        face(c,new float[][]{{x+1,y,z},{x+1,y,z+1},{x+1,top,z+1},{x+1,top,z}},shade(.5f),s);
        face(c,new float[][]{{x,y,z+1},{x+1,y,z+1},{x+1,top,z+1},{x,top,z+1}},shade(.72f),s);
        face(c,new float[][]{{x,top,z},{x+1,top,z},{x+1,top,z+1},{x,top,z+1}},mod.accent,s);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if(event.getAction()==MotionEvent.ACTION_DOWN){lastX=event.getX();return true;}
        if(event.getAction()==MotionEvent.ACTION_MOVE){yaw=Math.max(.15f,Math.min(1.4f,yaw+(event.getX()-lastX)*.005f));lastX=event.getX();invalidate();return true;}
        if(event.getAction()==MotionEvent.ACTION_UP){performClick();return true;}
        return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
}
