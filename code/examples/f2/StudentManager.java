import java.util.ArrayList;
import java.util.List;

/**
 * Demo B — Refactoring (Lec-3.2 Generics and Collections)
 *
 * Bug: raw List / Object parameters cause unchecked casts and runtime ClassCastException risk.
 *
 * Instruction:
 * "Refactor StudentManager to use generics (List<Student>) instead of raw types,
 *  eliminating unsafe casts. Preserve the class name, public method signatures,
 *  existing behavior, and the inner Student class."
 *
 * To run before refactor (works but uses raw types):
 *   java examples/f2/StudentManager.java
 */
public class StudentManager {
    private List students;

    public StudentManager() {
        students = new ArrayList();
    }

    public void addStudent(Object student) {
        students.add(student);
    }

    public String getStudentName(Object student) {
        return ((Student) student).getName();
    }

    public int count() {
        return students.size();
    }

    public static void main(String[] args) {
        StudentManager mgr = new StudentManager();
        mgr.addStudent(new Student("Alice"));
        mgr.addStudent(new Student("Bob"));
        System.out.println("Count: " + mgr.count());
        System.out.println("First: " + mgr.getStudentName(mgr.students.get(0)));
    }

    static class Student {
        private final String name;

        Student(String name) {
            this.name = name;
        }

        String getName() {
            return name;
        }
    }
}
