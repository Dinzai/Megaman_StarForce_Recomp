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
    private static final String[] GAMES={"Leo","Pegasus","Dragon","Zerker × Ninja","Ninja (Zerker × Ninja ROM)","Saurian","Black Ace","Red Joker"};
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private TextView status;
    private LinearLayout body;
    private int selected=0;
    private boolean busy=false;
    private String pendingGame="leo";
    private GameProfile game(){GameProfile g=GameProfile.forSelection(selected);return g==null?GameProfile.LEO:g;}
    private VisualMod mod;
    private ModelView modelSurface;
    private void stopModel(){if(modelSurface!=null){modelSurface.onPause();modelSurface=null;}}
    @Override protected void onPause(){if(modelSurface!=null)modelSurface.onPause();super.onPause();}
    @Override protected void onResume(){super.onResume();if(modelSurface!=null)modelSurface.onResume();}
    private String message="Select Leo, Zerker × Ninja, or Black Ace, then import your US ROM.";
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if(RomImporter.isPlayable(getFilesDir()))message="Leo is ready. Press Play.";
        else if(new File(getFilesDir(),"leo/import.json").isFile())message="This build plays Leo. Import the ROM again to upgrade your 0.3 import (saves are kept).";
        if(state!=null){selected=state.getInt("selected",0);pendingGame=state.getString("pendingGame","leo");}
        try {
            File installed=new File(getFilesDir(),"visual-mod.json");
            try(InputStream in=installed.exists()?new FileInputStream(installed):getAssets().open("dramatic-voxel.json")) {
                mod=VisualMod.parse(readText(in));
            }
        } catch(Exception e) {message="Mod could not load: "+e.getMessage();}
        home();
    }
    @Override public void onSaveInstanceState(Bundle state){state.putInt("selected",selected);state.putString("pendingGame",pendingGame);super.onSaveInstanceState(state);}
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
                GameProfile chosen=GameProfile.forSelection(position);
                boolean imported=chosen!=null&&RomImporter.isPlayable(getFilesDir(),chosen);
                if(chosen!=null){
                    status.setText(busy?"Working…":message);
                    button("▶ Play "+chosen.label,!busy&&imported,()->startActivity(new Intent(MainActivity.this,GameActivity.class).putExtra(GameProfile.EXTRA_GAME,chosen.id)));
                    button(imported?chosen.label+" imported":"Import "+chosen.label+" ROM",!busy&&!imported,()->pick(1));
                    if(!imported&&RomImporter.romFile(getFilesDir(),chosen).isFile())button("Verify existing "+chosen.label+" ROM",!busy,()->verifyExisting(chosen));
                    button("Install game mod (.zip)",!busy,()->pick(4));
                    button("Manage mods ("+new ModManager(getFilesDir(),chosen).installed().size()+")",!busy,MainActivity.this::manageMods);
                    button("Transformations",!busy&&imported,()->{if(chosen==GameProfile.LEO)transformations();else modelSwaps();});
                    button("Settings",!busy,MainActivity.this::settings);
                    button("Open voxel mod preview",!busy&&mod!=null,()->preview());
                    button("Install visual mod (.json)",!busy,()->pick(2));
                    if(chosen==GameProfile.LEO){
                    button("Import Leo asset pack (.zip)",!busy,()->pick(3));
                    button("Browse ROM textures",!busy&&new File(getFilesDir(),"textures/manifest.json").isFile(),MainActivity.this::gallery);
                    button("Browse 3D models",!busy&&new File(getFilesDir(),"textures/manifest.json").isFile(),MainActivity.this::models);
                    }
                } else status.setText(GAMES[position]+" is not profiled yet. Supported: Leo, Zerker × Ninja, Black Ace.");
                body=previous;
            }
        });
        body.addView(text("Supported games run on an embedded DS core while its code is progressively recompiled. You must supply your own ROM; nothing from the game ships in this app.",14,Color.rgb(147,166,185)));
    }
    private void verifyExisting(GameProfile target){
        busy=true;message="Verifying "+target.label+"…";home();
        worker.execute(()->{String m;
            try(InputStream in=new FileInputStream(RomImporter.romFile(getFilesDir(),target))){m=RomImporter.importGame(in,getFilesDir(),target);}
            catch(Exception e){m="Verification failed: "+e.getMessage();}
            String result=m;runOnUiThread(()->{busy=false;message=result;if(!isDestroyed())home();});
        });
    }
    private void pick(int request){
        pendingGame=game().id;
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");startActivityForResult(i,request);
    }
    @Override public void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK||data==null||data.getData()==null||busy)return;
        final GameProfile target=GameProfile.fromId(pendingGame);
        busy=true;home();
        if(request==5){
            worker.execute(()->{
                String m;
                try(OutputStream out=getContentResolver().openOutputStream(data.getData())){m=SaveTransfer.exportSave(out,getFilesDir(),target);}
                catch(Exception e){m="Export failed: "+e.getMessage();}
                String fm=m;runOnUiThread(()->{busy=false;message=fm;if(!isDestroyed())home();});
            });
            return;
        }
        worker.execute(()->{
            String resultMessage;
            VisualMod importedMod=null;
            try(InputStream input=getContentResolver().openInputStream(data.getData())){
                if(input==null)throw new IOException("Cannot open selected document");
                if(request==1)resultMessage=RomImporter.importGame(input,getFilesDir(),target);
                else if(request==3)resultMessage="Imported "+TexturePack.install(input,getFilesDir())+" assets. Open the texture or 3D model explorer.";
                else if(request==6)resultMessage=SaveTransfer.importSave(input,getFilesDir(),target);
                else if(request==7)resultMessage=RomImporter.importExtra(input,getFilesDir(),"sf2");
                else if(request==8)resultMessage=RomImporter.importExtra(input,getFilesDir(),"sf3");
                else if(request==4){ModManager.Mod m=new ModManager(getFilesDir(),target).install(input);resultMessage="Installed mod "+m.name+" ("+m.summary()+"). It is enabled for the next boot.";}
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
        ModManager mm=new ModManager(getFilesDir(),game());
        body.removeAllViews();
        body.addView(text(game().label+" mods",24,Color.WHITE));
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
    private void modelSwaps(){
        GameProfile target=game();body.removeAllViews();
        body.addView(text(target.label+" model swaps",24,Color.WHITE));
        body.addView(text("Replace base Mega Man's battle model using your imported ROMs. These swaps change appearance only; animations and moves remain the target game's. Leo's forced-transformation codes do not apply to this game.",14,Color.LTGRAY));
        for(FormBuilder.Form form:FormBuilder.FORMS){
            if(form.model==null)continue;
            File source=form.game.equals("sf1")?RomImporter.romFile(getFilesDir()):RomImporter.extraRom(getFilesDir(),form.game);
            button(form.label,!busy&&source.isFile(),()->applyCrossGameModel(target,source,form.model,form.label));
        }
        File sf3=RomImporter.extraRom(getFilesDir(),"sf3");
        if(sf3.isFile())for(String name:new String[]{"pl001","pl002","pl003","pl004","pl005","pl006","pl007","pl008","pl009","pl00a","pl00e","pl00f00","pl010"})
            button("SF3 model "+name,!busy,()->applyCrossGameModel(target,sf3,name,"SF3 "+name));
        button("Import Zerker × Ninja ROM",!busy,()->pick(7));
        button("Import Black Ace ROM",!busy,()->pick(8));
        button("Turn off model swaps",!busy,()->{try{new ModManager(getFilesDir(),target).disablePrefix("transform.base.",null);message="Model swaps off for "+target.label;}catch(Exception e){message=e.getMessage();}home();});
        button("Back to library",true,this::home);
    }
    private void applyCrossGameModel(GameProfile target,File source,String sourceModel,String label){
        busy=true;message="Building "+label+" for "+target.label+"…";home();
        worker.execute(()->{String result;
            try{
                File targetRom=RomImporter.romFile(getFilesDir(),target);
                int entry=FormBuilder.findModelEntry(targetRom,"pl000");
                byte[] targetData=FormBuilder.archiveEntries(FormBuilder.readRomFile(targetRom,"datbin/com/models.bin")).get(entry);
                int bones=FormBuilder.boneCount(FormBuilder.blocks(targetData).get("MDL0"));
                byte[] bmd=FormBuilder.build(source,sourceModel,"pl000",bones,0);
                String id="transform.base."+source.getParentFile().getName()+"."+sourceModel;
                java.util.Map<String,byte[]> assets=new java.util.LinkedHashMap<>();assets.put("datbin/com/models.bin@"+entry,bmd);
                ModManager mm=new ModManager(getFilesDir(),target);
                mm.installGenerated(id,label+" model", "Visual battle model swap for "+target.label+"; original moves retained.",assets);
                mm.disablePrefix("transform.base.",id);result=label+" model enabled for "+target.label+". Applies on the next boot.";
            }catch(Exception e){result="Model swap failed: "+e.getMessage();}
            String finalResult=result;runOnUiThread(()->{busy=false;message=finalResult;if(!isDestroyed())home();});
        });
    }
    private int transformSlot=FormBuilder.SLOT_STARFORCE;
    private void transformations(){
        File files=getFilesDir();
        body.removeAllViews();
        body.addView(text("Transformations",24,Color.WHITE));
        body.addView(text("Real transformations",18,Color.WHITE));
        body.addView(text("Mega Man starts every battle in a real Star Force form: the game's own transformation with that form's charged shot, element, weakness and effects. Hitting him with his weakness still knocks him out of it, as in the original.",14,Color.LTGRAY));
        String[][] real={{"1","Ice Pegasus"},{"2","Fire Leo"},{"3","Green Dragon"}};
        for(String[] r:real) button("Real "+r[1],!busy,()->applyRealForm(Integer.parseInt(r[0]),r[1]));
        button("Real transformation off",!busy,()->{try{new ModManager(files).disablePrefix("transform.real.",null);message="Real transformation off.";}catch(IOException e){message=e.getMessage();}home();});
        body.addView(text("Model swaps",18,Color.WHITE));
        body.addView(text("Change what a form looks like. Models come from your own imported ROMs. Choose \"When transforming\" plus a real transformation above to get, for example, Zerker's look with Fire Leo's moves. A swap alone is visual only.",14,Color.LTGRAY));
        RadioGroup slots=new RadioGroup(this);
        RadioButton sf=new RadioButton(this);sf.setText("When transforming (replaces Leo Kingdom)");sf.setTextColor(Color.WHITE);sf.setId(View.generateViewId());
        RadioButton base=new RadioButton(this);base.setText("Always in battle (replaces base Mega Man)");base.setTextColor(Color.WHITE);base.setId(View.generateViewId());
        slots.addView(sf);slots.addView(base);slots.check(transformSlot==FormBuilder.SLOT_BASE?base.getId():sf.getId());
        slots.setOnCheckedChangeListener((g,id)->transformSlot=id==base.getId()?FormBuilder.SLOT_BASE:FormBuilder.SLOT_STARFORCE);
        body.addView(slots);
        for(FormBuilder.Form f:FormBuilder.FORMS){
            if(f.model==null)continue;
            File src=f.game.equals("sf1")?RomImporter.romFile(files):RomImporter.extraRom(files,f.game);
            String need=f.game.equals("sf1")?"":(src.isFile()?"":" — import "+(f.game.equals("sf2")?"Star Force 2":"Star Force 3")+" first");
            button(f.label+need,!busy&&src.isFile(),()->applyForm(f.id,f.label,src,f.model));
        }
        File sf3=RomImporter.extraRom(files,"sf3");
        if(sf3.isFile()){
            body.addView(text("Star Force 3 models (Black Ace and Red Joker are among these; names not yet mapped):",14,Color.LTGRAY));
            for(String m:new String[]{"pl001","pl002","pl003","pl004","pl005","pl006"})
                button("SF3 "+m,!busy,()->applyForm("sf3-"+m,"Star Force 3 "+m,sf3,m));
        } else body.addView(text("Black Ace and Red Joker come from Star Force 3. Import that ROM to try them.",14,Color.LTGRAY));
        button("Import Star Force 2 ROM (Zerker, Ninja, Saurian)",!busy,()->pick(7));
        button("Import Star Force 3 ROM (Black Ace, Red Joker)",!busy,()->pick(8));
        button("Turn off transformations",!busy,()->{try{new ModManager(files).disablePrefix("transform.",null);message="Transformations off. Leo looks normal on the next boot.";}catch(IOException e){message=e.getMessage();}home();});
        button("Back to library",true,this::home);
    }
    private void applyRealForm(int form,String label){
        try{
            ModManager mm=new ModManager(getFilesDir());
            String id="transform.real."+form;
            java.util.List<LaunchConfig.Cheat> c=java.util.Collections.singletonList(new LaunchConfig.Cheat(label,FormBuilder.realFormCode(form)));
            mm.installGenerated(id,"Real "+label+" form","Every battle starts in the real "+label+" Star Force form.",new java.util.LinkedHashMap<>(),c);
            mm.disablePrefix("transform.real.",id);
            message="Real "+label+" enabled. Start a battle to see it.";
        }catch(Exception e){message="Could not enable "+label+": "+e.getMessage();}
        home();
    }
    private void applyForm(String formId,String label,File source,String model){
        int slot=transformSlot;busy=true;message="Building "+label+"…";home();
        worker.execute(()->{
            String m;
            try{
                byte[] bmd=FormBuilder.build(source,model,slot,0);
                String where=slot==FormBuilder.SLOT_BASE?"base":"starforce";
                String id="transform."+where+"."+formId.replace('_','-');
                java.util.Map<String,byte[]> assets=new java.util.LinkedHashMap<>();
                assets.put("datbin/com/models.bin@"+slot,bmd);
                ModManager mm=new ModManager(getFilesDir());
                mm.installGenerated(id,label+(slot==FormBuilder.SLOT_BASE?" (always)":" (Star Force)"),
                        "Mega Man appears as "+label+(slot==FormBuilder.SLOT_BASE?" in every battle.":" after Star Force Big Bang.")+" Visual only.",assets);
                mm.disablePrefix("transform."+where+".",id);
                m=label+" enabled. Start the game to see it"+(slot==FormBuilder.SLOT_BASE?" in your next battle.":" when you transform.");
            }catch(Exception e){m="Could not build "+label+": "+e.getMessage();}
            String fm=m;runOnUiThread(()->{busy=false;message=fm;if(!isDestroyed())home();});
        });
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
            {"smooth","false","Smooth (bilinear) filtering."},
            {"voxel_ingame","false","Voxel mod in game: draws the DS screens as 3D voxels using the installed visual mod. Drag the top screen to orbit. Also in the in-game menu."}};
        for(String[] t:toggles){
            CheckBox box=new CheckBox(this);box.setText(t[2]);box.setTextColor(Color.WHITE);
            box.setChecked(p.getBoolean(t[0],Boolean.parseBoolean(t[1])));
            box.setOnCheckedChangeListener((b,on)->p.edit().putBoolean(t[0],on).apply());
            body.addView(box);
        }
        String[] vs={"bottom","both","top"};String cur=p.getString("voxel_screens","bottom");
        Button vb=new Button(this);vb.setAllCaps(false);vb.setText("Voxel screens: "+cur);
        vb.setOnClickListener(v->{String c=p.getString("voxel_screens","bottom");String n=vs[(java.util.Arrays.asList(vs).indexOf(c)+1)%3];p.edit().putString("voxel_screens",n).apply();vb.setText("Voxel screens: "+n);});
        body.addView(vb);
        body.addView(text("Battery save",18,Color.WHITE));
        button("Export save (.sav)",RomImporter.saveFile(getFilesDir(),game()).isFile(),()->{
            Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/octet-stream");
            pendingGame=game().id;i.putExtra(Intent.EXTRA_TITLE,"MegaManStarForce-"+game().id+".sav");startActivityForResult(i,5);});
        button("Import save (.sav) — replaces the current save",!busy,()->pick(6));
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

