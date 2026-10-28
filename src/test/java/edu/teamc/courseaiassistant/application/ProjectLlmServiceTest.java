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
}
