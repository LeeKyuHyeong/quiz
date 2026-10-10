package com.kh.game.party;

import java.util.List;

/**
 * TSV 가져오기 결과. line 은 파일의 줄 번호(머리글이 1).
 * missing 은 이 파일에 나온 대분류의 켜진 문제 중 파일에 없는 것 — TSV 에서 지웠거나 정답 표기를 고치기 전의
 * 옛 행일 수 있다. 일부만 올린 파일일 수도 있으므로 숨기지는 않고 알리기만 한다(O-030 ④).
 */
public record PartyImportResult(int created, int updated, List<RowError> errors, List<Missing> missing) {

    public record RowError(int line, String reason) {
    }

    public record Missing(long id, String category, String answer) {
    }
}
