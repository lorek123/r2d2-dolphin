package com.koushikdutta.async.http.socketio;

import android.net.Uri;
import android.text.TextUtils;
import com.koushikdutta.async.callback.CompletedCallback;
import com.koushikdutta.async.future.Cancellable;
import com.koushikdutta.async.future.DependentCancellable;
import com.koushikdutta.async.future.Future;
import com.koushikdutta.async.future.FutureCallback;
import com.koushikdutta.async.future.SimpleFuture;
import com.koushikdutta.async.future.TransformFuture;
import com.koushikdutta.async.http.AsyncHttpClient;
import com.koushikdutta.async.http.WebSocket;
import com.koushikdutta.async.http.socketio.transport.SocketIOTransport;
import com.koushikdutta.async.http.socketio.transport.WebSocketTransport;
import com.koushikdutta.async.http.socketio.transport.XHRPollingTransport;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/* loaded from: classes.dex */
class SocketIOConnection {
    int ackCount;
    Cancellable connecting;
    int heartbeat;
    AsyncHttpClient httpClient;
    long reconnectDelay;
    SocketIORequest request;
    SocketIOTransport transport;
    ArrayList<SocketIOClient> clients = new ArrayList<>();
    Hashtable<String, Acknowledge> acknowledges = new Hashtable<>();

    private interface SelectCallback {
        void onSelect(SocketIOClient socketIOClient);
    }

    public SocketIOConnection(AsyncHttpClient httpClient, SocketIORequest request) {
        this.httpClient = httpClient;
        this.request = request;
        this.reconnectDelay = this.request.config.reconnectDelay;
    }

    public boolean isConnected() {
        return this.transport != null && this.transport.isConnected();
    }

    public void emitRaw(int type, SocketIOClient client, String message, Acknowledge acknowledge) {
        String ack = "";
        if (acknowledge != null) {
            StringBuilder append = new StringBuilder().append("");
            int i = this.ackCount;
            this.ackCount = i + 1;
            String id = append.append(i).toString();
            ack = id + "+";
            this.acknowledges.put(id, acknowledge);
        }
        this.transport.send(String.format(Locale.ENGLISH, "%d:%s:%s:%s", Integer.valueOf(type), ack, client.endpoint, message));
    }

    public void connect(SocketIOClient client) {
        if (!this.clients.contains(client)) {
            this.clients.add(client);
        }
        this.transport.send(String.format(Locale.ENGLISH, "1::%s", client.endpoint));
    }

    public void disconnect(SocketIOClient client) {
        this.clients.remove(client);
        boolean needsEndpointDisconnect = true;
        Iterator<SocketIOClient> it = this.clients.iterator();
        while (it.hasNext()) {
            SocketIOClient other = it.next();
            if (TextUtils.equals(other.endpoint, client.endpoint) || TextUtils.isEmpty(client.endpoint)) {
                needsEndpointDisconnect = false;
                break;
            }
        }
        SocketIOTransport ts = this.transport;
        if (needsEndpointDisconnect && ts != null) {
            ts.send(String.format(Locale.ENGLISH, "0::%s", client.endpoint));
        }
        if (this.clients.size() <= 0 && ts != null) {
            ts.setStringCallback(null);
            ts.setClosedCallback(null);
            ts.disconnect();
            this.transport = null;
        }
    }

    void reconnect(DependentCancellable child) {
        if (!isConnected()) {
            if (this.connecting != null && !this.connecting.isDone() && !this.connecting.isCancelled()) {
                if (child != null) {
                    child.setParent(this.connecting);
                }
            } else {
                this.request.logi("Reconnecting socket.io");
                this.connecting = ((C05582) this.httpClient.executeString(this.request, null).then(new TransformFuture<SocketIOTransport, String>() { // from class: com.koushikdutta.async.http.socketio.SocketIOConnection.2
                    C05582() {
                    }

                    @Override // com.koushikdutta.async.future.TransformFuture
                    public void transform(String result) throws Exception {
                        String[] parts = result.split(":");
                        String sessionId = parts[0];
                        if (!"".equals(parts[1])) {
                            SocketIOConnection.this.heartbeat = (Integer.parseInt(parts[1]) / 2) * 1000;
                        } else {
                            SocketIOConnection.this.heartbeat = 0;
                        }
                        String transportsLine = parts[3];
                        String[] transports = transportsLine.split(",");
                        HashSet<String> set = new HashSet<>(Arrays.asList(transports));
                        SimpleFuture<SocketIOTransport> transport = new SimpleFuture<>();
                        if (set.contains("websocket")) {
                            String sessionUrl = Uri.parse(SocketIOConnection.this.request.getUri().toString()).buildUpon().appendPath("websocket").appendPath(sessionId).build().toString();
                            SocketIOConnection.this.httpClient.websocket(sessionUrl, (String) null, (AsyncHttpClient.WebSocketConnectCallback) null).setCallback(new FutureCallback<WebSocket>() { // from class: com.koushikdutta.async.http.socketio.SocketIOConnection.2.1
                                final /* synthetic */ String val$sessionId;
                                final /* synthetic */ SimpleFuture val$transport;

                                AnonymousClass1(SimpleFuture transport2, String sessionId2) {
                                    r2 = transport2;
                                    r3 = sessionId2;
                                }

                                @Override // com.koushikdutta.async.future.FutureCallback
                                public void onCompleted(Exception e, WebSocket result2) {
                                    if (e != null) {
                                        r2.setComplete(e);
                                    } else {
                                        r2.setComplete((SimpleFuture) new WebSocketTransport(result2, r3));
                                    }
                                }
                            });
                        } else if (set.contains("xhr-polling")) {
                            String sessionUrl2 = Uri.parse(SocketIOConnection.this.request.getUri().toString()).buildUpon().appendPath("xhr-polling").appendPath(sessionId2).build().toString();
                            XHRPollingTransport xhrPolling = new XHRPollingTransport(SocketIOConnection.this.httpClient, sessionUrl2, sessionId2);
                            transport2.setComplete((SimpleFuture<SocketIOTransport>) xhrPolling);
                        } else {
                            throw new SocketIOException("transport not supported");
                        }
                        setComplete((Future) transport2);
                    }

                    /* renamed from: com.koushikdutta.async.http.socketio.SocketIOConnection$2$1 */
                    class AnonymousClass1 implements FutureCallback<WebSocket> {
                        final /* synthetic */ String val$sessionId;
                        final /* synthetic */ SimpleFuture val$transport;

                        AnonymousClass1(SimpleFuture transport2, String sessionId2) {
                            r2 = transport2;
                            r3 = sessionId2;
                        }

                        @Override // com.koushikdutta.async.future.FutureCallback
                        public void onCompleted(Exception e, WebSocket result2) {
                            if (e != null) {
                                r2.setComplete(e);
                            } else {
                                r2.setComplete((SimpleFuture) new WebSocketTransport(result2, r3));
                            }
                        }
                    }
                })).setCallback((FutureCallback) new FutureCallback<SocketIOTransport>() { // from class: com.koushikdutta.async.http.socketio.SocketIOConnection.1
                    C05521() {
                    }

                    @Override // com.koushikdutta.async.future.FutureCallback
                    public void onCompleted(Exception e, SocketIOTransport result) {
                        if (e != null) {
                            SocketIOConnection.this.reportDisconnect(e);
                            return;
                        }
                        SocketIOConnection.this.reconnectDelay = SocketIOConnection.this.request.config.reconnectDelay;
                        SocketIOConnection.this.transport = result;
                        SocketIOConnection.this.attach();
                    }
                });
                if (child != null) {
                    child.setParent(this.connecting);
                }
            }
        }
    }

    /* renamed from: com.koushikdutta.async.http.socketio.SocketIOConnection$2 */
    class C05582 extends TransformFuture<SocketIOTransport, String> {
        C05582() {
        }

        @Override // com.koushikdutta.async.future.TransformFuture
        public void transform(String result) throws Exception {
            String[] parts = result.split(":");
            String sessionId2 = parts[0];
            if (!"".equals(parts[1])) {
                SocketIOConnection.this.heartbeat = (Integer.parseInt(parts[1]) / 2) * 1000;
            } else {
                SocketIOConnection.this.heartbeat = 0;
            }
            String transportsLine = parts[3];
            String[] transports = transportsLine.split(",");
            HashSet<String> set = new HashSet<>(Arrays.asList(transports));
            SimpleFuture transport2 = new SimpleFuture<>();
            if (set.contains("websocket")) {
                String sessionUrl = Uri.parse(SocketIOConnection.this.request.getUri().toString()).buildUpon().appendPath("websocket").appendPath(sessionId2).build().toString();
                SocketIOConnection.this.httpClient.websocket(sessionUrl, (String) null, (AsyncHttpClient.WebSocketConnectCallback) null).setCallback(new FutureCallback<WebSocket>() { // from class: com.koushikdutta.async.http.socketio.SocketIOConnection.2.1
                    final /* synthetic */ String val$sessionId;
                    final /* synthetic */ SimpleFuture val$transport;

                    AnonymousClass1(SimpleFuture transport22, String sessionId22) {
                        r2 = transport22;
                        r3 = sessionId22;
                    }

                    @Override // com.koushikdutta.async.future.FutureCallback
                    public void onCompleted(Exception e, WebSocket result2) {
                        if (e != null) {
                            r2.setComplete(e);
                        } else {
                            r2.setComplete((SimpleFuture) new WebSocketTransport(result2, r3));
                        }
                    }
                });
            } else if (set.contains("xhr-polling")) {
                String sessionUrl2 = Uri.parse(SocketIOConnection.this.request.getUri().toString()).buildUpon().appendPath("xhr-polling").appendPath(sessionId22).build().toString();
                XHRPollingTransport xhrPolling = new XHRPollingTransport(SocketIOConnection.this.httpClient, sessionUrl2, sessionId22);
                transport22.setComplete((SimpleFuture<SocketIOTransport>) xhrPolling);
            } else {
                throw new SocketIOException("transport not supported");
            }
            setComplete((Future) transport22);
        }

        /* renamed from: com.koushikdutta.async.http.socketio.SocketIOConnection$2$1 */
        class AnonymousClass1 implements FutureCallback<WebSocket> {
            final /* synthetic */ String val$sessionId;
            final /* synthetic */ SimpleFuture val$transport;

            AnonymousClass1(SimpleFuture transport22, String sessionId22) {
                r2 = transport22;
                r3 = sessionId22;
            }

            @Override // com.koushikdutta.async.future.FutureCallback
            public void onCompleted(Exception e, WebSocket result2) {
                if (e != null) {
                    r2.setComplete(e);
                } else {
                    r2.setComplete((SimpleFuture) new WebSocketTransport(result2, r3));
                }
            }
        }
    }

    /* renamed from: com.koushikdutta.async.http.socketio.SocketIOConnection$1 */
    class C05521 implements FutureCallback<SocketIOTransport> {
        C05521() {
        }

        @Override // com.koushikdutta.async.future.FutureCallback
        public void onCompleted(Exception e, SocketIOTransport result) {
            if (e != null) {
                SocketIOConnection.this.reportDisconnect(e);
                return;
            }
            SocketIOConnection.this.reconnectDelay = SocketIOConnection.this.request.config.reconnectDelay;
            SocketIOConnection.this.transport = result;
            SocketIOConnection.this.attach();
        }
    }

    /* renamed from: com.koushikdutta.async.http.socketio.SocketIOConnection$3 */
    class RunnableC05593 implements Runnable {
        RunnableC05593() {
        }

        @Override // java.lang.Runnable
        public void run() {
            SocketIOTransport ts = SocketIOConnection.this.transport;
            if (SocketIOConnection.this.heartbeat > 0 && ts != null && ts.isConnected()) {
                ts.send("2:::");
                ts.getServer().postDelayed(this, SocketIOConnection.this.heartbeat);
            }
        }
    }

    void setupHeartbeat() {
        Runnable heartbeatRunner = new Runnable() { // from class: com.koushikdutta.async.http.socketio.SocketIOConnection.3
            RunnableC05593() {
            }

            @Override // java.lang.Runnable
            public void run() {
                SocketIOTransport ts = SocketIOConnection.this.transport;
                if (SocketIOConnection.this.heartbeat > 0 && ts != null && ts.isConnected()) {
                    ts.send("2:::");
                    ts.getServer().postDelayed(this, SocketIOConnection.this.heartbeat);
                }
            }
        };
        heartbeatRunner.run();
    }

    public void select(String endpoint, SelectCallback callback) {
        Iterator<SocketIOClient> it = this.clients.iterator();
        while (it.hasNext()) {
            SocketIOClient client = it.next();
            if (endpoint == null || TextUtils.equals(client.endpoint, endpoint)) {
                callback.onSelect(client);
            }
        }
    }

    private void delayReconnect() {
        if (this.transport == null && this.clients.size() != 0) {
            boolean disconnected = false;
            Iterator<SocketIOClient> it = this.clients.iterator();
            while (true) {
                if (!it.hasNext()) {
                    break;
                }
                SocketIOClient client = it.next();
                if (client.disconnected) {
                    disconnected = true;
                    break;
                }
            }
            if (disconnected) {
                this.httpClient.getServer().postDelayed(new Runnable() { // from class: com.koushikdutta.async.http.socketio.SocketIOConnection.4
                    RunnableC05604() {
                    }

                    @Override // java.lang.Runnable
                    public void run() {
                        SocketIOConnection.this.reconnect(null);
                    }
                }, nextReconnectDelay(this.reconnectDelay));
                this.reconnectDelay *= 2;
                if (this.request.config.reconnectDelayMax > 0) {
                    this.reconnectDelay = Math.min(this.reconnectDelay, this.request.config.reconnectDelayMax);
                }
            }
        }
    }

    /* renamed from: com.koushikdutta.async.http.socketio.SocketIOConnection$4 */
    class RunnableC05604 implements Runnable {
        RunnableC05604() {
        }

        @Override // java.lang.Runnable
        public void run() {
            SocketIOConnection.this.reconnect(null);
        }
    }

    private long nextReconnectDelay(long targetDelay) {
        return (targetDelay < 2 || targetDelay > 4611686018427387903L || !this.request.config.randomizeReconnectDelay) ? targetDelay : (targetDelay >> 1) + ((long) (targetDelay * Math.random()));
    }

    public void reportDisconnect(Exception ex) {
        if (ex != null) {
            this.request.loge("socket.io disconnected", ex);
        } else {
            this.request.logi("socket.io disconnected");
        }
        select(null, new SelectCallback() { // from class: com.koushikdutta.async.http.socketio.SocketIOConnection.5
            final /* synthetic */ Exception val$ex;

            C05615(Exception ex2) {
                r2 = ex2;
            }

            @Override // com.koushikdutta.async.http.socketio.SocketIOConnection.SelectCallback
            public void onSelect(SocketIOClient client) {
                if (client.connected) {
                    client.disconnected = true;
                    DisconnectCallback closed = client.getDisconnectCallback();
                    if (closed != null) {
                        closed.onDisconnect(r2);
                        return;
                    }
                    return;
                }
                ConnectCallback callback = client.connectCallback;
                if (callback != null) {
                    callback.onConnectCompleted(r2, client);
                }
            }
        });
        delayReconnect();
    }

    /* renamed from: com.koushikdutta.async.http.socketio.SocketIOConnection$5 */
    class C05615 implements SelectCallback {
        final /* synthetic */ Exception val$ex;

        C05615(Exception ex2) {
            r2 = ex2;
        }

        @Override // com.koushikdutta.async.http.socketio.SocketIOConnection.SelectCallback
        public void onSelect(SocketIOClient client) {
            if (client.connected) {
                client.disconnected = true;
                DisconnectCallback closed = client.getDisconnectCallback();
                if (closed != null) {
                    closed.onDisconnect(r2);
                    return;
                }
                return;
            }
            ConnectCallback callback = client.connectCallback;
            if (callback != null) {
                callback.onConnectCompleted(r2, client);
            }
        }
    }

    /* renamed from: com.koushikdutta.async.http.socketio.SocketIOConnection$6 */
    class C05626 implements SelectCallback {
        C05626() {
        }

        @Override // com.koushikdutta.async.http.socketio.SocketIOConnection.SelectCallback
        public void onSelect(SocketIOClient client) {
            if (!client.isConnected()) {
                if (!client.connected) {
                    client.connected = true;
                    ConnectCallback callback = client.connectCallback;
                    if (callback != null) {
                        callback.onConnectCompleted(null, client);
                        return;
                    }
                    return;
                }
                if (client.disconnected) {
                    client.disconnected = false;
                    ReconnectCallback callback2 = client.reconnectCallback;
                    if (callback2 != null) {
                        callback2.onReconnect();
                    }
                }
            }
        }
    }

    public void reportConnect(String endpoint) {
        select(endpoint, new SelectCallback() { // from class: com.koushikdutta.async.http.socketio.SocketIOConnection.6
            C05626() {
            }

            @Override // com.koushikdutta.async.http.socketio.SocketIOConnection.SelectCallback
            public void onSelect(SocketIOClient client) {
                if (!client.isConnected()) {
                    if (!client.connected) {
                        client.connected = true;
                        ConnectCallback callback = client.connectCallback;
                        if (callback != null) {
                            callback.onConnectCompleted(null, client);
                            return;
                        }
                        return;
                    }
                    if (client.disconnected) {
                        client.disconnected = false;
                        ReconnectCallback callback2 = client.reconnectCallback;
                        if (callback2 != null) {
                            callback2.onReconnect();
                        }
                    }
                }
            }
        });
    }

    /* renamed from: com.koushikdutta.async.http.socketio.SocketIOConnection$7 */
    class C05637 implements SelectCallback {
        final /* synthetic */ Acknowledge val$acknowledge;
        final /* synthetic */ JSONObject val$jsonMessage;

        C05637(JSONObject jSONObject, Acknowledge acknowledge) {
            r2 = jSONObject;
            r3 = acknowledge;
        }

        @Override // com.koushikdutta.async.http.socketio.SocketIOConnection.SelectCallback
        public void onSelect(SocketIOClient client) {
            JSONCallback callback = client.jsonCallback;
            if (callback != null) {
                callback.onJSON(r2, r3);
            }
        }
    }

    public void reportJson(String endpoint, JSONObject jsonMessage, Acknowledge acknowledge) {
        select(endpoint, new SelectCallback() { // from class: com.koushikdutta.async.http.socketio.SocketIOConnection.7
            final /* synthetic */ Acknowledge val$acknowledge;
            final /* synthetic */ JSONObject val$jsonMessage;

            C05637(JSONObject jsonMessage2, Acknowledge acknowledge2) {
                r2 = jsonMessage2;
                r3 = acknowledge2;
            }

            @Override // com.koushikdutta.async.http.socketio.SocketIOConnection.SelectCallback
            public void onSelect(SocketIOClient client) {
                JSONCallback callback = client.jsonCallback;
                if (callback != null) {
                    callback.onJSON(r2, r3);
                }
            }
        });
    }

    /* renamed from: com.koushikdutta.async.http.socketio.SocketIOConnection$8 */
    class C05648 implements SelectCallback {
        final /* synthetic */ Acknowledge val$acknowledge;
        final /* synthetic */ String val$string;

        C05648(String str, Acknowledge acknowledge) {
            r2 = str;
            r3 = acknowledge;
        }

        @Override // com.koushikdutta.async.http.socketio.SocketIOConnection.SelectCallback
        public void onSelect(SocketIOClient client) {
            StringCallback callback = client.stringCallback;
            if (callback != null) {
                callback.onString(r2, r3);
            }
        }
    }

    public void reportString(String endpoint, String string, Acknowledge acknowledge) {
        select(endpoint, new SelectCallback() { // from class: com.koushikdutta.async.http.socketio.SocketIOConnection.8
            final /* synthetic */ Acknowledge val$acknowledge;
            final /* synthetic */ String val$string;

            C05648(String string2, Acknowledge acknowledge2) {
                r2 = string2;
                r3 = acknowledge2;
            }

            @Override // com.koushikdutta.async.http.socketio.SocketIOConnection.SelectCallback
            public void onSelect(SocketIOClient client) {
                StringCallback callback = client.stringCallback;
                if (callback != null) {
                    callback.onString(r2, r3);
                }
            }
        });
    }

    /* renamed from: com.koushikdutta.async.http.socketio.SocketIOConnection$9 */
    class C05659 implements SelectCallback {
        final /* synthetic */ Acknowledge val$acknowledge;
        final /* synthetic */ JSONArray val$arguments;
        final /* synthetic */ String val$event;

        C05659(String str, JSONArray jSONArray, Acknowledge acknowledge) {
            r2 = str;
            r3 = jSONArray;
            r4 = acknowledge;
        }

        @Override // com.koushikdutta.async.http.socketio.SocketIOConnection.SelectCallback
        public void onSelect(SocketIOClient client) {
            client.onEvent(r2, r3, r4);
        }
    }

    public void reportEvent(String endpoint, String event, JSONArray arguments, Acknowledge acknowledge) {
        select(endpoint, new SelectCallback() { // from class: com.koushikdutta.async.http.socketio.SocketIOConnection.9
            final /* synthetic */ Acknowledge val$acknowledge;
            final /* synthetic */ JSONArray val$arguments;
            final /* synthetic */ String val$event;

            C05659(String event2, JSONArray arguments2, Acknowledge acknowledge2) {
                r2 = event2;
                r3 = arguments2;
                r4 = acknowledge2;
            }

            @Override // com.koushikdutta.async.http.socketio.SocketIOConnection.SelectCallback
            public void onSelect(SocketIOClient client) {
                client.onEvent(r2, r3, r4);
            }
        });
    }

    /* renamed from: com.koushikdutta.async.http.socketio.SocketIOConnection$10 */
    class C055310 implements SelectCallback {
        final /* synthetic */ String val$error;

        C055310(String str) {
            r2 = str;
        }

        @Override // com.koushikdutta.async.http.socketio.SocketIOConnection.SelectCallback
        public void onSelect(SocketIOClient client) {
            ErrorCallback callback = client.errorCallback;
            if (callback != null) {
                callback.onError(r2);
            }
        }
    }

    public void reportError(String endpoint, String error) {
        select(endpoint, new SelectCallback() { // from class: com.koushikdutta.async.http.socketio.SocketIOConnection.10
            final /* synthetic */ String val$error;

            C055310(String error2) {
                r2 = error2;
            }

            @Override // com.koushikdutta.async.http.socketio.SocketIOConnection.SelectCallback
            public void onSelect(SocketIOClient client) {
                ErrorCallback callback = client.errorCallback;
                if (callback != null) {
                    callback.onError(r2);
                }
            }
        });
    }

    public Acknowledge acknowledge(String _messageId, String endpoint) {
        if (TextUtils.isEmpty(_messageId)) {
            return null;
        }
        String messageId = _messageId.replaceAll("\\+$", "");
        return new Acknowledge() { // from class: com.koushikdutta.async.http.socketio.SocketIOConnection.11
            final /* synthetic */ String val$endpoint;
            final /* synthetic */ String val$messageId;

            C055411(String endpoint2, String messageId2) {
                r2 = endpoint2;
                r3 = messageId2;
            }

            @Override // com.koushikdutta.async.http.socketio.Acknowledge
            public void acknowledge(JSONArray arguments) {
                String data = arguments != null ? "+" + arguments.toString() : "";
                SocketIOTransport transport = SocketIOConnection.this.transport;
                if (transport == null) {
                    Exception e = new SocketIOException("not connected to server");
                    SocketIOConnection.this.select(r2, new SelectCallback() { // from class: com.koushikdutta.async.http.socketio.SocketIOConnection.11.1
                        final /* synthetic */ Exception val$e;

                        AnonymousClass1(Exception e2) {
                            r2 = e2;
                        }

                        @Override // com.koushikdutta.async.http.socketio.SocketIOConnection.SelectCallback
                        public void onSelect(SocketIOClient client) {
                            ExceptionCallback callback = client.exceptionCallback;
                            if (callback != null) {
                                callback.onException(r2);
                            }
                        }
                    });
                } else {
                    transport.send(String.format(Locale.ENGLISH, "6:::%s%s", r3, data));
                }
            }

            /* renamed from: com.koushikdutta.async.http.socketio.SocketIOConnection$11$1 */
            class AnonymousClass1 implements SelectCallback {
                final /* synthetic */ Exception val$e;

                AnonymousClass1(Exception e2) {
                    r2 = e2;
                }

                @Override // com.koushikdutta.async.http.socketio.SocketIOConnection.SelectCallback
                public void onSelect(SocketIOClient client) {
                    ExceptionCallback callback = client.exceptionCallback;
                    if (callback != null) {
                        callback.onException(r2);
                    }
                }
            }
        };
    }

    /* renamed from: com.koushikdutta.async.http.socketio.SocketIOConnection$11 */
    class C055411 implements Acknowledge {
        final /* synthetic */ String val$endpoint;
        final /* synthetic */ String val$messageId;

        C055411(String endpoint2, String messageId2) {
            r2 = endpoint2;
            r3 = messageId2;
        }

        @Override // com.koushikdutta.async.http.socketio.Acknowledge
        public void acknowledge(JSONArray arguments) {
            String data = arguments != null ? "+" + arguments.toString() : "";
            SocketIOTransport transport = SocketIOConnection.this.transport;
            if (transport == null) {
                Exception e2 = new SocketIOException("not connected to server");
                SocketIOConnection.this.select(r2, new SelectCallback() { // from class: com.koushikdutta.async.http.socketio.SocketIOConnection.11.1
                    final /* synthetic */ Exception val$e;

                    AnonymousClass1(Exception e22) {
                        r2 = e22;
                    }

                    @Override // com.koushikdutta.async.http.socketio.SocketIOConnection.SelectCallback
                    public void onSelect(SocketIOClient client) {
                        ExceptionCallback callback = client.exceptionCallback;
                        if (callback != null) {
                            callback.onException(r2);
                        }
                    }
                });
            } else {
                transport.send(String.format(Locale.ENGLISH, "6:::%s%s", r3, data));
            }
        }

        /* renamed from: com.koushikdutta.async.http.socketio.SocketIOConnection$11$1 */
        class AnonymousClass1 implements SelectCallback {
            final /* synthetic */ Exception val$e;

            AnonymousClass1(Exception e22) {
                r2 = e22;
            }

            @Override // com.koushikdutta.async.http.socketio.SocketIOConnection.SelectCallback
            public void onSelect(SocketIOClient client) {
                ExceptionCallback callback = client.exceptionCallback;
                if (callback != null) {
                    callback.onException(r2);
                }
            }
        }
    }

    public void attach() {
        if (this.transport.heartbeats()) {
            setupHeartbeat();
        }
        this.transport.setClosedCallback(new CompletedCallback() { // from class: com.koushikdutta.async.http.socketio.SocketIOConnection.12
            C055512() {
            }

            @Override // com.koushikdutta.async.callback.CompletedCallback
            public void onCompleted(Exception ex) {
                SocketIOConnection.this.transport = null;
                SocketIOConnection.this.reportDisconnect(ex);
            }
        });
        this.transport.setStringCallback(new SocketIOTransport.StringCallback() { // from class: com.koushikdutta.async.http.socketio.SocketIOConnection.13
            C055613() {
            }

            @Override // com.koushikdutta.async.http.socketio.transport.SocketIOTransport.StringCallback
            public void onStringAvailable(String message) {
                try {
                    String[] parts = message.split(":", 4);
                    int code = Integer.parseInt(parts[0]);
                    switch (code) {
                        case 0:
                            SocketIOConnection.this.transport.disconnect();
                            SocketIOConnection.this.reportDisconnect(null);
                            return;
                        case 1:
                            SocketIOConnection.this.reportConnect(parts[2]);
                            return;
                        case 2:
                            SocketIOConnection.this.transport.send("2::");
                            return;
                        case 3:
                            SocketIOConnection.this.reportString(parts[2], parts[3], SocketIOConnection.this.acknowledge(parts[1], parts[2]));
                            return;
                        case 4:
                            String dataString = parts[3];
                            JSONObject jsonMessage = new JSONObject(dataString);
                            SocketIOConnection.this.reportJson(parts[2], jsonMessage, SocketIOConnection.this.acknowledge(parts[1], parts[2]));
                            return;
                        case 5:
                            String dataString2 = parts[3];
                            JSONObject data = new JSONObject(dataString2);
                            String event = data.getString("name");
                            JSONArray args = data.optJSONArray("args");
                            SocketIOConnection.this.reportEvent(parts[2], event, args, SocketIOConnection.this.acknowledge(parts[1], parts[2]));
                            return;
                        case 6:
                            String[] ackParts = parts[3].split("\\+", 2);
                            Acknowledge ack = SocketIOConnection.this.acknowledges.remove(ackParts[0]);
                            if (ack != null) {
                                JSONArray arguments = null;
                                if (ackParts.length == 2) {
                                    arguments = new JSONArray(ackParts[1]);
                                }
                                ack.acknowledge(arguments);
                                return;
                            }
                            return;
                        case 7:
                            SocketIOConnection.this.reportError(parts[2], parts[3]);
                            return;
                        case 8:
                            return;
                        default:
                            throw new SocketIOException("unknown code");
                    }
                } catch (Exception ex) {
                    SocketIOConnection.this.transport.setClosedCallback(null);
                    SocketIOConnection.this.transport.disconnect();
                    SocketIOConnection.this.transport = null;
                    SocketIOConnection.this.reportDisconnect(ex);
                }
            }
        });
        select(null, new SelectCallback() { // from class: com.koushikdutta.async.http.socketio.SocketIOConnection.14
            C055714() {
            }

            @Override // com.koushikdutta.async.http.socketio.SocketIOConnection.SelectCallback
            public void onSelect(SocketIOClient client) {
                if (!TextUtils.isEmpty(client.endpoint)) {
                    SocketIOConnection.this.connect(client);
                }
            }
        });
    }

    /* renamed from: com.koushikdutta.async.http.socketio.SocketIOConnection$12 */
    class C055512 implements CompletedCallback {
        C055512() {
        }

        @Override // com.koushikdutta.async.callback.CompletedCallback
        public void onCompleted(Exception ex) {
            SocketIOConnection.this.transport = null;
            SocketIOConnection.this.reportDisconnect(ex);
        }
    }

    /* renamed from: com.koushikdutta.async.http.socketio.SocketIOConnection$13 */
    class C055613 implements SocketIOTransport.StringCallback {
        C055613() {
        }

        @Override // com.koushikdutta.async.http.socketio.transport.SocketIOTransport.StringCallback
        public void onStringAvailable(String message) {
            try {
                String[] parts = message.split(":", 4);
                int code = Integer.parseInt(parts[0]);
                switch (code) {
                    case 0:
                        SocketIOConnection.this.transport.disconnect();
                        SocketIOConnection.this.reportDisconnect(null);
                        return;
                    case 1:
                        SocketIOConnection.this.reportConnect(parts[2]);
                        return;
                    case 2:
                        SocketIOConnection.this.transport.send("2::");
                        return;
                    case 3:
                        SocketIOConnection.this.reportString(parts[2], parts[3], SocketIOConnection.this.acknowledge(parts[1], parts[2]));
                        return;
                    case 4:
                        String dataString = parts[3];
                        JSONObject jsonMessage = new JSONObject(dataString);
                        SocketIOConnection.this.reportJson(parts[2], jsonMessage, SocketIOConnection.this.acknowledge(parts[1], parts[2]));
                        return;
                    case 5:
                        String dataString2 = parts[3];
                        JSONObject data = new JSONObject(dataString2);
                        String event = data.getString("name");
                        JSONArray args = data.optJSONArray("args");
                        SocketIOConnection.this.reportEvent(parts[2], event, args, SocketIOConnection.this.acknowledge(parts[1], parts[2]));
                        return;
                    case 6:
                        String[] ackParts = parts[3].split("\\+", 2);
                        Acknowledge ack = SocketIOConnection.this.acknowledges.remove(ackParts[0]);
                        if (ack != null) {
                            JSONArray arguments = null;
                            if (ackParts.length == 2) {
                                arguments = new JSONArray(ackParts[1]);
                            }
                            ack.acknowledge(arguments);
                            return;
                        }
                        return;
                    case 7:
                        SocketIOConnection.this.reportError(parts[2], parts[3]);
                        return;
                    case 8:
                        return;
                    default:
                        throw new SocketIOException("unknown code");
                }
            } catch (Exception ex) {
                SocketIOConnection.this.transport.setClosedCallback(null);
                SocketIOConnection.this.transport.disconnect();
                SocketIOConnection.this.transport = null;
                SocketIOConnection.this.reportDisconnect(ex);
            }
        }
    }

    /* renamed from: com.koushikdutta.async.http.socketio.SocketIOConnection$14 */
    class C055714 implements SelectCallback {
        C055714() {
        }

        @Override // com.koushikdutta.async.http.socketio.SocketIOConnection.SelectCallback
        public void onSelect(SocketIOClient client) {
            if (!TextUtils.isEmpty(client.endpoint)) {
                SocketIOConnection.this.connect(client);
            }
        }
    }
}
