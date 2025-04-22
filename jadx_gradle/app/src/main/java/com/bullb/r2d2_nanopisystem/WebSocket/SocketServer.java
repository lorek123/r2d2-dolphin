package com.bullb.r2d2_nanopisystem.WebSocket;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import com.bullb.r2d2_nanopisystem.ModeControl.ModeController;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Iterator;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

/* loaded from: classes.dex */
public class SocketServer extends WebSocketServer {
    public static final String TAG = "SocketServer";
    private static final int TIMEOUT_SECONDS = 5;
    private static final int WEB_SOCKET_PORT = 8887;
    private static SocketServer socketServer;
    private final boolean LOG;
    private ArrayList<SocketConnection> connections;
    private Context context;

    public static synchronized SocketServer getInstance(Context context) throws UnknownHostException {
        SocketServer socketServer2;
        synchronized (SocketServer.class) {
            if (socketServer == null) {
                socketServer = new SocketServer(context);
            }
            socketServer2 = socketServer;
        }
        return socketServer2;
    }

    public SocketServer(Context context) throws UnknownHostException {
        super(new InetSocketAddress(WEB_SOCKET_PORT));
        this.LOG = true;
        this.connections = new ArrayList<>();
        this.context = context;
        setConnectionLostTimeout(5);
    }

    @Override // org.java_websocket.server.WebSocketServer
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        Log.d(TAG, conn.getRemoteSocketAddress().getAddress().getHostAddress() + "entered the room!");
        Iterator<SocketConnection> it = this.connections.iterator();
        while (it.hasNext()) {
            SocketConnection c = it.next();
            if (c.getWebSocket().getRemoteSocketAddress().getHostName().equals(conn.getRemoteSocketAddress().getHostName())) {
                c.close();
                Log.d(TAG, "Same source connected, closed old Connection");
            }
        }
        this.connections.add(new SocketConnection(this.context, conn, this));
        Log.d(TAG, "connecting size:" + String.valueOf(this.connections.size()));
    }

    @Override // org.java_websocket.server.WebSocketServer
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        Log.d(TAG, conn + " has left the room!");
        removeFromList(conn);
        Log.d(TAG, "connecting size:" + String.valueOf(this.connections.size()));
        checkControlModeNeeded();
    }

    private void removeFromList(WebSocket conn) {
        SocketConnection connection = getConnectionFromSocket(conn);
        if (connection != null) {
            this.connections.remove(getConnectionFromSocket(conn));
        }
    }

    /* renamed from: com.bullb.r2d2_nanopisystem.WebSocket.SocketServer$1 */
    class RunnableC03171 implements Runnable {
        final /* synthetic */ WebSocket val$conn;
        final /* synthetic */ String val$message;

        RunnableC03171(WebSocket webSocket, String str) {
            r2 = webSocket;
            r3 = str;
        }

        @Override // java.lang.Runnable
        public void run() {
            Log.d(SocketServer.TAG, r2 + ": " + r3);
            SocketConnection socketConnection = SocketServer.this.getConnectionFromSocket(r2);
            if (socketConnection == null) {
                r2.close();
            } else {
                socketConnection.receiveMessage(r3);
            }
        }
    }

    @Override // org.java_websocket.server.WebSocketServer
    public void onMessage(WebSocket conn, String message) {
        Log.d(TAG, "onMessage: " + message);
        ((Activity) this.context).runOnUiThread(new Runnable() { // from class: com.bullb.r2d2_nanopisystem.WebSocket.SocketServer.1
            final /* synthetic */ WebSocket val$conn;
            final /* synthetic */ String val$message;

            RunnableC03171(WebSocket conn2, String message2) {
                r2 = conn2;
                r3 = message2;
            }

            @Override // java.lang.Runnable
            public void run() {
                Log.d(SocketServer.TAG, r2 + ": " + r3);
                SocketConnection socketConnection = SocketServer.this.getConnectionFromSocket(r2);
                if (socketConnection == null) {
                    r2.close();
                } else {
                    socketConnection.receiveMessage(r3);
                }
            }
        });
    }

    public void checkControlModeNeeded() {
        int controllingNum = getControllingNum();
        Log.d(TAG, "Number of user controlling:" + String.valueOf(controllingNum));
        if (controllingNum > 0) {
            ModeController.getInstance(this.context).startUserControlMode();
        } else {
            ModeController.getInstance(this.context).stopUserControlMode();
        }
    }

    public int getControllingNum() {
        int num = 0;
        Iterator<SocketConnection> it = this.connections.iterator();
        while (it.hasNext()) {
            SocketConnection socketConnection = it.next();
            if (socketConnection.isControlling()) {
                num++;
            }
        }
        return num;
    }

    public SocketConnection getConnectionFromSocket(WebSocket webSocket) {
        Iterator<SocketConnection> it = this.connections.iterator();
        while (it.hasNext()) {
            SocketConnection connection = it.next();
            if (connection.getWebSocket().equals(webSocket)) {
                return connection;
            }
        }
        webSocket.close();
        return null;
    }

    @Override // org.java_websocket.server.WebSocketServer
    public void onError(WebSocket conn, Exception ex) {
        Log.d(TAG, conn + ": " + ex);
        removeFromList(conn);
        Log.d(TAG, "connecting size:" + String.valueOf(this.connections.size()));
    }

    @Override // org.java_websocket.server.WebSocketServer
    public void onStart() {
        Log.d(TAG, "Server started!");
    }

    public void startServer() {
        clearAll();
        try {
            socketServer.start();
        } catch (IllegalStateException e) {
            e.printStackTrace();
        }
    }

    public void stopServer() {
        clearAll();
    }

    public void closeClient(String uuid) {
        Iterator<SocketConnection> it = this.connections.iterator();
        while (it.hasNext()) {
            SocketConnection connection = it.next();
            if (connection.getClientUUID().equals(uuid)) {
                connection.close();
                this.connections.remove(connection);
                return;
            }
        }
    }

    public void clearAll() {
        Iterator<SocketConnection> it = this.connections.iterator();
        while (it.hasNext()) {
            SocketConnection connection = it.next();
            connection.close();
        }
        this.connections.clear();
    }

    public void send(String data) {
        Iterator<SocketConnection> it = this.connections.iterator();
        while (it.hasNext()) {
            SocketConnection connection = it.next();
            connection.send(data);
        }
    }
}
