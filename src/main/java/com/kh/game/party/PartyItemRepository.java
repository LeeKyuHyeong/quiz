package com.kh.game.party;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PartyItemRepository extends JpaRepository<PartyItem, Long> {

    Optional<PartyItem> findByCategoryAndAnswer(PartyCategory category, String answer);

    List<PartyItem> findByCategoryAndUseYn(PartyCategory category, String useYn);

    /** 낼 수 있는 문제: 켜져 있고, 재생·표시할 것이 있다. 스피드퀴즈 제시어는 본게임 밖이라 뺀다. */
    @Query("SELECT i FROM PartyItem i WHERE i.useYn = 'Y' "
            + "AND i.category <> com.kh.game.party.PartyCategory.SPEED "
            + "AND (i.presentation = com.kh.game.party.PartyPresentation.TEXT "
            + "  OR (i.presentation = com.kh.game.party.PartyPresentation.IMAGE AND i.imagePath IS NOT NULL) "
            + "  OR (i.presentation = com.kh.game.party.PartyPresentation.AUDIO AND i.youtubeVideoId IS NOT NULL "
            + "      AND (i.isYoutubeValid IS NULL OR i.isYoutubeValid = true)))")
    List<PartyItem> findPlayable();
}
