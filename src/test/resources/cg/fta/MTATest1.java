public class MTATest1 {
    public static Object v;

    public static void main(String[] args) {
        foo();
        bar();
        car();
        A.foo();
    }

    public static void foo() {
        Object o = new A();
        v = o;
    }

    public static void bar() {
        v.toString();
    }

    public static void car() {
        Object o = new B();
        v = o;
    }
}

class A {
    public String toString() {
        return "A";
    }

    public static void foo() {
        Object o = new C();
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
