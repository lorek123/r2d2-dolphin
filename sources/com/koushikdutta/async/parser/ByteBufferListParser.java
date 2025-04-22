package com.koushikdutta.async.parser;

import com.koushikdutta.async.ByteBufferList;
import com.koushikdutta.async.DataEmitter;
import com.koushikdutta.async.DataSink;
import com.koushikdutta.async.Util;
import com.koushikdutta.async.callback.CompletedCallback;
import com.koushikdutta.async.callback.DataCallback;
import com.koushikdutta.async.future.Future;
import com.koushikdutta.async.future.SimpleFuture;
import java.lang.reflect.Type;

/* loaded from: classes.dex */
public class ByteBufferListParser implements AsyncParser<ByteBufferList> {

    /* renamed from: com.koushikdutta.async.parser.ByteBufferListParser$1 */
    class C05831 extends SimpleFuture<ByteBufferList> {
        final /* synthetic */ DataEmitter val$emitter;

        C05831(DataEmitter dataEmitter) {
            r2 = dataEmitter;
        }

        @Override // com.koushikdutta.async.future.SimpleCancellable
        protected void cancelCleanup() {
            r2.close();
        }
    }

    @Override // com.koushikdutta.async.parser.AsyncParser
    public Future<ByteBufferList> parse(DataEmitter emitter) {
        ByteBufferList bb = new ByteBufferList();
        SimpleFuture<ByteBufferList> ret = new SimpleFuture<ByteBufferList>() { // from class: com.koushikdutta.async.parser.ByteBufferListParser.1
            final /* synthetic */ DataEmitter val$emitter;

            C05831(DataEmitter emitter2) {
                r2 = emitter2;
            }

            @Override // com.koushikdutta.async.future.SimpleCancellable
            protected void cancelCleanup() {
                r2.close();
            }
        };
        emitter2.setDataCallback(new DataCallback() { // from class: com.koushikdutta.async.parser.ByteBufferListParser.2
            final /* synthetic */ ByteBufferList val$bb;

            C05842(ByteBufferList bb2) {
                r2 = bb2;
            }

            @Override // com.koushikdutta.async.callback.DataCallback
            public void onDataAvailable(DataEmitter emitter2, ByteBufferList data) {
                data.get(r2);
            }
        });
        emitter2.setEndCallback(new CompletedCallback() { // from class: com.koushikdutta.async.parser.ByteBufferListParser.3
            final /* synthetic */ ByteBufferList val$bb;
            final /* synthetic */ SimpleFuture val$ret;

            C05853(SimpleFuture ret2, ByteBufferList bb2) {
                r2 = ret2;
                r3 = bb2;
            }

            @Override // com.koushikdutta.async.callback.CompletedCallback
            public void onCompleted(Exception ex) {
                if (ex != null) {
                    r2.setComplete(ex);
                    return;
                }
                try {
                    r2.setComplete((SimpleFuture) r3);
                } catch (Exception e) {
                    r2.setComplete(e);
                }
            }
        });
        return ret2;
    }

    /* renamed from: com.koushikdutta.async.parser.ByteBufferListParser$2 */
    class C05842 implements DataCallback {
        final /* synthetic */ ByteBufferList val$bb;

        C05842(ByteBufferList bb2) {
            r2 = bb2;
        }

        @Override // com.koushikdutta.async.callback.DataCallback
        public void onDataAvailable(DataEmitter emitter2, ByteBufferList data) {
            data.get(r2);
        }
    }

    /* renamed from: com.koushikdutta.async.parser.ByteBufferListParser$3 */
    class C05853 implements CompletedCallback {
        final /* synthetic */ ByteBufferList val$bb;
        final /* synthetic */ SimpleFuture val$ret;

        C05853(SimpleFuture ret2, ByteBufferList bb2) {
            r2 = ret2;
            r3 = bb2;
        }

        @Override // com.koushikdutta.async.callback.CompletedCallback
        public void onCompleted(Exception ex) {
            if (ex != null) {
                r2.setComplete(ex);
                return;
            }
            try {
                r2.setComplete((SimpleFuture) r3);
            } catch (Exception e) {
                r2.setComplete(e);
            }
        }
    }

    @Override // com.koushikdutta.async.parser.AsyncParser
    public void write(DataSink sink, ByteBufferList value, CompletedCallback completed) {
        Util.writeAll(sink, value, completed);
    }

    @Override // com.koushikdutta.async.parser.AsyncParser
    public Type getType() {
        return ByteBufferList.class;
    }
}
