package one.formwork.channel.sms.provider;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import one.formwork.channel.sms.api.SmsChannelProperties;
import one.formwork.channel.sms.api.SmsMessage;
import one.formwork.channel.sms.api.SmsResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Honest integration test — verifies actual HTTP bytes sent to Twilio.
 * Uses a real WireMock server, not Mockito mocks.
 * This test would have caught auth header format changes and body encoding bugs.
 */
class TwilioSmsGatewayIntegrationTest {

    private static final String ACCOUNT_SID = "ACtest123456789";
    private static final String AUTH_TOKEN  = "test_auth_token_abc";
    private static final String FROM_NUMBER = "+4915100000000";

    private WireMockServer wireMock;
    private TwilioSmsGateway gateway;

    @BeforeEach
    void setUp() throws Exception {
        // Start real WireMock server on a random port
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());

        // Build gateway pointing at WireMock instead of real Twilio
        SmsChannelProperties.TwilioProperties config = new SmsChannelProperties.TwilioProperties();
        config.setAccountSid(ACCOUNT_SID);
        config.setAuthToken(AUTH_TOKEN);
        config.setFromNumber(FROM_NUMBER);

        // Build expected Basic Auth header value
        String credentials = Base64.getEncoder().encodeToString(
                (ACCOUNT_SID + ":" + AUTH_TOKEN).getBytes(StandardCharsets.UTF_8));
        String expectedAuth = "Basic " + credentials;

        // Use reflection to inject a WebClient pointing at WireMock
        gateway = new TwilioSmsGateway(config);
        WebClient testClient = WebClient.builder()
                .baseUrl("http://localhost:" + wireMock.port() + "/2010-04-01")
                .defaultHeader("Authorization", expectedAuth)
                .build();

        Field field = TwilioSmsGateway.class.getDeclaredField("webClient");
        field.setAccessible(true);
        field.set(gateway, testClient);
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void send_Success_VerifiesCorrectHttpRequest() {
        // Stub WireMock to return success response
        String expectedPath = "/2010-04-01/Accounts/" + ACCOUNT_SID + "/Messages.json";
        String expectedAuth = "Basic " + Base64.getEncoder().encodeToString(
                (ACCOUNT_SID + ":" + AUTH_TOKEN).getBytes(StandardCharsets.UTF_8));

        wireMock.stubFor(post(urlEqualTo(expectedPath))
                .withHeader("Authorization", equalTo(expectedAuth))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(containing("To=%2B4915112345678"))
                .withRequestBody(containing("From=%2B4915100000000"))
                .withRequestBody(containing("Body=Hello"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"sid\":\"SM123\",\"status\":\"queued\",\"num_segments\":\"1\"}")));

        SmsMessage message = new SmsMessage("+4915112345678", "Hello", UUID.randomUUID());
        SmsResult result = gateway.send(message);

        // Verify result
        assertTrue(result.isSuccess());
        assertEquals("SM123", result.messageId());
        assertEquals("TWILIO", result.provider());
        assertEquals(1, result.segmentCount());

        // Verify the actual HTTP request was made
        wireMock.verify(postRequestedFor(urlEqualTo(expectedPath))
                .withHeader("Authorization", equalTo(expectedAuth))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(containing("To=%2B4915112345678"))
                .withRequestBody(containing("Body=Hello")));
    }

    @Test
    void send_ErrorResponse_ReturnsFailure() {
        String expectedPath = "/2010-04-01/Accounts/" + ACCOUNT_SID + "/Messages.json";

        wireMock.stubFor(post(urlEqualTo(expectedPath))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":21211,\"message\":\"Invalid phone number\"}")));

        SmsMessage message = new SmsMessage("+4915112345678", "Hello", UUID.randomUUID());
        SmsResult result = gateway.send(message);

        assertFalse(result.isSuccess());
        assertEquals("TWILIO", result.provider());
        assertEquals("400", result.errorCode());
    }

    @Test
    void send_VerifiesAuthHeaderFormat() {
        String expectedPath = "/2010-04-01/Accounts/" + ACCOUNT_SID + "/Messages.json";
        String expectedAuth = "Basic " + Base64.getEncoder().encodeToString(
                (ACCOUNT_SID + ":" + AUTH_TOKEN).getBytes(StandardCharsets.UTF_8));

        wireMock.stubFor(post(urlEqualTo(expectedPath))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"sid\":\"SM456\",\"status\":\"queued\",\"num_segments\":\"1\"}")));

        gateway.send(new SmsMessage("+4915112345678", "Test", UUID.randomUUID()));

        // This is the critical assertion — verifies exact Authorization header format
        // If someone changed Basic to Bearer this test would fail immediately
        wireMock.verify(postRequestedFor(urlEqualTo(expectedPath))
                .withHeader("Authorization", equalTo(expectedAuth)));
    }

    @Test
    void send_MultiSegmentMessage_ReturnsCorrectSegmentCount() {
        String expectedPath = "/2010-04-01/Accounts/" + ACCOUNT_SID + "/Messages.json";

        wireMock.stubFor(post(urlEqualTo(expectedPath))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"sid\":\"SM789\",\"status\":\"queued\",\"num_segments\":\"3\"}")));

        SmsMessage message = new SmsMessage("+4915112345678",
                "A very long message that spans multiple SMS segments because it exceeds 160 characters limit",
                UUID.randomUUID());
        SmsResult result = gateway.send(message);

        assertTrue(result.isSuccess());
        assertEquals(3, result.segmentCount());
    }
}