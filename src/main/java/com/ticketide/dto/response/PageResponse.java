package com.ticketide.dto.response;

import lombok.Data;

import java.util.List;

@Data
public class PageResponse<T> {

    private List<T> records;

    private Long total;

    private Integer size;

    private Integer current;

    private Integer pages;

    public PageResponse(List<T> records, Long total, Integer size, Integer current) {
        this.records = records;
        this.total = total;
        this.size = size;
        this.current = current;
        this.pages = (int) Math.ceil((double) total / size);
    }
}
