package com.linlay.springaiagw.model.agw;

public enum ViewportType {
    JSON_SCHEMA("json_schema"),
    HTML("html"),
    QLC("qlc"),
    DQLC("dqlc"),
    CUSTOM("custom");

    private final String value;

    ViewportType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
