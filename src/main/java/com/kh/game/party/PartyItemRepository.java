package com.kh.game.party;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PartyItemRepository extends JpaRepository<PartyItem, Long> {

    Optional<PartyItem> findByCategoryAndAnswer(PartyCategory category, String answer);
}
