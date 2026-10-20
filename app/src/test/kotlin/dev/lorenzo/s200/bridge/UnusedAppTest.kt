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
        assertTrue(UnusedApp.exempt(DISABLED))
        assertTrue(UnusedApp.exempt(FEATURE_NOT_AVAILABLE))
        listOf(API_30_BACKPORT, API_30, API_31, ERROR).forEach { assertFalse(UnusedApp.exempt(it)) }
    }

    @Test fun settingIsOfferedWhenTheFeatureExists() {
        listOf(API_30_BACKPORT, API_30, API_31, DISABLED).forEach { assertTrue(UnusedApp.canManage(it)) }
        assertFalse(UnusedApp.canManage(FEATURE_NOT_AVAILABLE))
        assertFalse(UnusedApp.canManage(ERROR))
    }

    @Test fun labelsNameTheRestriction() {
        assertEquals("off", UnusedApp.label(DISABLED))
        assertEquals("on (app paused if unused)", UnusedApp.label(API_31))
        assertEquals("unknown", UnusedApp.label(ERROR))
    }
}
