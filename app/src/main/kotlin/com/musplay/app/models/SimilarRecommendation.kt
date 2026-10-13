// SimilarRecommendation.kt

package com.musplay.app.models

import com.music.innertube.models.YTItem
import com.musplay.app.db.entities.LocalItem

data class SimilarRecommendation(
    val title: LocalItem,
    val items: List<YTItem>,
)
