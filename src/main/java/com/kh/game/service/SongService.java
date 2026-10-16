package com.kh.game.service;

import com.kh.game.config.CacheConfig;
import com.kh.game.dto.GameSettings;
import com.kh.game.exception.BusinessException;
import com.kh.game.entity.Song;
import com.kh.game.entity.SongAnswer;
import com.kh.game.repository.BadgeRepository;
import com.kh.game.repository.FanChallengeRecordRepository;
import com.kh.game.repository.GameRoomRepository;
import com.kh.game.repository.GameRoundRepository;
import com.kh.game.repository.SongAnswerRepository;
import com.kh.game.repository.SongReportRepository;
import com.kh.game.repository.SongRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class SongService {

    private final SongRepository songRepository;
    private final SongAnswerRepository songAnswerRepository;
    private final SongReportRepository songReportRepository;
    private final GameRoundRepository gameRoundRepository;
    private final GameRoomRepository gameRoomRepository;
    private final YouTubeValidationService youTubeValidationService;

    @Value("${file.upload-dir:uploads/songs}")
    private String uploadDir;

    /**
     * 일반 게임용 노래 목록 조회 (레트로 장르 제외, 대중곡만)
     * - 랜덤 게임, 멀티플레이어, 호스트 모드, 내가 맞추기에서 사용
     */
    private List<Song> findSongsForGame() {
        return songRepository.findPopularSongsForGame("Y", GenreService.EXCLUDED_GENRE_CODE);
    }

    /**
     * 아티스트 챌린지용 노래 목록 조회 (레트로 장르 제외, 매니악 곡 포함)
     * - 팬 챌린지에서 사용 (팬이라면 매니악 곡도 알아야 함)
     */
    private List<Song> findAllSongsForArtistChallenge() {
        return songRepository.findByUseYnAndHasAudioSourceExcludingGenre("Y", GenreService.EXCLUDED_GENRE_CODE);
    }

    /**
     * 레트로 게임용 노래 목록 조회 (releaseYear < 2000 OR genre.code = 'RETRO', 대중곡만)
     */
    public List<Song> findRetroSongsForGame() {
        return songRepository.findPopularRetroSongsForGame("Y");
    }

    /**
     * 레트로 게임용 모든 노래 조회 (매니악 곡 포함)
     */
    public List<Song> findAllRetroSongsForGame() {
        return songRepository.findRetroSongsForGame("Y");
    }

    public Page<Song> findAll(Pageable pageable) {
        return songRepository.findAll(pageable);
    }

    public Page<Song> search(String keyword, Pageable pageable) {
        return songRepository.findByTitleContainingOrArtistContaining(keyword, keyword, pageable);
    }

    public Page<Song> findByArtist(String artist, Pageable pageable) {
        return songRepository.findByArtist(artist, pageable);
    }

    public Page<Song> searchWithFilters(String keyword, String artist, Long genreId, String useYn, Boolean isSolo, Integer releaseYear, Pageable pageable) {
        return songRepository.searchWithFilters(keyword, artist, genreId, useYn, isSolo, releaseYear, pageable);
    }

    public Page<Song> searchWithFilters(String keyword, List<String> artists, Long genreId, String useYn, Boolean isSolo, Integer releaseYear, Pageable pageable) {
        if (artists != null && !artists.isEmpty()) {
            return songRepository.searchWithFiltersMultipleArtists(keyword, artists, genreId, useYn, isSolo, releaseYear, pageable);
        }
        return songRepository.searchWithFilters(keyword, null, genreId, useYn, isSolo, releaseYear, pageable);
    }

    // 전체 연도 목록 (관리자용)
    public List<Integer> getAllYears() {
        return songRepository.findAllDistinctYears();
    }

    public List<String> getAllArtists() {
        return songRepository.findAllDistinctArtists();
    }

    public Optional<Song> findById(Long id) {
        return songRepository.findById(id);
    }

    public List<Song> findActiveSongs() {
        return songRepository.findByUseYn("Y");
    }

    @CacheEvict(cacheNames = CacheConfig.SONG_CATALOG, allEntries = true)
    @Transactional
    public Song save(Song song) {
        return songRepository.save(song);
    }

    /**
     * 중복 체크 후 저장 (아티스트 + 제목 기준)
     * @throws IllegalArgumentException 중복된 곡이 이미 존재하는 경우
     */
    @CacheEvict(cacheNames = CacheConfig.SONG_CATALOG, allEntries = true)
    @Transactional
    public Song saveWithDuplicateCheck(Song song) {
        if (isDuplicate(song.getArtist(), song.getTitle())) {
            throw new BusinessException(
                String.format("이미 등록된 곡입니다: %s - %s", song.getArtist(), song.getTitle()));
        }
        return songRepository.save(song);
    }

    /**
     * 중복 여부 확인 (대소문자 무시)
     */
    public boolean isDuplicate(String artist, String title) {
        return songRepository.findByArtistAndTitleIgnoreCase(artist, title).isPresent();
    }

    /**
     * 정확한 아티스트 + 제목으로 중복 여부 확인
     */
    public boolean isDuplicateExact(String artist, String title) {
        return songRepository.existsByArtistAndTitle(artist, title);
    }

    /**
     * 중복 곡 조회 (대소문자 무시)
     */
    public Optional<Song> findDuplicate(String artist, String title) {
        return songRepository.findByArtistAndTitleIgnoreCase(artist, title);
    }

    @CacheEvict(cacheNames = CacheConfig.SONG_CATALOG, allEntries = true)
    @Transactional
    public void deleteById(Long id) {
        // 외래 키 제약조건 해결을 위해 관련 데이터 먼저 정리
        // 1. SongReport 삭제 (nullable=false이므로 삭제 필요)
        songReportRepository.deleteBySongId(id);
        // 2. GameRound의 song 참조를 null로 설정
        gameRoundRepository.clearSongReference(id);
        // 3. GameRoom의 currentSong 참조를 null로 설정
        gameRoomRepository.clearCurrentSongReference(id);
        // 4. Song 삭제 (SongAnswer는 cascade로 자동 삭제)
        songRepository.deleteById(id);
    }

    @CacheEvict(cacheNames = CacheConfig.SONG_CATALOG, allEntries = true)
    @Transactional
    public void toggleUseYn(Long id) {
        songRepository.findById(id).ifPresent(song -> {
            song.setUseYn("Y".equals(song.getUseYn()) ? "N" : "Y");
            songRepository.save(song);
        });
    }

    @CacheEvict(cacheNames = CacheConfig.SONG_CATALOG, allEntries = true)
    @Transactional
    public void togglePopular(Long id) {
        songRepository.findById(id).ifPresent(song -> {
            song.setIsPopular(!Boolean.TRUE.equals(song.getIsPopular()));
            songRepository.save(song);
        });
    }

    @CacheEvict(cacheNames = CacheConfig.SONG_CATALOG, allEntries = true)
    @Transactional
    public void updatePopularity(Long id, boolean isPopular) {
        Song song = songRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("곡을 찾을 수 없습니다: " + id));
        song.setIsPopular(isPopular);
        songRepository.save(song);
    }

    public String saveFile(MultipartFile file) throws IOException {
        Path uploadPath = Paths.get(uploadDir);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }

        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String newFilename = UUID.randomUUID().toString() + extension;

        Path filePath = uploadPath.resolve(newFilename);
        Files.copy(file.getInputStream(), filePath);

        return newFilename;
    }

    public void deleteFile(String filename) {
        try {
            Path filePath = Paths.get(uploadDir).resolve(filename);
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            // 로그만 남기고 예외는 무시
        }
    }

    public List<Song> getRandomSongs(int count, GameSettings settings) {
        List<Song> allSongs = findSongsForGame();

        // 필터링
        List<Song> filtered = new ArrayList<>();
        for (Song song : allSongs) {
            if (!matchesSettings(song, settings)) continue;
            filtered.add(song);
        }

        // 셔플 후 필요한 만큼 반환
        Collections.shuffle(filtered);
        return filtered.subList(0, Math.min(count, filtered.size()));
    }

    /**
     * 노래가 설정 조건에 맞는지 확인
     */
    private boolean matchesSettings(Song song, GameSettings settings) {
        // 연도 필터 (복수 선택)
        if (settings.getSelectedYears() != null && !settings.getSelectedYears().isEmpty()) {
            if (song.getReleaseYear() == null || !settings.getSelectedYears().contains(song.getReleaseYear())) {
                return false;
            }
        } else {
            // 레거시: 연도 범위 필터
            if (settings.getYearFrom() != null && song.getReleaseYear() != null) {
                if (song.getReleaseYear() < settings.getYearFrom()) return false;
            }
            if (settings.getYearTo() != null && song.getReleaseYear() != null) {
                if (song.getReleaseYear() > settings.getYearTo()) return false;
            }
        }

        // 솔로/그룹 필터
        if (settings.getSoloOnly() != null && settings.getSoloOnly()) {
            if (song.getIsSolo() == null || !song.getIsSolo()) return false;
        }
        if (settings.getGroupOnly() != null && settings.getGroupOnly()) {
            if (song.getIsSolo() != null && song.getIsSolo()) return false;
        }

        // 장르 필터
        if (settings.getFixedGenreId() != null) {
            if (song.getGenre() == null || !song.getGenre().getId().equals(settings.getFixedGenreId())) return false;
        }

        // 아티스트 필터 (복수 선택)
        if (settings.getSelectedArtists() != null && !settings.getSelectedArtists().isEmpty()) {
            if (song.getArtist() == null || !settings.getSelectedArtists().contains(song.getArtist())) {
                return false;
            }
        } else if (settings.getFixedArtistName() != null && !settings.getFixedArtistName().isEmpty()) {
            // 레거시: 단일 아티스트 필터
            if (song.getArtist() == null || !song.getArtist().equals(settings.getFixedArtistName())) return false;
        }

        return true;
    }

    public int getAvailableSongCount(GameSettings settings) {
        List<Song> allSongs = findSongsForGame();

        int count = 0;
        for (Song song : allSongs) {
            if (!matchesSettings(song, settings)) continue;
            count++;
        }

        return count;
    }

    // ========== 레트로 게임용 메서드 ==========

    /**
     * 레트로 게임용 랜덤 노래 목록 가져오기
     */
    public List<Song> getRandomRetroSongs(int count, GameSettings settings) {
        List<Song> allSongs = findRetroSongsForGame();

        // 필터링
        List<Song> filtered = new ArrayList<>();
        for (Song song : allSongs) {
            if (!matchesRetroSettings(song, settings)) continue;
            filtered.add(song);
        }

        // 셔플 후 필요한 만큼 반환
        Collections.shuffle(filtered);
        return filtered.subList(0, Math.min(count, filtered.size()));
    }

    /**
     * 레트로 게임용 노래 수 조회
     */
    public int getAvailableRetroSongCount(GameSettings settings) {
        List<Song> allSongs = findRetroSongsForGame();

        int count = 0;
        for (Song song : allSongs) {
            if (!matchesRetroSettings(song, settings)) continue;
            count++;
        }

        return count;
    }

    /**
     * 레트로 게임용 검증된 랜덤 노래 목록 가져오기
     */
    public ValidatedSongsResult getRandomRetroSongsWithValidation(int count, GameSettings settings) {
        List<Song> allSongs = findRetroSongsForGame();

        // 필터링
        List<Song> filtered = new ArrayList<>();
        for (Song song : allSongs) {
            if (!matchesRetroSettings(song, settings)) continue;
            filtered.add(song);
        }

        Collections.shuffle(filtered);

        List<Song> validSongs = new ArrayList<>();
        Set<Long> usedSongIds = new HashSet<>();
        int replacedCount = 0;
        int validationFailCount = 0;

        for (Song song : filtered) {
            if (validSongs.size() >= count) break;
            if (usedSongIds.contains(song.getId())) continue;

            // YouTube 검증
            if (song.getYoutubeVideoId() != null && !song.getYoutubeVideoId().isEmpty()) {
                YouTubeValidationService.ValidationResult result =
                        youTubeValidationService.validateVideo(song.getYoutubeVideoId());

                if (!result.isValid()) {
                    validationFailCount++;
                    continue;
                }
            }

            validSongs.add(song);
            usedSongIds.add(song.getId());
        }

        replacedCount = Math.min(validationFailCount, count);
        return new ValidatedSongsResult(validSongs, replacedCount);
    }

    /**
     * 레트로 노래가 설정 조건에 맞는지 확인
     */
    private boolean matchesRetroSettings(Song song, GameSettings settings) {
        // 레트로 게임은 연도/장르 필터가 이미 쿼리에서 처리됨
        // 여기서는 솔로/그룹 필터만 적용

        // 솔로/그룹 필터
        if (settings != null) {
            if (settings.getSoloOnly() != null && settings.getSoloOnly()) {
                if (song.getIsSolo() == null || !song.getIsSolo()) return false;
            }
            if (settings.getGroupOnly() != null && settings.getGroupOnly()) {
                if (song.getIsSolo() != null && song.getIsSolo()) return false;
            }
        }

        return true;
    }

    /**
     * 레트로 게임용 총 곡 수 조회
     */
    public long countRetroSongs() {
        return songRepository.countRetroSongsForGame("Y");
    }

    public long count() {
        return songRepository.count();
    }

    public long countByUseYn(String useYn) {
        return songRepository.countByUseYn(useYn);
    }

    // 아티스트 목록 조회 (곡 수 포함, isSolo 정보 포함) - 게임용 (레트로 제외, 대중곡만)
    @Cacheable(cacheNames = CacheConfig.SONG_CATALOG, key = "'artists'")
    public List<Map<String, Object>> getArtistsWithCount() {
        List<Song> allSongs = findSongsForGame();
        Map<String, Integer> artistCountMap = new TreeMap<>();
        Map<String, Boolean> artistSoloMap = new HashMap<>();

        for (Song song : allSongs) {
            if (song.getArtist() != null) {
                artistCountMap.merge(song.getArtist(), 1, Integer::sum);
                // 첫 번째 곡의 isSolo 값 저장
                if (!artistSoloMap.containsKey(song.getArtist())) {
                    artistSoloMap.put(song.getArtist(), song.getIsSolo());
                }
            }
        }

        List<Map<String, Object>> artists = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : artistCountMap.entrySet()) {
            Map<String, Object> artist = new HashMap<>();
            artist.put("name", entry.getKey());
            artist.put("count", entry.getValue());
            artist.put("isSolo", artistSoloMap.get(entry.getKey()));
            artists.add(artist);
        }
        return unmodifiableRows(artists);
    }

    /**
     * 아티스트 목록 조회 (필터 조건 적용)
     * 장르, 연도, 솔로/그룹 필터에 맞는 곡이 있는 아티스트만 반환
     */
    public List<Map<String, Object>> getArtistsWithCountFiltered(Long genreId, List<Integer> years, Boolean soloOnly, Boolean groupOnly) {
        List<Song> allSongs = findSongsForGame();
        Map<String, Integer> artistCountMap = new TreeMap<>();
        Map<String, Boolean> artistSoloMap = new HashMap<>();

        for (Song song : allSongs) {
            if (song.getArtist() == null) continue;

            // 장르 필터
            if (genreId != null) {
                if (song.getGenre() == null || !song.getGenre().getId().equals(genreId)) continue;
            }

            // 연도 필터 (복수 선택)
            if (years != null && !years.isEmpty()) {
                if (song.getReleaseYear() == null || !years.contains(song.getReleaseYear())) continue;
            }

            // 솔로/그룹 필터
            if (Boolean.TRUE.equals(soloOnly)) {
                if (song.getIsSolo() == null || !song.getIsSolo()) continue;
            }
            if (Boolean.TRUE.equals(groupOnly)) {
                if (song.getIsSolo() != null && song.getIsSolo()) continue;
            }

            artistCountMap.merge(song.getArtist(), 1, Integer::sum);
            if (!artistSoloMap.containsKey(song.getArtist())) {
                artistSoloMap.put(song.getArtist(), song.getIsSolo());
            }
        }

        List<Map<String, Object>> artists = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : artistCountMap.entrySet()) {
            Map<String, Object> artist = new HashMap<>();
            artist.put("name", entry.getKey());
            artist.put("count", entry.getValue());
            artist.put("isSolo", artistSoloMap.get(entry.getKey()));
            artists.add(artist);
        }
        return artists;
    }

    // 아티스트 목록 조회 (곡 수 포함) - 아티스트 챌린지용 (레트로 제외, 매니악 곡 포함)
    @Cacheable(cacheNames = CacheConfig.SONG_CATALOG, key = "'fanChallengeArtists'")
    public List<Map<String, Object>> getArtistsWithCountForFanChallenge() {
        List<Song> allSongs = findAllSongsForArtistChallenge();
        Map<String, Integer> artistCountMap = new TreeMap<>();

        for (Song song : allSongs) {
            if (song.getArtist() != null) {
                artistCountMap.merge(song.getArtist(), 1, Integer::sum);
            }
        }

        List<Map<String, Object>> artists = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : artistCountMap.entrySet()) {
            Map<String, Object> artist = new HashMap<>();
            artist.put("name", entry.getKey());
            artist.put("count", entry.getValue());
            artists.add(artist);
        }
        return unmodifiableRows(artists);
    }

    // 아티스트 목록 조회 (곡 수 포함) - 관리자용 (전체 곡 대상, 비활성화 및 매니악 곡 포함)
    public List<Map<String, Object>> getArtistsWithCountForAdmin() {
        List<Object[]> results = songRepository.findAllArtistsWithCount();
        List<Map<String, Object>> artists = new ArrayList<>();

        for (Object[] row : results) {
            Map<String, Object> artist = new HashMap<>();
            artist.put("name", row[0]);
            artist.put("count", ((Number) row[1]).intValue());
            artists.add(artist);
        }
        return artists;
    }

    // 연도 목록 조회 (곡 수 포함) - 게임용 (레트로 제외)
    @Cacheable(cacheNames = CacheConfig.SONG_CATALOG, key = "'years'")
    public List<Map<String, Object>> getYearsWithCount() {
        List<Song> allSongs = findSongsForGame();
        Map<Integer, Integer> yearCountMap = new TreeMap<>(Collections.reverseOrder()); // 최신 연도부터

        for (Song song : allSongs) {
            if (song.getReleaseYear() != null) {
                yearCountMap.merge(song.getReleaseYear(), 1, Integer::sum);
            }
        }

        List<Map<String, Object>> years = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : yearCountMap.entrySet()) {
            Map<String, Object> yearInfo = new HashMap<>();
            yearInfo.put("year", entry.getKey());
            yearInfo.put("count", entry.getValue());
            years.add(yearInfo);
        }
        return unmodifiableRows(years);
    }

    /**
     * 캐시에 들어가는 목록은 호출자가 바꿀 수 없게 한다 — 같은 인스턴스를 다음 요청도 받기 때문.
     * (값에 null 이 있을 수 있어 Map.copyOf 대신 unmodifiableMap)
     */
    private static List<Map<String, Object>> unmodifiableRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> copy = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            copy.add(Collections.unmodifiableMap(row));
        }
        return Collections.unmodifiableList(copy);
    }

    // 아티스트 검색 (자동완성용) - 게임용 (레트로 제외)
    public List<String> searchArtists(String keyword) {
        return songRepository.findArtistsByKeywordExcludingGenre(keyword, GenreService.EXCLUDED_GENRE_CODE);
    }

    public int getAvailableSongCountByGenreExcluding(Long genreId, List<Long> excludeSongIds) {
        List<Song> allSongs = findSongsForGame();

        int count = 0;
        for (Song song : allSongs) {
            if (song.getGenre() == null || !song.getGenre().getId().equals(genreId)) continue;
            if (excludeSongIds != null && excludeSongIds.contains(song.getId())) continue;
            count++;
        }

        return count;
    }

    public Song getRandomSongByGenreExcluding(Long genreId, List<Long> excludeSongIds) {
        List<Song> allSongs = findSongsForGame();

        List<Song> filtered = new ArrayList<>();
        for (Song song : allSongs) {
            if (song.getGenre() == null || !song.getGenre().getId().equals(genreId)) continue;
            if (excludeSongIds != null && excludeSongIds.contains(song.getId())) continue;
            filtered.add(song);
        }

        if (filtered.isEmpty()) {
            return null;
        }

        Collections.shuffle(filtered);
        return filtered.get(0);
    }

    // ========== 매 라운드 선택 모드용 메서드 ==========

    // 아티스트별 사용 가능한 곡 수 (제외 목록 적용) - 게임용 (레트로 제외)
    public int getAvailableSongCountByArtistExcluding(String artist, List<Long> excludeSongIds) {
        List<Song> allSongs = findSongsForGame();

        int count = 0;
        for (Song song : allSongs) {
            if (song.getArtist() == null || !song.getArtist().equals(artist)) continue;
            if (excludeSongIds != null && excludeSongIds.contains(song.getId())) continue;
            count++;
        }

        return count;
    }

    // 아티스트로 랜덤 노래 가져오기 (제외 목록 적용) - 게임용 (레트로 제외)
    public Song getRandomSongByArtistExcluding(String artist, List<Long> excludeSongIds) {
        List<Song> allSongs = findSongsForGame();

        List<Song> filtered = new ArrayList<>();
        for (Song song : allSongs) {
            if (song.getArtist() == null || !song.getArtist().equals(artist)) continue;
            if (excludeSongIds != null && excludeSongIds.contains(song.getId())) continue;
            filtered.add(song);
        }

        if (filtered.isEmpty()) {
            return null;
        }

        Collections.shuffle(filtered);
        return filtered.get(0);
    }

    // 연도별 사용 가능한 곡 수 (제외 목록 적용) - 게임용 (레트로 제외)
    public int getAvailableSongCountByYearExcluding(Integer year, List<Long> excludeSongIds) {
        List<Song> allSongs = findSongsForGame();

        int count = 0;
        for (Song song : allSongs) {
            if (song.getReleaseYear() == null || !song.getReleaseYear().equals(year)) continue;
            if (excludeSongIds != null && excludeSongIds.contains(song.getId())) continue;
            count++;
        }

        return count;
    }

    // 연도로 랜덤 노래 가져오기 (제외 목록 적용) - 게임용 (레트로 제외)
    public Song getRandomSongByYearExcluding(Integer year, List<Long> excludeSongIds) {
        List<Song> allSongs = findSongsForGame();

        List<Song> filtered = new ArrayList<>();
        for (Song song : allSongs) {
            if (song.getReleaseYear() == null || !song.getReleaseYear().equals(year)) continue;
            if (excludeSongIds != null && excludeSongIds.contains(song.getId())) continue;
            filtered.add(song);
        }

        if (filtered.isEmpty()) {
            return null;
        }

        Collections.shuffle(filtered);
        return filtered.get(0);
    }

    // 아티스트 목록 조회 (곡 수 포함, 제외 목록 적용) - 게임용 (레트로 제외)
    public List<Map<String, Object>> getArtistsWithCountExcluding(List<Long> excludeSongIds) {
        List<Song> allSongs = findSongsForGame();
        Map<String, Integer> artistCountMap = new HashMap<>();

        for (Song song : allSongs) {
            if (song.getArtist() == null) continue;
            if (excludeSongIds != null && excludeSongIds.contains(song.getId())) continue;
            artistCountMap.merge(song.getArtist(), 1, Integer::sum);
        }

        List<Map<String, Object>> artists = new ArrayList<>();
        artistCountMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    Map<String, Object> artist = new HashMap<>();
                    artist.put("name", entry.getKey());
                    artist.put("count", entry.getValue());
                    artists.add(artist);
                });

        return artists;
    }

    // 연도 목록 조회 (곡 수 포함, 제외 목록 적용) - 게임용 (레트로 제외)
    public List<Map<String, Object>> getYearsWithCountExcluding(List<Long> excludeSongIds) {
        List<Song> allSongs = findSongsForGame();
        Map<Integer, Integer> yearCountMap = new TreeMap<>(Collections.reverseOrder());

        for (Song song : allSongs) {
            if (song.getReleaseYear() == null) continue;
            if (excludeSongIds != null && excludeSongIds.contains(song.getId())) continue;
            yearCountMap.merge(song.getReleaseYear(), 1, Integer::sum);
        }

        List<Map<String, Object>> years = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : yearCountMap.entrySet()) {
            Map<String, Object> yearInfo = new HashMap<>();
            yearInfo.put("year", entry.getKey());
            yearInfo.put("count", entry.getValue());
            years.add(yearInfo);
        }

        return years;
    }

    // ========== 정답 관련 메서드 ==========

    public List<SongAnswer> getAnswers(Long songId) {
        return songAnswerRepository.findBySongIdOrderByIsPrimaryDesc(songId);
    }

    @Transactional
    public SongAnswer addAnswer(Long songId, String answer, Boolean isPrimary) {
        Song song = songRepository.findById(songId).orElseThrow();
        SongAnswer songAnswer = new SongAnswer(song, answer, isPrimary);
        return songAnswerRepository.save(songAnswer);
    }

    @Transactional
    public void deleteAnswer(Long answerId) {
        songAnswerRepository.deleteById(answerId);
    }

    @Transactional
    public void updateAnswers(Long songId, List<String> answers) {
        songAnswerRepository.deleteBySongId(songId);
        Song song = songRepository.findById(songId).orElseThrow();

        boolean isFirst = true;
        for (String answer : answers) {
            if (answer != null && !answer.trim().isEmpty()) {
                SongAnswer songAnswer = new SongAnswer(song, answer.trim(), isFirst);
                songAnswerRepository.save(songAnswer);
                isFirst = false;
            }
        }
    }

    public boolean checkAnswer(Long songId, String userAnswer) {
        if (userAnswer == null || userAnswer.trim().isEmpty()) {
            return false;
        }

        String normalizedUserAnswer = normalizeAnswer(userAnswer);

        // 1. song_answer 테이블에서 정답 확인
        List<SongAnswer> answers = songAnswerRepository.findBySongId(songId);
        for (SongAnswer answer : answers) {
            if (normalizeAnswer(answer.getAnswer()).equals(normalizedUserAnswer)) {
                return true;
            }
        }

        // 2. song_answer가 없으면 title로 체크
        if (answers.isEmpty()) {
            Song song = songRepository.findById(songId).orElse(null);
            if (song != null) {
                return normalizeAnswer(song.getTitle()).equals(normalizedUserAnswer);
            }
        }

        return false;
    }

    private String normalizeAnswer(String answer) {
        if (answer == null) return "";
        // 공백 제거, 소문자 변환, 특수문자 제거
        return answer.toLowerCase()
                .replaceAll("\\s+", "")
                .replaceAll("[^a-z0-9가-힣]", "");
    }

    // ========== 멀티게임용 메서드 ==========

    /**
     * 랜덤 노래 가져오기 (excludeSongIds 제외) - 게임용 (레트로 제외)
     */
    public Song getRandomSongExcluding(Long genreId, Set<Long> excludeSongIds) {
        List<Song> allSongs = findSongsForGame();

        List<Song> filtered = new ArrayList<>();
        for (Song song : allSongs) {
            // 장르 필터 (genreId가 null이면 전체)
            if (genreId != null) {
                if (song.getGenre() == null || !song.getGenre().getId().equals(genreId)) continue;
            }
            // 제외 목록 체크
            if (excludeSongIds != null && excludeSongIds.contains(song.getId())) continue;
            filtered.add(song);
        }

        if (filtered.isEmpty()) {
            return null;
        }

        Collections.shuffle(filtered);
        return filtered.get(0);
    }

    /**
     * 장르별 사용 가능한 노래 수 (excludeSongIds 제외) - 게임용 (레트로 제외)
     */
    public int getAvailableCountByGenreExcluding(Long genreId, Set<Long> excludeSongIds) {
        List<Song> allSongs = findSongsForGame();

        int count = 0;
        for (Song song : allSongs) {
            if (song.getGenre() == null || !song.getGenre().getId().equals(genreId)) continue;
            if (excludeSongIds != null && excludeSongIds.contains(song.getId())) continue;
            count++;
        }

        return count;
    }

    /**
     * 랜덤 노래 가져오기 (YouTube 검증 포함, excludeSongIds 제외)
     * 멀티플레이어용 - 게임용 (레트로 제외)
     */
    public Song getValidatedRandomSongExcluding(Long genreId, Set<Long> excludeSongIds) {
        List<Song> allSongs = findSongsForGame();

        List<Song> filtered = new ArrayList<>();
        for (Song song : allSongs) {
            // 장르 필터 (genreId가 null이면 전체)
            if (genreId != null) {
                if (song.getGenre() == null || !song.getGenre().getId().equals(genreId)) continue;
            }
            // 제외 목록 체크
            if (excludeSongIds != null && excludeSongIds.contains(song.getId())) continue;
            filtered.add(song);
        }

        Collections.shuffle(filtered);

        // YouTube 검증하면서 유효한 곡 반환
        for (Song song : filtered) {
            if (song.getYoutubeVideoId() != null && !song.getYoutubeVideoId().isEmpty()) {
                YouTubeValidationService.ValidationResult result =
                        youTubeValidationService.validateVideo(song.getYoutubeVideoId());

                if (!result.isValid()) {
                    continue; // 다음 곡 시도
                }
            }
            return song;
        }

        return null;
    }

    /**
     * GameSettings 기반 랜덤 노래 가져오기 (YouTube 검증 포함, excludeSongIds 제외)
     * 멀티플레이어용 - 게임용 (레트로 제외)
     */
    public Song getValidatedRandomSongWithSettings(GameSettings settings, Set<Long> excludeSongIds) {
        List<Song> allSongs = findSongsForGame();

        List<Song> filtered = new ArrayList<>();
        for (Song song : allSongs) {
            // GameSettings 기반 필터링
            if (!matchesSettings(song, settings)) continue;
            // 제외 목록 체크
            if (excludeSongIds != null && excludeSongIds.contains(song.getId())) continue;
            filtered.add(song);
        }

        Collections.shuffle(filtered);

        // YouTube 검증하면서 유효한 곡 반환
        for (Song song : filtered) {
            if (song.getYoutubeVideoId() != null && !song.getYoutubeVideoId().isEmpty()) {
                YouTubeValidationService.ValidationResult result =
                        youTubeValidationService.validateVideo(song.getYoutubeVideoId());

                if (!result.isValid()) {
                    continue; // 다음 곡 시도
                }
            }
            return song;
        }

        return null;
    }

    // ========== YouTube 사전 검증 메서드 ==========

    /**
     * YouTube 검증 결과를 포함한 랜덤 노래 목록 가져오기
     * 무효한 곡은 자동으로 대체됨 - 게임용 (레트로 제외)
     *
     * @return ValidatedSongsResult (노래 목록 + 대체된 곡 수)
     */
    public ValidatedSongsResult getRandomSongsWithValidation(int count, GameSettings settings) {
        // 여유분 포함해서 후보 가져오기 (대체용으로 2배 요청)
        List<Song> allSongs = findSongsForGame();

        // 필터링
        List<Song> filtered = new ArrayList<>();
        for (Song song : allSongs) {
            if (!matchesSettings(song, settings)) continue;
            filtered.add(song);
        }

        Collections.shuffle(filtered);

        List<Song> validSongs = new ArrayList<>();
        Set<Long> usedSongIds = new HashSet<>();
        int replacedCount = 0;
        int validationFailCount = 0;

        for (Song song : filtered) {
            if (validSongs.size() >= count) break;
            if (usedSongIds.contains(song.getId())) continue;

            // YouTube 검증
            if (song.getYoutubeVideoId() != null && !song.getYoutubeVideoId().isEmpty()) {
                YouTubeValidationService.ValidationResult result =
                        youTubeValidationService.validateVideo(song.getYoutubeVideoId());

                if (!result.isValid()) {
                    validationFailCount++;
                    continue; // 다음 곡으로 (대체됨)
                }
            }

            validSongs.add(song);
            usedSongIds.add(song.getId());
        }

        // 검증 실패로 인해 대체된 곡 수 계산
        // count 요청했는데 실제로 validationFailCount개가 스킵됨
        replacedCount = Math.min(validationFailCount, count);

        return new ValidatedSongsResult(validSongs, replacedCount);
    }

    /**
     * 단일 곡 YouTube 검증 후 유효한 곡 반환 (장르 기준)
     * 무효시 같은 장르의 다른 곡으로 대체 - 게임용 (레트로 제외)
     */
    public ValidatedSongResult getValidatedSongByGenre(Long genreId, List<Long> excludeSongIds) {
        List<Song> allSongs = findSongsForGame();

        List<Song> filtered = new ArrayList<>();
        for (Song song : allSongs) {
            if (song.getGenre() == null || !song.getGenre().getId().equals(genreId)) continue;
            if (excludeSongIds != null && excludeSongIds.contains(song.getId())) continue;
            filtered.add(song);
        }

        Collections.shuffle(filtered);

        for (Song song : filtered) {
            if (song.getYoutubeVideoId() != null && !song.getYoutubeVideoId().isEmpty()) {
                YouTubeValidationService.ValidationResult result =
                        youTubeValidationService.validateVideo(song.getYoutubeVideoId());

                if (!result.isValid()) {
                    continue; // 다음 곡 시도
                }
            }
            return new ValidatedSongResult(song, false);
        }

        return new ValidatedSongResult(null, false);
    }

    /**
     * 아티스트의 모든 곡을 YouTube 검증 후 반환 (팬 챌린지용)
     * 무효한 곡은 제외됨 - 매니악 곡 포함 (팬이라면 알아야 함)
     */
    public List<Song> getAllValidatedSongsByArtist(String artist) {
        List<Song> allSongs = findAllSongsForArtistChallenge();

        List<Song> filtered = new ArrayList<>();
        for (Song song : allSongs) {
            if (song.getArtist() == null || !song.getArtist().equals(artist)) continue;
            filtered.add(song);
        }

        // YouTube 검증
        List<Song> validSongs = new ArrayList<>();
        for (Song song : filtered) {
            if (song.getYoutubeVideoId() != null && !song.getYoutubeVideoId().isEmpty()) {
                YouTubeValidationService.ValidationResult result =
                        youTubeValidationService.validateVideo(song.getYoutubeVideoId());
                if (!result.isValid()) {
                    continue;
                }
            }
            validSongs.add(song);
        }

        // 셔플 후 반환
        Collections.shuffle(validSongs);
        return validSongs;
    }

    /**
     * 아티스트의 전체 곡 수 조회 (팬 챌린지 설정용) - 레트로 장르 제외, 매니악 곡 포함
     */
    public int getSongCountByArtist(String artist) {
        List<Song> allSongs = findAllSongsForArtistChallenge();
        int count = 0;
        for (Song song : allSongs) {
            if (song.getArtist() != null && song.getArtist().equals(artist)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 단일 곡 YouTube 검증 후 유효한 곡 반환 (아티스트 기준) - 게임용 (레트로 제외)
     */
    public ValidatedSongResult getValidatedSongByArtist(String artist, List<Long> excludeSongIds) {
        List<Song> allSongs = findSongsForGame();

        List<Song> filtered = new ArrayList<>();
        for (Song song : allSongs) {
            if (song.getArtist() == null || !song.getArtist().equals(artist)) continue;
            if (excludeSongIds != null && excludeSongIds.contains(song.getId())) continue;
            filtered.add(song);
        }

        Collections.shuffle(filtered);

        for (Song song : filtered) {
            if (song.getYoutubeVideoId() != null && !song.getYoutubeVideoId().isEmpty()) {
                YouTubeValidationService.ValidationResult result =
                        youTubeValidationService.validateVideo(song.getYoutubeVideoId());

                if (!result.isValid()) {
                    continue;
                }
            }
            return new ValidatedSongResult(song, false);
        }

        return new ValidatedSongResult(null, false);
    }

    /**
     * 단일 곡 YouTube 검증 후 유효한 곡 반환 (연도 기준) - 게임용 (레트로 제외)
     */
    public ValidatedSongResult getValidatedSongByYear(Integer year, List<Long> excludeSongIds) {
        List<Song> allSongs = findSongsForGame();

        List<Song> filtered = new ArrayList<>();
        for (Song song : allSongs) {
            if (song.getReleaseYear() == null || !song.getReleaseYear().equals(year)) continue;
            if (excludeSongIds != null && excludeSongIds.contains(song.getId())) continue;
            filtered.add(song);
        }

        Collections.shuffle(filtered);

        for (Song song : filtered) {
            if (song.getYoutubeVideoId() != null && !song.getYoutubeVideoId().isEmpty()) {
                YouTubeValidationService.ValidationResult result =
                        youTubeValidationService.validateVideo(song.getYoutubeVideoId());

                if (!result.isValid()) {
                    continue;
                }
            }
            return new ValidatedSongResult(song, false);
        }

        return new ValidatedSongResult(null, false);
    }

    /**
     * 검증된 노래 목록 결과
     */
    public static class ValidatedSongsResult {
        private final List<Song> songs;
        private final int replacedCount;

        public ValidatedSongsResult(List<Song> songs, int replacedCount) {
            this.songs = songs;
            this.replacedCount = replacedCount;
        }

        public List<Song> getSongs() {
            return songs;
        }

        public int getReplacedCount() {
            return replacedCount;
        }
    }

    /**
     * 검증된 단일 노래 결과
     */
    public static class ValidatedSongResult {
        private final Song song;
        private final boolean wasReplaced;

        public ValidatedSongResult(Song song, boolean wasReplaced) {
            this.song = song;
            this.wasReplaced = wasReplaced;
        }

        public Song getSong() {
            return song;
        }

        public boolean wasReplaced() {
            return wasReplaced;
        }
    }

    // ========== 이력 기반 곡 관리 메서드 ==========

    /**
     * Soft Delete - useYn='N'으로 변경
     */
    @CacheEvict(cacheNames = CacheConfig.SONG_CATALOG, allEntries = true)
    @Transactional
    public void softDeleteSong(Long songId) {
        Song song = songRepository.findById(songId)
                .orElseThrow(() -> new IllegalArgumentException("곡을 찾을 수 없습니다: " + songId));
        song.setUseYn("N");
        songRepository.save(song);
        log.info("곡 soft delete: {} - {}", song.getArtist(), song.getTitle());
    }

    /**
     * 곡 복구 - useYn='Y'로 변경
     */
    @CacheEvict(cacheNames = CacheConfig.SONG_CATALOG, allEntries = true)
    @Transactional
    public void restoreSong(Long songId) {
        Song song = songRepository.findById(songId)
                .orElseThrow(() -> new IllegalArgumentException("곡을 찾을 수 없습니다: " + songId));
        song.setUseYn("Y");
        songRepository.save(song);
        log.info("곡 복구: {} - {}", song.getArtist(), song.getTitle());
    }

    /**
     * 현재 활성 곡 수 조회
     */
    public int countActiveSongsByArtist(String artist) {
        return songRepository.countActiveSongsByArtist(artist);
    }

    // ========== 장르 챌린지용 메서드 ==========

    /**
     * 장르별 유효한 곡 조회 (YouTube 또는 MP3)
     * - DB에서 이미 YouTube 검증된 곡만 조회 (isYoutubeValid = true 또는 null)
     * - 실시간 검증 제거로 성능 개선
     */
    public List<Song> getAllValidatedSongsByGenreCode(String genreCode) {
        List<Song> songs = songRepository.findByGenreCodeAndHasAudioSource(genreCode);

        // 셔플 후 반환
        List<Song> shuffledSongs = new ArrayList<>(songs);
        Collections.shuffle(shuffledSongs);
        return shuffledSongs;
    }

    /**
     * 장르별 곡 수 조회 (장르 챌린지 설정용)
     */
    public int getSongCountByGenreCode(String genreCode) {
        return songRepository.countByGenreCodeAndHasAudioSource(genreCode);
    }

    /**
     * 장르 목록 조회 (곡 수 포함) - 장르 챌린지용
     */
    public List<Map<String, Object>> getGenresWithSongCount() {
        List<Object[]> results = songRepository.findGenresWithSongCount();
        List<Map<String, Object>> genres = new ArrayList<>();

        for (Object[] row : results) {
            Map<String, Object> genre = new HashMap<>();
            genre.put("code", row[0]);
            genre.put("name", row[1]);
            genre.put("count", ((Number) row[2]).intValue());
            genres.add(genre);
        }
        return genres;
    }

    /**
     * 장르 목록 조회 (최소 곡 수 이상) - 장르 챌린지용
     */
    public List<Map<String, Object>> getGenresWithSongCountMinimum(int minCount) {
        List<Object[]> results = songRepository.findGenresWithSongCountMinimum(minCount);
        List<Map<String, Object>> genres = new ArrayList<>();

        for (Object[] row : results) {
            Map<String, Object> genre = new HashMap<>();
            genre.put("code", row[0]);
            genre.put("name", row[1]);
            genre.put("count", ((Number) row[2]).intValue());
            genres.add(genre);
        }
        return genres;
    }

    // ========== 아티스트 관리 (병합) ==========

    /**
     * 아티스트 병합 (fromArtist → toArtist)
     * song, fan_challenge_record, badge 테이블의 아티스트명 일괄 변경
     *
     * @param fromArtist 변경할 아티스트명 (사라질 이름)
     * @param toArtist 새 아티스트명 (대표명)
     * @return 변경 결과 (테이블별 변경 건수)
     */
    @CacheEvict(cacheNames = CacheConfig.SONG_CATALOG, allEntries = true)
    @Transactional
    public ArtistMergeResult mergeArtist(String fromArtist, String toArtist) {
        if (fromArtist == null || fromArtist.trim().isEmpty()) {
            throw new BusinessException("변경할 아티스트명을 입력해주세요.");
        }
        if (toArtist == null || toArtist.trim().isEmpty()) {
            throw new BusinessException("새 아티스트명을 입력해주세요.");
        }
        if (fromArtist.equals(toArtist)) {
            throw new BusinessException("동일한 아티스트명으로는 변경할 수 없습니다.");
        }

        // 1. Song 테이블 업데이트
        int songCount = songRepository.updateArtistName(fromArtist, toArtist);

        // 2. FanChallengeRecord 테이블 업데이트
        int fanChallengeCount = fanChallengeRecordRepository.updateArtistName(fromArtist, toArtist);

        // 3. Badge 테이블 업데이트
        int badgeCount = badgeRepository.updateArtistName(fromArtist, toArtist);

        log.info("아티스트 병합 완료: '{}' → '{}' (Song: {}, FanChallenge: {}, Badge: {})",
                fromArtist, toArtist, songCount, fanChallengeCount, badgeCount);

        return new ArtistMergeResult(fromArtist, toArtist, songCount, fanChallengeCount, badgeCount);
    }

    /**
     * 특정 아티스트의 곡 수 조회
     */
    public long countSongsByArtist(String artist) {
        return songRepository.countByArtist(artist);
    }

    /**
     * 아티스트 존재 여부 확인
     */
    public boolean artistExists(String artist) {
        return songRepository.existsByArtist(artist);
    }

    /**
     * 아티스트 병합 결과 DTO
     */
    public static class ArtistMergeResult {
        private final String fromArtist;
        private final String toArtist;
        private final int songCount;
        private final int fanChallengeCount;
        private final int badgeCount;

        public ArtistMergeResult(String fromArtist, String toArtist, int songCount, int fanChallengeCount, int badgeCount) {
            this.fromArtist = fromArtist;
            this.toArtist = toArtist;
            this.songCount = songCount;
            this.fanChallengeCount = fanChallengeCount;
            this.badgeCount = badgeCount;
        }

        public String getFromArtist() { return fromArtist; }
        public String getToArtist() { return toArtist; }
        public int getSongCount() { return songCount; }
        public int getFanChallengeCount() { return fanChallengeCount; }
        public int getBadgeCount() { return badgeCount; }
        public int getTotalCount() { return songCount + fanChallengeCount + badgeCount; }
    }

    // FanChallengeRecordRepository, BadgeRepository 주입 필요
    @org.springframework.beans.factory.annotation.Autowired
    private FanChallengeRecordRepository fanChallengeRecordRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private BadgeRepository badgeRepository;
}