package edu.teamc.courseaiassistant.application;

import edu.teamc.courseaiassistant.context.CodeContext;
import edu.teamc.courseaiassistant.model.*;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class LlmRuntimeTest {
    @Test void startupIsLazyOffEdtAndSharedByConcurrentConsumers() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        CountDownLatch opening = new CountDownLatch(1), unlock = new CountDownLatch(1);
        Session session = new Session();
        try (var runtime = new LlmRuntime(() -> {
            assertFalse(SwingUtilities.isEventDispatchThread());
            opens.incrementAndGet(); opening.countDown(); await(unlock); return session;
        })) {
            assertEquals(0, opens.get());
            AtomicReference<CompletableFuture<ChatAnswer>> chat = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> chat.set(runtime.ask(new ChatRequest("question", null))));
            assertTrue(opening.await(5, TimeUnit.SECONDS));
            var generation = runtime.generate(modification());
            chat.get().cancel(true); // This consumer must not interrupt another consumer's unlock.
            unlock.countDown();
            assertSame(session.proposal, session.generations.poll(5, TimeUnit.SECONDS));
            assertEquals(1, opens.get());
            assertFalse(generation.isDone());
            generation.cancel(true);
            until(session.proposal::isCancelled);
            assertEquals(0, session.closes.get());
        } finally { unlock.countDown(); }
        assertEquals(1, session.closes.get());
    }

    @Test void cancellingOneRequestDoesNotCloseSharedTransportOrCancelOtherRequests() throws Exception {
        Session session = new Session();
        try (var runtime = new LlmRuntime(() -> session)) {
            var chat = runtime.ask(new ChatRequest("q", null));
            var child = session.chats.poll(5, TimeUnit.SECONDS);
            assertNotNull(child);
            var generation = runtime.generate(modification());
            assertNotNull(session.generations.poll(5, TimeUnit.SECONDS));
            chat.cancel(true);
            until(child::isCancelled);
            assertFalse(generation.isDone());
            assertEquals(0, session.closes.get());
        }
        assertTrue(session.proposal.isCancelled());
        assertEquals(1, session.closes.get());
    }

    @Test void retrievalChangesCancelChatOnlyAndReconfigurationCancelsBoth() throws Exception {
        BlockingQueue<Session> sessions = new LinkedBlockingQueue<>();
        Session first = new Session(), second = new Session();
        sessions.add(first); sessions.add(second);
        try (var runtime = new LlmRuntime(sessions::remove)) {
            var chat = runtime.ask(new ChatRequest("q", null));
            assertNotNull(first.chats.poll(5, TimeUnit.SECONDS));
            var generation = runtime.generate(modification());
            assertNotNull(first.generations.poll(5, TimeUnit.SECONDS));
            runtime.cancelChats();
            assertTrue(chat.isCancelled());
            assertFalse(generation.isDone());
            runtime.invalidate();
            assertTrue(generation.isCancelled());
            assertEquals(1, first.closes.get());
            var next = runtime.ask(new ChatRequest("new", null));
            assertNotNull(second.chats.poll(5, TimeUnit.SECONDS));
            assertFalse(next.isDone());
        }
        assertEquals(1, first.closes.get());
        assertEquals(1, second.closes.get());
    }

    @Test void staleInitializationReturningAfterReconfigurationIsClosedAndCannotRunARequest() throws Exception {
        CountDownLatch opening = new CountDownLatch(1), unlock = new CountDownLatch(1);
        AtomicInteger opens = new AtomicInteger();
        Session stale = new Session(), fresh = new Session();
        try (var runtime = new LlmRuntime(() -> {
            if (opens.incrementAndGet() == 1) { opening.countDown(); await(unlock); return stale; }
            return fresh;
        })) {
            var old = runtime.ask(new ChatRequest("old", null));
            assertTrue(opening.await(5, TimeUnit.SECONDS));
            runtime.invalidate();
            assertTrue(old.isCancelled());
            runtime.ask(new ChatRequest("new", null));
            assertNotNull(fresh.chats.poll(5, TimeUnit.SECONDS));
            unlock.countDown();
            until(() -> stale.closes.get() == 1);
            assertTrue(stale.chats.isEmpty());
        } finally { unlock.countDown(); }
    }

    @Test void closingDuringCredentialUnlockDiscardsLateResourcesAndRejectsNewRequests() throws Exception {
        CountDownLatch opening = new CountDownLatch(1), unlock = new CountDownLatch(1);
        Session late = new Session();
        var runtime = new LlmRuntime(() -> {
            opening.countDown();
            // Simulate an OS credential prompt that ignores interruption.
            boolean interrupted = false;
            while (true) {
                try { unlock.await(); break; }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
            return late;
        });
        try {
            var chat = runtime.ask(new ChatRequest("q", null));
            assertTrue(opening.await(5, TimeUnit.SECONDS));
            runtime.close();
            assertTrue(chat.isCancelled());
            unlock.countDown();
            until(() -> late.closes.get() == 1);
            assertTrue(late.chats.isEmpty());
            assertThrows(ExecutionException.class, () -> runtime.ask(new ChatRequest("closed", null)).get());
        } finally { unlock.countDown(); runtime.close(); }
    }

    @Test void failedStartupIsSafeAndCanBeRetried() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        Session session = new Session();
        try (var runtime = new LlmRuntime(() -> {
            if (opens.incrementAndGet() == 1) throw new IllegalStateException("secret endpoint/key");
            return session;
        })) {
            ChatRequest request = new ChatRequest("q", null);
            var failure = (ServiceFailure) assertThrows(ExecutionException.class, () -> runtime.ask(request).get(5, TimeUnit.SECONDS)).getCause();
            assertEquals(request.getRequestId(), failure.getRequestId());
            assertFalse(failure.getMessage().contains("secret"));
            var retry = runtime.ask(new ChatRequest("retry", null));
            assertNotNull(session.chats.poll(5, TimeUnit.SECONDS));
            assertFalse(retry.isDone());
        }
    }

    private static ModificationRequest modification() {
        return new ModificationRequest("fix", new CodeContext("Demo.java", "JAVA", "class Demo {}", 1, 1, 0, null), "class Demo {}");
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new CancellationException(); }
    }

    private static void until(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }

    static final class Session implements LlmRuntime.Session {
        final BlockingQueue<CompletableFuture<ChatAnswer>> chats = new LinkedBlockingQueue<>();
        final BlockingQueue<CompletableFuture<ChangeProposal>> generations = new LinkedBlockingQueue<>();
        final CompletableFuture<ChangeProposal> proposal = new CompletableFuture<>();
        final AtomicInteger closes = new AtomicInteger();
        @Override public CompletableFuture<ChatAnswer> ask(ChatRequest request) {
            assertFalse(SwingUtilities.isEventDispatchThread());
            var child = new CompletableFuture<ChatAnswer>(); chats.add(child); return child;
        }
        @Override public CompletableFuture<ChangeProposal> generate(ModificationRequest request) {
            generations.add(proposal); return proposal;
        }
        @Override public void close() { closes.incrementAndGet(); }
    }
}
