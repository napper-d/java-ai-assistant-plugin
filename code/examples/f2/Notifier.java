/**
 * Demo A — Interface vs Abstract Class (Lec-2 OOP)
 *
 * Select the three type declarations below and ask:
 * "When should I use an interface versus an abstract class in Java?
 *  Use this Notifier example to explain."
 *
 * Expected citations: Lec-2-OOP-with-Java.pdf (class declaration syntax,
 * implements/extends distinction, partial implementation via abstract class).
 */
public interface Notifier {
    void send(String message);
}

abstract class BaseNotifier implements Notifier {
    private final String prefix;

    BaseNotifier(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public void send(String message) {
        doSend(prefix + ": " + message);
    }

    protected abstract void doSend(String fullMessage);
}

class EmailNotifier extends BaseNotifier {
    private final String address;

    EmailNotifier(String address) {
        super("EMAIL");
        this.address = address;
    }

    @Override
    protected void doSend(String fullMessage) {
        System.out.println("To " + address + ": " + fullMessage);
    }
}
