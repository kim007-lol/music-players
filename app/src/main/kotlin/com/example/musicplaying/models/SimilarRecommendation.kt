// SimilarRecommendation.kt

package com.example.musicplaying.models

import com.music.innertube.models.YTItem
import com.example.musicplaying.db.entities.LocalItem

data class SimilarRecommendation(
    val title: LocalItem,
    val items: List<YTItem>,
)
