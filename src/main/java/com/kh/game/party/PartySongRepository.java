package com.kh.game.party;

import com.kh.game.entity.Song;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 파티용 노래 조회(읽기만). 기존 게임 조회와 달리 RETRO·비인기곡을 포함한다.
 */
@Repository
public interface PartySongRepository extends JpaRepository<Song, Long> {

    interface SongYear {
        Long getId();

        Integer getReleaseYear();
    }

    @Query("SELECT s.id AS id, s.releaseYear AS releaseYear FROM Song s WHERE s.useYn = 'Y' "
            + "AND s.youtubeVideoId IS NOT NULL "
            + "AND (s.isYoutubeValid IS NULL OR s.isYoutubeValid = true)")
    List<SongYear> findPlayable();
}
