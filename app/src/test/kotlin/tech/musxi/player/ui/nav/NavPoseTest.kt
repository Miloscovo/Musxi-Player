package tech.musxi.player.ui.nav

import androidx.activity.BackEventCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.player.ui.theme.Motion
import kotlin.math.abs

/** The transition maths: one exit value drives both pages. */
class NavPoseTest {
    private val eps = 1e-5f

    @Test fun pushStartsOffScreenAndSettlesAtRest() {
        assertEquals(1f, NavPose.exitForPush(0f), eps)
        assertEquals(0.5f, NavPose.exitForPush(0.5f), eps)
        assertEquals(0f, NavPose.exitForPush(1f), eps)
        assertEquals(0f, NavPose.exitForPush(1.2f), eps)
    }

    @Test fun pushedPageSlidesInFromTheFullWidth() {
        assertEquals(1f, NavPose.foreground(NavPose.exitForPush(0f), BackStyle.FOLLOW, 1f).translationX, eps)
        assertEquals(0.5f, NavPose.foreground(NavPose.exitForPush(0.5f), BackStyle.FOLLOW, 1f).translationX, eps)
        assertEquals(0f, NavPose.foreground(NavPose.exitForPush(1f), BackStyle.FOLLOW, 1f).translationX, eps)
    }

    @Test fun followIsIdentityAtRest() {
        val rest = NavPose.foreground(0f, BackStyle.FOLLOW, 1f)
        assertEquals(0f, rest.translationX, eps)
        assertEquals(1f, rest.scale, eps)
        assertEquals(0f, rest.cornerDp, eps)
        assertEquals(0f, rest.lift, eps)
    }

    @Test fun edgeShadowShowsOnlyWhileThePageIsMoving() {
        assertEquals(0f, NavPose.foreground(0f, BackStyle.FOLLOW, 1f).edgeShadow, eps)
        assertEquals(1f, NavPose.foreground(0.01f, BackStyle.FOLLOW, 1f).edgeShadow, eps)
        assertEquals(1f, NavPose.foreground(1f, BackStyle.FOLLOW, 1f).edgeShadow, eps)
        assertEquals(0f, NavPose.foreground(0.5f, BackStyle.SHRINK, 1f).edgeShadow, eps)
    }

    @Test fun edgeShadowMatchesTheSpec() {
        assertEquals(0.04f, NavPose.EdgeShadowAlpha, eps)
        assertEquals(12f, NavPose.EdgeShadowWidthDp, eps)
    }

    @Test fun followNeverMovesLeftOfRestEvenWhenTheSpringUndershoots() {
        assertEquals(0f, NavPose.foreground(-0.02f, BackStyle.FOLLOW, 1f).translationX, eps)
        assertEquals(0f, NavPose.foreground(-0.02f, BackStyle.SHRINK, 1f).translationX, eps)
    }

    @Test fun followAlwaysLeavesToTheRightSoThePageBelowIsNeverUncovered() {
        assertEquals(0.5f, NavPose.foreground(0.5f, BackStyle.FOLLOW, -1f).translationX, eps)
    }

    @Test fun underlaySitsAtTheTokenShiftAndDimWhilePushed() {
        val pose = NavPose.underlay(0f, BackStyle.FOLLOW)
        assertEquals(-Motion.PushUnderlayShift, pose.shiftX, eps)
        assertEquals(Motion.PushUnderlayDim, pose.dim, eps)
        // The numbers in the spec: 30% to the left, black at alpha 0.10.
        assertEquals(-0.3f, pose.shiftX, eps)
        assertEquals(0.1f, pose.dim, eps)
    }

    @Test fun underlayIsHomeAndClearWhenTheForegroundIsGone() {
        val pose = NavPose.underlay(1f, BackStyle.FOLLOW)
        assertEquals(0f, pose.shiftX, eps)
        assertEquals(0f, pose.dim, eps)
    }

    @Test fun underlayFollowsTheForegroundLinearly() {
        val pose = NavPose.underlay(0.5f, BackStyle.FOLLOW)
        assertEquals(-0.15f, pose.shiftX, eps)
        assertEquals(0.05f, pose.dim, eps)
    }

    @Test fun underlayIgnoresSpringOvershoot() {
        assertEquals(NavPose.underlay(1f, BackStyle.FOLLOW), NavPose.underlay(1.03f, BackStyle.FOLLOW))
        assertEquals(NavPose.underlay(0f, BackStyle.FOLLOW), NavPose.underlay(-0.03f, BackStyle.FOLLOW))
    }

    @Test fun shrinkReachesScaleCornersAndDriftAtTheEndOfThePhase() {
        val pose = NavPose.foreground(NavPose.ShrinkPhase, BackStyle.SHRINK, 1f)
        assertEquals(0.9f, pose.scale, eps)
        assertEquals(28f, pose.cornerDp, eps)
        assertEquals(NavPose.ShrinkDrift, pose.translationX, eps)
        assertEquals(1f, pose.lift, eps)
    }

    @Test fun shrinkSlidesFullyOutAfterTheRelease() {
        val pose = NavPose.foreground(1f, BackStyle.SHRINK, 1f)
        assertEquals(1f, pose.translationX, eps)
        assertEquals(0f, pose.lift, eps)
    }

    @Test fun shrinkMirrorsForTheRightEdge() {
        val left = NavPose.foreground(0.7f, BackStyle.SHRINK, 1f)
        val right = NavPose.foreground(0.7f, BackStyle.SHRINK, -1f)
        assertEquals(left.translationX, -right.translationX, eps)
        assertEquals(left.scale, right.scale, eps)
    }

    @Test fun shrinkHasNoJumpAtThePhaseBoundary() {
        val before = NavPose.foreground(NavPose.ShrinkPhase - 0.001f, BackStyle.SHRINK, 1f)
        val after = NavPose.foreground(NavPose.ShrinkPhase + 0.001f, BackStyle.SHRINK, 1f)
        assertTrue(abs(before.translationX - after.translationX) < 0.01f)
        assertTrue(abs(before.scale - after.scale) < 0.01f)
    }

    @Test fun shrinkBringsThePageBelowHomeWithinHalfThePhase() {
        assertEquals(0f, NavPose.underlay(NavPose.ShrinkPhase * 0.5f, BackStyle.SHRINK).shiftX, eps)
        assertEquals(-0.15f, NavPose.underlay(NavPose.ShrinkPhase * 0.25f, BackStyle.SHRINK).shiftX, eps)
    }

    @Test fun followGestureMapsProgressStraightToExit() {
        assertEquals(0.5f, NavPose.exitAt(BackStyle.FOLLOW, 0f, 0.5f), eps)
        assertEquals(1f, NavPose.exitAt(BackStyle.FOLLOW, 0f, 1f), eps)
    }

    @Test fun shrinkGestureStopsAtThePhase() {
        assertEquals(NavPose.ShrinkPhase, NavPose.exitAt(BackStyle.SHRINK, 0f, 1f), eps)
    }

    @Test fun grabbingATransitionContinuesFromWhereItIs() {
        assertEquals(0.65f, NavPose.exitAt(BackStyle.FOLLOW, 0.3f, 0.5f), eps)
        // Already past the shrink phase (a commit being grabbed): the page stays put instead of jumping back.
        assertEquals(0.7f, NavPose.exitAt(BackStyle.SHRINK, 0.7f, 0.5f), eps)
        assertEquals(0.3f, NavPose.exitAt(BackStyle.FOLLOW, 0.3f, 0f), eps)
    }

    @Test fun gestureProgressOutsideZeroToOneIsClamped() {
        assertEquals(0f, NavPose.exitAt(BackStyle.FOLLOW, 0f, -0.3f), eps)
        assertEquals(1f, NavPose.exitAt(BackStyle.FOLLOW, 0f, 1.4f), eps)
    }

    @Test fun exitVelocityScalesWithTheDistanceTheGestureCovers() {
        assertEquals(1f, NavPose.exitPerProgress(BackStyle.FOLLOW, 0f), eps)
        assertEquals(NavPose.ShrinkPhase, NavPose.exitPerProgress(BackStyle.SHRINK, 0f), eps)
        assertEquals(0.7f, NavPose.exitPerProgress(BackStyle.FOLLOW, 0.3f), eps)
        assertEquals(0f, NavPose.exitPerProgress(BackStyle.SHRINK, 0.7f), eps)
    }

    @Test fun swipeEdgeSetsTheDirection() {
        assertEquals(1f, NavPose.directionFor(BackEventCompat.EDGE_LEFT), eps)
        assertEquals(-1f, NavPose.directionFor(BackEventCompat.EDGE_RIGHT), eps)
        assertEquals(1f, NavPose.directionFor(BackEventCompat.EDGE_NONE), eps)
    }

    @Test fun reselectGoesBackWhenDeepAndScrollsAtTheRoot() {
        assertEquals(ReselectAction.SCROLL_TO_TOP, reselectActionFor(1))
        assertEquals(ReselectAction.POP_TO_ROOT, reselectActionFor(2))
        assertEquals(ReselectAction.POP_TO_ROOT, reselectActionFor(3))
    }
}
