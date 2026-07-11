package one.formwork.channel.sms.config;

import one.formwork.channel.sms.api.*;
import one.formwork.channel.sms.cost.SmsCostService;
import one.formwork.channel.sms.provider.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import one.formwork.base.tenant.config.TenantBaseAutoConfiguration;

@AutoConfiguration(after = TenantBaseAutoConfiguration.class)
@ConditionalOnProperty(prefix = "formwork.sms-channel", name = "provider")
@EnableConfigurationProperties(SmsChannelProperties.class)
public class SmsChannelAutoConfiguration {

    @Bean
    public SmsGateway twilioGateway(SmsChannelProperties props) {
        return new TwilioSmsGateway(props.getTwilio());
    }

    @Bean
    public SmsGateway vonageGateway(SmsChannelProperties props) {
        return new VonageSmsGateway(props.getVonage());
    }

    @Bean
    public SmsGateway awsSnsGateway(SmsChannelProperties props) {
        return new AwsSnsSmsGateway(props.getAwsSns());
    }

    @Bean
    public SmsGateway budgetSmsGateway(SmsChannelProperties props) {
        return new BudgetSmsGateway(props.getBudgetSms());
    }

    @Bean
    public SmsGateway messageBirdGateway(SmsChannelProperties props) {
        return new MessageBirdSmsGateway(props.getMessagebird());
    }

    @Bean
    public TenantSmsProviderRegistry tenantSmsProviderRegistry() {
        return new TenantSmsProviderRegistry();
    }

    @Bean
    public SmsChannelService smsChannelService(
            java.util.List<SmsGateway> gateways,
            SmsChannelProperties properties,
            SmsCostService costService,
            TenantSmsProviderRegistry tenantRegistry) {
        return new SmsChannelService(gateways, properties, costService, tenantRegistry);
    }
}