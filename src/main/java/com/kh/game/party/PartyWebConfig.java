package com.kh.game.party;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 사진·캡처를 /admin/party/images/** 로 내준다. /admin/** 아래라 관리자만 본다.
 * 폴더(party.image-dir)가 설정되지 않았으면 경로를 만들지 않는다.
 */
@Configuration
@RequiredArgsConstructor
public class PartyWebConfig implements WebMvcConfigurer {

    private final PartyImageStore imageStore;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (imageStore.directory() == null) {
            return;
        }
        String location = imageStore.directory().toUri().toString();
        registry.addResourceHandler("/admin/party/images/**")
                .addResourceLocations(location.endsWith("/") ? location : location + "/");
    }
}
