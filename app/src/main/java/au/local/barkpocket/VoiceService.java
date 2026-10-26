package au.local.barkpocket;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.*;
import android.os.*;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public class VoiceService extends Service {
    static final String START="start", STOP="stop", RETRY="retry";
    private SessionStore store;
    private volatile boolean listening;
    private final AtomicBoolean uploading=new AtomicBoolean(false);
    private volatile boolean failed;
    private PowerManager.WakeLock wake;
    @Override public void onCreate() {
        super.onCreate(); store=SessionStore.get(this);
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("recording","Recording and transcription",NotificationManager.IMPORTANCE_LOW));
    }
    private Notification notice() {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,VoiceService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=new Notification.Builder(this,"recording").setSmallIcon(R.drawable.ic_mic).setContentTitle(getString(R.string.app_name)).setContentText((listening?"Listening":"Finishing")+" · "+store.pending().length+" phrases pending").setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true);
        if(listening) b.addAction(new Notification.Action.Builder(Icon.createWithResource(this,android.R.drawable.ic_media_pause),"Stop",stop).build());
        return b.build();
    }
    private void notifyState() {getSystemService(NotificationManager.class).notify(11,notice());}
    @Override public int onStartCommand(Intent i,int flags,int startId) {
        if(i==null) {stopSelf(startId);return START_NOT_STICKY;}
        String action=i.getAction();
        if(STOP.equals(action)) {
            boolean idle;
            synchronized(store){listening=false;idle=!store.active&&!store.busy;store.status="Stopping microphone; finishing pending phrases";}
            if(idle) finish(); else notifyState();
            return START_NOT_STICKY;
        }
        int type=ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        try {
            if(START.equals(action)) {
                synchronized(store){if(store.exists||store.active||store.busy){stopSelf(startId);return START_NOT_STICKY;}store.begin(i.getBooleanExtra("document",false),i.getStringExtra("endpoint"),i.getStringExtra("folder"));}
                listening=true;failed=false;
            } else if(RETRY.equals(action)) {
                // Retrying only uploads/saves; it must not require (or claim) the microphone.
                type=ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
                synchronized(store){if(!store.exists||store.active||store.busy){stopSelf(startId);return START_NOT_STICKY;}store.error="";}
                failed=false;
            } else {stopSelf(startId);return START_NOT_STICKY;}
            startForeground(11,notice(),type);
            if(wake==null){wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"BarkPocket:session");wake.acquire(2*60*60*1000L);}
            if(START.equals(action))new Thread(this::capture,"bark-capture").start();else kick();
        } catch(Exception e){
            listening=false;
            synchronized(store){store.active=false;store.error=message(e);store.status="Could not start";}
            // Every startForegroundService() path must either enter the foreground or
            // stop itself promptly, otherwise Android kills the app for not doing so.
            try{stopForeground(STOP_FOREGROUND_REMOVE);}catch(Exception ignored){}
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }
    private void capture() {
        AudioRecord recorder=null;ByteArrayOutputStream phrase=new ByteArrayOutputStream();
        byte[] pre=new byte[6400];int preSize=0,silence=0,voiced=0;
        long began=SystemClock.elapsedRealtime();
        try {
            if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)throw new IOException("Microphone permission is not granted.");
            int min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(min<=0)throw new IOException("16 kHz microphone unavailable.");
            recorder=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min*2,6400));
            if(recorder.getState()!=AudioRecord.STATE_INITIALIZED)throw new IOException("Microphone could not initialise.");
            recorder.startRecording();short[] frame=new short[320];
            while(listening){
                int n=recorder.read(frame,0,frame.length,AudioRecord.READ_BLOCKING);
                if(n<=0)throw new IOException("Microphone read failed: "+n);
                byte[] bytes=new byte[n*2];double energy=0;
                for(int j=0;j<n;j++){int s=frame[j];energy+=(double)s*s;bytes[2*j]=(byte)s;bytes[2*j+1]=(byte)(s>>8);}
                boolean speech=Math.sqrt(energy/n)/32768.0>=0.012;
                if(speech&&phrase.size()==0&&preSize>0){phrase.write(pre,0,preSize);preSize=0;}
                if(speech||phrase.size()>0){
                    phrase.write(bytes);if(speech){voiced+=n;silence=0;}else silence+=n;
                    if(phrase.size()>=13*32000||silence>=11200){
                        if(voiced>=3200)emit(phrase.toByteArray());
                        phrase.reset();silence=0;voiced=0;preSize=0;
                    }
                }else{
                    int keep=Math.min(preSize,pre.length-bytes.length);
                    if(keep>0)System.arraycopy(pre,preSize-keep,pre,0,keep);
                    System.arraycopy(bytes,0,pre,keep,bytes.length);preSize=keep+bytes.length;
                }
                if(store.pending().length>=120){synchronized(store){store.error="Pending audio limit reached. Recording stopped; reconnect and retry.";}listening=false;}
                if(SystemClock.elapsedRealtime()-began>60*60*1000L){synchronized(store){store.error="One-hour recording limit reached.";}listening=false;}
            }
        }catch(Exception e){synchronized(store){store.error=message(e);}}
        finally{
            listening=false;
            if(recorder!=null){try{recorder.stop();}catch(Exception ignored){}recorder.release();}
            try{if(phrase.size()>0&&voiced>=3200)emit(phrase.toByteArray());}catch(Exception e){synchronized(store){store.error=message(e);}}
            synchronized(store){store.active=false;store.status="Microphone stopped; finishing transcription";}
            notifyState();kick();
        }
    }
    private void emit(byte[] pcm)throws Exception{File f=store.nextFile();Wav.write(f,pcm);kick();notifyState();}
    private void kick(){
        if(failed){if(!listening)finish();return;}
        if(!uploading.compareAndSet(false,true))return;
        synchronized(store){store.busy=true;}
        new Thread(()->{
            try{
                while(true){File[] a=store.pending();if(a.length==0)break;
                    synchronized(store){store.status=(listening?"Listening":"Finishing")+" · transcribing "+a[0].getName();}
                    String text=upload(a[0]);store.accept(a[0],text);notifyState();
                }
            }catch(Exception e){failed=true;synchronized(store){store.error=message(e);store.status="Pending work retained. Stop and tap Retry.";}}
            finally{
                synchronized(store){
                    if(!failed && !store.active && store.pending().length==0) {
                        try {
                            if(store.document && !store.text.isBlank()) store.saveText();
                            else store.status=store.text.isBlank()?"No speech transcribed":"Transcript ready. Copy, share or save.";
                        } catch(Exception e) { failed=true; store.error=message(e); store.status="Transcript retained. Choose a folder and retry saving."; }
                    }
                    store.busy=false;
                }
                uploading.set(false);
                if(!failed&&store.pending().length>0)kick();
                else if(!listening&&!store.active)finish();
            }
        },"bark-upload").start();
    }
    private String upload(File audio)throws Exception{
        String endpoint;synchronized(store){endpoint=store.endpoint;}
        URL url=new URL(endpoint);
        if(url.getHost().isEmpty()||!(url.getProtocol().equals("http")||url.getProtocol().equals("https")))throw new IOException("Use an HTTP/HTTPS endpoint.");
        HttpURLConnection c=(HttpURLConnection)url.openConnection();
        c.setConnectTimeout(15000);c.setReadTimeout(180000);c.setInstanceFollowRedirects(false);c.setRequestMethod("POST");c.setDoOutput(true);
        String boundary="BarkPocket"+System.nanoTime();c.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);
        String token=getSharedPreferences("settings",MODE_PRIVATE).getString("token","");if(!token.isBlank())c.setRequestProperty("Authorization","Bearer "+token);
        byte[] prefix=("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"phrase.wav\"\r\nContent-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix=("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(prefix.length+audio.length()+suffix.length);
        try{
            try(OutputStream out=c.getOutputStream();InputStream in=new FileInputStream(audio)){
                out.write(prefix);byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);out.write(suffix);
            }
            int code=c.getResponseCode();InputStream stream=code>=200&&code<300?c.getInputStream():c.getErrorStream();String body="";
            if(stream!=null)try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>1024*1024)throw new IOException("Server response too large.");out.write(b,0,n);}body=out.toString("UTF-8");
            }
            if(code<200||code>=300)throw new IOException("Server HTTP "+code+": "+body.substring(0,Math.min(300,body.length())));
            JSONObject j=new JSONObject(body);if(!j.has("text")||j.isNull("text"))throw new IOException("Server response has no text field.");return j.getString("text");
        }finally{c.disconnect();}
    }
    private static String message(Exception e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
    private synchronized void finish(){if(wake!=null&&wake.isHeld())wake.release();wake=null;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    @Override public void onDestroy(){listening=false;if(wake!=null&&wake.isHeld())wake.release();super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
