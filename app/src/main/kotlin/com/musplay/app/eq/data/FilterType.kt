// FilterType.kt

package com.musplay.app.eq.data

import kotlinx.serialization.Serializable

@Serializable
enum class FilterType {

    PK,

    LSC,

    HSC,

    LPQ,

    HPQ
}
