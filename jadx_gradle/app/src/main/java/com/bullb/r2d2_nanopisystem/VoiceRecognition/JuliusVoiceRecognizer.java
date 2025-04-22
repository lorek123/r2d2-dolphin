package com.bullb.r2d2_nanopisystem.VoiceRecognition;

import android.app.Activity;
import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;
import com.bullb.r2d2_nanopisystem.C0286R;
import com.bullb.r2d2_nanopisystem.EventHandler;
import com.bullb.r2d2_nanopisystem.VoiceRecognition.VoiceToEventHandler;
import com.bullb.r2d2_nanopisystem.utils.RobotPreference;
import edu.cmu.pocketsphinx.Assets;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Timer;
import java.util.TimerTask;

/* loaded from: classes.dex */
public class JuliusVoiceRecognizer {
    private static final String COMMAND_SEARCH = "command";
    private static final String GRAMMAR_JCONF = "/julius/command.jconf";
    private static final int MODE_DISABLE = -1;
    private static final int MODE_WAIT = 0;
    private static final int MODE_WAKE = 1;
    private static final String WAKE_GRAMMAR_JCONF = "/julius/wake_command.jconf";
    private static final int WAKE_TIME = 15000;
    public static boolean firstInit = false;
    private static JuliusVoiceRecognizer voiceRecognizer;
    private File assetDir;
    private String assetSubPath;
    private Context context;
    private boolean isSet;
    private Timer restartTimer;
    private TimerTask restartTimerTask;
    private TimerTask timerTask;
    private VoiceToEventHandler voiceToEventHandler;
    private String TAG = "Voice Recognizer";
    private boolean initialized = false;
    private boolean isWakeUpConfig = true;
    private String current_jconf = "init";
    private int currentMode = -1;
    private int nextMode = 0;
    private boolean isVoiceRecognitionMode = false;
    private Thread julius = null;
    private Timer timer = new Timer();

    private native boolean initJulius(String str);

    private native void pauseJulius();

    /* JADX INFO: Access modifiers changed from: private */
    public native void recognize();

    static {
        System.loadLibrary("portaudio");
        System.loadLibrary("julius_arm");
    }

    public static synchronized JuliusVoiceRecognizer getInstance(Context context) {
        JuliusVoiceRecognizer juliusVoiceRecognizer;
        synchronized (JuliusVoiceRecognizer.class) {
            if (voiceRecognizer == null) {
                voiceRecognizer = new JuliusVoiceRecognizer(context);
            }
            juliusVoiceRecognizer = voiceRecognizer;
        }
        return juliusVoiceRecognizer;
    }

    private JuliusVoiceRecognizer(Context context) {
        this.context = context;
        if (!this.isSet) {
            runRecognizerSetup();
        }
        this.voiceToEventHandler = new VoiceToEventHandler(context);
        this.assetSubPath = context.getResources().getString(C0286R.string.voice_path) + "/";
    }

    /* JADX WARN: Type inference failed for: r0v0, types: [com.bullb.r2d2_nanopisystem.VoiceRecognition.JuliusVoiceRecognizer$1] */
    private void runRecognizerSetup() {
        new AsyncTask<Void, Void, Exception>() { // from class: com.bullb.r2d2_nanopisystem.VoiceRecognition.JuliusVoiceRecognizer.1
            /* JADX INFO: Access modifiers changed from: protected */
            @Override // android.os.AsyncTask
            public Exception doInBackground(Void... params) {
                try {
                    Assets assets = new Assets(JuliusVoiceRecognizer.this.context);
                    JuliusVoiceRecognizer.this.assetDir = assets.syncAssets();
                    JuliusVoiceRecognizer.this.switchRecogniser(true);
                    JuliusVoiceRecognizer.firstInit = true;
                    return null;
                } catch (IOException e) {
                    return e;
                }
            }

            /* JADX INFO: Access modifiers changed from: protected */
            @Override // android.os.AsyncTask
            public void onPostExecute(Exception result) {
                if (result != null) {
                    Log.d(JuliusVoiceRecognizer.this.TAG, "Failed to init initialized");
                    return;
                }
                JuliusVoiceRecognizer.this.isSet = true;
                if (RobotPreference.isEnabledVoiceRecognition(JuliusVoiceRecognizer.this.context)) {
                    Log.d(JuliusVoiceRecognizer.this.TAG, "Enabled VOICE RECOGNITION");
                    JuliusVoiceRecognizer.this.start();
                }
            }
        }.execute(new Void[0]);
    }

    private boolean isWakeUpCommand(String keyword) {
        return this.voiceToEventHandler.getCommand(keyword) == VoiceToEventHandler.VoiceCommand.WAKE_UP;
    }

    private boolean isDanceCommand(String keyword) {
        return this.voiceToEventHandler.getCommand(keyword) == VoiceToEventHandler.VoiceCommand.DANCE;
    }

    private boolean isCirculeCommand(String keyword) {
        return this.voiceToEventHandler.getCommand(keyword) == VoiceToEventHandler.VoiceCommand.WALK_A_CIRCLE;
    }

    private boolean isPatrolCommand(String keyword) {
        return this.voiceToEventHandler.getCommand(keyword) == VoiceToEventHandler.VoiceCommand.PATROL;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onRecognizeKeyword(String keyword) {
        Log.d(this.TAG, "Current Mode: " + Integer.toString(this.currentMode));
        switch (this.currentMode) {
            case -1:
            default:
                return;
            case 0:
                if (!isWakeUpCommand(keyword)) {
                    return;
                }
                break;
            case 1:
                break;
        }
        stopSleepTimer();
        if (isDanceCommand(keyword)) {
            stopJulius(20000);
        } else if (isCirculeCommand(keyword)) {
            stopJulius(9000);
        } else if (isPatrolCommand(keyword)) {
            Log.d(this.TAG, "PAUSE JULIUS as patrol mode");
            pauseJulius();
            this.initialized = false;
            this.isVoiceRecognitionMode = false;
        } else {
            stopJulius(2000);
        }
        boolean needRestart = this.voiceToEventHandler.voiceToEvent(keyword);
        if (needRestart) {
            this.nextMode = 1;
            Log.d(this.TAG, "nextMode = MODE_WAKE");
        } else {
            this.nextMode = 0;
            Log.d(this.TAG, "nextMode = MODE_WAIT");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void switchMode(int mode) {
        Log.d(this.TAG, "Change Mode to: " + Integer.toString(mode));
        if (mode == -1) {
            Log.d(this.TAG, "Enter MODE_DISABLE");
            if (this.initialized) {
                Log.d(this.TAG, "PAUSE JULIUS as MODE_DISABLE");
                pauseJulius();
                this.initialized = false;
            }
            this.isVoiceRecognitionMode = false;
        } else if (mode == 0) {
            Log.d(this.TAG, "Enter MODE_WAIT");
            switchRecogniser(true);
            this.isVoiceRecognitionMode = false;
        } else if (mode == 1) {
            Log.d(this.TAG, "Enter MODE_WAKE");
            switchRecogniser(false);
            this.isVoiceRecognitionMode = true;
            EventHandler.getInstance(this.context).restoreLight();
            stopSleepTimer();
            startSleepTimer();
        }
        this.currentMode = mode;
    }

    public boolean isVoiceRecognitionMode() {
        return this.isVoiceRecognitionMode;
    }

    public void start() {
        if (firstInit) {
            switchMode(0);
        }
    }

    public void stop() {
        if (firstInit) {
            switchMode(-1);
        }
        EventHandler.getInstance(this.context).restoreLight();
    }

    public void callback(byte[] result) {
        Log.d(this.TAG, "Enter callbacked");
        StringBuilder bld = new StringBuilder();
        if (result.length < 3) {
            Log.d(this.TAG, "No date from callback");
            return;
        }
        for (byte b : result) {
            bld.append(String.format("%02x ", Byte.valueOf(b)));
        }
        String resultStr = "";
        try {
            String resultStr2 = new String(result, "UTF-8");
            resultStr = resultStr2;
        } catch (UnsupportedEncodingException e) {
            Log.e(this.TAG, e.toString());
        }
        final String resultStr3 = resultStr.substring(1).substring(0, r4.length() - 1);
        Log.d(this.TAG, "callbacked: " + resultStr3);
        ((Activity) this.context).runOnUiThread(new Runnable() { // from class: com.bullb.r2d2_nanopisystem.VoiceRecognition.JuliusVoiceRecognizer.2
            @Override // java.lang.Runnable
            public void run() {
                JuliusVoiceRecognizer.this.onRecognizeKeyword(resultStr3);
            }
        });
    }

    public void stopCallback() {
        Log.d(this.TAG, "CALLBACK FOR STOP JULIUS");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void switchRecogniser(boolean newIsWakeUpConfig) {
        if (newIsWakeUpConfig != this.isWakeUpConfig || !this.initialized) {
            if (this.initialized) {
                Log.d(this.TAG, "PAUSE JULIUS as Already Initialized");
                pauseJulius();
                this.initialized = false;
            }
            this.isWakeUpConfig = newIsWakeUpConfig;
            Log.d(this.TAG, "Try to Init Julius Recong Object");
            if (initJulius(newIsWakeUpConfig ? this.assetDir + WAKE_GRAMMAR_JCONF : this.assetDir + GRAMMAR_JCONF)) {
                Log.d(this.TAG, "JuliusInitializer:doInBackground:init julius success");
            } else {
                Log.e(this.TAG, "JuliusInitializer:doInBackground:init julius error");
            }
            Log.d(this.TAG, "Successfully init Julius");
            this.julius = new Thread(new Runnable() { // from class: com.bullb.r2d2_nanopisystem.VoiceRecognition.JuliusVoiceRecognizer.3
                @Override // java.lang.Runnable
                public void run() {
                    Log.d(JuliusVoiceRecognizer.this.TAG, "On Another Thread: Start Recognize process");
                    JuliusVoiceRecognizer.this.recognize();
                }
            });
            this.julius.start();
            this.initialized = true;
            Log.d(this.TAG, "initialized = TRUE");
            Log.d(this.TAG, "Finished isWakeUpConfig:" + this.isWakeUpConfig);
        }
    }

    private void stopJulius(int milliseconds) {
        if (this.initialized) {
            Log.d(this.TAG, "PAUSE JULIUS");
            pauseJulius();
            this.initialized = false;
            this.isVoiceRecognitionMode = false;
            Log.d(this.TAG, "initialized = FALSE");
            if (this.restartTimerTask != null) {
                this.restartTimerTask.cancel();
                this.restartTimerTask = null;
            }
            if (this.restartTimer != null) {
                this.restartTimer.cancel();
                this.restartTimer = null;
            }
            Log.d(this.TAG, "RESTART_TIMER: Stop timer");
            this.restartTimer = new Timer();
            this.restartTimerTask = new TimerTask() { // from class: com.bullb.r2d2_nanopisystem.VoiceRecognition.JuliusVoiceRecognizer.4
                @Override // java.util.TimerTask, java.lang.Runnable
                public void run() {
                    ((Activity) JuliusVoiceRecognizer.this.context).runOnUiThread(new Runnable() { // from class: com.bullb.r2d2_nanopisystem.VoiceRecognition.JuliusVoiceRecognizer.4.1
                        @Override // java.lang.Runnable
                        public void run() {
                            Log.d(JuliusVoiceRecognizer.this.TAG, "RESUME JULIUS");
                            System.gc();
                            JuliusVoiceRecognizer.this.switchMode(JuliusVoiceRecognizer.this.nextMode);
                        }
                    });
                }
            };
            Log.d(this.TAG, "RESTART_TIMER: RESTART AFTER n Second");
            this.restartTimer.schedule(this.restartTimerTask, milliseconds);
        }
    }

    private void startSleepTimer() {
        Log.d(this.TAG, "SLEEP_TIMER: Start sleep after 15s");
        this.timer = new Timer();
        this.timerTask = new TimerTask() { // from class: com.bullb.r2d2_nanopisystem.VoiceRecognition.JuliusVoiceRecognizer.5
            @Override // java.util.TimerTask, java.lang.Runnable
            public void run() {
                ((Activity) JuliusVoiceRecognizer.this.context).runOnUiThread(new Runnable() { // from class: com.bullb.r2d2_nanopisystem.VoiceRecognition.JuliusVoiceRecognizer.5.1
                    @Override // java.lang.Runnable
                    public void run() {
                        Log.d(JuliusVoiceRecognizer.this.TAG, "MODE_WAKE TIMEOUT, switch to MODE_WAIT");
                        if (JuliusVoiceRecognizer.this.currentMode != -1) {
                            JuliusVoiceRecognizer.this.switchMode(0);
                            JuliusVoiceRecognizer.this.voiceToEventHandler.endVoiceEvent();
                        }
                    }
                });
            }
        };
        this.timer.schedule(this.timerTask, 15000L);
    }

    private void stopSleepTimer() {
        Log.d(this.TAG, "SLEEP_TIMER: Stop timer");
        if (this.timerTask != null) {
            this.timerTask.cancel();
            this.timerTask = null;
        }
        if (this.timer != null) {
            this.timer.cancel();
            this.timer = null;
        }
    }
}
