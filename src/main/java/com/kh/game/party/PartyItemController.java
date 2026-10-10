package com.kh.game.party;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 파티 문제 관리 경로. TSV 가져오기 · 현황 요약 · 목록 · 사용 여부(숨기기). 사진 올리기는 나중(2026-10-10 결정).
 */
@RestController
@RequestMapping("/admin/party/items")
@RequiredArgsConstructor
public class PartyItemController {

    private static final long MAX_BYTES = 5L * 1024 * 1024;

    private final PartyItemImportService importService;
    private final PartyItemRepository partyItemRepository;
    private final PartyGameService gameService;
    private final PartyImageStore imageStore;

    @PostMapping("/import")
    public Map<String, Object> importTsv(@RequestParam MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new PartyInputException("빈 파일입니다");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new PartyInputException("파일이 5MB 를 넘습니다");
        }
        String content = decodeUtf8(file.getBytes());
        PartyImportResult result;
        // 겹쳐 들어온 두 번의 올리기(더블클릭·재시도)가 서로의 새 행을 못 보고 같은 문제를 두 번 넣지 않게 한 번에 하나만
        synchronized (this) {
            result = importService.importTsv(content);
        }
        return Map.of("success", true, "created", result.created(), "updated", result.updated(),
                "errors", result.errors(), "missing", result.missing());
    }

    /**
     * UTF-8 이 아니면 거부한다. 엑셀의 "텍스트(탭으로 분리)" 는 CP949, "유니코드 텍스트" 는 UTF-16 으로 저장해서
     * 그대로 읽으면 글자가 깨져 전 행이 엉뚱한 이유로 거부된다.
     */
    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new PartyInputException("UTF-8 파일이 아닙니다. 엑셀에서 저장했다면 메모장 등에서 UTF-8 로 다시 저장하세요");
        }
    }

    /** 대분류별 전체·꺼짐·낼 수 있음·미완성(재생·표시할 것이 아직 없음) 수. */
    @GetMapping("/summary")
    public List<Map<String, Object>> summary() {
        List<PartyItem> all = partyItemRepository.findAll();
        Set<Long> playable = gameService.playableItems().stream().map(PartyItem::getId).collect(Collectors.toSet());

        List<Map<String, Object>> rows = new ArrayList<>();
        for (PartyCategory category : PartyCategory.values()) {
            List<PartyItem> items = all.stream().filter(item -> item.getCategory() == category).toList();
            long off = items.stream().filter(item -> !"Y".equals(item.getUseYn())).count();
            // 스피드퀴즈 제시어는 정답이 곧 제시어라 켜져 있으면 낼 수 있다
            long ready = category == PartyCategory.SPEED ? items.size() - off
                    : items.stream().filter(item -> playable.contains(item.getId())).count();
            rows.add(Map.of("category", category.name(), "total", items.size(), "off", off,
                    "playable", ready, "incomplete", items.size() - off - ready));
        }
        return rows;
    }

    /**
     * 대분류 하나의 문제 목록(정답 포함 — MC 전용 화면에서만 쓴다). status 는 PLAYABLE·OFF·INCOMPLETE,
     * INCOMPLETE·OFF 면 reason 에 이유.
     */
    @GetMapping("/list")
    public List<Map<String, Object>> list(@RequestParam String category) {
        PartyCategory parsed = parseCategory(category);
        return partyItemRepository.findAll().stream()
                .filter(item -> item.getCategory() == parsed)
                .map(this::toRow)
                .toList();
    }

    /**
     * 사용 여부. N 이면 출제·남은 수에서 빠진다(지우지 않으므로 Y 로 되돌릴 수 있다).
     * TSV 에서 지운 행이나 정답 표기를 고치기 전의 옛 행을 숨기는 데 쓴다(O-030 ④).
     */
    @PostMapping("/{id}/use")
    public Map<String, Object> setUse(@PathVariable long id, @RequestParam String useYn) {
        if (!"Y".equals(useYn) && !"N".equals(useYn)) {
            throw new PartyInputException("사용 여부는 Y 또는 N 입니다");
        }
        PartyItem item = partyItemRepository.findById(id)
                .orElseThrow(() -> new PartyInputException("문제를 찾을 수 없습니다: " + id));
        item.setUseYn(useYn);
        partyItemRepository.save(item);
        return Map.of("success", true, "item", toRow(item));
    }

    private Map<String, Object> toRow(PartyItem item) {
        String reason = unplayableReason(item);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", item.getId());
        row.put("category", item.getCategory().name());
        row.put("subCategory", item.getSubCategory());
        row.put("presentation", item.getPresentation().name());
        row.put("answer", item.getAnswer());
        row.put("useYn", item.getUseYn());
        row.put("status", !"Y".equals(item.getUseYn()) ? "OFF" : reason == null ? "PLAYABLE" : "INCOMPLETE");
        row.put("reason", !"Y".equals(item.getUseYn()) ? "사용 안 함" : reason);
        return row;
    }

    /** 켜져 있어도 낼 수 없는 이유. 낼 수 있으면 null. PartyItemRepository#findPlayable·playableItems 와 같은 기준. */
    private String unplayableReason(PartyItem item) {
        if (item.getCategory() == PartyCategory.SPEED) {
            return null;
        }
        switch (item.getPresentation()) {
            case AUDIO:
                if (item.getYoutubeVideoId() == null) {
                    return "YouTube URL 없음";
                }
                return Boolean.FALSE.equals(item.getIsYoutubeValid()) ? "재생 불가로 표시된 영상" : null;
            case IMAGE:
                if (item.getImagePath() == null) {
                    return "사진 파일명 없음";
                }
                return imageStore.exists(item.getImagePath()) ? null : "사진 파일 없음: " + item.getImagePath();
            default:
                return null;
        }
    }

    private static PartyCategory parseCategory(String category) {
        try {
            return PartyCategory.valueOf(category);
        } catch (IllegalArgumentException e) {
            throw new PartyInputException("대분류를 알 수 없습니다: '" + category + "'");
        }
    }
}
