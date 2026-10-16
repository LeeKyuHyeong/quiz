package com.kh.game.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 게임 설정 화면이 매 요청마다 읽던 것을 프로세스 안 캐시(Caffeine)에 둔다.
 *
 * - {@link #SONG_CATALOG}: 활성 곡 전체를 읽어 자바에서 집계하던 아티스트·연도 목록 (SongService)
 * - {@link #GENRES}: 게임용 장르 목록 (GenreService)
 *
 * 정확성은 쓰기 경로의 @CacheEvict 가 책임진다 (관리자 곡·장르 변경, 배치 복구, 곡을 비활성화하는 배치 3종).
 * expireAfterWrite 는 무효화를 빠뜨린 경로가 생겼을 때의 안전망이지 갱신 수단이 아니다.
 * 캐시 이름을 여기 고정해 두면 오타 난 이름으로 @Cacheable 을 붙였을 때 기동 시점에 실패한다.
 * 단일 인스턴스 전제 — 인스턴스가 늘면 인스턴스마다 따로 비워야 하므로 그때는 외부 캐시로 바꾼다.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String SONG_CATALOG = "songCatalog";
    public static final String GENRES = "genres";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(SONG_CATALOG, GENRES);
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(10))
                .maximumSize(100));
        return manager;
    }
}
