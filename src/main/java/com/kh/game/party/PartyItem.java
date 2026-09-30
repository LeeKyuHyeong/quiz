package com.kh.game.party;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 파티 퀴즈 문제. party 브랜치·로컬 DB 전용 — 운영 DB 에는 테이블이 없다.
 */
@Entity
@Table(name = "party_item", uniqueConstraints = {
    @UniqueConstraint(name = "uk_party_item_category_answer", columnNames = {"category", "answer"})
})
@Getter
@Setter
@NoArgsConstructor
public class PartyItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PartyCategory category;

    @Column(name = "sub_category", length = 50)
    private String subCategory;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PartyPresentation presentation;

    @Column(nullable = false)
    private String answer;

    @Column(name = "answer_aliases", length = 500)
    private String answerAliases;

    private String detail;

    @Column(name = "youtube_video_id", length = 20)
    private String youtubeVideoId;

    @Column(name = "start_time")
    private Integer startTime;

    @Column(name = "play_duration")
    private Integer playDuration;

    @Column(name = "image_path")
    private String imagePath;

    @Column(name = "question_text", length = 500)
    private String questionText;

    private String hint1;

    private String hint2;

    private String hint3;

    @Column(name = "source_note")
    private String sourceNote;

    private Integer difficulty;

    @Column(name = "is_youtube_valid")
    private Boolean isYoutubeValid = true;

    @Column(name = "youtube_checked_at")
    private LocalDateTime youtubeCheckedAt;

    @Column(name = "use_yn", nullable = false, length = 1)
    private String useYn = "Y";

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
