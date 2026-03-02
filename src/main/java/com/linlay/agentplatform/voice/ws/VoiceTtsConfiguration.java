package com.linlay.agentplatform.voice.ws;

import com.linlay.agentplatform.config.VoiceTtsProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VoiceTtsConfiguration {

    @Bean
    public VoicePcmSynthesizer voicePcmSynthesizer(VoiceTtsProperties voiceTtsProperties) {
        if ("aliyun".equals(voiceTtsProperties.normalizedProvider())) {
            return new AliyunRealtimeVoicePcmSynthesizer(voiceTtsProperties);
        }
        return new SyntheticVoicePcmSynthesizer();
    }
}
