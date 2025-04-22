package com.bullb.r2d2_nanopisystem;

import android.content.Context;
import android.util.Log;
import com.bullb.r2d2_nanopisystem.Bluetooth.BluetoothService;
import com.bullb.r2d2_nanopisystem.ModeControl.ModeController;
import com.bullb.r2d2_nanopisystem.Model.Command;
import com.bullb.r2d2_nanopisystem.RobotApi.RobotApiHandler;
import com.bullb.r2d2_nanopisystem.SelfUpdate.AppUpdater;
import com.bullb.r2d2_nanopisystem.WebSocket.SocketConnection;
import com.bullb.r2d2_nanopisystem.utils.RobotPreference;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Timer;
import java.util.TimerTask;

/* loaded from: classes.dex */
public class CommandReceiver {
    private final int SOURCE_BLUETOOTH;
    private final int SOURCE_WEBSOCKET;
    private final String TAG;
    private BluetoothService.ConnectedThread connectedThread;
    private Context context;
    private String data;
    private EventHandler eventHandler;
    private Gson gson;
    private ArrayList<String> lines;
    private RobotApiHandler robotApiHandler;
    private SocketConnection socketConnection;
    private int source;

    public CommandReceiver(Context context, SocketConnection socketConnection) {
        this(context);
        this.socketConnection = socketConnection;
        this.source = 2;
        this.robotApiHandler = new RobotApiHandler(context, socketConnection);
    }

    public CommandReceiver(Context context, BluetoothService.ConnectedThread connectedThread) {
        this(context);
        this.connectedThread = connectedThread;
        this.source = 1;
        this.robotApiHandler = new RobotApiHandler(context, connectedThread);
    }

    public CommandReceiver(Context context) {
        this.SOURCE_BLUETOOTH = 1;
        this.SOURCE_WEBSOCKET = 2;
        this.TAG = "CommandReceiver";
        this.data = "";
        this.lines = new ArrayList<>();
        this.source = -1;
        this.gson = new Gson();
        this.eventHandler = EventHandler.getInstance(context);
        this.context = context;
    }

    private boolean isValidConnection() {
        if (this.source == 1) {
            return this.connectedThread.isValidConnection();
        }
        if (this.source == 2) {
            return this.socketConnection.isValidConnection();
        }
        return false;
    }

    public void interpretCommand(String incomeData) {
        String incomeData2;
        if (incomeData.isEmpty()) {
            Iterator<String> it = this.lines.iterator();
            while (it.hasNext()) {
                String line = it.next();
                Log.i("CommandReceiver", "Interpret Command: " + line);
                try {
                    Log.d("input json", line);
                    Command command = (Command) this.gson.fromJson(line, Command.class);
                    if (command != null) {
                        String cmd = command.cmd;
                        if (RobotApiHandler.ROBOT_AUTH_COMMAND_LIST.contains(command.cmd)) {
                            this.robotApiHandler.handleAuthCommand(cmd, line);
                        } else if (isValidConnection() && RobotApiHandler.ROBOT_NORMAL_COMMAND_LIST.contains(command.cmd)) {
                            this.robotApiHandler.handleNormalCommand(cmd, line);
                        } else if (isValidConnection() && ModeController.getInstance(this.context).getMode() != 3) {
                            switch (cmd) {
                                case "move":
                                    this.eventHandler.move(command.power, command.angle);
                                    if (command.power > 0 && command.angle == 0) {
                                        new Timer().schedule(new TimerTask() { // from class: com.bullb.r2d2_nanopisystem.CommandReceiver.1
                                            @Override // java.util.TimerTask, java.lang.Runnable
                                            public void run() {
                                                CommandReceiver.this.eventHandler.moveHead(0);
                                            }
                                        }, 100L);
                                        break;
                                    }
                                    break;
                                case "move-head":
                                    this.eventHandler.moveHead(command.angle);
                                    break;
                                case "head-dir":
                                    this.eventHandler.moveHeadDir(command.dir);
                                    break;
                                case "projector":
                                    this.eventHandler.projectorMode(Integer.valueOf(command.mode).intValue());
                                    break;
                                case "reset-wdt":
                                    this.eventHandler.reset();
                                    break;
                                case "d-head-power":
                                    this.eventHandler.changeHeadDirPower(Integer.valueOf(command.power).intValue());
                                    break;
                                case "d-leg-power":
                                    this.eventHandler.changeLegPower(Integer.valueOf(command.power).intValue());
                                    break;
                                case "lcd":
                                    int l = -1;
                                    int s = -1;
                                    if (command.f37s != -1) {
                                        s = command.f37s;
                                    }
                                    if (command.f35l != -1) {
                                        l = command.f35l;
                                    }
                                    this.eventHandler.LCD(s, l);
                                    break;
                                case "led":
                                    int g = -1;
                                    int y = -1;
                                    int b = -1;
                                    int r = -1;
                                    if (command.f36r != -1) {
                                        r = command.f36r;
                                    }
                                    if (command.f33b != -1) {
                                        b = command.f33b;
                                    }
                                    if (command.f38y != -1) {
                                        y = command.f38y;
                                    }
                                    if (command.f34g != -1) {
                                        g = command.f34g;
                                    }
                                    this.eventHandler.LED(r, b, y, g);
                                    break;
                                case "mode":
                                    Log.d("CommandReceiver", Commander.MODE);
                                    this.eventHandler.mode(Integer.valueOf(command.mode).intValue());
                                    break;
                                case "play_sound":
                                    boolean interrupt = false;
                                    if (command.interrupt == 1) {
                                        interrupt = true;
                                    }
                                    this.eventHandler.playSound(Integer.valueOf(command.sound_id).intValue(), interrupt);
                                    break;
                                case "self_update":
                                    if (command.url != null && RobotPreference.getRobotBattery(this.context) > 50) {
                                        Log.d("CommandReceiver", "self update now: " + command.url);
                                        AppUpdater.getInstance(this.context).updateAPK(command.url);
                                        break;
                                    }
                                    break;
                                case "self_update_unsafe":
                                    Log.d("CommandReceiver", "self update now: " + command.url);
                                    if (command.url == null) {
                                        break;
                                    } else {
                                        AppUpdater.getInstance(this.context).updateAPK(command.url);
                                        break;
                                    }
                                case "reset_mcu":
                                    this.eventHandler.resetMCU();
                                    break;
                            }
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            this.lines.clear();
            return;
        }
        if (incomeData.contains("\n")) {
            this.data += incomeData.substring(0, incomeData.indexOf("\n"));
            incomeData2 = incomeData.substring(incomeData.indexOf("\n") + 1);
            this.lines.add(this.data);
            this.data = "";
        } else {
            this.data += incomeData;
            incomeData2 = "";
        }
        interpretCommand(incomeData2);
    }
}
