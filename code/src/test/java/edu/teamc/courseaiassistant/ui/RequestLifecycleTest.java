package edu.teamc.courseaiassistant.ui;

import com.intellij.openapi.project.Project;
import edu.teamc.courseaiassistant.application.RequestCoordinator;
import edu.teamc.courseaiassistant.context.CodeContext;
import edu.teamc.courseaiassistant.model.*;
import edu.teamc.courseaiassistant.sample.SampleScenario;
import edu.teamc.courseaiassistant.service.ChatService;
import edu.teamc.courseaiassistant.service.mock.MockChatService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Real Swing/coordinator regression checks with a Project lifetime proxy, not an IDE integration test. */
class RequestLifecycleTest {
    @Test void checkClearAndStaleReplies() throws Exception {
        ControlledService service = new ControlledService();
        try (Fixture fixture = new Fixture(service)) {
            edt(() -> fixture.send("first"));
            Call old = service.next();
            edt(() -> {
                require(fixture.clear().isEnabled(), "First pending request can be cleared");
                fixture.input().setText("next draft");
                require(!fixture.send().isEnabled(), "Draft does not re-enable send while waiting");
                fixture.input().getActionMap().get("sendQuestion").actionPerformed(null);
                fixture.clear().doClick(0);
                fixture.send("new question");
            });
            Call current = service.next();
            await(() -> old.future.isCancelled());
            flush();
            edt(() -> {
                require(!fixture.history().contains("Unexpected") && !fixture.history().contains("⚠"), "Cancellation is not an error");
                require(fixture.history().contains("Thinking…"), "New request stays loading after old cancellation");
                fixture.input().setText("third draft");
                require(!fixture.send().isEnabled(), "Old callback cannot re-enable send for new request");
                require(service.calls.isEmpty(), "Waiting guard prevents a duplicate service call");
                current.future.complete(answer(current.request, "CURRENT"));
            });
            awaitEdt(() -> fixture.history().contains("CURRENT"));
        }
    }

    @Test void checkQueuedSuccess() throws Exception {
        ControlledService service = new ControlledService();
        try (Fixture fixture = new Fixture(service)) {
            edt(() -> fixture.send("old")); Call old = service.next();
            // Wait until the coordinator has installed its service completion handler.
            await(() -> old.future.getNumberOfDependents() > 0);
            edt(() -> {
                old.future.complete(answer(old.request, "STALE_ANSWER"));
                fixture.clear().doClick(0);
                fixture.send("new");
            });
            Call current = service.next(); flush();
            edt(() -> {
                require(!fixture.history().contains("STALE_ANSWER"), "Queued success is invalidated by clear");
                require(fixture.history().contains("Thinking…"), "Queued success cannot finish a new request");
                current.future.complete(answer(current.request, "NEW_ANSWER"));
            });
            awaitEdt(() -> fixture.history().contains("NEW_ANSWER"));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void checkDisposal(boolean projectDisposal) throws Exception {
        AtomicBoolean disposed = new AtomicBoolean();
        ControlledService service = new ControlledService();
        RequestCoordinator coordinator = new RequestCoordinator(project(disposed), service);
        Recording callback = new Recording();
        try {
            edt(() -> coordinator.submitChatRequest(new ChatRequest("q", null), callback));
            Call call = service.next(); await(() -> call.future.getNumberOfDependents() > 0);
            edt(() -> {
                call.future.complete(answer(call.request, "late"));
                if (projectDisposal) disposed.set(true); else coordinator.dispose();
            });
            flush();
            require(callback.terminals.get() == 0, "No callback after project/content disposal");
        } finally { coordinator.dispose(); }
    }

    @Test void checkServiceFailures() throws Exception {
        for (MockChatService.Scenario scenario : List.of(MockChatService.Scenario.TIMEOUT, MockChatService.Scenario.NETWORK_ERROR)) {
            var coordinator = new RequestCoordinator(project(new AtomicBoolean()), new MockChatService(scenario, 1));
            Recording callback = new Recording();
            try {
                edt(() -> coordinator.submitChatRequest(new ChatRequest("q", null), callback)); callback.await();
                require(callback.failure.get().getCategory() == (scenario == MockChatService.Scenario.TIMEOUT
                        ? ServiceFailure.Category.TIMEOUT : ServiceFailure.Category.NETWORK_ERROR), "Preserve Mock failure category");
                require(callback.failure.get().isRetryable(), "Preserve retryability");
            } finally { coordinator.dispose(); }
        }
        var coordinator = new RequestCoordinator(project(new AtomicBoolean()), request -> CompletableFuture.failedFuture(
                new RuntimeException(new CompletionException(new ServiceFailure(request.getRequestId(),
                        ServiceFailure.Category.MODEL_ERROR, "Safe model error", false)))));
        Recording callback = new Recording();
        try {
            edt(() -> coordinator.submitChatRequest(new ChatRequest("q", null), callback)); callback.await();
            require(callback.failure.get().getCategory() == ServiceFailure.Category.MODEL_ERROR, "Unwrap nested failures");
            require(!callback.failure.get().isRetryable(), "Preserve non-retryable failures");
        } finally { coordinator.dispose(); }
    }

    @Test void checkDeadline() throws Exception {
        ControlledService service = new ControlledService();
        var coordinator = new RequestCoordinator(project(new AtomicBoolean()), service, Duration.ofMillis(200));
        Recording callback = new Recording();
        try {
            edt(() -> coordinator.submitChatRequest(new ChatRequest("never completes", null), callback));
            Call call = service.next(); callback.await();
            require(callback.failure.get().getCategory() == ServiceFailure.Category.TIMEOUT, "Real deadline produces TIMEOUT");
            await(() -> call.future.isCancelled());
            require(callback.terminals.get() == 1, "Timeout delivers one terminal result");
            require(!coordinator.hasPendingRequests(), "Timeout releases request tracking");
        } finally { coordinator.dispose(); }
    }

    @Test void deadlineSupplierIsReadPerRequestAndInvalidValuesRecover() throws Exception {
        ControlledService service = new ControlledService();
        var timeout = new AtomicReference<>(Duration.ZERO);
        var coordinator = new RequestCoordinator(project(new AtomicBoolean()), service, timeout::get);
        try {
            Recording invalid = new Recording();
            edt(() -> coordinator.submitChatRequest(new ChatRequest("invalid timeout", null), invalid));
            invalid.await();
            require(invalid.failure.get() != null, "Invalid deadline must release UI with a safe failure");
            require(service.calls.isEmpty(), "Invalid deadline cannot invoke service");
            timeout.set(Duration.ofMillis(100));
            Recording shortRequest = new Recording();
            edt(() -> coordinator.submitChatRequest(new ChatRequest("short", null), shortRequest));
            Call shortCall = service.next(); shortRequest.await();
            await(shortCall.future::isCancelled);
            timeout.set(Duration.ofSeconds(95));
            Recording longRequest = new Recording();
            edt(() -> coordinator.submitChatRequest(new ChatRequest("long", null), longRequest));
            Call longCall = service.next();
            longCall.future.complete(answer(longCall.request, "recovered")); longRequest.await();
            require(longRequest.failure.get() == null, "Updated deadline allows a subsequent request to succeed");
        } finally { coordinator.dispose(); }
    }

    @Test void checkStartupRecovery() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        AtomicBoolean onEdt = new AtomicBoolean();
        try (Fixture fixture = new Fixture(request -> {
            onEdt.set(SwingUtilities.isEventDispatchThread());
            if (starts.getAndIncrement() == 0) throw new IllegalStateException("sensitive-endpoint-detail");
            return CompletableFuture.completedFuture(answer(request, "RECOVERED"));
        })) {
            edt(() -> fixture.send("first"));
            awaitEdt(() -> fixture.history().contains("⚠"));
            edt(() -> {
                require(!onEdt.get(), "Service startup runs off the EDT");
                require(!fixture.history().contains("sensitive-endpoint-detail"), "Raw diagnostics stay out of user messages");
                fixture.send("retry");
            });
            awaitEdt(() -> fixture.history().contains("RECOVERED"));
        }
    }

    @Test void checkSampleSelectionAndContext() throws Exception {
        MockChatService service = new MockChatService(MockChatService.Scenario.SUCCESS_COURSE_GROUNDED, 1);
        AtomicReference<ChatRequest> received = new AtomicReference<>();
        var coordinator = new RequestCoordinator(project(new AtomicBoolean()), request -> { received.set(request); return service.ask(request); });
        AtomicReference<ChatPanel> ref = new AtomicReference<>();
        try {
            edt(() -> ref.set(new ChatPanel(coordinator, choice -> service.setScenario(choice == SampleScenario.COURSE_CITATION
                    ? MockChatService.Scenario.SUCCESS_COURSE_GROUNDED : MockChatService.Scenario.SUCCESS_GENERAL_KNOWLEDGE))));
            ChatPanel panel = ref.get();
            CodeContext context = new CodeContext("src/Main.java", "JAVA", "return value;", 3, 3, 42, null);
            edt(() -> {
                require(find(panel, "scenarioSelector", JComboBox.class) != null, "Sample tests retain their explicit selector");
                panel.attachCodeContext(context); find(panel, "questionInput", JTextArea.class).setText("explain");
                find(panel, "sendButton", JButton.class).doClick(0);
            });
            awaitEdt(() -> transcript(panel).contains("invented"));
            require(received.get().getCodeContext().equals(context), "Exact context snapshot reaches the service");
            edt(() -> {
                require(transcript(panel).contains("Course AI (fixed sample)"), "Async mock answer is marked as a fixed sample");
                require(transcript(panel).contains("Example sources (invented)"), "Mock sources remain identified as invented");
                require(!find(panel, "contextPreview", JPanel.class).isVisible(), "Sent context preview is consumed");
                find(panel, "clearChatButton", JButton.class).doClick(0);
                find(panel, "scenarioSelector", JComboBox.class).setSelectedItem(SampleScenario.GENERAL_KNOWLEDGE);
                find(panel, "questionInput", JTextArea.class).setText("general"); find(panel, "sendButton", JButton.class).doClick(0);
            });
            awaitEdt(() -> transcript(panel).contains("no specific course material is referenced"));
            edt(() -> require(!transcript(panel).contains("[S1]"), "General selection has no course citations"));
        } finally { edt(() -> ref.get().dispose()); }
    }

    @Test void checkInvalidReplies() throws Exception {
        for (boolean nullAnswer : List.of(true, false)) {
            var coordinator = new RequestCoordinator(project(new AtomicBoolean()), request -> CompletableFuture.completedFuture(
                    nullAnswer ? null : answer(new ChatRequest("other", null), "wrong request")));
            Recording callback = new Recording();
            try {
                edt(() -> coordinator.submitChatRequest(new ChatRequest("q", null), callback)); callback.await();
                require(callback.failure.get().getCategory() == ServiceFailure.Category.UNKNOWN, "Invalid response becomes a failure");
                require(callback.terminals.get() == 1, "Invalid response completes once");
            } finally { coordinator.dispose(); }
        }
    }

    private record Call(ChatRequest request, CompletableFuture<ChatAnswer> future) {}
    private static final class ControlledService implements ChatService {
        private final BlockingQueue<Call> calls = new LinkedBlockingQueue<>();
        public CompletableFuture<ChatAnswer> ask(ChatRequest request) {
            var future = new CompletableFuture<ChatAnswer>(); calls.add(new Call(request, future)); return future;
        }
        Call next() throws Exception {
            Call call = calls.poll(3, TimeUnit.SECONDS); require(call != null, "Service was invoked"); return call;
        }
    }
    private static final class Fixture implements AutoCloseable {
        private final RequestCoordinator coordinator;
        private final ChatPanel panel;
        Fixture(ChatService service) throws Exception {
            coordinator = new RequestCoordinator(project(new AtomicBoolean()), service);
            AtomicReference<ChatPanel> created = new AtomicReference<>();
            edt(() -> created.set(new ChatPanel(coordinator))); panel = created.get();
        }
        JTextArea input() { return find(panel, "questionInput", JTextArea.class); }
        JButton send() { return find(panel, "sendButton", JButton.class); }
        JButton clear() { return find(panel, "clearChatButton", JButton.class); }
        String history() { return transcript(panel); }
        void send(String text) { input().setText(text); send().doClick(0); }
        public void close() throws Exception { edt(panel::dispose); }
    }
    private static final class Recording implements RequestCoordinator.ChatCallback {
        private final AtomicReference<ServiceFailure> failure = new AtomicReference<>();
        private final AtomicInteger terminals = new AtomicInteger();
        private final CountDownLatch done = new CountDownLatch(1);
        public void onRequestStarted(String id) { require(SwingUtilities.isEventDispatchThread(), "Started callback uses EDT"); }
        public void onRequestCompleted(ChatAnswer answer) { finish(); }
        public void onRequestFailed(ServiceFailure error) { failure.set(error); finish(); }
        public void onRequestCancelled(String id) { finish(); }
        private void finish() { require(SwingUtilities.isEventDispatchThread(), "Terminal callback uses EDT"); terminals.incrementAndGet(); done.countDown(); }
        void await() throws Exception { require(done.await(3, TimeUnit.SECONDS), "Terminal callback received"); }
    }
    static Project project(AtomicBoolean disposed) {
        return (Project) Proxy.newProxyInstance(Project.class.getClassLoader(), new Class<?>[]{Project.class}, (p, method, args) ->
                method.getName().equals("isDisposed") ? disposed.get() : null);
    }
    private static ChatAnswer answer(ChatRequest request, String text) {
        return new ChatAnswer(request.getRequestId(), text, List.of(), ChatAnswer.Provenance.GENERAL_KNOWLEDGE, "General knowledge");
    }
    private static String transcript(ChatPanel panel) { return find(panel, "conversationTranscript", ConversationView.class).getText(); }
    private static <T extends Component> T find(Container parent, String name, Class<T> type) {
        for (Component child : parent.getComponents()) {
            if (name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container container) { T found = find(container, name, type); if (found != null) return found; }
        }
        return null;
    }
    static void edt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }
    private static void flush() throws Exception { edt(() -> {}); edt(() -> {}); }
    static void awaitEdt(BooleanSupplier condition) throws Exception {
        await(() -> { AtomicBoolean ready = new AtomicBoolean();
            try { edt(() -> ready.set(condition.getAsBoolean())); } catch (Exception e) { throw new RuntimeException(e); }
            return ready.get(); });
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("Timed out waiting for expected state");
            Thread.sleep(5);
        }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
