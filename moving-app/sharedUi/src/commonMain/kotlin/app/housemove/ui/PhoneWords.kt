package app.housemove.ui

import app.housemove.data.AppData

/**
 * The few words of the shared screens that differ between the phones (step 5 of design/ios-plan.md), given to them
 * through [Platform.words]. Android's ([ANDROID]) are the app's own words, exactly as they always were; the iPhone's
 * ([IPHONE]) say what is true on an iPhone, which has Photos rather than a gallery, its own Settings, and gets HouseMove
 * through TestFlight rather than as a file another phone sends.
 */
class PhoneWords private constructor(
    /** This phone is an iPhone. */
    val iphone: Boolean,
    /** "Add Sofa photo from gallery": where the photos already on the phone are. */
    val fromGallery: String,
    /** "Add a receipt from the gallery". */
    val fromTheGallery: String,
    /** Under "Allow camera", for when the phone does not ask again. */
    val allowCameraHint: String,
    /** Said when the phone's own settings could not be opened to allow the camera. */
    val cameraSettingsSteps: String,
    /** Who's helping: how a phone with no HouseMove, or an older one, gets it. */
    val getTheApp: String,
    /** Can this phone show the code to download HouseMove ([app.housemove.ui.screens.APP_DOWNLOAD_URL], Android's app)? */
    val downloadCode: Boolean,
) {
    companion object {
        val ANDROID = PhoneWords(
            iphone = false,
            fromGallery = "from gallery",
            fromTheGallery = "from the gallery",
            allowCameraHint = "If nothing happens, allow the camera for HouseMove in the phone's app settings.",
            cameraSettingsSteps = "Open Settings, then Apps, HouseMove, Permissions.",
            getTheApp = "No HouseMove on their phone? Send it this one's, or show a code to download it.",
            downloadCode = true,
        )

        val IPHONE = PhoneWords(
            iphone = true,
            fromGallery = "from Photos",
            fromTheGallery = "from Photos",
            allowCameraHint = "If nothing happens, switch on Camera for HouseMove in the iPhone's Settings.",
            cameraSettingsSteps = "Open Settings, then HouseMove, and switch on Camera.",
            getTheApp = "No HouseMove on their phone? An Android phone gets it from an Android phone that has it (Who's helping, " +
                "Share the app). $IPHONE_GETS_IT_SHORT",
            downloadCode = false,
        )
    }
}

/** Beside Android's "Share the app" and its download code: what neither does for an iPhone. */
const val IPHONE_GETS_IT = "An iPhone gets HouseMove through a TestFlight invite from the move's Organiser."

/** Under an invite or a Helper code: an iPhone joins through its Camera app, not a button in HouseMove. */
const val IPHONE_JOINS = "On an iPhone: point the Camera app here and tap Open in HouseMove."

private const val IPHONE_GETS_IT_SHORT = "An iPhone gets it through a TestFlight invite from the move's Organiser."

/**
 * Is [deviceName] an iPhone's? It is the name a phone gives itself when it joins or syncs (WifiSync's hello, kept on
 * its entry in the move): since iOS 16 an app is told only the model's name ("iPhone", in the simulator "iPhone 17
 * Pro"), never the one the owner chose; Android gives the owner's name for the phone, or its maker and model. Nothing
 * more reliable travels between the phones, and nothing new is sent for it.
 */
fun isIphoneName(deviceName: String): Boolean = Regex("\\biP(hone|ad)\\b").containsMatchIn(deviceName)

/** Does the move have a phone that is an iPhone ([isIphoneName])? */
fun AppData.hasIphone(): Boolean = people.any { p -> p.phones.any { isIphoneName(it.name) } }
