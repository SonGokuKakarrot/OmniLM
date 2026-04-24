version = "1.0.0"
description = "Aggressive communication audio profile for Aliucord voice calls"

aliucord {
    changelog.set(
        """
        # 1.0.0
        * Initial LoudMic release.
        * Sets communication mode, unmutes mic, and maxes voice-call stream while enabled.
        """.trimIndent(),
    )

    deploy.set(true)
}
