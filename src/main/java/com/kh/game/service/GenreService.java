package com.kh.game.service;

import com.kh.game.config.CacheConfig;
import com.kh.game.entity.Genre;
import com.kh.game.repository.GenreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GenreService {

    // 게임에서 제외할 장르 코드 (레트로)
    public static final String EXCLUDED_GENRE_CODE = "RETRO";

    private final GenreRepository genreRepository;

    public Page<Genre> findAll(Pageable pageable) {
        return genreRepository.findAll(pageable);
    }

    public List<Genre> findAllOrderByDisplayOrder() {
        return genreRepository.findAllByOrderByDisplayOrderAsc();
    }

    public List<Genre> findActiveGenres() {
        return genreRepository.findByUseYnOrderByDisplayOrderAsc("Y");
    }

    /**
     * 게임용 장르 목록 조회 (레트로 장르 제외) — 캐시. 반환 목록은 불변(캐시 인스턴스를 그대로 돌려준다)
     */
    @Cacheable(cacheNames = CacheConfig.GENRES, key = "'game'")
    public List<Genre> findActiveGenresForGame() {
        return List.copyOf(genreRepository.findByUseYnAndCodeNotOrderByDisplayOrderAsc("Y", EXCLUDED_GENRE_CODE));
    }

    public Page<Genre> search(String keyword, Pageable pageable) {
        return genreRepository.findByNameContainingOrCodeContaining(keyword, keyword, pageable);
    }

    public Optional<Genre> findById(Long id) {
        return genreRepository.findById(id);
    }

    public Optional<Genre> findByCode(String code) {
        return genreRepository.findByCode(code);
    }

    public boolean existsByCode(String code) {
        return genreRepository.existsByCode(code);
    }

    @CacheEvict(cacheNames = {CacheConfig.GENRES, CacheConfig.SONG_CATALOG}, allEntries = true)
    @Transactional
    public Genre save(Genre genre) {
        return genreRepository.save(genre);
    }

    @CacheEvict(cacheNames = {CacheConfig.GENRES, CacheConfig.SONG_CATALOG}, allEntries = true)
    @Transactional
    public void deleteById(Long id) {
        genreRepository.deleteById(id);
    }

    @CacheEvict(cacheNames = {CacheConfig.GENRES, CacheConfig.SONG_CATALOG}, allEntries = true)
    @Transactional
    public void toggleUseYn(Long id) {
        genreRepository.findById(id).ifPresent(genre -> {
            genre.setUseYn("Y".equals(genre.getUseYn()) ? "N" : "Y");
            genreRepository.save(genre);
        });
    }

    public long count() {
        return genreRepository.count();
    }

    public long countActive() {
        return genreRepository.countByUseYn("Y");
    }
}