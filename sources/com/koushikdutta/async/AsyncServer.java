package com.koushikdutta.async;

import android.os.Build;
import android.os.Handler;
import android.util.Log;
import com.koushikdutta.async.callback.CompletedCallback;
import com.koushikdutta.async.callback.ConnectCallback;
import com.koushikdutta.async.callback.ListenCallback;
import com.koushikdutta.async.future.Cancellable;
import com.koushikdutta.async.future.Future;
import com.koushikdutta.async.future.FutureCallback;
import com.koushikdutta.async.future.SimpleFuture;
import com.koushikdutta.async.future.TransformFuture;
import com.koushikdutta.async.util.StreamUtility;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/* loaded from: classes.dex */
public class AsyncServer {
    static final /* synthetic */ boolean $assertionsDisabled;
    public static final String LOGTAG = "NIO";
    private static final long QUEUE_EMPTY = Long.MAX_VALUE;
    private static final Comparator<InetAddress> ipSorter;
    static AsyncServer mInstance;
    static final WeakHashMap<Thread, AsyncServer> mServers;
    private static ExecutorService synchronousResolverWorkers;
    private static ExecutorService synchronousWorkers;
    Thread mAffinity;
    String mName;
    PriorityQueue<Scheduled> mQueue;
    private SelectorWrapper mSelector;
    int postCounter;

    static {
        $assertionsDisabled = !AsyncServer.class.desiredAssertionStatus();
        try {
            if (Build.VERSION.SDK_INT <= 8) {
                System.setProperty("java.net.preferIPv4Stack", "true");
                System.setProperty("java.net.preferIPv6Addresses", "false");
            }
        } catch (Throwable th) {
        }
        mInstance = new AsyncServer();
        synchronousWorkers = newSynchronousWorkers("AsyncServer-worker-");
        ipSorter = new Comparator<InetAddress>() { // from class: com.koushikdutta.async.AsyncServer.8
            C04428() {
            }

            @Override // java.util.Comparator
            public int compare(InetAddress lhs, InetAddress rhs) {
                if ((lhs instanceof Inet4Address) && (rhs instanceof Inet4Address)) {
                    return 0;
                }
                if ((lhs instanceof Inet6Address) && (rhs instanceof Inet6Address)) {
                    return 0;
                }
                if ((lhs instanceof Inet4Address) && (rhs instanceof Inet6Address)) {
                    return -1;
                }
                return 1;
            }
        };
        synchronousResolverWorkers = newSynchronousWorkers("AsyncServer-resolver-");
        mServers = new WeakHashMap<>();
    }

    private static class RunnableWrapper implements Runnable {
        Handler handler;
        boolean hasRun;
        Runnable runnable;
        ThreadQueue threadQueue;

        private RunnableWrapper() {
        }

        /* synthetic */ RunnableWrapper(RunnableC04291 x0) {
            this();
        }

        @Override // java.lang.Runnable
        public void run() {
            synchronized (this) {
                if (!this.hasRun) {
                    this.hasRun = true;
                    try {
                        this.runnable.run();
                    } finally {
                        this.threadQueue.remove(this);
                        this.handler.removeCallbacks(this);
                        this.threadQueue = null;
                        this.handler = null;
                        this.runnable = null;
                    }
                }
            }
        }
    }

    public static void post(Handler handler, Runnable runnable) {
        RunnableWrapper wrapper = new RunnableWrapper();
        ThreadQueue threadQueue = ThreadQueue.getOrCreateThreadQueue(handler.getLooper().getThread());
        wrapper.threadQueue = threadQueue;
        wrapper.handler = handler;
        wrapper.runnable = runnable;
        threadQueue.add((Runnable) wrapper);
        handler.post(wrapper);
        threadQueue.queueSemaphore.release();
    }

    public static AsyncServer getDefault() {
        return mInstance;
    }

    public boolean isRunning() {
        return this.mSelector != null;
    }

    public AsyncServer() {
        this(null);
    }

    public AsyncServer(String name) {
        this.postCounter = 0;
        this.mQueue = new PriorityQueue<>(1, Scheduler.INSTANCE);
        this.mName = name == null ? "AsyncServer" : name;
    }

    public void handleSocket(AsyncNetworkSocket handler) throws ClosedChannelException {
        ChannelWrapper sc = handler.getChannel();
        SelectionKey ckey = sc.register(this.mSelector.getSelector());
        ckey.attach(handler);
        handler.setup(this, ckey);
    }

    public void removeAllCallbacks(Object scheduled) {
        synchronized (this) {
            this.mQueue.remove(scheduled);
        }
    }

    /* renamed from: com.koushikdutta.async.AsyncServer$1 */
    static class RunnableC04291 implements Runnable {
        RunnableC04291() {
        }

        @Override // java.lang.Runnable
        public void run() {
            try {
                SelectorWrapper.this.wakeupOnce();
            } catch (Exception e) {
                Log.i(AsyncServer.LOGTAG, "Selector Exception? L Preview?");
            }
        }
    }

    private static void wakeup(SelectorWrapper selector) {
        synchronousWorkers.execute(new Runnable() { // from class: com.koushikdutta.async.AsyncServer.1
            RunnableC04291() {
            }

            @Override // java.lang.Runnable
            public void run() {
                try {
                    SelectorWrapper.this.wakeupOnce();
                } catch (Exception e) {
                    Log.i(AsyncServer.LOGTAG, "Selector Exception? L Preview?");
                }
            }
        });
    }

    public Object postDelayed(Runnable runnable, long delay) {
        long time;
        Scheduled s;
        synchronized (this) {
            if (delay > 0) {
                time = System.currentTimeMillis() + delay;
            } else if (delay == 0) {
                int i = this.postCounter;
                this.postCounter = i + 1;
                time = i;
            } else if (this.mQueue.size() > 0) {
                time = Math.min(0L, this.mQueue.peek().time - 1);
            } else {
                time = 0;
            }
            PriorityQueue<Scheduled> priorityQueue = this.mQueue;
            s = new Scheduled(runnable, time);
            priorityQueue.add(s);
            if (this.mSelector == null) {
                run(true);
            }
            if (!isAffinityThread()) {
                wakeup(this.mSelector);
            }
        }
        return s;
    }

    public Object postImmediate(Runnable runnable) {
        if (Thread.currentThread() != getAffinity()) {
            return postDelayed(runnable, -1L);
        }
        runnable.run();
        return null;
    }

    public Object post(Runnable runnable) {
        return postDelayed(runnable, 0L);
    }

    /* renamed from: com.koushikdutta.async.AsyncServer$2 */
    class RunnableC04362 implements Runnable {
        final /* synthetic */ CompletedCallback val$callback;
        final /* synthetic */ Exception val$e;

        RunnableC04362(CompletedCallback completedCallback, Exception exc) {
            r2 = completedCallback;
            r3 = exc;
        }

        @Override // java.lang.Runnable
        public void run() {
            r2.onCompleted(r3);
        }
    }

    public Object post(CompletedCallback callback, Exception e) {
        return post(new Runnable() { // from class: com.koushikdutta.async.AsyncServer.2
            final /* synthetic */ CompletedCallback val$callback;
            final /* synthetic */ Exception val$e;

            RunnableC04362(CompletedCallback callback2, Exception e2) {
                r2 = callback2;
                r3 = e2;
            }

            @Override // java.lang.Runnable
            public void run() {
                r2.onCompleted(r3);
            }
        });
    }

    public void run(Runnable runnable) {
        if (Thread.currentThread() == this.mAffinity) {
            post(runnable);
            lockAndRunQueue(this, this.mQueue);
            return;
        }
        Semaphore semaphore = new Semaphore(0);
        post(new Runnable() { // from class: com.koushikdutta.async.AsyncServer.3
            final /* synthetic */ Runnable val$runnable;
            final /* synthetic */ Semaphore val$semaphore;

            RunnableC04373(Runnable runnable2, Semaphore semaphore2) {
                r2 = runnable2;
                r3 = semaphore2;
            }

            @Override // java.lang.Runnable
            public void run() {
                r2.run();
                r3.release();
            }
        });
        try {
            semaphore2.acquire();
        } catch (InterruptedException e) {
            Log.e(LOGTAG, "run", e);
        }
    }

    /* renamed from: com.koushikdutta.async.AsyncServer$3 */
    class RunnableC04373 implements Runnable {
        final /* synthetic */ Runnable val$runnable;
        final /* synthetic */ Semaphore val$semaphore;

        RunnableC04373(Runnable runnable2, Semaphore semaphore2) {
            r2 = runnable2;
            r3 = semaphore2;
        }

        @Override // java.lang.Runnable
        public void run() {
            r2.run();
            r3.release();
        }
    }

    private static class Scheduled {
        public Runnable runnable;
        public long time;

        public Scheduled(Runnable runnable, long time) {
            this.runnable = runnable;
            this.time = time;
        }
    }

    static class Scheduler implements Comparator<Scheduled> {
        public static Scheduler INSTANCE = new Scheduler();

        private Scheduler() {
        }

        @Override // java.util.Comparator
        public int compare(Scheduled s1, Scheduled s2) {
            if (s1.time == s2.time) {
                return 0;
            }
            if (s1.time > s2.time) {
                return 1;
            }
            return -1;
        }
    }

    public void stop() {
        synchronized (this) {
            boolean isAffinityThread = isAffinityThread();
            SelectorWrapper currentSelector = this.mSelector;
            if (currentSelector != null) {
                synchronized (mServers) {
                    mServers.remove(this.mAffinity);
                }
                Semaphore semaphore = new Semaphore(0);
                this.mQueue.add(new Scheduled(new Runnable() { // from class: com.koushikdutta.async.AsyncServer.4
                    final /* synthetic */ SelectorWrapper val$currentSelector;
                    final /* synthetic */ Semaphore val$semaphore;

                    RunnableC04384(SelectorWrapper currentSelector2, Semaphore semaphore2) {
                        r2 = currentSelector2;
                        r3 = semaphore2;
                    }

                    @Override // java.lang.Runnable
                    public void run() {
                        AsyncServer.shutdownEverything(r2);
                        r3.release();
                    }
                }, 0L));
                currentSelector2.wakeupOnce();
                shutdownKeys(currentSelector2);
                this.mQueue = new PriorityQueue<>(1, Scheduler.INSTANCE);
                this.mSelector = null;
                this.mAffinity = null;
                if (!isAffinityThread) {
                    try {
                        semaphore2.acquire();
                    } catch (Exception e) {
                    }
                }
            }
        }
    }

    /* renamed from: com.koushikdutta.async.AsyncServer$4 */
    class RunnableC04384 implements Runnable {
        final /* synthetic */ SelectorWrapper val$currentSelector;
        final /* synthetic */ Semaphore val$semaphore;

        RunnableC04384(SelectorWrapper currentSelector2, Semaphore semaphore2) {
            r2 = currentSelector2;
            r3 = semaphore2;
        }

        @Override // java.lang.Runnable
        public void run() {
            AsyncServer.shutdownEverything(r2);
            r3.release();
        }
    }

    protected void onDataReceived(int transmitted) {
    }

    protected void onDataSent(int transmitted) {
    }

    private static class ObjectHolder<T> {
        T held;

        private ObjectHolder() {
        }

        /* synthetic */ ObjectHolder(RunnableC04291 x0) {
            this();
        }
    }

    /* renamed from: com.koushikdutta.async.AsyncServer$5 */
    class RunnableC04395 implements Runnable {
        final /* synthetic */ ListenCallback val$handler;
        final /* synthetic */ ObjectHolder val$holder;
        final /* synthetic */ InetAddress val$host;
        final /* synthetic */ int val$port;

        RunnableC04395(InetAddress inetAddress, int i, ListenCallback listenCallback, ObjectHolder objectHolder) {
            r2 = inetAddress;
            r3 = i;
            r4 = listenCallback;
            r5 = objectHolder;
        }

        /* JADX WARN: Type inference failed for: r8v11, types: [T, com.koushikdutta.async.AsyncServer$5$1] */
        @Override // java.lang.Runnable
        public void run() {
            ServerSocketChannelWrapper closeableWrapper;
            InetSocketAddress isa;
            ServerSocketChannel closeableServer = null;
            ServerSocketChannelWrapper closeableWrapper2 = null;
            try {
                closeableServer = ServerSocketChannel.open();
                closeableWrapper = new ServerSocketChannelWrapper(closeableServer);
            } catch (IOException e) {
                e = e;
            }
            try {
                if (r2 == null) {
                    isa = new InetSocketAddress(r3);
                } else {
                    isa = new InetSocketAddress(r2, r3);
                }
                closeableServer.socket().bind(isa);
                SelectionKey key = closeableWrapper.register(AsyncServer.this.mSelector.getSelector());
                key.attach(r4);
                ListenCallback listenCallback = r4;
                ObjectHolder objectHolder = r5;
                ?? anonymousClass1 = new AsyncServerSocket() { // from class: com.koushikdutta.async.AsyncServer.5.1
                    final /* synthetic */ SelectionKey val$key;
                    final /* synthetic */ ServerSocketChannel val$server;
                    final /* synthetic */ ServerSocketChannelWrapper val$wrapper;

                    AnonymousClass1(ServerSocketChannel closeableServer2, ServerSocketChannelWrapper closeableWrapper3, SelectionKey key2) {
                        r2 = closeableServer2;
                        r3 = closeableWrapper3;
                        r4 = key2;
                    }

                    @Override // com.koushikdutta.async.AsyncServerSocket
                    public int getLocalPort() {
                        return r2.socket().getLocalPort();
                    }

                    @Override // com.koushikdutta.async.AsyncServerSocket
                    public void stop() {
                        StreamUtility.closeQuietly(r3);
                        try {
                            r4.cancel();
                        } catch (Exception e2) {
                        }
                    }
                };
                objectHolder.held = anonymousClass1;
                listenCallback.onListening((AsyncServerSocket) anonymousClass1);
            } catch (IOException e2) {
                e = e2;
                closeableWrapper2 = closeableWrapper3;
                Log.e(AsyncServer.LOGTAG, "wtf", e);
                StreamUtility.closeQuietly(closeableWrapper2, closeableServer2);
                r4.onCompleted(e);
            }
        }

        /* renamed from: com.koushikdutta.async.AsyncServer$5$1 */
        class AnonymousClass1 implements AsyncServerSocket {
            final /* synthetic */ SelectionKey val$key;
            final /* synthetic */ ServerSocketChannel val$server;
            final /* synthetic */ ServerSocketChannelWrapper val$wrapper;

            AnonymousClass1(ServerSocketChannel closeableServer2, ServerSocketChannelWrapper closeableWrapper3, SelectionKey key2) {
                r2 = closeableServer2;
                r3 = closeableWrapper3;
                r4 = key2;
            }

            @Override // com.koushikdutta.async.AsyncServerSocket
            public int getLocalPort() {
                return r2.socket().getLocalPort();
            }

            @Override // com.koushikdutta.async.AsyncServerSocket
            public void stop() {
                StreamUtility.closeQuietly(r3);
                try {
                    r4.cancel();
                } catch (Exception e2) {
                }
            }
        }
    }

    public AsyncServerSocket listen(InetAddress host, int port, ListenCallback handler) {
        ObjectHolder<AsyncServerSocket> holder = new ObjectHolder<>();
        run(new Runnable() { // from class: com.koushikdutta.async.AsyncServer.5
            final /* synthetic */ ListenCallback val$handler;
            final /* synthetic */ ObjectHolder val$holder;
            final /* synthetic */ InetAddress val$host;
            final /* synthetic */ int val$port;

            RunnableC04395(InetAddress host2, int port2, ListenCallback handler2, ObjectHolder holder2) {
                r2 = host2;
                r3 = port2;
                r4 = handler2;
                r5 = holder2;
            }

            /* JADX WARN: Type inference failed for: r8v11, types: [T, com.koushikdutta.async.AsyncServer$5$1] */
            @Override // java.lang.Runnable
            public void run() {
                ServerSocketChannelWrapper closeableWrapper3;
                InetSocketAddress isa;
                ServerSocketChannel closeableServer2 = null;
                ServerSocketChannelWrapper closeableWrapper2 = null;
                try {
                    closeableServer2 = ServerSocketChannel.open();
                    closeableWrapper3 = new ServerSocketChannelWrapper(closeableServer2);
                } catch (IOException e) {
                    e = e;
                }
                try {
                    if (r2 == null) {
                        isa = new InetSocketAddress(r3);
                    } else {
                        isa = new InetSocketAddress(r2, r3);
                    }
                    closeableServer2.socket().bind(isa);
                    SelectionKey key2 = closeableWrapper3.register(AsyncServer.this.mSelector.getSelector());
                    key2.attach(r4);
                    ListenCallback listenCallback = r4;
                    ObjectHolder objectHolder = r5;
                    ?? anonymousClass1 = new AsyncServerSocket() { // from class: com.koushikdutta.async.AsyncServer.5.1
                        final /* synthetic */ SelectionKey val$key;
                        final /* synthetic */ ServerSocketChannel val$server;
                        final /* synthetic */ ServerSocketChannelWrapper val$wrapper;

                        AnonymousClass1(ServerSocketChannel closeableServer22, ServerSocketChannelWrapper closeableWrapper32, SelectionKey key22) {
                            r2 = closeableServer22;
                            r3 = closeableWrapper32;
                            r4 = key22;
                        }

                        @Override // com.koushikdutta.async.AsyncServerSocket
                        public int getLocalPort() {
                            return r2.socket().getLocalPort();
                        }

                        @Override // com.koushikdutta.async.AsyncServerSocket
                        public void stop() {
                            StreamUtility.closeQuietly(r3);
                            try {
                                r4.cancel();
                            } catch (Exception e2) {
                            }
                        }
                    };
                    objectHolder.held = anonymousClass1;
                    listenCallback.onListening((AsyncServerSocket) anonymousClass1);
                } catch (IOException e2) {
                    e = e2;
                    closeableWrapper2 = closeableWrapper32;
                    Log.e(AsyncServer.LOGTAG, "wtf", e);
                    StreamUtility.closeQuietly(closeableWrapper2, closeableServer22);
                    r4.onCompleted(e);
                }
            }

            /* renamed from: com.koushikdutta.async.AsyncServer$5$1 */
            class AnonymousClass1 implements AsyncServerSocket {
                final /* synthetic */ SelectionKey val$key;
                final /* synthetic */ ServerSocketChannel val$server;
                final /* synthetic */ ServerSocketChannelWrapper val$wrapper;

                AnonymousClass1(ServerSocketChannel closeableServer22, ServerSocketChannelWrapper closeableWrapper32, SelectionKey key22) {
                    r2 = closeableServer22;
                    r3 = closeableWrapper32;
                    r4 = key22;
                }

                @Override // com.koushikdutta.async.AsyncServerSocket
                public int getLocalPort() {
                    return r2.socket().getLocalPort();
                }

                @Override // com.koushikdutta.async.AsyncServerSocket
                public void stop() {
                    StreamUtility.closeQuietly(r3);
                    try {
                        r4.cancel();
                    } catch (Exception e2) {
                    }
                }
            }
        });
        return holder2.held;
    }

    private class ConnectFuture extends SimpleFuture<AsyncNetworkSocket> {
        ConnectCallback callback;
        SocketChannel socket;

        private ConnectFuture() {
        }

        /* synthetic */ ConnectFuture(AsyncServer x0, RunnableC04291 x1) {
            this();
        }

        @Override // com.koushikdutta.async.future.SimpleCancellable
        protected void cancelCleanup() {
            super.cancelCleanup();
            try {
                if (this.socket != null) {
                    this.socket.close();
                }
            } catch (IOException e) {
            }
        }
    }

    public ConnectFuture connectResolvedInetSocketAddress(InetSocketAddress address, ConnectCallback callback) {
        ConnectFuture cancel = new ConnectFuture();
        if (!$assertionsDisabled && address.isUnresolved()) {
            throw new AssertionError();
        }
        post(new Runnable() { // from class: com.koushikdutta.async.AsyncServer.6
            final /* synthetic */ InetSocketAddress val$address;
            final /* synthetic */ ConnectCallback val$callback;
            final /* synthetic */ ConnectFuture val$cancel;

            RunnableC04406(ConnectFuture cancel2, ConnectCallback callback2, InetSocketAddress address2) {
                r2 = cancel2;
                r3 = callback2;
                r4 = address2;
            }

            @Override // java.lang.Runnable
            public void run() {
                if (!r2.isCancelled()) {
                    r2.callback = r3;
                    SelectionKey ckey = null;
                    SocketChannel socket = null;
                    try {
                        ConnectFuture connectFuture = r2;
                        SocketChannel socket2 = SocketChannel.open();
                        connectFuture.socket = socket2;
                        try {
                            socket2.configureBlocking(false);
                            ckey = socket2.register(AsyncServer.this.mSelector.getSelector(), 8);
                            ckey.attach(r2);
                            socket2.connect(r4);
                        } catch (Throwable th) {
                            e = th;
                            socket = socket2;
                            if (ckey != null) {
                                ckey.cancel();
                            }
                            StreamUtility.closeQuietly(socket);
                            r2.setComplete((Exception) new RuntimeException(e));
                        }
                    } catch (Throwable th2) {
                        e = th2;
                    }
                }
            }
        });
        return cancel2;
    }

    /* renamed from: com.koushikdutta.async.AsyncServer$6 */
    class RunnableC04406 implements Runnable {
        final /* synthetic */ InetSocketAddress val$address;
        final /* synthetic */ ConnectCallback val$callback;
        final /* synthetic */ ConnectFuture val$cancel;

        RunnableC04406(ConnectFuture cancel2, ConnectCallback callback2, InetSocketAddress address2) {
            r2 = cancel2;
            r3 = callback2;
            r4 = address2;
        }

        @Override // java.lang.Runnable
        public void run() {
            if (!r2.isCancelled()) {
                r2.callback = r3;
                SelectionKey ckey = null;
                SocketChannel socket = null;
                try {
                    ConnectFuture connectFuture = r2;
                    SocketChannel socket2 = SocketChannel.open();
                    connectFuture.socket = socket2;
                    try {
                        socket2.configureBlocking(false);
                        ckey = socket2.register(AsyncServer.this.mSelector.getSelector(), 8);
                        ckey.attach(r2);
                        socket2.connect(r4);
                    } catch (Throwable th) {
                        e = th;
                        socket = socket2;
                        if (ckey != null) {
                            ckey.cancel();
                        }
                        StreamUtility.closeQuietly(socket);
                        r2.setComplete((Exception) new RuntimeException(e));
                    }
                } catch (Throwable th2) {
                    e = th2;
                }
            }
        }
    }

    public Cancellable connectSocket(InetSocketAddress remote, ConnectCallback callback) {
        if (!remote.isUnresolved()) {
            return connectResolvedInetSocketAddress(remote, callback);
        }
        SimpleFuture<AsyncNetworkSocket> ret = new SimpleFuture<>();
        Future<InetAddress> lookup = getByName(remote.getHostName());
        ret.setParent((Cancellable) lookup);
        lookup.setCallback(new FutureCallback<InetAddress>() { // from class: com.koushikdutta.async.AsyncServer.7
            final /* synthetic */ ConnectCallback val$callback;
            final /* synthetic */ InetSocketAddress val$remote;
            final /* synthetic */ SimpleFuture val$ret;

            C04417(ConnectCallback callback2, SimpleFuture ret2, InetSocketAddress remote2) {
                r2 = callback2;
                r3 = ret2;
                r4 = remote2;
            }

            @Override // com.koushikdutta.async.future.FutureCallback
            public void onCompleted(Exception e, InetAddress result) {
                if (e == null) {
                    r3.setComplete((Future) AsyncServer.this.connectResolvedInetSocketAddress(new InetSocketAddress(result, r4.getPort()), r2));
                } else {
                    r2.onConnectCompleted(e, null);
                    r3.setComplete(e);
                }
            }
        });
        return ret2;
    }

    /* renamed from: com.koushikdutta.async.AsyncServer$7 */
    class C04417 implements FutureCallback<InetAddress> {
        final /* synthetic */ ConnectCallback val$callback;
        final /* synthetic */ InetSocketAddress val$remote;
        final /* synthetic */ SimpleFuture val$ret;

        C04417(ConnectCallback callback2, SimpleFuture ret2, InetSocketAddress remote2) {
            r2 = callback2;
            r3 = ret2;
            r4 = remote2;
        }

        @Override // com.koushikdutta.async.future.FutureCallback
        public void onCompleted(Exception e, InetAddress result) {
            if (e == null) {
                r3.setComplete((Future) AsyncServer.this.connectResolvedInetSocketAddress(new InetSocketAddress(result, r4.getPort()), r2));
            } else {
                r2.onConnectCompleted(e, null);
                r3.setComplete(e);
            }
        }
    }

    public Cancellable connectSocket(String host, int port, ConnectCallback callback) {
        return connectSocket(InetSocketAddress.createUnresolved(host, port), callback);
    }

    private static ExecutorService newSynchronousWorkers(String prefix) {
        ThreadFactory tf = new NamedThreadFactory(prefix);
        ThreadPoolExecutor tpe = new ThreadPoolExecutor(1, 4, 10L, TimeUnit.SECONDS, new LinkedBlockingQueue(), tf);
        return tpe;
    }

    /* renamed from: com.koushikdutta.async.AsyncServer$8 */
    static class C04428 implements Comparator<InetAddress> {
        C04428() {
        }

        @Override // java.util.Comparator
        public int compare(InetAddress lhs, InetAddress rhs) {
            if ((lhs instanceof Inet4Address) && (rhs instanceof Inet4Address)) {
                return 0;
            }
            if ((lhs instanceof Inet6Address) && (rhs instanceof Inet6Address)) {
                return 0;
            }
            if ((lhs instanceof Inet4Address) && (rhs instanceof Inet6Address)) {
                return -1;
            }
            return 1;
        }
    }

    /* renamed from: com.koushikdutta.async.AsyncServer$9 */
    class RunnableC04439 implements Runnable {
        final /* synthetic */ String val$host;
        final /* synthetic */ SimpleFuture val$ret;

        RunnableC04439(String str, SimpleFuture simpleFuture) {
            r2 = str;
            r3 = simpleFuture;
        }

        @Override // java.lang.Runnable
        public void run() {
            try {
                InetAddress[] result = InetAddress.getAllByName(r2);
                Arrays.sort(result, AsyncServer.ipSorter);
                if (result == null || result.length == 0) {
                    throw new HostnameResolutionException("no addresses for host");
                }
                AsyncServer.this.post(new Runnable() { // from class: com.koushikdutta.async.AsyncServer.9.1
                    final /* synthetic */ InetAddress[] val$result;

                    AnonymousClass1(InetAddress[] result2) {
                        r2 = result2;
                    }

                    @Override // java.lang.Runnable
                    public void run() {
                        r3.setComplete(null, r2);
                    }
                });
            } catch (Exception e) {
                AsyncServer.this.post(new Runnable() { // from class: com.koushikdutta.async.AsyncServer.9.2
                    final /* synthetic */ Exception val$e;

                    AnonymousClass2(Exception e2) {
                        r2 = e2;
                    }

                    @Override // java.lang.Runnable
                    public void run() {
                        r3.setComplete(r2, null);
                    }
                });
            }
        }

        /* renamed from: com.koushikdutta.async.AsyncServer$9$1 */
        class AnonymousClass1 implements Runnable {
            final /* synthetic */ InetAddress[] val$result;

            AnonymousClass1(InetAddress[] result2) {
                r2 = result2;
            }

            @Override // java.lang.Runnable
            public void run() {
                r3.setComplete(null, r2);
            }
        }

        /* renamed from: com.koushikdutta.async.AsyncServer$9$2 */
        class AnonymousClass2 implements Runnable {
            final /* synthetic */ Exception val$e;

            AnonymousClass2(Exception e2) {
                r2 = e2;
            }

            @Override // java.lang.Runnable
            public void run() {
                r3.setComplete(r2, null);
            }
        }
    }

    public Future<InetAddress[]> getAllByName(String host) {
        SimpleFuture<InetAddress[]> ret = new SimpleFuture<>();
        synchronousResolverWorkers.execute(new Runnable() { // from class: com.koushikdutta.async.AsyncServer.9
            final /* synthetic */ String val$host;
            final /* synthetic */ SimpleFuture val$ret;

            RunnableC04439(String host2, SimpleFuture ret2) {
                r2 = host2;
                r3 = ret2;
            }

            @Override // java.lang.Runnable
            public void run() {
                try {
                    InetAddress[] result2 = InetAddress.getAllByName(r2);
                    Arrays.sort(result2, AsyncServer.ipSorter);
                    if (result2 == null || result2.length == 0) {
                        throw new HostnameResolutionException("no addresses for host");
                    }
                    AsyncServer.this.post(new Runnable() { // from class: com.koushikdutta.async.AsyncServer.9.1
                        final /* synthetic */ InetAddress[] val$result;

                        AnonymousClass1(InetAddress[] result22) {
                            r2 = result22;
                        }

                        @Override // java.lang.Runnable
                        public void run() {
                            r3.setComplete(null, r2);
                        }
                    });
                } catch (Exception e2) {
                    AsyncServer.this.post(new Runnable() { // from class: com.koushikdutta.async.AsyncServer.9.2
                        final /* synthetic */ Exception val$e;

                        AnonymousClass2(Exception e22) {
                            r2 = e22;
                        }

                        @Override // java.lang.Runnable
                        public void run() {
                            r3.setComplete(r2, null);
                        }
                    });
                }
            }

            /* renamed from: com.koushikdutta.async.AsyncServer$9$1 */
            class AnonymousClass1 implements Runnable {
                final /* synthetic */ InetAddress[] val$result;

                AnonymousClass1(InetAddress[] result22) {
                    r2 = result22;
                }

                @Override // java.lang.Runnable
                public void run() {
                    r3.setComplete(null, r2);
                }
            }

            /* renamed from: com.koushikdutta.async.AsyncServer$9$2 */
            class AnonymousClass2 implements Runnable {
                final /* synthetic */ Exception val$e;

                AnonymousClass2(Exception e22) {
                    r2 = e22;
                }

                @Override // java.lang.Runnable
                public void run() {
                    r3.setComplete(r2, null);
                }
            }
        });
        return ret2;
    }

    /* renamed from: com.koushikdutta.async.AsyncServer$10 */
    class C043010 extends TransformFuture<InetAddress, InetAddress[]> {
        C043010() {
        }

        @Override // com.koushikdutta.async.future.TransformFuture
        public void transform(InetAddress[] result) throws Exception {
            setComplete((C043010) result[0]);
        }
    }

    public Future<InetAddress> getByName(String host) {
        return (Future) getAllByName(host).then(new TransformFuture<InetAddress, InetAddress[]>() { // from class: com.koushikdutta.async.AsyncServer.10
            C043010() {
            }

            @Override // com.koushikdutta.async.future.TransformFuture
            public void transform(InetAddress[] result) throws Exception {
                setComplete((C043010) result[0]);
            }
        });
    }

    public AsyncDatagramSocket connectDatagram(String host, int port) throws IOException {
        DatagramChannel socket = DatagramChannel.open();
        AsyncDatagramSocket handler = new AsyncDatagramSocket();
        handler.attach(socket);
        run(new Runnable() { // from class: com.koushikdutta.async.AsyncServer.11
            final /* synthetic */ AsyncDatagramSocket val$handler;
            final /* synthetic */ String val$host;
            final /* synthetic */ int val$port;
            final /* synthetic */ DatagramChannel val$socket;

            RunnableC043111(String host2, int port2, AsyncDatagramSocket handler2, DatagramChannel socket2) {
                r2 = host2;
                r3 = port2;
                r4 = handler2;
                r5 = socket2;
            }

            @Override // java.lang.Runnable
            public void run() {
                try {
                    SocketAddress remote = new InetSocketAddress(r2, r3);
                    AsyncServer.this.handleSocket(r4);
                    r5.connect(remote);
                } catch (IOException e) {
                    Log.e(AsyncServer.LOGTAG, "Datagram error", e);
                    StreamUtility.closeQuietly(r5);
                }
            }
        });
        return handler2;
    }

    /* renamed from: com.koushikdutta.async.AsyncServer$11 */
    class RunnableC043111 implements Runnable {
        final /* synthetic */ AsyncDatagramSocket val$handler;
        final /* synthetic */ String val$host;
        final /* synthetic */ int val$port;
        final /* synthetic */ DatagramChannel val$socket;

        RunnableC043111(String host2, int port2, AsyncDatagramSocket handler2, DatagramChannel socket2) {
            r2 = host2;
            r3 = port2;
            r4 = handler2;
            r5 = socket2;
        }

        @Override // java.lang.Runnable
        public void run() {
            try {
                SocketAddress remote = new InetSocketAddress(r2, r3);
                AsyncServer.this.handleSocket(r4);
                r5.connect(remote);
            } catch (IOException e) {
                Log.e(AsyncServer.LOGTAG, "Datagram error", e);
                StreamUtility.closeQuietly(r5);
            }
        }
    }

    public AsyncDatagramSocket openDatagram() throws IOException {
        return openDatagram(null, false);
    }

    public AsyncDatagramSocket openDatagram(SocketAddress address, boolean reuseAddress) throws IOException {
        DatagramChannel socket = DatagramChannel.open();
        AsyncDatagramSocket handler = new AsyncDatagramSocket();
        handler.attach(socket);
        run(new Runnable() { // from class: com.koushikdutta.async.AsyncServer.12
            final /* synthetic */ SocketAddress val$address;
            final /* synthetic */ AsyncDatagramSocket val$handler;
            final /* synthetic */ boolean val$reuseAddress;
            final /* synthetic */ DatagramChannel val$socket;

            RunnableC043212(boolean reuseAddress2, DatagramChannel socket2, SocketAddress address2, AsyncDatagramSocket handler2) {
                r2 = reuseAddress2;
                r3 = socket2;
                r4 = address2;
                r5 = handler2;
            }

            @Override // java.lang.Runnable
            public void run() {
                try {
                    if (r2) {
                        r3.socket().setReuseAddress(r2);
                    }
                    r3.socket().bind(r4);
                    AsyncServer.this.handleSocket(r5);
                } catch (IOException e) {
                    Log.e(AsyncServer.LOGTAG, "Datagram error", e);
                    StreamUtility.closeQuietly(r3);
                }
            }
        });
        return handler2;
    }

    /* renamed from: com.koushikdutta.async.AsyncServer$12 */
    class RunnableC043212 implements Runnable {
        final /* synthetic */ SocketAddress val$address;
        final /* synthetic */ AsyncDatagramSocket val$handler;
        final /* synthetic */ boolean val$reuseAddress;
        final /* synthetic */ DatagramChannel val$socket;

        RunnableC043212(boolean reuseAddress2, DatagramChannel socket2, SocketAddress address2, AsyncDatagramSocket handler2) {
            r2 = reuseAddress2;
            r3 = socket2;
            r4 = address2;
            r5 = handler2;
        }

        @Override // java.lang.Runnable
        public void run() {
            try {
                if (r2) {
                    r3.socket().setReuseAddress(r2);
                }
                r3.socket().bind(r4);
                AsyncServer.this.handleSocket(r5);
            } catch (IOException e) {
                Log.e(AsyncServer.LOGTAG, "Datagram error", e);
                StreamUtility.closeQuietly(r3);
            }
        }
    }

    public AsyncDatagramSocket connectDatagram(SocketAddress remote) throws IOException {
        DatagramChannel socket = DatagramChannel.open();
        AsyncDatagramSocket handler = new AsyncDatagramSocket();
        handler.attach(socket);
        run(new Runnable() { // from class: com.koushikdutta.async.AsyncServer.13
            final /* synthetic */ AsyncDatagramSocket val$handler;
            final /* synthetic */ SocketAddress val$remote;
            final /* synthetic */ DatagramChannel val$socket;

            RunnableC043313(AsyncDatagramSocket handler2, DatagramChannel socket2, SocketAddress remote2) {
                r2 = handler2;
                r3 = socket2;
                r4 = remote2;
            }

            @Override // java.lang.Runnable
            public void run() {
                try {
                    AsyncServer.this.handleSocket(r2);
                    r3.connect(r4);
                } catch (IOException e) {
                    StreamUtility.closeQuietly(r3);
                }
            }
        });
        return handler2;
    }

    /* renamed from: com.koushikdutta.async.AsyncServer$13 */
    class RunnableC043313 implements Runnable {
        final /* synthetic */ AsyncDatagramSocket val$handler;
        final /* synthetic */ SocketAddress val$remote;
        final /* synthetic */ DatagramChannel val$socket;

        RunnableC043313(AsyncDatagramSocket handler2, DatagramChannel socket2, SocketAddress remote2) {
            r2 = handler2;
            r3 = socket2;
            r4 = remote2;
        }

        @Override // java.lang.Runnable
        public void run() {
            try {
                AsyncServer.this.handleSocket(r2);
                r3.connect(r4);
            } catch (IOException e) {
                StreamUtility.closeQuietly(r3);
            }
        }
    }

    private boolean addMe() {
        synchronized (mServers) {
            AsyncServer current = mServers.get(this.mAffinity);
            if (current != null) {
                return false;
            }
            mServers.put(this.mAffinity, this);
            return true;
        }
    }

    public static AsyncServer getCurrentThreadServer() {
        return mServers.get(Thread.currentThread());
    }

    private void run(boolean newThread) {
        SelectorWrapper selector;
        PriorityQueue<Scheduled> queue;
        boolean reentrant = false;
        synchronized (this) {
            if (this.mSelector != null) {
                Log.i(LOGTAG, "Reentrant call");
                if (!$assertionsDisabled && Thread.currentThread() != this.mAffinity) {
                    throw new AssertionError();
                }
                reentrant = true;
                selector = this.mSelector;
                queue = this.mQueue;
            } else {
                try {
                    selector = new SelectorWrapper(SelectorProvider.provider().openSelector());
                    this.mSelector = selector;
                    queue = this.mQueue;
                    if (newThread) {
                        this.mAffinity = new Thread(this.mName) { // from class: com.koushikdutta.async.AsyncServer.14
                            final /* synthetic */ PriorityQueue val$queue;
                            final /* synthetic */ SelectorWrapper val$selector;

                            /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
                            C043414(String x0, SelectorWrapper selector2, PriorityQueue queue2) {
                                super(x0);
                                r3 = selector2;
                                r4 = queue2;
                            }

                            @Override // java.lang.Thread, java.lang.Runnable
                            public void run() {
                                AsyncServer.run(AsyncServer.this, r3, r4);
                            }
                        };
                    } else {
                        this.mAffinity = Thread.currentThread();
                    }
                    if (!addMe()) {
                        try {
                            this.mSelector.close();
                        } catch (Exception e) {
                        }
                        this.mSelector = null;
                        this.mAffinity = null;
                        return;
                    } else if (newThread) {
                        this.mAffinity.start();
                        return;
                    }
                } catch (IOException e2) {
                    return;
                }
            }
            if (reentrant) {
                try {
                    runLoop(this, selector2, queue2);
                    return;
                } catch (AsyncSelectorException e3) {
                    Log.i(LOGTAG, "Selector closed", e3);
                    try {
                        selector2.getSelector().close();
                        return;
                    } catch (Exception e4) {
                        return;
                    }
                }
            }
            run(this, selector2, queue2);
        }
    }

    /* renamed from: com.koushikdutta.async.AsyncServer$14 */
    class C043414 extends Thread {
        final /* synthetic */ PriorityQueue val$queue;
        final /* synthetic */ SelectorWrapper val$selector;

        /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
        C043414(String x0, SelectorWrapper selector2, PriorityQueue queue2) {
            super(x0);
            r3 = selector2;
            r4 = queue2;
        }

        @Override // java.lang.Thread, java.lang.Runnable
        public void run() {
            AsyncServer.run(AsyncServer.this, r3, r4);
        }
    }

    public static void run(AsyncServer server, SelectorWrapper selector, PriorityQueue<Scheduled> queue) {
        while (true) {
            try {
                runLoop(server, selector, queue);
            } catch (AsyncSelectorException e) {
                Log.i(LOGTAG, "Selector exception, shutting down", e);
                try {
                    selector.getSelector().close();
                } catch (Exception e2) {
                }
            }
            synchronized (server) {
                if (!selector.isOpen() || (selector.keys().size() <= 0 && queue.size() <= 0)) {
                    break;
                }
            }
        }
        shutdownEverything(selector);
        if (server.mSelector == selector) {
            server.mQueue = new PriorityQueue<>(1, Scheduler.INSTANCE);
            server.mSelector = null;
            server.mAffinity = null;
        }
        synchronized (mServers) {
            mServers.remove(Thread.currentThread());
        }
    }

    private static void shutdownKeys(SelectorWrapper selector) {
        try {
            for (SelectionKey key : selector.keys()) {
                StreamUtility.closeQuietly(key.channel());
                try {
                    key.cancel();
                } catch (Exception e) {
                }
            }
        } catch (Exception e2) {
        }
    }

    public static void shutdownEverything(SelectorWrapper selector) {
        shutdownKeys(selector);
        try {
            selector.close();
        } catch (Exception e) {
        }
    }

    private static long lockAndRunQueue(AsyncServer server, PriorityQueue<Scheduled> queue) {
        long wait = QUEUE_EMPTY;
        while (true) {
            Scheduled run = null;
            synchronized (server) {
                long now = System.currentTimeMillis();
                if (queue.size() > 0) {
                    Scheduled s = queue.remove();
                    if (s.time <= now) {
                        run = s;
                    } else {
                        wait = s.time - now;
                        queue.add(s);
                    }
                }
            }
            if (run != null) {
                run.runnable.run();
            } else {
                server.postCounter = 0;
                return wait;
            }
        }
    }

    private static class AsyncSelectorException extends IOException {
        public AsyncSelectorException(Exception e) {
            super(e);
        }
    }

    private static void runLoop(AsyncServer server, SelectorWrapper selector, PriorityQueue<Scheduled> queue) throws AsyncSelectorException {
        boolean needsSelect = true;
        long wait = lockAndRunQueue(server, queue);
        try {
            synchronized (server) {
                int readyNow = selector.selectNow();
                if (readyNow == 0) {
                    if (selector.keys().size() == 0 && wait == QUEUE_EMPTY) {
                        return;
                    }
                } else {
                    needsSelect = false;
                }
                if (needsSelect) {
                    if (wait == QUEUE_EMPTY) {
                        selector.select();
                    } else {
                        selector.select(wait);
                    }
                }
                Set<SelectionKey> readyKeys = selector.selectedKeys();
                for (SelectionKey selectionKey : readyKeys) {
                    if (selectionKey.isAcceptable()) {
                        ServerSocketChannel nextReady = (ServerSocketChannel) selectionKey.channel();
                        SocketChannel sc = null;
                        SelectionKey ckey = null;
                        try {
                            sc = nextReady.accept();
                            if (sc != null) {
                                sc.configureBlocking(false);
                                SelectionKey register = sc.register(selector.getSelector(), 1);
                                ListenCallback listenCallback = (ListenCallback) selectionKey.attachment();
                                AsyncNetworkSocket asyncNetworkSocket = new AsyncNetworkSocket();
                                asyncNetworkSocket.attach(sc, (InetSocketAddress) sc.socket().getRemoteSocketAddress());
                                asyncNetworkSocket.setup(server, register);
                                register.attach(asyncNetworkSocket);
                                listenCallback.onAccepted(asyncNetworkSocket);
                            }
                        } catch (IOException e) {
                            StreamUtility.closeQuietly(sc);
                            if (0 != 0) {
                                ckey.cancel();
                            }
                        }
                    } else if (selectionKey.isReadable()) {
                        AsyncNetworkSocket handler = (AsyncNetworkSocket) selectionKey.attachment();
                        int transmitted = handler.onReadable();
                        server.onDataReceived(transmitted);
                    } else if (selectionKey.isWritable()) {
                        AsyncNetworkSocket handler2 = (AsyncNetworkSocket) selectionKey.attachment();
                        handler2.onDataWritable();
                    } else if (selectionKey.isConnectable()) {
                        ConnectFuture cancel = (ConnectFuture) selectionKey.attachment();
                        SocketChannel sc2 = (SocketChannel) selectionKey.channel();
                        selectionKey.interestOps(1);
                        try {
                            sc2.finishConnect();
                            AsyncNetworkSocket asyncNetworkSocket2 = new AsyncNetworkSocket();
                            asyncNetworkSocket2.setup(server, selectionKey);
                            asyncNetworkSocket2.attach(sc2, (InetSocketAddress) sc2.socket().getRemoteSocketAddress());
                            selectionKey.attach(asyncNetworkSocket2);
                            try {
                                if (cancel.setComplete((ConnectFuture) asyncNetworkSocket2)) {
                                    cancel.callback.onConnectCompleted(null, asyncNetworkSocket2);
                                }
                            } catch (Exception e2) {
                                throw new RuntimeException(e2);
                            }
                        } catch (IOException ex) {
                            selectionKey.cancel();
                            StreamUtility.closeQuietly(sc2);
                            if (cancel.setComplete((Exception) ex)) {
                                cancel.callback.onConnectCompleted(ex, null);
                            }
                        }
                    } else {
                        Log.i(LOGTAG, "wtf");
                        throw new RuntimeException("Unknown key state.");
                    }
                }
                readyKeys.clear();
            }
        } catch (Exception e3) {
            throw new AsyncSelectorException(e3);
        }
    }

    /* renamed from: com.koushikdutta.async.AsyncServer$15 */
    class RunnableC043515 implements Runnable {
        RunnableC043515() {
        }

        @Override // java.lang.Runnable
        public void run() {
            if (AsyncServer.this.mSelector == null) {
                Log.i(AsyncServer.LOGTAG, "Server dump not possible. No selector?");
                return;
            }
            Log.i(AsyncServer.LOGTAG, "Key Count: " + AsyncServer.this.mSelector.keys().size());
            for (SelectionKey key : AsyncServer.this.mSelector.keys()) {
                Log.i(AsyncServer.LOGTAG, "Key: " + key);
            }
        }
    }

    public void dump() {
        post(new Runnable() { // from class: com.koushikdutta.async.AsyncServer.15
            RunnableC043515() {
            }

            @Override // java.lang.Runnable
            public void run() {
                if (AsyncServer.this.mSelector == null) {
                    Log.i(AsyncServer.LOGTAG, "Server dump not possible. No selector?");
                    return;
                }
                Log.i(AsyncServer.LOGTAG, "Key Count: " + AsyncServer.this.mSelector.keys().size());
                for (SelectionKey key : AsyncServer.this.mSelector.keys()) {
                    Log.i(AsyncServer.LOGTAG, "Key: " + key);
                }
            }
        });
    }

    public Thread getAffinity() {
        return this.mAffinity;
    }

    public boolean isAffinityThread() {
        return this.mAffinity == Thread.currentThread();
    }

    public boolean isAffinityThreadOrStopped() {
        Thread affinity = this.mAffinity;
        return affinity == null || affinity == Thread.currentThread();
    }

    private static class NamedThreadFactory implements ThreadFactory {
        private final ThreadGroup group;
        private final String namePrefix;
        private final AtomicInteger threadNumber = new AtomicInteger(1);

        NamedThreadFactory(String namePrefix) {
            SecurityManager s = System.getSecurityManager();
            this.group = s != null ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
            this.namePrefix = namePrefix;
        }

        @Override // java.util.concurrent.ThreadFactory
        public Thread newThread(Runnable r) {
            Thread t = new Thread(this.group, r, this.namePrefix + this.threadNumber.getAndIncrement(), 0L);
            if (t.isDaemon()) {
                t.setDaemon(false);
            }
            if (t.getPriority() != 5) {
                t.setPriority(5);
            }
            return t;
        }
    }
}
