package com.kh.game.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "game_room_participant",
        uniqueConstraints = @UniqueConstraint(columnNames = {"game_room_id", "member_id"}))
public class GameRoomParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "game_room_id", nullable = false)
    private GameRoom gameRoom;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(nullable = false)
    private Boolean isReady = false;  // 대기실 준비 상태

    @Column(nullable = false)
    private Boolean roundReady = false;  // 라운드 준비 상태 (광고 시청 완료)

    @Column(nullable = false)
    private Boolean skipVote = false;  // 라운드 스킵 투표 여부

    @Column(nullable = false)
    private Integer score = 0;  // 현재 게임 점수

    @Column(nullable = false)
    private Integer correctCount = 0;  // 정답 수

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ParticipantStatus status = ParticipantStatus.JOINED;

    @CreationTimestamp
    private LocalDateTime joinedAt;

    public enum ParticipantStatus {
        JOINED,     // 참가중
        PLAYING,    // 게임중
        LEFT        // 나감
    }

    // 생성자
    public GameRoomParticipant(GameRoom gameRoom, Member member) {
        this.gameRoom = gameRoom;
        this.member = member;
    }

    // 준비 토글
    public void toggleReady() {
        this.isReady = !this.isReady;
    }

    // 점수 추가
    public void addScore(int points) {
        this.score += points;
    }

    // 정답 카운트 증가
    public void incrementCorrect() {
        this.correctCount++;
    }

    // 점수 리셋 (게임 시작 시)
    public void resetScore() {
        this.score = 0;
        this.correctCount = 0;
    }
}