package com.san.bastionpay.admin;

import java.util.List;

/** 10건씩 나눈 목록 한 쪽. 쪽 번호는 1부터이고, 범위를 벗어나면 가까운 쪽으로 맞춘다. */
public record PagedList<T>(List<T> rows, int page, int totalPages, long total) {

    public static final int PAGE_SIZE = 10;

    /** 이미 다 가져온 목록을 메모리에서 10건씩 자른다(집계처럼 건수가 한정된 목록용). */
    public static <T> PagedList<T> of(List<T> all, int requestedPage) {
        int totalPages = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.min(Math.max(requestedPage, 1), totalPages);
        int from = (page - 1) * PAGE_SIZE;
        return new PagedList<>(all.subList(Math.min(from, all.size()), Math.min(from + PAGE_SIZE, all.size())), page, totalPages, all.size());
    }

    public static <T> PagedList<T> of(org.springframework.data.domain.Page<T> page) {
        return new PagedList<>(page.getContent(), page.getNumber() + 1, Math.max(1, page.getTotalPages()), page.getTotalElements());
    }
}
