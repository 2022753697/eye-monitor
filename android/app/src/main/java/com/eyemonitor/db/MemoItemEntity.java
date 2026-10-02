package com.eyemonitor.db;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.PrimaryKey;

/**
 * 备忘录可勾选子项（清单能力）。
 * <p>
 * memoId 外键级联删除：删除备忘录时子项一并清除。
 */
@Entity(tableName = "memo_item",
        indices = {@androidx.room.Index(value = "memoId")},
        foreignKeys = @ForeignKey(entity = MemoEntity.class,
                parentColumns = "id", childColumns = "memoId",
                onDelete = ForeignKey.CASCADE))
public class MemoItemEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public long memoId;

    /** 子项文本 */
    public String text;

    /** 勾选态 */
    public boolean checked;

    public MemoItemEntity() {}
}
