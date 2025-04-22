package org.opencv.core;

/* loaded from: classes.dex */
public class Rect {
    public int height;
    public int width;

    /* renamed from: x */
    public int f101x;

    /* renamed from: y */
    public int f102y;

    public Rect(int x, int y, int width, int height) {
        this.f101x = x;
        this.f102y = y;
        this.width = width;
        this.height = height;
    }

    public Rect() {
        this(0, 0, 0, 0);
    }

    public Rect(Point p1, Point p2) {
        this.f101x = (int) (p1.f96x < p2.f96x ? p1.f96x : p2.f96x);
        this.f102y = (int) (p1.f97y < p2.f97y ? p1.f97y : p2.f97y);
        this.width = ((int) (p1.f96x > p2.f96x ? p1.f96x : p2.f96x)) - this.f101x;
        this.height = ((int) (p1.f97y > p2.f97y ? p1.f97y : p2.f97y)) - this.f102y;
    }

    public Rect(Point p, Size s) {
        this((int) p.f96x, (int) p.f97y, (int) s.width, (int) s.height);
    }

    public Rect(double[] vals) {
        set(vals);
    }

    public void set(double[] vals) {
        if (vals != null) {
            this.f101x = vals.length > 0 ? (int) vals[0] : 0;
            this.f102y = vals.length > 1 ? (int) vals[1] : 0;
            this.width = vals.length > 2 ? (int) vals[2] : 0;
            this.height = vals.length > 3 ? (int) vals[3] : 0;
            return;
        }
        this.f101x = 0;
        this.f102y = 0;
        this.width = 0;
        this.height = 0;
    }

    public Rect clone() {
        return new Rect(this.f101x, this.f102y, this.width, this.height);
    }

    /* renamed from: tl */
    public Point m19tl() {
        return new Point(this.f101x, this.f102y);
    }

    /* renamed from: br */
    public Point m18br() {
        return new Point(this.f101x + this.width, this.f102y + this.height);
    }

    public Size size() {
        return new Size(this.width, this.height);
    }

    public double area() {
        return this.width * this.height;
    }

    public boolean empty() {
        return this.width <= 0 || this.height <= 0;
    }

    public boolean contains(Point p) {
        return ((double) this.f101x) <= p.f96x && p.f96x < ((double) (this.f101x + this.width)) && ((double) this.f102y) <= p.f97y && p.f97y < ((double) (this.f102y + this.height));
    }

    public int hashCode() {
        long temp = Double.doubleToLongBits(this.height);
        int result = ((int) ((temp >>> 32) ^ temp)) + 31;
        long temp2 = Double.doubleToLongBits(this.width);
        int result2 = (result * 31) + ((int) ((temp2 >>> 32) ^ temp2));
        long temp3 = Double.doubleToLongBits(this.f101x);
        int result3 = (result2 * 31) + ((int) ((temp3 >>> 32) ^ temp3));
        long temp4 = Double.doubleToLongBits(this.f102y);
        return (result3 * 31) + ((int) ((temp4 >>> 32) ^ temp4));
    }

    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Rect)) {
            return false;
        }
        Rect it = (Rect) obj;
        return this.f101x == it.f101x && this.f102y == it.f102y && this.width == it.width && this.height == it.height;
    }

    public String toString() {
        return "{" + this.f101x + ", " + this.f102y + ", " + this.width + "x" + this.height + "}";
    }
}
