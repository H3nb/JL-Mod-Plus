package rndguest;
public final class AliasGame {
    public Holder first;
    public Holder stable;
    public Target actual;
    public static final class Holder { public Target value; public Holder(Target t) { value = t; } }
    public static final class Target { public int coins; public Target(int n) { coins = n; } }
    public AliasGame() {
        Target t = new Target(75);
        first = new Holder(t); stable = new Holder(t); actual = t;
    }
    public void shiftFirstAlias() { first.value = new Target(75); }
}
