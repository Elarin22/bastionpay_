package com.san.bastionpay.app;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * 번호 페이지 이동. 현재 쪽 앞뒤 2쪽씩(최대 5개)과 처음·마지막 쪽을 보여 주고 사이는 줄임표로 둔다.
 * 링크 주소는 호출하는 쪽이 만들어 넘긴다(조회 조건을 유지하기 위해).
 */
public record Pager(int page, int totalPages, List<Item> items, String prevHref, String nextHref) {

    /** ellipsis 가 true 이면 번호 대신 줄임표를 보여 준다. */
    public record Item(int number, String href, boolean current, boolean ellipsis) {
    }

    private static final int WINDOW = 5;

    public static Pager of(int page, int totalPages, IntFunction<String> href) {
        int total = Math.max(totalPages, 1);
        int current = Math.min(Math.max(page, 1), total);
        int start = Math.max(1, current - WINDOW / 2);
        int end = Math.min(total, start + WINDOW - 1);
        start = Math.max(1, end - WINDOW + 1);

        List<Item> items = new ArrayList<>();
        if (start > 1) {
            items.add(new Item(1, href.apply(1), false, false));
            if (start > 2) items.add(new Item(0, null, false, true));
        }
        for (int i = start; i <= end; i++) items.add(new Item(i, href.apply(i), i == current, false));
        if (end < total) {
            if (end < total - 1) items.add(new Item(0, null, false, true));
            items.add(new Item(total, href.apply(total), false, false));
        }
        return new Pager(current, total, List.copyOf(items), current > 1 ? href.apply(current - 1) : null,
                current < total ? href.apply(current + 1) : null);
    }

    /** 쪽이 둘 이상일 때만 이동 줄을 보여 준다. */
    public boolean isNeeded() {
        return totalPages > 1;
    }
}
