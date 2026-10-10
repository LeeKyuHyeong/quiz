package com.kh.game.batch;

import com.kh.game.entity.BatchConfig;
import com.kh.game.service.BatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 배치 스케줄 켜고 끄기(game.batch.schedule-enabled). 파티 행사 실행은 끈다 —
 * 오프라인 노트북에서 02:00 YouTube 검사 배치가 네트워크 오류를 "삭제된 영상"으로 보고 노래를 전부 끄기 때문.
 * 관리자 수동 실행은 이 설정과 무관하다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("배치 스케줄 켜고 끄기")
class BatchSchedulerToggleTest {

    @Mock
    private TaskScheduler taskScheduler;
    @Mock
    private BatchService batchService;
    @InjectMocks
    private BatchScheduler scheduler;

    @BeforeEach
    void setUp() {
        BatchConfig config = new BatchConfig();
        config.setBatchId(SessionCleanupBatch.BATCH_ID);
        config.setCronExpression("0 0 2 * * *");
        lenient().when(batchService.findEnabledAndImplemented()).thenReturn(List.of(config));
    }

    @Test
    @DisplayName("[정상] 기본(켜짐)이면 켜진 배치를 스케줄에 올린다")
    void schedulesByDefault() {
        ReflectionTestUtils.setField(scheduler, "scheduleEnabled", true);

        scheduler.refreshAllSchedules();

        verify(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));
    }

    @Test
    @DisplayName("[정상] 끄면 기동해도 아무 배치도 스케줄에 올리지 않는다")
    void schedulesNothingWhenDisabled() {
        ReflectionTestUtils.setField(scheduler, "scheduleEnabled", false);

        scheduler.refreshAllSchedules();

        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Trigger.class));
    }

    @Test
    @DisplayName("[정상] party 브랜치의 dev 프로필은 배치 스케줄이 꺼져 있다 — 실행 배치 파일이 아닌 방법(mvnw·IDE)으로 띄워도")
    void devProfileTurnsSchedulesOff() throws IOException {
        Properties dev = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application-dev.properties")) {
            dev.load(in);
        }
        assertThat(dev.getProperty("game.batch.schedule-enabled")).isEqualTo("false");
    }
}
