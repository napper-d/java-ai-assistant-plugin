package edu.teamc.courseaiassistant.modification;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import edu.teamc.courseaiassistant.context.CodeContext;
import edu.teamc.courseaiassistant.llm.*;
import edu.teamc.courseaiassistant.model.*;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class LlmF2IntegrationTest {
    @Test void httpGenerationProducesProposalAcceptedByMemberFour() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            try {
                var input=JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
                assertFalse(input.get("stream").getAsBoolean());
                JsonObject proposal=new JsonObject();
                proposal.addProperty("path","src/SumDemo.java");
                proposal.addProperty("proposedFileText",SampleCodeGenerationService.BUG.replace("a - b","a + b"));
                proposal.addProperty("summary","Fix addition");
                JsonObject message=new JsonObject(); message.addProperty("content",proposal.toString());
                JsonObject choice=new JsonObject(); choice.add("message",message); choice.addProperty("finish_reason","stop");
                JsonArray choices=new JsonArray(); choices.add(choice);
                JsonObject response=new JsonObject(); response.add("choices",choices);
                byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes);
            } finally {exchange.close();}
        });
        server.start();
        try {
            var config=new LlmConfig(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/v1"),"local-fixture","fixture-key",Duration.ofSeconds(5),4096,60000);
            var request=new ModificationRequest("Fix sum",new CodeContext("src/SumDemo.java","JAVA","return a - b;",3,3,42,null),SampleCodeGenerationService.BUG);
            try(var client=new HttpLlmClient(config); var generator=new LlmCodeGenerationService(client,config)) {
                var result=generator.generate(request).get(10,TimeUnit.SECONDS);
                new ProposalValidator().validate(request,result);
                assertEquals(request.requestId(),result.requestId());
                assertEquals(request.originalFileText(),result.change().originalFileText());
                assertTrue(result.change().proposedFileText().contains("return a + b;"));
            }
        } finally {server.stop(0);}
    }
    @Test void safeGenerationFailuresKeepCategoryAndRetryability() {
        var original=new ServiceFailure("old",ServiceFailure.Category.NETWORK_ERROR,"Provider temporarily unavailable.",true);
        var failure=assertInstanceOf(ServiceFailure.class,GenerationFailures.toFailure("active",new CompletionException(original)));
        assertEquals("active",failure.getRequestId());
        assertEquals(original.getCategory(),failure.getCategory());
        assertTrue(failure.isRetryable());
        assertInstanceOf(CancellationException.class,GenerationFailures.toFailure("active",new CancellationException()));
        var unknown=assertInstanceOf(ServiceFailure.class,GenerationFailures.toFailure("active",new RuntimeException("secret-url")));
        assertFalse(unknown.getMessage().contains("secret-url"));
    }
}
