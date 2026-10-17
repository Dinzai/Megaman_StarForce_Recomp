package io.dinzai.starforce;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String[] GAMES={"Leo","Pegasus","Dragon","Zerker","Ninja","Saurian","Black Ace","Red Joker"};
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private TextView status;
    private LinearLayout body;
    private int selected=0;
    private boolean busy=false;
    private VisualMod mod;
    private ModelView modelSurface;
    private void stopModel(){if(modelSurface!=null){modelSurface.onPause();modelSurface=null;}}
    @Override protected void onPause(){if(modelSurface!=null)modelSurface.onPause();super.onPause();}
    @Override protected void onResume(){super.onResume();if(modelSurface!=null)modelSurface.onResume();}
    private String message="Choose Leo to import your ROM. Other games are planned.";
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if(RomImporter.isPlayable(getFilesDir()))message="Leo is ready. Press Play.";
        else if(new File(getFilesDir(),"leo/import.json").isFile())message="This build plays Leo. Import the ROM again to upgrade your 0.3 import (saves are kept).";
        if(state!=null)selected=state.getInt("selected",0);
        try {
            File installed=new File(getFilesDir(),"visual-mod.json");
            try(InputStream in=installed.exists()?new FileInputStream(installed):getAssets().open("dramatic-voxel.json")) {
                mod=VisualMod.parse(readText(in));
            }
        } catch(Exception e) {message="Mod could not load: "+e.getMessage();}
        home();
    }
    @Override public void onSaveInstanceState(Bundle state){state.putInt("selected",selected);super.onSaveInstanceState(state);}
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private TextView text(String value,int size,int color){
        TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(8),0,dp(8));return t;
    }
    private void button(String label,boolean enabled,Runnable action){
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setEnabled(enabled);b.setOnClickListener(v->action.run());body.addView(b);
    }
    private void home(){
        stopModel();
        ScrollView scroll=new ScrollView(this);
        body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(20),dp(28),dp(20),dp(28));body.setBackgroundColor(Color.rgb(8,15,28));
        scroll.addView(body);setContentView(scroll);
        // Respect system bars with target SDK 35 edge-to-edge behavior.
        scroll.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        scroll.requestApplyInsets();
        body.addView(text("STAR FORCE",30,Color.rgb(77,220,255)));
        body.addView(text("Recomp • build "+BuildConfig.VERSION_NAME,16,Color.LTGRAY));
        body.addView(text("Game library",20,Color.WHITE));
        Spinner selector=new Spinner(this);
        selector.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,GAMES));selector.setSelection(selected);body.addView(selector);
        status=text(message,16,Color.LTGRAY);body.addView(status);
        LinearLayout controls=new LinearLayout(this);controls.setOrientation(LinearLayout.VERTICAL);body.addView(controls);
        selector.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(android.widget.AdapterView<?> p){}
            public void onItemSelected(android.widget.AdapterView<?> p,View v,int position,long id){
                selected=position;controls.removeAllViews();
                LinearLayout previous=body;body=controls;
                boolean imported=RomImporter.isPlayable(getFilesDir());
                if(position==0){
                    status.setText(busy?"Working…":message);
                    button("▶ Play Leo",!busy&&imported,()->startActivity(new Intent(MainActivity.this,GameActivity.class)));
                    button(imported?"Leo imported":"Import Leo ROM",!busy&&!imported,()->pick(1));
                    button("Install game mod (.zip)",!busy,()->pick(4));
                    button("Manage mods ("+new ModManager(getFilesDir()).installed().size()+")",!busy,MainActivity.this::manageMods);
                    button("Settings",!busy,MainActivity.this::settings);
                    button("Open voxel mod preview",!busy&&mod!=null,()->preview());
                    button("Install visual mod (.json)",!busy,()->pick(2));
                    button("Import Leo asset pack (.zip)",!busy,()->pick(3));
                    button("Browse ROM textures",!busy&&new File(getFilesDir(),"textures/manifest.json").isFile(),MainActivity.this::gallery);
                    button("Browse 3D models",!busy&&new File(getFilesDir(),"textures/manifest.json").isFile(),MainActivity.this::models);
                } else status.setText(GAMES[position]+" is planned. Only Leo is playable in this build.");
                body=previous;
            }
        });
        body.addView(text("Leo runs on an embedded DS core while its code is progressively recompiled. You must supply your own ROM; nothing from the game ships in this app.",14,Color.rgb(147,166,185)));
    }
    private void pick(int request){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");startActivityForResult(i,request);
    }
    @Override public void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK||data==null||data.getData()==null||busy)return;
        busy=true;home();
        worker.execute(()->{
            String resultMessage;
            VisualMod importedMod=null;
            try(InputStream input=getContentResolver().openInputStream(data.getData())){
                if(input==null)throw new IOException("Cannot open selected document");
                if(request==1)resultMessage=RomImporter.importLeo(input,getFilesDir());
                else if(request==3)resultMessage="Imported "+TexturePack.install(input,getFilesDir())+" assets. Open the texture or 3D model explorer.";
                else if(request==4){ModManager.Mod m=new ModManager(getFilesDir()).install(input);resultMessage="Installed mod "+m.name+" ("+m.summary()+"). It is enabled for the next boot.";}
                else {
                    String json=readText(input);importedMod=VisualMod.parse(json);
                    android.util.AtomicFile file=new android.util.AtomicFile(new File(getFilesDir(),"visual-mod.json"));
                    FileOutputStream out=null;
                    try{out=file.startWrite();out.write(json.getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}
                    catch(Exception e){file.failWrite(out);throw e;}
                    resultMessage="Installed "+importedMod.name+". Open the preview to inspect it.";
                }
            }catch(Exception e){importedMod=null;resultMessage="Import failed: "+e.getMessage();}
            String finalMessage=resultMessage;VisualMod finalMod=importedMod;
            runOnUiThread(()->{busy=false;message=finalMessage;if(finalMod!=null)mod=finalMod;if(!isDestroyed())home();});
        });
    }
    private static String readText(InputStream input)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
        while((n=input.read(buffer))!=-1){if(out.size()+n>16384)throw new IOException("Mod exceeds 16 KiB");out.write(buffer,0,n);}
        return out.toString(StandardCharsets.UTF_8.name());
    }
    private void gallery(){
        try {
            File root=new File(getFilesDir(),"textures");org.json.JSONArray entries=TexturePack.manifest(root);
            body.removeAllViews();body.addView(text("Leo texture explorer",24,Color.WHITE));
            body.addView(text("Actual ROM textures • not a reconstructed scene",14,Color.LTGRAY));
            Spinner choice=new Spinner(this);String[] labels=new String[entries.length()];
            for(int i=0;i<labels.length;i++){org.json.JSONObject item=entries.getJSONObject(i);labels[i]=item.getInt("file_id")+" / "+item.getInt("entry")+" — "+item.getString("name");}
            choice.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));body.addView(choice);
            LinearLayout display=new LinearLayout(this);display.setOrientation(LinearLayout.VERTICAL);body.addView(display);
            choice.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
                public void onNothingSelected(android.widget.AdapterView<?> p){}
                public void onItemSelected(android.widget.AdapterView<?> p,View v,int index,long id){
                    display.removeAllViews();
                    try {
                        org.json.JSONObject item=entries.getJSONObject(index);
                        android.graphics.Bitmap bitmap=android.graphics.BitmapFactory.decodeFile(new File(root,item.getString("path")).getPath());
                        if(bitmap==null)throw new IOException("Unable to decode texture");
                        ImageView image=new ImageView(MainActivity.this);image.setImageBitmap(bitmap);image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                        display.addView(image,new LinearLayout.LayoutParams(-1,dp(220)));
                        display.addView(text(bitmap.getWidth()+" × "+bitmap.getHeight()+" • "+item.getString("format")+" • palette: "+item.optString("palette_selection"),14,Color.LTGRAY));
                        if(mod!=null)display.addView(new VoxelView(MainActivity.this,mod,bitmap),new LinearLayout.LayoutParams(-1,dp(320)));
                    }catch(Exception e){display.addView(text(e.getMessage(),14,Color.LTGRAY));}
                }
            });
            button("Back to library",true,this::home);
        }catch(Exception e){message="Texture pack error: "+e.getMessage();home();}
    }
    private void models(){
        try {
            File root=new File(getFilesDir(),"textures");org.json.JSONArray entries=TexturePack.models(root);
            if(entries.length()==0)throw new IOException("Import a model asset pack first");
            stopModel();body.removeAllViews();body.addView(text("Leo 3D model explorer",24,Color.WHITE));
            body.addView(text("Bind pose • drag or D-pad to rotate • pinch to zoom. Animation and gameplay are not implemented.",14,Color.LTGRAY));
            Spinner choice=new Spinner(this);String[] labels=new String[entries.length()];
            for(int i=0;i<labels.length;i++){org.json.JSONObject item=entries.getJSONObject(i);labels[i]=item.getInt("file_id")+" / "+item.getInt("entry")+" — "+item.getString("name");}
            choice.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));body.addView(choice);
            LinearLayout display=new LinearLayout(this);display.setOrientation(LinearLayout.VERTICAL);body.addView(display);
            choice.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
                public void onNothingSelected(android.widget.AdapterView<?> p){}
                public void onItemSelected(android.widget.AdapterView<?> p,View v,int index,long id){
                    stopModel();display.removeAllViews();
                    try {
                        org.json.JSONObject item=entries.getJSONObject(index);
                        SceneModel scene=SceneModel.read(new File(root,item.getString("path")));
                        if(scene.hasVisibleGeometry) {
                            modelSurface=new ModelView(MainActivity.this,scene,root);
                            display.addView(modelSurface,new LinearLayout.LayoutParams(-1,dp(440)));
                        } else display.addView(text("This model has no visible geometry in its static pose. Select another model.",16,Color.LTGRAY));
                        display.addView(text(item.getInt("triangles")+" triangles • "+item.getJSONArray("unresolved_materials").length()+" unresolved materials",14,Color.LTGRAY));
                    }catch(Exception e){display.addView(text("Model error: "+e.getMessage(),14,Color.LTGRAY));}
                }
            });
            button("Back to library",true,this::home);
        }catch(Exception e){message="Model pack error: "+e.getMessage();home();}
    }
    private void manageMods(){
        ModManager mm=new ModManager(getFilesDir());
        body.removeAllViews();
        body.addView(text("Game mods",24,Color.WHITE));
        body.addView(text("Enabled mods apply on the next boot. Later mods win when two replace the same file.",14,Color.LTGRAY));
        java.util.List<ModManager.Mod> list=mm.installed();
        if(list.isEmpty())body.addView(text("No mods installed. Use “Install game mod (.zip)”. See docs/MODS.md for the format.",16,Color.LTGRAY));
        for(ModManager.Mod m:list){
            LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);row.setPadding(0,dp(8),0,dp(8));
            CheckBox box=new CheckBox(this);box.setText(m.name+(m.version.isEmpty()?"":" "+m.version));box.setTextColor(Color.WHITE);box.setChecked(mm.isEnabled(m.id));
            box.setOnCheckedChangeListener((b,on)->{try{mm.setEnabled(m.id,on);}catch(IOException e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_SHORT).show();}});
            row.addView(box);
            row.addView(text(m.summary()+(m.author.isEmpty()?"":" • by "+m.author)+(m.description.isEmpty()?"":"\n"+m.description),13,Color.LTGRAY));
            LinearLayout actions=new LinearLayout(this);
            for(String[] a:new String[][]{{"Up","-1"},{"Down","1"},{"Remove","0"}}){
                Button b=new Button(this);b.setText(a[0]);b.setAllCaps(false);
                b.setOnClickListener(v->{try{if(a[1].equals("0"))mm.remove(m.id);else mm.move(m.id,Integer.parseInt(a[1]));}catch(IOException e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_SHORT).show();}manageMods();});
                actions.addView(b);
            }
            row.addView(actions);body.addView(row);
        }
        button("Back to library",true,this::home);
    }
    private void settings(){
        android.content.SharedPreferences p=getSharedPreferences("settings",MODE_PRIVATE);
        body.removeAllViews();
        body.addView(text("Settings",24,Color.WHITE));
        String[][] toggles={
            {"jit","true","Fast CPU (JIT). Turn off if the game crashes on boot."},
            {"dual_screen","true","Use a second display for the touch screen (AYN Thor and other dual-screen devices)."},
            {"touch_controls","true","Show on-screen buttons."},
            {"autohide_controls","true","Hide on-screen buttons when a controller is used."},
            {"swap_face","false","Map face buttons by label instead of position."},
            {"integer_scale","false","Integer scaling (sharper, smaller)."},
            {"smooth","false","Smooth (bilinear) filtering."}};
        for(String[] t:toggles){
            CheckBox box=new CheckBox(this);box.setText(t[2]);box.setTextColor(Color.WHITE);
            box.setChecked(p.getBoolean(t[0],Boolean.parseBoolean(t[1])));
            box.setOnCheckedChangeListener((b,on)->p.edit().putBoolean(t[0],on).apply());
            body.addView(box);
        }
        button("Back to library",true,this::home);
    }
    private void preview(){
        body.removeAllViews();body.addView(text(mod.name,24,Color.WHITE));
        body.addView(text("Rendering prototype • not Leo gameplay",14,Color.LTGRAY));
        body.addView(new VoxelView(this,mod),new LinearLayout.LayoutParams(-1,dp(440)));
        button("Back to library",true,this::home);
    }
    @Override public void onBackPressed(){home();}
    @Override protected void onDestroy(){stopModel();worker.shutdown();super.onDestroy();}
}
