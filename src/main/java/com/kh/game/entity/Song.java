package com.kh.game.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "song", uniqueConstraints = {
    @UniqueConstraint(name = "uk_song_artist_title", columnNames = {"artist", "title"})
})
@Getter
@Setter
@NoArgsConstructor
public class Song {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String artist;

    @Column(name = "file_path")
    private String filePath;

    @Column(name = "youtube_video_id", length = 20)
    private String youtubeVideoId;

    @Column(name = "start_time")
    private Integer startTime;

    @Column(name = "play_duration")
    private Integer playDuration;

    @ManyToOne(fetch = FetchType.LAZY)   // 목록 쿼리는 SongRepository 에서 fetch join / @EntityGraph (N+1 방지)
    @JoinColumn(name = "genre_id")
    private Genre genre;

    @Column(name = "release_year")
    private Integer releaseYear;

    @Column(name = "is_solo")
    private Boolean isSolo;

    @Column(name = "is_popular")
    private Boolean isPopular = true;

    @Column(name = "use_yn")
    private String useYn = "Y";

    // YouTube 유효성 플래그 (Error 2 방지용)
    @Column(name = "is_youtube_valid")
    private Boolean isYoutubeValid = true;

    @Column(name = "youtube_checked_at")
    private LocalDateTime youtubeCheckedAt;

    @Column(name = "youtube_error_code")
    private Integer youtubeErrorCode;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "song", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SongAnswer> answers = new ArrayList<>();

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