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

class AwsSnsSmsGatewayTest {

    private SmsChannelProperties.AwsSnsProperties createConfig() {
        SmsChannelProperties.AwsSnsProperties config = new SmsChannelProperties.AwsSnsProperties();
        config.setRegion("eu-central-1");
        config.setSenderId("TestApp");
        return new SmsChannelProperties.AwsSnsProperties();
    }

    private AwsSnsSmsGateway createGateway() {
        SmsChannelProperties.AwsSnsProperties config = new SmsChannelProperties.AwsSnsProperties();
        config.setRegion("eu-central-1");
        config.setSenderId("TestApp");
        return new AwsSnsSmsGateway(config);
    }

    @Test
    void supports_AwsSns_ReturnsTrue() {
        assertTrue(createGateway().supports("AWS_SNS"));
    }

    @Test
    void supports_AwsSnsCaseInsensitive_ReturnsTrue() {
        assertTrue(createGateway().supports("aws_sns"));
    }

    @Test
    void supports_OtherProvider_ReturnsFalse() {
        assertFalse(createGateway().supports("TWILIO"));
    }

    @Test
    void getProviderName_ReturnsExpected() {
        assertEquals("AWS_SNS", createGateway().getProviderName());
    }
}