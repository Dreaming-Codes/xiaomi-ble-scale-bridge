package dev.lorenzo.s200.bridge

import androidx.core.content.UnusedAppRestrictionsConstants.API_30
import androidx.core.content.UnusedAppRestrictionsConstants.API_30_BACKPORT
import androidx.core.content.UnusedAppRestrictionsConstants.API_31
import androidx.core.content.UnusedAppRestrictionsConstants.DISABLED
import androidx.core.content.UnusedAppRestrictionsConstants.ERROR
import androidx.core.content.UnusedAppRestrictionsConstants.FEATURE_NOT_AVAILABLE
import kotlin.test.*

class UnusedAppTest {
    @Test fun onlyDisabledOrUnavailableCountsAsExempt() {
        assertTrue(UnusedApp.of(DISABLED).exempt)
        assertTrue(UnusedApp.of(FEATURE_NOT_AVAILABLE).exempt)
        listOf(API_30_BACKPORT, API_30, API_31, ERROR).forEach { assertFalse(UnusedApp.of(it).exempt) }
    }

    @Test fun settingIsOfferedWhenTheFeatureExists() {
        listOf(API_30_BACKPORT, API_30, API_31, DISABLED).forEach { assertTrue(UnusedApp.of(it).canManage) }
        assertFalse(UnusedApp.of(FEATURE_NOT_AVAILABLE).canManage)
        assertFalse(UnusedApp.of(ERROR).canManage)
    }

    @Test fun labelsNameTheRestriction() {
        assertEquals("off", UnusedApp.of(DISABLED).label)
        assertEquals("not on this device", UnusedApp.of(FEATURE_NOT_AVAILABLE).label)
        assertEquals("on (permission revocation only)", UnusedApp.of(API_30_BACKPORT).label)
        assertEquals("on (permissions removed if unused)", UnusedApp.of(API_30).label)
        assertEquals("on (app paused if unused)", UnusedApp.of(API_31).label)
        assertEquals("unknown", UnusedApp.of(ERROR).label)
        assertEquals("unknown", UnusedApp.of(-1).label)
    }
}
