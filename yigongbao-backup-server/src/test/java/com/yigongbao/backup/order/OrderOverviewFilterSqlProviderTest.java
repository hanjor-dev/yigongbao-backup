package com.yigongbao.backup.order;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderOverviewFilterSqlProviderTest {
    @Test
    void filtersCurrentAccessBeforeStablePaginationAndKeepsCountUnpaged() {
        String select = normalize(OrderOverviewFilterSqlProvider.selectIds());
        String count = normalize(OrderOverviewFilterSqlProvider.count());

        assertThat(select).contains("s.backup_mode=#{backupmode}", "v.mount_status='online'",
                "v.operation_status='archive_guard'", "not exists", "limit #{limit} offset #{offset}");
        assertThat(select.indexOf("order by s.order_created_at desc,s.id desc"))
                .isLessThan(select.indexOf("limit #{limit}"));
        assertThat(count).doesNotContain("order by", "limit #{limit}").contains("select count(*)");
    }

    @Test
    void unreadableExcludesExpectedOfflineCopiesButIncludesMissingOrDamagedCopies() {
        String sql = normalize(OrderOverviewFilterSqlProvider.selectIds());
        assertThat(sql).contains("tier='cold'", "mount_status='offline'", "write_status='read_only'",
                "c.state not in ('present','verified')", "c.storage_volume_id is null", "v.id is null",
                "s.backup_mode='real'", "s.snapshot_status='available'");
    }

    private static String normalize(String sql) {
        return sql.toLowerCase().replaceAll("\\s+", " ");
    }
}
