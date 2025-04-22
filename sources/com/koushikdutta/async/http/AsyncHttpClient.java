package com.koushikdutta.async.http;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import com.koushikdutta.async.AsyncSSLException;
import com.koushikdutta.async.AsyncServer;
import com.koushikdutta.async.AsyncSocket;
import com.koushikdutta.async.ByteBufferList;
import com.koushikdutta.async.DataEmitter;
import com.koushikdutta.async.callback.CompletedCallback;
import com.koushikdutta.async.callback.ConnectCallback;
import com.koushikdutta.async.callback.DataCallback;
import com.koushikdutta.async.future.Cancellable;
import com.koushikdutta.async.future.Future;
import com.koushikdutta.async.future.FutureCallback;
import com.koushikdutta.async.future.SimpleFuture;
import com.koushikdutta.async.http.AsyncHttpClientMiddleware;
import com.koushikdutta.async.http.callback.HttpConnectCallback;
import com.koushikdutta.async.http.callback.RequestCallback;
import com.koushikdutta.async.http.spdy.SpdyMiddleware;
import com.koushikdutta.async.parser.AsyncParser;
import com.koushikdutta.async.parser.ByteBufferListParser;
import com.koushikdutta.async.parser.JSONArrayParser;
import com.koushikdutta.async.parser.JSONObjectParser;
import com.koushikdutta.async.parser.StringParser;
import com.koushikdutta.async.stream.OutputStreamDataCallback;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URL;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import org.json.JSONArray;
import org.json.JSONObject;

/* loaded from: classes.dex */
public class AsyncHttpClient {
    static final /* synthetic */ boolean $assertionsDisabled;
    private static final String LOGTAG = "AsyncHttp";
    private static AsyncHttpClient mDefaultInstance;
    HttpTransportMiddleware httpTransportMiddleware;
    final List<AsyncHttpClientMiddleware> mMiddleware = new CopyOnWriteArrayList();
    AsyncServer mServer;
    AsyncSocketMiddleware socketMiddleware;
    SpdyMiddleware sslSocketMiddleware;

    public static abstract class DownloadCallback extends RequestCallbackBase<ByteBufferList> {
    }

    public static abstract class FileCallback extends RequestCallbackBase<File> {
    }

    public static abstract class JSONArrayCallback extends RequestCallbackBase<JSONArray> {
    }

    public static abstract class JSONObjectCallback extends RequestCallbackBase<JSONObject> {
    }

    public static abstract class StringCallback extends RequestCallbackBase<String> {
    }

    public interface WebSocketConnectCallback {
        void onCompleted(Exception exc, WebSocket webSocket);
    }

    static {
        $assertionsDisabled = !AsyncHttpClient.class.desiredAssertionStatus();
    }

    public static AsyncHttpClient getDefaultInstance() {
        if (mDefaultInstance == null) {
            mDefaultInstance = new AsyncHttpClient(AsyncServer.getDefault());
        }
        return mDefaultInstance;
    }

    public Collection<AsyncHttpClientMiddleware> getMiddleware() {
        return this.mMiddleware;
    }

    public void insertMiddleware(AsyncHttpClientMiddleware middleware) {
        this.mMiddleware.add(0, middleware);
    }

    public AsyncHttpClient(AsyncServer server) {
        this.mServer = server;
        AsyncSocketMiddleware asyncSocketMiddleware = new AsyncSocketMiddleware(this);
        this.socketMiddleware = asyncSocketMiddleware;
        insertMiddleware(asyncSocketMiddleware);
        SpdyMiddleware spdyMiddleware = new SpdyMiddleware(this);
        this.sslSocketMiddleware = spdyMiddleware;
        insertMiddleware(spdyMiddleware);
        HttpTransportMiddleware httpTransportMiddleware = new HttpTransportMiddleware();
        this.httpTransportMiddleware = httpTransportMiddleware;
        insertMiddleware(httpTransportMiddleware);
        this.sslSocketMiddleware.addEngineConfigurator(new SSLEngineSNIConfigurator());
    }

    @SuppressLint({"NewApi"})
    public static void setupAndroidProxy(AsyncHttpRequest request) {
        String proxyHost;
        if (request.proxyHost == null) {
            try {
                List<Proxy> proxies = ProxySelector.getDefault().select(URI.create(request.getUri().toString()));
                if (!proxies.isEmpty()) {
                    Proxy proxy = proxies.get(0);
                    if (proxy.type() == Proxy.Type.HTTP && (proxy.address() instanceof InetSocketAddress)) {
                        InetSocketAddress proxyAddress = (InetSocketAddress) proxy.address();
                        if (Build.VERSION.SDK_INT >= 14) {
                            proxyHost = proxyAddress.getHostString();
                        } else {
                            InetAddress address = proxyAddress.getAddress();
                            if (address != null) {
                                proxyHost = address.getHostAddress();
                            } else {
                                proxyHost = proxyAddress.getHostName();
                            }
                        }
                        request.enableProxy(proxyHost, proxyAddress.getPort());
                    }
                }
            } catch (Exception e) {
            }
        }
    }

    public AsyncSocketMiddleware getSocketMiddleware() {
        return this.socketMiddleware;
    }

    public SpdyMiddleware getSSLSocketMiddleware() {
        return this.sslSocketMiddleware;
    }

    public Future<AsyncHttpResponse> execute(AsyncHttpRequest request, HttpConnectCallback callback) {
        FutureAsyncHttpResponse ret = new FutureAsyncHttpResponse();
        execute(request, 0, ret, callback);
        return ret;
    }

    public Future<AsyncHttpResponse> execute(String uri, HttpConnectCallback callback) {
        return execute(new AsyncHttpGet(uri), callback);
    }

    private class FutureAsyncHttpResponse extends SimpleFuture<AsyncHttpResponse> {
        public Object scheduled;
        public AsyncSocket socket;
        public Runnable timeoutRunnable;

        private FutureAsyncHttpResponse() {
        }

        /* synthetic */ FutureAsyncHttpResponse(AsyncHttpClient x0, RunnableC04791 x1) {
            this();
        }

        @Override // com.koushikdutta.async.future.SimpleFuture, com.koushikdutta.async.future.SimpleCancellable, com.koushikdutta.async.future.Cancellable
        public boolean cancel() {
            if (!super.cancel()) {
                return false;
            }
            if (this.socket != null) {
                this.socket.setDataCallback(new DataCallback.NullDataCallback());
                this.socket.close();
            }
            if (this.scheduled != null) {
                AsyncHttpClient.this.mServer.removeAllCallbacks(this.scheduled);
            }
            return true;
        }
    }

    public void reportConnectedCompleted(FutureAsyncHttpResponse cancel, Exception ex, AsyncHttpResponseImpl response, AsyncHttpRequest request, HttpConnectCallback callback) {
        boolean complete;
        if (!$assertionsDisabled && callback == null) {
            throw new AssertionError();
        }
        this.mServer.removeAllCallbacks(cancel.scheduled);
        if (ex != null) {
            request.loge("Connection error", ex);
            complete = cancel.setComplete(ex);
        } else {
            request.logd("Connection successful");
            complete = cancel.setComplete((FutureAsyncHttpResponse) response);
        }
        if (complete) {
            callback.onConnectCompleted(ex, response);
            if (!$assertionsDisabled && ex == null && response.socket() != null && response.getDataCallback() == null && !response.isPaused()) {
                throw new AssertionError();
            }
            return;
        }
        if (response != null) {
            response.setDataCallback(new DataCallback.NullDataCallback());
            response.close();
        }
    }

    public void execute(AsyncHttpRequest request, int redirectCount, FutureAsyncHttpResponse cancel, HttpConnectCallback callback) {
        if (this.mServer.isAffinityThread()) {
            executeAffinity(request, redirectCount, cancel, callback);
        } else {
            this.mServer.post(new Runnable() { // from class: com.koushikdutta.async.http.AsyncHttpClient.1
                final /* synthetic */ HttpConnectCallback val$callback;
                final /* synthetic */ FutureAsyncHttpResponse val$cancel;
                final /* synthetic */ int val$redirectCount;
                final /* synthetic */ AsyncHttpRequest val$request;

                RunnableC04791(AsyncHttpRequest request2, int redirectCount2, FutureAsyncHttpResponse cancel2, HttpConnectCallback callback2) {
                    r2 = request2;
                    r3 = redirectCount2;
                    r4 = cancel2;
                    r5 = callback2;
                }

                @Override // java.lang.Runnable
                public void run() {
                    AsyncHttpClient.this.executeAffinity(r2, r3, r4, r5);
                }
            });
        }
    }

    /* renamed from: com.koushikdutta.async.http.AsyncHttpClient$1 */
    class RunnableC04791 implements Runnable {
        final /* synthetic */ HttpConnectCallback val$callback;
        final /* synthetic */ FutureAsyncHttpResponse val$cancel;
        final /* synthetic */ int val$redirectCount;
        final /* synthetic */ AsyncHttpRequest val$request;

        RunnableC04791(AsyncHttpRequest request2, int redirectCount2, FutureAsyncHttpResponse cancel2, HttpConnectCallback callback2) {
            r2 = request2;
            r3 = redirectCount2;
            r4 = cancel2;
            r5 = callback2;
        }

        @Override // java.lang.Runnable
        public void run() {
            AsyncHttpClient.this.executeAffinity(r2, r3, r4, r5);
        }
    }

    public static long getTimeoutRemaining(AsyncHttpRequest request) {
        return request.getTimeout();
    }

    public static void copyHeader(AsyncHttpRequest from, AsyncHttpRequest to, String header) {
        String value = from.getHeaders().get(header);
        if (!TextUtils.isEmpty(value)) {
            to.getHeaders().set(header, value);
        }
    }

    public void executeAffinity(AsyncHttpRequest request, int redirectCount, FutureAsyncHttpResponse cancel, HttpConnectCallback callback) {
        if (!$assertionsDisabled && !this.mServer.isAffinityThread()) {
            throw new AssertionError();
        }
        if (redirectCount > 15) {
            reportConnectedCompleted(cancel, new RedirectLimitExceededException("too many redirects"), null, request, callback);
            return;
        }
        request.getUri();
        AsyncHttpClientMiddleware.OnResponseCompleteDataOnRequestSentData data = new AsyncHttpClientMiddleware.OnResponseCompleteDataOnRequestSentData();
        request.executionTime = System.currentTimeMillis();
        data.request = request;
        request.logd("Executing request.");
        for (AsyncHttpClientMiddleware middleware : this.mMiddleware) {
            middleware.onRequest(data);
        }
        if (request.getTimeout() > 0) {
            cancel.timeoutRunnable = new Runnable() { // from class: com.koushikdutta.async.http.AsyncHttpClient.2
                final /* synthetic */ HttpConnectCallback val$callback;
                final /* synthetic */ FutureAsyncHttpResponse val$cancel;
                final /* synthetic */ AsyncHttpClientMiddleware.OnResponseCompleteDataOnRequestSentData val$data;
                final /* synthetic */ AsyncHttpRequest val$request;

                RunnableC04822(AsyncHttpClientMiddleware.OnResponseCompleteDataOnRequestSentData data2, FutureAsyncHttpResponse cancel2, AsyncHttpRequest request2, HttpConnectCallback callback2) {
                    r2 = data2;
                    r3 = cancel2;
                    r4 = request2;
                    r5 = callback2;
                }

                @Override // java.lang.Runnable
                public void run() {
                    if (r2.socketCancellable != null) {
                        r2.socketCancellable.cancel();
                        if (r2.socket != null) {
                            r2.socket.close();
                        }
                    }
                    AsyncHttpClient.this.reportConnectedCompleted(r3, new TimeoutException(), null, r4, r5);
                }
            };
            cancel2.scheduled = this.mServer.postDelayed(cancel2.timeoutRunnable, getTimeoutRemaining(request2));
        }
        data2.connectCallback = new ConnectCallback() { // from class: com.koushikdutta.async.http.AsyncHttpClient.3
            boolean reported;
            final /* synthetic */ HttpConnectCallback val$callback;
            final /* synthetic */ FutureAsyncHttpResponse val$cancel;
            final /* synthetic */ AsyncHttpClientMiddleware.OnResponseCompleteDataOnRequestSentData val$data;
            final /* synthetic */ int val$redirectCount;
            final /* synthetic */ AsyncHttpRequest val$request;

            C04833(AsyncHttpRequest request2, FutureAsyncHttpResponse cancel2, HttpConnectCallback callback2, AsyncHttpClientMiddleware.OnResponseCompleteDataOnRequestSentData data2, int redirectCount2) {
                r2 = request2;
                r3 = cancel2;
                r4 = callback2;
                r5 = data2;
                r6 = redirectCount2;
            }

            @Override // com.koushikdutta.async.callback.ConnectCallback
            public void onConnectCompleted(Exception ex, AsyncSocket socket) {
                if (this.reported && socket != null) {
                    socket.setDataCallback(new DataCallback.NullDataCallback());
                    socket.setEndCallback(new CompletedCallback.NullCompletedCallback());
                    socket.close();
                    throw new AssertionError("double connect callback");
                }
                this.reported = true;
                r2.logv("socket connected");
                if (r3.isCancelled()) {
                    if (socket != null) {
                        socket.close();
                        return;
                    }
                    return;
                }
                if (r3.timeoutRunnable != null) {
                    AsyncHttpClient.this.mServer.removeAllCallbacks(r3.scheduled);
                }
                if (ex != null) {
                    AsyncHttpClient.this.reportConnectedCompleted(r3, ex, null, r2, r4);
                    return;
                }
                r5.socket = socket;
                r3.socket = socket;
                AsyncHttpClient.this.executeSocket(r2, r6, r3, r4, r5);
            }
        };
        setupAndroidProxy(request2);
        if (request2.getBody() != null && request2.getHeaders().get("Content-Type") == null) {
            request2.getHeaders().set("Content-Type", request2.getBody().getContentType());
        }
        for (AsyncHttpClientMiddleware middleware2 : this.mMiddleware) {
            Cancellable socketCancellable = middleware2.getSocket(data2);
            if (socketCancellable != null) {
                data2.socketCancellable = socketCancellable;
                cancel2.setParent(socketCancellable);
                return;
            }
        }
        Exception unsupportedURI = new IllegalArgumentException("invalid uri=" + request2.getUri() + " middlewares=" + this.mMiddleware);
        reportConnectedCompleted(cancel2, unsupportedURI, null, request2, callback2);
    }

    /* renamed from: com.koushikdutta.async.http.AsyncHttpClient$2 */
    class RunnableC04822 implements Runnable {
        final /* synthetic */ HttpConnectCallback val$callback;
        final /* synthetic */ FutureAsyncHttpResponse val$cancel;
        final /* synthetic */ AsyncHttpClientMiddleware.OnResponseCompleteDataOnRequestSentData val$data;
        final /* synthetic */ AsyncHttpRequest val$request;

        RunnableC04822(AsyncHttpClientMiddleware.OnResponseCompleteDataOnRequestSentData data2, FutureAsyncHttpResponse cancel2, AsyncHttpRequest request2, HttpConnectCallback callback2) {
            r2 = data2;
            r3 = cancel2;
            r4 = request2;
            r5 = callback2;
        }

        @Override // java.lang.Runnable
        public void run() {
            if (r2.socketCancellable != null) {
                r2.socketCancellable.cancel();
                if (r2.socket != null) {
                    r2.socket.close();
                }
            }
            AsyncHttpClient.this.reportConnectedCompleted(r3, new TimeoutException(), null, r4, r5);
        }
    }

    /* renamed from: com.koushikdutta.async.http.AsyncHttpClient$3 */
    class C04833 implements ConnectCallback {
        boolean reported;
        final /* synthetic */ HttpConnectCallback val$callback;
        final /* synthetic */ FutureAsyncHttpResponse val$cancel;
        final /* synthetic */ AsyncHttpClientMiddleware.OnResponseCompleteDataOnRequestSentData val$data;
        final /* synthetic */ int val$redirectCount;
        final /* synthetic */ AsyncHttpRequest val$request;

        C04833(AsyncHttpRequest request2, FutureAsyncHttpResponse cancel2, HttpConnectCallback callback2, AsyncHttpClientMiddleware.OnResponseCompleteDataOnRequestSentData data2, int redirectCount2) {
            r2 = request2;
            r3 = cancel2;
            r4 = callback2;
            r5 = data2;
            r6 = redirectCount2;
        }

        @Override // com.koushikdutta.async.callback.ConnectCallback
        public void onConnectCompleted(Exception ex, AsyncSocket socket) {
            if (this.reported && socket != null) {
                socket.setDataCallback(new DataCallback.NullDataCallback());
                socket.setEndCallback(new CompletedCallback.NullCompletedCallback());
                socket.close();
                throw new AssertionError("double connect callback");
            }
            this.reported = true;
            r2.logv("socket connected");
            if (r3.isCancelled()) {
                if (socket != null) {
                    socket.close();
                    return;
                }
                return;
            }
            if (r3.timeoutRunnable != null) {
                AsyncHttpClient.this.mServer.removeAllCallbacks(r3.scheduled);
            }
            if (ex != null) {
                AsyncHttpClient.this.reportConnectedCompleted(r3, ex, null, r2, r4);
                return;
            }
            r5.socket = socket;
            r3.socket = socket;
            AsyncHttpClient.this.executeSocket(r2, r6, r3, r4, r5);
        }
    }

    /* renamed from: com.koushikdutta.async.http.AsyncHttpClient$4 */
    class C04844 extends AsyncHttpResponseImpl {
        final /* synthetic */ HttpConnectCallback val$callback;
        final /* synthetic */ FutureAsyncHttpResponse val$cancel;
        final /* synthetic */ AsyncHttpClientMiddleware.OnResponseCompleteDataOnRequestSentData val$data;
        final /* synthetic */ int val$redirectCount;
        final /* synthetic */ AsyncHttpRequest val$request;

        /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
        C04844(AsyncHttpRequest request, FutureAsyncHttpResponse futureAsyncHttpResponse, AsyncHttpRequest asyncHttpRequest, HttpConnectCallback httpConnectCallback, AsyncHttpClientMiddleware.OnResponseCompleteDataOnRequestSentData onResponseCompleteDataOnRequestSentData, int i) {
            super(request);
            r3 = futureAsyncHttpResponse;
            r4 = asyncHttpRequest;
            r5 = httpConnectCallback;
            r6 = onResponseCompleteDataOnRequestSentData;
            r7 = i;
        }

        @Override // com.koushikdutta.async.http.AsyncHttpResponseImpl
        protected void onRequestCompleted(Exception ex) {
            if (ex != null) {
                AsyncHttpClient.this.reportConnectedCompleted(r3, ex, null, r4, r5);
                return;
            }
            r4.logv("request completed");
            if (!r3.isCancelled()) {
                if (r3.timeoutRunnable != null && this.mHeaders == null) {
                    AsyncHttpClient.this.mServer.removeAllCallbacks(r3.scheduled);
                    r3.scheduled = AsyncHttpClient.this.mServer.postDelayed(r3.timeoutRunnable, AsyncHttpClient.getTimeoutRemaining(r4));
                }
                for (AsyncHttpClientMiddleware middleware : AsyncHttpClient.this.mMiddleware) {
                    middleware.onRequestSent(r6);
                }
            }
        }

        @Override // com.koushikdutta.async.FilteredDataEmitter, com.koushikdutta.async.DataTrackingEmitter
        public void setDataEmitter(DataEmitter emitter) {
            r6.bodyEmitter = emitter;
            for (AsyncHttpClientMiddleware middleware : AsyncHttpClient.this.mMiddleware) {
                middleware.onBodyDecoder(r6);
            }
            super.setDataEmitter(r6.bodyEmitter);
            Headers headers = this.mHeaders;
            int responseCode = code();
            if ((responseCode == 301 || responseCode == 302 || responseCode == 307) && r4.getFollowRedirect()) {
                String location = headers.get("Location");
                try {
                    Uri redirect = Uri.parse(location);
                    if (redirect.getScheme() == null) {
                        redirect = Uri.parse(new URL(new URL(r4.getUri().toString()), location).toString());
                    }
                    String method = r4.getMethod().equals(AsyncHttpHead.METHOD) ? AsyncHttpHead.METHOD : AsyncHttpGet.METHOD;
                    AsyncHttpRequest newReq = new AsyncHttpRequest(redirect, method);
                    newReq.executionTime = r4.executionTime;
                    newReq.logLevel = r4.logLevel;
                    newReq.LOGTAG = r4.LOGTAG;
                    newReq.proxyHost = r4.proxyHost;
                    newReq.proxyPort = r4.proxyPort;
                    AsyncHttpClient.setupAndroidProxy(newReq);
                    AsyncHttpClient.copyHeader(r4, newReq, "User-Agent");
                    AsyncHttpClient.copyHeader(r4, newReq, "Range");
                    r4.logi("Redirecting");
                    newReq.logi("Redirected");
                    AsyncHttpClient.this.execute(newReq, r7 + 1, r3, r5);
                    setDataCallback(new DataCallback.NullDataCallback());
                    return;
                } catch (Exception e) {
                    AsyncHttpClient.this.reportConnectedCompleted(r3, e, this, r4, r5);
                    return;
                }
            }
            r4.logv("Final (post cache response) headers:\n" + toString());
            AsyncHttpClient.this.reportConnectedCompleted(r3, null, this, r4, r5);
        }

        @Override // com.koushikdutta.async.http.AsyncHttpResponseImpl
        protected void onHeadersReceived() {
            super.onHeadersReceived();
            if (!r3.isCancelled()) {
                if (r3.timeoutRunnable != null) {
                    AsyncHttpClient.this.mServer.removeAllCallbacks(r3.scheduled);
                }
                r4.logv("Received headers:\n" + toString());
                for (AsyncHttpClientMiddleware middleware : AsyncHttpClient.this.mMiddleware) {
                    middleware.onHeadersReceived(r6);
                }
            }
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // com.koushikdutta.async.http.AsyncHttpResponseImpl, com.koushikdutta.async.DataEmitterBase
        public void report(Exception ex) {
            if (ex != null) {
                r4.loge("exception during response", ex);
            }
            if (!r3.isCancelled()) {
                if (ex instanceof AsyncSSLException) {
                    r4.loge("SSL Exception", ex);
                    AsyncSSLException ase = (AsyncSSLException) ex;
                    r4.onHandshakeException(ase);
                    if (ase.getIgnore()) {
                        return;
                    }
                }
                AsyncSocket socket = socket();
                if (socket != null) {
                    super.report(ex);
                    if ((!socket.isOpen() || ex != null) && headers() == null && ex != null) {
                        AsyncHttpClient.this.reportConnectedCompleted(r3, ex, null, r4, r5);
                    }
                    r6.exception = ex;
                    for (AsyncHttpClientMiddleware middleware : AsyncHttpClient.this.mMiddleware) {
                        middleware.onResponseComplete(r6);
                    }
                }
            }
        }

        @Override // com.koushikdutta.async.http.AsyncHttpResponse
        public AsyncSocket detachSocket() {
            r4.logd("Detaching socket");
            AsyncSocket socket = socket();
            if (socket == null) {
                return null;
            }
            socket.setWriteableCallback(null);
            socket.setClosedCallback(null);
            socket.setEndCallback(null);
            socket.setDataCallback(null);
            setSocket(null);
            return socket;
        }
    }

    public void executeSocket(AsyncHttpRequest request, int redirectCount, FutureAsyncHttpResponse cancel, HttpConnectCallback callback, AsyncHttpClientMiddleware.OnResponseCompleteDataOnRequestSentData data) {
        AsyncHttpResponseImpl ret = new AsyncHttpResponseImpl(request) { // from class: com.koushikdutta.async.http.AsyncHttpClient.4
            final /* synthetic */ HttpConnectCallback val$callback;
            final /* synthetic */ FutureAsyncHttpResponse val$cancel;
            final /* synthetic */ AsyncHttpClientMiddleware.OnResponseCompleteDataOnRequestSentData val$data;
            final /* synthetic */ int val$redirectCount;
            final /* synthetic */ AsyncHttpRequest val$request;

            /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
            C04844(AsyncHttpRequest request2, FutureAsyncHttpResponse cancel2, AsyncHttpRequest request22, HttpConnectCallback callback2, AsyncHttpClientMiddleware.OnResponseCompleteDataOnRequestSentData data2, int redirectCount2) {
                super(request22);
                r3 = cancel2;
                r4 = request22;
                r5 = callback2;
                r6 = data2;
                r7 = redirectCount2;
            }

            @Override // com.koushikdutta.async.http.AsyncHttpResponseImpl
            protected void onRequestCompleted(Exception ex) {
                if (ex != null) {
                    AsyncHttpClient.this.reportConnectedCompleted(r3, ex, null, r4, r5);
                    return;
                }
                r4.logv("request completed");
                if (!r3.isCancelled()) {
                    if (r3.timeoutRunnable != null && this.mHeaders == null) {
                        AsyncHttpClient.this.mServer.removeAllCallbacks(r3.scheduled);
                        r3.scheduled = AsyncHttpClient.this.mServer.postDelayed(r3.timeoutRunnable, AsyncHttpClient.getTimeoutRemaining(r4));
                    }
                    for (AsyncHttpClientMiddleware middleware : AsyncHttpClient.this.mMiddleware) {
                        middleware.onRequestSent(r6);
                    }
                }
            }

            @Override // com.koushikdutta.async.FilteredDataEmitter, com.koushikdutta.async.DataTrackingEmitter
            public void setDataEmitter(DataEmitter emitter) {
                r6.bodyEmitter = emitter;
                for (AsyncHttpClientMiddleware middleware : AsyncHttpClient.this.mMiddleware) {
                    middleware.onBodyDecoder(r6);
                }
                super.setDataEmitter(r6.bodyEmitter);
                Headers headers = this.mHeaders;
                int responseCode = code();
                if ((responseCode == 301 || responseCode == 302 || responseCode == 307) && r4.getFollowRedirect()) {
                    String location = headers.get("Location");
                    try {
                        Uri redirect = Uri.parse(location);
                        if (redirect.getScheme() == null) {
                            redirect = Uri.parse(new URL(new URL(r4.getUri().toString()), location).toString());
                        }
                        String method = r4.getMethod().equals(AsyncHttpHead.METHOD) ? AsyncHttpHead.METHOD : AsyncHttpGet.METHOD;
                        AsyncHttpRequest newReq = new AsyncHttpRequest(redirect, method);
                        newReq.executionTime = r4.executionTime;
                        newReq.logLevel = r4.logLevel;
                        newReq.LOGTAG = r4.LOGTAG;
                        newReq.proxyHost = r4.proxyHost;
                        newReq.proxyPort = r4.proxyPort;
                        AsyncHttpClient.setupAndroidProxy(newReq);
                        AsyncHttpClient.copyHeader(r4, newReq, "User-Agent");
                        AsyncHttpClient.copyHeader(r4, newReq, "Range");
                        r4.logi("Redirecting");
                        newReq.logi("Redirected");
                        AsyncHttpClient.this.execute(newReq, r7 + 1, r3, r5);
                        setDataCallback(new DataCallback.NullDataCallback());
                        return;
                    } catch (Exception e) {
                        AsyncHttpClient.this.reportConnectedCompleted(r3, e, this, r4, r5);
                        return;
                    }
                }
                r4.logv("Final (post cache response) headers:\n" + toString());
                AsyncHttpClient.this.reportConnectedCompleted(r3, null, this, r4, r5);
            }

            @Override // com.koushikdutta.async.http.AsyncHttpResponseImpl
            protected void onHeadersReceived() {
                super.onHeadersReceived();
                if (!r3.isCancelled()) {
                    if (r3.timeoutRunnable != null) {
                        AsyncHttpClient.this.mServer.removeAllCallbacks(r3.scheduled);
                    }
                    r4.logv("Received headers:\n" + toString());
                    for (AsyncHttpClientMiddleware middleware : AsyncHttpClient.this.mMiddleware) {
                        middleware.onHeadersReceived(r6);
                    }
                }
            }

            /* JADX INFO: Access modifiers changed from: protected */
            @Override // com.koushikdutta.async.http.AsyncHttpResponseImpl, com.koushikdutta.async.DataEmitterBase
            public void report(Exception ex) {
                if (ex != null) {
                    r4.loge("exception during response", ex);
                }
                if (!r3.isCancelled()) {
                    if (ex instanceof AsyncSSLException) {
                        r4.loge("SSL Exception", ex);
                        AsyncSSLException ase = (AsyncSSLException) ex;
                        r4.onHandshakeException(ase);
                        if (ase.getIgnore()) {
                            return;
                        }
                    }
                    AsyncSocket socket = socket();
                    if (socket != null) {
                        super.report(ex);
                        if ((!socket.isOpen() || ex != null) && headers() == null && ex != null) {
                            AsyncHttpClient.this.reportConnectedCompleted(r3, ex, null, r4, r5);
                        }
                        r6.exception = ex;
                        for (AsyncHttpClientMiddleware middleware : AsyncHttpClient.this.mMiddleware) {
                            middleware.onResponseComplete(r6);
                        }
                    }
                }
            }

            @Override // com.koushikdutta.async.http.AsyncHttpResponse
            public AsyncSocket detachSocket() {
                r4.logd("Detaching socket");
                AsyncSocket socket = socket();
                if (socket == null) {
                    return null;
                }
                socket.setWriteableCallback(null);
                socket.setClosedCallback(null);
                socket.setEndCallback(null);
                socket.setDataCallback(null);
                setSocket(null);
                return socket;
            }
        };
        data2.sendHeadersCallback = new CompletedCallback() { // from class: com.koushikdutta.async.http.AsyncHttpClient.5
            final /* synthetic */ AsyncHttpResponseImpl val$ret;

            C04855(AsyncHttpResponseImpl ret2) {
                r2 = ret2;
            }

            @Override // com.koushikdutta.async.callback.CompletedCallback
            public void onCompleted(Exception ex) {
                if (ex != null) {
                    r2.report(ex);
                } else {
                    r2.onHeadersSent();
                }
            }
        };
        data2.receiveHeadersCallback = new CompletedCallback() { // from class: com.koushikdutta.async.http.AsyncHttpClient.6
            final /* synthetic */ AsyncHttpResponseImpl val$ret;

            C04866(AsyncHttpResponseImpl ret2) {
                r2 = ret2;
            }

            @Override // com.koushikdutta.async.callback.CompletedCallback
            public void onCompleted(Exception ex) {
                if (ex != null) {
                    r2.report(ex);
                } else {
                    r2.onHeadersReceived();
                }
            }
        };
        data2.response = ret2;
        ret2.setSocket(data2.socket);
        for (AsyncHttpClientMiddleware middleware : this.mMiddleware) {
            if (middleware.exchangeHeaders(data2)) {
                return;
            }
        }
    }

    /* renamed from: com.koushikdutta.async.http.AsyncHttpClient$5 */
    class C04855 implements CompletedCallback {
        final /* synthetic */ AsyncHttpResponseImpl val$ret;

        C04855(AsyncHttpResponseImpl ret2) {
            r2 = ret2;
        }

        @Override // com.koushikdutta.async.callback.CompletedCallback
        public void onCompleted(Exception ex) {
            if (ex != null) {
                r2.report(ex);
            } else {
                r2.onHeadersSent();
            }
        }
    }

    /* renamed from: com.koushikdutta.async.http.AsyncHttpClient$6 */
    class C04866 implements CompletedCallback {
        final /* synthetic */ AsyncHttpResponseImpl val$ret;

        C04866(AsyncHttpResponseImpl ret2) {
            r2 = ret2;
        }

        @Override // com.koushikdutta.async.callback.CompletedCallback
        public void onCompleted(Exception ex) {
            if (ex != null) {
                r2.report(ex);
            } else {
                r2.onHeadersReceived();
            }
        }
    }

    public static abstract class RequestCallbackBase<T> implements RequestCallback<T> {
        @Override // com.koushikdutta.async.http.callback.RequestCallback
        public void onProgress(AsyncHttpResponse response, long downloaded, long total) {
        }

        @Override // com.koushikdutta.async.http.callback.RequestCallback
        public void onConnect(AsyncHttpResponse response) {
        }
    }

    public Future<ByteBufferList> executeByteBufferList(AsyncHttpRequest request, DownloadCallback callback) {
        return execute(request, new ByteBufferListParser(), callback);
    }

    public Future<String> executeString(AsyncHttpRequest req, StringCallback callback) {
        return execute(req, new StringParser(), callback);
    }

    public Future<JSONObject> executeJSONObject(AsyncHttpRequest req, JSONObjectCallback callback) {
        return execute(req, new JSONObjectParser(), callback);
    }

    public Future<JSONArray> executeJSONArray(AsyncHttpRequest req, JSONArrayCallback callback) {
        return execute(req, new JSONArrayParser(), callback);
    }

    public <T> void invokeWithAffinity(RequestCallback<T> callback, SimpleFuture<T> future, AsyncHttpResponse response, Exception e, T result) {
        boolean complete;
        if (e != null) {
            complete = future.setComplete(e);
        } else {
            complete = future.setComplete((SimpleFuture<T>) result);
        }
        if (complete && callback != null) {
            callback.onCompleted(e, response, result);
        }
    }

    /* renamed from: com.koushikdutta.async.http.AsyncHttpClient$7 */
    class RunnableC04877 implements Runnable {
        final /* synthetic */ RequestCallback val$callback;
        final /* synthetic */ Exception val$e;
        final /* synthetic */ SimpleFuture val$future;
        final /* synthetic */ AsyncHttpResponse val$response;
        final /* synthetic */ Object val$result;

        RunnableC04877(RequestCallback requestCallback, SimpleFuture simpleFuture, AsyncHttpResponse asyncHttpResponse, Exception exc, Object obj) {
            r2 = requestCallback;
            r3 = simpleFuture;
            r4 = asyncHttpResponse;
            r5 = exc;
            r6 = obj;
        }

        @Override // java.lang.Runnable
        public void run() {
            AsyncHttpClient.this.invokeWithAffinity(r2, r3, r4, r5, r6);
        }
    }

    public <T> void invoke(RequestCallback<T> callback, SimpleFuture<T> future, AsyncHttpResponse response, Exception e, T result) {
        Runnable runnable = new Runnable() { // from class: com.koushikdutta.async.http.AsyncHttpClient.7
            final /* synthetic */ RequestCallback val$callback;
            final /* synthetic */ Exception val$e;
            final /* synthetic */ SimpleFuture val$future;
            final /* synthetic */ AsyncHttpResponse val$response;
            final /* synthetic */ Object val$result;

            RunnableC04877(RequestCallback callback2, SimpleFuture future2, AsyncHttpResponse response2, Exception e2, Object result2) {
                r2 = callback2;
                r3 = future2;
                r4 = response2;
                r5 = e2;
                r6 = result2;
            }

            @Override // java.lang.Runnable
            public void run() {
                AsyncHttpClient.this.invokeWithAffinity(r2, r3, r4, r5, r6);
            }
        };
        this.mServer.post(runnable);
    }

    public void invokeProgress(RequestCallback callback, AsyncHttpResponse response, long downloaded, long total) {
        if (callback != null) {
            callback.onProgress(response, downloaded, total);
        }
    }

    public void invokeConnect(RequestCallback callback, AsyncHttpResponse response) {
        if (callback != null) {
            callback.onConnect(response);
        }
    }

    /* JADX WARN: Generic types in debug info not equals: java.lang.Object != com.koushikdutta.async.future.SimpleFuture<java.io.File> */
    public Future<File> executeFile(AsyncHttpRequest req, String filename, FileCallback callback) {
        File file = new File(filename);
        file.getParentFile().mkdirs();
        try {
            OutputStream fout = new BufferedOutputStream(new FileOutputStream(file), 8192);
            FutureAsyncHttpResponse cancel = new FutureAsyncHttpResponse();
            SimpleFuture<File> ret = new SimpleFuture<File>() { // from class: com.koushikdutta.async.http.AsyncHttpClient.8
                final /* synthetic */ FutureAsyncHttpResponse val$cancel;
                final /* synthetic */ File val$file;
                final /* synthetic */ OutputStream val$fout;

                C04888(FutureAsyncHttpResponse cancel2, OutputStream fout2, File file2) {
                    r2 = cancel2;
                    r3 = fout2;
                    r4 = file2;
                }

                @Override // com.koushikdutta.async.future.SimpleCancellable
                public void cancelCleanup() {
                    try {
                        r2.get().setDataCallback(new DataCallback.NullDataCallback());
                        r2.get().close();
                    } catch (Exception e) {
                    }
                    try {
                        r3.close();
                    } catch (Exception e2) {
                    }
                    r4.delete();
                }
            };
            ret.setParent((Cancellable) cancel2);
            execute(req, 0, cancel2, new C04899(fout2, file2, callback, ret));
            return ret;
        } catch (FileNotFoundException e) {
            SimpleFuture<File> ret2 = new SimpleFuture<>();
            ret2.setComplete(e);
            return ret2;
        }
    }

    /* renamed from: com.koushikdutta.async.http.AsyncHttpClient$8 */
    class C04888 extends SimpleFuture<File> {
        final /* synthetic */ FutureAsyncHttpResponse val$cancel;
        final /* synthetic */ File val$file;
        final /* synthetic */ OutputStream val$fout;

        C04888(FutureAsyncHttpResponse cancel2, OutputStream fout2, File file2) {
            r2 = cancel2;
            r3 = fout2;
            r4 = file2;
        }

        @Override // com.koushikdutta.async.future.SimpleCancellable
        public void cancelCleanup() {
            try {
                r2.get().setDataCallback(new DataCallback.NullDataCallback());
                r2.get().close();
            } catch (Exception e) {
            }
            try {
                r3.close();
            } catch (Exception e2) {
            }
            r4.delete();
        }
    }

    /* renamed from: com.koushikdutta.async.http.AsyncHttpClient$9 */
    class C04899 implements HttpConnectCallback {
        long mDownloaded = 0;
        final /* synthetic */ FileCallback val$callback;
        final /* synthetic */ File val$file;
        final /* synthetic */ OutputStream val$fout;
        final /* synthetic */ SimpleFuture val$ret;

        C04899(OutputStream outputStream, File file, FileCallback fileCallback, SimpleFuture simpleFuture) {
            this.val$fout = outputStream;
            this.val$file = file;
            this.val$callback = fileCallback;
            this.val$ret = simpleFuture;
        }

        @Override // com.koushikdutta.async.http.callback.HttpConnectCallback
        public void onConnectCompleted(Exception ex, AsyncHttpResponse response) {
            if (ex == null) {
                AsyncHttpClient.this.invokeConnect(this.val$callback, response);
                long contentLength = HttpUtil.contentLength(response.headers());
                response.setDataCallback(new OutputStreamDataCallback(this.val$fout) { // from class: com.koushikdutta.async.http.AsyncHttpClient.9.1
                    final /* synthetic */ long val$contentLength;
                    final /* synthetic */ AsyncHttpResponse val$response;

                    /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
                    AnonymousClass1(OutputStream os, AsyncHttpResponse response2, long contentLength2) {
                        super(os);
                        r3 = response2;
                        r4 = contentLength2;
                    }

                    @Override // com.koushikdutta.async.stream.OutputStreamDataCallback, com.koushikdutta.async.callback.DataCallback
                    public void onDataAvailable(DataEmitter emitter, ByteBufferList bb) {
                        C04899.this.mDownloaded += bb.remaining();
                        super.onDataAvailable(emitter, bb);
                        AsyncHttpClient.this.invokeProgress(C04899.this.val$callback, r3, C04899.this.mDownloaded, r4);
                    }
                });
                response2.setEndCallback(new CompletedCallback() { // from class: com.koushikdutta.async.http.AsyncHttpClient.9.2
                    final /* synthetic */ AsyncHttpResponse val$response;

                    AnonymousClass2(AsyncHttpResponse response2) {
                        r2 = response2;
                    }

                    @Override // com.koushikdutta.async.callback.CompletedCallback
                    public void onCompleted(Exception ex2) {
                        try {
                            C04899.this.val$fout.close();
                        } catch (IOException e) {
                            ex2 = e;
                        }
                        if (ex2 == null) {
                            AsyncHttpClient.this.invoke(C04899.this.val$callback, C04899.this.val$ret, r2, null, C04899.this.val$file);
                        } else {
                            C04899.this.val$file.delete();
                            AsyncHttpClient.this.invoke(C04899.this.val$callback, C04899.this.val$ret, r2, ex2, null);
                        }
                    }
                });
                return;
            }
            try {
                this.val$fout.close();
            } catch (IOException e) {
            }
            this.val$file.delete();
            AsyncHttpClient.this.invoke(this.val$callback, this.val$ret, response2, ex, null);
        }

        /* renamed from: com.koushikdutta.async.http.AsyncHttpClient$9$1 */
        class AnonymousClass1 extends OutputStreamDataCallback {
            final /* synthetic */ long val$contentLength;
            final /* synthetic */ AsyncHttpResponse val$response;

            /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
            AnonymousClass1(OutputStream os, AsyncHttpResponse response2, long contentLength2) {
                super(os);
                r3 = response2;
                r4 = contentLength2;
            }

            @Override // com.koushikdutta.async.stream.OutputStreamDataCallback, com.koushikdutta.async.callback.DataCallback
            public void onDataAvailable(DataEmitter emitter, ByteBufferList bb) {
                C04899.this.mDownloaded += bb.remaining();
                super.onDataAvailable(emitter, bb);
                AsyncHttpClient.this.invokeProgress(C04899.this.val$callback, r3, C04899.this.mDownloaded, r4);
            }
        }

        /* renamed from: com.koushikdutta.async.http.AsyncHttpClient$9$2 */
        class AnonymousClass2 implements CompletedCallback {
            final /* synthetic */ AsyncHttpResponse val$response;

            AnonymousClass2(AsyncHttpResponse response2) {
                r2 = response2;
            }

            @Override // com.koushikdutta.async.callback.CompletedCallback
            public void onCompleted(Exception ex2) {
                try {
                    C04899.this.val$fout.close();
                } catch (IOException e) {
                    ex2 = e;
                }
                if (ex2 == null) {
                    AsyncHttpClient.this.invoke(C04899.this.val$callback, C04899.this.val$ret, r2, null, C04899.this.val$file);
                } else {
                    C04899.this.val$file.delete();
                    AsyncHttpClient.this.invoke(C04899.this.val$callback, C04899.this.val$ret, r2, ex2, null);
                }
            }
        }
    }

    public <T> SimpleFuture<T> execute(AsyncHttpRequest req, AsyncParser<T> parser, RequestCallback<T> callback) {
        FutureAsyncHttpResponse cancel = new FutureAsyncHttpResponse();
        SimpleFuture<T> ret = new SimpleFuture<>();
        execute(req, 0, cancel, new HttpConnectCallback() { // from class: com.koushikdutta.async.http.AsyncHttpClient.10
            final /* synthetic */ RequestCallback val$callback;
            final /* synthetic */ AsyncParser val$parser;
            final /* synthetic */ SimpleFuture val$ret;

            C048010(RequestCallback callback2, SimpleFuture ret2, AsyncParser parser2) {
                r2 = callback2;
                r3 = ret2;
                r4 = parser2;
            }

            @Override // com.koushikdutta.async.http.callback.HttpConnectCallback
            public void onConnectCompleted(Exception ex, AsyncHttpResponse response) {
                if (ex != null) {
                    AsyncHttpClient.this.invoke(r2, r3, response, ex, null);
                    return;
                }
                AsyncHttpClient.this.invokeConnect(r2, response);
                r3.setParent((Cancellable) r4.parse(response).setCallback(new FutureCallback<T>() { // from class: com.koushikdutta.async.http.AsyncHttpClient.10.1
                    final /* synthetic */ AsyncHttpResponse val$response;

                    AnonymousClass1(AsyncHttpResponse response2) {
                        r2 = response2;
                    }

                    @Override // com.koushikdutta.async.future.FutureCallback
                    public void onCompleted(Exception e, T result) {
                        AsyncHttpClient.this.invoke(r2, r3, r2, e, result);
                    }
                }));
            }

            /* renamed from: com.koushikdutta.async.http.AsyncHttpClient$10$1 */
            class AnonymousClass1<T> implements FutureCallback<T> {
                final /* synthetic */ AsyncHttpResponse val$response;

                AnonymousClass1(AsyncHttpResponse response2) {
                    r2 = response2;
                }

                @Override // com.koushikdutta.async.future.FutureCallback
                public void onCompleted(Exception e, T result) {
                    AsyncHttpClient.this.invoke(r2, r3, r2, e, result);
                }
            }
        });
        ret2.setParent((Cancellable) cancel);
        return ret2;
    }

    /* renamed from: com.koushikdutta.async.http.AsyncHttpClient$10 */
    class C048010 implements HttpConnectCallback {
        final /* synthetic */ RequestCallback val$callback;
        final /* synthetic */ AsyncParser val$parser;
        final /* synthetic */ SimpleFuture val$ret;

        C048010(RequestCallback callback2, SimpleFuture ret2, AsyncParser parser2) {
            r2 = callback2;
            r3 = ret2;
            r4 = parser2;
        }

        @Override // com.koushikdutta.async.http.callback.HttpConnectCallback
        public void onConnectCompleted(Exception ex, AsyncHttpResponse response2) {
            if (ex != null) {
                AsyncHttpClient.this.invoke(r2, r3, response2, ex, null);
                return;
            }
            AsyncHttpClient.this.invokeConnect(r2, response2);
            r3.setParent((Cancellable) r4.parse(response2).setCallback(new FutureCallback<T>() { // from class: com.koushikdutta.async.http.AsyncHttpClient.10.1
                final /* synthetic */ AsyncHttpResponse val$response;

                AnonymousClass1(AsyncHttpResponse response22) {
                    r2 = response22;
                }

                @Override // com.koushikdutta.async.future.FutureCallback
                public void onCompleted(Exception e, T result) {
                    AsyncHttpClient.this.invoke(r2, r3, r2, e, result);
                }
            }));
        }

        /* renamed from: com.koushikdutta.async.http.AsyncHttpClient$10$1 */
        class AnonymousClass1<T> implements FutureCallback<T> {
            final /* synthetic */ AsyncHttpResponse val$response;

            AnonymousClass1(AsyncHttpResponse response22) {
                r2 = response22;
            }

            @Override // com.koushikdutta.async.future.FutureCallback
            public void onCompleted(Exception e, T result) {
                AsyncHttpClient.this.invoke(r2, r3, r2, e, result);
            }
        }
    }

    public Future<WebSocket> websocket(AsyncHttpRequest req, String protocol, WebSocketConnectCallback callback) {
        WebSocketImpl.addWebSocketUpgradeHeaders(req, protocol);
        SimpleFuture<WebSocket> ret = new SimpleFuture<>();
        Cancellable connect = execute(req, new HttpConnectCallback() { // from class: com.koushikdutta.async.http.AsyncHttpClient.11
            final /* synthetic */ WebSocketConnectCallback val$callback;
            final /* synthetic */ AsyncHttpRequest val$req;
            final /* synthetic */ SimpleFuture val$ret;

            C048111(SimpleFuture ret2, WebSocketConnectCallback callback2, AsyncHttpRequest req2) {
                r2 = ret2;
                r3 = callback2;
                r4 = req2;
            }

            @Override // com.koushikdutta.async.http.callback.HttpConnectCallback
            public void onConnectCompleted(Exception ex, AsyncHttpResponse response) {
                if (ex != null) {
                    if (r2.setComplete(ex) && r3 != null) {
                        r3.onCompleted(ex, null);
                        return;
                    }
                    return;
                }
                WebSocket ws = WebSocketImpl.finishHandshake(r4.getHeaders(), response);
                if (ws == null) {
                    ex = new WebSocketHandshakeException("Unable to complete websocket handshake");
                    if (!r2.setComplete(ex)) {
                        return;
                    }
                } else if (!r2.setComplete((SimpleFuture) ws)) {
                    return;
                }
                if (r3 != null) {
                    r3.onCompleted(ex, ws);
                }
            }
        });
        ret2.setParent(connect);
        return ret2;
    }

    /* renamed from: com.koushikdutta.async.http.AsyncHttpClient$11 */
    class C048111 implements HttpConnectCallback {
        final /* synthetic */ WebSocketConnectCallback val$callback;
        final /* synthetic */ AsyncHttpRequest val$req;
        final /* synthetic */ SimpleFuture val$ret;

        C048111(SimpleFuture ret2, WebSocketConnectCallback callback2, AsyncHttpRequest req2) {
            r2 = ret2;
            r3 = callback2;
            r4 = req2;
        }

        @Override // com.koushikdutta.async.http.callback.HttpConnectCallback
        public void onConnectCompleted(Exception ex, AsyncHttpResponse response) {
            if (ex != null) {
                if (r2.setComplete(ex) && r3 != null) {
                    r3.onCompleted(ex, null);
                    return;
                }
                return;
            }
            WebSocket ws = WebSocketImpl.finishHandshake(r4.getHeaders(), response);
            if (ws == null) {
                ex = new WebSocketHandshakeException("Unable to complete websocket handshake");
                if (!r2.setComplete(ex)) {
                    return;
                }
            } else if (!r2.setComplete((SimpleFuture) ws)) {
                return;
            }
            if (r3 != null) {
                r3.onCompleted(ex, ws);
            }
        }
    }

    public Future<WebSocket> websocket(String uri, String protocol, WebSocketConnectCallback callback) {
        AsyncHttpGet get = new AsyncHttpGet(uri.replace("ws://", "http://").replace("wss://", "https://"));
        return websocket(get, protocol, callback);
    }

    public AsyncServer getServer() {
        return this.mServer;
    }
}
