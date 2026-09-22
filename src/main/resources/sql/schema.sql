-- ============================================================
-- song DB 스키마 baseline
-- 출처: 운영 DB(quiz-db) mariadb-dump --no-data (2026-09-08)
-- 가공: 미사용 잔재 테이블 제외, AUTO_INCREMENT 시작값 제거,
--       song.created_at DEFAULT를 current_timestamp()로 통일 (운영도 동일 적용)
--
-- 적용:
--   CREATE DATABASE song DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
--   mariadb -u root -p song < schema.sql
--
-- prod는 ddl-auto=validate 이므로 이 파일이 스키마의 단일 출처다.
-- 엔티티를 변경하면 이 파일도 함께 갱신할 것.
-- ============================================================

/*M!999999\- enable the sandbox mode */ 

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!40101 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*M!100616 SET @OLD_NOTE_VERBOSITY=@@NOTE_VERBOSITY, NOTE_VERBOSITY=0 */;
DROP TABLE IF EXISTS `bad_word`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `bad_word` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `is_active` bit(1) DEFAULT NULL,
  `replacement` varchar(100) DEFAULT NULL,
  `word` varchar(100) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_bad_word` (`word`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `badge`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `badge` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `code` varchar(50) NOT NULL,
  `name` varchar(100) NOT NULL,
  `description` varchar(500) DEFAULT NULL,
  `emoji` varchar(10) DEFAULT NULL,
  `color` varchar(20) DEFAULT NULL,
  `category` varchar(20) NOT NULL,
  `rarity` varchar(20) NOT NULL,
  `sort_order` int(11) DEFAULT NULL,
  `is_active` tinyint(1) DEFAULT 1,
  `created_at` datetime DEFAULT NULL,
  `badge_type` varchar(30) DEFAULT NULL,
  `artist_name` varchar(100) DEFAULT NULL,
  `fan_stage_level` int(11) DEFAULT NULL,
  `fan_challenge_difficulty` varchar(20) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `batch_affected_song`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `batch_affected_song` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `history_id` bigint(20) NOT NULL COMMENT '배치 실행 이력 ID',
  `batch_id` varchar(50) NOT NULL COMMENT '배치 식별자',
  `song_id` bigint(20) NOT NULL COMMENT '영향받은 곡 ID',
  `action_type` varchar(20) NOT NULL COMMENT '처리 유형 (DEACTIVATED, REACTIVATED)',
  `reason` varchar(30) NOT NULL COMMENT '영향 사유 (YOUTUBE_DELETED 등)',
  `reason_detail` varchar(500) DEFAULT NULL COMMENT '상세 사유',
  `is_restored` tinyint(1) NOT NULL DEFAULT 0 COMMENT '복구 여부 (0:미복구, 1:복구됨)',
  `restored_at` datetime(6) DEFAULT NULL COMMENT '복구 일시',
  `restored_by` bigint(20) DEFAULT NULL COMMENT '복구한 관리자 ID',
  `created_at` datetime(6) NOT NULL COMMENT '생성 일시',
  PRIMARY KEY (`id`),
  KEY `idx_batch_affected_song_history` (`history_id`),
  KEY `idx_batch_affected_song_batch` (`batch_id`),
  KEY `idx_batch_affected_song_created` (`created_at`),
  KEY `fk_affected_song_song` (`song_id`),
  KEY `fk_affected_song_member` (`restored_by`),
  CONSTRAINT `fk_affected_song_history` FOREIGN KEY (`history_id`) REFERENCES `batch_execution_history` (`id`),
  CONSTRAINT `fk_affected_song_member` FOREIGN KEY (`restored_by`) REFERENCES `member` (`id`),
  CONSTRAINT `fk_affected_song_song` FOREIGN KEY (`song_id`) REFERENCES `song` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `batch_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `batch_config` (
  `batch_id` varchar(50) NOT NULL,
  `name` varchar(100) DEFAULT NULL,
  `description` varchar(500) DEFAULT NULL,
  `cron_expression` varchar(100) DEFAULT NULL,
  `schedule_text` varchar(50) DEFAULT NULL,
  `enabled` tinyint(1) DEFAULT NULL,
  `target_entity` varchar(50) DEFAULT NULL,
  `priority` varchar(20) DEFAULT NULL,
  `last_executed_at` timestamp NULL DEFAULT NULL,
  `last_result` varchar(20) DEFAULT NULL,
  `last_result_message` varchar(255) DEFAULT NULL,
  `last_affected_count` int(11) DEFAULT NULL,
  `last_execution_time_ms` bigint(20) DEFAULT NULL,
  `implemented` tinyint(1) DEFAULT NULL,
  `created_at` timestamp NULL DEFAULT NULL,
  `updated_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`batch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `batch_execution_history`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `batch_execution_history` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `batch_id` varchar(50) DEFAULT NULL,
  `batch_name` varchar(100) DEFAULT NULL,
  `execution_type` varchar(20) DEFAULT NULL,
  `result` varchar(20) DEFAULT NULL,
  `message` varchar(1000) DEFAULT NULL,
  `affected_count` int(11) DEFAULT NULL,
  `execution_time_ms` bigint(20) DEFAULT NULL,
  `executed_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `board`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `board` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `member_id` bigint(20) NOT NULL,
  `category` varchar(20) NOT NULL,
  `title` varchar(100) NOT NULL,
  `content` text NOT NULL,
  `view_count` int(11) DEFAULT 0,
  `like_count` int(11) DEFAULT 0,
  `comment_count` int(11) DEFAULT 0,
  `status` varchar(20) NOT NULL DEFAULT 'ACTIVE',
  `created_at` datetime DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_board_category` (`category`),
  KEY `idx_board_member` (`member_id`),
  KEY `idx_board_created` (`created_at`),
  KEY `idx_board_status_created` (`status`,`created_at` DESC),
  CONSTRAINT `board_ibfk_1` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `board_comment`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `board_comment` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `board_id` bigint(20) NOT NULL,
  `member_id` bigint(20) NOT NULL,
  `content` varchar(500) NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'ACTIVE',
  `created_at` datetime DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_comment_board` (`board_id`),
  KEY `idx_comment_member` (`member_id`),
  CONSTRAINT `board_comment_ibfk_1` FOREIGN KEY (`board_id`) REFERENCES `board` (`id`),
  CONSTRAINT `board_comment_ibfk_2` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `board_like`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `board_like` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `board_id` bigint(20) NOT NULL,
  `member_id` bigint(20) NOT NULL,
  `created_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_board_like` (`board_id`,`member_id`),
  KEY `idx_like_board` (`board_id`),
  KEY `idx_like_member` (`member_id`),
  CONSTRAINT `board_like_ibfk_1` FOREIGN KEY (`board_id`) REFERENCES `board` (`id`),
  CONSTRAINT `board_like_ibfk_2` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `daily_stats`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `daily_stats` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `stat_date` date NOT NULL,
  `total_games` int(11) DEFAULT 0,
  `total_participants` int(11) DEFAULT 0,
  `total_rooms` int(11) DEFAULT 0,
  `total_chats` int(11) DEFAULT 0,
  `new_members` int(11) DEFAULT 0,
  `active_members` int(11) DEFAULT 0,
  `total_correct_answers` int(11) DEFAULT 0,
  `total_rounds_played` int(11) DEFAULT 0,
  `created_at` datetime DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stat_date` (`stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `email_verification`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `email_verification` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `email` varchar(100) NOT NULL,
  `code` varchar(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `verified` bit(1) NOT NULL DEFAULT b'0',
  `verified_at` datetime(6) DEFAULT NULL,
  `attempts` int(11) NOT NULL DEFAULT 0,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_email_verification_email` (`email`),
  KEY `idx_email_verification_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `fan_challenge_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `fan_challenge_record` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `member_id` bigint(20) NOT NULL,
  `artist` varchar(100) NOT NULL,
  `total_songs` int(11) NOT NULL,
  `correct_count` int(11) NOT NULL DEFAULT 0,
  `is_perfect_clear` tinyint(1) DEFAULT 0,
  `best_time_ms` bigint(20) DEFAULT NULL,
  `achieved_at` datetime DEFAULT NULL,
  `created_at` datetime DEFAULT current_timestamp(),
  `updated_at` datetime DEFAULT NULL,
  `difficulty` varchar(20) NOT NULL DEFAULT 'HARDCORE',
  `is_current_perfect` tinyint(1) DEFAULT 0 COMMENT '현재시점 기준 퍼펙트 여부',
  `last_checked_at` datetime DEFAULT NULL COMMENT '마지막 주간 배치 검사 시점',
  `stage_level` int(11) DEFAULT 1,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_fan_challenge_member_artist_difficulty` (`member_id`,`artist`,`difficulty`),
  UNIQUE KEY `UK_member_artist_difficulty_stage` (`member_id`,`artist`,`difficulty`,`stage_level`),
  CONSTRAINT `fan_challenge_record_ibfk_1` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `fan_challenge_stage_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `fan_challenge_stage_config` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `stage_level` int(11) NOT NULL,
  `required_songs` int(11) NOT NULL,
  `is_active` tinyint(1) DEFAULT 0,
  `activated_at` datetime DEFAULT NULL,
  `stage_name` varchar(50) DEFAULT NULL,
  `stage_emoji` varchar(10) DEFAULT NULL,
  `created_at` datetime DEFAULT current_timestamp(),
  `updated_at` datetime DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`id`),
  UNIQUE KEY `stage_level` (`stage_level`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `game_room`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `game_room` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `room_code` varchar(6) DEFAULT NULL,
  `room_name` varchar(50) DEFAULT NULL,
  `host_id` bigint(20) NOT NULL,
  `status` varchar(20) DEFAULT NULL,
  `max_players` int(11) NOT NULL,
  `total_rounds` int(11) NOT NULL,
  `settings` text DEFAULT NULL,
  `is_private` tinyint(1) DEFAULT NULL,
  `current_round` int(11) NOT NULL,
  `current_song_id` bigint(20) DEFAULT NULL,
  `round_phase` varchar(20) DEFAULT NULL,
  `round_start_time` datetime DEFAULT NULL,
  `game_session_id` bigint(20) DEFAULT NULL,
  `created_at` datetime DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  `audio_playing` tinyint(1) DEFAULT NULL,
  `audio_played_at` bigint(20) DEFAULT NULL,
  `winner_id` bigint(20) DEFAULT NULL,
  `password` varchar(50) DEFAULT NULL,
  `restarted_at` datetime DEFAULT NULL,
  `version` bigint(20) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_game_room_code` (`room_code`),
  KEY `fk_game_room_host` (`host_id`),
  KEY `fk_game_room_current_song` (`current_song_id`),
  KEY `fk_game_room_winner` (`winner_id`),
  KEY `fk_game_room_session` (`game_session_id`),
  CONSTRAINT `fk_game_room_current_song` FOREIGN KEY (`current_song_id`) REFERENCES `song` (`id`),
  CONSTRAINT `fk_game_room_host` FOREIGN KEY (`host_id`) REFERENCES `member` (`id`),
  CONSTRAINT `fk_game_room_session` FOREIGN KEY (`game_session_id`) REFERENCES `game_session` (`id`),
  CONSTRAINT `fk_game_room_winner` FOREIGN KEY (`winner_id`) REFERENCES `member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `game_room_chat`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `game_room_chat` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `game_room_id` bigint(20) NOT NULL,
  `member_id` bigint(20) NOT NULL,
  `message` varchar(500) DEFAULT NULL,
  `message_type` varchar(20) DEFAULT NULL,
  `round_number` int(11) DEFAULT NULL,
  `created_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `fk_chat_room` (`game_room_id`),
  KEY `fk_chat_member` (`member_id`),
  CONSTRAINT `fk_chat_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`),
  CONSTRAINT `fk_chat_room` FOREIGN KEY (`game_room_id`) REFERENCES `game_room` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `game_room_participant`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `game_room_participant` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `game_room_id` bigint(20) NOT NULL,
  `member_id` bigint(20) NOT NULL,
  `is_ready` tinyint(1) DEFAULT NULL,
  `score` int(11) NOT NULL,
  `correct_count` int(11) NOT NULL,
  `status` varchar(20) DEFAULT NULL,
  `current_answer` varchar(255) DEFAULT NULL,
  `has_answered` tinyint(1) DEFAULT NULL,
  `current_round_score` int(11) DEFAULT 0,
  `current_round_correct` tinyint(1) DEFAULT NULL,
  `answer_time` datetime DEFAULT NULL,
  `joined_at` datetime DEFAULT NULL,
  `round_ready` tinyint(1) DEFAULT NULL,
  `skip_vote` tinyint(1) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_room_member` (`game_room_id`,`member_id`),
  KEY `fk_participant_member` (`member_id`),
  CONSTRAINT `fk_participant_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`),
  CONSTRAINT `fk_participant_room` FOREIGN KEY (`game_room_id`) REFERENCES `game_room` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `game_round`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `game_round` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `game_session_id` bigint(20) NOT NULL,
  `round_number` int(11) NOT NULL,
  `song_id` bigint(20) DEFAULT NULL,
  `genre_id` bigint(20) DEFAULT NULL,
  `play_start_time` int(11) DEFAULT NULL,
  `play_duration` int(11) DEFAULT NULL,
  `user_answer` varchar(255) DEFAULT NULL,
  `is_correct` tinyint(1) DEFAULT NULL,
  `answer_time_ms` bigint(20) DEFAULT NULL,
  `hint_used` tinyint(1) DEFAULT NULL,
  `hint_type` varchar(20) DEFAULT NULL,
  `score` int(11) DEFAULT NULL,
  `status` varchar(20) DEFAULT NULL,
  `created_at` datetime DEFAULT NULL,
  `attempt_count` int(11) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `fk_game_round_session` (`game_session_id`),
  KEY `fk_game_round_song` (`song_id`),
  KEY `fk_game_round_genre` (`genre_id`),
  CONSTRAINT `fk_game_round_genre` FOREIGN KEY (`genre_id`) REFERENCES `genre` (`id`),
  CONSTRAINT `fk_game_round_session` FOREIGN KEY (`game_session_id`) REFERENCES `game_session` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_game_round_song` FOREIGN KEY (`song_id`) REFERENCES `song` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `game_round_attempt`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `game_round_attempt` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `game_round_id` bigint(20) NOT NULL,
  `attempt_number` int(11) NOT NULL,
  `user_answer` varchar(255) DEFAULT NULL,
  `is_correct` tinyint(1) DEFAULT NULL,
  `created_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `fk_game_round_attempt_round` (`game_round_id`),
  KEY `idx_game_round_attempt_correct` (`is_correct`),
  CONSTRAINT `fk_game_round_attempt_round` FOREIGN KEY (`game_round_id`) REFERENCES `game_round` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `game_session`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `game_session` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `session_uuid` varchar(36) DEFAULT NULL,
  `member_id` bigint(20) DEFAULT NULL,
  `nickname` varchar(50) DEFAULT NULL,
  `game_type` varchar(20) DEFAULT NULL,
  `game_mode` varchar(30) NOT NULL,
  `total_rounds` int(11) DEFAULT NULL,
  `completed_rounds` int(11) DEFAULT NULL,
  `total_score` int(11) DEFAULT NULL,
  `correct_count` int(11) DEFAULT NULL,
  `skip_count` int(11) DEFAULT NULL,
  `status` varchar(20) DEFAULT NULL,
  `settings` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL CHECK (json_valid(`settings`)),
  `started_at` datetime DEFAULT NULL,
  `ended_at` datetime DEFAULT NULL,
  `created_at` datetime DEFAULT NULL,
  `remaining_lives` int(11) DEFAULT NULL,
  `challenge_artist` varchar(100) DEFAULT NULL,
  `challenge_genre_code` varchar(100) DEFAULT NULL,
  `current_combo` int(11) DEFAULT 0,
  `max_combo` int(11) DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_game_session_member_status` (`member_id`,`status`,`total_rounds`,`started_at`),
  KEY `idx_game_session_type_status` (`game_type`,`status`,`created_at` DESC),
  CONSTRAINT `fk_game_session_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `genre`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `genre` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `code` varchar(255) DEFAULT NULL,
  `name` varchar(255) DEFAULT NULL,
  `display_order` int(11) DEFAULT NULL,
  `use_yn` varchar(255) DEFAULT NULL,
  `created_at` datetime DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_genre_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `genre_challenge_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `genre_challenge_record` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `member_id` bigint(20) NOT NULL,
  `genre_id` bigint(20) NOT NULL,
  `difficulty` varchar(20) NOT NULL DEFAULT 'HARDCORE',
  `total_songs` int(11) DEFAULT NULL,
  `correct_count` int(11) DEFAULT 0,
  `max_combo` int(11) DEFAULT 0,
  `best_time_ms` bigint(20) DEFAULT NULL,
  `achieved_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_member_genre_difficulty` (`member_id`,`genre_id`,`difficulty`),
  KEY `fk_genre_challenge_genre` (`genre_id`),
  CONSTRAINT `fk_genre_challenge_genre` FOREIGN KEY (`genre_id`) REFERENCES `genre` (`id`),
  CONSTRAINT `fk_genre_challenge_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `member`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `member` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `username` varchar(30) DEFAULT NULL,
  `password` varchar(255) DEFAULT NULL,
  `nickname` varchar(50) DEFAULT NULL,
  `total_games` int(11) DEFAULT NULL,
  `total_score` int(11) DEFAULT NULL,
  `total_correct` int(11) DEFAULT NULL,
  `total_rounds` int(11) DEFAULT NULL,
  `created_at` datetime DEFAULT NULL,
  `email` varchar(100) DEFAULT NULL,
  `last_login_at` datetime(6) DEFAULT NULL,
  `role` enum('ADMIN','USER') DEFAULT NULL,
  `status` enum('ACTIVE','BANNED','INACTIVE') DEFAULT NULL,
  `total_skip` int(11) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `session_token` varchar(64) DEFAULT NULL,
  `session_created_at` datetime DEFAULT NULL,
  `guess_games` int(11) DEFAULT 0,
  `guess_score` int(11) DEFAULT 0,
  `guess_correct` int(11) DEFAULT 0,
  `guess_rounds` int(11) DEFAULT 0,
  `guess_skip` int(11) DEFAULT 0,
  `multi_games` int(11) DEFAULT 0,
  `multi_score` int(11) DEFAULT 0,
  `multi_correct` int(11) DEFAULT 0,
  `multi_rounds` int(11) DEFAULT 0,
  `weekly_guess_games` int(11) DEFAULT 0,
  `weekly_guess_score` int(11) DEFAULT 0,
  `weekly_guess_correct` int(11) DEFAULT 0,
  `weekly_guess_rounds` int(11) DEFAULT 0,
  `weekly_multi_games` int(11) DEFAULT 0,
  `weekly_multi_score` int(11) DEFAULT 0,
  `weekly_multi_correct` int(11) DEFAULT 0,
  `weekly_multi_rounds` int(11) DEFAULT 0,
  `weekly_reset_at` datetime DEFAULT NULL,
  `best_guess_score` int(11) DEFAULT 0,
  `best_guess_accuracy` double DEFAULT 0,
  `best_guess_at` datetime DEFAULT NULL,
  `best_multi_score` int(11) DEFAULT 0,
  `best_multi_accuracy` double DEFAULT 0,
  `best_multi_at` datetime DEFAULT NULL,
  `tier` varchar(20) DEFAULT 'BRONZE',
  `tier_updated_at` datetime DEFAULT NULL,
  `multi_lp` int(11) DEFAULT NULL,
  `multi_tier` varchar(20) DEFAULT 'BRONZE',
  `multi_wins` int(11) DEFAULT 0,
  `multi_top3` int(11) DEFAULT 0,
  `multi_tier_updated_at` datetime DEFAULT NULL,
  `selected_badge_id` bigint(20) DEFAULT NULL,
  `current_correct_streak` int(11) DEFAULT 0,
  `max_correct_streak` int(11) DEFAULT 0,
  `weekly_best_30_score` int(11) DEFAULT NULL,
  `weekly_best_30_at` datetime DEFAULT NULL,
  `monthly_best_30_score` int(11) DEFAULT NULL,
  `monthly_best_30_at` datetime DEFAULT NULL,
  `monthly_reset_at` datetime DEFAULT NULL,
  `all_time_best_30_score` int(11) DEFAULT NULL,
  `all_time_best_30_at` datetime DEFAULT NULL,
  `retro_best_30_score` int(11) DEFAULT NULL,
  `retro_best_30_at` datetime DEFAULT NULL,
  `weekly_retro_best_30_score` int(11) DEFAULT NULL,
  `weekly_retro_best_30_at` datetime DEFAULT NULL,
  `monthly_retro_best_30_score` int(11) DEFAULT NULL,
  `monthly_retro_best_30_at` datetime DEFAULT NULL,
  `weekly_retro_games` int(11) DEFAULT NULL,
  `weekly_retro_score` int(11) DEFAULT NULL,
  `weekly_retro_correct` int(11) DEFAULT NULL,
  `weekly_retro_rounds` int(11) DEFAULT NULL,
  `retro_games` int(11) DEFAULT NULL,
  `retro_score` int(11) DEFAULT NULL,
  `retro_correct` int(11) DEFAULT NULL,
  `retro_rounds` int(11) DEFAULT NULL,
  `retro_skip` int(11) DEFAULT NULL,
  `login_streak` int(11) DEFAULT 0,
  `max_login_streak` int(11) DEFAULT 0,
  `last_login_date` date DEFAULT NULL,
  `last_game_played_at` datetime DEFAULT NULL,
  `login_fail_count` int(11) NOT NULL DEFAULT 0,
  `login_locked_until` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_email` (`email`),
  KEY `selected_badge_id` (`selected_badge_id`),
  CONSTRAINT `member_ibfk_1` FOREIGN KEY (`selected_badge_id`) REFERENCES `badge` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `member_badge`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `member_badge` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `member_id` bigint(20) NOT NULL,
  `badge_id` bigint(20) NOT NULL,
  `earned_at` datetime NOT NULL,
  `is_new` tinyint(1) DEFAULT 1,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_member_badge` (`member_id`,`badge_id`),
  KEY `badge_id` (`badge_id`),
  CONSTRAINT `member_badge_ibfk_1` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`),
  CONSTRAINT `member_badge_ibfk_2` FOREIGN KEY (`badge_id`) REFERENCES `badge` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `member_login_history`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `member_login_history` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `member_id` bigint(20) DEFAULT NULL,
  `email` varchar(100) DEFAULT NULL,
  `result` varchar(20) DEFAULT NULL,
  `ip_address` varchar(50) DEFAULT NULL,
  `user_agent` varchar(500) DEFAULT NULL,
  `fail_reason` varchar(255) DEFAULT NULL,
  `created_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `fk_login_history_member` (`member_id`),
  KEY `idx_login_history_ip` (`ip_address`,`created_at` DESC),
  CONSTRAINT `fk_login_history_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `menu_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `menu_config` (
  `menu_id` varchar(50) NOT NULL,
  `name` varchar(100) NOT NULL,
  `description` varchar(500) DEFAULT NULL,
  `enabled` tinyint(1) NOT NULL DEFAULT 1,
  `display_order` int(11) NOT NULL DEFAULT 0,
  `category` varchar(50) DEFAULT NULL,
  `icon` varchar(100) DEFAULT NULL,
  `url` varchar(200) DEFAULT NULL,
  `created_at` datetime(6) DEFAULT current_timestamp(6),
  `updated_at` datetime(6) DEFAULT current_timestamp(6) ON UPDATE current_timestamp(6),
  PRIMARY KEY (`menu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `ranking_history`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `ranking_history` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `period_type` varchar(20) NOT NULL COMMENT '기간 유형 (WEEKLY, MONTHLY)',
  `ranking_type` varchar(30) NOT NULL COMMENT '랭킹 유형',
  `rank_position` int(11) NOT NULL COMMENT '순위 (1~100)',
  `member_id` bigint(20) NOT NULL COMMENT '회원 ID (FK 아님)',
  `nickname` varchar(50) NOT NULL COMMENT '당시 닉네임',
  `score` int(11) NOT NULL COMMENT '점수',
  `accuracy` double DEFAULT NULL COMMENT '정답률',
  `games_played` int(11) DEFAULT NULL COMMENT '게임 수',
  `correct_count` int(11) DEFAULT NULL COMMENT '정답 수',
  `multi_tier` varchar(20) DEFAULT NULL COMMENT '멀티게임 티어',
  `multi_lp` int(11) DEFAULT NULL COMMENT '멀티게임 LP',
  `period_start` date NOT NULL COMMENT '기간 시작일',
  `period_end` date NOT NULL COMMENT '기간 종료일',
  `created_at` datetime DEFAULT current_timestamp() COMMENT '생성일시',
  PRIMARY KEY (`id`),
  KEY `idx_ranking_history_period_type` (`period_type`,`ranking_type`),
  KEY `idx_ranking_history_member` (`member_id`),
  KEY `idx_ranking_history_period` (`period_start`,`period_end`),
  KEY `idx_ranking_history_position` (`rank_position`,`ranking_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='랭킹 스냅샷 히스토리 - 주간/월간 랭킹 리셋 전 Top 100 기록 보관';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `song`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `song` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `title` varchar(255) NOT NULL COMMENT '노래 제목',
  `artist` varchar(255) NOT NULL COMMENT '아티스트',
  `file_path` varchar(255) DEFAULT NULL,
  `start_time` int(11) NOT NULL DEFAULT 0 COMMENT '시작 시간(초)',
  `play_duration` int(11) DEFAULT NULL COMMENT '재생 시간(초)',
  `genre_id` bigint(20) DEFAULT NULL COMMENT '장르 ID',
  `release_year` int(11) DEFAULT NULL COMMENT '발매연도',
  `is_solo` tinyint(1) DEFAULT NULL COMMENT '솔로여부',
  `use_yn` varchar(255) NOT NULL DEFAULT 'Y',
  `created_at` datetime NOT NULL DEFAULT current_timestamp(),
  `updated_at` datetime DEFAULT NULL,
  `youtube_video_id` varchar(30) DEFAULT NULL COMMENT 'YOUTUBE 영상 ID',
  `is_youtube_valid` tinyint(1) DEFAULT 1,
  `youtube_checked_at` datetime(6) DEFAULT NULL,
  `youtube_error_code` int(11) DEFAULT NULL,
  `is_popular` tinyint(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `unique_artist_title` (`artist`,`title`),
  KEY `fk_song_genre` (`genre_id`),
  KEY `idx_song_artist_use_yn` (`artist`,`use_yn`),
  CONSTRAINT `fk_song_genre` FOREIGN KEY (`genre_id`) REFERENCES `genre` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `song_answer`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `song_answer` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `song_id` bigint(20) NOT NULL,
  `answer` varchar(255) DEFAULT NULL,
  `is_primary` tinyint(1) DEFAULT NULL,
  `created_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `fk_song_answer_song` (`song_id`),
  CONSTRAINT `fk_song_answer_song` FOREIGN KEY (`song_id`) REFERENCES `song` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `song_popularity_vote`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `song_popularity_vote` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `song_id` bigint(20) NOT NULL,
  `member_id` bigint(20) NOT NULL,
  `rating` int(11) NOT NULL COMMENT '1:매우대중적 ~ 5:매우매니악',
  `created_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_song_popularity_vote_song_member` (`song_id`,`member_id`),
  KEY `fk_song_popularity_vote_member` (`member_id`),
  CONSTRAINT `fk_song_popularity_vote_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`),
  CONSTRAINT `fk_song_popularity_vote_song` FOREIGN KEY (`song_id`) REFERENCES `song` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `song_report`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `song_report` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '신고 고유 ID',
  `song_id` bigint(20) NOT NULL COMMENT '신고된 곡 ID',
  `member_id` bigint(20) DEFAULT NULL COMMENT '신고한 회원 ID (게스트일 경우 NULL)',
  `session_id` varchar(100) DEFAULT NULL COMMENT '게스트 중복 신고 방지용 세션 ID',
  `report_type` varchar(20) NOT NULL COMMENT '신고 유형 (AD, UNPLAYABLE, OTHER)',
  `description` varchar(500) DEFAULT NULL COMMENT '기타 상세 내용',
  `status` varchar(20) NOT NULL DEFAULT 'PENDING' COMMENT '처리 상태 (PENDING, CONFIRMED, REJECTED, RESOLVED)',
  `admin_note` varchar(500) DEFAULT NULL COMMENT '관리자 처리 메모',
  `processed_by` bigint(20) DEFAULT NULL COMMENT '처리를 담당한 관리자(Member) ID',
  `processed_at` datetime(6) DEFAULT NULL COMMENT '처리 일시',
  `created_at` datetime(6) DEFAULT current_timestamp(6) COMMENT '신고 생성 일시',
  PRIMARY KEY (`id`),
  KEY `idx_song_report_status` (`status`),
  KEY `idx_song_report_song` (`song_id`),
  KEY `fk_song_report_member` (`member_id`),
  KEY `fk_song_report_admin` (`processed_by`),
  CONSTRAINT `fk_song_report_admin` FOREIGN KEY (`processed_by`) REFERENCES `member` (`id`),
  CONSTRAINT `fk_song_report_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`),
  CONSTRAINT `fk_song_report_song` FOREIGN KEY (`song_id`) REFERENCES `song` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*M!100616 SET NOTE_VERBOSITY=@OLD_NOTE_VERBOSITY */;


--
-- Spring Session JDBC (2026-09-22, O-020) — 로그인 세션 저장소. 엔티티가 아니라 ddl-auto=validate 가 검사하지 않는다.
-- 배포 전에 운영 DB 에 먼저 만든다. Spring Session 3.4 schema-mysql.sql 과 같고 PRINCIPAL_NAME 만 255 로 넓혔다.
--
CREATE TABLE IF NOT EXISTS `SPRING_SESSION` (
  `PRIMARY_ID` char(36) NOT NULL,
  `SESSION_ID` char(36) NOT NULL,
  `CREATION_TIME` bigint(20) NOT NULL,
  `LAST_ACCESS_TIME` bigint(20) NOT NULL,
  `MAX_INACTIVE_INTERVAL` int(11) NOT NULL,
  `EXPIRY_TIME` bigint(20) NOT NULL,
  `PRINCIPAL_NAME` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`PRIMARY_ID`),
  UNIQUE KEY `SPRING_SESSION_IX1` (`SESSION_ID`),
  KEY `SPRING_SESSION_IX2` (`EXPIRY_TIME`),
  KEY `SPRING_SESSION_IX3` (`PRINCIPAL_NAME`)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC;

CREATE TABLE IF NOT EXISTS `SPRING_SESSION_ATTRIBUTES` (
  `SESSION_PRIMARY_ID` char(36) NOT NULL,
  `ATTRIBUTE_NAME` varchar(200) NOT NULL,
  `ATTRIBUTE_BYTES` blob NOT NULL,
  PRIMARY KEY (`SESSION_PRIMARY_ID`,`ATTRIBUTE_NAME`),
  CONSTRAINT `SPRING_SESSION_ATTRIBUTES_FK` FOREIGN KEY (`SESSION_PRIMARY_ID`) REFERENCES `SPRING_SESSION` (`PRIMARY_ID`) ON DELETE CASCADE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC;
