package com.torve.presentation.transfer

/**
 * Single source of truth for credential-transfer UI copy.
 *
 * Every platform (desktop, Android mobile + TV, iOS) reads strings from
 * this object so a copy fix is one edit, not seven. The strings are
 * intentionally direct, in plain English, and explicitly call out the
 * device-flow direction (which device starts what).
 *
 * Tested by `TransferCopyTest` - those assertions guard against silent
 * regressions of phrasing the spec is explicit about.
 */
object TransferCopy {

    // Send screen

    /** Sender screen step 1 header. Mental model the user must form. */
    const val SEND_STEP1_HEADER: String = "Step 1 - Start on the device you want to set up"

    /** Sender screen step 1 explainer. */
    const val SEND_STEP1_EXPLAINER: String =
        "Open Torve on your TV/phone > Settings > Receive credentials. " +
            "It will show a QR code and a receiver code."

    /** Sender screen step 2 header. */
    const val SEND_STEP2_HEADER: String = "Step 2 - Choose what to send"

    /** Sender screen step 3 header. */
    const val SEND_STEP3_HEADER: String = "Step 3 - Generate and send"

    /** Renamed from "Receiver session string". */
    const val SEND_RECEIVER_FIELD_LABEL: String = "Receiver code from the other device"

    const val SEND_RECEIVER_FIELD_PLACEHOLDER: String =
        "Scan the QR code or paste the receiver code shown on the other device"

    /** Error shown when the user tries to generate without a receiver code. */
    const val SEND_RECEIVER_REQUIRED_ERROR: String =
        "Enter the receiver code shown on the other device first."

    /**
     * Error shown when the pasted code isn't a Torve receive code at all
     * (wrong prefix). Distinct from "corrupted" because the corrective
     * action is different - the user pasted the wrong thing.
     */
    const val SEND_RECEIVER_NOT_TORVE_ERROR: String =
        "This is not a Torve receive code. Open Receive credentials on " +
            "the other device and use the code it shows."

    /**
     * Error shown when the code shape is recognisably Torve's but the
     * payload is malformed (truncated paste, wrong-format key, bad
     * encoding, etc.). One unified message - no base64/JSON/key jargon
     * leaks into primary UI.
     */
    const val SEND_RECEIVER_CORRUPTED_ERROR: String =
        "The receiver code is corrupted. Ask the other device to " +
            "generate a fresh one and try again."

    /**
     * Error shown when the receive code's expiry has passed. Tells the
     * user exactly which device to act on.
     */
    const val SEND_RECEIVER_EXPIRED_ERROR: String =
        "The receiver code expired. Generate a new one on the " +
            "receiving device."

    /** Sender empty-state hint when the receiver field is blank. */
    const val SEND_RECEIVER_EMPTY_HINT: String =
        "No receiver code yet. Open Receive credentials on the device you are setting up."

    /** Relay-down fallback copy. */
    const val SEND_RELAY_UNAVAILABLE: String =
        "Automatic transfer is unavailable. You can still use manual sealed-code paste."

    /** Camera-permission denied copy on senders that have a camera. */
    const val SEND_CAMERA_DENIED: String =
        "Camera is blocked. Type the receiver code instead."

    /** Disclosure header for the encryption explainer. */
    const val SEND_PRIVACY_DISCLOSURE_HEADER: String = "How this stays private"

    /** Disclosure body. */
    const val SEND_PRIVACY_DISCLOSURE_BODY: String =
        "The bundle is sealed with a one-time key derived from the receiver " +
            "code. Torve servers cannot read the contents - they only relay " +
            "the sealed blob to your other device. You can also skip the " +
            "relay and paste the sealed code by hand."

    /** Header for the manual sealed-code paste fallback (Advanced). */
    const val SEND_ADVANCED_HEADER: String = "Advanced - manual sealed-code paste"

    // Receive screen

    /** Receive screen primary header. */
    const val RECEIVE_HEADER: String = "Receive credentials"

    /** Receive screen explainer (TV phrasing - pairs with the QR + paste code). */
    const val RECEIVE_PRIMARY_EXPLAINER_TV: String =
        "On your desktop or phone, open Send credentials and scan this code."

    /** Receive screen explainer (desktop / mobile). */
    const val RECEIVE_PRIMARY_EXPLAINER_DESKTOP: String =
        "On the device that already has these set up, open Send credentials " +
            "and scan this QR - or paste the receiver code below."

    /** Receiver-code label (renders next to the QR). */
    const val RECEIVE_SHORT_CODE_LABEL: String = "Receiver code"

    /** Disclosure header for the long sealed-code paste fallback. */
    const val RECEIVE_ADVANCED_HEADER: String = "Advanced - paste sealed code manually"
}
