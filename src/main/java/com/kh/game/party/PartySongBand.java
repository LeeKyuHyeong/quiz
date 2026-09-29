package com.kh.game.party;

import java.util.ArrayList;
import java.util.List;

/**
 * 노래 중분류: 발매연도 5년 묶음. from·to 는 포함, null 은 열린 쪽.
 */
public record PartySongBand(String label, Integer from, Integer to) {

    private static final int FIRST_YEAR = 1990;
    private static final int LAST_BAND_START = 2025;
    private static final List<PartySongBand> ALL = build();

    private static List<PartySongBand> build() {
        List<PartySongBand> bands = new ArrayList<>();
        bands.add(new PartySongBand(FIRST_YEAR + " 이전", null, FIRST_YEAR - 1));
        for (int start = FIRST_YEAR; start < LAST_BAND_START; start += 5) {
            bands.add(new PartySongBand(start + "~" + (start + 4), start, start + 4));
        }
        bands.add(new PartySongBand(LAST_BAND_START + "~", LAST_BAND_START, null));
        return List.copyOf(bands);
    }

    public static List<PartySongBand> all() {
        return ALL;
    }

    public static PartySongBand of(int year) {
        return ALL.stream().filter(band -> band.contains(year)).findFirst().orElseThrow();
    }

    public static PartySongBand ofLabel(String label) {
        return ALL.stream().filter(band -> band.label().equals(label)).findFirst()
                .orElseThrow(() -> new PartyGameException("노래 묶음을 알 수 없습니다: '" + label + "'"));
    }

    public boolean contains(Integer year) {
        return year != null && (from == null || year >= from) && (to == null || year <= to);
    }
}
