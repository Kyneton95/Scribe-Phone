package au.local.barkpocket;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.net.URI;

public class MainActivity extends Activity{
    private SessionStore store;private android.content.SharedPreferences prefs;
    private EditText endpoint,token;private TextView status,transcript,folderLabel;private RadioGroup modes;
    private Button toggle,retry,save,discard,folderButton,apply;
    private final Handler handler=new Handler(Looper.getMainLooper());private boolean pendingStart;private String lastText="";
    private final Runnable refresh=new Runnable(){public void run(){render();handler.postDelayed(this,500);}};
    private int dp(int n){return(int)(getResources().getDisplayMetrics().density*n);}
    private TextView label(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setPadding(0,dp(8),0,dp(8));return t;}
    private Button button(LinearLayout l,String s,View.OnClickListener click){Button b=new Button(this);b.setText(s);b.setOnClickListener(click);l.addView(b,new LinearLayout.LayoutParams(-1,-2));return b;}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);store=SessionStore.get(this);prefs=getSharedPreferences("settings",MODE_PRIVATE);
        ScrollView scroll=new ScrollView(this);LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(20),dp(16),dp(20),dp(24));scroll.addView(body);setContentView(scroll);
        body.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets b=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(dp(20)+b.left,dp(16)+b.top,dp(20)+b.right,dp(24)+b.bottom);return insets;});
        body.addView(label("Bark Pocket",28));body.addView(label("Your voice → your server → plain text",15));body.addView(label("Choose before starting",16));
        modes=new RadioGroup(this);RadioButton a=new RadioButton(this);a.setId(R.id.mode_dictate);a.setText("Dictate · copy/share, no automatic file");modes.addView(a);
        RadioButton b=new RadioButton(this);b.setId(R.id.mode_document);b.setText("Dictate + document · save one .txt file");modes.addView(b);modes.check(R.id.mode_dictate);body.addView(modes);
        toggle=button(body,"Start listening",v->toggle());status=label("Ready",16);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);body.addView(status);
        transcript=label("Your transcript appears here after each phrase.",18);transcript.setTextIsSelectable(true);body.addView(transcript);
        button(body,"Copy transcript",v->{synchronized(store){((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Transcript",store.text));}toast("Copied");});
        button(body,"Share transcript",v->{String text;synchronized(store){text=store.text;}startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,text),"Share transcript"));});
        retry=button(body,"Retry pending audio / finish saving",v->{pendingStart=false;permissions();});
        save=button(body,"Save transcript as .txt",v->save());
        discard=button(body,"Discard / new session",v->new AlertDialog.Builder(this).setTitle("Discard private session?").setMessage("Deletes this session's private draft and pending audio. Already exported .txt files stay in your chosen folder.").setNegativeButton("Cancel",null).setPositiveButton("Discard",(d,w)->{try{store.discard();render();}catch(Exception e){toast(e.getMessage());}}).show());
        body.addView(label("Connection and local storage",22));body.addView(label("Full transcription endpoint",14));
        endpoint=new EditText(this);endpoint.setSingleLine(true);endpoint.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);endpoint.setText(prefs.getString("endpoint","http://192.168.20.14:24100/v1/audio/transcriptions"));body.addView(endpoint);
        body.addView(label("Optional API bearer token",14));token=new EditText(this);token.setSingleLine(true);token.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);token.setText(prefs.getString("token",""));body.addView(token);
        apply=button(body,"Save connection settings",v->{try{settings();toast("Settings saved");}catch(Exception e){toast(e.getMessage());}});
        folderButton=button(body,"Choose local Syncthing folder",v->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,22);});
        folderLabel=label("",12);body.addView(folderLabel);
        body.addView(label("Files contain only the transcript in UTF-8. No Markdown or AI formatting.\n\nTemporary audio and a private recovery draft remain until processed/discarded. No automatic cloud sync. HTTP is unencrypted: trusted LAN only; use HTTPS remotely.\n\nLong-press the app icon for mode shortcuts. The Quick Settings tile opens this screen. Start here, then stop from the recording notification.",14));selectShortcut(getIntent());
    }
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);selectShortcut(i);}
    private void selectShortcut(Intent i){String mode=i.getStringExtra("mode");if(mode!=null)modes.check(mode.equals("document")?R.id.mode_document:R.id.mode_dictate);}
    @Override protected void onResume(){super.onResume();handler.post(refresh);}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
    private void settings()throws Exception{
        String url=endpoint.getText().toString().trim();URI uri=new URI(url);
        if(uri.getHost()==null||!("http".equals(uri.getScheme())||"https".equals(uri.getScheme()))||uri.getUserInfo()!=null)throw new Exception("Enter a full HTTP/HTTPS URL without embedded credentials.");
        prefs.edit().putString("endpoint",url).putString("token",token.getText().toString().trim()).apply();
    }
    private void toggle(){synchronized(store){if(store.active){startService(new Intent(this,VoiceService.class).setAction(VoiceService.STOP));return;}}pendingStart=true;permissions();}
    private void permissions(){
        // Retrying pending uploads/saving never touches the microphone, so do not
        // block it behind a runtime permission the user may have revoked.
        if(!pendingStart){runService();return;}
        java.util.ArrayList<String> missing=new java.util.ArrayList<>();
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)missing.add(Manifest.permission.RECORD_AUDIO);
        if(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)missing.add(Manifest.permission.POST_NOTIFICATIONS);
        if(!missing.isEmpty()){requestPermissions(missing.toArray(new String[0]),23);return;}runService();
    }
    @Override public void onRequestPermissionsResult(int request,String[] p,int[] g){
        super.onRequestPermissionsResult(request,p,g);
        if(request!=23)return;
        if(!pendingStart||checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)runService();
        else toast("Microphone permission is required.");
    }
    private void runService(){
        try{settings();Intent i=new Intent(this,VoiceService.class);
            if(pendingStart){boolean doc=modes.getCheckedRadioButtonId()==R.id.mode_document;String folder=prefs.getString("folder","");
                synchronized(store){if(store.exists||store.busy)throw new Exception("Copy/save the previous session, then Discard / new session.");}
                if(doc&&folder.isEmpty())throw new Exception("Choose your local Syncthing folder first.");
                i.setAction(VoiceService.START).putExtra("document",doc).putExtra("endpoint",prefs.getString("endpoint","")).putExtra("folder",folder);
            }else{synchronized(store){if(!store.exists)throw new Exception("No session to retry.");store.endpoint=prefs.getString("endpoint","");store.folder=prefs.getString("folder","");store.persist();}i.setAction(VoiceService.RETRY);}
            startForegroundService(i);
        }catch(Exception e){toast(e.getMessage());}
    }
    private void save(){
        synchronized(store){if(store.active||store.busy||store.pending().length>0){toast("Stop and finish pending transcription before saving.");return;}}
        new Thread(()->{try{synchronized(store){store.folder=prefs.getString("folder","");store.saveText();}runOnUiThread(()->{toast("Saved plain text");render();});}catch(Exception e){runOnUiThread(()->toast(e.getMessage()));}},"bark-save").start();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=22||result!=RESULT_OK||data==null||data.getData()==null)return;
        try{
            Uri uri=data.getData();
            // The tree picker was opened asking for both; persist both so export keeps working.
            getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            prefs.edit().putString("folder",uri.toString()).apply();toast("Folder selected");
        }catch(Exception e){toast(e.getMessage()==null?"Folder permission could not be saved.":e.getMessage());}
    }
    private void render(){
        synchronized(store){boolean locked=store.active||store.busy;
            toggle.setText(store.active?"Stop listening":"Start listening");toggle.setEnabled(store.active||(!store.busy&&!store.exists));
            retry.setEnabled(store.exists&&!store.busy&&!store.active);discard.setEnabled(store.exists&&!locked);save.setEnabled(store.exists&&!locked&&store.pending().length==0&&!store.text.isBlank()&&store.saved.isEmpty());
            for(int n=0;n<modes.getChildCount();n++)modes.getChildAt(n).setEnabled(!locked&&!store.exists);
            endpoint.setEnabled(!locked);token.setEnabled(!locked);apply.setEnabled(!locked);folderButton.setEnabled(!locked);
            String s=store.status+"\nPending phrases: "+store.pending().length+(store.error.isEmpty()?"":"\n"+store.error);if(!s.contentEquals(status.getText()))status.setText(s);
            if(!store.text.equals(lastText)){lastText=store.text;transcript.setText(lastText.isBlank()?"Your transcript appears here after each phrase.":lastText);}
        }folderLabel.setText("Save folder: "+prefs.getString("folder","Not selected"));
    }
    private void toast(String text){Toast.makeText(this,text==null?"Unknown error":text,Toast.LENGTH_LONG).show();}
}
