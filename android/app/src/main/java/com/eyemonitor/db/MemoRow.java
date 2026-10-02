package com.eyemonitor.db;

import androidx.room.Embedded;

/**
 * 备忘录 + 子项数（列表页角标用）。
 */
public class MemoRow {

    @Embedded
    public MemoEntity memo;

    public int itemCount;

    public MemoRow() {}
}
