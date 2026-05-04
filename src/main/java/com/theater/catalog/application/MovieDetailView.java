package com.theater.catalog.application;

import java.util.List;

/** Movie detail for CT-03. */
public record MovieDetailView(
    String movieId,
    String title,
    String description,
    int durationMinutes,
    List<ScreeningSummary> upcomingScreenings) {}
