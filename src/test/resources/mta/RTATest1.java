public class RTATest1 {
    public static void main(String[] args) {
        Object o = foo();
        bar(o);
    }

    public static Object foo() {
        return new A();
    }

    public static void bar(Object o) {
        o.toString();
    }
}

class A {
    public String toString() {
        return "A";
    }
}
