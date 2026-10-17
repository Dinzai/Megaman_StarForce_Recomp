package io.dinzai.starforce;

import org.json.*;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class SceneModelTest {
    private JSONObject fixture(float[][] points)throws Exception {
        JSONObject mat=new JSONObject();
        mat.put("width",8).put("height",8).put("alpha",1).put("image","textures/661_429_0_0.png");
        for(String key:new String[]{"repeat_s","repeat_t","mirror_s","mirror_t"})mat.put(key,false);
        JSONArray vertices=new JSONArray();
        for(float[] p:points) {
            for(float value:p)vertices.put(value);
            for(int value:new int[]{0,0,1,1,1,1})vertices.put(value);
        }
        JSONObject draw=new JSONObject().put("material",0).put("vertices",vertices);
        return new JSONObject().put("name","fixture").put("mode","bind-pose")
            .put("materials",new JSONArray().put(mat)).put("draws",new JSONArray().put(draw));
    }
    private SceneModel read(JSONObject data)throws Exception {
        File file=File.createTempFile("model-test-",".json");
        try {Files.write(file.toPath(),data.toString().getBytes(StandardCharsets.UTF_8));return SceneModel.read(file);}
        finally {Files.deleteIfExists(file.toPath());}
    }
    @Test public void pointCollapsedEffectKeepsTextureReferences()throws Exception {
        SceneModel model=read(fixture(new float[180][3]));
        assertFalse(model.hasVisibleGeometry);
        assertEquals("textures/661_429_0_0.png",model.parts.get(0).image);
        assertEquals(180,model.parts.get(0).count);
        assertEquals(0f,model.parts.get(0).positions.get(0),0f);
    }
    @Test public void translatedPointAlsoDoesNotDivideByZero()throws Exception {
        assertFalse(read(fixture(new float[][]{{2,3,4},{2,3,4},{2,3,4}})).hasVisibleGeometry);
    }
    @Test public void flatTriangleStillNormalizes()throws Exception {
        SceneModel model=read(fixture(new float[][]{{0,0,0},{2,0,0},{0,2,0}}));
        assertTrue(model.hasVisibleGeometry);
        assertEquals(-1f,model.parts.get(0).positions.get(0),0f);
        assertEquals(1f,model.parts.get(0).positions.get(3),0f);
    }
    @Test public void malformedCollapsedModelStillRejected()throws Exception {
        JSONObject data=fixture(new float[3][3]);
        data.getJSONArray("materials").getJSONObject(0).put("image","../outside.png");
        assertThrows(IOException.class,()->read(data));
    }
    @Test public void emptyGeometryStillRejected()throws Exception {
        assertThrows(IOException.class,()->read(fixture(new float[0][3])));
    }
}
