package com.yigongbao.backup.storage.volume;

/** 由卷访问策略生成、可嵌入 MyBatis Provider 的只读 SQL 谓词。 */
public record StorageVolumeAccessSqlPredicate(String sql) {
    public StorageVolumeAccessSqlPredicate {
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("存储卷访问 SQL 谓词不能为空");
    }

    /** 查询中卷表和封存尝试表的别名；仅接受 SQL 标识符，避免 Provider 拼接外部输入。 */
    public record SqlAliases(String volume, String archive) {
        public SqlAliases {
            volume = identifier(volume, "卷表");
            archive = identifier(archive, "封存表");
        }

        private static String identifier(String value, String label) {
            if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException(label + "别名不是合法 SQL 标识符");
            }
            return value;
        }
    }
}
