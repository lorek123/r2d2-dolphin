package com.koushikdutta.async.util;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Set;

/* loaded from: classes.dex */
public class HashList<T> {
    Hashtable<String, TaggedList<T>> internal = new Hashtable<>();

    public Set<String> keySet() {
        return this.internal.keySet();
    }

    public synchronized <V> V tag(String str) {
        TaggedList<T> taggedList;
        taggedList = this.internal.get(str);
        return taggedList == null ? null : (V) taggedList.tag();
    }

    public synchronized <V> void tag(String key, V tag) {
        TaggedList<T> list = this.internal.get(key);
        if (list == null) {
            list = new TaggedList<>();
            this.internal.put(key, list);
        }
        list.tag(tag);
    }

    public synchronized ArrayList<T> remove(String key) {
        return this.internal.remove(key);
    }

    public synchronized int size() {
        return this.internal.size();
    }

    public synchronized ArrayList<T> get(String key) {
        return this.internal.get(key);
    }

    public synchronized boolean contains(String key) {
        boolean z;
        ArrayList<T> check = get(key);
        if (check != null) {
            z = check.size() > 0;
        }
        return z;
    }

    public synchronized void add(String key, T value) {
        ArrayList<T> ret = get(key);
        if (ret == null) {
            TaggedList<T> put = new TaggedList<>();
            ret = put;
            this.internal.put(key, put);
        }
        ret.add(value);
    }

    public synchronized T pop(String key) {
        T t = null;
        synchronized (this) {
            TaggedList<T> values = this.internal.get(key);
            if (values != null && values.size() != 0) {
                t = values.remove(values.size() - 1);
            }
        }
        return t;
    }

    public synchronized boolean removeItem(String key, T value) {
        boolean z = false;
        synchronized (this) {
            TaggedList<T> values = this.internal.get(key);
            if (values != null) {
                values.remove(value);
                if (values.size() == 0) {
                    z = true;
                }
            }
        }
        return z;
    }
}
