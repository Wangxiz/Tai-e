public class FTATest2 {
    public Object v;
    public Object u;
    public Object w;

    public static void main(String[] args) {
        FTATest2 t = new FTATest2();
        t.foo();
        t.car();
        t.bar();
    }

    public void foo() {
        Object o = new A();
        v = o;
    }

    public void car() {
        Object o = new B();
        u = o;
    }

    public void bar() {
        Object o = new C();
        w = o;
        w.toString();
    }
}

class A {
    public String toString() {
        return "A";
    }
}

class B {
    public String toString() {
        return "B";
    }
}

class C {
    public String toString() {
        return "C";
    }
}
