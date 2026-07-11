package one.formwork.channel.sms.provider;

import one.formwork.channel.sms.api.SmsChannelProperties;
import one.formwork.channel.sms.api.SmsMessage;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AwsSnsSmsGatewaySigningTest {

    private SmsChannelProperties.AwsSnsProperties createConfig() {
        SmsChannelProperties.AwsSnsProperties config = new SmsChannelProperties.AwsSnsProperties();
        config.setRegion("eu-central-1");
        config.setSenderId("TestApp");
        config.setAccessKey("AKIAIOSFODNN7EXAMPLE");
        config.setSecretKey("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");
        return config;
    }

    private WebClient mockPostChain(String responseXml) {
        WebClient mockWebClient = mock(WebClient.class);
        WebClient.RequestBodyUriSpec postSpec = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
        WebClient.RequestHeadersSpec headersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

        when(mockWebClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
        when(bodySpec.bodyValue(anyString())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(responseXml));

        return mockWebClient;
    }

    @Test
    void send_UsesPostNotGet() {
        String fakeXml = "<PublishResponse><PublishResult>"
                + "<MessageId>test-msg-id</MessageId>"
                + "</PublishResult></PublishResponse>";

        WebClient mockWebClient = mockPostChain(fakeXml);
        AwsSnsSmsGateway gateway = new AwsSnsSmsGateway(createConfig(), mockWebClient);
        gateway.send(new SmsMessage("+4915112345678", "Hello", UUID.randomUUID()));

        verify(mockWebClient).post();
        verify(mockWebClient, never()).get();
    }

    @Test
    void send_PostBodyContainsRequiredParams() {
        String fakeXml = "<PublishResponse><PublishResult>"
                + "<MessageId>test-msg-id</MessageId>"
                + "</PublishResult></PublishResponse>";

        WebClient mockWebClient = mock(WebClient.class);
        WebClient.RequestBodyUriSpec postSpec = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
        WebClient.RequestHeadersSpec headersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

        org.mockito.ArgumentCaptor<String> bodyCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);

        when(mockWebClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
        when(bodySpec.bodyValue(bodyCaptor.capture())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(fakeXml));

        AwsSnsSmsGateway gateway = new AwsSnsSmsGateway(createConfig(), mockWebClient);
        gateway.send(new SmsMessage("+4915112345678", "Hello", UUID.randomUUID()));

        String capturedBody = bodyCaptor.getValue();
        assertNotNull(capturedBody);
        assertFalse(capturedBody.isEmpty(), "POST body must not be empty");
        assertTrue(capturedBody.contains("Action=Publish"), "Body must contain Action=Publish");
        assertTrue(capturedBody.contains("PhoneNumber"), "Body must contain PhoneNumber");
    }

    @Test
    void send_SuccessfulResponse_ReturnsSuccess() {
        String fakeXml = "<PublishResponse><PublishResult>"
                + "<MessageId>sns-msg-123</MessageId>"
                + "</PublishResult></PublishResponse>";

        WebClient mockWebClient = mockPostChain(fakeXml);
        AwsSnsSmsGateway gateway = new AwsSnsSmsGateway(createConfig(), mockWebClient);
        var result = gateway.send(
                new SmsMessage("+4915112345678", "Hello", UUID.randomUUID()));

        assertTrue(result.isSuccess());
        assertEquals("sns-msg-123", result.messageId());
        assertEquals("AWS_SNS", result.provider());
    }
}