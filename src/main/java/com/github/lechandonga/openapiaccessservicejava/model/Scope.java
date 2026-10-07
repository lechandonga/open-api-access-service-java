package com.github.lechandonga.openapiaccessservicejava.model;

/**
 * 权限范围。权限范围全部使用常量字符串，避免大小写/别名造成的不明确匹配。
 */
public final class Scope {
    /** 允许写入数据（POST /api/data）。 */
    public static final String DATA_WRITE = "data:write";
    /** 允许读取数据与查询配额（GET /api/data/**、GET /api/quota）。 */
    public static final String DATA_READ = "data:read";

    private Scope() {
    }
}
