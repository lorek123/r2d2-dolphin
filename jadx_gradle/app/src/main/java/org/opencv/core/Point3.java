package org.opencv.core;

/* loaded from: classes.dex */
public class Point3 {

    /* renamed from: x */
    public double f98x;

    /* renamed from: y */
    public double f99y;

    /* renamed from: z */
    public double f100z;

    public Point3(double x, double y, double z) {
        this.f98x = x;
        this.f99y = y;
        this.f100z = z;
    }

    public Point3() {
        this(0.0d, 0.0d, 0.0d);
    }

    public Point3(Point p) {
        this.f98x = p.f96x;
        this.f99y = p.f97y;
        this.f100z = 0.0d;
    }

    public Point3(double[] vals) {
        this();
        set(vals);
    }

    public void set(double[] vals) {
        if (vals != null) {
            this.f98x = vals.length > 0 ? vals[0] : 0.0d;
            this.f99y = vals.length > 1 ? vals[1] : 0.0d;
            this.f100z = vals.length > 2 ? vals[2] : 0.0d;
        } else {
            this.f98x = 0.0d;
            this.f99y = 0.0d;
            this.f100z = 0.0d;
        }
    }

    public Point3 clone() {
        return new Point3(this.f98x, this.f99y, this.f100z);
    }

    public double dot(Point3 p) {
        return (this.f98x * p.f98x) + (this.f99y * p.f99y) + (this.f100z * p.f100z);
    }

    public Point3 cross(Point3 p) {
        return new Point3((this.f99y * p.f100z) - (this.f100z * p.f99y), (this.f100z * p.f98x) - (this.f98x * p.f100z), (this.f98x * p.f99y) - (this.f99y * p.f98x));
    }

    public int hashCode() {
        long temp = Double.doubleToLongBits(this.f98x);
        int result = ((int) ((temp >>> 32) ^ temp)) + 31;
        long temp2 = Double.doubleToLongBits(this.f99y);
        int result2 = (result * 31) + ((int) ((temp2 >>> 32) ^ temp2));
        long temp3 = Double.doubleToLongBits(this.f100z);
        return (result2 * 31) + ((int) ((temp3 >>> 32) ^ temp3));
    }

    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Point3)) {
            return false;
        }
        Point3 it = (Point3) obj;
        return this.f98x == it.f98x && this.f99y == it.f99y && this.f100z == it.f100z;
    }

    public String toString() {
        return "{" + this.f98x + ", " + this.f99y + ", " + this.f100z + "}";
    }
}
