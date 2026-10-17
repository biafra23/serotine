package eth.serotine.domain.model

/**
 * One thing the user is told, outside the app, about an [upgrade] this version does not
 * support: that it is announced, that it is close, or that it has happened. Which [kind] is
 * due when is [eth.serotine.domain.usecase.UpgradeNotices]'s decision.
 */
data class UpgradeNotice(val kind: Kind, val upgrade: NetworkUpgrade) {

    /** The moments the user hears about an upgrade, least urgent first. */
    enum class Kind {
        /** Peers announce it, for a time ahead or one not known yet. */
        ANNOUNCED,

        /** It activates within a week. */
        WEEK_AHEAD,

        /** It activates within a day. */
        DAY_AHEAD,

        /** It has activated: this version can no longer follow the network. The last notice about it. */
        ACTIVATED,
    }
}
