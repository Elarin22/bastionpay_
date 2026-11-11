package com.san.bastionpay.app;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PagerTest {

    private static Pager pager(int page, int total) {
        return Pager.of(page, total, n -> "/p?page=" + n);
    }

    private static List<String> labels(Pager p) {
        return p.items().stream().map(i -> i.ellipsis() ? "…" : (i.current() ? "[" + i.number() + "]" : String.valueOf(i.number()))).toList();
    }

    @Test
    void singlePageNeedsNoPager() {
        assertThat(pager(1, 1).isNeeded()).isFalse();
        assertThat(pager(1, 0).isNeeded()).isFalse();
    }

    @Test
    void fewPagesAreAllShownWithPrevNext() {
        Pager p = pager(2, 3);

        assertThat(labels(p)).containsExactly("1", "[2]", "3");
        assertThat(p.prevHref()).isEqualTo("/p?page=1");
        assertThat(p.nextHref()).isEqualTo("/p?page=3");
    }

    @Test
    void firstAndLastPagesHaveOnlyOneDirection() {
        assertThat(pager(1, 5).prevHref()).isNull();
        assertThat(pager(1, 5).nextHref()).isEqualTo("/p?page=2");
        assertThat(pager(5, 5).nextHref()).isNull();
        assertThat(pager(5, 5).prevHref()).isEqualTo("/p?page=4");
    }

    @Test
    void manyPagesShowAWindowWithFirstLastAndGaps() {
        assertThat(labels(pager(1, 20))).containsExactly("[1]", "2", "3", "4", "5", "…", "20");
        assertThat(labels(pager(10, 20))).containsExactly("1", "…", "8", "9", "[10]", "11", "12", "…", "20");
        assertThat(labels(pager(20, 20))).containsExactly("1", "…", "16", "17", "18", "19", "[20]");
        assertThat(labels(pager(4, 8))).containsExactly("1", "2", "3", "[4]", "5", "6", "…", "8");
    }

    @Test
    void outOfRangePageIsClamped() {
        assertThat(pager(99, 5).page()).isEqualTo(5);
        assertThat(pager(-3, 5).page()).isEqualTo(1);
    }
}
