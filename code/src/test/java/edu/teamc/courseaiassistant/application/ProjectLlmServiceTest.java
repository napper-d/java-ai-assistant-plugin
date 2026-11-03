package edu.teamc.courseaiassistant.application;

import com.intellij.openapi.project.Project;
import edu.teamc.courseaiassistant.model.ChatRequest;
import edu.teamc.courseaiassistant.settings.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

class ProjectLlmServiceTest {
    @TempDir Path root;

    @Test void saveIsLazyAndRagEventsInvalidateChatWhileSettingsReplacementOwnsModelLifetime() throws Exception {
        var llm = new LlmSettings();
        var ragSettings = new RagSettings();
        AtomicReference<ProjectRagService> ragRef = new AtomicReference<>();
        Project project = (Project) Proxy.newProxyInstance(Project.class.getClassLoader(), new Class<?>[]{Project.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getService" -> args[0] == LlmSettings.class ? llm : args[0] == RagSettings.class ? ragSettings : ragRef.get();
            case "getBasePath" -> root.toString();
            case "isDisposed", "isDefault" -> false;
            case "toString" -> "ProjectLlmServiceTestProject";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        });
        var rag = new ProjectRagService(project); ragRef.set(rag);
        AtomicInteger opens = new AtomicInteger(), invalidations = new AtomicInteger();
        BlockingQueue<LlmRuntimeTest.Session> sessions = new LinkedBlockingQueue<>();
        var model = new ProjectLlmService(project, () -> {
            opens.incrementAndGet(); var session = new LlmRuntimeTest.Session(); sessions.add(session); return session;
        });
        Runnable unsubscribe = model.onChatInvalidated(invalidations::incrementAndGet);
        try {
            var configured = llm.getState(); configured.timeoutMillis = 90_000;
            model.updateSettings(configured);
            assertEquals(0, opens.get(), "Saving must not open Password Safe or an HTTP client");
            assertEquals(95_000, model.coordinatorTimeout().toMillis());
            var chat = model.ask(new ChatRequest("q", null));
            var first = sessions.poll(5, TimeUnit.SECONDS); assertNotNull(first);
            assertNotNull(first.chats.poll(5, TimeUnit.SECONDS));
            rag.updateSettings(ragSettings.getState());
            assertTrue(chat.isCancelled());
            assertEquals(0, first.closes.get(), "Retrieval changes must not close the shared model client");
            var next = model.ask(new ChatRequest("new", null));
            assertNotNull(first.chats.poll(5, TimeUnit.SECONDS));
            Path pdf = Files.writeString(root.resolve("registered.pdf"), "registration fixture");
            rag.registerMaterials(List.of(pdf)).get(5, TimeUnit.SECONDS);
            // Library notifications are completion listeners; wait for their cancellation to reach the wrapper.
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!next.isCancelled() && System.nanoTime() < until) Thread.sleep(5);
            assertTrue(next.isCancelled());
            model.updateSettings(configured);
            assertEquals(1, first.closes.get());
            assertEquals(1, opens.get());
            model.ask(new ChatRequest("after replacement", null));
            var second = sessions.poll(5, TimeUnit.SECONDS); assertNotNull(second);
            assertNotNull(second.chats.poll(5, TimeUnit.SECONDS));
            unsubscribe.run();
            int previous = invalidations.get();
            rag.updateSettings(ragSettings.getState());
            assertEquals(previous, invalidations.get());
            model.dispose();
            assertEquals(1, second.closes.get());
            assertThrows(ExecutionException.class, () -> model.ask(new ChatRequest("closed", null)).get());
        } finally { unsubscribe.run(); model.dispose(); rag.dispose(); }
    }
    @Test void liveBindingInvalidatesQueuedSuccessOnModelOrRagChangeAndDisposal() throws Exception {
        for (String change : List.of("model", "rag", "content", "project")) {
            var settings = new LlmSettings(); var ragSettings = new RagSettings();
            var ragRef = new AtomicReference<ProjectRagService>(); var disposed = new AtomicBoolean();
            Project project = (Project) Proxy.newProxyInstance(Project.class.getClassLoader(), new Class<?>[]{Project.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getService" -> args[0] == LlmSettings.class ? settings : args[0] == RagSettings.class ? ragSettings : ragRef.get();
                case "getBasePath" -> root.toString(); case "isDisposed" -> disposed.get(); case "isDefault" -> false;
                case "toString" -> "QueuedLiveChatProject"; case "hashCode" -> System.identityHashCode(proxy); case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
            var rag = new ProjectRagService(project); ragRef.set(rag);
            var session = new LlmRuntimeTest.Session();
            var model = new ProjectLlmService(project, () -> session);
            var binding = new LiveChatSession(project, model);
            var request = new ChatRequest("question", null);
            AtomicInteger successes = new AtomicInteger(), cancellations = new AtomicInteger();
            var callback = new RequestCoordinator.ChatCallback() {
                public void onRequestStarted(String id) { }
                public void onRequestCompleted(edu.teamc.courseaiassistant.model.ChatAnswer answer) { successes.incrementAndGet(); }
                public void onRequestFailed(edu.teamc.courseaiassistant.model.ServiceFailure failure) { fail(failure.getMessage()); }
                public void onRequestCancelled(String id) { cancellations.incrementAndGet(); }
            };
            try {
                javax.swing.SwingUtilities.invokeAndWait(() -> binding.coordinator().submitChatRequest(request, callback));
                var child = session.chats.poll(5, TimeUnit.SECONDS); assertNotNull(child);
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (child.getNumberOfDependents() == 0 && System.nanoTime() < until) Thread.sleep(5);
                assertTrue(child.getNumberOfDependents() > 0);
                javax.swing.SwingUtilities.invokeAndWait(() -> {
                    child.complete(new edu.teamc.courseaiassistant.model.ChatAnswer(request.getRequestId(), "stale", List.of(),
                            edu.teamc.courseaiassistant.model.ChatAnswer.Provenance.GENERAL_KNOWLEDGE, "general"));
                    switch (change) {
                        case "model" -> model.updateSettings(settings.getState());
                        case "rag" -> rag.updateSettings(ragSettings.getState());
                        case "content" -> binding.dispose();
                        default -> { disposed.set(true); model.dispose(); }
                    }
                });
                javax.swing.SwingUtilities.invokeAndWait(() -> { });
                assertEquals(0, successes.get(), change + " must suppress queued success");
                if (change.equals("model") || change.equals("rag")) assertEquals(1, cancellations.get());
                if (change.equals("content")) assertEquals(0, session.closes.get(), "Content must not own the model session");
            } finally { binding.dispose(); model.dispose(); rag.dispose(); }
        }
    }

}
