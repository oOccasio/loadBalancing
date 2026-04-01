package com.alb.config;

import com.alb.analyzer.AnalyzerConfig;
import com.alb.engine.SwitchPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({AnalyzerConfig.class, SwitchPolicy.class})
public class EngineConfig {
    // AnalyzerConfig and SwitchPolicy are registered as beans by @EnableConfigurationProperties
}
