package com.kh.game.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "game_room")
public class GameRoom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @Column(nullable = false, unique = true, length = 6)
    private String roomCode;  // 6자리 참가 코드

    @Column(nullable = false, length = 50)
    private String roomName;  // 방 이름

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "host_id", nullable = false)
    private Member host;  // 방장

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RoomStatus status = RoomStatus.WAITING;

    @Column(nullable = false)
    private Integer maxPlayers = 8;  // 최대 인원

    @Column(nullable = false)
    private Integer totalRounds = 10;  // 총 라운드

    @Column(columnDefinition = "TEXT")
    private String settings;  // JSON 형태의 게임 설정

    @Column(nullable = false)
    private Boolean isPrivate = false;  // 비공개 방 여부

    @Column(length = 50)
    private String password;  // 비공개 방 비밀번호

    // 게임 진행 상태
    @Column(nullable = false)
    private Integer currentRound = 0;  // 현재 라운드 (0이면 시작 전)

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_song_id")
    private Song currentSong;  // 현재 출제된 노래

    @Enumerated(EnumType.STRING)
    private RoundPhase roundPhase;  // 라운드 단계

    private LocalDateTime roundStartTime;  // 라운드 시작 시간

    // 오디오 동기화 상태
    @Column(nullable = false)
    private Boolean audioPlaying = false;  // 현재 재생 중 여부

    private Long audioPlayedAt;  // 재생 시작 시각 (epoch millis, 동기화용)

    // 현재 라운드 정답자
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "winner_id")
    private Member winner;

    // 재시작 시각 (grace period용)
    private LocalDateTime restartedAt;

    @OneToMany(mappedBy = "gameRoom", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<GameRoomParticipant> participants = new ArrayList<>();

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "game_session_id")
    private GameSession gameSession;  // 게임 시작 시 연결

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public enum RoomStatus {
        WAITING,    // 대기중 (참가 가능)
        PLAYING,    // 게임중
        FINISHED    // 종료됨
    }

    public enum RoundPhase {
        PREPARING,      // 참가자 준비 대기 (광고 시청)
        PLAYING,        // 노래 재생 & 답변 대기
        RESULT          // 결과 표시
    }

    // 현재 참가자 수 (나간 사람은 행이 남되 LEFT 이므로 제외)
    public int getCurrentPlayerCount() {
        return (int) participants.stream()
                .filter(p -> p.getStatus() != GameRoomParticipant.ParticipantStatus.LEFT)
                .count();
    }

    // 참가 가능 여부
    public boolean canJoin() {
        return status == RoomStatus.WAITING && getCurrentPlayerCount() < maxPlayers;
    }

    // 방장 여부 확인
    public boolean isHost(Member member) {
        return host != null && host.getId().equals(member.getId());
    }

    // 참가자 추가
    public void addParticipant(GameRoomParticipant participant) {
        participants.add(participant);
        participant.setGameRoom(this);
    }

    // 참가자 제거
    public void removeParticipant(GameRoomParticipant participant) {
        participants.remove(participant);
        participant.setGameRoom(null);
    }

    // 다음 라운드로 이동
    public void nextRound() {
        this.currentRound++;
    }

    // 게임 종료 여부
    public boolean isGameOver() {
        return currentRound >= totalRounds;
    }
}