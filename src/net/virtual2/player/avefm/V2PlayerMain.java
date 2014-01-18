package net.virtual2.player.avefm;

import android.app.AlertDialog;
import android.os.Bundle;
import android.app.Activity;
import android.os.Handler;
import android.view.Menu;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import android.widget.ImageButton;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import com.un4seen.bass.BASS;



public class V2PlayerMain extends Activity {

    int req; // request number/counter
    int chan; // stream handle

    static final String radioName="AveFm";
    static final String url="http://stream.avefm.net:8000/avefm";
    static final String rss="http://avefm.net/feed";
    //static final String url="http://81.173.21.80/tmlmp3"; //tomorrowland radio

    Handler handler=new Handler();
    Runnable timer;
    Object lock = new Object();

    class RunnableParam implements Runnable {
        Object param;
        RunnableParam(Object p) { param=p; }
        public void run() {}
    }

    // display error messages
    void Error(String es) {
        // get error code in current thread for display in UI thread
        String s=String.format("%s\n(Erro: %d)", es, BASS.BASS_ErrorGetCode());
        runOnUiThread(new RunnableParam(s) {
            public void run() {
                new AlertDialog.Builder(V2PlayerMain.this)
                        .setMessage((String)param)
                        .setPositiveButton("OK", null)
                        .show();
            }
        });
    }

    // update stream title from metadata
    void DoMeta() {
        String meta=(String)BASS.BASS_ChannelGetTags(chan, BASS.BASS_TAG_META);
        if (meta!=null) { // got Shoutcast metadata
            int ti=meta.indexOf("StreamTitle='");
            if (ti>=0) {
                String title=meta.substring(ti+13, meta.indexOf("'", ti+13));
                ((TextView)findViewById(R.id.music_title)).setText(title);
            }
        } else {
            String[] ogg=(String[])BASS.BASS_ChannelGetTags(chan, BASS.BASS_TAG_OGG);
            if (ogg!=null) { // got Icecast/OGG tags
                String artist=null, title=null;
                for (String s: ogg) {
                    if (s.regionMatches(true, 0, "artist=", 0, 7))
                        artist=s.substring(7);
                    else if (s.regionMatches(true, 0, "title=", 0, 6))
                        title=s.substring(6);
                }
                if (title!=null) {
                    if (artist!=null)
                        ((TextView)findViewById(R.id.music_title)).setText(title+" - "+title);
                    else
                        ((TextView)findViewById(R.id.music_title)).setText(title);
                }
            }
        }
    }

    BASS.SYNCPROC MetaSync=new BASS.SYNCPROC() {
        public void SYNCPROC(int handle, int channel, int data, Object user) {
            runOnUiThread(new Runnable() {
                public void run() {
                    DoMeta();
                }
            });
        }
    };

    BASS.SYNCPROC EndSync=new BASS.SYNCPROC() {
        public void SYNCPROC(int handle, int channel, int data, Object user) {
            runOnUiThread(new Runnable() {
                public void run() {
                    ((TextView)findViewById(R.id.music_title)).setText("Reprodução interrompida.");

                }
            });
        }
    };

    BASS.DOWNLOADPROC StatusProc=new BASS.DOWNLOADPROC() {
        public void DOWNLOADPROC(ByteBuffer buffer, int length, Object user) {
            if (buffer!=null && length==0 && (Integer)user==req) { // got HTTP/ICY tags, and this is still the current request
                String[] s;
                try {
                    CharsetDecoder dec=Charset.forName("ISO-8859-1").newDecoder();
                    ByteBuffer temp=ByteBuffer.allocate(buffer.limit()); // CharsetDecoder doesn't like a direct buffer?
                    temp.put(buffer);
                    temp.position(0);
                    s=dec.decode(temp).toString().split("\0"); // convert buffer to string array
                } catch (Exception e) {
                    return;
                }
                runOnUiThread(new RunnableParam(s[0]) { // 1st string = status
                    public void run() {
                        //((TextView)findViewById(R.id.music_title)).setText((String)param);
                        ((TextView)findViewById(R.id.music_title)).setText(radioName);
                    }
                });
            }
        }
    };

    public class OpenURL implements Runnable {
        String url;
        public OpenURL(String p) { url=p; }
        public void run() {
            int r;
            synchronized(lock) { // make sure only 1 thread at a time can do the following
                r=++req; // increment the request counter for this request
            }
            BASS.BASS_StreamFree(chan); // close old stream
            runOnUiThread(new Runnable() {
                public void run() {
                    ((TextView)findViewById(R.id.music_title)).setText("A ligar...");
                }
            });
            int c=BASS.BASS_StreamCreateURL(url, 0, BASS.BASS_STREAM_BLOCK|BASS.BASS_STREAM_STATUS|BASS.BASS_STREAM_AUTOFREE, StatusProc, r); // open URL
            synchronized(lock) {
                if (r!=req) { // there is a newer request, discard this stream
                    if (c!=0) BASS.BASS_StreamFree(c);
                    return;
                }
                chan=c; // this is now the current stream
            }
            if (chan==0) { // failed to open
                runOnUiThread(new Runnable() {
                    public void run() {
                        ((TextView)findViewById(R.id.music_title)).setText("Ave fm a sua rádio!");
                    }
                });
                Error("Ocurreu um erro ao reproduzir.");
            } else
                handler.postDelayed(timer, 50); // start prebuffer monitoring
        }
    }

    public void Play(View v) {
        String proxy=null;
        /*
        if (!((CheckBox)findViewById(R.id.proxydirect)).isChecked())
        {
            proxy=((EditText)findViewById(R.id.proxy)).getText().toString();
        }
        */
        BASS.BASS_SetConfigPtr(BASS.BASS_CONFIG_NET_PROXY, proxy); // set proxy server
        new Thread(new OpenURL(url)).start();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.v2playermain_layout);

        // initialize output device
        if (!BASS.BASS_Init(-1, 44100, 0)) {
            Error("Erro ao inicializar a aplicação.");
            return;
        }
        BASS.BASS_SetConfig(BASS.BASS_CONFIG_NET_PLAYLIST, 1); // enable playlist processing
        BASS.BASS_SetConfig(BASS.BASS_CONFIG_NET_PREBUF, 1); // minimize automatic pre-buffering, so we can do it (and display it) instead

        timer=new Runnable() {
            public void run() {
                // monitor prebuffering progress
                long progress=BASS.BASS_StreamGetFilePosition(chan, BASS.BASS_FILEPOS_BUFFER)*100/BASS.BASS_StreamGetFilePosition(chan, BASS.BASS_FILEPOS_END); // percentage of buffer filled
                if (progress>75 || BASS.BASS_StreamGetFilePosition(chan, BASS.BASS_FILEPOS_CONNECTED)==0) { // over 75% full (or end of download)
                    // get the broadcast name and URL
                    String[] icy=(String[])BASS.BASS_ChannelGetTags(chan, BASS.BASS_TAG_ICY);
                    if (icy==null) icy=(String[])BASS.BASS_ChannelGetTags(chan, BASS.BASS_TAG_HTTP); // no ICY tags, try HTTP
                    if (icy!=null) {
                        for (String s: icy) {
                            // if (s.regionMatches(true, 0, "icy-name:", 0, 9))
                            //    ((TextView)findViewById(R.id.status2)).setText(s.substring(9));
                            // else if (s.regionMatches(true, 0, "icy-url:", 0, 8))
                            //    ((TextView)findViewById(R.id.status3)).setText(s.substring(8));
                        }
                    } else
                       // ((TextView)findViewById(R.id.status2)).setText("");
                    // get the stream title and set sync for subsequent titles
                    DoMeta();
                    BASS.BASS_ChannelSetSync(chan, BASS.BASS_SYNC_META, 0, MetaSync, 0); // Shoutcast
                    BASS.BASS_ChannelSetSync(chan, BASS.BASS_SYNC_OGG_CHANGE, 0, MetaSync, 0); // Icecast/OGG
                    // set sync for end of stream
                    BASS.BASS_ChannelSetSync(chan, BASS.BASS_SYNC_END, 0, EndSync, 0);
                    // play it!
                    BASS.BASS_ChannelPlay(chan, false);
                } else {
                   // ((TextView)findViewById(R.id.status2)).setText(String.format("buffering... %d%%", progress));
                    handler.postDelayed(this, 50);
                }
            }
        };


        final ImageButton button = (ImageButton) findViewById(R.id.imageButton1);
        button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // Perform action on click
               Play(v);
            }
        });

    }
    @Override
    public void onDestroy() {
        BASS.BASS_Free();

        super.onDestroy();
    }
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.v2_player_main, menu);
        return true;
    }
    
}
