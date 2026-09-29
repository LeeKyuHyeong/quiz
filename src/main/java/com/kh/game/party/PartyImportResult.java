package com.kh.game.party;

import java.util.List;

/**
 * TSV 가져오기 결과. line 은 파일의 줄 번호(머리글이 1).
 */
public record PartyImportResult(int created, int updated, List<RowError> errors) {

    public record RowError(int line, String reason) {
    }
}
