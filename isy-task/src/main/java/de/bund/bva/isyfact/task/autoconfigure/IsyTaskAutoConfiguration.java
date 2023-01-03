package de.bund.bva.isyfact.task.autoconfigure;

import io.micrometer.core.aop.TimedAspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import de.bund.bva.isyfact.aufrufkontext.AufrufKontextFactory;
import de.bund.bva.isyfact.aufrufkontext.AufrufKontextVerwalter;
import de.bund.bva.isyfact.sicherheit.Sicherheit;
import de.bund.bva.isyfact.task.config.IsyTaskConfigurationProperties;
import de.bund.bva.isyfact.task.konfiguration.HostHandler;
import de.bund.bva.isyfact.task.konfiguration.TaskKonfigurationVerwalter;
import de.bund.bva.isyfact.task.konfiguration.impl.LocalHostHandlerImpl;
import de.bund.bva.isyfact.task.monitoring.TaskMonitoringAspect;
import de.bund.bva.isyfact.task.sicherheit.AuthenticatorFactory;
import de.bund.bva.isyfact.task.sicherheit.impl.IsySicherheitAuthenticatorFactory;
import de.bund.bva.isyfact.task.sicherheit.impl.NoOpAuthenticatorFactory;

import io.micrometer.core.instrument.MeterRegistry;

@Configuration
@EnableConfigurationProperties
@EnableScheduling
public class IsyTaskAutoConfiguration {

    @Bean
    @ConfigurationProperties(prefix = "isy.task")
    public IsyTaskConfigurationProperties isyTaskConfigurationProperties() {
        return new IsyTaskConfigurationProperties();
    }

    @Bean
    public TaskKonfigurationVerwalter taskKonfigurationVerwalter(
        IsyTaskConfigurationProperties configurationProperties, AuthenticatorFactory authenticatorFactory) {
        return new TaskKonfigurationVerwalter(configurationProperties, authenticatorFactory);
    }

    @Bean
    @ConditionalOnMissingBean(HostHandler.class)
    public HostHandler localHostHandler() {
        return new LocalHostHandlerImpl();
    }

    @Bean
    public TaskMonitoringAspect taskMonitoringAspect(MeterRegistry registry, HostHandler hostHandler, TaskKonfigurationVerwalter taskKonfigurationVerwalter) {
        return new TaskMonitoringAspect(registry, hostHandler, taskKonfigurationVerwalter);
    }

    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }

    @Bean
    @ConditionalOnProperty(value = "isy.task.authentication.enabled", havingValue = "false", matchIfMissing = true)
    @ConditionalOnMissingBean(AuthenticatorFactory.class)
    public AuthenticatorFactory authenticatorFactoryNoOp() {
        return new NoOpAuthenticatorFactory();
    }

    @Bean
    @ConditionalOnProperty(value = "isy.task.authentication.enabled")
    @ConditionalOnMissingBean(AuthenticatorFactory.class)
    public AuthenticatorFactory authenticatorFactoryIsySicherheit(
        IsyTaskConfigurationProperties configurationProperties, Sicherheit sicherheit,
        AufrufKontextVerwalter aufrufKontextVerwalter, AufrufKontextFactory aufrufKontextFactory) {
        return new IsySicherheitAuthenticatorFactory(configurationProperties, sicherheit,
            aufrufKontextFactory, aufrufKontextVerwalter);
    }
}
