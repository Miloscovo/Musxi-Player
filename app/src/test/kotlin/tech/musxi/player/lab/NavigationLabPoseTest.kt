package tech.musxi.player.lab

import androidx.activity.BackEventCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The transition maths of the navigation lab: one exit value drives both pages. */
class NavigationLabPoseTest {
    private val eps = 1e-5f

    @Test fun followIsIdentityAtRestAndFullWidthWhenGone() {
        val rest = LabPose.foreground(0f, BackStyle.FOLLOW, 1f)
        assertEquals(0f, rest.translationX, eps)
        assertEquals(1f, rest.scale, eps)
        assertEquals(0f, rest.cornerDp, eps)
        assertEquals(1f, LabPose.foreground(1f, BackStyle.FOLLOW, 1f).translationX, eps)
    }

    @Test fun followNeverMovesLeftOfRestEvenWhenTheSpringUndershoots() {
        assertEquals(0f, LabPose.foreground(-0.02f, BackStyle.FOLLOW, 1f).translationX, eps)
        assertEquals(0f, LabPose.foreground(-0.02f, BackStyle.SHRINK, 1f).translationX, eps)
    }

    @Test fun followAlwaysLeavesToTheRightSoThePageBelowIsNeverUncovered() {
        assertEquals(0.5f, LabPose.foreground(0.5f, BackStyle.FOLLOW, -1f).translationX, eps)
    }

    @Test fun underlaySitsThirtyPercentLeftAndTenPercentDarkWhilePushed() {
        val pose = LabPose.underlay(0f, BackStyle.FOLLOW)
        assertEquals(-0.3f, pose.shiftX, eps)
        assertEquals(0.1f, pose.dim, eps)
    }

    @Test fun underlayIsHomeAndClearWhenTheForegroundIsGone() {
        val pose = LabPose.underlay(1f, BackStyle.FOLLOW)
        assertEquals(0f, pose.shiftX, eps)
        assertEquals(0f, pose.dim, eps)
    }

    @Test fun underlayFollowsTheForegroundLinearly() {
        val pose = LabPose.underlay(0.5f, BackStyle.FOLLOW)
        assertEquals(-0.15f, pose.shiftX, eps)
        assertEquals(0.05f, pose.dim, eps)
    }

    @Test fun underlayIgnoresSpringOvershoot() {
        assertEquals(LabPose.underlay(1f, BackStyle.FOLLOW), LabPose.underlay(1.03f, BackStyle.FOLLOW))
        assertEquals(LabPose.underlay(0f, BackStyle.FOLLOW), LabPose.underlay(-0.03f, BackStyle.FOLLOW))
    }

    @Test fun shrinkReachesScaleCornersAndDriftAtTheEndOfThePhase() {
        val pose = LabPose.foreground(LabPose.ShrinkPhase, BackStyle.SHRINK, 1f)
        assertEquals(0.9f, pose.scale, eps)
        assertEquals(28f, pose.cornerDp, eps)
        assertEquals(LabPose.ShrinkDrift, pose.translationX, eps)
        assertEquals(1f, pose.lift, eps)
    }

    @Test fun shrinkSlidesFullyOutAfterTheRelease() {
        val pose = LabPose.foreground(1f, BackStyle.SHRINK, 1f)
        assertEquals(1f, pose.translationX, eps)
        assertEquals(0f, pose.lift, eps)
    }

    @Test fun shrinkMirrorsForTheRightEdge() {
        val left = LabPose.foreground(0.7f, BackStyle.SHRINK, 1f)
        val right = LabPose.foreground(0.7f, BackStyle.SHRINK, -1f)
        assertEquals(left.translationX, -right.translationX, eps)
        assertEquals(left.scale, right.scale, eps)
    }

    @Test fun shrinkHasNoJumpAtThePhaseBoundary() {
        val before = LabPose.foreground(LabPose.ShrinkPhase - 0.001f, BackStyle.SHRINK, 1f)
        val after = LabPose.foreground(LabPose.ShrinkPhase + 0.001f, BackStyle.SHRINK, 1f)
        assertTrue(kotlin.math.abs(before.translationX - after.translationX) < 0.01f)
        assertTrue(kotlin.math.abs(before.scale - after.scale) < 0.01f)
    }

    @Test fun shrinkBringsThePageBelowHomeWithinHalfThePhase() {
        assertEquals(0f, LabPose.underlay(LabPose.ShrinkPhase * 0.5f, BackStyle.SHRINK).shiftX, eps)
        assertEquals(-0.15f, LabPose.underlay(LabPose.ShrinkPhase * 0.25f, BackStyle.SHRINK).shiftX, eps)
    }

    @Test fun followGestureMapsProgressStraightToExit() {
        assertEquals(0.5f, LabPose.exitAt(BackStyle.FOLLOW, 0f, 0.5f), eps)
        assertEquals(1f, LabPose.exitAt(BackStyle.FOLLOW, 0f, 1f), eps)
    }

    @Test fun shrinkGestureStopsAtThePhase() {
        assertEquals(LabPose.ShrinkPhase, LabPose.exitAt(BackStyle.SHRINK, 0f, 1f), eps)
    }

    @Test fun grabbingATransitionContinuesFromWhereItIs() {
        assertEquals(0.65f, LabPose.exitAt(BackStyle.FOLLOW, 0.3f, 0.5f), eps)
        // Already past the shrink phase (a commit being grabbed): the page stays put instead of jumping back.
        assertEquals(0.7f, LabPose.exitAt(BackStyle.SHRINK, 0.7f, 0.5f), eps)
        assertEquals(0.3f, LabPose.exitAt(BackStyle.FOLLOW, 0.3f, 0f), eps)
    }

    @Test fun swipeEdgeSetsTheDirection() {
        assertEquals(1f, LabPose.directionFor(BackEventCompat.EDGE_LEFT), eps)
        assertEquals(-1f, LabPose.directionFor(BackEventCompat.EDGE_RIGHT), eps)
        assertEquals(1f, LabPose.directionFor(BackEventCompat.EDGE_NONE), eps)
    }
}
