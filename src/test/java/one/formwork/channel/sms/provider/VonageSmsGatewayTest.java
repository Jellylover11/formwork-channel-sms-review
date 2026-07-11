package one.formwork.channel.sms.provider;

import one.formwork.channel.sms.api.SmsChannelProperties;
import one.formwork.channel.sms.api.SmsMessage;
import one.formwork.channel.sms.api.SmsResult;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VonageSmsGatewayTest {

    private SmsChannelProperties.VonageProperties createConfig() {
        SmsChannelProperties.VonageProperties config = new SmsChannelProperties.VonageProperties();
        config.setApiKey("test_key");
        config.setApiSecret("test_secret");
        config.setFromNumber("+4915100000000");
        return config;
    }

    private VonageSmsGateway createGateway() {
        return new VonageSmsGateway(createConfig());
    }

    @Test
    void supports_Vonage_ReturnsTrue() {
        assertTrue(createGateway().supports("VONAGE"));
    }

    @Test
    void supports_VonageCaseInsensitive_ReturnsTrue() {
        assertTrue(createGateway().supports("vonage"));
    }

    @Test
    void supports_OtherProvider_ReturnsFalse() {
        assertFalse(createGateway().supports("TWILIO"));
    }

    @Test
    void getProviderName_ReturnsExpected() {
        assertEquals("VONAGE", createGateway().getProviderName());
    }

    @Test
    void send_MultiSegmentMessage_ReturnsCorrectSegmentCount() {
        // Fake Vonage response with message-parts = 3
        Map<String, Object> fakeMessage = new HashMap<>();
        fakeMessage.put("status", "0");
        fakeMessage.put("message-id", "VM-MULTI-1");
        fakeMessage.put("message-parts", "3");

        Map<String, Object> fakeResponse = Map.of(
                "messages", List.of(fakeMessage)
        );

        // Mock the WebClient and its chain
        WebClient mockWebClient = Mockito.mock(WebClient.class);
        WebClient.RequestBodyUriSpec uriSpec = Mockito.mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestBodySpec bodySpec = Mockito.mock(WebClient.RequestBodySpec.class);
        WebClient.RequestHeadersSpec headersSpec = Mockito.mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = Mockito.mock(WebClient.ResponseSpec.class);

        Mockito.when(mockWebClient.post()).thenReturn(uriSpec);
        Mockito.when(uriSpec.uri("/sms/json")).thenReturn(bodySpec);
        Mockito.when(bodySpec.contentType(Mockito.any())).thenReturn(bodySpec);
        Mockito.when(bodySpec.bodyValue(Mockito.any())).thenReturn(headersSpec);
        Mockito.when(headersSpec.retrieve()).thenReturn(responseSpec);
        Mockito.when(responseSpec.bodyToMono(Map.class)).thenReturn(Mono.just(fakeResponse));

        // Use second constructor to inject fake WebClient
        VonageSmsGateway gateway = new VonageSmsGateway(createConfig(), mockWebClient);
        SmsMessage message = new SmsMessage(
                "+4915112345678",
                "A very long message that spans multiple segments",
                UUID.randomUUID()
        );

        SmsResult result = gateway.send(message);

        assertTrue(result.isSuccess());
        assertEquals(3, result.segmentCount());
    }
}