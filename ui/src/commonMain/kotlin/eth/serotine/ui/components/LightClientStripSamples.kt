package eth.serotine.ui.components

// The status strip as the screens' previews show it: made-up blocks and ages, one per colour.

/** Green: reads verified at a fresh head. */
internal val verifiedStripSample = LightClientItem(
    "Verified at block 8,412,307 · 8 s ago",
    null,
    false,
    LightClientHealth.VERIFIED,
)

/** Yellow: the verified head too old to read at. */
internal val staleStripSample = LightClientItem(
    "Synced · the verified head is 4 min old, waiting for a fresh one",
    null,
    true,
    LightClientHealth.STALE,
)

/** Red: no verified head yet, the sync committees still being verified. */
internal val catchingUpStripSample = LightClientItem(
    "Verifying sync committees · 1 of 4 periods",
    0.25f,
    true,
    LightClientHealth.NOT_VERIFIED,
)
