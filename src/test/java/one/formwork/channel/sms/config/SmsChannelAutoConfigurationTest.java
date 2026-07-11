package one.formwork.channel.sms.config;

import one.formwork.channel.sms.api.SmsChannelProperties;
import one.formwork.channel.sms.api.SmsGateway;
import one.formwork.channel.sms.api.TenantSmsProviderRegistry;
import one.formwork.channel.sms.cost.SmsCostService;
import one.formwork.channel.sms.provider.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SmsChannelAutoConfigurationTest {

    private SmsChannelProperties createProperties() {
        SmsChannelProperties props = new SmsChannelProperties();
        props.setProvider("TWILIO");

        SmsChannelProperties.TwilioProperties twilio = new SmsChannelProperties.TwilioProperties();
        twilio.setAccountSid("test");
        twilio.setAuthToken("test");
        twilio.setFromNumber("+1234567890");
        props.setTwilio(twilio);

        SmsChannelProperties.VonageProperties vonage = new SmsChannelProperties.VonageProperties();
        vonage.setApiKey("test");
        vonage.setApiSecret("test");
        vonage.setFromNumber("+1234567890");
        props.setVonage(vonage);

        SmsChannelProperties.AwsSnsProperties awsSns = new SmsChannelProperties.AwsSnsProperties();
        awsSns.setRegion("eu-central-1");
        awsSns.setSenderId("test");
        props.setAwsSns(awsSns);

        SmsChannelProperties.BudgetSmsProperties budgetSms = new SmsChannelProperties.BudgetSmsProperties();
        budgetSms.setUsername("test");
        budgetSms.setPassword("test");
        budgetSms.setOriginator("test");
        props.setBudgetSms(budgetSms);

        SmsChannelProperties.MessageBirdProperties messagebird = new SmsChannelProperties.MessageBirdProperties();
        messagebird.setAccessKey("test");
        messagebird.setOriginator("test");
        props.setMessagebird(messagebird);

        return props;
    }

    @Test
    void allFiveGatewaysAreRegistered() {
        SmsChannelAutoConfiguration config = new SmsChannelAutoConfiguration();
        SmsChannelProperties props = createProperties();

        List<SmsGateway> gateways = List.of(
                config.twilioGateway(props),
                config.vonageGateway(props),
                config.awsSnsGateway(props),
                config.budgetSmsGateway(props),
                config.messageBirdGateway(props)
        );

        assertThat(gateways).hasSize(5);
    }

    @Test
    void allProviderNamesArePresent() {
        SmsChannelAutoConfiguration config = new SmsChannelAutoConfiguration();
        SmsChannelProperties props = createProperties();

        List<String> providerNames = List.of(
                config.twilioGateway(props).getProviderName(),
                config.vonageGateway(props).getProviderName(),
                config.awsSnsGateway(props).getProviderName(),
                config.budgetSmsGateway(props).getProviderName(),
                config.messageBirdGateway(props).getProviderName()
        );

        assertThat(providerNames).containsExactlyInAnyOrder(
                "TWILIO", "VONAGE", "AWS_SNS", "BUDGET_SMS", "MESSAGEBIRD"
        );
    }

    @Test
    void eachGatewaySupportsItsOwnProvider() {
        SmsChannelAutoConfiguration config = new SmsChannelAutoConfiguration();
        SmsChannelProperties props = createProperties();

        assertThat(config.twilioGateway(props).supports("TWILIO")).isTrue();
        assertThat(config.vonageGateway(props).supports("VONAGE")).isTrue();
        assertThat(config.awsSnsGateway(props).supports("AWS_SNS")).isTrue();
        assertThat(config.budgetSmsGateway(props).supports("BUDGET_SMS")).isTrue();
        assertThat(config.messageBirdGateway(props).supports("MESSAGEBIRD")).isTrue();
    }
}