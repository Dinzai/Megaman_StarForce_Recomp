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
    private String message="Choose Leo to import your ROM. Other games are planned.";
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if(new File(getFilesDir(),"leo/import.json").isFile())message="Leo imported. Gameplay is not implemented yet.";
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
        ScrollView scroll=new ScrollView(this);
        body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(20),dp(28),dp(20),dp(28));body.setBackgroundColor(Color.rgb(8,15,28));
        scroll.addView(body);setContentView(scroll);
        // Respect system bars with target SDK 35 edge-to-edge behavior.
        scroll.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        scroll.requestApplyInsets();
        body.addView(text("STAR FORCE",30,Color.rgb(77,220,255)));
        body.addView(text("Recomp • research build 0.1",16,Color.LTGRAY));
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
                boolean imported=new File(getFilesDir(),"leo/import.json").isFile();
                if(position==0){
                    status.setText(busy?"Importing Leo…":message);
                    button(imported?"Leo imported":"Import Leo ROM",!busy&&!imported,()->pick(1));
                    button("Open voxel mod preview",!busy&&mod!=null,()->preview());
                    button("Install visual mod (.json)",!busy,()->pick(2));
                } else status.setText(GAMES[position]+" is planned. Only Leo import is implemented.");
                body=previous;
            }
        });
        body.addView(text("This build does not play Star Force. The preview uses original test artwork to develop the rendering mod API.",14,Color.rgb(147,166,185)));
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
    private void preview(){
        body.removeAllViews();body.addView(text(mod.name,24,Color.WHITE));
        body.addView(text("Rendering prototype • not Leo gameplay",14,Color.LTGRAY));
        body.addView(new VoxelView(this,mod),new LinearLayout.LayoutParams(-1,dp(440)));
        button("Back to library",true,this::home);
    }
    @Override public void onBackPressed(){home();}
    @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
}
