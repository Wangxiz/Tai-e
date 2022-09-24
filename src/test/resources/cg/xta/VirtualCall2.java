public class VirtualCall2 {

    public static void main(String[] args) {
        B b = new D();
        b.foo();
    }
}

class A {
    void foo() {
    }
}

class B extends A {
}

class C extends B {
    void foo() {
    }
}

class D extends B {
    void foo() {
        C c = new C();
        c.foo();
    }
}

class E extends A {
    void foo() {
    }
}
