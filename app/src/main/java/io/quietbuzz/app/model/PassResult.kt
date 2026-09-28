package io.quietbuzz.app.model

data class PassResult(
    val appsChanged: Int,
    val channelsChanged: Int,
    val failures: Int,
    val associationLost: Boolean,
)
