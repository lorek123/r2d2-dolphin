package com.koushikdutta.async;

import com.koushikdutta.async.callback.CompletedCallback;
import com.koushikdutta.async.callback.DataCallback;
import com.koushikdutta.async.callback.WritableCallback;
import com.koushikdutta.async.util.Allocator;
import com.koushikdutta.async.util.StreamUtility;
import com.koushikdutta.async.wrapper.AsyncSocketWrapper;
import com.koushikdutta.async.wrapper.DataEmitterWrapper;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/* loaded from: classes.dex */
public class Util {
    static final /* synthetic */ boolean $assertionsDisabled;
    public static boolean SUPRESS_DEBUG_EXCEPTIONS;

    static {
        $assertionsDisabled = !Util.class.desiredAssertionStatus();
        SUPRESS_DEBUG_EXCEPTIONS = false;
    }

    public static void emitAllData(DataEmitter emitter, ByteBufferList list) {
        int remaining;
        DataCallback handler = null;
        while (!emitter.isPaused() && (handler = emitter.getDataCallback()) != null && (remaining = list.remaining()) > 0) {
            handler.onDataAvailable(emitter, list);
            if (remaining == list.remaining() && handler == emitter.getDataCallback() && !emitter.isPaused()) {
                System.out.println("handler: " + handler);
                list.recycle();
                if (!SUPRESS_DEBUG_EXCEPTIONS) {
                    if (!$assertionsDisabled) {
                        throw new AssertionError();
                    }
                    throw new RuntimeException("mDataHandler failed to consume data, yet remains the mDataHandler.");
                }
                return;
            }
        }
        if (list.remaining() != 0 && !emitter.isPaused()) {
            System.out.println("handler: " + handler);
            System.out.println("emitter: " + emitter);
            list.recycle();
            if (!SUPRESS_DEBUG_EXCEPTIONS) {
                if (!$assertionsDisabled) {
                    throw new AssertionError();
                }
                throw new RuntimeException("Not all data was consumed by Util.emitAllData");
            }
        }
    }

    public static void pump(InputStream is, DataSink ds, CompletedCallback callback) {
        pump(is, 2147483647L, ds, callback);
    }

    /* renamed from: com.koushikdutta.async.Util$1 */
    static class C04601 implements CompletedCallback {
        boolean reported;

        C04601() {
        }

        @Override // com.koushikdutta.async.callback.CompletedCallback
        public void onCompleted(Exception ex) {
            if (!this.reported) {
                this.reported = true;
                CompletedCallback.this.onCompleted(ex);
            }
        }
    }

    public static void pump(InputStream is, long max, DataSink ds, CompletedCallback callback) {
        CompletedCallback wrapper = new CompletedCallback() { // from class: com.koushikdutta.async.Util.1
            boolean reported;

            C04601() {
            }

            @Override // com.koushikdutta.async.callback.CompletedCallback
            public void onCompleted(Exception ex) {
                if (!this.reported) {
                    this.reported = true;
                    CompletedCallback.this.onCompleted(ex);
                }
            }
        };
        WritableCallback cb = new WritableCallback() { // from class: com.koushikdutta.async.Util.2
            final /* synthetic */ InputStream val$is;
            final /* synthetic */ long val$max;
            final /* synthetic */ CompletedCallback val$wrapper;
            int totalRead = 0;
            ByteBufferList pending = new ByteBufferList();
            Allocator allocator = new Allocator();

            C04612(InputStream is2, long max2, CompletedCallback wrapper2) {
                r3 = is2;
                r4 = max2;
                r6 = wrapper2;
            }

            private void cleanup() {
                DataSink.this.setClosedCallback(null);
                DataSink.this.setWriteableCallback(null);
                this.pending.recycle();
                StreamUtility.closeQuietly(r3);
            }

            @Override // com.koushikdutta.async.callback.WritableCallback
            public void onWriteable() {
                do {
                    try {
                        if (!this.pending.hasRemaining()) {
                            ByteBuffer b = this.allocator.allocate();
                            long toRead = Math.min(r4 - this.totalRead, b.capacity());
                            int read = r3.read(b.array(), 0, (int) toRead);
                            if (read == -1 || this.totalRead == r4) {
                                cleanup();
                                r6.onCompleted(null);
                                return;
                            } else {
                                this.allocator.track(read);
                                this.totalRead += read;
                                b.position(0);
                                b.limit(read);
                                this.pending.add(b);
                            }
                        }
                        DataSink.this.write(this.pending);
                    } catch (Exception e) {
                        cleanup();
                        r6.onCompleted(e);
                        return;
                    }
                } while (!this.pending.hasRemaining());
            }
        };
        ds.setWriteableCallback(cb);
        ds.setClosedCallback(wrapper2);
        cb.onWriteable();
    }

    /* renamed from: com.koushikdutta.async.Util$2 */
    static class C04612 implements WritableCallback {
        final /* synthetic */ InputStream val$is;
        final /* synthetic */ long val$max;
        final /* synthetic */ CompletedCallback val$wrapper;
        int totalRead = 0;
        ByteBufferList pending = new ByteBufferList();
        Allocator allocator = new Allocator();

        C04612(InputStream is2, long max2, CompletedCallback wrapper2) {
            r3 = is2;
            r4 = max2;
            r6 = wrapper2;
        }

        private void cleanup() {
            DataSink.this.setClosedCallback(null);
            DataSink.this.setWriteableCallback(null);
            this.pending.recycle();
            StreamUtility.closeQuietly(r3);
        }

        @Override // com.koushikdutta.async.callback.WritableCallback
        public void onWriteable() {
            do {
                try {
                    if (!this.pending.hasRemaining()) {
                        ByteBuffer b = this.allocator.allocate();
                        long toRead = Math.min(r4 - this.totalRead, b.capacity());
                        int read = r3.read(b.array(), 0, (int) toRead);
                        if (read == -1 || this.totalRead == r4) {
                            cleanup();
                            r6.onCompleted(null);
                            return;
                        } else {
                            this.allocator.track(read);
                            this.totalRead += read;
                            b.position(0);
                            b.limit(read);
                            this.pending.add(b);
                        }
                    }
                    DataSink.this.write(this.pending);
                } catch (Exception e) {
                    cleanup();
                    r6.onCompleted(e);
                    return;
                }
            } while (!this.pending.hasRemaining());
        }
    }

    /* renamed from: com.koushikdutta.async.Util$3 */
    static class C04623 implements DataCallback {
        C04623() {
        }

        @Override // com.koushikdutta.async.callback.DataCallback
        public void onDataAvailable(DataEmitter emitter, ByteBufferList bb) {
            DataSink.this.write(bb);
            if (bb.remaining() > 0) {
                emitter.pause();
            }
        }
    }

    public static void pump(DataEmitter emitter, DataSink sink, CompletedCallback callback) {
        DataCallback dataCallback = new DataCallback() { // from class: com.koushikdutta.async.Util.3
            C04623() {
            }

            @Override // com.koushikdutta.async.callback.DataCallback
            public void onDataAvailable(DataEmitter emitter2, ByteBufferList bb) {
                DataSink.this.write(bb);
                if (bb.remaining() > 0) {
                    emitter2.pause();
                }
            }
        };
        emitter.setDataCallback(dataCallback);
        sink.setWriteableCallback(new WritableCallback() { // from class: com.koushikdutta.async.Util.4
            C04634() {
            }

            @Override // com.koushikdutta.async.callback.WritableCallback
            public void onWriteable() {
                DataEmitter.this.resume();
            }
        });
        CompletedCallback wrapper = new CompletedCallback() { // from class: com.koushikdutta.async.Util.5
            boolean reported;
            final /* synthetic */ CompletedCallback val$callback;
            final /* synthetic */ DataSink val$sink;

            C04645(DataSink sink2, CompletedCallback callback2) {
                r2 = sink2;
                r3 = callback2;
            }

            @Override // com.koushikdutta.async.callback.CompletedCallback
            public void onCompleted(Exception ex) {
                if (!this.reported) {
                    this.reported = true;
                    DataEmitter.this.setDataCallback(null);
                    DataEmitter.this.setEndCallback(null);
                    r2.setClosedCallback(null);
                    r2.setWriteableCallback(null);
                    r3.onCompleted(ex);
                }
            }
        };
        emitter.setEndCallback(wrapper);
        sink2.setClosedCallback(new CompletedCallback() { // from class: com.koushikdutta.async.Util.6
            C04656() {
            }

            @Override // com.koushikdutta.async.callback.CompletedCallback
            public void onCompleted(Exception ex) {
                if (ex == null) {
                    ex = new IOException("sink was closed before emitter ended");
                }
                CompletedCallback.this.onCompleted(ex);
            }
        });
    }

    /* renamed from: com.koushikdutta.async.Util$4 */
    static class C04634 implements WritableCallback {
        C04634() {
        }

        @Override // com.koushikdutta.async.callback.WritableCallback
        public void onWriteable() {
            DataEmitter.this.resume();
        }
    }

    /* renamed from: com.koushikdutta.async.Util$5 */
    static class C04645 implements CompletedCallback {
        boolean reported;
        final /* synthetic */ CompletedCallback val$callback;
        final /* synthetic */ DataSink val$sink;

        C04645(DataSink sink2, CompletedCallback callback2) {
            r2 = sink2;
            r3 = callback2;
        }

        @Override // com.koushikdutta.async.callback.CompletedCallback
        public void onCompleted(Exception ex) {
            if (!this.reported) {
                this.reported = true;
                DataEmitter.this.setDataCallback(null);
                DataEmitter.this.setEndCallback(null);
                r2.setClosedCallback(null);
                r2.setWriteableCallback(null);
                r3.onCompleted(ex);
            }
        }
    }

    /* renamed from: com.koushikdutta.async.Util$6 */
    static class C04656 implements CompletedCallback {
        C04656() {
        }

        @Override // com.koushikdutta.async.callback.CompletedCallback
        public void onCompleted(Exception ex) {
            if (ex == null) {
                ex = new IOException("sink was closed before emitter ended");
            }
            CompletedCallback.this.onCompleted(ex);
        }
    }

    public static void stream(AsyncSocket s1, AsyncSocket s2, CompletedCallback callback) {
        pump(s1, s2, callback);
        pump(s2, s1, callback);
    }

    public static void pump(File file, DataSink ds, CompletedCallback callback) {
        try {
            if (file == null || ds == null) {
                callback.onCompleted(null);
            } else {
                InputStream is = new FileInputStream(file);
                pump(is, ds, new CompletedCallback() { // from class: com.koushikdutta.async.Util.7
                    final /* synthetic */ CompletedCallback val$callback;
                    final /* synthetic */ InputStream val$is;

                    C04667(InputStream is2, CompletedCallback callback2) {
                        r1 = is2;
                        r2 = callback2;
                    }

                    @Override // com.koushikdutta.async.callback.CompletedCallback
                    public void onCompleted(Exception ex) {
                        try {
                            r1.close();
                            r2.onCompleted(ex);
                        } catch (IOException e) {
                            r2.onCompleted(e);
                        }
                    }
                });
            }
        } catch (Exception e) {
            callback2.onCompleted(e);
        }
    }

    /* renamed from: com.koushikdutta.async.Util$7 */
    static class C04667 implements CompletedCallback {
        final /* synthetic */ CompletedCallback val$callback;
        final /* synthetic */ InputStream val$is;

        C04667(InputStream is2, CompletedCallback callback2) {
            r1 = is2;
            r2 = callback2;
        }

        @Override // com.koushikdutta.async.callback.CompletedCallback
        public void onCompleted(Exception ex) {
            try {
                r1.close();
                r2.onCompleted(ex);
            } catch (IOException e) {
                r2.onCompleted(e);
            }
        }
    }

    /* renamed from: com.koushikdutta.async.Util$8 */
    static class C04678 implements WritableCallback {
        final /* synthetic */ ByteBufferList val$bb;
        final /* synthetic */ CompletedCallback val$callback;

        C04678(ByteBufferList byteBufferList, CompletedCallback completedCallback) {
            r2 = byteBufferList;
            r3 = completedCallback;
        }

        @Override // com.koushikdutta.async.callback.WritableCallback
        public void onWriteable() {
            DataSink.this.write(r2);
            if (r2.remaining() == 0 && r3 != null) {
                DataSink.this.setWriteableCallback(null);
                r3.onCompleted(null);
            }
        }
    }

    public static void writeAll(DataSink sink, ByteBufferList bb, CompletedCallback callback) {
        WritableCallback wc = new WritableCallback() { // from class: com.koushikdutta.async.Util.8
            final /* synthetic */ ByteBufferList val$bb;
            final /* synthetic */ CompletedCallback val$callback;

            C04678(ByteBufferList bb2, CompletedCallback callback2) {
                r2 = bb2;
                r3 = callback2;
            }

            @Override // com.koushikdutta.async.callback.WritableCallback
            public void onWriteable() {
                DataSink.this.write(r2);
                if (r2.remaining() == 0 && r3 != null) {
                    DataSink.this.setWriteableCallback(null);
                    r3.onCompleted(null);
                }
            }
        };
        sink.setWriteableCallback(wc);
        wc.onWriteable();
    }

    public static void writeAll(DataSink sink, byte[] bytes, CompletedCallback callback) {
        ByteBuffer bb = ByteBufferList.obtain(bytes.length);
        bb.put(bytes);
        bb.flip();
        ByteBufferList bbl = new ByteBufferList();
        bbl.add(bb);
        writeAll(sink, bbl, callback);
    }

    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Type inference failed for: r1v0, types: [T extends com.koushikdutta.async.AsyncSocket, com.koushikdutta.async.AsyncSocket, java.lang.Object] */
    /* JADX WARN: Type inference failed for: r1v1 */
    /* JADX WARN: Type inference failed for: r1v4, types: [T extends com.koushikdutta.async.AsyncSocket, com.koushikdutta.async.AsyncSocket, java.lang.Object] */
    public static <T extends AsyncSocket> T getWrappedSocket(AsyncSocket asyncSocket, Class<T> cls) {
        if (!cls.isInstance(asyncSocket)) {
            while (asyncSocket instanceof AsyncSocketWrapper) {
                asyncSocket = (T) ((AsyncSocketWrapper) asyncSocket).getSocket();
                if (cls.isInstance(asyncSocket)) {
                    return asyncSocket;
                }
            }
            return null;
        }
        return asyncSocket;
    }

    public static DataEmitter getWrappedDataEmitter(DataEmitter emitter, Class wrappedClass) {
        if (!wrappedClass.isInstance(emitter)) {
            while (emitter instanceof DataEmitterWrapper) {
                emitter = ((AsyncSocketWrapper) emitter).getSocket();
                if (wrappedClass.isInstance(emitter)) {
                    return emitter;
                }
            }
            return null;
        }
        return emitter;
    }

    public static void end(DataEmitter emitter, Exception e) {
        if (emitter != null) {
            end(emitter.getEndCallback(), e);
        }
    }

    public static void end(CompletedCallback end, Exception e) {
        if (end != null) {
            end.onCompleted(e);
        }
    }

    public static void writable(DataSink emitter) {
        if (emitter != null) {
            writable(emitter.getWriteableCallback());
        }
    }

    public static void writable(WritableCallback writable) {
        if (writable != null) {
            writable.onWriteable();
        }
    }
}
