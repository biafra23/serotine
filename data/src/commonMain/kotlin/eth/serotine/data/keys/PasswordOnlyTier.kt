package eth.serotine.data.keys

/**
 * The Argon2id cost an envelope gets where the password must hold against an offline attack
 * alone (docs/keys-and-signing.md, rule 2 and *Costs*; the owner's ruling of 2026-10-18): sized
 * by the device's memory, each tier about the same wall time, so a device short of memory pays in
 * passes what it cannot in memory. The device's own tier is where sealing starts ([forDevice] on
 * Android, [FULL] on the desktop); when Argon2id cannot have that memory at the moment, sealing
 * steps down to the next tier, never below [LEAST]'s 64 MiB, the floor. The hardware path's cost
 * (64 MiB, three passes) is not a tier: every guess needs the device there.
 */
enum class PasswordOnlyTier(internal val cost: Envelope.Cost) {
    /** 256 MiB, four passes: the full cost, a device with 4 GB of memory or more. */
    FULL(Envelope.Cost(opsLimit = 4, memLimitBytes = 256L * MIB)),

    /** 128 MiB, eight passes: a device with about 2 to 4 GB, or a low-RAM one. */
    REDUCED(Envelope.Cost(opsLimit = 8, memLimitBytes = 128L * MIB)),

    /** 64 MiB, sixteen passes: the floor's memory, for a device with less. */
    LEAST(Envelope.Cost(opsLimit = 16, memLimitBytes = 64L * MIB)),
    ;

    /** This tier, then each lighter one: the order sealing tries them in. */
    internal fun andLighter(): List<PasswordOnlyTier> = entries.drop(ordinal)

    companion object {
        /**
         * The tier of an Android device with [totalMemoryBytes] of memory
         * (`ActivityManager.MemoryInfo.totalMem`), [lowRamDevice] when the system says it is
         * one (`ActivityManager.isLowRamDevice`, Android Go). `totalMem` leaves out what the
         * kernel and the firmware reserve, so a phone sold with 4 GB reports some 3.5 to 3.8 GB
         * and one with 2 GB some 1.8: the thresholds sit below the nominal sizes, above the next
         * smaller ones (3 GB, 1.5 GB). A low-RAM device keeps its few hundred MiB of headroom for
         * the app in front, so it never gets the full 256 MiB.
         */
        fun forDevice(totalMemoryBytes: Long, lowRamDevice: Boolean): PasswordOnlyTier = when {
            totalMemoryBytes >= FOUR_GB_CLASS && !lowRamDevice -> FULL
            totalMemoryBytes >= TWO_GB_CLASS -> REDUCED
            else -> LEAST
        }

        /** What a device that reports 4 GB of memory has at least, as `totalMem` counts it. */
        private const val FOUR_GB_CLASS = 3_400_000_000L

        /** What a device that reports 2 GB of memory has at least, as `totalMem` counts it. */
        private const val TWO_GB_CLASS = 1_700_000_000L
    }
}

private const val MIB = 1024L * 1024
