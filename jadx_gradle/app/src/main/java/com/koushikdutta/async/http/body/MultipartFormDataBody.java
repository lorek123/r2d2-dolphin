package com.koushikdutta.async.http.body;

import com.koushikdutta.async.ByteBufferList;
import com.koushikdutta.async.DataEmitter;
import com.koushikdutta.async.DataSink;
import com.koushikdutta.async.LineEmitter;
import com.koushikdutta.async.Util;
import com.koushikdutta.async.callback.CompletedCallback;
import com.koushikdutta.async.callback.ContinuationCallback;
import com.koushikdutta.async.callback.DataCallback;
import com.koushikdutta.async.future.Continuation;
import com.koushikdutta.async.http.AsyncHttpRequest;
import com.koushikdutta.async.http.Headers;
import com.koushikdutta.async.http.Multimap;
import com.koushikdutta.async.http.server.BoundaryEmitter;
import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.UUID;

/* loaded from: classes.dex */
public class MultipartFormDataBody extends BoundaryEmitter implements AsyncHttpRequestBody<Multimap> {
    public static final String CONTENT_TYPE = "multipart/form-data";
    String contentType = CONTENT_TYPE;
    Headers formData;
    ByteBufferList last;
    String lastName;
    LineEmitter liner;
    MultipartCallback mCallback;
    private ArrayList<Part> mParts;
    int totalToWrite;
    int written;

    public interface MultipartCallback {
        void onPart(Part part);
    }

    @Override // com.koushikdutta.async.http.body.AsyncHttpRequestBody
    public void parse(DataEmitter emitter, CompletedCallback completed) {
        setDataEmitter(emitter);
        setEndCallback(completed);
    }

    void handleLast() {
        if (this.last != null) {
            if (this.formData == null) {
                this.formData = new Headers();
            }
            this.formData.add(this.lastName, this.last.peekString());
            this.lastName = null;
            this.last = null;
        }
    }

    public String getField(String name) {
        if (this.formData == null) {
            return null;
        }
        return this.formData.get(name);
    }

    @Override // com.koushikdutta.async.http.server.BoundaryEmitter
    protected void onBoundaryEnd() {
        super.onBoundaryEnd();
        handleLast();
    }

    @Override // com.koushikdutta.async.http.server.BoundaryEmitter
    protected void onBoundaryStart() {
        Headers headers = new Headers();
        this.liner = new LineEmitter();
        this.liner.setLineCallback(new LineEmitter.StringCallback() { // from class: com.koushikdutta.async.http.body.MultipartFormDataBody.1
            final /* synthetic */ Headers val$headers;

            C05191(Headers headers2) {
                r2 = headers2;
            }

            @Override // com.koushikdutta.async.LineEmitter.StringCallback
            public void onStringAvailable(String s) {
                if (!"\r".equals(s)) {
                    r2.addLine(s);
                    return;
                }
                MultipartFormDataBody.this.handleLast();
                MultipartFormDataBody.this.liner = null;
                MultipartFormDataBody.this.setDataCallback(null);
                Part part = new Part(r2);
                if (MultipartFormDataBody.this.mCallback != null) {
                    MultipartFormDataBody.this.mCallback.onPart(part);
                }
                if (MultipartFormDataBody.this.getDataCallback() == null) {
                    if (part.isFile()) {
                        MultipartFormDataBody.this.setDataCallback(new DataCallback.NullDataCallback());
                        return;
                    }
                    MultipartFormDataBody.this.lastName = part.getName();
                    MultipartFormDataBody.this.last = new ByteBufferList();
                    MultipartFormDataBody.this.setDataCallback(new DataCallback() { // from class: com.koushikdutta.async.http.body.MultipartFormDataBody.1.1
                        AnonymousClass1() {
                        }

                        @Override // com.koushikdutta.async.callback.DataCallback
                        public void onDataAvailable(DataEmitter emitter, ByteBufferList bb) {
                            bb.get(MultipartFormDataBody.this.last);
                        }
                    });
                }
            }

            /* renamed from: com.koushikdutta.async.http.body.MultipartFormDataBody$1$1 */
            class AnonymousClass1 implements DataCallback {
                AnonymousClass1() {
                }

                @Override // com.koushikdutta.async.callback.DataCallback
                public void onDataAvailable(DataEmitter emitter, ByteBufferList bb) {
                    bb.get(MultipartFormDataBody.this.last);
                }
            }
        });
        setDataCallback(this.liner);
    }

    /* renamed from: com.koushikdutta.async.http.body.MultipartFormDataBody$1 */
    class C05191 implements LineEmitter.StringCallback {
        final /* synthetic */ Headers val$headers;

        C05191(Headers headers2) {
            r2 = headers2;
        }

        @Override // com.koushikdutta.async.LineEmitter.StringCallback
        public void onStringAvailable(String s) {
            if (!"\r".equals(s)) {
                r2.addLine(s);
                return;
            }
            MultipartFormDataBody.this.handleLast();
            MultipartFormDataBody.this.liner = null;
            MultipartFormDataBody.this.setDataCallback(null);
            Part part = new Part(r2);
            if (MultipartFormDataBody.this.mCallback != null) {
                MultipartFormDataBody.this.mCallback.onPart(part);
            }
            if (MultipartFormDataBody.this.getDataCallback() == null) {
                if (part.isFile()) {
                    MultipartFormDataBody.this.setDataCallback(new DataCallback.NullDataCallback());
                    return;
                }
                MultipartFormDataBody.this.lastName = part.getName();
                MultipartFormDataBody.this.last = new ByteBufferList();
                MultipartFormDataBody.this.setDataCallback(new DataCallback() { // from class: com.koushikdutta.async.http.body.MultipartFormDataBody.1.1
                    AnonymousClass1() {
                    }

                    @Override // com.koushikdutta.async.callback.DataCallback
                    public void onDataAvailable(DataEmitter emitter, ByteBufferList bb) {
                        bb.get(MultipartFormDataBody.this.last);
                    }
                });
            }
        }

        /* renamed from: com.koushikdutta.async.http.body.MultipartFormDataBody$1$1 */
        class AnonymousClass1 implements DataCallback {
            AnonymousClass1() {
            }

            @Override // com.koushikdutta.async.callback.DataCallback
            public void onDataAvailable(DataEmitter emitter, ByteBufferList bb) {
                bb.get(MultipartFormDataBody.this.last);
            }
        }
    }

    public MultipartFormDataBody(String[] values) {
        for (String value : values) {
            String[] splits = value.split("=");
            if (splits.length == 2 && "boundary".equals(splits[0])) {
                setBoundary(splits[1]);
                return;
            }
        }
        report(new Exception("No boundary found for multipart/form-data"));
    }

    public void setMultipartCallback(MultipartCallback callback) {
        this.mCallback = callback;
    }

    public MultipartCallback getMultipartCallback() {
        return this.mCallback;
    }

    @Override // com.koushikdutta.async.http.body.AsyncHttpRequestBody
    public void write(AsyncHttpRequest request, DataSink sink, CompletedCallback completed) {
        if (this.mParts != null) {
            Continuation c = new Continuation(new CompletedCallback() { // from class: com.koushikdutta.async.http.body.MultipartFormDataBody.2
                final /* synthetic */ CompletedCallback val$completed;

                C05202(CompletedCallback completed2) {
                    r2 = completed2;
                }

                @Override // com.koushikdutta.async.callback.CompletedCallback
                public void onCompleted(Exception ex) {
                    r2.onCompleted(ex);
                }
            });
            Iterator<Part> it = this.mParts.iterator();
            while (it.hasNext()) {
                Part part = it.next();
                c.add(new ContinuationCallback() { // from class: com.koushikdutta.async.http.body.MultipartFormDataBody.5
                    final /* synthetic */ Part val$part;
                    final /* synthetic */ DataSink val$sink;

                    C05235(Part part2, DataSink sink2) {
                        r2 = part2;
                        r3 = sink2;
                    }

                    @Override // com.koushikdutta.async.callback.ContinuationCallback
                    public void onContinue(Continuation continuation, CompletedCallback next) throws Exception {
                        byte[] bytes = r2.getRawHeaders().toPrefixString(MultipartFormDataBody.this.getBoundaryStart()).getBytes();
                        Util.writeAll(r3, bytes, next);
                        MultipartFormDataBody.this.written += bytes.length;
                    }
                }).add(new ContinuationCallback() { // from class: com.koushikdutta.async.http.body.MultipartFormDataBody.4
                    final /* synthetic */ Part val$part;
                    final /* synthetic */ DataSink val$sink;

                    C05224(Part part2, DataSink sink2) {
                        r2 = part2;
                        r3 = sink2;
                    }

                    @Override // com.koushikdutta.async.callback.ContinuationCallback
                    public void onContinue(Continuation continuation, CompletedCallback next) throws Exception {
                        long partLength = r2.length();
                        if (partLength >= 0) {
                            MultipartFormDataBody.this.written = (int) (r2.written + partLength);
                        }
                        r2.write(r3, next);
                    }
                }).add(new ContinuationCallback() { // from class: com.koushikdutta.async.http.body.MultipartFormDataBody.3
                    final /* synthetic */ DataSink val$sink;

                    C05213(DataSink sink2) {
                        r2 = sink2;
                    }

                    @Override // com.koushikdutta.async.callback.ContinuationCallback
                    public void onContinue(Continuation continuation, CompletedCallback next) throws Exception {
                        byte[] bytes = "\r\n".getBytes();
                        Util.writeAll(r2, bytes, next);
                        MultipartFormDataBody.this.written += bytes.length;
                    }
                });
            }
            c.add(new ContinuationCallback() { // from class: com.koushikdutta.async.http.body.MultipartFormDataBody.6
                static final /* synthetic */ boolean $assertionsDisabled;
                final /* synthetic */ DataSink val$sink;

                static {
                    $assertionsDisabled = !MultipartFormDataBody.class.desiredAssertionStatus();
                }

                C05246(DataSink sink2) {
                    r2 = sink2;
                }

                @Override // com.koushikdutta.async.callback.ContinuationCallback
                public void onContinue(Continuation continuation, CompletedCallback next) throws Exception {
                    byte[] bytes = MultipartFormDataBody.this.getBoundaryEnd().getBytes();
                    Util.writeAll(r2, bytes, next);
                    MultipartFormDataBody.this.written += bytes.length;
                    if (!$assertionsDisabled && MultipartFormDataBody.this.written != MultipartFormDataBody.this.totalToWrite) {
                        throw new AssertionError();
                    }
                }
            });
            c.start();
        }
    }

    /* renamed from: com.koushikdutta.async.http.body.MultipartFormDataBody$2 */
    class C05202 implements CompletedCallback {
        final /* synthetic */ CompletedCallback val$completed;

        C05202(CompletedCallback completed2) {
            r2 = completed2;
        }

        @Override // com.koushikdutta.async.callback.CompletedCallback
        public void onCompleted(Exception ex) {
            r2.onCompleted(ex);
        }
    }

    /* renamed from: com.koushikdutta.async.http.body.MultipartFormDataBody$5 */
    class C05235 implements ContinuationCallback {
        final /* synthetic */ Part val$part;
        final /* synthetic */ DataSink val$sink;

        C05235(Part part2, DataSink sink2) {
            r2 = part2;
            r3 = sink2;
        }

        @Override // com.koushikdutta.async.callback.ContinuationCallback
        public void onContinue(Continuation continuation, CompletedCallback next) throws Exception {
            byte[] bytes = r2.getRawHeaders().toPrefixString(MultipartFormDataBody.this.getBoundaryStart()).getBytes();
            Util.writeAll(r3, bytes, next);
            MultipartFormDataBody.this.written += bytes.length;
        }
    }

    /* renamed from: com.koushikdutta.async.http.body.MultipartFormDataBody$4 */
    class C05224 implements ContinuationCallback {
        final /* synthetic */ Part val$part;
        final /* synthetic */ DataSink val$sink;

        C05224(Part part2, DataSink sink2) {
            r2 = part2;
            r3 = sink2;
        }

        @Override // com.koushikdutta.async.callback.ContinuationCallback
        public void onContinue(Continuation continuation, CompletedCallback next) throws Exception {
            long partLength = r2.length();
            if (partLength >= 0) {
                MultipartFormDataBody.this.written = (int) (r2.written + partLength);
            }
            r2.write(r3, next);
        }
    }

    /* renamed from: com.koushikdutta.async.http.body.MultipartFormDataBody$3 */
    class C05213 implements ContinuationCallback {
        final /* synthetic */ DataSink val$sink;

        C05213(DataSink sink2) {
            r2 = sink2;
        }

        @Override // com.koushikdutta.async.callback.ContinuationCallback
        public void onContinue(Continuation continuation, CompletedCallback next) throws Exception {
            byte[] bytes = "\r\n".getBytes();
            Util.writeAll(r2, bytes, next);
            MultipartFormDataBody.this.written += bytes.length;
        }
    }

    /* renamed from: com.koushikdutta.async.http.body.MultipartFormDataBody$6 */
    class C05246 implements ContinuationCallback {
        static final /* synthetic */ boolean $assertionsDisabled;
        final /* synthetic */ DataSink val$sink;

        static {
            $assertionsDisabled = !MultipartFormDataBody.class.desiredAssertionStatus();
        }

        C05246(DataSink sink2) {
            r2 = sink2;
        }

        @Override // com.koushikdutta.async.callback.ContinuationCallback
        public void onContinue(Continuation continuation, CompletedCallback next) throws Exception {
            byte[] bytes = MultipartFormDataBody.this.getBoundaryEnd().getBytes();
            Util.writeAll(r2, bytes, next);
            MultipartFormDataBody.this.written += bytes.length;
            if (!$assertionsDisabled && MultipartFormDataBody.this.written != MultipartFormDataBody.this.totalToWrite) {
                throw new AssertionError();
            }
        }
    }

    @Override // com.koushikdutta.async.http.body.AsyncHttpRequestBody
    public String getContentType() {
        if (getBoundary() == null) {
            setBoundary("----------------------------" + UUID.randomUUID().toString().replace("-", ""));
        }
        return this.contentType + "; boundary=" + getBoundary();
    }

    @Override // com.koushikdutta.async.http.body.AsyncHttpRequestBody
    public boolean readFullyOnRequest() {
        return false;
    }

    @Override // com.koushikdutta.async.http.body.AsyncHttpRequestBody
    public int length() {
        if (getBoundary() == null) {
            setBoundary("----------------------------" + UUID.randomUUID().toString().replace("-", ""));
        }
        int length = 0;
        Iterator<Part> it = this.mParts.iterator();
        while (it.hasNext()) {
            Part part = it.next();
            String partHeader = part.getRawHeaders().toPrefixString(getBoundaryStart());
            if (part.length() == -1) {
                return -1;
            }
            length = (int) (length + part.length() + partHeader.getBytes().length + "\r\n".length());
        }
        int length2 = length + getBoundaryEnd().getBytes().length;
        this.totalToWrite = length2;
        return length2;
    }

    public MultipartFormDataBody() {
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public void addFilePart(String name, File file) {
        addPart(new FilePart(name, file));
    }

    public void addStringPart(String name, String value) {
        addPart(new StringPart(name, value));
    }

    public void addPart(Part part) {
        if (this.mParts == null) {
            this.mParts = new ArrayList<>();
        }
        this.mParts.add(part);
    }

    @Override // com.koushikdutta.async.http.body.AsyncHttpRequestBody
    public Multimap get() {
        return new Multimap(this.formData.getMultiMap());
    }
}
