/**
 * BC 越境で受け渡される DTO 配置先。
 *
 * <p>命名規約 (docs/architecture.md 参照):
 *
 * <ul>
 *   <li>{@code application/*View.java} / {@code application/*Summary.java}: 自 BC 内部の view (B の
 *       MovieSummary / ScreeningDetailView など)
 *   <li>{@code application/dto/*.java}: <strong>cross-BC</strong> 契約 (本 package)
 * </ul>
 */
package com.theater.reservation.application.dto;
