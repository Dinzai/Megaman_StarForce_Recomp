package io.dinzai.starforce;

import org.json.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Host geometry data. These are reconstructed bind-pose vertices, not DS instructions. */
public final class SceneModel {
    public static final class Part {
        public FloatBuffer positions,uv,colors;
        public int count; public String image;
        public boolean repeatS,repeatT,mirrorS,mirrorT;
    }
    public final ArrayList<Part> parts=new ArrayList<>();
    public String name;
    private static float number(JSONArray a,int i)throws Exception {
        double v=a.getDouble(i);if(!Double.isFinite(v)||Math.abs(v)>1000000)throw new IOException("Invalid model coordinate");return (float)v;
    }
    private static FloatBuffer buffer(float[] a){FloatBuffer b=ByteBuffer.allocateDirect(a.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();b.put(a);b.position(0);return b;}
    public static SceneModel read(File file)throws Exception {
        if(file.length()>8*1024*1024)throw new IOException("Model exceeds 8 MiB");
        byte[] bytes=new byte[(int)file.length()];try(DataInputStream in=new DataInputStream(new FileInputStream(file))){in.readFully(bytes);}
        JSONObject root=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
        if(!root.getString("mode").equals("bind-pose"))throw new IOException("Unsupported model mode");
        SceneModel result=new SceneModel();result.name=root.getString("name");
        JSONArray materials=root.getJSONArray("materials"),draws=root.getJSONArray("draws");
        if(draws.length()>512||materials.length()>256)throw new IOException("Too many model parts");
        float[] lo={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY};
        float[] hi={Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
        ArrayList<float[]> positions=new ArrayList<>();int total=0;
        for(int d=0;d<draws.length();d++) {
            JSONObject draw=draws.getJSONObject(d);JSONArray v=draw.getJSONArray("vertices");
            if(v.length()==0||v.length()%27!=0)throw new IOException("Invalid triangle list");
            total+=v.length()/9;if(total>60000)throw new IOException("Model exceeds 20,000 triangles");
            JSONObject mat=materials.getJSONObject(draw.getInt("material"));
            float width=mat.getInt("width"),height=mat.getInt("height");
            if(width<1||height<1||width>1024||height>1024)throw new IOException("Invalid material dimensions");
            Part part=new Part();part.count=v.length()/9;
            part.image=mat.isNull("image")?null:mat.getString("image");
            if(part.image!=null&&!part.image.matches("textures/[0-9]+(?:_[0-9]+){2,3}\\.png"))throw new IOException("Invalid material image");
            part.repeatS=mat.getBoolean("repeat_s");part.repeatT=mat.getBoolean("repeat_t");
            part.mirrorS=mat.getBoolean("mirror_s");part.mirrorT=mat.getBoolean("mirror_t");
            float alpha=(float)mat.getDouble("alpha");if(!Float.isFinite(alpha)||alpha<0||alpha>1)throw new IOException("Invalid material alpha");
            float[] xyz=new float[part.count*3],uv=new float[part.count*2],rgba=new float[part.count*4];
            for(int i=0;i<part.count;i++) {
                for(int j=0;j<3;j++){float value=number(v,i*9+j);xyz[i*3+j]=value;lo[j]=Math.min(lo[j],value);hi[j]=Math.max(hi[j],value);}
                uv[i*2]=number(v,i*9+3)/width;uv[i*2+1]=number(v,i*9+4)/height;
                for(int j=0;j<4;j++){float value=number(v,i*9+5+j);if(value<0||value>1)throw new IOException("Invalid vertex color");rgba[i*4+j]=value;}
                rgba[i*4+3]*=alpha;
            }
            positions.add(xyz);part.uv=buffer(uv);part.colors=buffer(rgba);result.parts.add(part);
        }
        if(total==0)throw new IOException("Model has no geometry");
        float extent=Math.max(hi[0]-lo[0],Math.max(hi[1]-lo[1],hi[2]-lo[2]));
        if(!Float.isFinite(extent)||extent<=0)throw new IOException("Degenerate model bounds");
        for(int i=0;i<result.parts.size();i++) {
            float[] xyz=positions.get(i);
            for(int v=0;v<xyz.length;v++)xyz[v]=(xyz[v]-(hi[v%3]+lo[v%3])*0.5f)*2f/extent;
            result.parts.get(i).positions=buffer(xyz);
        }
        return result;
    }
}
