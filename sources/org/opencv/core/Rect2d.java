package org.opencv.core;

/* loaded from: classes.dex */
public class Rect2d {
    public double height;
    public double width;

    /* renamed from: x */
    public double f103x;

    /* renamed from: y */
    public double f104y;

    public Rect2d(double x, double y, double width, double height) {
        this.f103x = x;
        this.f104y = y;
        this.width = width;
        this.height = height;
    }

    public Rect2d() {
        this(0.0d, 0.0d, 0.0d, 0.0d);
    }

    public Rect2d(Point p1, Point p2) {
        this.f103x = p1.f96x < p2.f96x ? p1.f96x : p2.f96x;
        this.f104y = p1.f97y < p2.f97y ? p1.f97y : p2.f97y;
        this.width = (p1.f96x > p2.f96x ? p1.f96x : p2.f96x) - this.f103x;
        this.height = (p1.f97y > p2.f97y ? p1.f97y : p2.f97y) - this.f104y;
    }

    public Rect2d(Point p, Size s) {
        this(p.f96x, p.f97y, s.width, s.height);
    }

    public Rect2d(double[] vals) {
        set(vals);
    }

    public void set(double[] vals) {
        if (vals != null) {
            this.f103x = vals.length > 0 ? vals[0] : 0.0d;
            this.f104y = vals.length > 1 ? vals[1] : 0.0d;
            this.width = vals.length > 2 ? vals[2] : 0.0d;
            this.height = vals.length > 3 ? vals[3] : 0.0d;
            return;
        }
        this.f103x = 0.0d;
        this.f104y = 0.0d;
        this.width = 0.0d;
        this.height = 0.0d;
    }

    public Rect2d clone() {
        return new Rect2d(this.f103x, this.f104y, this.width, this.height);
    }

    /* renamed from: tl */
    public Point m21tl() {
        return new Point(this.f103x, this.f104y);
    }

    /* renamed from: br */
    public Point m20br() {
        return new Point(this.f103x + this.width, this.f104y + this.height);
    }

    public Size size() {
        return new Size(this.width, this.height);
    }

    public double area() {
        return this.width * this.height;
    }

    public boolean empty() {
        return this.width <= 0.0d || this.height <= 0.0d;
    }

    public boolean contains(Point p) {
        return this.f103x <= p.f96x && p.f96x < this.f103x + this.width && this.f104y <= p.f97y && p.f97y < this.f104y + this.height;
    }

    public int hashCode() {
        long temp = Double.doubleToLongBits(this.height);
        int result = ((int) ((temp >>> 32) ^ temp)) + 31;
        long temp2 = Double.doubleToLongBits(this.width);
        int result2 = (result * 31) + ((int) ((temp2 >>> 32) ^ temp2));
        long temp3 = Double.doubleToLongBits(this.f103x);
        int result3 = (result2 * 31) + ((int) ((temp3 >>> 32) ^ temp3));
        long temp4 = Double.doubleToLongBits(this.f104y);
        return (result3 * 31) + ((int) ((temp4 >>> 32) ^ temp4));
    }

    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Rect2d)) {
            return false;
        }
        Rect2d it = (Rect2d) obj;
        return this.f103x == it.f103x && this.f104y == it.f104y && this.width == it.width && this.height == it.height;
    }

    public String toString() {
        return "{" + this.f103x + ", " + this.f104y + ", " + this.width + "x" + this.height + "}";
    }
}
