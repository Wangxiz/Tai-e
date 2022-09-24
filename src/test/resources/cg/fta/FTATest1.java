public class FTATest1 {
    public static Object v;
    public static Object u;
    public static Object w;

    public static void main(String[] args) {
        foo();
        car();
        bar();
    }

    public static void foo() {
        Object o = new A();
        v = o;
    }

    public static void car() {
        Object o = new B();
        u = o;
    }

    public static void bar() {
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
