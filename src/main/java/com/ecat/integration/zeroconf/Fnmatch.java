/*
 * Copyright (c) 2026 ECAT Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.ecat.integration.zeroconf;

/**
 * fnmatch 风格的 glob 匹配（HA-faithful zeroconf 匹配用）。
 * <p>支持通配符：{@code *} 匹配任意长度任意字符（含空），{@code ?} 匹配单字符。
 * 其余字符按字面匹配。整个串全匹配（anchored）。
 *
 * <p>HA zeroconf properties 值匹配语义：声明值如 {@code prod-*} 匹配实际值 {@code prod-v1}。
 * 参考 HA architecture #198。
 *
 * <p>注意：本实现不支持字符类 {@code [...]}（ECAT discovery 场景仅需 {@code *} / {@code ?}）。
 *
 * @author coffee
 */
public final class Fnmatch {

    private Fnmatch() {
    }

    /**
     * @param pattern glob 模式（如 {@code prod-*}）
     * @param text    待匹配文本（如 {@code prod-v1}）
     * @return 全匹配返回 true；pattern/text 为 null 返回 false
     */
    public static boolean match(String pattern, String text) {
        if (pattern == null || text == null) {
            return false;
        }
        return text.matches(toRegex(pattern));
    }

    /**
     * glob → 正则（转义 regex 元字符，仅保留 {@code *}→{@code .*}、{@code ?}→{@code .}）。
     */
    private static String toRegex(String pattern) {
        StringBuilder sb = new StringBuilder(pattern.length() + 8);
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*':
                    sb.append(".*");
                    break;
                case '?':
                    sb.append('.');
                    break;
                case '.':
                case '\\':
                case '+':
                case '(':
                case ')':
                case '[':
                case ']':
                case '{':
                case '}':
                case '^':
                case '$':
                case '|':
                    sb.append('\\').append(c);
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }
}
