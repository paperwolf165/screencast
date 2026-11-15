package net.prototux.aare.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastDisplayPlanTest {
    private val probe = """
        Physical size: 1080x2400
        Physical density: 420
        brightness=87
        brightness_mode=1
        auto_rotate=1
        user_rotation=0
        ignore_orientation=ignoreOrientationRequest false for displayId=0
    """.trimIndent()

    private val phone = CastDisplayPlan.parse(probe)!!

    private fun options(
        resize: Boolean = true,
        resolution: String = CastDisplayPlan.RES_HEAD_UNIT,
        customWidth: Int = 0,
        customHeight: Int = 0,
        dpi: Int = 0,
        lockOrientation: Boolean = true,
        blank: Boolean = true,
        uiScale: Int = 100,
        fontScale: Int = 100,
    ) = CastOptions(resize, resolution, customWidth, customHeight, dpi, lockOrientation, blank, uiScale, fontScale)

    @Test
    fun parsesAPlainPhone() {
        assertEquals(1080, phone.physicalWidth)
        assertEquals(2400, phone.physicalHeight)
        assertNull(phone.overrideSize)
        assertEquals(420, phone.physicalDensity)
        assertNull(phone.overrideDensity)
        assertEquals(87, phone.brightness)
        assertEquals(1, phone.brightnessMode)
        assertEquals(1, phone.autoRotate)
        assertEquals(0, phone.userRotation)
        assertFalse(phone.ignoreOrientationRequest)
    }

    @Test
    fun parsesOverridesAndUnsetSettings() {
        val s = CastDisplayPlan.parse(
            "Physical size: 1440x3120\nOverride size: 1080x2340\nPhysical density: 560\nOverride density: 480\n" +
                "brightness=null\nbrightness_mode=0\nauto_rotate=0\nuser_rotation=null\nignore_orientation=ignoreOrientationRequest true for displayId=0\n")!!
        assertEquals(1080 to 2340, s.overrideSize)
        assertEquals(480, s.overrideDensity)
        assertNull(s.brightness)
        assertNull(s.userRotation)
        assertTrue(s.ignoreOrientationRequest)
    }

    @Test
    fun aProbeThatDidNotRunIsRefused() {
        assertNull(CastDisplayPlan.parse("su: permission denied"))
    }

    @Test
    fun headUnitResolutionTakesTheCarsPixels() {
        // A wide 1920x720 car screen: the phone renders exactly that, turned to landscape.
        val plan = CastDisplayPlan.plan(phone, options(), 1920, 720)
        assertTrue(plan.apply.lines().first() == "input keyevent KEYCODE_WAKEUP")
        // `wm size` is in the phone's natural (portrait) orientation; the rotation turns it.
        assertTrue("wm size 720x1920" in plan.apply.lines())
        assertTrue("settings put system user_rotation 1" in plan.apply.lines())
        // 720 of 1080 on the short side: things keep their size at 280 dpi.
        assertTrue("wm density 280" in plan.apply.lines())
        assertTrue("settings put system screen_brightness 0" in plan.apply.lines())
    }

    @Test
    fun restorePutsBackWhatWasThere() {
        val restore = CastDisplayPlan.plan(phone, options(), 1920, 720).restore.lines()
        assertTrue("wm size reset" in restore)
        assertTrue("wm density reset" in restore)
        assertTrue("settings put system screen_brightness 87" in restore)
        assertTrue("settings put system screen_brightness_mode 1" in restore)
        assertTrue("settings put system accelerometer_rotation 1" in restore)
        assertTrue("settings put system user_rotation 0" in restore)
        // The light comes back before anything else.
        assertEquals("settings put system screen_brightness 87", restore.first())
    }

    @Test
    fun restoreKeepsTheUsersOwnOverrides() {
        val custom = phone.copy(overrideSize = 900 to 2000, overrideDensity = 360)
        val restore = CastDisplayPlan.plan(custom, options(), 1280, 720).restore.lines()
        assertTrue("wm size 900x2000" in restore)
        assertTrue("wm density 360" in restore)
    }

    @Test
    fun deviceResolutionFillsThePanelInTheCarsShape() {
        // 1920x720 is 8:3; the panel turned to landscape is 2400x1080, so the width is the limit.
        assertEquals(2400 to 900, CastDisplayPlan.castSize(phone, options(resolution = CastDisplayPlan.RES_DEVICE), 1920, 720))
        // 16:9 is limited by the panel's short side.
        assertEquals(1920 to 1080, CastDisplayPlan.castSize(phone, options(resolution = CastDisplayPlan.RES_DEVICE), 1280, 720))
    }

    @Test
    fun customResolutionAndDensityAreUsedAsGiven() {
        val plan = CastDisplayPlan.plan(phone, options(resolution = CastDisplayPlan.RES_CUSTOM, customWidth = 1600, customHeight = 600, dpi = 200), 1920, 720)
        assertTrue("wm size 600x1600" in plan.apply.lines())
        assertTrue("wm density 200" in plan.apply.lines())
    }

    @Test
    fun anUnsetCustomSizeFallsBackToTheHeadUnit() {
        assertEquals(1920 to 720, CastDisplayPlan.castSize(phone, options(resolution = CastDisplayPlan.RES_CUSTOM), 1920, 720))
    }

    @Test
    fun withoutTheRotationLockTheDisplayTakesTheCarsShapeItself() {
        val plan = CastDisplayPlan.plan(phone, options(lockOrientation = false), 1920, 720)
        assertTrue("wm size 1920x720" in plan.apply.lines())
        assertFalse(plan.apply.contains("user_rotation"))
        assertFalse(plan.restore.contains("user_rotation"))
    }

    @Test
    fun aPortraitHeadUnitKeepsThePhoneUpright() {
        val plan = CastDisplayPlan.plan(phone, options(), 1080, 1920)
        assertTrue("settings put system user_rotation 0" in plan.apply.lines())
        assertTrue("wm size 1080x1920" in plan.apply.lines())
    }

    @Test
    fun eachSwitchOnlyTouchesItsOwnSettings() {
        val blankOnly = CastDisplayPlan.plan(phone, options(resize = false, lockOrientation = false), 1920, 720)
        assertFalse(blankOnly.apply.contains("wm "))
        assertFalse(blankOnly.restore.contains("wm "))
        assertTrue(blankOnly.apply.contains("screen_brightness 0"))

        val resizeOnly = CastDisplayPlan.plan(phone, options(lockOrientation = false, blank = false), 1920, 720)
        assertFalse(resizeOnly.apply.contains("screen_brightness"))
        assertFalse(resizeOnly.restore.contains("screen_brightness"))
    }

    @Test
    fun aSmallerUiLowersTheDensity() {
        // 280 dpi keeps the phone's sizes on a 1920x720 screen; 75% of that fits a third more.
        val plan = CastDisplayPlan.plan(phone, options(uiScale = 75), 1920, 720)
        assertTrue("wm density 210" in plan.apply.lines())
        assertTrue("wm density reset" in plan.restore.lines())
    }

    @Test
    fun anExactDensityWinsOverTheUiSize() {
        val plan = CastDisplayPlan.plan(phone, options(uiScale = 75, dpi = 300), 1920, 720)
        assertTrue("wm density 300" in plan.apply.lines())
    }

    @Test
    fun theUiSizeWorksWithoutAResize() {
        val plan = CastDisplayPlan.plan(phone, options(resize = false, lockOrientation = false, blank = false, uiScale = 65), 1920, 720)
        assertTrue("wm density 273" in plan.apply.lines())
        assertFalse(plan.apply.contains("wm size"))
    }

    @Test
    fun textSizeIsSetAndPutBack() {
        val plan = CastDisplayPlan.plan(phone, options(fontScale = 75), 1920, 720)
        assertTrue("settings put system font_scale 0.75" in plan.apply.lines())
        // Never changed on this phone: back to Android's default.
        assertTrue("settings put system font_scale 1.0" in plan.restore.lines())

        val enlarged = CastDisplayPlan.parse(probe + "\nfont_scale=1.15")!!
        assertEquals("1.15", enlarged.fontScale)
        assertTrue("settings put system font_scale 1.15" in CastDisplayPlan.plan(enlarged, options(fontScale = 65), 1920, 720).restore.lines())
    }

    @Test
    fun textSizeAt100LeavesThePhonesSettingAlone() {
        val plan = CastDisplayPlan.plan(phone, options(), 1920, 720)
        assertFalse(plan.apply.contains("font_scale"))
        assertFalse(plan.restore.contains("font_scale"))
    }

    @Test
    fun everythingOffChangesNothing() {
        val o = options(resize = false, lockOrientation = false, blank = false)
        assertFalse(o.changesDisplay)
        val plan = CastDisplayPlan.plan(phone, o, 1920, 720)
        assertEquals("", plan.apply)
        assertEquals("", plan.restore)
    }
}
