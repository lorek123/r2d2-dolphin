package android.support.constraint.solver.widgets;

/* loaded from: classes.dex */
public class Rectangle {
    public int height;
    public int width;

    /* renamed from: x */
    public int f10x;

    /* renamed from: y */
    public int f11y;

    public void setBounds(int x, int y, int width, int height) {
        this.f10x = x;
        this.f11y = y;
        this.width = width;
        this.height = height;
    }

    void grow(int w, int h) {
        this.f10x -= w;
        this.f11y -= h;
        this.width += w * 2;
        this.height += h * 2;
    }

    boolean intersects(Rectangle bounds) {
        return this.f10x >= bounds.f10x && this.f10x < bounds.f10x + bounds.width && this.f11y >= bounds.f11y && this.f11y < bounds.f11y + bounds.height;
    }

    public boolean contains(int x, int y) {
        return x >= this.f10x && x < this.f10x + this.width && y >= this.f11y && y < this.f11y + this.height;
    }

    public int getCenterX() {
        return (this.f10x + this.width) / 2;
    }

    public int getCenterY() {
        return (this.f11y + this.height) / 2;
    }
}
