package org.opencv.core;

/* loaded from: classes.dex */
public class Point {

    /* renamed from: x */
    public double f96x;

    /* renamed from: y */
    public double f97y;

    public Point(double x, double y) {
        this.f96x = x;
        this.f97y = y;
    }

    public Point() {
        this(0.0d, 0.0d);
    }

    public Point(double[] vals) {
        this();
        set(vals);
    }

    public void set(double[] vals) {
        if (vals != null) {
            this.f96x = vals.length > 0 ? vals[0] : 0.0d;
            this.f97y = vals.length > 1 ? vals[1] : 0.0d;
        } else {
            this.f96x = 0.0d;
            this.f97y = 0.0d;
        }
    }

    public Point clone() {
        return new Point(this.f96x, this.f97y);
    }

    public double dot(Point p) {
        return (this.f96x * p.f96x) + (this.f97y * p.f97y);
    }

    public int hashCode() {
        long temp = Double.doubleToLongBits(this.f96x);
        int result = ((int) ((temp >>> 32) ^ temp)) + 31;
        long temp2 = Double.doubleToLongBits(this.f97y);
        return (result * 31) + ((int) ((temp2 >>> 32) ^ temp2));
    }

    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Point)) {
            return false;
        }
        Point it = (Point) obj;
        return this.f96x == it.f96x && this.f97y == it.f97y;
    }

    public boolean inside(Rect r) {
        return r.contains(this);
    }

    public String toString() {
        return "{" + this.f96x + ", " + this.f97y + "}";
    }
}
