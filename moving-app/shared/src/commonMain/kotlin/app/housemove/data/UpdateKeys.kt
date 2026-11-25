package app.housemove.data

/*
 * What the shared code needs of the update-file crypto (step 1 of design/ios-plan.md).
 *
 * The crypto itself (the phone's EC key pair in Keys.kt, the sealing in Sealed.kt, the update files in Update.kt) is
 * built only for Android and the JVM: the iPhone v1 has no update files and declares no non-exempt encryption. The
 * shared code sees it only through [updateCrypto], which is null on the iPhone.
 */

/** A phone's key pair for update files, as the shared code sees it: the public half as it travels, and when it was made. */
interface UpdateKeyPair {
    /** The public key as it travels: X.509, Base64 on one line. Empty when the phone has none. */
    val publicText: String
    val madeAt: Long
}

/** The key pair of a phone that cannot read update files (the iPhone v1): none. */
internal object NoUpdateKeys : UpdateKeyPair {
    override val publicText: String = ""
    override val madeAt: Long = 0
}

/** The update-file crypto, where this platform has it. */
internal interface UpdateCrypto {
    /** This phone's key pair, kept in [file] (made and written there the first time), or made afresh when [file] is null. */
    fun keysIn(file: File?): UpdateKeyPair

    /** Is [text] a public key as it travels ([UpdateKeyPair.publicText])? */
    fun isPublicKey(text: String): Boolean
}

/** Keys.kt's on Android and the JVM; null on the iPhone. */
internal expect val updateCrypto: UpdateCrypto?
