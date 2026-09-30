package com.kh.game.party;

/** 바뀔 때마다 버전이 오르는 상태. 화면은 버전이 달라졌을 때만 다시 그린다. */
interface PartyVersioned {

    long getVersion();

    void setVersion(long version);
}
