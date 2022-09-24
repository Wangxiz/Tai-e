public class StaticCall2 {

    public static void main(String[] args) {
        foo();
        A.baz();
    }

    static void foo() {
        B b = new B();
        bar(b);
    }

    static void bar(B b) {
        B.foo(b);
    }
}

class A {

    static void baz() {
        B.qux();
    }

}

class B {

    static void qux() {
        A.baz();
    }

    static void foo(B b) {
        b.haz();
    }

    void haz() {}

}
