package com.koushikdutta.async.dns;

import com.koushikdutta.async.ByteBufferList;
import com.koushikdutta.async.http.Multimap;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Iterator;
import org.java_websocket.drafts.Draft_75;

/* loaded from: classes.dex */
public class DnsResponse {
    public InetSocketAddress source;
    public ArrayList<InetAddress> addresses = new ArrayList<>();
    public ArrayList<String> names = new ArrayList<>();
    public Multimap txt = new Multimap();

    private static String parseName(ByteBufferList bb, ByteBuffer backReference) {
        bb.order(ByteOrder.BIG_ENDIAN);
        String ret = "";
        while (true) {
            int len = bb.get() & Draft_75.END_OF_FRAME;
            if (len == 0) {
                return ret;
            }
            if ((len & 192) == 192) {
                int offset = ((len & 63) << 8) | (bb.get() & Draft_75.END_OF_FRAME);
                if (ret.length() > 0) {
                    ret = ret + ".";
                }
                ByteBufferList sub = new ByteBufferList();
                ByteBuffer duplicate = backReference.duplicate();
                duplicate.get(new byte[offset]);
                sub.add(duplicate);
                return ret + parseName(sub, backReference);
            }
            byte[] bytes = new byte[len];
            bb.get(bytes);
            if (ret.length() > 0) {
                ret = ret + ".";
            }
            ret = ret + new String(bytes);
        }
    }

    /*  JADX ERROR: JadxRuntimeException in pass: RegionMakerVisitor
        jadx.core.utils.exceptions.JadxRuntimeException: Can't find top splitter block for handler:B:49:0x00f3
        	at jadx.core.utils.BlockUtils.getTopSplitterForHandler(BlockUtils.java:1179)
        	at jadx.core.dex.visitors.regions.maker.ExcHandlersRegionMaker.collectHandlerRegions(ExcHandlersRegionMaker.java:53)
        	at jadx.core.dex.visitors.regions.maker.ExcHandlersRegionMaker.process(ExcHandlersRegionMaker.java:38)
        	at jadx.core.dex.visitors.regions.RegionMakerVisitor.visit(RegionMakerVisitor.java:27)
        */
    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:14:0x0080 -> B:11:0x006c). Please report as a decompilation issue!!! */
    public static com.koushikdutta.async.dns.DnsResponse parse(com.koushikdutta.async.ByteBufferList r17) {
        /*
            Method dump skipped, instructions count: 248
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.koushikdutta.async.dns.DnsResponse.parse(com.koushikdutta.async.ByteBufferList):com.koushikdutta.async.dns.DnsResponse");
    }

    void parseTxt(ByteBufferList bb) {
        while (bb.hasRemaining()) {
            int length = bb.get() & Draft_75.END_OF_FRAME;
            byte[] bytes = new byte[length];
            bb.get(bytes);
            String string = new String(bytes);
            String[] pair = string.split("=");
            this.txt.add(pair[0], pair[1]);
        }
    }

    public String toString() {
        String ret = "addresses:\n";
        Iterator<InetAddress> it = this.addresses.iterator();
        while (it.hasNext()) {
            InetAddress address = it.next();
            ret = ret + address.toString() + "\n";
        }
        String ret2 = ret + "names:\n";
        Iterator<String> it2 = this.names.iterator();
        while (it2.hasNext()) {
            String name = it2.next();
            ret2 = ret2 + name + "\n";
        }
        return ret2;
    }
}
